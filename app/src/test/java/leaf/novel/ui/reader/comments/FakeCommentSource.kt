package leaf.novel.ui.reader.comments

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import leaf.novel.api.NovelComment
import leaf.novel.api.NovelCommentCapabilities
import leaf.novel.api.NovelCommentDraft
import leaf.novel.api.NovelCommentFeed
import leaf.novel.api.NovelCommentFeedSource
import leaf.novel.api.NovelCommentPage
import leaf.novel.api.NovelCommentRequest
import leaf.novel.api.NovelCommentVote
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** A source that serves whatever the test hands it, and remembers what it was asked. */
internal open class FakeCommentSource(
    override val commentCapabilities: NovelCommentCapabilities = NovelCommentCapabilities(),
    override val id: Long = 1L,
    override val name: String = "fake",
    private val respond: suspend (NovelCommentRequest) -> NovelCommentPage,
) : NovelCommentFeedSource {

    override var commentFeeds: List<NovelCommentFeed> = emptyList()
    val feedRequests = CopyOnWriteArrayList<Pair<String, NovelCommentRequest>>()
    var feedResponse: suspend (NovelCommentRequest, NovelCommentFeed) -> NovelCommentPage = { request, _ ->
        respond(request)
    }

    override suspend fun getComments(request: NovelCommentRequest, feed: NovelCommentFeed): NovelCommentPage {
        feedRequests += feed.key to request
        return feedResponse(request, feed)
    }

    val votes = CopyOnWriteArrayList<NovelCommentVote>()
    val votedOn = CopyOnWriteArrayList<NovelComment>()
    val posts = CopyOnWriteArrayList<NovelCommentDraft>()
    var voteResponse: suspend (NovelComment, NovelCommentVote) -> NovelComment = { comment, vote ->
        comment.copy(vote = vote)
    }
    var postResponse: suspend (NovelCommentDraft) -> NovelComment = { throw UnsupportedOperationException() }

    override suspend fun voteComment(comment: NovelComment, vote: NovelCommentVote): NovelComment {
        votes += vote
        votedOn += comment
        return voteResponse(comment, vote)
    }

    override suspend fun postComment(draft: NovelCommentDraft): NovelComment {
        posts += draft
        return postResponse(draft)
    }

    /** Written from the source's own thread and read from the test's, so not an `ArrayList`. */
    val requests = CopyOnWriteArrayList<NovelCommentRequest>()

    @Volatile
    var started = false

    @Volatile
    var cancelled = false

    fun replyRequests() = requests.count { it.parent != null }

    fun idle(comments: NovelComments) = comments.state.value.loadingReplies.isEmpty()

    /** What a title search finds. Unset, a search is a test that went somewhere it should not. */
    var search: suspend (String) -> List<SManga> = { throw UnsupportedOperationException("unexpected search") }
    val searches = CopyOnWriteArrayList<String>()

    /** The novel's chapters, as a chapter list fetch returns them. */
    var chapters: suspend () -> List<SChapter> = { throw UnsupportedOperationException("unexpected chapters") }
    val chapterFetches = AtomicInteger()

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

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        searches += query
        return MangasPage(search(query), hasNextPage = false)
    }

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        chapterFetches.incrementAndGet()
        return SMangaUpdate(manga, chapters())
    }

    override suspend fun getPageList(chapter: SChapter) = throw UnsupportedOperationException()
}

internal fun novel(title: String, url: String = "/${title.lowercase().replace(' ', '-')}") =
    SManga.create().also {
        it.title = title
        it.url = url
    }

internal fun sourceChapter(number: Float, url: String = "/chapter/$number") = SChapter.create().also {
    it.url = url
    it.name = "Chapter $number"
    it.chapter_number = number
}
