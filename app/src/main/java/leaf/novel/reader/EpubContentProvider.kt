package leaf.novel.reader

import leaf.novel.epub.NovelEpubReader
import org.jsoup.Jsoup
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.chapter.model.Chapter
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Serves chapters straight out of `novels/<Title>/book.epub`.
 *
 * Nothing is extracted to disk: the reader's virtual origin maps back onto archive entries, so a
 * chapter's own images, stylesheet and fonts come out of the zip on demand.
 *
 * Every archive access is guarded by [lock] and refused once [close] has run. `ArchiveReader.close`
 * unmaps the file, and the WebView issues resource requests from its own threads — a read that
 * arrived after the unmap would be a native crash, not an exception.
 */
class EpubContentProvider(
    private val epub: NovelEpubReader,
    /** The novel's folder name, which is also the prefix on every `chapters.url`. */
    private val novelUrl: String,
) : NovelContentProvider {

    private val lock = Any()
    private var closed = false

    override suspend fun content(chapter: Chapter): NovelChapterContent = withIOContext {
        val entry = entryPathOf(chapter)
        val bytes = readEntryBytes(entry) ?: throw NovelChapterMissingException(entry)

        val document = ByteArrayInputStream(bytes).use { Jsoup.parse(it, null, VIRTUAL_ORIGIN + entry) }

        // Scripts never run (JavaScript is disabled) but there is no reason to hand them to the
        // engine at all, and dropping them keeps the fragment to what is actually readable.
        document.select("script").remove()

        NovelChapterContent(
            html = document.body().html(),
            head = document.head().select("style, link[rel=stylesheet]").joinToString("\n") { it.outerHtml() },
            baseUrl = VIRTUAL_ORIGIN + entry,
            title = document.title().trim().takeIf { it.isNotEmpty() },
        )
    }

    /**
     * Buffered rather than handed over as a live archive stream: a WebView holds the streams it is
     * given well past the call, and the archive must be free to close underneath it.
     */
    override fun resourceStream(path: String): InputStream? =
        readEntryBytes(path)?.let(::ByteArrayInputStream)

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            epub.close()
        }
    }

    private fun readEntryBytes(path: String): ByteArray? = synchronized(lock) {
        if (closed) return null
        // A URL always uses forward slashes, but an archive written with Windows separators names
        // its entries with backslashes, so the round trip through the virtual origin loses them.
        val entry = epub.readEntry(path) ?: epub.readEntry(path.replace('/', '\\'))
        entry?.use { it.readBytes() }
    }

    /**
     * `chapters.url` is `<folder>/<spine href>` so that replacing `book.epub` matches existing rows
     * by URL. Strip the folder back off to get the archive entry.
     */
    private fun entryPathOf(chapter: Chapter): String = chapter.url.removePrefix("$novelUrl/")
}

/** The spine document a chapter row points at is no longer in the archive. */
class NovelChapterMissingException(val entry: String) : Exception(entry)

/**
 * Origin the reader serves chapter resources from. `.invalid` is reserved by RFC 2606 and can never
 * resolve, so a request that escapes the interceptor fails rather than reaching the network.
 */
const val VIRTUAL_ORIGIN = "https://novel.rectoleaf.invalid/"
