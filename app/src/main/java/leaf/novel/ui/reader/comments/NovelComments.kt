package leaf.novel.ui.reader.comments

import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.source.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import leaf.novel.api.NovelComment
import leaf.novel.api.NovelCommentCapabilities
import leaf.novel.api.NovelCommentDraft
import leaf.novel.api.NovelCommentPage
import leaf.novel.api.NovelCommentRequest
import leaf.novel.api.NovelCommentScope
import leaf.novel.api.NovelCommentSort
import leaf.novel.api.NovelCommentSource
import leaf.novel.api.NovelCommentTarget
import leaf.novel.api.NovelCommentVote
import leaf.novel.ui.reader.setting.NovelReaderPreferences
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/**
 * One novel's comments while it is open in the reader.
 *
 * It owns the network calls, the paging cursor and everything the sheet can change — which scope is
 * showing, how it is ordered, what is folded, what is being voted on. The sheet is drawn from
 * [state] and calls back into here; nothing about a site reaches this class, because
 * [NovelCommentSource] is the only thing it talks to and the extension behind it is the only thing
 * that knows what a site's comments look like.
 *
 * Not a `ViewModel` of its own. It belongs to a reading session exactly as the chapter list does,
 * lives and dies with [NovelReaderViewModel][leaf.novel.ui.reader.NovelReaderViewModel], and
 * registering a second view model would cost DI wiring for state that has no life without the
 * first.
 *
 * Nothing is fetched until the sheet is opened. A reader who never opens it makes no requests at
 * all, which matters: this is a request per chapter turn on a site that may be rate limited.
 */
