package leaf.novel.ui.reader.comments

import io.kotest.matchers.shouldBe
import leaf.novel.api.NovelComment
import leaf.novel.api.NovelCommentCapabilities
import leaf.novel.api.NovelCommentFeed
import org.junit.jupiter.api.Test

/**
 * The state is where a fetched thread becomes the rows the sheet draws, so the cases worth holding
 * are the ones a thread that arrives in pieces causes: a page that overlaps the one before it, a
 * fold set that has to survive the next page, and an order the site was never asked for.
 */
class NovelCommentsStateTest {

    private fun comment(
        id: String,
        parentId: String? = null,
        score: Int? = null,
        replies: List<NovelComment> = emptyList(),
    ) = NovelComment(
        id = id,
        body = id,
        author = "author-$id",
        parentId = parentId,
        score = score,
        replies = replies,
        replyCount = replies.size,
    )

    /**
     * A feed's key settles what it holds, except where it does not: a site whose reviews come back
     * through the ordinary listing marks them by writing a rating into the comment, and nothing
     * about the feed says so.
     */
    @Test
    fun `a comment carrying stars is a review wherever it arrived`() {
        val discussion = NovelCommentFeed("comments", "Comments", NovelCommentCapabilities())
        val reviews = NovelCommentFeed(NovelCommentFeed.REVIEWS, "Reviews", NovelCommentCapabilities())
        val plain = comment("plain")
        val starred = comment("starred").copy(body = "<b>★ 4.5 / 5.0</b><br>Worth every chapter")

        // A review feed holds reviews whatever its comments happen to look like.
        NovelCommentKind.REVIEWS.admits(reviews, plain) shouldBe true
        NovelCommentKind.COMMENTS.admits(reviews, plain) shouldBe false

        // A comments feed holds comments, until one of them turns out to carry a rating.
        NovelCommentKind.COMMENTS.admits(discussion, plain) shouldBe true
        NovelCommentKind.REVIEWS.admits(discussion, plain) shouldBe false
        NovelCommentKind.REVIEWS.admits(discussion, starred) shouldBe true
        NovelCommentKind.COMMENTS.admits(discussion, starred) shouldBe false

        // And neither filter is the unfiltered sheet, which shows both.
        NovelCommentKind.ALL.admits(discussion, starred) shouldBe true
        NovelCommentKind.ALL.admits(discussion, plain) shouldBe true
    }

    private fun state() = NovelCommentsState(capabilities = NovelCommentCapabilities())

    private fun thread(vararg comments: NovelComment, done: Boolean = false) =
        NovelCommentThread(comments = comments.toList(), loaded = true, done = done)

    /** Two rows under one key is one comment drawn twice and one row's state shared with it. */
    @Test
    fun `never draws two rows with the same key when the pages overlap`() {
        // A comment the site sent nested on one page and flat on the next, which is what a thread
        // that grew between two requests looks like by the time it gets here.
        val state = state().copy(expandedReplies = setOf("1")).withThread(
            thread(
                comment("1", replies = listOf(comment("1a", parentId = "1"))),
                comment("1a", parentId = "1"),
                comment("2"),
            ),
        )

        state.rows.map { it.key } shouldBe listOf("1", "1a", "hide:1", "2")
        state.count shouldBe 3
    }

    @Test
    fun `keeps what the reader has opened when a later page arrives collapsed`() {
        val first = state().withThread(
            thread(comment("1", replies = listOf(comment("1a")))),
            collapseNew = true,
        )
        first.collapsed shouldBe setOf("1")

        // The reader opens the first page's thread, then the next page lands.
        val opened = first.copy(collapsed = emptySet(), expandedReplies = setOf("1")).withRoots(first.roots)
        val second = opened.withThread(
            thread(
                comment("1", replies = listOf(comment("1a"))),
                comment("2", replies = listOf(comment("2a"))),
            ),
            collapseNew = true,
        )

        second.collapsed shouldBe setOf("2")
        second.rows.map { it.key } shouldBe listOf("1", "1a", "hide:1", "2")
    }

    @Test
    fun `shows parent bodies while keeping replies closed by default`() {
        val state = state().withThread(
            thread(comment("1"), comment("2", replies = listOf(comment("2a")))),
        )

        state.collapsed shouldBe emptySet()
        state.rows.map { it.key } shouldBe listOf("1", "2")
        state.expandedReplies shouldBe emptySet()
    }

    /**
     * A comment arriving once the fetch is over is the reader's own, and folding what someone just
     * posted is the one case where "start threads collapsed" is plainly the wrong answer.
     */
    @Test
    fun `does not fold a comment that arrives after the fetch has finished`() {
        val state = state().withThread(
            thread(comment("1"), comment("posted", replies = listOf(comment("reply"))), done = true),
            collapseNew = true,
        )

        state.collapsed shouldBe emptySet()
    }
}
