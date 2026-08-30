package leaf.novel.ui.reader.setting

import io.kotest.matchers.shouldBe
import leaf.novel.ui.reader.NovelReaderCss
import org.junit.jupiter.api.Test

private const val WHITE = 0xFFFFFFFF.toInt()
private const val BLACK = 0xFF000000.toInt()
private const val MIHON_GRAY = 0xFF2B2B2B.toInt()

/**
 * A theme resolves to the pair of colours the whole reader draws with, so getting this wrong is a
 * page whose text and background disagree. The case that matters most is Follow Mihon, which has to
 * reproduce the derivation the reader used before themes existed on every one of Mihon's own
 * backgrounds.
 */
class NovelReaderThemeTest {

    @Test
    fun `follow Mihon keeps the background it is given`() {
        NovelReaderTheme.FOLLOW_MIHON.colors(WHITE).background shouldBe WHITE
        NovelReaderTheme.FOLLOW_MIHON.colors(BLACK).background shouldBe BLACK
        NovelReaderTheme.FOLLOW_MIHON.colors(MIHON_GRAY).background shouldBe MIHON_GRAY
    }

    @Test
    fun `follow Mihon derives the foreground exactly as the reader did before themes`() {
        for (background in listOf(WHITE, BLACK, MIHON_GRAY)) {
            NovelReaderTheme.FOLLOW_MIHON.colors(background).foreground shouldBe
                NovelReaderCss.foregroundFor(background)
        }
    }

    @Test
    fun `a preset ignores Mihon's background entirely`() {
        val onWhite = NovelReaderTheme.SEPIA.colors(WHITE)
        val onBlack = NovelReaderTheme.SEPIA.colors(BLACK)

        onWhite shouldBe onBlack
        onWhite.background shouldBe 0xFFEAD9BD.toInt()
        onWhite.foreground shouldBe 0xFF4A3A28.toInt()
    }

    /**
     * The point of the stage: a preset's text colour is chosen, not derived. Sepia's ink is a warm
     * brown, where the luminance rule would have made it near-black on that background.
     */
    @Test
    fun `every preset carries a foreground the luminance rule would not have picked`() {
        val presets = NovelReaderTheme.entries.filter { it.background != null }

        presets.forEach { theme ->
            val colors = theme.colors(WHITE)
            colors.background shouldBe theme.background
            colors.foreground shouldBe theme.foreground
        }
        presets.any { it.foreground != NovelReaderCss.foregroundFor(it.background!!) } shouldBe true
    }

    @Test
    fun `a custom slot draws in whatever that slot holds`() {
        val slots = listOf(null, NovelReaderColors(SEPIA_PAPER, SEPIA_INK), null)

        NovelReaderTheme.CUSTOM_2.colors(WHITE, slots) shouldBe
            NovelReaderColors(SEPIA_PAPER, SEPIA_INK)
    }

    /**
     * Selecting a slot that has never been filled must not blank the page, and a slot list that has
     * not loaded yet must not either — both fall back to what Follow Mihon would have drawn.
     */
    @Test
    fun `an empty custom slot falls back to follow Mihon`() {
        val empty = listOf(null, null, null)

        NovelReaderTheme.CUSTOM_1.colors(BLACK, empty) shouldBe
            NovelReaderTheme.FOLLOW_MIHON.colors(BLACK)
        NovelReaderTheme.CUSTOM_3.colors(BLACK) shouldBe
            NovelReaderTheme.FOLLOW_MIHON.colors(BLACK)
    }

    @Test
    fun `a preset ignores the custom slots`() {
        val slots = listOf(NovelReaderColors(SEPIA_PAPER, SEPIA_INK), null, null)

        NovelReaderTheme.NIGHT.colors(WHITE, slots) shouldBe NovelReaderTheme.NIGHT.colors(WHITE)
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