class NovelComments(
    private val scope: CoroutineScope,
    private val preferences: NovelReaderPreferences,
) {

    private val mutableState = MutableStateFlow(NovelCommentsState())
    val state: StateFlow<NovelCommentsState> = mutableState.asStateFlow()

    private var source: NovelCommentSource? = null
    private var manga: Manga? = null
    private var chapter: Chapter? = null

    /** Cancelled whenever the target, the scope or the order changes; see [reload]. */
    private var loadJob: Job? = null

    /** Cursor for the next page, when the site pages by cursor rather than by number. */
    private var cursor: String? = null
    private var page = 1

    /**
     * Whether this novel has comments at all.
     *
     * Read by the reader to decide whether the button exists. A button that opens a sheet saying
     * "this source has no comments" is worse than no button: it is one of six slots, and the answer
     * is the same every time.
     */
    val supported: Boolean get() = state.value.capabilities != null

    /**
     * Points the sheet at a novel and its source.
     *
     * Called once per reading session. A source that does not implement [NovelCommentSource] leaves
     * [supported] false and nothing else here ever runs.
     */
    fun bind(source: Source?, manga: Manga?) {
        this.source = source as? NovelCommentSource
        this.manga = manga

        val capabilities = this.source?.commentCapabilities
        val sorts = capabilities?.sorts.orEmpty()
        mutableState.update {
            it.copy(
                capabilities = capabilities,
                sorts = sorts,
                // A key stored from another site means nothing here, so it is kept only when this
                // one offers it; otherwise the source's own first order wins.
                sortKey = preferences.commentsSort.get().takeIf { key -> sorts.any { it.key == key } }
                    ?: sorts.firstOrNull()?.key,
                localSort = preferences.commentsLocalSort.get(),
                scope = capabilities.defaultScope(),
            )
        }
    }

    /**
     * Follows the reader to another chapter.
     *
     * Comments belong to the chapter on screen, so crossing a boundary in a continuous document
     * invalidates them exactly as opening a chapter from the list does. What is already loaded is
     * dropped rather than refetched: the sheet is usually closed when this happens, and refetching
     * on every chapter turn would be a request the reader never asked for.
     */
    fun setChapter(chapter: Chapter?) {
        if (this.chapter?.id == chapter?.id) return
        this.chapter = chapter
        loadJob?.cancel()
        mutableState.update { it.reset(chapterName = chapter?.name) }
    }

    /**
     * Called as the sheet opens, to fetch the first page unless one is already in hand.
     *
     * Whether the sheet is on screen is the screen's business, not this class's — it is one boolean
     * that would otherwise put every comment, vote and fold into the reader screen's recomposition.
     */
    fun open() {
        if (state.value.loaded || state.value.loading) return
        if (preferences.commentsAutoLoad.get()) reload()
    }

    /** Called as the sheet closes, so a half-written reply does not reopen with the next chapter. */
    fun close() {
        mutableState.update { it.copy(replyingTo = null) }
    }

    /** Throws away everything loaded and fetches the first page again. */
    fun reload() {
        val source = source ?: return
        val request = request(page = 1, cursor = null) ?: return

        loadJob?.cancel()
        cursor = null
        page = 1
        mutableState.update { it.reset().copy(loading = true) }

        loadJob = scope.launch {
            fetch(source, request)
                .onSuccess { result ->
                    cursor = result.nextCursor
                    mutableState.update { it.withFirstPage(result) }
                    if (preferences.commentsCollapseReplies.get()) collapseAll()
                }
                .onFailure { failure -> mutableState.update { it.withFailure(failure) } }
        }
    }

    /** Fetches the next page onto the end of what is showing. */
    fun loadMore() {
        val source = source ?: return
        val current = state.value
        if (!current.hasMore || current.loading || current.loadingMore) return
        val request = request(page = page + 1, cursor = cursor) ?: return

        mutableState.update { it.copy(loadingMore = true) }
        loadJob = scope.launch {
            fetch(source, request)
                .onSuccess { result ->
                    page += 1
                    cursor = result.nextCursor
                    mutableState.update { it.withNextPage(result) }
                }
                .onFailure { failure -> mutableState.update { it.withFailure(failure) } }
        }
    }

    /**
     * Fetches one comment's replies, for a site that serves them separately.
     *
     * Only ever reached when the source said [NovelCommentCapabilities.lazyReplies]; without it the
     * sheet assumes what arrived with the comment is all there is and never offers the row.
     */
    fun loadReplies(comment: NovelComment) {
        val source = source ?: return
        if (comment.id in state.value.loadingReplies) return
        val request = request(page = 1, cursor = null, parent = comment) ?: return

        mutableState.update { it.copy(loadingReplies = it.loadingReplies + comment.id) }
        scope.launch {
            fetch(source, request)
                .onSuccess { result ->
                    mutableState.update {
                        it.copy(loadingReplies = it.loadingReplies - comment.id)
                            .withRoots(NovelCommentTree.addReplies(it.roots, comment.id, result.comments))
                    }
                }
                .onFailure { failure ->
                    mutableState.update {
                        it.withFailure(failure)
                            .copy(loadingReplies = it.loadingReplies - comment.id)
                            .withRoots(it.roots)
                    }
                }
        }
    }

    /** Switches between the chapter's comments and the novel's, and refetches. */
    fun setScope(scope: NovelCommentScope) {
        if (state.value.scope == scope) return
        preferences.commentsScope.set(scope)
        mutableState.update { it.reset().copy(scope = scope) }
        reload()
    }

    /** Chooses one of the source's own orders, which means asking the site again. */
    fun setSort(sort: NovelCommentSort) {
        if (state.value.sortKey == sort.key) return
        preferences.commentsSort.set(sort.key)
        mutableState.update { it.copy(sortKey = sort.key) }
        reload()
    }

    /**
     * Chooses one of the reader's own orders, which means reordering what is already here.
     *
     * No request: these exist precisely because the site has no orders to ask it for.
     */
    fun setLocalSort(sort: NovelCommentLocalSort) {
        preferences.commentsLocalSort.set(sort)
        mutableState.update { it.copy(localSort = sort).withRoots(it.roots) }
    }

    /** Folds or unfolds one comment's subtree. */
    fun toggleCollapsed(id: String) {
        mutableState.update {
            val collapsed = if (id in it.collapsed) it.collapsed - id else it.collapsed + id
            it.copy(collapsed = collapsed).withRoots(it.roots)
        }
    }

    /** Folds every top-level comment, which is how a long thread becomes a table of contents. */
    fun collapseAll() {
        mutableState.update {
            it.copy(collapsed = it.roots.mapTo(mutableSetOf()) { root -> root.id }).withRoots(it.roots)
        }
    }

    fun expandAll() {
        mutableState.update { it.copy(collapsed = emptySet()).withRoots(it.roots) }
    }

    /**
     * Shows one comment and its descendants as if it were the whole thread.
     *
     * The display depth cap is measured from the focused comment, so this is also how a reader gets
     * past "continue this thread" — the same move Reddit makes for the same reason.
     */
    fun focus(id: String?) {
        mutableState.update { it.copy(focus = id, collapsed = it.collapsed - id.orEmpty()).withRoots(it.roots) }
    }

    /**
     * Casts, changes or withdraws a vote.
     *
     * Applied locally first and replaced with whatever the site reports, so the arrow responds to
     * the tap rather than to the round trip. A failure puts the old comment back and says so: a
     * vote that silently did not happen is worse than one that visibly failed.
     */
    fun vote(comment: NovelComment, vote: NovelCommentVote) {
        val source = source ?: return
        if (state.value.capabilities?.voting != true) return

        val wanted = if (comment.vote == vote) NovelCommentVote.NONE else vote
        val optimistic = comment.copy(vote = wanted, score = comment.score?.plus(wanted.delta - comment.vote.delta))
        mutableState.update { it.withRoots(NovelCommentTree.replace(it.roots, optimistic)) }

        scope.launch {
            runCatching { withIOContext { source.voteComment(comment, wanted) } }
                .onSuccess { updated ->
                    mutableState.update { it.withRoots(NovelCommentTree.replace(it.roots, updated)) }
                }
                .onFailure { failure ->
                    logcat(LogPriority.WARN, failure) { "Could not vote on comment ${comment.id}" }
                    mutableState.update {
                        it.withRoots(NovelCommentTree.replace(it.roots, comment)).withFailure(failure)
                    }
                }
        }
    }

    /** Opens the composer, either for a new comment or as a reply to [comment]. */
    fun replyTo(comment: NovelComment?) {
        mutableState.update { it.copy(replyingTo = comment) }
    }

    /** Posts what the composer holds, dropping it into the thread where the site put it. */
    fun post(body: String) {
        val source = source ?: return
        val target = target() ?: return
        if (state.value.capabilities?.posting != true || body.isBlank()) return

        val parentId = state.value.replyingTo?.id
        mutableState.update { it.copy(posting = true, error = null) }

        scope.launch {
            runCatching {
                withIOContext { source.postComment(NovelCommentDraft(target, body.trim(), parentId)) }
            }
                .onSuccess { posted ->
                    mutableState.update {
                        it.withRoots(NovelCommentTree.insert(it.roots, parentId, posted))
                            .copy(posting = false, replyingTo = null)
                    }
                }
                .onFailure { failure ->
                    logcat(LogPriority.WARN, failure) { "Could not post a comment" }
                    mutableState.update { it.withFailure(failure).copy(posting = false) }
                }
        }
    }

    fun dismissError() {
        mutableState.update { it.copy(error = null) }
    }

    private suspend fun fetch(
        source: NovelCommentSource,
        request: NovelCommentRequest,
    ): Result<NovelCommentPage> = runCatching { withIOContext { source.getComments(request) } }
        .onFailure { logcat(LogPriority.WARN, it) { "Could not load comments for ${manga?.title}" } }

    /** The request for the state as it stands, or null when there is nothing to ask about. */
    private fun request(page: Int, cursor: String?, parent: NovelComment? = null): NovelCommentRequest? {
        val target = target() ?: return null
        val current = state.value
        val sort = current.sorts.firstOrNull { it.key == current.sortKey }
            ?: current.sorts.firstOrNull()
            ?: NovelCommentSort(key = "", label = "")
        return NovelCommentRequest(target = target, sort = sort, page = page, cursor = cursor, parent = parent)
    }

    private fun target(): NovelCommentTarget? {
        val novel = manga?.toSManga() ?: return null
        return when (state.value.scope) {
            NovelCommentScope.NOVEL -> NovelCommentTarget(novel)
            NovelCommentScope.CHAPTER -> NovelCommentTarget(novel, chapter?.toSChapter() ?: return null)
        }
    }

    /**
     * Which scope to start in: the reader's preference where the source serves it, otherwise
     * whichever one it does serve.
     */
    private fun NovelCommentCapabilities?.defaultScope(): NovelCommentScope {
        val scopes = this?.scopes.orEmpty()
        val preferred = preferences.commentsScope.get()
        return preferred.takeIf { it in scopes } ?: scopes.firstOrNull() ?: NovelCommentScope.CHAPTER
    }
}

/** How much a vote moves a score, for the optimistic update. */
private val NovelCommentVote.delta: Int
    get() = when (this) {
        NovelCommentVote.UP -> 1
        NovelCommentVote.NONE -> 0
        NovelCommentVote.DOWN -> -1
    }
