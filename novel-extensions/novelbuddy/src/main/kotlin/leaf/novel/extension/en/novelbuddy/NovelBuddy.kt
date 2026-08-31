package leaf.novel.extension.en.novelbuddy

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import leaf.novel.api.NovelChapterContent
import leaf.novel.api.NovelHttpSource
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/**
 * NovelBuddy, which is where madnovel.com now redirects.
 *
 * A Next.js site, so nothing here scrapes rendered markup. Every page ships its data as JSON in the
 * `__NEXT_DATA__` script element, and reading that is both simpler and far less brittle than parsing
 * the hydrated DOM — listings, details and the chapter body all come from it.
 *
 * The one exception is the chapter list, which is served by the site's own API and returns every
 * chapter in a single response rather than paginating.
 */
class NovelBuddy : NovelHttpSource() {

    override val baseUrl = "https://novelbuddy.me"

    private val apiUrl = "https://api.novelbuddy.me"

    override val name = "NovelBuddy"

    override val lang = "en"

    override val supportsLatest = true

    /** The chapter list comes from a second host, which needs its own budget. */
    override val client: OkHttpClient by lazy {
        super.client.newBuilder()
            .rateLimitHost(apiUrl.toHttpUrl(), permits = requestsPerSecond, period = 1.seconds)
            .build()
    }

    private val json = Json { ignoreUnknownKeys = true }

    // ------------------------------------------------------------------------------------------
    // Browse
    // ------------------------------------------------------------------------------------------

    // Each listing route names its payload differently; the item shape is identical across all three.
    override suspend fun getPopularManga(page: Int): MangasPage =
        pageProps("$baseUrl/ranking?page=$page").toMangasPage("initialItems", "initialPagination")

