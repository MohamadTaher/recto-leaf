package leaf.novel.ui.reader.setting

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

private const val FAR = 200f
private const val NEAR = 10f

/**
 * The only arithmetic in the swipe handling. A sign the wrong way round binds the opposite
 * direction, which is the kind of thing that reads as correct until someone actually swipes.
 */
class NovelReaderSwipeTest {

    @Test
    fun `reads the horizontal directions`() {
        NovelReaderSwipe.of(dx = -FAR, dy = 0f) shouldBe NovelReaderSwipe.RIGHT_TO_LEFT
        NovelReaderSwipe.of(dx = FAR, dy = 0f) shouldBe NovelReaderSwipe.LEFT_TO_RIGHT
    }

    @Test
    fun `reads the vertical directions`() {
        NovelReaderSwipe.of(dx = 0f, dy = -FAR) shouldBe NovelReaderSwipe.BOTTOM_TO_TOP
        NovelReaderSwipe.of(dx = 0f, dy = FAR) shouldBe NovelReaderSwipe.TOP_TO_BOTTOM
    }

    @Test
    fun `gives a diagonal to whichever axis it is more of`() {
        NovelReaderSwipe.of(dx = FAR, dy = NEAR) shouldBe NovelReaderSwipe.LEFT_TO_RIGHT
        NovelReaderSwipe.of(dx = NEAR, dy = FAR) shouldBe NovelReaderSwipe.TOP_TO_BOTTOM
    }

    @Test
    fun `ignores a movement too short to be a swipe`() {
        NovelReaderSwipe.of(dx = NEAR, dy = NEAR) shouldBe null
        NovelReaderSwipe.of(dx = 0f, dy = 0f) shouldBe null
    }

    /** The threshold is inclusive, so a swipe of exactly the minimum counts. */
    @Test
    fun `accepts a swipe of exactly the minimum distance`() {
        NovelReaderSwipe.of(dx = NovelReaderSwipe.MIN_DISTANCE_PX, dy = 0f) shouldBe
            NovelReaderSwipe.LEFT_TO_RIGHT
    }
}
