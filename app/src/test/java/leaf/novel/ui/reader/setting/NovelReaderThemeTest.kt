package leaf.novel.ui.reader.setting

import io.kotest.matchers.shouldBe
import leaf.novel.ui.reader.NovelReaderCss
import org.junit.jupiter.api.Test

/**
 * A theme resolves to the pair of colours the whole reader draws with, so getting this wrong is a
 * page whose text and background disagree.
 */
class NovelReaderThemeTest {

    @Test
    fun `a preset draws in its own colours`() {
        NovelReaderTheme.SEPIA.colors() shouldBe NovelReaderColors(SEPIA_PAPER, SEPIA_INK)
    }

    @Test
    fun `every preset carries both colours`() {
        NovelReaderTheme.entries.filter { it.slot == null }.forEach { theme ->
            val colors = theme.colors()
            colors.background shouldBe theme.background
            colors.foreground shouldBe theme.foreground
        }
    }

    /** The settings lay the presets out as a light row and a dark row of three. */
    @Test
    fun `the presets are three light themes and three dark ones`() {
        val presets = NovelReaderTheme.entries.filter { it.slot == null }

        presets.count { !NovelReaderCss.isDark(it.background!!) } shouldBe 3
        presets.count { NovelReaderCss.isDark(it.background!!) } shouldBe 3
    }

    @Test
    fun `a custom slot draws in whatever that slot holds`() {
        val slots = listOf(null, NovelReaderColors(SEPIA_PAPER, SEPIA_INK), null)

        NovelReaderTheme.CUSTOM_2.colors(slots) shouldBe NovelReaderColors(SEPIA_PAPER, SEPIA_INK)
    }

    /**
     * Selecting a slot that has never been filled must not blank the page, and a slot list that has
     * not loaded yet must not either — both fall back to the default theme.
     */
    @Test
    fun `an empty custom slot falls back to the default theme`() {
        val empty = listOf(null, null, null)

        NovelReaderTheme.CUSTOM_1.colors(empty) shouldBe NovelReaderTheme.DEFAULT.colors()
        NovelReaderTheme.CUSTOM_3.colors() shouldBe NovelReaderTheme.DEFAULT.colors()
    }

    @Test
    fun `a preset ignores the custom slots`() {
        val slots = listOf(NovelReaderColors(SEPIA_PAPER, SEPIA_INK), null, null)

        NovelReaderTheme.NIGHT.colors(slots) shouldBe NovelReaderTheme.NIGHT.colors()
    }

    /** One slot per custom entry, so no entry can point past the end of the list. */
    @Test
    fun `there is a slot for every custom entry`() {
        val slots = NovelReaderTheme.entries.mapNotNull { it.slot }

        slots shouldBe List(NovelReaderTheme.CUSTOM_SLOTS) { it }
    }
}

private const val SEPIA_PAPER = 0xFFEAD9BD.toInt()
private const val SEPIA_INK = 0xFF4A3A28.toInt()