    override suspend fun getLatestUpdates(page: Int): MangasPage =
        pageProps("$baseUrl/latest?page=$page").toMangasPage("items", "pagination")

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isBlank() && filters.none { it.isActive() }) return getPopularManga(page)

        val mtlMode = filters.filterIsInstance<MtlFilter>().firstOrNull()?.selectedValue().orEmpty()
        val url = "$baseUrl/search".toHttpUrl().newBuilder().apply {
            query.trim().takeIf { it.isNotEmpty() }?.let { addQueryParameter("q", it) }

            filters.forEach { filter ->
                when (filter) {
                    is GenreFilter -> {
                        filter.state.filter(Genre::isIncluded)
                            .takeIf { it.isNotEmpty() }
                            ?.let { addQueryParameter("genres", it.joinToString(",", transform = Genre::value)) }
                        filter.state.filter(Genre::isExcluded)
                            .takeIf { it.isNotEmpty() }
                            ?.let { addQueryParameter("exclude", it.joinToString(",", transform = Genre::value)) }
                    }
                    is StatusFilter -> filter.selectedValue()
                        .takeIf { it.isNotEmpty() }
                        ?.let { addQueryParameter("status", it) }
                    is MtlFilter -> if (filter.selectedValue() == "include") {
                        addQueryParameter("mtl", "true")
                    }
                    is SortFilter -> filter.selectedValue()
                        .takeUnless { it == "best_match" }
                        ?.let { addQueryParameter("sort", it) }
                    is AuthorFilter -> filter.state.trim()
                        .takeIf { it.isNotEmpty() }
                        ?.let { addQueryParameter("author", it) }
                    is MinChaptersFilter -> filter.state.toIntOrNull()
                        ?.takeIf { it > 0 }
                        ?.let { addQueryParameter("min_ch", it.toString()) }
                    else -> Unit
                }
            }

            if (page > 1) addQueryParameter("page", page.toString())
        }.build()

        return pageProps(url.toString())
            .toMangasPage("ssrItems", "ssrPagination", excludeMtl = mtlMode == "exclude")
    }

    private fun Filter<*>.isActive(): Boolean = when (this) {
        is GenreFilter -> state.any { it.state != Filter.TriState.STATE_IGNORE }
        is StatusFilter -> selectedValue().isNotEmpty()
        is MtlFilter -> selectedValue().isNotEmpty()
        is SortFilter -> selectedValue() != "best_match"
        is AuthorFilter -> state.isNotBlank()
        is MinChaptersFilter -> state.toIntOrNull()?.let { it > 0 } == true
        else -> false
    }

    override fun getFilterList() = FilterList(
        MtlFilter(),
        GenreFilter(),
        StatusFilter(),
        SortFilter(),
        AuthorFilter(),
        MinChaptersFilter(),
    )

    private fun JsonObject.toMangasPage(
        itemsKey: String,
        paginationKey: String,
        excludeMtl: Boolean = false,
    ): MangasPage {
        val novels = array(itemsKey).orEmpty()
            .mapNotNull { it as? JsonObject }
            .filterNot { excludeMtl && it.boolean("isMtl") == true }
            .map { it.toSManga() }
        val hasNext = (obj(paginationKey)?.get("has_next") as? JsonPrimitive)?.booleanOrNull ?: false
        return MangasPage(novels, hasNext)
    }

    private fun JsonObject.toSManga(): SManga = SManga.create().apply {
        url = string("url") ?: "/${string("slug").orEmpty()}"
        title = string("name").orEmpty()
        thumbnail_url = string("cover")
    }

    // ------------------------------------------------------------------------------------------
    // Details and chapters
    // ------------------------------------------------------------------------------------------

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        if (!fetchDetails && !fetchChapters) return SMangaUpdate(manga, chapters)

        val details = pageProps(baseUrl + manga.url).obj("initialManga")
            ?: return SMangaUpdate(manga, chapters)

        if (fetchDetails) details.fillDetails(manga)
        val updated = if (fetchChapters) fetchChapterList(details) else chapters

        return SMangaUpdate(manga, updated)
    }

    private fun JsonObject.fillDetails(manga: SManga) {
        string("name")?.takeIf { it.isNotBlank() }?.let { manga.title = it }
        manga.thumbnail_url = string("cover")
        manga.author = names("authors")
        manga.genre = names("genres")
        // The summary is a fragment rather than plain text, and Mihon renders descriptions as text.
        manga.description = string("summary")?.let { Jsoup.parseBodyFragment(it).text() }

        val status = string("status").orEmpty()
        manga.status = when {
            status.contains("complet", ignoreCase = true) -> SManga.COMPLETED
            status.contains("ongoing", ignoreCase = true) -> SManga.ONGOING
            status.contains("hiatus", ignoreCase = true) -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
        manga.initialized = true
    }

    /**
     * One request for the whole list — the API does not paginate, and already returns newest-first,
     * which is the order `SyncChaptersWithSource` expects.
     */
    private suspend fun fetchChapterList(details: JsonObject): List<SChapter> {
        val id = details.string("id") ?: return emptyList()
        val cv = details.string("cv").orEmpty()

        val body = client.newCall(GET("$apiUrl/titles/$id/chapters?cv=$cv", headers))
            .awaitSuccess()
            .use { it.body.string() }

        val payload = json.parseToJsonElement(body) as? JsonObject ?: return emptyList()

        return payload.obj("data")?.array("chapters").orEmpty()
            .mapNotNull { element ->
                val chapter = element as? JsonObject ?: return@mapNotNull null
                SChapter.create().apply {
                    url = chapter.string("url").orEmpty()
                    name = chapter.string("name").orEmpty()
                    chapter_number = chapter.string("number")?.toFloatOrNull() ?: -1f
                    date_upload = (chapter.string("updated_at") ?: chapter.string("updatedAt"))
                        ?.toEpochMillis()
                        ?: 0L
                }
            }
    }

    // ------------------------------------------------------------------------------------------
    // Chapter content
    // ------------------------------------------------------------------------------------------

    override suspend fun getChapterContent(chapter: SChapter): NovelChapterContent {
        val chapterUrl = baseUrl + chapter.url
        val props = pageProps(chapterUrl)
        val body = props.obj("initialChapter")

        val html = body?.string("content")
            ?: throw Exception("Chapter body not found for ${chapter.url}")

        // The body arrives as a fragment, so the site furniture is stripped from the fragment itself
        // rather than from a surrounding page.
        val fragment = Jsoup.parseBodyFragment(html, chapterUrl)
        fragment.body().select(JUNK).remove()

        return NovelChapterContent(
            html = fragment.body().html(),
            baseUrl = chapterUrl,
            title = body.string("name") ?: chapter.name,
        )
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    /** The `pageProps` object Next.js embeds in every server-rendered page. */
    private suspend fun pageProps(url: String): JsonObject {
        val document = client.newCall(GET(url, headers)).awaitSuccess().use { it.asJsoup() }
        val raw = document.getElementById("__NEXT_DATA__")?.data()
            ?: throw Exception("Page data not found at $url")

        return (json.parseToJsonElement(raw) as? JsonObject)
            ?.obj("props")
            ?.obj("pageProps")
            ?: throw Exception("Unexpected page data at $url")
    }

    /**
     * Reads a string field defensively. `jsonPrimitive` throws when the value is an object or an
     * array, which would take down a whole browse or details call over one unexpected field, so the
     * cast is checked instead. JSON null arrives as the literal `"null"`, and is treated as absent.
     */
    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() && it != "null" }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

    private fun JsonObject.boolean(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull

    /** Joins the `name` of each entry in a `[{name, …}]` array, as used for authors and genres. */
    private fun JsonObject.names(key: String): String? =
        array(key)
            ?.mapNotNull { (it as? JsonObject)?.string("name") }
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString()

    private fun String.toEpochMillis(): Long = runCatching { Instant.parse(this).toEpochMilli() }
        .getOrDefault(0L)

    private data class Option(val label: String, val value: String) {
        override fun toString() = label
    }

    private class Genre(name: String, val value: String) : Filter.TriState(name)

    private class GenreFilter : Filter.Group<Genre>(
        "Genres",
        GENRES.map { Genre(it.label, it.value) },
    )

    private class StatusFilter : Filter.Select<Option>("Status", STATUS) {
        fun selectedValue() = values[state].value
    }

    private class MtlFilter : Filter.Select<Option>("MTL", MTL) {
        fun selectedValue() = values[state].value
    }

    private class SortFilter : Filter.Select<Option>("Sort", SORT) {
        fun selectedValue() = values[state].value
    }

    private class AuthorFilter : Filter.Text("Author")

    private class MinChaptersFilter : Filter.Text("Minimum chapters")

    private companion object {
        /** Ad slots and spacing wrappers the site injects into the chapter fragment. */
        const val JUNK = "script, ins, iframe, noscript, .ads-banner, .my-4"

        val STATUS = arrayOf(
            Option("Any", ""),
            Option("Ongoing", "ongoing"),
            Option("Completed", "completed"),
            Option("Hiatus", "hiatus"),
            Option("Cancelled", "cancelled"),
        )

        val MTL = arrayOf(
            Option("Any", ""),
            Option("MTL only", "include"),
            Option("Exclude MTL", "exclude"),
        )

        val SORT = arrayOf(
            Option("Best match", "best_match"),
            Option("Latest updated", "latest"),
            Option("Recently added", "newest"),
            Option("Most followed", "popular"),
            Option("Highest rating", "rating"),
            Option("Most viewed: today", "views_today"),
            Option("Most viewed: 7 days", "views_7days"),
            Option("Most viewed: 30 days", "views_30days"),
            Option("Most viewed: all time", "views"),
            Option("Most chapters", "chapters"),
            Option("A-Z", "alphabetical"),
        )

        val GENRES = listOf(
            Option("Action", "action"),
            Option("Adult", "adult"),
            Option("Adventure", "adventure"),
            Option("Comedy", "comedy"),
            Option("Drama", "drama"),
            Option("Eastern", "eastern"),
            Option("Ecchi", "ecchi"),
            Option("Fan-Fiction", "fan-fiction"),
            Option("Fantasy", "fantasy"),
            Option("Game", "game"),
            Option("Gender Bender", "gender-bender"),
            Option("Harem", "harem"),
            Option("Historical", "historical"),
            Option("Horror", "horror"),
            Option("Josei", "josei"),
            Option("Martial Arts", "martial-arts"),
            Option("Mature", "mature"),
            Option("Mecha", "mecha"),
            Option("Military", "military"),
            Option("Modern Life", "modern-life"),
            Option("Mystery", "mystery"),
            Option("Psychological", "psychological"),
            Option("Reincarnation", "reincarnation"),
            Option("Romance", "romance"),
            Option("School Life", "school-life"),
            Option("Sci-fi", "sci-fi"),
            Option("Seinen", "seinen"),
            Option("Shoujo", "shoujo"),
            Option("Shoujo Ai", "shoujo-ai"),
            Option("Shounen", "shounen"),
            Option("Shounen Ai", "shounen-ai"),
            Option("Slice of Life", "slice-of-life"),
            Option("Smut", "smut"),
            Option("Sports", "sports"),
            Option("Supernatural", "supernatural"),
            Option("System", "system"),
            Option("Tragedy", "tragedy"),
            Option("Urban", "urban"),
            Option("Urban Life", "urban-life"),
            Option("Wuxia", "wuxia"),
            Option("Xianxia", "xianxia"),
            Option("Xuanhuan", "xuanhuan"),
            Option("Yaoi", "yaoi"),
            Option("Yuri", "yuri"),
        )
    }
}
