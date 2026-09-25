package leaf.novel.ui.reader.comments

import io.kotest.matchers.shouldBe
import leaf.novel.api.NovelCommentRating
import org.junit.jupiter.api.Test

class NovelCommentReviewTest {
    @Test
    fun `separates existing extension ratings without losing review markup`() {
        val review = NovelCommentReview.parse("<b>★ 4.5 / 5.0</b><br><p>A <em>good</em> book</p>")
        review.rating shouldBe NovelCommentRating(4.5, 5.0)
        review.body shouldBe "<p>A <em>good</em> book</p>"
    }

    @Test
    fun `does not reinterpret ordinary prose or invalid ratings`() {
        listOf(
            "I give this ★ 5 / 5",
            "<p>My review</p><b>★ 5 / 5</b><br>",
            "<b>★ 6 / 5</b><br>Review",
            "<b>★ 0 / 0</b><br>Review",
            "<b>★ 5 / 5</b> without the extension separator",
        ).forEach { body ->
            NovelCommentReview.parse(body) shouldBe NovelCommentReview(body, null)
        }
    }

    @Test
    fun `retains zero and non five point scales`() {
        NovelCommentReview.parse("<b>★ 0 / 10</b><br/>Review").rating shouldBe NovelCommentRating(0.0, 10.0)
    }

    @Test
    fun `puts every scale on five stars`() {
        NovelCommentRating(7.0, 10.0).outOfFive() shouldBe NovelCommentRating(3.5, 5.0)
        NovelCommentRating(73.0, 100.0).outOfFive() shouldBe NovelCommentRating(3.7, 5.0)
        NovelCommentRating(4.5, 5.0).outOfFive() shouldBe NovelCommentRating(4.5, 5.0)
    }
}
