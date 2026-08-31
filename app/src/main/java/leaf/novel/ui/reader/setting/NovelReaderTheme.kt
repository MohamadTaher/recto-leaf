package leaf.novel.ui.reader.setting

import androidx.annotation.ColorInt
import dev.icerock.moko.resources.StringResource
import leaf.novel.ui.reader.NovelReaderCss
import tachiyomi.i18n.MR

/** The two colours a page is drawn in, once a theme has been resolved against Mihon's own. */
data class NovelReaderColors(
    @ColorInt val background: Int,
    @ColorInt val foreground: Int,
)

/**
 * A background and a text colour together.
 *
 * This is the one setting on the imported list that Mihon cannot carry as it stands: `readerTheme`
 * is four backgrounds with no text colour of its own, and the reader has been deriving one from the
 * background's luminance ever since. That derivation is right for black-on-white and white-on-black
 * and wrong for everything in between, which is what a sepia page is.
 *
 * [FOLLOW_MIHON] is therefore the default and keeps the derivation, so a reader who never opens this
 * setting sees exactly what they saw before it existed — and the image reader, which shares
 * `readerTheme`, is untouched either way.
 *
 * Stored with `getEnum`, so the constant names are persisted: reorder them freely, but renaming one
 * loses whatever a reader had chosen.
 */
enum class NovelReaderTheme(
    val titleRes: StringResource,
    @ColorInt val background: Int?,
    @ColorInt val foreground: Int?,
    val slot: Int? = null,
) {
    FOLLOW_MIHON(MR.strings.leaf_novel_theme_follow_mihon, null, null),
    PAPER(MR.strings.leaf_novel_theme_paper, 0xFFF3EEE3.toInt(), 0xFF33302B.toInt()),
    SEPIA(MR.strings.leaf_novel_theme_sepia, 0xFFEAD9BD.toInt(), 0xFF4A3A28.toInt()),
    GREY(MR.strings.leaf_novel_theme_grey, 0xFF8A8A8A.toInt(), 0xFF141414.toInt()),
    NIGHT(MR.strings.leaf_novel_theme_night, 0xFF121212.toInt(), 0xFFC8C8C8.toInt()),
    CONSOLE(MR.strings.leaf_novel_theme_console, 0xFF000000.toInt(), 0xFF4FD86B.toInt()),
    CUSTOM_1(MR.strings.leaf_novel_theme_custom_1, null, null, slot = 0),
    CUSTOM_2(MR.strings.leaf_novel_theme_custom_2, null, null, slot = 1),
    CUSTOM_3(MR.strings.leaf_novel_theme_custom_3, null, null, slot = 2),
    ;

    /**
     * The colours to draw with, given whatever background Mihon's own theme currently resolves to
     * and whatever the reader's own slots hold.
     *
     * A preset ignores both arguments; [FOLLOW_MIHON] and the custom slots are why they are taken.
     * The caller resolves this once and hands the pair to the stylesheet, the WebView, the page
     * behind it and the status bar alike, so none of them can disagree about what colour the page
     * is. A slot that has never been given colours falls back to [FOLLOW_MIHON]'s, so selecting an
     * empty one shows the page rather than a blank rectangle.
     */
    fun colors(
        @ColorInt mihonBackground: Int,
        custom: List<NovelReaderColors?> = emptyList(),
    ): NovelReaderColors {
        val slotColors = slot?.let(custom::getOrNull)
        return NovelReaderColors(
            background = background ?: slotColors?.background ?: mihonBackground,
            foreground = foreground ?: slotColors?.foreground
                ?: NovelReaderCss.foregroundFor(mihonBackground),
        )
    }

    companion object {
        /** How many slots of their own colours a reader gets. Fixed; see [NovelCustomTheme]. */
        const val CUSTOM_SLOTS = 3
    }
}
