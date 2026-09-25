package leaf.novel.api

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga

/**
 * Finds one chapter by its number, implemented alongside [NovelCommentSource] by a site whose
 * chapter list is too long to fetch just to look one chapter up.
 *
 * Another source's chapter comments are found by number, and without this the app reads the number
 * off that source's whole chapter list. That is one request on most sites, and one per page on a
 * site that pages its table of contents — over a hundred for a long novel on a paced site, which is
 * more than a comment thread is worth and more than the thirty seconds a call is given.
 *
 * Kept separate from [NovelCommentSource] so installed extensions that lack it keep loading.
 */
interface NovelCommentChapterSource {

    /**
     * [novel]'s chapter numbered [number], or null when it has none.
     *
     * Null is an answer, and the reader shows it as a chapter with no comments; throw when the site
     * could not be asked.
     */
    suspend fun getCommentChapter(novel: SManga, number: Double): SChapter?
}
