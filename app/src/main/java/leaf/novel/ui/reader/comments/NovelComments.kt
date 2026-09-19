package leaf.novel.ui.reader.comments

import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.merge
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
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes

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
 *
 * ### Every source that has the novel
 *
 * The novel's own source is only one of the places its readers talk. [matcher] finds the same novel
 * on the other installed comment sources, and their threads join this one: all together by default,
 * or one source at a time through [setOrigin]. Each source's thread is still fetched, paged and kept
 * on its own, exactly as a lone source's always was; only what reaches the sheet is merged. That
 * costs three things, each settled here so the sheet never has to know:
 *
 *  - **Ids.** A comment id is unique only on its own site, so another source's ids carry a prefix
 *    naming it ([Origin.tag]), and every call back to a source takes it off again. The novel's own
 *    source keeps its ids untouched.
 *  - **Order.** One site's "top" means nothing next to another's, so a merged thread is ordered
 *    here by [NovelCommentsState.localSort], and a site's own orders are offered only while it is
 *    the one source showing.
 *  - **Tabs.** Feeds are matched by [NovelCommentFeed.key], with a source that declares none
 *    counted as [COMMENTS], so one site's reviews sit with another's reviews.
 */
class NovelComments(
    private val scope: CoroutineScope,
    private val preferences: NovelReaderPreferences,
    /** Which comments this instance serves, for its whole life. */
    private val commentScope: NovelCommentScope = NovelCommentScope.CHAPTER,
    /** Finds the novel on the other sources. Null keeps to the novel's own. */
    private val matcher: NovelCommentMatcher? = null,
    /**
     * Where fetched threads outlive this instance — [NovelCommentCache.shared] outside tests.
     *
     * The reader's own chapter cache in all but name, and kept for the same reason: a reader who
     * turns back a chapter, or comes back to the novel, has already paid for those comments, and a
     * second fetch of them is a request the site did not need to serve.
     */
    private val cache: NovelCommentCache = NovelCommentCache(),
) {

    private val mutableState = MutableStateFlow(NovelCommentsState(scope = commentScope))
    val state: StateFlow<NovelCommentsState> = mutableState.asStateFlow()

    /** The novel's own source first, then whatever [matcher] found. */
    @Volatile
    private var origins: List<Origin> = emptyList()
    private var chapter: Chapter? = null

    /** Watches the threads on screen. Cancelled and restarted whenever what is on screen changes. */
    private var loadJob: Job? = null
    private var matchJob: Job? = null

    /**
     * One entry per thread asked for, keyed by source, by chapter — null for the novel's own — by
     * feed and by order, because a site's orders are its own answers and each one is a different
     * listing.
     */
    private val drains = ConcurrentHashMap<Key, Drain>()

    /** The threads the sheet is drawn from, one per source that serves the open tab and the filter. */
    @Volatile
    private var shown: List<Drain> = emptyList()

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

    fun feedback(comment: NovelComment): NovelCommentFeedback {
        val origin = originOf(comment.id)
        return (origin?.source as? NovelCommentFeedbackSource)?.getCommentFeedback(origin.untag(comment))
            ?: NovelCommentFeedback(
                positiveVote = if (capabilities(comment)?.downvotes == true) {
                    NovelCommentPositiveVote.UPVOTE
                } else {
                    NovelCommentPositiveVote.LIKE
                },
            )
    }

    /** What the source a comment came from can do with it, which in a merged thread varies by row. */
    fun capabilities(comment: NovelComment): NovelCommentCapabilities? =
        originOf(comment.id)?.feed(state.value.feed?.key)?.capabilities

    /**
     * The source a comment came from, named only while the sheet mixes comments from more than one —
     * a chapter that only one of the sources has comments on needs no label on every row.
     */
    fun sourceName(comment: NovelComment): String? =
        if (shown.count { it.pages.value.comments.isNotEmpty() } > 1) originOf(comment.id)?.source?.name else null

    /**
     * Points this at a novel and its source.
     *
     * Called once. A source that does not implement [NovelCommentSource] leaves [supported] false
     * and nothing else here ever runs — the other sources are only ever looked for on behalf of a
     * novel whose own source has comments.
     */
    fun bind(source: Source?, manga: Manga?) {
        val own = source as? NovelCommentSource
        origins = if (own != null && manga != null) listOf(Origin(own, manga.toSManga(), tag = "")) else emptyList()
        mutableState.update { it.copy(localSort = preferences.commentsLocalSort.get()).configured() }
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
        shown = emptyList()
        mutableState.update { it.reset(chapterName = chapter?.name).copy(draft = "", origins = describe()) }
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
        val sources = contributors()
        chapters.forEach { chapter -> sources.forEach { drainFor(it, chapter) } }
    }

    /**
     * Drops the threads outside the reader's own cache window, cancelling anything still running.
     *
     * What they fetched stays in [cache], so a chapter that comes back into the window picks up
     * where its thread stopped rather than starting again.
     */
    fun trim(keep: Set<Long>) {
        if (commentScope != NovelCommentScope.CHAPTER) return
        drains.entries
            .filter { it.key.chapterId != null && it.key.chapterId !in keep }
            .forEach { (key, drain) ->
                drains.remove(key)
                drain.job?.cancel()
            }
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

    /** Throws away everything fetched for what is on screen, from every source, and fetches it again. */
    fun reload() {
        if (state.value.posting || state.value.voting.isNotEmpty()) return
        contributors().forEach { origin ->
            val key = keyFor(origin, chapter) ?: return@forEach
            drains.remove(key)?.let { drain ->
                drain.job?.cancel()
                drain.cacheKey?.let(cache::remove)
            }
        }
        loadJob?.cancel()
        mutableState.update { it.reset().copy(replyingTo = it.replyingTo) }
        show()
    }

    /**
     * Carries on the fetches that stopped short of the end.
     *
     * Only reachable after [PAGE_LIMIT] pages, or after a failure part way down — the sheet has no
     * such button while the thread is still draining, because the next page is already on its way.
     */
    fun loadMore() {
        val stalled = shown.filter { it.job?.isActive != true && it.pages.value.hasMore }
        if (stalled.isEmpty()) return

        mutableState.update { it.copy(error = null) }
        stalled.forEach { drain ->
            drain.update { it.copy(done = false, hasMore = false, failure = null) }
            drain.job = scope.launch { fill(drain) }
        }
    }

    /**
     * Fetches one comment's replies, for a site that serves them separately.
     *
     * Only ever reached when the source said [NovelCommentCapabilities.lazyReplies]; without it the
     * sheet assumes what arrived with the comment is all there is and never offers the row.
     */
    fun loadReplies(comment: NovelComment) {
        val drain = shownDrainOf(comment.id) ?: return
        val origin = drain.origin
        val target = drain.target ?: return
        val id = origin.untagged(comment.id)
        if (id in drain.pages.value.loadingReplies) return
        val parent = NovelCommentTree.find(NovelCommentTree.build(drain.pages.value.comments), id) ?: return
        val next = drain.pages.value.replyPages.getOrElse(id) { 0 } + 1

        change(drain) { it.copy(loadingReplies = it.loadingReplies + id) }
        scope.launch {
            val request = NovelCommentRequest(
                target = target,
                sort = drain.order,
                page = next,
                cursor = drain.pages.value.replyCursors[id],
                parent = parent,
            )
            attempt { origin.getComments(request, drain.feed) }
                .onSuccess { result ->
                    // A page that brought nothing new is the end of the replies whatever the site
                    // says about there being more, or the row would offer them again for ever.
                    val known = NovelCommentTree.find(NovelCommentTree.build(drain.pages.value.comments), id)
                        ?.replies
                        ?.mapTo(mutableSetOf()) { reply -> reply.id }
                        .orEmpty()
                    val complete = !result.hasNextPage || result.comments.none { it.id !in known }
                    // The spinner going out and the replies arriving are one update, not two.
                    // Two leaves a window where the row that asked for them is unchanged and no
                    // longer loading, and a reader who tapped it again in that window would be
                    // right to.
                    change(drain) {
                        it.copy(
                            comments = NovelCommentTree.addReplies(it.comments, id, result.comments, complete),
                            loadingReplies = it.loadingReplies - id,
                            replyPages = it.replyPages + (id to next),
                            replyCursors = it.replyCursors + (id to result.nextCursor),
                        )
                    }
                }
                .onFailure { failure ->
                    logcat(LogPriority.WARN, failure) { "Could not load replies to $id" }
                    drain.update { it.copy(loadingReplies = it.loadingReplies - id) }
                    if (drain in shown) publish { it.withFailure(failureOf(drain, failure)) }
                }
        }
    }

    /**
     * Chooses one of the source's own orders, which means asking the site for it.
     *
     * Asked rather than applied here, because a site ranks from data it does not necessarily send —
     * see [NovelCommentsState.localSort]. Each order is kept as its own thread, so going back to one
     * already fetched costs nothing. Only offered while one source is showing.
     */
    fun setSort(sort: NovelCommentSort) {
        if (state.value.posting || state.value.voting.isNotEmpty()) return
        if (state.value.sortKey == sort.key) return
        val origin = contributors().singleOrNull() ?: return
        origin.sortKey = sort.key
        preferences.commentsSort.set(sort.key)
        loadJob?.cancel()
        mutableState.update { it.reset().copy(replyingTo = it.replyingTo).configured() }
        show()
    }

    fun setFeed(feed: NovelCommentFeed) {
        val current = state.value
        if (current.feeds.none { it.key == feed.key } || feed.key == current.feed?.key) return
        if (current.posting || current.voting.isNotEmpty() || current.draft.isNotBlank()) return
        loadJob?.cancel()
        // A tab starts at each site's own first order, as a single source's tabs always have.
        origins.forEach { it.sortKey = null }
        mutableState.update { it.reset().copy(feed = feed).configured() }
        show()
    }

    /** Shows one source's comments, or with null every source's together. */
    fun setOrigin(id: Long?) {
        val current = state.value
        if (id == current.origin || (id != null && origins.none { it.source.id == id })) return
        if (current.posting || current.voting.isNotEmpty() || current.draft.isNotBlank()) return
        loadJob?.cancel()
        mutableState.update { it.reset().copy(origin = id).configured() }
        show()
    }

    /**
     * Chooses one of the reader's own orders, which means reordering what is already here.
     *
     * No request: these exist precisely because the site has no orders to ask it for — or, with
     * several sources showing, because no one site's order can rank another's comments.
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
            capabilities(current)?.lazyReplies == true
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
        val drain = shownDrainOf(comment.id) ?: return
        val capabilities = drain.feed.capabilities
        val id = drain.origin.untagged(comment.id)
        if (!capabilities.voting || comment.deleted || id in drain.pages.value.voting) return
        if (vote == NovelCommentVote.DOWN && !capabilities.downvotes) return
        val current = NovelCommentTree.find(NovelCommentTree.build(drain.pages.value.comments), id) ?: return

        val wanted = if (current.vote == vote) NovelCommentVote.NONE else vote
        val optimistic = current.copy(vote = wanted, score = current.score?.plus(wanted.delta - current.vote.delta))
        change(drain) { it.copy(voting = it.voting + id, comments = NovelCommentTree.replace(it.comments, optimistic)) }

        scope.launch {
            attempt { drain.origin.source.voteComment(current, wanted) }
                .onSuccess { updated ->
                    change(drain) { it.copy(comments = NovelCommentTree.replace(it.comments, updated)) }
                }
                .onFailure { failure ->
                    logcat(LogPriority.WARN, failure) { "Could not vote on comment $id" }
                    change(drain) { it.copy(comments = NovelCommentTree.replace(it.comments, current)) }
                    if (drain in shown) mutableState.update { it.withFailure(failureOf(drain, failure)) }
                }
            change(drain) { it.copy(voting = it.voting - id) }
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

    /**
     * Posts what the composer holds, dropping it into the thread where the site put it.
     *
     * Only with one source showing: a comment written into a merged thread has no one site to go to,
     * and the merged capabilities say so by never claiming [NovelCommentCapabilities.posting].
     */
    fun post(body: String) {
        val drain = shown.singleOrNull() ?: return
        val target = drain.target ?: return
        if (state.value.capabilities?.posting != true || body.isBlank() || drain.pages.value.posting) return

        val shownParentId = state.value.replyingTo?.id
        val parentId = shownParentId?.let(drain.origin::untagged)
        change(drain) { it.copy(posting = true) }
        mutableState.update { it.copy(error = null) }

        scope.launch {
            attempt { drain.origin.source.postComment(NovelCommentDraft(target, body.trim(), parentId)) }
                .onSuccess { posted ->
                    drain.update {
                        it.copy(comments = NovelCommentTree.insert(it.comments, parentId, posted), posting = false)
                    }
                    if (drain in shown) {
                        publish {
                            it.copy(
                                replyingTo = null,
                                draft = "",
                                posted = it.posted + 1,
                                expandedReplies = if (shownParentId == null) {
                                    it.expandedReplies
                                } else {
                                    it.expandedReplies + shownParentId
                                },
                            )
                        }
                    }
                }
                .onFailure { failure ->
                    logcat(LogPriority.WARN, failure) { "Could not post a comment" }
                    drain.update { it.copy(posting = false) }
                    if (drain in shown) publish { it.withFailure(failure) }
                }
        }
    }

    fun dismissError() {
        mutableState.update { it.copy(error = null) }
    }

    /**
     * Changes a thread, and the sheet with it when the thread is on screen.
     *
     * The thread is where a change has to land: it is what survives a chapter turn, and what the
     * next page is merged into. The sheet is redrawn here rather than left to the collector in
     * [show] because a vote has to answer the tap that caused it — the collector gets there, but a
     * frame later, and through a flow that may not have been resumed yet. Redrawing twice from the
     * same thread draws the same thing, which is what makes that safe.
     */
    private fun change(drain: Drain, transform: (NovelCommentThread) -> NovelCommentThread) {
        drain.update(transform)
        if (drain in shown) publish()
    }

    /** Draws whatever the open threads hold, and keeps drawing them as they fill. */
    private fun show() {
        loadJob?.cancel()
        match()
        val chapter = chapter
        shown = contributors().mapNotNull { drainFor(it, chapter) }
        if (shown.isEmpty()) return
        // Read once rather than inside the collector, which runs for every page.
        val collapseNew = preferences.commentsCollapseReplies.get()
        loadJob = scope.launch {
            shown.map { it.pages }.merge().collect { publish(collapseNew) }
        }
    }

    /**
     * Draws the threads on screen as one.
     *
     * The single place the sheet is derived from what was fetched: called for every page that lands
     * and for every change made to a thread here. With one source showing it is that source's
     * thread untouched. With several, each is prefixed by its source, a site that sends every reply
     * has its counts settled at what it sent — the reader assumes that of it anyway, and a merged
     * thread offers lazy replies for whichever site has them — and one source's failure is named as
     * that source's rather than passed off as the sheet's.
     *
     * @param then applied in the same update, for a change that has to land with the redraw rather
     * than a frame after it.
     */
    private fun publish(
        collapseNew: Boolean = false,
        then: (NovelCommentsState) -> NovelCommentsState = { it },
    ) {
        val threads = shown.map { it to it.pages.value }
        val merged = threads.size > 1
        val arrived = threads.any { (_, thread) -> thread.loaded }
        val done = threads.all { (_, thread) -> thread.done }
        val failed = threads.firstOrNull { (_, thread) -> thread.failure != null }
        val thread = NovelCommentThread(
            comments = threads.flatMap { (drain, thread) ->
                drain.origin.present(thread.comments, settle = merged && !drain.feed.capabilities.lazyReplies)
            },
            voting = threads.flatMapTo(mutableSetOf()) { (drain, thread) -> thread.voting.map(drain.origin::tagged) },
            loadingReplies = threads.flatMapTo(mutableSetOf()) { (drain, thread) ->
                thread.loadingReplies.map(drain.origin::tagged)
            },
            posting = threads.any { (_, thread) -> thread.posting },
            total = threads.map { (_, thread) -> thread.total }.takeIf { null !in it }?.sumOf { it ?: 0 },
            loaded = arrived,
            done = done,
            hasMore = threads.any { (_, thread) -> thread.hasMore },
            // A source that failed while the rest are still on their way is not yet the sheet's
            // failure: its error would stand in the place of comments that are about to arrive.
            failure = failed?.takeIf { arrived || done }?.let { (drain, thread) -> failureOf(drain, thread.failure!!) },
        )
        val described = describe()
        mutableState.update { then(it.copy(origins = described).withThread(thread, collapseNew)) }
    }

    /**
     * The tabs, orders and capabilities for the sources the filter lets through.
     *
     * A tab exists when any of them has a feed with its key, and is labelled by the first that names
     * it. The orders are a site's own only when one source is left to ask; the capabilities are that
     * source's, or with several the first one's with posting withdrawn and lazy replies claimed for
     * whichever of them has them.
     */
    private fun NovelCommentsState.configured(): NovelCommentsState {
        val filter = origin
        val visible = this@NovelComments.origins.filter { filter == null || it.source.id == filter }
        val tabs = visible.flatMap { it.feeds }
            .groupBy { it.key }
            .values
            .map { same -> same.firstOrNull { it.label.isNotBlank() } ?: same.first() }
        val tab = tabs.firstOrNull { it.key == feed?.key } ?: tabs.firstOrNull()
        val feeds = visible.mapNotNull { source -> source.feed(tab?.key)?.let { source to it } }
        val only = feeds.singleOrNull()
        val sorts = only?.second?.capabilities?.sorts.orEmpty()
        val capabilities = only?.second?.capabilities
            ?: feeds.map { it.second.capabilities }.let { all ->
                all.firstOrNull()?.copy(posting = false, lazyReplies = all.any { it.lazyReplies })
            }
        return copy(
            capabilities = capabilities,
            feeds = tabs,
            feed = tab,
            sorts = sorts,
            sortKey = only?.let { (source, feed) -> source.sort(feed).key }?.takeIf { sorts.isNotEmpty() },
            origins = describe(tab?.key),
        )
    }

    /** Each source as the filter shows it, counted from its thread for whatever is open now. */
    private fun describe(feed: String? = state.value.feed?.key): List<NovelCommentOrigin> = origins.map { origin ->
        val thread = keyFor(origin, chapter, feed)?.let { drains[it] }?.pages?.value
        NovelCommentOrigin(
            id = origin.source.id,
            name = origin.source.name,
            count = thread?.takeIf { it.loaded }?.let {
                it.total ?: NovelCommentTree.count(NovelCommentTree.build(it.comments))
            },
            loading = thread != null && !thread.done,
        )
    }

    /** The sources whose threads make up the open tab, as far as the filter allows. */
    private fun contributors(): List<Origin> {
        val current = state.value
        return origins.filter {
            (current.origin == null || it.source.id == current.origin) && it.feed(current.feed?.key) != null
        }
    }

    /**
     * Looks for the novel on the other sources, once, and brings their threads in when they are found.
     *
     * Started by the first [show] rather than by [bind], because binding happens as the novel's
     * screen is drawn and a search on every source is not something to spend on a screen that may
     * never open its comments.
     */
    private fun match() {
        val matcher = matcher ?: return
        val own = origins.firstOrNull() ?: return
        if (matchJob != null) return
        mutableState.update { it.copy(searching = true) }
        matchJob = scope.launch {
            val found = attempt { matcher.find(own.source, own.novel, commentScope) }.getOrElse { emptyList() }
            origins = origins + found.map { (source, novel) -> Origin(source, novel, tag = "$TAG${source.id}$TAG") }
            mutableState.update { it.copy(searching = false).configured() }
            if (loadJob?.isActive == true) show()
        }
    }

    /** One source's thread for one chapter, started if this is the first time anything asked for it. */
    private fun drainFor(origin: Origin, chapter: Chapter?): Drain? {
        val key = keyFor(origin, chapter) ?: return null
        drains[key]?.let { return it }
        val feed = origin.feed(key.feed) ?: return null
        return Drain(origin, feed, origin.sort(feed)).also { drain ->
            drains[key] = drain
            drain.job = scope.launch { start(drain, chapter) }
        }
    }

    /**
     * Works out what a thread is for, takes whatever an earlier screen already fetched of it, and
     * fetches the rest.
     *
     * For another source's chapter the first step is a lookup: its chapter numbered as the reader's
     * one, from a chapter list fetched once for the process. A source with no such chapter has an
     * empty thread, which is the truth, rather than an error.
     */
    private suspend fun start(drain: Drain, chapter: Chapter?) {
        val target = attempt { drain.origin.target(chapter) }.getOrElse { failure ->
            logcat(LogPriority.WARN, failure) { "Could not find ${chapter?.name} on ${drain.origin.source.name}" }
            drain.update { it.copy(done = true, failure = failure) }
            return
        }
        if (target == null) {
            drain.update { it.copy(loaded = true, done = true) }
            return
        }
        drain.target = target
        val key =
            ThreadKey(drain.origin.source.id, target.novel.url, target.chapter?.url, drain.feed.key, drain.order.key)
        drain.cacheKey = key
        cache.get<NovelCommentThread>(key, THREAD_MAX_AGE)?.let { drain.pages.value = it }
        if (!drain.pages.value.done) fill(drain)
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
    private suspend fun fill(drain: Drain) {
        val target = drain.target ?: return
        val paginated = drain.feed.capabilities.paginated
        val stopAfter = drain.pages.value.nextPage + PAGE_LIMIT - 1

        while (true) {
            val thread = drain.pages.value
            val request = NovelCommentRequest(
                target = target,
                sort = drain.order,
                page = thread.nextPage,
                cursor = thread.nextCursor,
            )
            val page = attempt { drain.origin.getComments(request, drain.feed) }.getOrElse { failure ->
                logcat(LogPriority.WARN, failure) {
                    "Could not load comments for ${target.novel.title} from ${drain.origin.source.name}"
                }
                drain.update { it.copy(done = true, hasMore = it.loaded, failure = failure) }
                return
            }

            // A page that repeats what is already here is the end of the thread whatever the site
            // says about there being more, or this would ask for the same page until the cap.
            val known = NovelCommentTree.ids(thread.comments)
            val added = page.comments.filterNot { it.id in known }
            val exhausted = added.isEmpty() || !page.hasNextPage || !paginated
            val capped = !exhausted && thread.nextPage >= stopAfter

            drain.update {
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

    /** A failure as the sheet reports it: with several sources showing, prefixed by whose it was. */
    private fun failureOf(drain: Drain, failure: Throwable): Throwable {
        if (shown.size < 2) return failure
        val message = failure.message?.takeIf { it.isNotBlank() } ?: failure::class.simpleName
        return Exception("${drain.origin.source.name}: $message", failure)
    }

    private fun originOf(id: String): Origin? = if (id.startsWith(TAG)) {
        origins.firstOrNull { it.tag.isNotEmpty() && id.startsWith(it.tag) }
    } else {
        origins.firstOrNull()
    }

    private fun shownDrainOf(id: String): Drain? {
        val origin = originOf(id) ?: return null
        return shown.firstOrNull { it.origin === origin }
    }

    /** One thread is one source's comments on one chapter, in one feed and one of its orders. */
    private fun keyFor(origin: Origin, chapter: Chapter?, feedKey: String? = state.value.feed?.key): Key? {
        val feed = origin.feed(feedKey) ?: return null
        if (commentScope == NovelCommentScope.CHAPTER && chapter == null) return null
        return Key(origin.source.id, chapter?.id, origin.sort(feed).key, feed.key)
    }

    /** One source's copy of the novel. [tag] prefixes its comment ids, and is empty for the novel's own. */
    private inner class Origin(val source: NovelCommentSource, val novel: SManga, val tag: String) {

        private val declared = (source as? NovelCommentFeedSource)?.commentFeeds.orEmpty()
            .filter { commentScope in it.capabilities.scopes }

        /** A source that declares no feeds has one all the same, and the sheet calls it comments. */
        val feeds: List<NovelCommentFeed> = declared.ifEmpty {
            listOfNotNull(
                NovelCommentFeed(COMMENTS, "", source.commentCapabilities)
                    .takeIf { commentScope in source.commentCapabilities.scopes },
            )
        }

        /**
         * The order last chosen here, kept when a feed offers it.
         *
         * A key stored from another site means nothing here, so it is honoured only when this one
         * offers it; otherwise the feed's own first order wins.
         */
        var sortKey: String? = preferences.commentsSort.get()

        fun feed(key: String?): NovelCommentFeed? = feeds.firstOrNull { it.key == key }

        /**
         * The order to ask the site for.
         *
         * A source with no orders gets a blank one, which costs it nothing — it declared that it has
         * nothing to sort by, and [NovelCommentsState.localSort] covers that case instead.
         */
        fun sort(feed: NovelCommentFeed): NovelCommentSort = feed.capabilities.sorts.let { sorts ->
            sorts.firstOrNull { it.key == sortKey } ?: sorts.firstOrNull() ?: NovelCommentSort(key = "", label = "")
        }

        suspend fun getComments(request: NovelCommentRequest, feed: NovelCommentFeed): NovelCommentPage =
            if (declared.isNotEmpty() && source is NovelCommentFeedSource) {
                source.getComments(request, feed)
            } else {
                source.getComments(request)
            }

        /** What to ask about, or null when there is nothing to ask about yet. */
        suspend fun target(chapter: Chapter?): NovelCommentTarget? = when {
            commentScope == NovelCommentScope.NOVEL -> NovelCommentTarget(novel)
            chapter == null -> null
            tag.isEmpty() -> NovelCommentTarget(novel, chapter.toSChapter())
            else -> matcher?.chapter(source, novel, chapter.chapterNumber)?.let { NovelCommentTarget(novel, it) }
        }

        fun tagged(id: String) = tag + id

        fun untagged(id: String) = id.removePrefix(tag)

        /** The comment as its own source knows it, as far as the source reads it back. */
        fun untag(comment: NovelComment) = if (tag.isEmpty()) {
            comment
        } else {
            comment.copy(id = untagged(comment.id), parentId = comment.parentId?.let(::untagged))
        }

        /**
         * The comments as the sheet sees them: prefixed, and with [settle], counted at what arrived.
         *
         * The novel's own comments pass through untouched unless they need settling, so a sheet
         * showing only them draws exactly what the source sent.
         */
        fun present(comments: List<NovelComment>, settle: Boolean): List<NovelComment> =
            if (tag.isEmpty() && !settle) {
                comments
            } else {
                comments.map {
                    it.copy(
                        id = tagged(it.id),
                        parentId = it.parentId?.let(::tagged),
                        replies = present(it.replies, settle),
                        replyCount = if (settle) it.replies.size else it.replyCount,
                    )
                }
            }
    }

    /** One source's thread being filled: what has arrived so far, and the coroutine filling it. */
    private inner class Drain(val origin: Origin, val feed: NovelCommentFeed, val order: NovelCommentSort) {
        val pages = MutableStateFlow(NovelCommentThread())
        var job: Job? = null

        /** Known once [start] has worked out what the thread is for. */
        @Volatile
        var target: NovelCommentTarget? = null

        @Volatile
        var cacheKey: ThreadKey? = null

        /** Changes the thread, and the process's copy of it. */
        fun update(transform: (NovelCommentThread) -> NovelCommentThread) {
            pages.update(transform)
            cacheKey?.let { cache.put(it, pages.value.settled()) }
        }
    }

    private data class Key(val source: Long, val chapterId: Long?, val sort: String, val feed: String)

    /** The same thread wherever it is asked for from, which a [Key]'s chapter id is not for another source. */
    private data class ThreadKey(
        val source: Long,
        val novel: String,
        val chapter: String?,
        val feed: String,
        val sort: String,
    )

    private companion object {
        /** How many pages one fetch will ask for before it stops and offers the rest as a button. */
        const val PAGE_LIMIT = 20

        /** Long enough not to look like a scraper, short enough that a long thread still finishes. */
        const val PAGE_DELAY_MS = 350L

        /**
         * The feed every source without feeds of its own is taken to have, so its comments meet the
         * ones other sources file under the same key. See `docs/leaf/comments/PROVIDERS.md`.
         */
        const val COMMENTS = "comments"

        /** Opens and closes another source's prefix. No site puts a unit separator in an id. */
        const val TAG = '\u001f'

        /** How long a thread fetched by an earlier screen is shown rather than fetched again. */
        val THREAD_MAX_AGE = 30.minutes.inWholeMilliseconds
    }
}

/** How much a vote moves a score, for the optimistic update. */
private val NovelCommentVote.delta: Int
    get() = when (this) {
        NovelCommentVote.UP -> 1
        NovelCommentVote.NONE -> 0
        NovelCommentVote.DOWN -> -1
    }
