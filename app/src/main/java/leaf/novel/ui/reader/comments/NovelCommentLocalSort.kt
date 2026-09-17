package leaf.novel.ui.reader.comments

import dev.icerock.moko.resources.StringResource
import leaf.novel.api.NovelComment
import tachiyomi.i18n.MR

/**
 * The orders the reader can apply itself, for a source that declares none of its own.
 *
 * The app does not otherwise invent sorts — a site's orders are the site's, and asking for "top"
 * from a server that has never heard of it is how a listing silently comes back wrong. These are
 * different and the sheet says so: they reorder the comments *already fetched*, which on a
 * paginated site is a page rather than the thread.
 *
 * Three, not six. Top, newest and oldest are what people reach for; everything past them needs
 * data (vote ratios, reply velocity) that a source with no sorts was never going to supply.
 *
 * Stored with `getEnum`, so the constant names are persisted: reorder them freely, renaming one
 * resets whatever a reader had chosen.
 */
enum class NovelCommentLocalSort(
    val titleRes: StringResource,
    val comparator: Comparator<NovelComment>,
) {
    TOP(
        MR.strings.leaf_novel_comments_sort_top,
        // An unscored comment sorts as zero rather than dropping to the bottom: on a site with no
        // scores at all this leaves the source's own order intact, which is the honest result.
        compareByDescending { it.score ?: 0 },
    ),
    NEWEST(
        MR.strings.leaf_novel_comments_sort_newest,
        compareByDescending { it.postedAt },
    ),
    OLDEST(
        MR.strings.leaf_novel_comments_sort_oldest,
        // A site that gives no timestamps reports zero for all of them, and a stable sort then
        // leaves the list exactly as it arrived.
        compareBy { it.postedAt },
    ),
}
