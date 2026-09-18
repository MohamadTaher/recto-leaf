package leaf.novel.ui.reader.comments

import androidx.compose.runtime.Immutable
import leaf.novel.api.NovelComment
import leaf.novel.api.NovelCommentCapabilities
import leaf.novel.api.NovelCommentScope
import leaf.novel.api.NovelCommentSort

/**
 * One thread as it is fetched, which is the model everything else is derived from.
 *
 * Held per chapter and filled page by page, so it survives a chapter turn and comes back instantly
 * when the reader turns back — exactly as the chapter text does. Everything that changes a comment
 * goes through here rather than through [NovelCommentsState]: a vote, a reply that was fetched and
 * a comment that was posted all belong to the thread, and writing them into the view would mean
 * losing them the moment the next page arrived.
 *
 * [comments] is whatever the site has sent so far, nested or flat as the site chose; it is
 * `NovelCommentTree.build` that decides which and turns it into the forest.
 */
data class NovelCommentThread(
    val comments: List<NovelComment> = emptyList(),
    /** Whatever the site said the thread's size is, which is not always what arrived. */
    val total: Int? = null,
    /** Set once a page has actually come back, so an empty thread is "none" rather than "not yet". */
    val loaded: Boolean = false,
    /** Whether this class has stopped asking — because the site ran out, or it failed, or the cap. */
    val done: Boolean = false,
    /** Set only when it stopped early with pages still to get, which is what offers "load more". */
    val hasMore: Boolean = false,
    val nextPage: Int = 1,
    val nextCursor: String? = null,
    val failure: Throwable? = null,
)

/**
 * Everything the comments sheet draws.
 *
 * [roots] is the thread and [rows] is that thread flattened for a `LazyColumn`. Both are held
 * rather than derived in the composition: flattening walks the whole forest, and it changes only
 * when the thread, the fold set or the focus does — not on every recomposition of a scrolling list.
 * [withRoots] is the one place the two are kept in step.
 */
