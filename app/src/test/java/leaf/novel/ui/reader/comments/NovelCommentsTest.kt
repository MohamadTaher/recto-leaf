package leaf.novel.ui.reader.comments

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import leaf.novel.api.NovelComment
import leaf.novel.api.NovelCommentCapabilities
import leaf.novel.api.NovelCommentDraft
import leaf.novel.api.NovelCommentFeed
import leaf.novel.api.NovelCommentFeedSource
import leaf.novel.api.NovelCommentPage
import leaf.novel.api.NovelCommentPositiveVote
import leaf.novel.api.NovelCommentRequest
import leaf.novel.api.NovelCommentScope
import leaf.novel.api.NovelCommentSort
import leaf.novel.api.NovelCommentSource
import leaf.novel.api.NovelCommentVote
import leaf.novel.ui.reader.setting.NovelReaderPreferences
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The session's comments, against a source that behaves the way a real one does: it takes time to
 * answer, it is cancelled mid-answer, and it serves replies a page at a time.
 *
 * Real dispatchers rather than a test scheduler, because what is being checked is what happens to a
 * call that is already on its way out — which is exactly what a virtual clock skips.
 */
class NovelCommentsTest {

    @Test
    fun `opening replies fetches once and a late response does not reopen a closed thread`() = runBlocking<Unit> {
        val parent = comment("parent", replyCount = 1)
        val response = CompletableDeferred<NovelCommentPage>()
        val source = FakeCommentSource(NovelCommentCapabilities(lazyReplies = true)) { request ->
            if (request.parent == null) NovelCommentPage(listOf(parent)) else response.await()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val comments = NovelComments(scope, preferences())
            comments.bind(source, Manga.create())
            comments.setChapter(chapter(1))
            waitFor("parent") { comments.state.value.loaded }
            comments.state.value.rows.map { it.key } shouldBe listOf("parent")
            comments.toggleReplies(parent)
            waitFor("reply request") { source.replyRequests() == 1 }
            comments.toggleReplies(parent)
            response.complete(NovelCommentPage(listOf(comment("reply"))))
            waitFor("cached response") { comments.state.value.roots.single().replies.size == 1 }
            comments.state.value.rows.map { it.key } shouldBe listOf("parent")
            comments.toggleReplies(parent)
            comments.state.value.rows.map { it.key } shouldBe listOf("parent", "reply")
            source.replyRequests() shouldBe 1
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `pending replies stay with their feed and cannot be fetched twice after switching filters`() =
        runBlocking<Unit> {
            val capabilities = NovelCommentCapabilities(scopes = setOf(NovelCommentScope.NOVEL), lazyReplies = true)
            val discussion = NovelCommentFeed("comments", "Comments", capabilities)
            val reviews = NovelCommentFeed("reviews", "Reviews", capabilities)
            val response = CompletableDeferred<NovelCommentPage>()
            val parent = comment("same-id", replyCount = 1)
            val source = FakeCommentSource { error("A declared feed must be supplied") }
            source.commentFeeds = listOf(discussion, reviews)
            source.feedResponse = { request, feed ->
                if (request.parent == null) {
                    NovelCommentPage(listOf(parent.copy(body = feed.key)))
                } else {
                    response.await()
                }
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val comments = NovelComments(scope, preferences(), NovelCommentScope.NOVEL)
                comments.bind(source, Manga.create())
                comments.open()
                // The same id in two feeds is two comments, not one drawn twice.
                waitFor("both feeds") { comments.state.value.roots.size == 2 }
                comments.state.value.roots.map { it.body } shouldBe listOf("comments", "reviews")
                comments.loadReplies(parent)
                waitFor("reply request") { source.feedRequests.any { it.second.parent != null } }
                source.feedRequests.single { it.second.parent != null }.first shouldBe "comments"
                comments.setKind(NovelCommentKind.REVIEWS)
                waitFor("reviews") { comments.state.value.roots.singleOrNull()?.body == "reviews" }
                comments.state.value.loadingReplies shouldBe emptySet()
                comments.setKind(NovelCommentKind.COMMENTS)
                waitFor("pending discussion reply") { "same-id" in comments.state.value.loadingReplies }
                comments.loadReplies(parent)
                delay(SETTLE_MS)
                source.feedRequests.count { it.second.parent != null } shouldBe 1
                comments.setKind(NovelCommentKind.ALL)
                waitFor("both feeds again") { comments.state.value.roots.size == 2 }
                comments.setKind(NovelCommentKind.REVIEWS)
                waitFor("reviews again") { comments.state.value.roots.singleOrNull()?.body == "reviews" }
                response.complete(NovelCommentPage(listOf(comment("reply"))))
                delay(SETTLE_MS)
                comments.state.value.roots.single().replies shouldBe emptyList()
                comments.setKind(NovelCommentKind.COMMENTS)
                waitFor("cached reply") { comments.state.value.roots.singleOrNull()?.replies?.size == 1 }
                comments.state.value.loadingReplies shouldBe emptySet()
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `reviews and comments keep independent paging and cached results`() = runBlocking<Unit> {
        val capabilities = NovelCommentCapabilities(
            scopes = setOf(NovelCommentScope.NOVEL),
            sorts = listOf(NovelCommentSort("newest", "Newest"), NovelCommentSort("top", "Top")),
        )
        val discussion = NovelCommentFeed("comments", "Comments", capabilities)
        val reviews = NovelCommentFeed("reviews", "Reviews", capabilities)
        val source = FakeCommentSource { error("A declared feed must be supplied") }
        source.commentFeeds = listOf(discussion, reviews)
        source.feedResponse = { request, feed ->
            NovelCommentPage(
                comments = listOf(comment(if (request.page == 1) "same-id" else "page-2").copy(body = feed.key)),
                hasNextPage = feed == discussion && request.page == 1,
            )
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val comments = NovelComments(scope, preferences(), NovelCommentScope.NOVEL)
            comments.bind(source, Manga.create())
            comments.open()
            waitFor("every page of both feeds") { comments.state.value.roots.size == 3 }
            source.feedRequests.size shouldBe 3
            // Each site is asked for its own default order; the sheet orders the rest itself.
            source.feedRequests.map { it.second.sort.key }.toSet() shouldBe setOf("newest")

            comments.setKind(NovelCommentKind.REVIEWS)
            waitFor("reviews") { comments.state.value.roots.map { it.body } == listOf("reviews") }
            comments.setKind(NovelCommentKind.COMMENTS)
            waitFor("comments") { comments.state.value.roots.map { it.body } == listOf("comments", "comments") }
            comments.setKind(NovelCommentKind.ALL)
            waitFor("both") { comments.state.value.roots.size == 3 }
            source.feedRequests.size shouldBe 3
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `novel reviews are not offered on chapters`() {
        val source = FakeCommentSource { NovelCommentPage(emptyList()) }
        source.commentFeeds = listOf(
            NovelCommentFeed("comments", "Comments", NovelCommentCapabilities()),
            NovelCommentFeed(
                "reviews",
                "Reviews",
                NovelCommentCapabilities(scopes = setOf(NovelCommentScope.NOVEL)),
            ),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val comments = NovelComments(scope, preferences())
            comments.bind(source, Manga.create())
            comments.state.value.kinds shouldBe setOf(NovelCommentKind.COMMENTS)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `keeps drafts and reply targets when the sheet closes`() = runBlocking<Unit> {
        val reply = comment("2")
        val parent = comment("1", replies = listOf(reply))
        val source = FakeCommentSource(NovelCommentCapabilities(posting = true, maxDepth = 2)) {
            NovelCommentPage(listOf(parent))
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val comments = NovelComments(scope, preferences())
            comments.bind(source, Manga.create())
            comments.setChapter(chapter(1))
            waitFor("comments") { comments.state.value.loaded }
            comments.setDraft("Keep this draft")
            comments.canReply(reply) shouldBe false
            comments.canReply(parent) shouldBe true
            comments.replyTo(parent)
            comments.close()
            comments.state.value.draft shouldBe "Keep this draft"
            comments.state.value.replyingTo shouldBe parent
            comments.reload()
            waitFor("refreshed comments") { comments.state.value.loaded }
            comments.state.value.draft shouldBe "Keep this draft"
            comments.state.value.replyingTo shouldBe parent
            comments.replyTo(null)
            comments.state.value.draft shouldBe "Keep this draft"
            comments.setChapter(chapter(2))
            comments.state.value.draft shouldBe ""
            comments.state.value.replyingTo shouldBe null
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `serializes votes and restores the original after rejection`() = runBlocking<Unit> {
        val original = comment("1").copy(score = 7)
        val result = CompletableDeferred<NovelComment>()
        val source = FakeCommentSource(NovelCommentCapabilities(voting = true, scored = true)) {
            NovelCommentPage(listOf(original))
        }
        source.voteResponse = { _, _ -> result.await() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val comments = NovelComments(scope, preferences())
            comments.bind(source, Manga.create())
            comments.setChapter(chapter(1))
            waitFor("comments") { comments.state.value.loaded }
            comments.feedback(original).positiveVote shouldBe NovelCommentPositiveVote.LIKE
            comments.vote(original, NovelCommentVote.DOWN)
            source.votes.size shouldBe 0
            comments.vote(original, NovelCommentVote.UP)
            waitFor("vote") { source.votes.size == 1 }
            comments.state.value.roots.single().score shouldBe 8
            comments.vote(original, NovelCommentVote.UP)
            result.completeExceptionally(IllegalStateException("Sign in to vote"))
            waitFor("rollback") { comments.state.value.voting.isEmpty() }
            source.votes.size shouldBe 1
            comments.state.value.roots.single() shouldBe original
            comments.state.value.error shouldBe "Sign in to vote"
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a late vote only updates the chapter it belongs to`() = runBlocking<Unit> {
        val original = comment("same-id").copy(score = 2)
        val result = CompletableDeferred<NovelComment>()
        val source = FakeCommentSource(NovelCommentCapabilities(voting = true, downvotes = true)) {
            NovelCommentPage(listOf(original))
        }
        source.voteResponse = { _, _ -> result.await() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val comments = NovelComments(scope, preferences())
            comments.bind(source, Manga.create())
            comments.setChapter(chapter(1))
            waitFor("first chapter") { comments.state.value.loaded }
            comments.feedback(original).positiveVote shouldBe NovelCommentPositiveVote.UPVOTE
            comments.vote(original, NovelCommentVote.UP)
            waitFor("vote") { source.votes.size == 1 }
            comments.setChapter(chapter(2))
            waitFor("second chapter") { comments.state.value.loaded }
            comments.setChapter(chapter(1))
            waitFor("pending vote") { original.id in comments.state.value.voting }
            comments.vote(original, NovelCommentVote.UP)
            source.votes.size shouldBe 1
            comments.setChapter(chapter(2))
            waitFor("second chapter again") { comments.state.value.loaded }
            result.complete(original.copy(score = 3, vote = NovelCommentVote.UP))
            delay(SETTLE_MS)
            comments.state.value.roots.single() shouldBe original
            comments.setChapter(chapter(1))
            waitFor("cached vote") { comments.state.value.roots.firstOrNull()?.score == 3 }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `failed posts retain the draft and duplicate submissions are ignored`() = runBlocking<Unit> {
        val result = CompletableDeferred<NovelComment>()
        val source = FakeCommentSource(NovelCommentCapabilities(posting = true)) {
            NovelCommentPage(emptyList())
        }
        source.postResponse = { result.await() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val comments = NovelComments(scope, preferences())
            comments.bind(source, Manga.create())
            comments.setChapter(chapter(1))
            waitFor("comments") { comments.state.value.loaded }
            comments.replyTo(comment("flat"))
            comments.state.value.replyingTo shouldBe null
            comments.setDraft("My review")
            comments.post(comments.state.value.draft)
            waitFor("post") { source.posts.size == 1 }
            comments.post(comments.state.value.draft)
            result.completeExceptionally(IllegalStateException("Sign in to post"))
            waitFor("failure") { !comments.state.value.posting }
            source.posts.size shouldBe 1
            comments.state.value.draft shouldBe "My review"
            comments.state.value.error shouldBe "Sign in to post"
            source.postResponse = { comment("posted") }
            comments.post(comments.state.value.draft)
            waitFor("success") { comments.state.value.posted == 1 }
            comments.state.value.draft shouldBe ""
            comments.state.value.roots.single().id shouldBe "posted"
        } finally {
            scope.cancel()
        }
    }

    /** Two sites that both number their comments from one have two comments called "1", not one. */
    @Test
    fun `gathers the novel's comments from every source that has it`() = runBlocking<Unit> {
        val capabilities = NovelCommentCapabilities(
            scopes = setOf(NovelCommentScope.NOVEL),
            sorts = listOf(NovelCommentSort("newest", "Newest")),
            posting = true,
        )
        val own = FakeCommentSource(capabilities, id = 1L, name = "Own") { NovelCommentPage(listOf(comment("1"))) }
        val other = FakeCommentSource(capabilities, id = 2L, name = "Other") {
            NovelCommentPage(listOf(comment("1").copy(body = "theirs")))
        }
        other.search = { listOf(novel("Shadow Slave")) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val comments = shared(scope, own, other)
            comments.open()
            waitFor("both sources") { comments.state.value.roots.size == 2 }

            val state = comments.state.value
            state.roots.map { it.body } shouldBe listOf("1", "theirs")
            state.rows.map { it.key }.distinct().size shouldBe 2
            state.roots.map(comments::sourceName) shouldBe listOf("Own", "Other")
            state.origins.map { it.name to it.count } shouldBe listOf("Own" to 1, "Other" to 1)
            // No one site can take a post written into a thread gathered from several.
            state.capabilities?.posting shouldBe false

            comments.setOrigin(2L)
            waitFor("one source") { comments.state.value.roots.size == 1 }
            comments.state.value.roots.single().body shouldBe "theirs"
            comments.sourceName(comments.state.value.roots.single()) shouldBe null
            comments.state.value.capabilities?.posting shouldBe true

            comments.setOrigin(null)
            waitFor("both again") { comments.state.value.roots.size == 2 }
            // Filtering redraws what was fetched rather than fetching it again.
            own.requests.size shouldBe 1
            other.requests.size shouldBe 1
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `answers another source's comment through that source and under its own id`() = runBlocking<Unit> {
        val capabilities = NovelCommentCapabilities(
            scopes = setOf(NovelCommentScope.NOVEL),
            lazyReplies = true,
            voting = true,
        )
        val own = FakeCommentSource(capabilities, id = 1L, name = "Own") { NovelCommentPage(listOf(comment("1"))) }
        val other = FakeCommentSource(capabilities, id = 2L, name = "Other") { request ->
            if (request.parent == null) {
                NovelCommentPage(listOf(comment("1", replyCount = 1)))
            } else {
                NovelCommentPage(listOf(comment("1a")))
            }
        }
        other.search = { listOf(novel("Shadow Slave")) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val comments = shared(scope, own, other)
            comments.open()
            waitFor("both sources") { comments.state.value.roots.size == 2 }
            val theirs = comments.state.value.roots.single { comments.sourceName(it) == "Other" }

            comments.toggleReplies(theirs)
            waitFor("their reply") {
                NovelCommentTree.find(comments.state.value.roots, theirs.id)?.replies?.size == 1
            }
            other.requests.single { it.parent != null }.parent?.id shouldBe "1"
            own.replyRequests() shouldBe 0
            comments.state.value.rows.map { it.key }.distinct().size shouldBe 3

            comments.vote(theirs, NovelCommentVote.UP)
            waitFor("their vote") { other.votedOn.size == 1 && comments.state.value.voting.isEmpty() }
            other.votedOn.single().id shouldBe "1"
            own.votes shouldBe emptyList()
            comments.state.value.roots.single { it.id == theirs.id }.vote shouldBe NovelCommentVote.UP
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `filters every source to reviews or to comments, counting a source without feeds as comments`() =
        runBlocking<Unit> {
            val capabilities = NovelCommentCapabilities(scopes = setOf(NovelCommentScope.NOVEL))
            val own =
                FakeCommentSource(capabilities, id = 1L, name = "Own") { error("A declared feed must be supplied") }
            own.commentFeeds = listOf(
                NovelCommentFeed("comments", "Comments", capabilities),
                NovelCommentFeed("reviews", "Reviews", capabilities),
            )
            own.feedResponse = { _, feed -> NovelCommentPage(listOf(comment("own-${feed.key}"))) }
            val plain = FakeCommentSource(capabilities, id = 2L, name = "Plain") {
                NovelCommentPage(listOf(comment("plain")))
            }
            val reviewer = FakeCommentSource(capabilities, id = 3L, name = "Reviewer") {
                error("A declared feed must be supplied")
            }
            reviewer.commentFeeds = listOf(NovelCommentFeed("reviews", "Their reviews", capabilities))
            reviewer.feedResponse = { _, _ -> NovelCommentPage(listOf(comment("their-review"))) }
            listOf(plain, reviewer).forEach { it.search = { listOf(novel("Shadow Slave")) } }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val comments = shared(scope, own, plain, reviewer)
                comments.open()
                waitFor("everything") { comments.state.value.roots.size == 4 }
                comments.state.value.roots.map { it.body } shouldBe
                    listOf("own-comments", "own-reviews", "plain", "their-review")
                comments.state.value.kinds shouldBe setOf(NovelCommentKind.REVIEWS, NovelCommentKind.COMMENTS)

                comments.setKind(NovelCommentKind.REVIEWS)
                waitFor("the reviews") {
                    comments.state.value.roots.map { it.body } == listOf("own-reviews", "their-review")
                }
                comments.setKind(NovelCommentKind.COMMENTS)
                waitFor("the comments") {
                    comments.state.value.roots.map { it.body } == listOf("own-comments", "plain")
                }

                // A source with reviews alone has nothing for the comments filter, which gives way.
                comments.setOrigin(3L)
                waitFor("the reviewer") { comments.state.value.roots.map { it.body } == listOf("their-review") }
                comments.state.value.kind shouldBe NovelCommentKind.ALL
                comments.state.value.kinds shouldBe setOf(NovelCommentKind.REVIEWS)
                listOf(own, plain, reviewer).sumOf { it.requests.size + it.feedRequests.size } shouldBe 4
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `asks another source for its own chapter with the reader's number`() = runBlocking<Unit> {
        val own = FakeCommentSource(id = 1L, name = "Own") { NovelCommentPage(listOf(comment("own"))) }
        val other = FakeCommentSource(id = 2L, name = "Other") { request ->
            NovelCommentPage(listOf(comment("theirs-${request.target.chapter?.url}")))
        }
        other.search = { listOf(novel("Shadow Slave")) }
        other.chapters = { listOf(sourceChapter(12f, "/theirs/12"), sourceChapter(11f, "/theirs/11")) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val comments = shared(scope, own, other, commentScope = NovelCommentScope.CHAPTER)
            comments.setChapter(chapter(1).copy(chapterNumber = 11.0))
            waitFor("both sources") { comments.state.value.roots.size == 2 }
            comments.state.value.roots.map { it.body } shouldBe listOf("own", "theirs-/theirs/11")

            // A chapter the other site does not have is an empty thread there, not a request.
            comments.setChapter(chapter(2).copy(chapterNumber = 40.0))
            waitFor("the next chapter") {
                comments.state.value.origins.all { !it.loading } && comments.state.value.roots.isNotEmpty()
            }
            comments.state.value.roots.map { it.body } shouldBe listOf("own")
            // Unfiltered, every row names its source, whether or not the others have anything.
            comments.sourceName(comments.state.value.roots.single()) shouldBe "Own"
            other.requests.size shouldBe 1
            other.chapterFetches.get() shouldBe 1
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `names the source whose comments failed and keeps the ones that arrived`() = runBlocking<Unit> {
        val capabilities = NovelCommentCapabilities(scopes = setOf(NovelCommentScope.NOVEL))
        val own = FakeCommentSource(capabilities, id = 1L, name = "Own") { NovelCommentPage(listOf(comment("1"))) }
        val other = FakeCommentSource(capabilities, id = 2L, name = "Other") { error("offline") }
        other.search = { listOf(novel("Shadow Slave")) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val comments = shared(scope, own, other)
            comments.open()
            waitFor("the failure") { comments.state.value.error != null }
            comments.state.value.error shouldBe "Other: offline"
            comments.state.value.roots.map { it.id } shouldBe listOf("1")
        } finally {
            scope.cancel()
        }
    }

    /** Leaving the novel and coming back used to fetch every thread again. */
    @Test
    fun `a novel opened again soon after is not fetched again`() = runBlocking<Unit> {
        val source = FakeCommentSource(NovelCommentCapabilities(scopes = setOf(NovelCommentScope.NOVEL))) {
            NovelCommentPage(listOf(comment("1")))
        }
        val cache = NovelCommentCache()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val first = NovelComments(scope, preferences(), NovelCommentScope.NOVEL, cache = cache)
            first.bind(source, Manga.create())
            first.open()
            waitFor("the first screen") { first.state.value.loaded }

            val second = NovelComments(scope, preferences(), NovelCommentScope.NOVEL, cache = cache)
            second.bind(source, Manga.create())
            second.open()
            waitFor("the second screen") { second.state.value.loaded }
            second.state.value.roots.map { it.id } shouldBe listOf("1")
            source.requests.size shouldBe 1
            // The byline names the extension whenever the sheet is not filtered, even with only one.
            second.sourceName(second.state.value.roots.single()) shouldBe "fake"

            second.reload()
            waitFor("the refresh") { source.requests.size == 2 }
        } finally {
            scope.cancel()
        }
    }

    /** A controller that also looks for the novel on the [sources] after the first, which is its own. */
    private fun shared(
        scope: CoroutineScope,
        vararg sources: FakeCommentSource,
        commentScope: NovelCommentScope = NovelCommentScope.NOVEL,
    ) = NovelComments(
        scope = scope,
        preferences = preferences(),
        commentScope = commentScope,
        matcher = NovelCommentMatcher(NovelCommentCache()) { sources.toList() },
    ).apply { bind(sources.first(), Manga.create().copy(title = "Shadow Slave", url = "/shadow-slave")) }

    private fun preferences() = NovelReaderPreferences(InMemoryPreferenceStore())

    private fun comment(id: String, replies: List<NovelComment> = emptyList(), replyCount: Int = replies.size) =
        NovelComment(
            id = id,
            body = id,
            author = "author-$id",
            replies = replies,
            replyCount = replyCount,
        )

    private fun chapter(id: Long) = Chapter.create().copy(id = id, url = "/chapter/$id", name = "chapter $id")

    /** Polls rather than sleeps, so a slow machine waits and a wrong result still fails. */
    private suspend fun waitFor(what: String, condition: () -> Boolean) {
        val met = withTimeoutOrNull(WAIT_MS) {
            while (!condition()) delay(POLL_MS)
            true
        }
        if (met != true) throw AssertionError("Timed out waiting for $what")
    }

    /**
     * A fetch that is cancelled is this class doing as it was told, and `runCatching` used to turn it
     * into "StandaloneCoroutine was cancelled" in front of the reader.
     */
    @Test
    fun `says nothing went wrong when a fetch is cancelled out from under it`() = runBlocking {
        val source = FakeCommentSource { awaitCancellation() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val comments = NovelComments(scope, preferences())

        comments.bind(source, Manga.create())
        comments.setChapter(chapter(1))

        waitFor("the fetch to reach the source") { source.started }
        comments.reload()
        waitFor("the source call to be cancelled") { source.cancelled }
        // Long enough for a handler that should not run to have run.
        delay(SETTLE_MS)

        comments.state.value.error shouldBe null
        scope.cancel()
    }

    /**
     * A chapter turn used to throw away whatever was in flight. It no longer does: the request has
     * been made and the reader may well turn straight back, so the answer is kept and the next
     * arrival at that chapter costs nothing.
     */
    @Test
    fun `does not abandon a fetch the reader has already paid for`() = runBlocking {
        val source = FakeCommentSource { awaitCancellation() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val comments = NovelComments(scope, preferences())

        comments.bind(source, Manga.create())
        comments.setChapter(chapter(1))
        waitFor("the fetch to reach the source") { source.started }

        comments.setChapter(chapter(2))
        delay(SETTLE_MS)

        source.cancelled shouldBe false
        comments.state.value.error shouldBe null
        scope.cancel()
    }

    @Test
    fun `asks the site for the next page of replies rather than the first again`() = runBlocking {
        val parent = comment("1", replies = listOf(comment("1a")), replyCount = 9)
        val source = FakeCommentSource(NovelCommentCapabilities(lazyReplies = true)) { request ->
            when (request.parent) {
                null -> NovelCommentPage(listOf(parent))
                else -> NovelCommentPage(listOf(comment("reply-page-${request.page}")), hasNextPage = true)
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val comments = NovelComments(scope, preferences())

        comments.bind(source, Manga.create())
        comments.setChapter(Chapter.create().copy(id = 1L))
        comments.open()
        waitFor("the first page") { comments.state.value.loaded }

        comments.loadReplies(parent)
        waitFor("the first page of replies") { source.replyRequests() == 1 && source.idle(comments) }
        comments.loadReplies(NovelCommentTree.find(comments.state.value.roots, "1")!!)
        waitFor("the second page of replies") { source.replyRequests() == 2 && source.idle(comments) }

        source.requests.filter { it.parent != null }.map { it.page } shouldBe listOf(1, 2)
        scope.cancel()
    }

    /**
     * The comments are for the chapter being read, so they start when it does. Waiting for the
     * sheet meant every reader who opened it paid a round trip they could have paid while reading.
     */
    @Test
    fun `starts fetching as the chapter opens rather than waiting for the sheet`() = runBlocking {
        val source = FakeCommentSource { NovelCommentPage(listOf(comment("1"))) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val comments = NovelComments(scope, preferences())

        comments.bind(source, Manga.create())
        comments.setChapter(chapter(1))

        // Nothing has opened the sheet.
        waitFor("the thread without anything opening the sheet") { comments.state.value.loaded }
        comments.state.value.roots.map { it.id } shouldBe listOf("1")
        scope.cancel()
    }

    /** "Load more" was a button the reader had to find and press once per page. */
    @Test
    fun `keeps asking for pages until the site runs out`() = runBlocking {
        val source = FakeCommentSource { request ->
            NovelCommentPage(
                comments = listOf(comment("page-${request.page}")),
                hasNextPage = request.page < 3,
            )
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val comments = NovelComments(scope, preferences())

        comments.bind(source, Manga.create())
        comments.setChapter(chapter(1))
        waitFor("every page") { comments.state.value.roots.size == 3 }

        comments.state.value.roots.map { it.id } shouldBe listOf("page-1", "page-2", "page-3")
        source.requests.filter { it.parent == null }.map { it.page } shouldBe listOf(1, 2, 3)
        comments.state.value.hasMore shouldBe false
        comments.state.value.loadingMore shouldBe false
        scope.cancel()
    }

    /**
     * A site that answers every page with the same comments — because it ignores the page parameter,
     * which is exactly what the sites this was written against do — would otherwise be asked for
     * pages until the cap, once every pause, for ever.
     */
    @Test
    fun `stops asking when a page repeats what is already here`() = runBlocking {
        val source = FakeCommentSource { NovelCommentPage(listOf(comment("1")), hasNextPage = true) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val comments = NovelComments(scope, preferences())

        comments.bind(source, Manga.create())
        comments.setChapter(chapter(1))
        waitFor("the fetch to give up") { comments.state.value.loaded && !comments.state.value.loadingMore }

        source.requests.count { it.parent == null } shouldBe 2
        comments.state.value.hasMore shouldBe false
        scope.cancel()
    }

    /** Turning a page should not begin with a spinner over comments that could have been fetched. */
    @Test
    fun `fetches the next chapter ahead, so opening it shows what is already in hand`() = runBlocking {
        val source = FakeCommentSource { request ->
            NovelCommentPage(listOf(comment("for-${request.target.chapter?.url}")))
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val comments = NovelComments(scope, preferences())

        comments.bind(source, Manga.create())
        comments.setChapter(chapter(1))
        comments.prefetch(listOf(chapter(2)))
        waitFor("both chapters to have been asked for") { source.requests.size == 2 }

        comments.setChapter(chapter(2))
        waitFor("the second chapter's comments") { comments.state.value.loaded }

        comments.state.value.roots.map { it.id } shouldBe listOf("for-/chapter/2")
        // The point of the whole thing: arriving at the chapter cost no further request.
        source.requests.size shouldBe 2
        scope.cancel()
    }

    /**
     * A reply count the site cannot honour would otherwise leave a row offering replies that never
     * arrive, and every tap on it is another request for the same nothing.
     */
    @Test
    fun `stops offering replies once the site has none left to give`() = runBlocking {
        val parent = comment("1", replies = listOf(comment("1a")), replyCount = 9)
        val source = FakeCommentSource(NovelCommentCapabilities(lazyReplies = true)) { request ->
            if (request.parent == null) NovelCommentPage(listOf(parent)) else NovelCommentPage(emptyList())
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val comments = NovelComments(scope, preferences())

        comments.bind(source, Manga.create())
        comments.setChapter(Chapter.create().copy(id = 1L))
        comments.open()
        waitFor("the first page") { comments.state.value.loaded }
        comments.toggleReplies(parent)
        comments.state.value.rows.any { it is NovelCommentRow.MoreReplies } shouldBe true

        comments.loadReplies(parent)
        waitFor("the empty page of replies") { comments.state.value.loadingReplies.isEmpty() }

        comments.state.value.rows.any { it is NovelCommentRow.MoreReplies } shouldBe false
        scope.cancel()
    }
}

private const val WAIT_MS = 5_000L
private const val POLL_MS = 5L
private const val SETTLE_MS = 150L
