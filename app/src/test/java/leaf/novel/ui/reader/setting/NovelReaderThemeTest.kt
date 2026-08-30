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
        val presets = NovelReaderTheme.entries - NovelReaderTheme.FOLLOW_MIHON

        presets.forEach { theme ->
            val colors = theme.colors(WHITE)
            colors.background shouldBe theme.background
            colors.foreground shouldBe theme.foreground
        }
        presets.any { it.foreground != NovelReaderCss.foregroundFor(it.background!!) } shouldBe true
    }
}