@Immutable
data class NovelCommentsState(
    /** Null until a source that serves comments is bound; the reader's button hangs off it. */
    val capabilities: NovelCommentCapabilities? = null,

    /** Whose comments these are. Fixed for the life of the controller that owns this state. */
    val scope: NovelCommentScope = NovelCommentScope.CHAPTER,

    /** The source's own orders. Empty means it has none and [localSort] applies instead. */
    val sorts: List<NovelCommentSort> = emptyList(),
    val sortKey: String? = null,

    /**
     * The order to apply here, for a source that offers none of its own.
     *
     * A fallback, never a replacement. A site orders from data it does not necessarily send: its
     * "top" can weigh replies, recency and votes that never reach a [NovelComment], so a site with
     * no likes count in its payload can still rank by likes and this cannot. Asking the site is
     * strictly the more capable of the two, and reordering its answer locally would throw that away.
     */
    val localSort: NovelCommentLocalSort = NovelCommentLocalSort.TOP,

    val roots: List<NovelComment> = emptyList(),
    val rows: List<NovelCommentRow> = emptyList(),

    val collapsed: Set<String> = emptySet(),
    val loadingReplies: Set<String> = emptySet(),
    /** When set, the sheet shows this comment's subtree as if it were the whole thread. */
    val focus: String? = null,

    /** Nothing has arrived yet. Mutually exclusive with [loaded]. */
    val loading: Boolean = false,
    /** Something has arrived and the rest is still being fetched, page by page. */
    val loadingMore: Boolean = false,
    val posting: Boolean = false,
    /** The fetch stopped with pages still to get; see [NovelCommentThread.hasMore]. */
    val hasMore: Boolean = false,

    /** Whatever the site said the thread's size is, which is not always what arrived. */
    val total: Int? = null,

    /** The failure to show. Cleared as soon as the reader has seen it. */
    val error: String? = null,

    /** Set once a page has come back, so reopening the sheet does not refetch. */
    val loaded: Boolean = false,

    /** Which comment the composer is answering, or null for a new top-level one. */
    val replyingTo: NovelComment? = null,

    /**
     * How many comments this session has successfully posted.
     *
     * A counter rather than a flag because it is what tells the composer to empty itself, and only
     * a post that actually landed should: clearing the field on the way out would lose what someone
     * typed the moment the site refused it. Deliberately outside [reset] — a chapter turn is not an
     * unposting, and putting it back to zero would fire the composer's own effect again.
     */
    val posted: Int = 0,

    val chapterName: String? = null,
) {

    /** How many comments are in hand, for the header. Falls back to the count when the site is quiet. */
    val count: Int get() = total ?: NovelCommentTree.count(roots)

    val isEmpty: Boolean get() = loaded && roots.isEmpty()

    /** The comment the sheet is focused on, when it is. */
    val focused: NovelComment? get() = focus?.let { NovelCommentTree.find(roots, it) }

    /** Everything cleared but the configuration, which survives a chapter turn and a re-sort. */
    fun reset(chapterName: String? = this.chapterName) = copy(
        roots = emptyList(),
        rows = emptyList(),
        collapsed = emptySet(),
        loadingReplies = emptySet(),
        focus = null,
        loading = false,
        loadingMore = false,
        posting = false,
        hasMore = false,
        total = null,
        error = null,
        loaded = false,
        replyingTo = null,
        chapterName = chapterName,
    )

    /** The thread, and the rows that go with it. Nothing else may set one without the other. */
    fun withRoots(roots: List<NovelComment>): NovelCommentsState {
        // Ordered locally only where the source offers nothing to order by; see [localSort].
        val ordered = if (sorts.isEmpty()) NovelCommentTree.sortedBy(roots, localSort) else roots
        return copy(
            roots = ordered,
            rows = NovelCommentTree.flatten(
                roots = ordered,
                collapsed = collapsed,
                loadingReplies = loadingReplies,
                lazyReplies = capabilities?.lazyReplies == true,
                root = focus,
            ),
        )
    }

    /**
     * The thread as it now stands, nested and flattened.
     *
     * Called for every change to it, not just for a new page: a vote, a fetched reply and a posted
     * comment are all changes to the thread, and routing them through one function is what keeps
     * [roots] and [rows] from ever disagreeing with what was fetched.
     *
     * @param collapseNew folds the top-level comments that have just arrived, for the reader who
     * asked for threads to start collapsed. Only while the fetch is still running, and only the new
     * ones: whatever they have opened since stays open, because a page of comments arriving is no
     * reason to undo it, and neither is their own comment landing at the top.
     */
    fun withThread(thread: NovelCommentThread, collapseNew: Boolean = false): NovelCommentsState {
        val built = NovelCommentTree.build(thread.comments)
        val arrived = thread.loaded || built.isNotEmpty()
        val folds = when {
            !collapseNew || thread.done -> collapsed
            else -> {
                val known = roots.mapTo(mutableSetOf()) { it.id }
                collapsed + built.filterNot { it.id in known }.map { it.id }
            }
        }
        return copy(
            loading = !arrived && thread.failure == null,
            loaded = arrived,
            loadingMore = arrived && !thread.done,
            hasMore = thread.hasMore,
            total = thread.total,
            // Only ever set from the thread, never cleared by it: a vote that failed is an error
            // this has no business tidying away, and the reader dismisses it themselves.
            error = thread.failure?.text() ?: error,
            collapsed = folds,
        ).withRoots(built)
    }

    /**
     * The failure, with the loading flags cleared.
     *
     * For the things that are not the thread — a vote, a post, a page of replies. The message is the
     * exception's own. It comes from the extension, which knows what went wrong with its own site
     * far better than any string this app could substitute for it.
     */
    fun withFailure(failure: Throwable) = copy(
        loading = false,
        loadingMore = false,
        error = failure.text(),
    )
}

private fun Throwable.text(): String =
    message?.takeIf { it.isNotBlank() } ?: this::class.simpleName.orEmpty()
