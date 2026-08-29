package leaf.novel.reader

import leaf.novel.api.NovelChapterContent
import tachiyomi.domain.chapter.model.Chapter
import java.io.Closeable
import java.io.InputStream

/**
 * Where the reader gets chapter HTML from, bound to one novel for the life of a reading session.
 *
 * Two implementations: [EpubContentProvider] serves an imported book out of its archive, and
 * [SourceContentProvider] serves a web novel from its download or its source. The reader knows
 * neither.
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
