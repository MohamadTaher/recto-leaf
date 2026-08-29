package leaf.novel.reader

import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import leaf.novel.api.NovelChapterContent
import leaf.novel.api.NovelSource
import leaf.novel.download.StoredNovelChapter
import leaf.novel.download.readStoredChapter
import okhttp3.OkHttpClient
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.time.Duration.Companion.seconds

/**
 * Serves chapters from a web-novel source, the counterpart to [EpubContentProvider].
 *
 * A downloaded chapter is read from disk; anything else is fetched. Downloading is therefore
 * optional — reading a novel never requires one — and it is the same code path either way, so a
 * partially downloaded novel reads seamlessly.
 *
 * Everything is put through [NovelHtmlSanitizer] on the way out, including what came off disk. That
 * is deliberate: downloads store exactly what the source returned, so improving the sanitiser also
 * improves chapters that were downloaded before the change.
 */
class SourceContentProvider(
    private val source: NovelSource,
    private val manga: Manga,
    private val downloadProvider: DownloadProvider,
) : NovelContentProvider {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun content(chapter: Chapter): NovelChapterContent = withIOContext {
        val raw = storedChapter(chapter)?.let {
            NovelChapterContent(html = it.html, baseUrl = it.baseUrl, title = it.title)
        } ?: source.getChapterContent(chapter.toSChapter())

        NovelChapterContent(
            html = NovelHtmlSanitizer.sanitize(raw.html, raw.baseUrl),
            // The source's own <style> and <link> are dropped rather than forwarded: the reader owns
            // the stylesheet, and a remote sheet could not load through the WebView anyway.
            head = "",
            // The reader loads this into `loadDataWithBaseURL`, so it becomes the document's origin.
            // Sanitising has already absolutised every reference against the real chapter URL, so
            // passing that URL on would buy nothing and would give the document a live web origin;
            // the unresolvable virtual origin is what the EPUB path uses and is the safer default.
            baseUrl = VIRTUAL_ORIGIN,
            title = raw.title,
        )
    }

    /** The chapter's downloaded copy, or null when it was never downloaded. */
    private fun storedChapter(chapter: Chapter): StoredNovelChapter? {
        val directory = downloadProvider.findChapterDir(
            chapterName = chapter.name,
            chapterScanlator = chapter.scanlator,
            chapterUrl = chapter.url,
            mangaTitle = manga.title,
            source = source,
        ) ?: return null

        return readStoredChapter(directory, json)
    }

    /**
     * Fetches an image the chapter referenced. [NovelHtmlSanitizer] rewrote every remote `img` onto
     * the reader's virtual origin, so by the time the WebView asks for one the path here is the
     * original absolute URL.
     *
     * Blocking on purpose: `shouldInterceptRequest` is synchronous, and the WebView calls it from
     * its own thread. The body is buffered for the same reason [EpubContentProvider] buffers — a
     * WebView holds the stream well past the call.
     */
    override fun resourceStream(path: String): InputStream? {
        val client = imageClient ?: return null
        val http = source as? HttpSource ?: return null
        if (!path.startsWith("http://", true) && !path.startsWith("https://", true)) return null

        return runCatching {
            runBlocking {
                client.newCall(GET(path, http.headers)).awaitSuccess()
                    .use { ByteArrayInputStream(it.body.bytes()) }
            }
        }.getOrNull()
    }

    /**
     * The source's client with a much shorter deadline. The shared client allows a two-minute call
     * timeout, which is fine for a chapter fetched off the main thread but not for this: the WebView
     * calls [resourceStream] synchronously on one of its own threads, so a hung image would stall
     * resource loading for that whole time. A missing illustration is the better failure.
     */
    private val imageClient: OkHttpClient? by lazy {
        (source as? HttpSource)?.client?.newBuilder()
            ?.callTimeout(IMAGE_TIMEOUT)
            ?.build()
    }

    private companion object {
        val IMAGE_TIMEOUT = 15.seconds
    }

    /** Nothing is held open: each chapter and each image is a request or a file read of its own. */
    override fun close() = Unit
}
