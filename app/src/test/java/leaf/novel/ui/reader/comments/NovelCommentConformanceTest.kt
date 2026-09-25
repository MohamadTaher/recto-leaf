package leaf.novel.ui.reader.comments

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import leaf.novel.api.NovelComment
import leaf.novel.api.NovelCommentCapabilities
import leaf.novel.api.NovelCommentConformance
import leaf.novel.api.NovelCommentFeed
import leaf.novel.api.NovelCommentFeedback
import leaf.novel.api.NovelCommentFeedbackSource
import leaf.novel.api.NovelCommentPage
import leaf.novel.api.NovelCommentRating
import leaf.novel.api.NovelCommentScope
import leaf.novel.api.NovelCommentSort
import leaf.novel.api.NovelCommentVote
import org.junit.jupiter.api.Test

/**
 * The checks an extension runs against itself, checked themselves.
 *
 * Each of these is a mistake that costs a real extension a feature and says nothing while it does —
 * a score the sheet never draws, a review feed filtered as comments, a reply past the depth its own
 * source declared. The point of the checker is that they stop being silent, so what is worth
 * holding is that it finds each one, and that a source doing nothing wrong hears nothing.
 */
class NovelCommentConformanceTest {

    private val flat = NovelCommentCapabilities()

    @Test
    fun `a minimal source doing nothing wrong has nothing said about it`() {
        val page = NovelCommentPage(listOf(comment("1"), comment("2")))

        NovelCommentConformance.check(page, flat).shouldBeEmpty()
        NovelCommentConformance.check(flat).shouldBeEmpty()
    }

    @Test
    fun `catches a page that contradicts the feed that served it`() {
        val capabilities = NovelCommentCapabilities(maxDepth = 1)
        val page = NovelCommentPage(
            comments = listOf(
                comment("1").copy(score = 12),
                comment("2").copy(avatarUrl = "https://example.invalid/a.png"),
                comment("3").copy(vote = NovelCommentVote.UP),
                comment("4", replies = listOf(comment("5"))),
                comment("1"),
            ),
            hasNextPage = false,
            nextCursor = "more",
        )

        val found = NovelCommentConformance.check(page, capabilities)

        found.single { it.contains("\"1\" has a score") } shouldContain "not scored"
        found.single { it.contains("\"2\"") } shouldContain "does not claim avatars"
        found.single { it.contains("\"3\"") } shouldContain "cannot vote"
        found.single { it.contains("\"5\"") } shouldContain "past the maxDepth of 1"
        found.single { it.contains("appears twice") } shouldContain "\"1\""
        found.single { it.contains("cursor") } shouldContain "last"
    }

    /** The id is what the app tags to keep one source's thread apart from another's. */
    @Test
    fun `catches an id that cannot survive being merged with another source's`() {
        val page = NovelCommentPage(listOf(comment("7\u001f7"), comment("")))

        val found = NovelCommentConformance.check(page, flat)

        found.any { it.contains("control character") } shouldBe true
        found.any { it.contains("blank id") } shouldBe true
    }

    /** A page that repeats an earlier one ends the thread, so the repeat is worth hearing about. */
    @Test
    fun `catches a second page that resends the first`() {
        val page = NovelCommentPage(listOf(comment("1")), hasNextPage = true)

        NovelCommentConformance.check(page, flat, known = setOf("1"))
            .single() shouldContain "appears twice"
    }

    @Test
    fun `catches capabilities that contradict themselves`() {
        val found = NovelCommentConformance.check(
            NovelCommentCapabilities(
                scopes = emptySet(),
                maxDepth = 1,
                lazyReplies = true,
                downvotes = true,
                sorts = listOf(NovelCommentSort("top", "Top"), NovelCommentSort("top", "Best")),
            ),
        )

        found.any { it.contains("no scope") } shouldBe true
        found.any { it.contains("lazyReplies") } shouldBe true
        found.any { it.contains("downvotes without voting") } shouldBe true
        found.any { it.contains("\"top\" twice") } shouldBe true
    }

    /** The reviews filter reads the key and only the key; see `docs/leaf/comments/PROVIDERS.md`. */
    @Test
    fun `catches a review feed that is not keyed as one`() {
        val source = FakeCommentSource(flat) { NovelCommentPage(emptyList()) }
        source.commentFeeds = listOf(
            NovelCommentFeed("comments", "Comments", flat),
            NovelCommentFeed("user-reviews", "Reviews", flat),
        )

        NovelCommentConformance.check(source)
            .single() shouldContain "NovelCommentFeed.REVIEWS"
    }

    @Test
    fun `says nothing about a source that keys its reviews properly`() {
        val source = FakeCommentSource(NovelCommentCapabilities(scopes = setOf(NovelCommentScope.NOVEL))) {
            NovelCommentPage(emptyList())
        }
        source.commentFeeds = listOf(
            NovelCommentFeed(NovelCommentFeed.COMMENTS, "Comments", flat),
            NovelCommentFeed(NovelCommentFeed.REVIEWS, "Reviews", flat),
        )

        NovelCommentConformance.check(source).shouldBeEmpty()
    }

    @Test
    fun `catches two feeds sharing a key`() {
        val source = FakeCommentSource(flat) { NovelCommentPage(emptyList()) }
        source.commentFeeds = listOf(
            NovelCommentFeed("comments", "Comments", flat),
            NovelCommentFeed("comments", "More", flat),
        )

        NovelCommentConformance.check(source)
            .single() shouldContain "more than one feed keyed \"comments\""
    }

    /**
     * The app asks for a comment's feedback exactly once, as its page lands. An implementation that
     * answers differently the second time is one that was parsing rather than looking up, and it
     * would have lost whichever call it did not win.
     */
    @Test
    fun `catches feedback that is not a lookup`() {
        var asked = 0
        val consuming = object : NovelCommentFeedbackSource {
            override fun getCommentFeedback(comment: NovelComment) = NovelCommentFeedback(likes = asked++)
        }

        NovelCommentConformance.check(consuming, listOf(comment("1")))
            .single() shouldContain "not a lookup"
    }

    @Test
    fun `catches a rating outside its own scale and a count below zero`() {
        val wrong = object : NovelCommentFeedbackSource {
            override fun getCommentFeedback(comment: NovelComment) = when (comment.id) {
                "1" -> NovelCommentFeedback(rating = NovelCommentRating(7.0, 5.0))
                else -> NovelCommentFeedback(likes = -3)
            }
        }

        val found = NovelCommentConformance.check(wrong, listOf(comment("1"), comment("2")))

        found.any { it.contains("rated 7.0 out of 5.0") } shouldBe true
        found.any { it.contains("negative like count") } shouldBe true
    }

    @Test
    fun `says nothing about feedback that is a plain lookup`() {
        val byId = mapOf("1" to NovelCommentFeedback(likes = 4, dislikes = 0))
        val source = object : NovelCommentFeedbackSource {
            override fun getCommentFeedback(comment: NovelComment) = byId[comment.id] ?: NovelCommentFeedback()
        }

        NovelCommentConformance.check(source, listOf(comment("1"), comment("2"))).shouldBeEmpty()
    }

    private fun comment(id: String, replies: List<NovelComment> = emptyList()) = NovelComment(
        id = id,
        body = "body-$id",
        author = "author-$id",
        replies = replies,
        replyCount = replies.size,
    )
}
