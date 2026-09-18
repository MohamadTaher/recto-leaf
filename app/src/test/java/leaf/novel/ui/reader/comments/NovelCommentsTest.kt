package leaf.novel.ui.reader.comments

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import io.kotest.matchers.shouldBe
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
import leaf.novel.api.NovelCommentPage
import leaf.novel.api.NovelCommentRequest
import leaf.novel.api.NovelCommentSource
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
        comments.state.value.rows.any { it is NovelCommentRow.MoreReplies } shouldBe true

        comments.loadReplies(parent)
        waitFor("the empty page of replies") { comments.state.value.loadingReplies.isEmpty() }

        comments.state.value.rows.any { it is NovelCommentRow.MoreReplies } shouldBe false
        scope.cancel()
    }
}

/** A source that serves whatever the test hands it, and remembers what it was asked. */
private class FakeCommentSource(
    override val commentCapabilities: NovelCommentCapabilities = NovelCommentCapabilities(),
    private val respond: suspend (NovelCommentRequest) -> NovelCommentPage,
) : NovelCommentSource {

    /** Written from the source's own thread and read from the test's, so not an `ArrayList`. */
    val requests = CopyOnWriteArrayList<NovelCommentRequest>()

    @Volatile
    var started = false

    @Volatile
    var cancelled = false

    fun replyRequests() = requests.count { it.parent != null }

    fun idle(comments: NovelComments) = comments.state.value.loadingReplies.isEmpty()

    override val id = 1L
    override val name = "fake"
    override val supportsLatest = false

    override suspend fun getComments(request: NovelCommentRequest): NovelCommentPage {
        requests += request
        started = true
        try {
            return respond(request)
        } catch (e: Throwable) {
            cancelled = true
            throw e
        }
    }

    override suspend fun getPopularManga(page: Int): MangasPage = throw UnsupportedOperationException()

    override suspend fun getLatestUpdates(page: Int): MangasPage = throw UnsupportedOperationException()

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
        throw UnsupportedOperationException()

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = throw UnsupportedOperationException()

    override suspend fun getPageList(chapter: SChapter) = throw UnsupportedOperationException()
}

private const val WAIT_MS = 5_000L
private const val POLL_MS = 5L
private const val SETTLE_MS = 150L
