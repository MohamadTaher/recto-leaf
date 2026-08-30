package leaf.novel.ui.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The mini status bar's two sums.
 *
 * Both divide by something a chapter is allowed to report as zero — a viewport that has not been
 * measured, a book whose chapter list has not loaded — so the guards are most of what is checked
 * here. The rest is rounding, which is where a bar that reads "100.0%" halfway through a book
 * would come from.
 */
class NovelStatusLineTest {

    @Test
    fun `counts a chapter shorter than the viewport as one screen`() {
        NovelStatusLine.screens(scrollY = 0, range = 400, viewportHeight = 1000) shouldBe
            NovelStatusLine.Screens(current = 1, total = 1)
    }

    @Test
    fun `rounds a part-screen up, because it is still a screen to read`() {
        NovelStatusLine.screens(scrollY = 0, range = 2500, viewportHeight = 1000) shouldBe
            NovelStatusLine.Screens(current = 1, total = 3)
    }

    @Test
    fun `counts screens from one as a reader does`() {
        NovelStatusLine.screens(scrollY = 1000, range = 3000, viewportHeight = 1000) shouldBe
            NovelStatusLine.Screens(current = 2, total = 3)
    }

    /** Over-scroll reports a position past the end, which must not read as a fourth of three. */
    @Test
    fun `clamps a scroll past the end to the last screen`() {
        NovelStatusLine.screens(scrollY = 9999, range = 3000, viewportHeight = 1000) shouldBe
            NovelStatusLine.Screens(current = 3, total = 3)
    }

    /** The common case: the last screenful is a partial one, and it still has to be reachable. */
    @Test
    fun `reaches the last screen of a chapter that does not divide evenly`() {
        // Two and a half screens: the furthest it can scroll is 1500, which is the third screen.
        NovelStatusLine.screens(scrollY = 1500, range = 2500, viewportHeight = 1000) shouldBe
            NovelStatusLine.Screens(current = 3, total = 3)
    }

    @Test
    fun `reads as one of one before the view has been measured`() {
        NovelStatusLine.screens(scrollY = 0, range = 3000, viewportHeight = 0) shouldBe
            NovelStatusLine.Screens.NONE
    }

    @Test
    fun `is at zero at the start of the first chapter`() {
        NovelStatusLine.bookPercent(chapterIndex = 0, chapterCount = 4, percentRead = 0) shouldBe "0.0"
    }

    @Test
    fun `is at a hundred at the end of the last chapter`() {
        NovelStatusLine.bookPercent(chapterIndex = 3, chapterCount = 4, percentRead = 100) shouldBe "100.0"
    }

    @Test
    fun `weights every chapter equally`() {
        NovelStatusLine.bookPercent(chapterIndex = 1, chapterCount = 4, percentRead = 0) shouldBe "25.0"
        NovelStatusLine.bookPercent(chapterIndex = 1, chapterCount = 4, percentRead = 50) shouldBe "37.5"
    }

    @Test
    fun `keeps the tenth a long book resolves to`() {
        NovelStatusLine.bookPercent(chapterIndex = 432, chapterCount = 650, percentRead = 60) shouldBe "66.5"
    }

    @Test
    fun `reads as zero before the chapter list has loaded`() {
        NovelStatusLine.bookPercent(chapterIndex = 0, chapterCount = 0, percentRead = 50) shouldBe "0.0"
    }

    /** A percent restored from an older range, or an index from a list that has since shrunk. */
    @Test
    fun `clamps values from outside their range rather than formatting a negative`() {
        NovelStatusLine.bookPercent(chapterIndex = -1, chapterCount = 4, percentRead = -20) shouldBe "0.0"
        NovelStatusLine.bookPercent(chapterIndex = 9, chapterCount = 4, percentRead = 100) shouldBe "100.0"
    }
}
