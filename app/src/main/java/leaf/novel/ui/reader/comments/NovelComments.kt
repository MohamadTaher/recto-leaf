package leaf.novel.ui.reader.comments

import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import leaf.novel.api.NovelComment
import leaf.novel.api.NovelCommentCapabilities
import leaf.novel.api.NovelCommentConformance
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

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
 * Two bounds hold whatever a site does. One source is asked one thing at a time ([Origin.lock]),
 * so the number of installed extensions decides how many sites are in flight and never how many
 * requests one site is handling. And every call into a source is given [timeout], after which its
 * thread reports the timeout against that source's name and stops — without which a site that
 * accepts a connection and then says nothing leaves the sheet loading for ever.
 *
 * ### One thread from every source and every feed
 *
 * The novel's own source is only one of the places its readers talk. [matcher] finds the same novel
 * on the other installed comment sources, and every feed of every one of them — its comments, its
 * reviews — is fetched, paged and kept as a thread of its own, exactly as a lone feed always was.
 * Only what reaches the sheet is merged, and two filters narrow it: [setKind] to reviews or to
 * comments, [setOrigin] to one source. Merging costs two things, each settled here so the sheet
 * never has to know:
 *
 *  - **Ids.** A comment id is unique only within its own feed on its own site, so every other
 *    thread's ids carry a prefix naming both ([Drain.tag]), and every call back to a source takes it
 *    off again. The novel's own source keeps the ids of its first feed untouched.
 *  - **Order.** One site's "top" means nothing next to another's, so each site is asked for its
 *    default order and the thread is always ordered here, by [NovelCommentsState.sort].
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
    /** What one call to a source is given before its thread gives up on it. */
    private val timeout: Duration = COMMENT_TIMEOUT,
) {

    private val mutableState = MutableStateFlow(NovelCommentsState(scope = commentScope))
    val state: StateFlow<NovelCommentsState> = mutableState.asStateFlow()

    /** The novel's own source first, then whatever [matcher] found. */
    @Volatile
    private var origins: List<Origin> = emptyList()
    private var chapter: Chapter? = null

    /** Whose thread failed last, so [verification] knows which site to offer. */
    @Volatile
    private var failedOrigin: Origin? = null

    /** Watches the threads on screen. Cancelled and restarted whenever what is on screen changes. */
    private var loadJob: Job? = null
    private var matchJob: Job? = null

    /** One entry per thread asked for, keyed by source, by chapter — null for the novel's own — and by feed. */
    private val drains = ConcurrentHashMap<Key, Drain>()

    /** The threads the sheet is drawn from: every source and feed the filters let through. */
    @Volatile
    private var shown: List<Drain> = emptyList()

    /**
     * Which [shown] the sheet is drawing, counted up every time it changes.
     *
     * A collector cancelled by a chapter turn can be part way through drawing the chapter it was
     * watching; without this its last redraw could land after the next chapter's first, and the
     * sheet would show the old chapter's comments until the new one's thread next changed.
     */
    @Volatile
    private var generation = 0

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

    /**
     * What the source said about a comment, as it said it when the comment arrived.
     *
     * A lookup and nothing more. It is called from the row being drawn, so every recomposition of
     * a scrolling thread would otherwise be a call per visible row into extension code — and worse,
     * a call that could no longer tell which feed the row came from.
     */
    fun feedback(comment: NovelComment): NovelCommentFeedback {
        val drain = drainOf(comment.id)
        return drain?.pages?.value?.feedback?.get(drain.untagged(comment.id))
            ?: NovelCommentFeedback(
                positiveVote = if (capabilities(comment)?.downvotes == true) {
                    NovelCommentPositiveVote.UPVOTE
                } else {
                    NovelCommentPositiveVote.LIKE
                },
            )
    }

    /** What the feed a comment came from can do with it, which in a merged thread varies by row. */
    fun capabilities(comment: NovelComment): NovelCommentCapabilities? = drainOf(comment.id)?.feed?.capabilities

    /**
     * The source a comment came from, named on every row unless the sheet is filtered to one source
     * — then the filter already says it, and every row saying it again is noise.
     */
    fun sourceName(comment: NovelComment): String? =
        if (state.value.origin == null) drainOf(comment.id)?.origin?.source?.name else null

    /**
     * Points this at a novel and its source.
     *
     * Called once. A source that does not implement [NovelCommentSource] leaves [supported] false
     * and nothing else here ever runs — the other sources are only ever looked for on behalf of a
     * novel whose own source has comments.
     */
    fun bind(source: Source?, manga: Manga?) {
        val own = source as? NovelCommentSource
        origins = if (own != null && manga != null) listOf(Origin(own, manga.toSManga(), own = true)) else emptyList()
        mutableState.update { it.copy(sort = preferences.commentsLocalSort.get()).configured() }
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
        generation++
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
        val threads = contributors()
        chapters.forEach { chapter -> threads.forEach { (origin, feed) -> drainFor(origin, feed, chapter) } }
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

    /**
     * Throws away everything fetched for what is on screen, from every source, and fetches it again.
     *
     * Including the search for the novel on the other sources, which is otherwise done once and
     * never again. A reader who opened the sheet with no connection got their own source's comments
     * and an error, and then reload fetched that one source again for ever: the search had already
     * been attempted, and [match] asks only whether it has been. A search that completed and found
     * nothing is remembered by the matcher, so asking again costs a lookup rather than a request.
     */
    fun reload() {
        if (state.value.posting || state.value.voting.isNotEmpty()) return
        contributors().forEach { (origin, feed) ->
            drains.remove(keyFor(origin, feed, chapter) ?: return@forEach)?.let { drain ->
                drain.job?.cancel()
                drain.cacheKey?.let(cache::remove)
            }
        }
        loadJob?.cancel()
        matchJob?.cancel()
        matchJob = null
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
        val drain = drainOf(comment.id) ?: return
        val target = drain.target ?: return
        val id = drain.untagged(comment.id)
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
            attempt { drain.fetch(request) }
                .onSuccess { fetched ->
                    val result = fetched.page
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
                            feedback = it.feedback + fetched.feedback,
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

    /** Redraws the thread in another order. No request: the order is applied here, to what arrived. */
    fun setSort(sort: NovelCommentLocalSort) {
        if (sort == state.value.sort) return
        preferences.commentsLocalSort.set(sort)
        mutableState.update { it.copy(sort = sort) }
        publish()
    }

    /** Narrows the thread to reviews or to comments, or with [NovelCommentKind.ALL] shows both. */
    fun setKind(kind: NovelCommentKind) {
        val current = state.value
        if (kind == current.kind || (kind != NovelCommentKind.ALL && kind !in current.kinds)) return
        if (current.posting || current.voting.isNotEmpty() || current.draft.isNotBlank()) return
        loadJob?.cancel()
        mutableState.update { it.reset().copy(kind = kind).configured() }
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
        val drain = drainOf(comment.id) ?: return
        val capabilities = drain.feed.capabilities
        val id = drain.untagged(comment.id)
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
     * Only with one feed of one source showing: a comment written into a merged thread has no one
     * place to go, and the merged capabilities say so by never claiming
     * [NovelCommentCapabilities.posting].
     */
    fun post(body: String) {
        val drain = shown.singleOrNull() ?: return
        val target = drain.target ?: return
        if (state.value.capabilities?.posting != true || body.isBlank() || drain.pages.value.posting) return

        val shownParentId = state.value.replyingTo?.id
        val parentId = shownParentId?.let(drain::untagged)
        change(drain) { it.copy(posting = true) }
        mutableState.update { it.copy(error = null) }

        scope.launch {
            attempt { drain.origin.source.postComment(NovelCommentDraft(target, body.trim(), parentId)) }
                .onSuccess { posted ->
                    val feedback = attempt { drain.feedbackFor(posted) }.getOrDefault(emptyMap())
                    drain.update {
                        it.copy(
                            comments = NovelCommentTree.insert(it.comments, parentId, posted),
                            feedback = it.feedback + feedback,
                            posting = false,
                        )
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
        shown = contributors().mapNotNull { (origin, feed) -> drainFor(origin, feed, chapter) }
        val current = ++generation
        if (shown.isEmpty()) return
        // Read once rather than inside the collector, which runs for every page.
        val collapseNew = preferences.commentsCollapseReplies.get()
        loadJob = scope.launch {
            shown.map { it.pages }.merge().collect { publish(collapseNew, current) }
        }
    }

    /**
     * Draws the threads on screen as one.
     *
     * The single place the sheet is derived from what was fetched: called for every page that lands
     * and for every change made to a thread here. Every comment is ordered by its likes, whichever
     * source sent it. With several threads showing, each is prefixed by its tag, a site that sends
     * every reply has its counts settled at what it sent — the reader assumes that of it anyway, and
     * a merged thread offers lazy replies for whichever site has them — and one thread's failure is
     * named as its source's rather than passed off as the sheet's.
     *
     * @param current the [generation] the caller is drawing; a redraw for an older one is dropped.
     * @param then applied in the same update, for a change that has to land with the redraw rather
     * than a frame after it.
     */
    private fun publish(
        collapseNew: Boolean = false,
        current: Int = generation,
        then: (NovelCommentsState) -> NovelCommentsState = { it },
    ) {
        val threads = shown.map { it to it.pages.value }
        val merged = threads.size > 1
        val arrived = threads.any { (_, thread) -> thread.loaded }
        val done = threads.all { (_, thread) -> thread.done }
        val failed = threads.firstOrNull { (_, thread) -> thread.failure != null }
        val likes = HashMap<String, Int>()
        // Filtered per comment rather than per feed, because a comments feed can be carrying
        // reviews: see NovelCommentKind.admits(feed, comment).
        val kind = state.value.kind
        val presented = threads.flatMap { (drain, thread) ->
            drain.present(thread, settle = merged && !drain.feed.capabilities.lazyReplies, likes)
                .filter { kind.admits(drain.feed, it) }
        }
        var reviewCount = 0
        var commentCount = 0
        threads.filter { (_, thread) -> thread.loaded }.forEach { (drain, thread) ->
            val roots = NovelCommentTree.build(thread.comments)
            val total = thread.total ?: NovelCommentTree.count(roots)
            if (NovelCommentKind.REVIEWS.admits(drain.feed)) {
                reviewCount += total
            } else {
                // The site's own total counts everything the feed holds, so the reviews found
                // inside it come off the comment tally rather than being added on top of it.
                val reviews = roots.count { NovelCommentKind.REVIEWS.admits(drain.feed, it) }
                reviewCount += reviews
                commentCount += (total - reviews).coerceAtLeast(0)
            }
        }
        val thread = NovelCommentThread(
            comments = NovelCommentTree.sortedBy(presented, state.value.sort.comparator { likes[it.id] ?: 0 }),
            voting = threads.flatMapTo(mutableSetOf()) { (drain, thread) -> thread.voting.map(drain::tagged) },
            loadingReplies = threads.flatMapTo(mutableSetOf()) { (drain, thread) ->
                thread.loadingReplies.map(drain::tagged)
            },
            posting = threads.any { (_, thread) -> thread.posting },
            total = threads.map { (_, thread) -> thread.total }.takeIf { null !in it }?.sumOf { it ?: 0 },
            loaded = arrived,
            done = done,
            hasMore = threads.any { (_, thread) -> thread.hasMore },
            // A thread that failed while the rest are still on their way is not yet the sheet's
            // failure: its error would stand in the place of comments that are about to arrive.
            failure = failed?.takeIf { arrived || done }?.let { (drain, thread) -> failureOf(drain, thread.failure!!) },
        )
        val described = describe()
        mutableState.update {
            if (current != generation) return@update it
            val counted = it.copy(
                origins = described,
                reviewCount = reviewCount,
                commentCount = commentCount,
            )
            then(counted.withThread(thread, collapseNew))
        }
    }

    /**
     * What the filters allow, and what the threads they let through can do.
     *
     * The reviews filter is offered only while the sources on show have both reviews and comments,
     * and falls back to both when a change of source leaves it pointing at nothing. The capabilities
     * are the one thread's when one is showing, or with several the first one's with posting
     * withdrawn and lazy replies claimed for whichever of them has them.
     */
    private fun NovelCommentsState.configured(): NovelCommentsState {
        val filter = origin
        val visible = this@NovelComments.origins.filter { filter == null || it.source.id == filter }
        val kinds = visible.flatMap { it.feeds }.mapTo(mutableSetOf()) {
            if (NovelCommentKind.REVIEWS.admits(it)) NovelCommentKind.REVIEWS else NovelCommentKind.COMMENTS
        }
        val kind = kind.takeIf { it == NovelCommentKind.ALL || it in kinds } ?: NovelCommentKind.ALL
        val feeds = visible.flatMap { it.feeds.filter(kind::admits) }
        val capabilities = feeds.singleOrNull()?.capabilities
            ?: feeds.map { it.capabilities }.let { all ->
                all.firstOrNull()?.copy(posting = false, lazyReplies = all.any { it.lazyReplies })
            }
        return copy(capabilities = capabilities, kind = kind, kinds = kinds, origins = describe(kind))
    }

    /** Each source as the filter shows it, counted over its threads for whatever is open now. */
    private fun describe(kind: NovelCommentKind = state.value.kind): List<NovelCommentOrigin> = origins.map { origin ->
        val threads = origin.feeds.filter(kind::admits)
            .mapNotNull { feed -> keyFor(origin, feed, chapter)?.let { drains[it] }?.pages?.value }
        NovelCommentOrigin(
            id = origin.source.id,
            name = origin.source.name,
            count = threads.filter { it.loaded }.takeIf { it.isNotEmpty() }?.sumOf {
                it.total ?: NovelCommentTree.count(NovelCommentTree.build(it.comments))
            },
            loading = threads.any { !it.done },
        )
    }

    /** Every source and feed whose thread makes up what the filters allow. */
    private fun contributors(): List<Pair<Origin, NovelCommentFeed>> {
        val current = state.value
        return origins
            .filter { current.origin == null || it.source.id == current.origin }
            // Still by feed. Letting every feed through so the reviews filter could reach the
            // reviews hiding in a comments feed also keeps that feed's pending replies alive while
            // it is filtered away, which is a documented invariant and a worse thing to break than
            // this is to gain. What a starred comment gets instead is in NovelCommentKind.
            .flatMap { origin -> origin.feeds.filter(current.kind::admits).map { origin to it } }
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
            // Cleared before the search rather than rebuilt after it, so a second search — which is
            // what reload is — does not list every source it finds again twice, and so each match
            // below can simply be appended as it lands.
            origins = origins.filter { it.own }
            // Kept by the place its source had in the list rather than by when it answered, so the
            // sheet reads the same each time it is opened however fast the sites happen to be.
            val found = sortedMapOf<Int, Origin>()
            try {
                matcher.find(own.source, own.novel, commentScope).collect { match ->
                    // Brought in one at a time: a site that answered quickly is read while the slow
                    // ones are still being asked, instead of every site waiting for the last.
                    found[match.order] = Origin(match.source, match.novel, own = false)
                    origins = origins.filter { it.own } + found.values
                    mutableState.update { it.configured() }
                    if (loadJob?.isActive == true) show()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logcat(LogPriority.WARN, e) { "Could not look for ${own.novel.title} on the other sources" }
            }
            mutableState.update { it.copy(searching = false).configured() }
            if (loadJob?.isActive == true) show()
        }
    }

    /** One feed's thread for one chapter, started if this is the first time anything asked for it. */
    private fun drainFor(origin: Origin, feed: NovelCommentFeed, chapter: Chapter?): Drain? {
        val key = keyFor(origin, feed, chapter) ?: return null
        drains[key]?.let { return it }
        return Drain(origin, feed).also { drain ->
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
            val fetched = attempt { drain.fetch(request) }.getOrElse { failure ->
                logcat(LogPriority.WARN, failure) {
                    "Could not load comments for ${target.novel.title} from ${drain.origin.source.name}"
                }
                drain.update { it.copy(done = true, hasMore = it.loaded, failure = failure) }
                return
            }
            val page = fetched.page

            // A page that repeats what is already here is the end of the thread whatever the site
            // says about there being more, or this would ask for the same page until the cap.
            val known = NovelCommentTree.ids(thread.comments)
            drain.audit(page, known)
            val added = page.comments.filterNot { it.id in known }
            val exhausted = added.isEmpty() || !page.hasNextPage || !paginated
            val capped = !exhausted && thread.nextPage >= stopAfter

            drain.update {
                it.copy(
                    comments = it.comments + added,
                    // Read under the same lock as the fetch that produced them; see [Origin.lock].
                    feedback = it.feedback + fetched.feedback,
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
        Result.success(withCommentTimeout(timeout) { withIOContext { block() } })
    } catch (e: Throwable) {
        if (e is CancellationException) throw e
        Result.failure(e)
    }

    /**
     * A failure as the sheet reports it: with several threads showing, prefixed by whose it was.
     *
     * Also the one place every failure passes through, so it is where the site that produced it is
     * remembered for [verification].
     */
    private fun failureOf(drain: Drain, failure: Throwable): Throwable {
        failedOrigin = drain.origin
        if (shown.size < 2) return failure
        val message = failure.message?.takeIf { it.isNotBlank() } ?: failure::class.simpleName
        return Exception("${drain.origin.source.name}: $message", failure)
    }

    /**
     * The site to open by hand, and the novel's page on it.
     *
     * Some sites guard themselves with a check no request can answer — a form served with an
     * ordinary success, asking whoever is on the other end to prove they are a person. An
     * extension can try to clear one, but trying is all it can do: a check that wants a human gets
     * one only if the reader is given somewhere to go. This is that somewhere, and it is worth
     * offering whatever the failure was, because a site that will not answer is a site worth
     * looking at.
     *
     * The failing source where one is known, the novel's own otherwise.
     */
    fun verification(): NovelCommentVerification? {
        val origin = failedOrigin ?: origins.firstOrNull() ?: return null
        val source = origin.source as? HttpSource ?: return null
        val url = runCatching { source.getMangaUrl(origin.novel) }.getOrNull() ?: source.baseUrl
        return NovelCommentVerification(sourceId = source.id, name = source.name, url = url)
    }

    /** The thread on screen a comment belongs to, read off its id's tag. */
    private fun drainOf(id: String): Drain? {
        val shown = shown
        return if (id.startsWith(TAG)) {
            shown.firstOrNull { it.tag.isNotEmpty() && id.startsWith(it.tag) }
        } else {
            shown.firstOrNull { it.tag.isEmpty() }
        }
    }

    /** One thread is one feed of one source, on one chapter. */
    private fun keyFor(origin: Origin, feed: NovelCommentFeed, chapter: Chapter?): Key? {
        if (commentScope == NovelCommentScope.CHAPTER && chapter == null) return null
        return Key(origin.source.id, chapter?.id, feed.key)
    }

    /** One source's copy of the novel. [own] is the novel's own source, whose first feed keeps its ids. */
    private inner class Origin(val source: NovelCommentSource, val novel: SManga, val own: Boolean) {

        /**
         * One request to this source at a time, however many feeds and chapters are being filled.
         *
         * [NovelCommentFeedbackSource] is handed a comment and nothing else, so an extension keys
         * whatever it parsed by comment id alone — and a source whose reviews and comments can both
         * number from one overwrites its own answer when the two feeds are parsed at once. Reading
         * the feedback under the same lock as the fetch that produced it is what makes "the feed
         * that just answered" a true statement rather than a likely one.
         *
         * It costs a source with several feeds their overlap, which is a fetch's worth of latency
         * on one site and no requests at all. A site seeing one comment request from us at a time
         * is the better shape anyway, and more so the more extensions are installed.
         */
        val lock = Mutex()

        private val declared = (source as? NovelCommentFeedSource)?.commentFeeds.orEmpty()
            .filter { commentScope in it.capabilities.scopes }

        /** A source that declares no feeds has one all the same, and it counts as comments. */
        val feeds: List<NovelCommentFeed> = declared.ifEmpty {
            listOfNotNull(
                NovelCommentFeed(NovelCommentFeed.COMMENTS, "", source.commentCapabilities)
                    .takeIf { commentScope in source.commentCapabilities.scopes },
            )
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
            own -> NovelCommentTarget(novel, chapter.toSChapter())
            else -> matcher?.chapter(source, novel, chapter.chapterNumber)?.let { NovelCommentTarget(novel, it) }
        }
    }

    /** One feed's thread being filled: what has arrived so far, and the coroutine filling it. */
    private inner class Drain(val origin: Origin, val feed: NovelCommentFeed) {
        val pages = MutableStateFlow(NovelCommentThread())
        var job: Job? = null

        /**
         * The site's own default order, which is all it is ever asked for.
         *
         * A source with no orders gets a blank one, which costs it nothing — it declared that it has
         * nothing to sort by, and the thread is ordered here either way.
         */
        val order: NovelCommentSort = feed.capabilities.sorts.firstOrNull() ?: NovelCommentSort(key = "", label = "")

        /** Prefixes this thread's ids; empty for the novel's own source's first feed. */
        val tag: String = if (origin.own && feed == origin.feeds.firstOrNull()) {
            ""
        } else {
            "$TAG${origin.source.id}$TAG${feed.key}$TAG"
        }

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

        fun tagged(id: String) = tag + id

        fun untagged(id: String) = id.removePrefix(tag)

        /**
         * The comments as the sheet sees them: prefixed, and with [settle], counted at what arrived.
         *
         * An untagged thread passes through untouched unless it needs settling, so a sheet showing
         * only it draws exactly what the source sent. Each comment's likes go into [likes] under the
         * id the sheet will know it by, read while the source's own copy is still in hand.
         */
        fun present(thread: NovelCommentThread, settle: Boolean, likes: MutableMap<String, Int>): List<NovelComment> =
            present(thread.comments, thread.feedback, settle, likes)

        private fun present(
            comments: List<NovelComment>,
            feedback: Map<String, NovelCommentFeedback>,
            settle: Boolean,
            likes: MutableMap<String, Int>,
        ): List<NovelComment> = comments.map {
            likes[tagged(it.id)] = likes(it, feedback)
            if (tag.isEmpty() && !settle) {
                it.also { comment -> present(comment.replies, feedback, settle = false, likes) }
            } else {
                it.copy(
                    id = tagged(it.id),
                    parentId = it.parentId?.let(::tagged),
                    replies = present(it.replies, feedback, settle, likes),
                    replyCount = if (settle) it.replies.size else it.replyCount,
                )
            }
        }

        /**
         * Holds a page against the contract, and says so in the log when it breaks it.
         *
         * Debug builds only, and for one reason: an extension that gets the shape wrong does not
         * fail, it produces a sheet that is quietly missing something — a score that is never drawn
         * because the feed did not claim to be scored, a reply past a maxDepth of one, an id that
         * cannot survive the merge. Whoever is writing the extension is the only person who can fix
         * any of it, and this is the build they are running.
         *
         * [NovelCommentConformance] is in `:novel-api`, so the same checks run from an extension's
         * own unit tests without a device; see `docs/leaf/comments/PROVIDERS.md`.
         */
        fun audit(page: NovelCommentPage, known: Set<String>) {
            if (!BuildConfig.DEBUG) return
            val violations = NovelCommentConformance.check(page, feed.capabilities, known) +
                (origin.source as? NovelCommentFeedbackSource)
                    ?.let { NovelCommentConformance.check(it, page.comments) }
                    .orEmpty()
            violations.forEach { logcat(LogPriority.WARN) { "${origin.source.name} [${feed.key}]: $it" } }
        }

        /**
         * A page, and what the source says about the comments on it, with nothing else asked of
         * that source in between; see [Origin.lock].
         */
        suspend fun fetch(request: NovelCommentRequest): Fetched = origin.lock.withLock {
            val page = origin.getComments(request, feed)
            Fetched(page, snapshot(page.comments))
        }

        /** The same, for a comment that arrived by itself — one this reader has just posted. */
        suspend fun feedbackFor(comment: NovelComment): Map<String, NovelCommentFeedback> =
            origin.lock.withLock { snapshot(listOf(comment)) }

        /**
         * What the source says about each of [comments] and their replies, asked once, now.
         *
         * Empty for a source that has nothing extra to say, which is most of them; the sheet's
         * fallback covers that case without an entry per comment.
         */
        private fun snapshot(comments: List<NovelComment>): Map<String, NovelCommentFeedback> {
            val source = origin.source as? NovelCommentFeedbackSource ?: return emptyMap()
            return buildMap { collect(source, comments) }
        }

        private fun MutableMap<String, NovelCommentFeedback>.collect(
            source: NovelCommentFeedbackSource,
            comments: List<NovelComment>,
        ) {
            comments.forEach {
                put(it.id, source.getCommentFeedback(it))
                collect(source, it.replies)
            }
        }

        /**
         * How liked a comment is: the site's own like count where it gives one apart from dislikes,
         * or else its score, which on a site with no downvote is the same thing.
         */
        private fun likes(comment: NovelComment, feedback: Map<String, NovelCommentFeedback>): Int =
            feedback[comment.id]?.likes ?: comment.score ?: 0
    }

    /** One page and the source's own word on what is in it, read together. */
    private class Fetched(val page: NovelCommentPage, val feedback: Map<String, NovelCommentFeedback>)

    private data class Key(val source: Long, val chapterId: Long?, val feed: String)

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

        /** Opens and closes a thread's prefix. No site puts a unit separator in an id. */
        const val TAG = '\u001f'

        /** How long a thread fetched by an earlier screen is shown rather than fetched again. */
        val THREAD_MAX_AGE = 30.minutes.inWholeMilliseconds
    }
}

/** How long one call to a site may take before whatever is waiting on it gives up. */
internal val COMMENT_TIMEOUT = 30.seconds

/**
 * [block], with the budget every call into an extension gets.
 *
 * Nothing here used to have one, so a site that accepted a connection and then said nothing left
 * its thread neither finished nor failed for ever: the sheet went on saying it was still loading,
 * that source's filter chip went on spinning, and "load more" — which only appears once a thread
 * has stopped — never came back. With a dozen extensions installed that is no longer an unlikely
 * site, it is a weekly one.
 *
 * The timeout is turned into an ordinary exception on the way out. `withTimeout` throws a
 * [TimeoutCancellationException], which is a [CancellationException], and everything in this
 * package reads one of those as the caller having gone away and rethrows it untouched — so left as
 * it is, a timeout would silently kill the fetch instead of reporting it.
 */
internal suspend fun <T> withCommentTimeout(timeout: Duration = COMMENT_TIMEOUT, block: suspend () -> T): T = try {
    withTimeout(timeout) { block() }
} catch (e: TimeoutCancellationException) {
    throw Exception("Timed out after $timeout", e)
}

/** How much a vote moves a score, for the optimistic update. */
private val NovelCommentVote.delta: Int
    get() = when (this) {
        NovelCommentVote.UP -> 1
        NovelCommentVote.NONE -> 0
        NovelCommentVote.DOWN -> -1
    }
