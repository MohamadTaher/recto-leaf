package leaf.novel.ui.reader.comments

import androidx.compose.runtime.Immutable
import leaf.novel.api.NovelComment
import leaf.novel.api.NovelCommentCapabilities
import leaf.novel.api.NovelCommentFeed
import leaf.novel.api.NovelCommentScope

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
    val voting: Set<String> = emptySet(),
    val loadingReplies: Set<String> = emptySet(),
    val posting: Boolean = false,
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
    /** Per comment, the last page of replies fetched and the cursor that follows it. */
    val replyPages: Map<String, Int> = emptyMap(),
    val replyCursors: Map<String, String?> = emptyMap(),
) {

    /**
     * The thread as another screen can pick it up: what was fetched, without what was happening.
     *
     * A vote or a reply page still on its way belongs to the coroutine that asked for it, and a
     * failure to the attempt that met it — so a thread that failed is left for the next reader to
     * try again from where it stopped, rather than handed over already given up.
     */
    fun settled() = copy(
        voting = emptySet(),
        loadingReplies = emptySet(),
        posting = false,
        done = done && failure == null,
        hasMore = hasMore && failure == null,
        failure = null,
    )
}

/** The sheet's reviews filter: everything, reviews alone, or everything that is not a review. */
enum class NovelCommentKind {
    ALL,
    REVIEWS,
    COMMENTS,
    ;

    /** Whether a feed belongs to this kind. A feed is a review feed by its key, and nothing else. */
    fun admits(feed: NovelCommentFeed): Boolean = when (this) {
        ALL -> true
        REVIEWS -> feed.key == REVIEWS_FEED
        COMMENTS -> feed.key != REVIEWS_FEED
    }

    /** The next in the toggle's cycle: all, then reviews, then comments, then all again. */
    val next: NovelCommentKind get() = entries[(ordinal + 1) % entries.size]

    companion object {
        /** The key every source files its reviews under; see `docs/leaf/comments/PROVIDERS.md`. */
        const val REVIEWS_FEED = "reviews"
    }
}

/** One source in the sheet's extension filter, with however many comments it has brought so far. */
@Immutable
data class NovelCommentOrigin(
    val id: Long,
    val name: String,
    /** Null until its thread has come back, which is not the same as none. */
    val count: Int? = null,
    val loading: Boolean = false,
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

    /** Reviews, comments or both. Offered only while [kinds] holds both. */
    val kind: NovelCommentKind = NovelCommentKind.ALL,
    /** Which of reviews and comments the sources on show have at all. */
    val kinds: Set<NovelCommentKind> = emptySet(),

    /** Whose comments these are. Fixed for the life of the controller that owns this state. */
    val scope: NovelCommentScope = NovelCommentScope.CHAPTER,

    /** The novel's own source and every other that has it; the filter appears once there are two. */
    val origins: List<NovelCommentOrigin> = emptyList(),
    /** The one source to show, or null for all of them together. */
    val origin: Long? = null,
    /** Still looking for the novel on the other sources. */
    val searching: Boolean = false,

    /**
     * The order the thread is drawn in, applied here to whatever every source sent.
     *
     * Always the app's own rather than a site's: the thread mixes sources, and one site's "top" is
     * no way to rank another site's comments. Each site is asked for its default order and the
     * sheet reorders the lot, so the same choice means the same thing whatever the source.
     */
    val localSort: NovelCommentLocalSort = NovelCommentLocalSort.TOP,

    val roots: List<NovelComment> = emptyList(),
    val rows: List<NovelCommentRow> = emptyList(),

    val collapsed: Set<String> = emptySet(),
    /** Replies open independently of the parent comment, and start behind a reply-count row. */
    val expandedReplies: Set<String> = emptySet(),
    val loadingReplies: Set<String> = emptySet(),
    /** When set, the sheet shows this comment's subtree as if it were the whole thread. */
    val focus: String? = null,

    /** Nothing has arrived yet. Mutually exclusive with [loaded]. */
    val loading: Boolean = false,
    /** Something has arrived and the rest is still being fetched, page by page. */
    val loadingMore: Boolean = false,
    val posting: Boolean = false,
    val voting: Set<String> = emptySet(),
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
    val draft: String = "",

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
        expandedReplies = emptySet(),
        loadingReplies = emptySet(),
        focus = null,
        loading = false,
        loadingMore = false,
        posting = false,
        voting = emptySet(),
        hasMore = false,
        total = null,
        error = null,
        loaded = false,
        replyingTo = null,
        chapterName = chapterName,
    )

    /** The thread, and the rows that go with it. Nothing else may set one without the other. */
    fun withRoots(roots: List<NovelComment>): NovelCommentsState {
        val ordered = NovelCommentTree.sortedBy(roots, localSort)
        return copy(
            roots = ordered,
            rows = NovelCommentTree.flatten(
                roots = ordered,
                collapsed = collapsed,
                loadingReplies = loadingReplies,
                lazyReplies = capabilities?.lazyReplies == true,
                root = focus,
                expandedReplies = expandedReplies,
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
            voting = thread.voting,
            loadingReplies = thread.loadingReplies,
            posting = thread.posting,
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
