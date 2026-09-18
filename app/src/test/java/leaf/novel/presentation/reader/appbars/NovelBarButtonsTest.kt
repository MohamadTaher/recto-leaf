package leaf.novel.presentation.reader.appbars

import io.kotest.matchers.shouldBe
import leaf.novel.ui.reader.setting.NovelReaderAction
import org.junit.jupiter.api.Test

/**
 * The bar is built from six independent slots, so the states worth checking are the ones a reader
 * can reach by editing them one at a time: gaps, repeats, and clearing the lot.
 */
class NovelBarButtonsTest {

    @Test
    fun `keeps the chosen buttons in the order they were chosen`() {
        val chosen = listOf(
            NovelReaderAction.SEARCH,
            NovelReaderAction.VISUAL_OPTIONS,
            NovelReaderAction.SHOW_CHAPTERS,
            NovelReaderAction.NONE,
            NovelReaderAction.NONE,
            NovelReaderAction.NONE,
        )

        NovelBarButtons.resolve(chosen) shouldBe listOf(
            NovelReaderAction.SEARCH,
            NovelReaderAction.VISUAL_OPTIONS,
            NovelReaderAction.SHOW_CHAPTERS,
        )
    }

    /** A gap in the middle is what a reader leaves behind while rearranging the slots. */
    @Test
    fun `closes a gap rather than leaving a hole in the bar`() {
        val chosen = listOf(
            NovelReaderAction.SEARCH,
            NovelReaderAction.NONE,
            NovelReaderAction.MISCELLANEOUS,
            NovelReaderAction.NONE,
            NovelReaderAction.NONE,
            NovelReaderAction.NONE,
        )

        NovelBarButtons.resolve(chosen) shouldBe
            listOf(NovelReaderAction.SEARCH, NovelReaderAction.MISCELLANEOUS)
    }

    @Test
    fun `shows a repeated button once`() {
        val chosen = List(NovelBarButtons.SLOTS) { NovelReaderAction.SEARCH }

        NovelBarButtons.resolve(chosen) shouldBe listOf(NovelReaderAction.SEARCH)
    }

    /**
     * Clearing every slot is not a request for the chrome to vanish, and if it were honoured there
     * would be no button left to open the settings and undo it.
     */
    @Test
    fun `falls back to the default bar when every slot is empty`() {
        val chosen = List(NovelBarButtons.SLOTS) { NovelReaderAction.NONE }

        NovelBarButtons.resolve(chosen) shouldBe NovelBarButtons.DEFAULT
        NovelBarButtons.resolve(emptyList()) shouldBe NovelBarButtons.DEFAULT
    }

    /**
     * A button this novel cannot carry out is not a choice the reader made, so it must not be the
     * thing that empties their bar and takes the settings away with it.
     */
    @Test
    fun `falls back to the default bar rather than leaving only an unavailable button`() {
        val chosen = listOf(NovelReaderAction.COMMENTS) +
            List(NovelBarButtons.SLOTS - 1) { NovelReaderAction.NONE }

        val bar = NovelBarButtons.resolve(chosen, unavailable = setOf(NovelReaderAction.COMMENTS))

        bar shouldBe NovelBarButtons.DEFAULT - NovelReaderAction.COMMENTS
        bar.isEmpty() shouldBe false
    }

    @Test
    fun `leaves out an unavailable button the reader chose alongside others`() {
        val chosen = listOf(
            NovelReaderAction.SEARCH,
            NovelReaderAction.COMMENTS,
            NovelReaderAction.ADDITIONAL_OPTIONS,
        )

        NovelBarButtons.resolve(chosen, unavailable = setOf(NovelReaderAction.COMMENTS)) shouldBe
            listOf(NovelReaderAction.SEARCH, NovelReaderAction.ADDITIONAL_OPTIONS)
    }

    @Test
    fun `never puts more on the bar than it has room for`() {
        val chosen = NovelBarButtons.CANDIDATES - NovelReaderAction.NONE

        NovelBarButtons.resolve(chosen).size shouldBe NovelBarButtons.SLOTS
    }

    /** The picker must never offer a button the bar cannot draw. */
    @Test
    fun `every candidate but None has a glyph`() {
        val undrawable = NovelBarButtons.CANDIDATES
            .filter { it != NovelReaderAction.NONE }
            .filter { NovelBarButtons.iconFor(it) == null }

        undrawable shouldBe emptyList()
    }

    @Test
    fun `the default bar is made of candidates`() {
        NovelBarButtons.DEFAULT.all { it in NovelBarButtons.CANDIDATES } shouldBe true
    }
}
