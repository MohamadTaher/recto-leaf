package leaf.novel.extension.en.freewebnovel

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import leaf.novel.api.NovelChapterContent
import leaf.novel.api.NovelHttpSource
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * freewebnovel.com.
 *
 * Listings, details and chapter bodies are ordinary server-rendered HTML. The table of contents is
 * the one exception: it is paginated behind a small JSON endpoint that reports its own page count,
 * which is both cheaper and more reliable than scraping the page picker.
 *
 * The site refuses requests that do not look like a browser, which is why everything goes through
 * the app's client — it carries the real user agent, the Cloudflare handling and the cookie jar.
 */
class FreeWebNovel : NovelHttpSource() {

    override val baseUrl = "https://freewebnovel.com"

    override val name = "FreeWebNovel"

    override val lang = "en"

    override val supportsLatest = true

    private val json = Json { ignoreUnknownKeys = true }

    private val chapterCache = object : LinkedHashMap<String, NovelChapterContent>(CHAPTER_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, NovelChapterContent>) =
            size > CHAPTER_CACHE_SIZE
    }

    // ------------------------------------------------------------------------------------------
    // Browse
    // ------------------------------------------------------------------------------------------

    override suspend fun getPopularManga(page: Int): MangasPage = listing("sort/most-popular", page)

    override suspend fun getLatestUpdates(page: Int): MangasPage = listing("sort/latest-release", page)

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isBlank()) {
            val path = filters.filterIsInstance<BrowseFilter>().firstOrNull()?.selectedPath()
                ?: "sort/most-popular"
            return listing(path, page)
        }

        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$baseUrl/search?keyword=$encoded".let { if (page > 1) "$it&page=$page" else it }
        return document(url).toMangasPage()
    }

    override fun getFilterList() = FilterList(
        Filter.Header("Ignored when searching by title"),
        BrowseFilter(),
    )

    /** Page one is the bare path; later pages append the number. */
    private suspend fun listing(path: String, page: Int): MangasPage {
        val url = "$baseUrl/$path".let { if (page > 1) "$it/$page" else it }
        return document(url).toMangasPage()
    }

    private fun Document.toMangasPage(): MangasPage {
        val novels = select(".li-row").mapNotNull { row ->
            val link = row.selectFirst("h3.tit a") ?: return@mapNotNull null
            SManga.create().apply {
                url = link.attr("href")
                title = link.attr("title").ifBlank { link.text() }.trim()
                thumbnail_url = row.selectFirst(".pic img")?.absUrl("src")
            }
        }
        // The site serves a fixed page size, so a full page implies there is another one.
        return MangasPage(novels, novels.size >= PAGE_SIZE)
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

        val detailsUrl = (baseUrl + manga.url).toHttpUrl().newBuilder()
            .addQueryParameter("pageSize", CHAPTERS_PER_PAGE.toString())
            .build()
        val page = document(detailsUrl.toString())

        val needsDetails = fetchDetails || manga.author.isNullOrBlank() ||
            manga.genre.isNullOrBlank() || manga.description.isNullOrBlank()
        if (needsDetails) page.fillDetails(manga)
        val updated = if (fetchChapters) fetchChapterList(manga.url, page) else chapters

        return SMangaUpdate(manga, updated)
    }

    private fun Document.fillDetails(manga: SManga) {
        selectFirst("h1.tit")?.text()?.trim()?.takeIf { it.isNotEmpty() }?.let { manga.title = it }
        manga.author = selectFirst(
            ".m-book1 .item:has(span[title=Author]) a, .m-book1 a[href^='/author/']",
        )?.text()?.trim()
        manga.genre = select(
            ".m-book1 .item:has(span[title=Genre]) a, .m-book1 a[href^='/genre/']",
        )
            .joinToString { it.text().trim() }
            .takeIf { it.isNotEmpty() }
        manga.description = selectFirst(".m-desc .inner")?.text()?.trim()
            ?: selectFirst(".m-desc")?.text()?.trim()
        manga.thumbnail_url = selectFirst(".m-book1 .pic img")?.absUrl("src")

        val status = selectFirst(".m-book1 .item:has(span[title=Status]) .right")?.text().orEmpty()
        manga.status = when {
            status.contains("complet", ignoreCase = true) -> SManga.COMPLETED
            status.contains("ongoing", ignoreCase = true) -> SManga.ONGOING
            else -> SManga.UNKNOWN
        }
        manga.initialized = true
    }

    /**
     * The details page is also the first 200-chapter batch. The site's own JavaScript exposes
     * `pageSize`, capped by the server at 200, so only the remaining batches use its JSON endpoint.
     *
     * Returned newest-first: `SyncChaptersWithSource` numbers chapters by list index and the app
     * reads a title from the highest index down, which is the order the rest of Mihon expects.
     */
    private suspend fun fetchChapterList(novelUrl: String, firstPage: Document): List<SChapter> {
        val chapters = mutableListOf<SChapter>()
        firstPage.select("#idData li a").mapTo(chapters) { it.toSChapter() }

        val totalPages = firstPage.selectFirst("#indexListPage")
            ?.attr("data-total-page")
            ?.toIntOrNull()
            ?.coerceIn(1, MAX_TOC_PAGES)
            ?: 1

        for (page in 2..totalPages) {
            delay(TOC_INTERVAL)
            val url = (baseUrl + novelUrl).toHttpUrl().newBuilder()
                .addQueryParameter("ajax", "chapters")
                .addQueryParameter("page", page.toString())
                .addQueryParameter("pageSize", CHAPTERS_PER_PAGE.toString())
                .build()
            val payload = fetchChapterPage(url.toString())
            if (payload["code"]?.jsonPrimitive?.content != "200") break

            val fragment = payload["html"]?.jsonPrimitive?.content.orEmpty()
            Jsoup.parseBodyFragment(fragment, baseUrl)
                .select("li a")
                .mapTo(chapters) { it.toSChapter() }
        }

        return chapters.asReversed()
    }

    private suspend fun fetchChapterPage(url: String): JsonObject {
        repeat(MAX_RETRIES + 1) { attempt ->
            val response = client.newCall(GET(url, headers)).await()
            if (response.isSuccessful) {
                return response.use { json.parseToJsonElement(it.body.string()).jsonObject }
            }

            val code = response.code
            val retryAfter = response.header("Retry-After")?.toLongOrNull()?.coerceIn(1, MAX_RETRY_SECONDS)
            response.close()
            if (code != 429 || attempt == MAX_RETRIES) throw Exception("HTTP $code fetching chapter list")
            delay((retryAfter ?: DEFAULT_RETRY_SECONDS).seconds)
        }
        error("Chapter list retry exhausted")
    }

    private fun Element.toSChapter(): SChapter = SChapter.create().apply {
        url = attr("href")
        name = attr("title").ifBlank { text() }.trim()
        // Matched against the last path segment only. A novel whose own slug contains "chapter-<n>"
        // would otherwise have that number picked up for every one of its chapters.
        chapter_number = CHAPTER_NUMBER.find(url.substringAfterLast('/'))
            ?.groupValues?.get(1)?.toFloatOrNull()
            ?: -1f
    }

    // ------------------------------------------------------------------------------------------
    // Chapter content
    // ------------------------------------------------------------------------------------------

    override suspend fun getChapterContent(chapter: SChapter): NovelChapterContent {
        synchronized(chapterCache) { chapterCache[chapter.url] }?.let { return it }

        val chapterUrl = baseUrl + chapter.url
        val page = document(chapterUrl)

        val article = page.selectFirst("div#article")
            ?: page.selectFirst("div.txt")
            ?: throw Exception("Chapter body not found for ${chapter.url}")

        article.select(JUNK).remove()
        article.select("p")
            .filter { PROMO.containsMatchIn(it.text()) }
            .forEach { it.remove() }

        val content = NovelChapterContent(
            html = article.html(),
            baseUrl = chapterUrl,
            title = page.selectFirst("h1.tit")?.text()?.trim(),
        )
        synchronized(chapterCache) { chapterCache[chapter.url] = content }
        return content
    }

    private suspend fun document(url: String): Document =
        client.newCall(GET(url, headers)).awaitSuccess().use { it.asJsoup() }

    private data class BrowseOption(val label: String, val path: String) {
        override fun toString() = label
    }

    private class BrowseFilter : Filter.Select<BrowseOption>("Browse", BROWSE) {
        fun selectedPath() = values[state].path
    }

    private companion object {
        /** The listings serve fixed-size pages. */
        const val PAGE_SIZE = 20

        /** The site's own maximum, reducing chapter-list calls fivefold from its default of 40. */
        const val CHAPTERS_PER_PAGE = 200

        const val MAX_TOC_PAGES = 100

        val TOC_INTERVAL = 350.milliseconds

        const val MAX_RETRIES = 2
        const val DEFAULT_RETRY_SECONDS = 2L
        const val MAX_RETRY_SECONDS = 30L

        /** Enough for current, previous and next revisits without retaining whole novels. */
        const val CHAPTER_CACHE_SIZE = 4

        val CHAPTER_NUMBER = Regex("chapter-(\\d+(?:\\.\\d+)?)")

        /** Ad slots, the translate widget and the site's own scripts, all inside the body element. */
        const val JUNK = "script, ins, iframe, noscript, .reader-ad-skip, .skiptranslate, " +
            "div[id^=bg-ssp-], div[id^=pf-], p sub"

        /** Paragraphs the site injects into the prose to watermark it. */
        val PROMO = Regex("originates from|ensure the author|freewebnovel", RegexOption.IGNORE_CASE)

        val GENRES = listOf(
            "Action", "Adult", "Adventure", "Comedy", "Drama", "Eastern", "Ecchi", "Fan-fic",
            "Fantasy", "Game", "Gender-Bender", "Harem", "Historical", "Horror", "Josei",
            "Martial-Arts", "Mature", "Mecha", "Mystery", "Psychological", "Reincarnation",
            "Romance", "School-Life", "Sci-fi", "Seinen", "Shoujo", "Shounen-Ai", "Shounen",
            "Slice-of-Life", "Smut", "Sports", "Supernatural", "System", "Tragedy", "Wuxia",
            "Xianxia", "Xuanhuan", "Yaoi",
        )

        val BROWSE = buildList {
            add(BrowseOption("Most popular", "sort/most-popular"))
            add(BrowseOption("Latest releases", "sort/latest-release"))
            add(BrowseOption("Latest novels", "sort/latest-novel"))
            add(BrowseOption("Completed novels", "sort/completed-novel"))
            GENRES.forEach { add(BrowseOption("Genre: $it", "genre/$it")) }
        }.toTypedArray()
    }
}
