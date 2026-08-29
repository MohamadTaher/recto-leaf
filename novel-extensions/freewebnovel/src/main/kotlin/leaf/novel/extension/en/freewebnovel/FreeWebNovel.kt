package leaf.novel.extension.en.freewebnovel

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import leaf.novel.api.NovelChapterContent
import leaf.novel.api.NovelHttpSource
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

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

    // ------------------------------------------------------------------------------------------
    // Browse
    // ------------------------------------------------------------------------------------------

    override suspend fun getPopularManga(page: Int): MangasPage = listing("most-popular", page)

    override suspend fun getLatestUpdates(page: Int): MangasPage = listing("latest-release", page)

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isBlank()) return listing("most-popular", page)

        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$baseUrl/search?keyword=$encoded".let { if (page > 1) "$it&page=$page" else it }
        return document(url).toMangasPage()
    }

    /** Page one is the bare path; later pages append the number. */
    private suspend fun listing(sort: String, page: Int): MangasPage {
        val url = "$baseUrl/sort/$sort".let { if (page > 1) "$it/$page" else it }
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

        if (fetchDetails) document(baseUrl + manga.url).fillDetails(manga)
        val updated = if (fetchChapters) fetchChapterList(manga.url) else chapters

        return SMangaUpdate(manga, updated)
    }

    private fun Document.fillDetails(manga: SManga) {
        selectFirst("h1.tit")?.text()?.trim()?.takeIf { it.isNotEmpty() }?.let { manga.title = it }
        manga.author = selectFirst("span[title=Author] ~ a")?.text()?.trim()
        manga.genre = select("span[title=Genre] ~ a")
            .joinToString { it.text().trim() }
            .takeIf { it.isNotEmpty() }
        manga.description = selectFirst(".m-desc .txt")?.text()?.trim()
            ?: selectFirst(".m-desc")?.text()?.trim()
        manga.thumbnail_url = selectFirst(".m-book1 .pic img")?.absUrl("src")

        val status = selectFirst("span[title=Status]")?.parent()?.text().orEmpty()
        manga.status = when {
            status.contains("complet", ignoreCase = true) -> SManga.COMPLETED
            status.contains("ongoing", ignoreCase = true) -> SManga.ONGOING
            else -> SManga.UNKNOWN
        }
        manga.initialized = true
    }

    /**
     * Walks the JSON table of contents. The first response reports `totalPage`, so the remaining
     * requests are known up front rather than probed for.
     *
     * Returned newest-first: `SyncChaptersWithSource` numbers chapters by list index and the app
     * reads a title from the highest index down, which is the order the rest of Mihon expects.
     */
    private suspend fun fetchChapterList(novelUrl: String): List<SChapter> {
        val chapters = mutableListOf<SChapter>()
        var page = 1
        var totalPages = 1

        while (page <= totalPages) {
            val body = client.newCall(GET("$baseUrl$novelUrl?ajax=chapters&page=$page", headers))
                .awaitSuccess()
                .use { it.body.string() }
            val payload = json.parseToJsonElement(body).jsonObject

            if (payload["code"]?.jsonPrimitive?.content != "200") break

            if (page == 1) {
                // Capped: the count is the site's to state, and one request per page means a
                // malformed value would otherwise drive an unbounded run of them.
                totalPages = (payload["totalPage"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1)
                    .coerceIn(1, MAX_TOC_PAGES)
            }

            val fragment = payload["html"]?.jsonPrimitive?.content.orEmpty()
            Jsoup.parseBodyFragment(fragment, baseUrl)
                .select("li a")
                .mapTo(chapters) { it.toSChapter() }

            page++
        }

        return chapters.asReversed()
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
        val chapterUrl = baseUrl + chapter.url
        val page = document(chapterUrl)

        val article = page.selectFirst("div#article")
            ?: page.selectFirst("div.txt")
            ?: throw Exception("Chapter body not found for ${chapter.url}")

        article.select(JUNK).remove()
        article.select("p")
            .filter { PROMO.containsMatchIn(it.text()) }
            .forEach { it.remove() }

        return NovelChapterContent(
            html = article.html(),
            baseUrl = chapterUrl,
            title = page.selectFirst("h1.tit")?.text()?.trim(),
        )
    }

    private suspend fun document(url: String): Document =
        client.newCall(GET(url, headers)).awaitSuccess().use { it.asJsoup() }

    private companion object {
        /** The listings serve fixed-size pages. */
        const val PAGE_SIZE = 20

        /** 40 chapters a page, so this is far past the longest novel the site carries. */
        const val MAX_TOC_PAGES = 500

        val CHAPTER_NUMBER = Regex("chapter-(\\d+(?:\\.\\d+)?)")

        /** Ad slots, the translate widget and the site's own scripts, all inside the body element. */
        const val JUNK = "script, ins, iframe, noscript, .reader-ad-skip, .skiptranslate, " +
            "div[id^=bg-ssp-], div[id^=pf-], p sub"

        /** Paragraphs the site injects into the prose to watermark it. */
        val PROMO = Regex("originates from|ensure the author|freewebnovel", RegexOption.IGNORE_CASE)
    }
}
