package leaf.novel.ui.reader.comments

import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.source.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import leaf.novel.api.NovelComment
import leaf.novel.api.NovelCommentCapabilities
import leaf.novel.api.NovelCommentDraft
import leaf.novel.api.NovelCommentFeed
import leaf.novel.api.NovelCommentFeedSource
import leaf.novel.api.NovelCommentFeedback
import leaf.novel.api.NovelCommentFeedbackSource
import leaf.novel.api.NovelCommentPage
import leaf.novel.api.NovelCommentPositiveVote
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
 * One novel's comments, for as long as something is looking at them.
 *
 * It owns the network calls, the paging and everything the sheet can change — what is folded, what
 * is being voted on, how the thread is ordered. The sheet is drawn from [state] and calls back into
 * here; nothing about a site reaches this class, because [NovelCommentSource] is the only thing it
 * talks to and the extension behind it is the only thing that knows what a site's comments look
 * like.
 *
 * **One instance, one scope.** A chapter's comments and a novel's are different things in different
 * places — the reader has the first, the novel's own screen has the second — so each gets its own
 * instance and neither has a scope to switch. That is why [supported] asks whether the source
 * serves *this* scope: a site with chapter comments and nothing else withdraws the button on the
 * novel screen and keeps the one in the reader.
 *
 * Not a `ViewModel` of its own. The reader's belongs to a reading session exactly as the chapter
 * list does and lives and dies with
 * [NovelReaderViewModel][leaf.novel.ui.reader.NovelReaderViewModel]; the novel screen's is
 * remembered by the composable that draws the button. Registering a view model would cost DI wiring
 * for state that has no life without its owner.
 *
 * ### What it fetches, and when
 *
 * A chapter's comments start loading as the chapter opens, and the chapters either side of it are
 * fetched ahead — the same thing the reader already does with the text, for the same reason. Each
 * thread is then drained to the end a page at a time rather than waiting to be asked for the next
 * one, with a pause between requests so that a long thread is not a burst at a site that may be
 * rate limiting. `commentsAutoLoad` turns the whole of that off for a metered connection, and then
 * nothing is fetched until the sheet is opened.
 */
