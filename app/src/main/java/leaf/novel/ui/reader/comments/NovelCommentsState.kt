package leaf.novel.ui.reader.comments

import androidx.compose.runtime.Immutable
import leaf.novel.api.NovelComment
import leaf.novel.api.NovelCommentCapabilities
import leaf.novel.api.NovelCommentPage
import leaf.novel.api.NovelCommentScope
import leaf.novel.api.NovelCommentSort

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

    /** Whether the chapter's or the novel's comments are showing. */
    val scope: NovelCommentScope = NovelCommentScope.CHAPTER,

    /** The source's own orders. Empty means it has none and [localSort] applies instead. */
    val sorts: List<NovelCommentSort> = emptyList(),
    val sortKey: String? = null,
    val localSort: NovelCommentLocalSort = NovelCommentLocalSort.TOP,

    val roots: List<NovelComment> = emptyList(),
    val rows: List<NovelCommentRow> = emptyList(),

    val collapsed: Set<String> = emptySet(),
    val loadingReplies: Set<String> = emptySet(),
    /** When set, the sheet shows this comment's subtree as if it were the whole thread. */
    val focus: String? = null,

    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val posting: Boolean = false,
    val hasMore: Boolean = false,

    /** Whatever the site said the thread's size is, which is not always what arrived. */
    val total: Int? = null,

    /** The failure to show. Cleared as soon as the reader has seen it. */
    val error: String? = null,

    /** Set once a page has come back, so reopening the sheet does not refetch. */
    val loaded: Boolean = false,

    /** Which comment the composer is answering, or null for a new top-level one. */
    val replyingTo: NovelComment? = null,

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
        // Ordered locally only when the source offers nothing to order by; see [NovelCommentLocalSort].
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

    fun withFirstPage(page: NovelCommentPage) = copy(
        loading = false,
        loaded = true,
        error = null,
        hasMore = page.hasNextPage && capabilities?.paginated != false,
        total = page.total,
    ).withRoots(NovelCommentTree.build(page.comments))

    fun withNextPage(page: NovelCommentPage) = copy(
        loadingMore = false,
        error = null,
        hasMore = page.hasNextPage && capabilities?.paginated != false,
        total = page.total ?: total,
    ).withRoots(roots + NovelCommentTree.build(page.comments).filterNot { new -> roots.any { it.id == new.id } })

    /**
     * The failure, with the loading flags cleared.
     *
     * The message is the exception's own. It comes from the extension, which knows what went wrong
     * with its own site far better than any string this app could substitute for it.
     */
    fun withFailure(failure: Throwable) = copy(
        loading = false,
        loadingMore = false,
        error = failure.message?.takeIf { it.isNotBlank() } ?: failure::class.simpleName.orEmpty(),
    )
}
