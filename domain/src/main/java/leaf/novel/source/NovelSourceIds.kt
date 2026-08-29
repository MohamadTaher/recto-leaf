package leaf.novel.source

import tachiyomi.domain.manga.model.Manga

/**
 * The novel source ids, in the lowest module that the persistence layer can see.
 *
 * `LocalNovelSource` lives in `:app` and owns the behaviour; this holds only the number, so that
 * `:data` can flag a row as a novel at the moment it is inserted without depending on `:app`.
 *
 * Extension source ids are the first 64 bits of an MD5 with the sign bit cleared, so a negative id
 * can never collide. `0L` is LocalSource and `-1L` is an invalid-id sentinel elsewhere.
 */
const val LOCAL_NOVEL_SOURCE_ID = -2L

/** True for any source whose titles are novels. */
fun isNovelSourceId(sourceId: Long): Boolean = sourceId == LOCAL_NOVEL_SOURCE_ID

/**
 * True for a novel that was imported as an EPUB rather than fetched from a source.
 *
 * These have nothing to download — the book is already on disk — so they suppress the download
 * affordances, which is what the `isNovel` checks used to do before web-novel sources existed.
 * A novel from an extension is downloadable like any manga and must not match here.
 */
fun Manga.isLocalNovel(): Boolean = isNovel && source == LOCAL_NOVEL_SOURCE_ID
