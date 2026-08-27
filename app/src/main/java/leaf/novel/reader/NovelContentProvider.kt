package leaf.novel.reader

import tachiyomi.domain.chapter.model.Chapter
import java.io.Closeable
import java.io.InputStream

/**
 * The readable content of one chapter.
 *
 * [html] is a body fragment, not a document: the reader owns the shell, the `<head>` and the injected
 * stylesheet, which is what lets `NovelReaderCss` apply identically whatever the content came from.
 */
class NovelChapterContent(
    /** Cleaned chapter HTML — the contents of `<body>`. */
    val html: String,
    /** The source document's own `<style>` and `<link rel="stylesheet">` elements, verbatim. */
    val head: String = "",
    /** Absolute URL the fragment's relative references resolve against. */
    val baseUrl: String? = null,
    /** Per-chapter title discovered while parsing, if any. */
    val title: String? = null,
)

/**
 * Where the reader gets chapter HTML from, bound to one novel for the life of a reading session.
 *
 * The MVP ships exactly one implementation, [EpubContentProvider]. The interface exists anyway
 * because a future web-novel source is the one place a second implementation is certain, and
 * retrofitting it would mean surgery on the reader's most complex class. See plans/07.
 */
interface NovelContentProvider : Closeable {

    /** Loads and cleans the body of [chapter]. Throws when the chapter is not in the novel. */
    suspend fun content(chapter: Chapter): NovelChapterContent

    /**
     * Opens a resource a chapter document references — an image, stylesheet or font — by the path
     * the reader's virtual origin resolved it to. Returns null when it does not exist.
     */
    fun resourceStream(path: String): InputStream?
}
