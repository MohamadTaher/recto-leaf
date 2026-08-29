package leaf.novel.extension.en.novelbuddy

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
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
import java.net.URLEncoder
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
        if (query.isBlank()) return getPopularManga(page)

        val encoded = URLEncoder.encode(query, "UTF-8")
        return pageProps("$baseUrl/search?q=$encoded&page=$page")
            .toMangasPage("ssrItems", "ssrPagination")
    }

    private fun JsonObject.toMangasPage(itemsKey: String, paginationKey: String): MangasPage {
        val novels = array(itemsKey).orEmpty()
            .mapNotNull { (it as? JsonObject)?.toSManga() }
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

    /** Joins the `name` of each entry in a `[{name, …}]` array, as used for authors and genres. */
    private fun JsonObject.names(key: String): String? =
        array(key)
            ?.mapNotNull { (it as? JsonObject)?.string("name") }
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString()

    private companion object {
        /** Ad slots and spacing wrappers the site injects into the chapter fragment. */
        const val JUNK = "script, ins, iframe, noscript, .ads-banner, .my-4"
    }
}
