package leaf.novel.ui.reader.comments

import dev.icerock.moko.resources.StringResource
import leaf.novel.api.NovelComment
import tachiyomi.i18n.MR

/**
 * The orders the sheet can draw a thread in, always applied here rather than asked of a site.
 *
 * A thread gathers several sources, and no one site's "top" can rank another's comments, so every
 * site is asked for its default order and the lot is reordered here. Top is by likes — the like
 * count a source gives apart from dislikes, or its score — which is why it is built from a likes
 * lookup rather than read off the comment.
 *
 * Stored with `getEnum`, so the constant names are persisted: reorder them freely, renaming one
 * resets whatever a reader had chosen.
 */
enum class NovelCommentLocalSort(val titleRes: StringResource) {
    TOP(MR.strings.leaf_novel_comments_sort_top),
    NEWEST(MR.strings.leaf_novel_comments_sort_newest),
    OLDEST(MR.strings.leaf_novel_comments_sort_oldest),
    ;

    /**
     * The order itself. A site that gives no timestamps reports zero for all of them, and a stable
     * sort then leaves its comments as they arrived; the same goes for a site with no likes.
     */
    fun comparator(likes: (NovelComment) -> Int): Comparator<NovelComment> = when (this) {
        TOP -> compareByDescending(likes)
        NEWEST -> compareByDescending { it.postedAt }
        OLDEST -> compareBy { it.postedAt }
    }
}
