package leaf.novel.ui.reader.setting

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

private const val WIDTH = 300
private const val HEIGHT = 600

/**
 * The only arithmetic in the tap handling, and the only part of it that can be wrong quietly: a
 * mistake here binds the wrong corner rather than failing outright.
 */
class NovelTapGridTest {

    private fun cell(x: Float, y: Float) = NovelTapGrid.cellOf(x, y, WIDTH, HEIGHT)

    @Test
    fun `resolves each corner and the centre`() {
        cell(10f, 10f) shouldBe 0
        cell(290f, 10f) shouldBe 2
        cell(150f, 300f) shouldBe NovelTapGrid.CENTRE
        cell(10f, 590f) shouldBe NovelTapGrid.BOTTOM_LEFT
        cell(290f, 590f) shouldBe 8
    }

    @Test
    fun `numbers cells left to right then top to bottom`() {
        cell(150f, 10f) shouldBe 1
        cell(10f, 300f) shouldBe 3
        cell(290f, 300f) shouldBe 5
        cell(150f, 590f) shouldBe 7
    }

    @Test
    fun `puts a tap on a boundary in the cell below and to the right`() {
        cell(100f, 200f) shouldBe NovelTapGrid.CENTRE
        cell(99f, 199f) shouldBe 0
    }

    @Test
    fun `pulls a tap outside the view back to the nearest cell`() {
        cell(-50f, -50f) shouldBe 0
        cell(1000f, 1000f) shouldBe 8
    }

    @Test
    fun `falls back to the centre before the view has been measured`() {
        NovelTapGrid.cellOf(10f, 10f, width = 0, height = 0) shouldBe NovelTapGrid.CENTRE
    }
}