class NovelComments(
    private val scope: CoroutineScope,
    private val preferences: NovelReaderPreferences,
    /** Which comments this instance serves, for its whole life. */
    private val commentScope: NovelCommentScope = NovelCommentScope.CHAPTER,
) {

    private val mutableState = MutableStateFlow(NovelCommentsState(scope = commentScope))
    val state: StateFlow<NovelCommentsState> = mutableState.asStateFlow()

    private var source: NovelCommentSource? = null
    private var manga: Manga? = null
    private var chapter: Chapter? = null

    /** Watches the open target's thread. Cancelled and restarted whenever that target changes. */
    private var loadJob: Job? = null

    /**
     * One entry per thread asked for, keyed by chapter — null for the novel's own — and by order,
     * because a site's orders are its own answers and each one is a different listing.
     *
     * The reader's own chapter cache in all but name, and kept for the same reason: a reader who
     * turns back a chapter has already paid for those comments, and a second fetch of them is a
     * request the site did not need to serve.
     */
    private val drains = mutableMapOf<Key, Drain>()

    /**
     * Whether this novel has the comments this instance is for.
     *
     * Read by whoever draws the button, to decide whether the button exists. A button that opens a
     * sheet saying "this source has none of these" is worse than no button: it is one of six slots
     * in the reader and one of four in the novel's action row, and the answer is the same every
     * time.
     */
    val supported: Boolean
        get() = state.value.capabilities?.scopes?.contains(commentScope) == true

    fun feedback(comment: NovelComment): NovelCommentFeedback =
        (source as? NovelCommentFeedbackSource)?.getCommentFeedback(comment)
            ?: NovelCommentFeedback(
                positiveVote = if (state.value.capabilities?.downvotes == true) {
                    NovelCommentPositiveVote.UPVOTE
                } else {
                    NovelCommentPositiveVote.LIKE
                },
            )

    /**
     * Points this at a novel and its source.
     *
     * Called once. A source that does not implement [NovelCommentSource] leaves [supported] false
     * and nothing else here ever runs.
     */
    fun bind(source: Source?, manga: Manga?) {
        this.source = source as? NovelCommentSource
        this.manga = manga

        val capabilities = this.source?.commentCapabilities
        val feeds = (source as? NovelCommentFeedSource)?.commentFeeds.orEmpty()
            .filter { commentScope in it.capabilities.scopes }
        val feed = feeds.firstOrNull()
        val sorts = (feed?.capabilities ?: capabilities)?.sorts.orEmpty()
        mutableState.update {
            it.copy(
                capabilities = feed?.capabilities ?: capabilities,
                feeds = feeds,
                feed = feed,
                sorts = sorts,
                // A key stored from another site means nothing here, so it is kept only when this
                // one offers it; otherwise the source's own first order wins.
                sortKey = preferences.commentsSort.get().takeIf { key -> sorts.any { it.key == key } }
                    ?: sorts.firstOrNull()?.key,
                localSort = preferences.commentsLocalSort.get(),
            )
        }
    }

    /**
     * Follows the reader to another chapter, and starts that chapter's comments.
     *
     * Comments belong to the chapter on screen, so crossing a boundary in a continuous document
     * invalidates them exactly as opening a chapter from the list does. What is on screen is
     * dropped; what was fetched is kept in [drains], so turning back is free.
     */
    fun setChapter(chapter: Chapter?) {
        if (commentScope != NovelCommentScope.CHAPTER || this.chapter?.id == chapter?.id) return
        this.chapter = chapter
        loadJob?.cancel()
        mutableState.update { it.reset(chapterName = chapter?.name).copy(draft = "") }
        if (preferences.commentsAutoLoad.get()) show()
    }

    /**
     * Fetches the chapters either side of the open one, without showing them.
     *
     * Called by the reader alongside its own chapter preload, so that turning a page does not begin
     * with a spinner over comments that could have been fetched while the chapter was being read.
     */
    fun prefetch(chapters: List<Chapter>) {
        if (commentScope != NovelCommentScope.CHAPTER || !preferences.commentsAutoLoad.get()) return
        chapters.forEach { drainFor(it) }
    }

    /** Drops the threads outside the reader's own cache window, cancelling anything still running. */
    fun trim(keep: Set<Long>) {
        if (commentScope != NovelCommentScope.CHAPTER) return
        drains.keys
            .filter { it.chapterId != null && it.chapterId !in keep }
            .toList()
            .forEach { drains.remove(it)?.job?.cancel() }
    }

    /**
     * Called as the sheet opens, to start the thread unless something already has.
     *
     * Whether the sheet is on screen is the screen's business, not this class's — it is one boolean
     * that would otherwise put every comment, vote and fold into the reader screen's recomposition.
     */
    fun open() {
        if (state.value.loaded || state.value.loading) return
        if (preferences.commentsAutoLoad.get()) show()
    }

    /** Draft and reply target survive dismissing the sheet; changing chapters clears both. */
    fun close() = Unit

    fun setDraft(body: String) {
        if (!state.value.posting) mutableState.update { it.copy(draft = body) }
    }

    /** Throws away everything fetched for the open target and fetches it again. */
    fun reload() {
        if (state.value.posting || state.value.voting.isNotEmpty()) return
        drains.remove(key(chapter))?.job?.cancel()
        loadJob?.cancel()
        mutableState.update { it.reset().copy(replyingTo = it.replyingTo) }
        show()
    }

    /**
     * Carries on a fetch that stopped short of the end.
     *
     * Only reachable after [PAGE_LIMIT] pages, or after a failure part way down — the sheet has no
     * such button while the thread is still draining, because the next page is already on its way.
     */
    fun loadMore() {
        val source = source ?: return
        val target = target(chapter) ?: return
        val current = drains[key(chapter)] ?: return
        if (current.job.isActive || !current.pages.value.hasMore) return

        mutableState.update { it.copy(error = null) }
        current.pages.update { it.copy(done = false, hasMore = false, failure = null) }
        val feed = state.value.feed
        val order = sort()
        current.job = scope.launch { fill(source, target, current.pages, feed, order) }
    }

    /**
     * Fetches one comment's replies, for a site that serves them separately.
     *
     * Only ever reached when the source said [NovelCommentCapabilities.lazyReplies]; without it the
     * sheet assumes what arrived with the comment is all there is and never offers the row.
     */
    fun loadReplies(comment: NovelComment) {
        val source = source ?: return
        val target = target(chapter) ?: return
        val drain = drains[key(chapter)] ?: return
        if (comment.id in drain.pages.value.loadingReplies) return
        val next = drain.replyPages.getOrElse(comment.id) { 0 } + 1
        val feed = state.value.feed
        val order = sort()

        drain.pages.update { it.copy(loadingReplies = it.loadingReplies + comment.id) }
        mutableState.update { it.copy(loadingReplies = it.loadingReplies + comment.id).withRoots(it.roots) }
        scope.launch {
            val request = NovelCommentRequest(
                target = target,
                sort = order,
                page = next,
                cursor = drain.replyCursors[comment.id],
                parent = comment,
            )
            attempt { fetch(source, request, feed) }
                .onSuccess { result ->
                    drain.replyPages[comment.id] = next
                    drain.replyCursors[comment.id] = result.nextCursor
                    // A page that brought nothing new is the end of the replies whatever the site
                    // says about there being more, or the row would offer them again for ever.
                    val known = NovelCommentTree.find(NovelCommentTree.build(drain.pages.value.comments), comment.id)
                        ?.replies
                        ?.mapTo(mutableSetOf()) { reply -> reply.id }
                        .orEmpty()
                    val complete = !result.hasNextPage || result.comments.none { it.id !in known }
                    val attach = { roots: List<NovelComment> ->
                        NovelCommentTree.addReplies(roots, comment.id, result.comments, complete)
                    }
                    drain.pages.update {
                        it.copy(comments = attach(it.comments), loadingReplies = it.loadingReplies - comment.id)
                    }
                    // The spinner going out and the replies arriving are one update, not two.
                    // Two leaves a window where the row that asked for them is unchanged and no
                    // longer loading, and a reader who tapped it again in that window would be
                    // right to.
                    if (drains[key(chapter)] === drain) {
                        mutableState.update {
                            it.copy(loadingReplies = it.loadingReplies - comment.id).withRoots(attach(it.roots))
                        }
                    }
                }
                .onFailure { failure ->
                    logcat(LogPriority.WARN, failure) { "Could not load replies to ${comment.id}" }
                    drain.pages.update { it.copy(loadingReplies = it.loadingReplies - comment.id) }
                    if (drains[key(chapter)] === drain) {
                        mutableState.update {
                            it.withFailure(failure)
                                .copy(loadingReplies = it.loadingReplies - comment.id)
                                .withRoots(it.roots)
                        }
                    }
                }
        }
    }

    /**
     * Chooses one of the source's own orders, which means asking the site for it.
     *
     * Asked rather than applied here, because a site ranks from data it does not necessarily send —
     * see [NovelCommentsState.localSort]. Each order is kept as its own thread, so going back to one
     * already fetched costs nothing.
     */
    fun setSort(sort: NovelCommentSort) {
        if (state.value.posting || state.value.voting.isNotEmpty()) return
        if (state.value.sortKey == sort.key) return
        preferences.commentsSort.set(sort.key)
        loadJob?.cancel()
        mutableState.update { it.reset().copy(sortKey = sort.key, replyingTo = it.replyingTo) }
        show()
    }

    fun setFeed(feed: NovelCommentFeed) {
        if (feed !in state.value.feeds || feed == state.value.feed) return
        if (state.value.posting || state.value.voting.isNotEmpty() || state.value.draft.isNotBlank()) return
        loadJob?.cancel()
        mutableState.update {
            it.reset().copy(
                feed = feed,
                capabilities = feed.capabilities,
                sorts = feed.capabilities.sorts,
                sortKey = feed.capabilities.sorts.firstOrNull()?.key,
            )
        }
        show()
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

    /** Opens cached replies immediately, fetching the first page only when none arrived with the parent. */
    fun toggleReplies(comment: NovelComment) {
        val current = NovelCommentTree.find(state.value.roots, comment.id) ?: return
        val opening = current.id !in state.value.expandedReplies
        mutableState.update {
            val expanded = if (opening) it.expandedReplies + current.id else it.expandedReplies - current.id
            it.copy(expandedReplies = expanded).withRoots(it.roots)
        }
        if (opening && current.replies.isEmpty() && current.replyCount > 0 &&
            state.value.capabilities?.lazyReplies == true
        ) {
            loadReplies(current)
        }
    }

    /** Folds every top-level comment, which is how a long thread becomes a table of contents. */
    fun collapseAll() {
        mutableState.update {
            it.copy(collapsed = it.roots.mapTo(mutableSetOf()) { root -> root.id }, expandedReplies = emptySet())
                .withRoots(it.roots)
        }
    }

    fun expandAll() {
        mutableState.update {
            it.copy(collapsed = emptySet(), expandedReplies = NovelCommentTree.ids(it.roots)).withRoots(it.roots)
        }
    }

    /**
     * Shows one comment and its descendants as if it were the whole thread.
     *
     * The display depth cap is measured from the focused comment, so this is also how a reader gets
     * past "continue this thread" — the same move Reddit makes for the same reason.
     */
    fun focus(id: String?) {
        mutableState.update {
            it.copy(
                focus = id,
                collapsed = it.collapsed - id.orEmpty(),
                expandedReplies = if (id == null) it.expandedReplies else it.expandedReplies + id,
            ).withRoots(it.roots)
        }
    }

    /**
     * Casts, changes or withdraws a vote.
     *
     * Applied to the thread first and replaced with whatever the site reports, so the arrow responds
     * to the tap rather than to the round trip. A failure puts the old comment back and says so: a
     * vote that silently did not happen is worse than one that visibly failed.
     */
    fun vote(comment: NovelComment, vote: NovelCommentVote) {
        val source = source ?: return
        val drain = drains[key(chapter)] ?: return
        val capabilities = state.value.capabilities ?: return
        if (!capabilities.voting || comment.deleted || comment.id in drain.pages.value.voting) return
        if (vote == NovelCommentVote.DOWN && !capabilities.downvotes) return
        val current = NovelCommentTree.find(state.value.roots, comment.id) ?: return

        val wanted = if (current.vote == vote) NovelCommentVote.NONE else vote
        val optimistic = current.copy(vote = wanted, score = current.score?.plus(wanted.delta - current.vote.delta))
        drain.pages.update { it.copy(voting = it.voting + comment.id) }
        mutableState.update { it.copy(voting = it.voting + comment.id) }
        change(drain) { NovelCommentTree.replace(it, optimistic) }

        scope.launch {
            attempt { source.voteComment(current, wanted) }
                .onSuccess { updated -> change(drain) { NovelCommentTree.replace(it, updated) } }
                .onFailure { failure ->
                    logcat(LogPriority.WARN, failure) { "Could not vote on comment ${comment.id}" }
                    change(drain) { NovelCommentTree.replace(it, current) }
                    if (drains[key(chapter)] === drain) mutableState.update { it.withFailure(failure) }
                }
            drain.pages.update { it.copy(voting = it.voting - comment.id) }
            if (drains[key(chapter)] === drain) {
                mutableState.update { it.copy(voting = it.voting - comment.id) }
            }
        }
    }

    /** Opens the composer, either for a new comment or as a reply to [comment]. */
    fun replyTo(comment: NovelComment?) {
        if (state.value.posting) return
        if (comment != null && !canReply(comment)) return
        mutableState.update { it.copy(replyingTo = comment) }
    }

    fun canReply(comment: NovelComment): Boolean {
        val current = state.value
        val capabilities = current.capabilities ?: return false
        return capabilities.posting && !comment.deleted &&
            NovelCommentTree.ancestorsOf(current.roots, comment.id).size + 1 < capabilities.maxDepth
    }

    /** Posts what the composer holds, dropping it into the thread where the site put it. */
    fun post(body: String) {
        val source = source ?: return
        val target = target(chapter) ?: return
        val drain = drains[key(chapter)] ?: return
        if (state.value.capabilities?.posting != true || body.isBlank() || drain.pages.value.posting) return

        val parentId = state.value.replyingTo?.id
        drain.pages.update { it.copy(posting = true) }
        mutableState.update { it.copy(posting = true, error = null) }

        scope.launch {
            attempt { source.postComment(NovelCommentDraft(target, body.trim(), parentId)) }
                .onSuccess { posted ->
                    change(drain) { NovelCommentTree.insert(it, parentId, posted) }
                    drain.pages.update { it.copy(posting = false) }
                    if (drains[key(chapter)] === drain) {
                        mutableState.update {
                            it.copy(
                                posting = false,
                                replyingTo = null,
                                draft = "",
                                posted = it.posted + 1,
                                expandedReplies = if (parentId ==
                                    null
                                ) {
                                    it.expandedReplies
                                } else {
                                    it.expandedReplies + parentId
                                },
                            ).withRoots(it.roots)
                        }
                    }
                }
                .onFailure { failure ->
                    logcat(LogPriority.WARN, failure) { "Could not post a comment" }
                    drain.pages.update { it.copy(posting = false) }
                    if (drains[key(chapter)] === drain) {
                        mutableState.update { it.withFailure(failure).copy(posting = false) }
                    }
                }
        }
    }

    fun dismissError() {
        mutableState.update { it.copy(error = null) }
    }

    /**
     * Changes the thread and the rows drawn from it, together.
     *
     * The thread is where a change has to land: it is what survives a chapter turn, and what the
     * next page is merged into. The rows are what is on screen, and they are written here rather
     * than left to the collector in [show] because a vote has to answer the tap that caused it —
     * the collector gets there, but a frame later, and through a flow that may not have been
     * resumed yet. Applying the same change twice is why every one of these is idempotent.
     */
    private fun change(drain: Drain, transform: (List<NovelComment>) -> List<NovelComment>) {
        drain.pages.update { it.copy(comments = transform(it.comments)) }
        if (drains[key(chapter)] === drain) mutableState.update { it.withRoots(transform(it.roots)) }
    }

    /** Draws whatever the open target's thread holds, and keeps drawing it as it fills. */
    private fun show() {
        loadJob?.cancel()
        val drain = drainFor(chapter) ?: return
        // Read once rather than inside the collector, which runs for every page.
        val collapseNew = preferences.commentsCollapseReplies.get()
        loadJob = scope.launch {
            drain.pages.collect { thread -> mutableState.update { it.withThread(thread, collapseNew) } }
        }
    }

    /** The thread for one chapter, started if this is the first time anything asked for it. */
    private fun drainFor(chapter: Chapter?): Drain? {
        val source = source ?: return null
        val target = target(chapter) ?: return null
        drains[key(chapter)]?.let { return it }

        val pages = MutableStateFlow(NovelCommentThread())
        val feed = state.value.feed
        val order = sort()
        return Drain(
            pages,
            scope.launch {
                fill(source, target, pages, feed, order)
            },
        ).also { drains[key(chapter)] = it }
    }

    /**
     * Asks the site for page after page until it runs out, publishing each one as it lands.
     *
     * Published rather than accumulated and handed over at the end, so the first page is on screen
     * in one round trip while the rest arrive behind it — a thread of six pages would otherwise be
     * six round trips of spinner.
     *
     * Stops at [PAGE_LIMIT] pages counted from wherever it started, which is what makes resuming
     * after a stop possible rather than instantly capped again. The cap is not a guess about what a
     * reader wants: it is a guess about what a site will tolerate.
     */
    private suspend fun fill(
        source: NovelCommentSource,
        target: NovelCommentTarget,
        pages: MutableStateFlow<NovelCommentThread>,
        feed: NovelCommentFeed?,
        order: NovelCommentSort,
    ) {
        val paginated = (feed?.capabilities ?: source.commentCapabilities).paginated
        val stopAfter = pages.value.nextPage + PAGE_LIMIT - 1

        while (true) {
            val thread = pages.value
            val request = NovelCommentRequest(
                target = target,
                sort = order,
                page = thread.nextPage,
                cursor = thread.nextCursor,
            )
            val page = attempt { fetch(source, request, feed) }.getOrElse { failure ->
                logcat(LogPriority.WARN, failure) { "Could not load comments for ${manga?.title}" }
                pages.update { it.copy(done = true, hasMore = it.loaded, failure = failure) }
                return
            }

            // A page that repeats what is already here is the end of the thread whatever the site
            // says about there being more, or this would ask for the same page until the cap.
            val known = NovelCommentTree.ids(thread.comments)
            val added = page.comments.filterNot { it.id in known }
            val exhausted = added.isEmpty() || !page.hasNextPage || !paginated
            val capped = !exhausted && thread.nextPage >= stopAfter

            pages.update {
                it.copy(
                    comments = it.comments + added,
                    total = page.total ?: it.total,
                    loaded = true,
                    done = exhausted || capped,
                    hasMore = capped,
                    nextPage = it.nextPage + 1,
                    nextCursor = page.nextCursor,
                    failure = null,
                )
            }
            if (exhausted || capped) return

            // Paced rather than fetched in a burst. Nobody is waiting on the fifth page — the first
            // one is already being read — and a site that rate limits will say so on the third.
            delay(PAGE_DELAY_MS)
        }
    }

    /**
     * One call to the source, as a [Result] that cannot be a cancellation.
     *
     * `runCatching` on its own would catch one, and the handler that follows it does not suspend, so
     * it runs even though the coroutine is already dead — which put "StandaloneCoroutine was
     * cancelled" in front of the reader every time a chapter turn cancelled a load that was still in
     * flight. A cancellation is this class doing what it was told, and the only correct thing to do
     * with it is let it through.
     */
    private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
        Result.success(withIOContext { block() })
    } catch (e: Throwable) {
        if (e is CancellationException) throw e
        Result.failure(e)
    }

    /**
     * The order to ask the site for: whichever the reader chose, or the source's own first.
     *
     * A source with no orders gets a blank one, which costs it nothing — it declared that it has
     * nothing to sort by, and [NovelCommentsState.localSort] covers that case instead.
     */
    private fun sort(): NovelCommentSort {
        val current = state.value
        return current.sorts.firstOrNull { it.key == current.sortKey }
            ?: current.sorts.firstOrNull()
            ?: NovelCommentSort(key = "", label = "")
    }

    private suspend fun fetch(
        source: NovelCommentSource,
        request: NovelCommentRequest,
        feed: NovelCommentFeed?,
    ): NovelCommentPage = if (source is NovelCommentFeedSource && feed != null) {
        source.getComments(request, feed)
    } else {
        source.getComments(request)
    }

    /** One thread is one chapter's comments in one of the site's orders. */
    private fun key(chapter: Chapter?) = Key(chapter?.id, sort().key, state.value.feed?.key)

    /** What to ask about, or null when there is nothing to ask about yet. */
    private fun target(chapter: Chapter?): NovelCommentTarget? {
        val novel = manga?.toSManga() ?: return null
        return when (commentScope) {
            NovelCommentScope.NOVEL -> NovelCommentTarget(novel)
            NovelCommentScope.CHAPTER -> NovelCommentTarget(novel, chapter?.toSChapter() ?: return null)
        }
    }

    /** One thread being filled: what has arrived so far, and the coroutine filling it. */
    private class Drain(val pages: MutableStateFlow<NovelCommentThread>, var job: Job) {
        val replyPages = mutableMapOf<String, Int>()
        val replyCursors = mutableMapOf<String, String?>()
    }

    private data class Key(val chapterId: Long?, val sort: String, val feed: String?)

    private companion object {
        /** How many pages one fetch will ask for before it stops and offers the rest as a button. */
        const val PAGE_LIMIT = 20

        /** Long enough not to look like a scraper, short enough that a long thread still finishes. */
        const val PAGE_DELAY_MS = 350L
    }
}

/** How much a vote moves a score, for the optimistic update. */
private val NovelCommentVote.delta: Int
    get() = when (this) {
        NovelCommentVote.UP -> 1
        NovelCommentVote.NONE -> 0
        NovelCommentVote.DOWN -> -1
    }
