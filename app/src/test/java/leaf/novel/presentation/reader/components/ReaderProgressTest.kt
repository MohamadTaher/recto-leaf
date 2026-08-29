package leaf.novel.presentation.reader.components

import io.kotest.matchers.shouldBe
import leaf.novel.ui.reader.NovelReaderViewModel
import org.junit.jupiter.api.Test

/**
 * This is the arithmetic that writes to `chapters.last_page_read`, and the 95% threshold that marks
 * a chapter read is applied to its output.
 */
class ReaderProgressTest {

    private val viewport = 1000

    @Test
    fun `reports zero at the top of a scrollable chapter`() {
        percentOf(scrollY = 0, range = 3000, viewportHeight = viewport) shouldBe 0
    }

    @Test
    fun `reports the midpoint`() {
        percentOf(scrollY = 1000, range = 3000, viewportHeight = viewport) shouldBe 50
    }

    @Test
    fun `reports one hundred at the bottom`() {
        percentOf(scrollY = 2000, range = 3000, viewportHeight = viewport) shouldBe 100
    }

    @Test
    fun `treats content shorter than the viewport as complete`() {
        // Nothing can be scrolled, so the reader has seen all of it.
        percentOf(scrollY = 0, range = 400, viewportHeight = viewport) shouldBe 100
    }

    @Test
    fun `treats content exactly the viewport height as complete`() {
        percentOf(scrollY = 0, range = viewport, viewportHeight = viewport) shouldBe 100
    }

    @Test
    fun `guards against a zero viewport before layout`() {
        percentOf(scrollY = 0, range = 0, viewportHeight = 0) shouldBe 100
    }

    @Test
    fun `clamps overscroll rather than reporting past one hundred`() {
        percentOf(scrollY = 5000, range = 3000, viewportHeight = viewport) shouldBe 100
    }

    @Test
    fun `clamps a negative scroll position`() {
        percentOf(scrollY = -50, range = 3000, viewportHeight = viewport) shouldBe 0
    }

    @Test
    fun `crosses the completion threshold only near the end`() {
        val threshold = NovelReaderViewModel.COMPLETION_THRESHOLD
        (percentOf(scrollY = 1880, range = 3000, viewportHeight = viewport) >= threshold) shouldBe false
        (percentOf(scrollY = 1900, range = 3000, viewportHeight = viewport) >= threshold) shouldBe true
    }
}
