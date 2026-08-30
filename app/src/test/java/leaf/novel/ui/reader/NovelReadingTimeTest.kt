package leaf.novel.ui.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * An estimate is still arithmetic, and this one is read off the screen while someone is reading, so
 * it has to fall as they scroll and reach zero only at the end.
 */
class NovelReadingTimeTest {

    @Test
    fun `counts words and not markup`() {
        NovelReadingTime.wordsIn("<p>one two three</p>") shouldBe 3
    }

    @Test
    fun `counts a word once across nested elements`() {
        NovelReadingTime.wordsIn("<p>one <em>two</em> three</p>") shouldBe 3
    }

    @Test
    fun `counts nothing in an empty chapter`() {
        NovelReadingTime.wordsIn("<p></p>") shouldBe 0
        NovelReadingTime.wordsIn("") shouldBe 0
    }

    @Test
    fun `gives the whole chapter at the start`() {
        NovelReadingTime.minutesRemaining(words = 1000, percentRead = 0) shouldBe 5
    }

    @Test
    fun `halves as the reader passes the middle`() {
        NovelReadingTime.minutesRemaining(words = 2000, percentRead = 50) shouldBe 5
    }

    @Test
    fun `reaches zero only at the end`() {
        NovelReadingTime.minutesRemaining(words = 1000, percentRead = 99) shouldBe 1
        NovelReadingTime.minutesRemaining(words = 1000, percentRead = 100) shouldBe 0
    }

    /** Rounded up, so a part minute reads as a minute rather than as none. */
    @Test
    fun `rounds a part minute up`() {
        NovelReadingTime.minutesRemaining(words = 250, percentRead = 0) shouldBe 2
    }

    @Test
    fun `survives a percent outside its range`() {
        NovelReadingTime.minutesRemaining(words = 1000, percentRead = -20) shouldBe 5
        NovelReadingTime.minutesRemaining(words = 1000, percentRead = 500) shouldBe 0
    }
}
