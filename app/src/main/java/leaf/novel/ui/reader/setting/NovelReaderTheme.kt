package leaf.novel.ui.reader.setting

import androidx.annotation.ColorInt
import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR

/** The two colours a page is drawn in, once a theme has been resolved. */
data class NovelReaderColors(
    @ColorInt val background: Int,
    @ColorInt val foreground: Int,
)

/**
 * A background and a text colour together.
 *
 * Mihon's `readerTheme` is four backgrounds with no text colour of its own, which is right for
 * black-on-white and white-on-black and wrong for everything in between — a sepia page above all.
 * So the reader keeps its own, and the image reader, which shares `readerTheme`, is untouched.
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
    WHITE(MR.strings.leaf_novel_theme_white, 0xFFFFFFFF.toInt(), 0xFF1F1F1F.toInt()),
    PAPER(MR.strings.leaf_novel_theme_paper, 0xFFF3EEE3.toInt(), 0xFF33302B.toInt()),
    SEPIA(MR.strings.leaf_novel_theme_sepia, 0xFFEAD9BD.toInt(), 0xFF4A3A28.toInt()),
    NIGHT(MR.strings.leaf_novel_theme_night, 0xFF121212.toInt(), 0xFFC8C8C8.toInt()),
    BLACK(MR.strings.leaf_novel_theme_black, 0xFF000000.toInt(), 0xFFB4B4B4.toInt()),
    SOLARIZED(MR.strings.leaf_novel_theme_solarized, 0xFF002B36.toInt(), 0xFF93A1A1.toInt()),
    CUSTOM_1(MR.strings.leaf_novel_theme_custom_1, null, null, slot = 0),
    CUSTOM_2(MR.strings.leaf_novel_theme_custom_2, null, null, slot = 1),
    CUSTOM_3(MR.strings.leaf_novel_theme_custom_3, null, null, slot = 2),
    ;

    /**
     * The colours to draw with, given whatever the reader's own slots hold.
     *
     * The caller resolves this once and hands the pair to the stylesheet, the WebView, the page
     * behind it and the status bar alike, so none of them can disagree about what colour the page
     * is. A slot that has never been given colours falls back to [DEFAULT]'s, so selecting an empty
     * one shows the page rather than a blank rectangle.
     */
    fun colors(custom: List<NovelReaderColors?> = emptyList()): NovelReaderColors =
        slot?.let(custom::getOrNull)
            ?: if (background != null && foreground != null) {
                NovelReaderColors(background, foreground)
            } else {
                DEFAULT.colors()
            }

    companion object {
        /** How many slots of their own colours a reader gets. Fixed; see [NovelCustomTheme]. */
        const val CUSTOM_SLOTS = 3

        val DEFAULT = PAPER
    }
}
