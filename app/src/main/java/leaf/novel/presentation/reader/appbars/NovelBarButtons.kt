package leaf.novel.presentation.reader.appbars

import androidx.compose.ui.graphics.vector.ImageVector
import leaf.novel.ui.reader.setting.NovelReaderAction
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.Sort
import mihon.icons.materialsymbols.rounded.FormatListNumbered
import mihon.icons.materialsymbols.rounded.Info
import mihon.icons.materialsymbols.rounded.Palette
import mihon.icons.materialsymbols.rounded.PlayArrow
import mihon.icons.materialsymbols.rounded.ScreenRotation
import mihon.icons.materialsymbols.rounded.Search
import mihon.icons.materialsymbols.rounded.Settings
import mihon.icons.materialsymbols.rounded.ViewModule

/**
 * Which of the reader's actions can go on the bottom bar, and what each looks like there.
 *
 * A bar button is just an action with a glyph, so this is a lookup rather than a type of its own —
 * the picker in the settings offers [CANDIDATES] and the bar draws whatever it finds.
 *
 * The candidate set is exactly the actions the generated Material Symbols set has an icon for. Day
 * and night mode and the reading ruler are absent for that reason and no other: there is no glyph
 * for either, drawing one would be a new icon, and both are still an item in the additional options
 * menu. An unlabelled button nobody can identify is worse than one more line in a menu.
 */
object NovelBarButtons {

    /** In the order the picker offers them, which is roughly how often they are wanted. */
    val CANDIDATES = listOf(
        NovelReaderAction.NONE,
        NovelReaderAction.VISUAL_OPTIONS,
        NovelReaderAction.CONTROL_OPTIONS,
        NovelReaderAction.MISCELLANEOUS,
        NovelReaderAction.ADVANCED_OPTIONS,
        NovelReaderAction.ADDITIONAL_OPTIONS,
        NovelReaderAction.SHOW_CHAPTERS,
        NovelReaderAction.SEARCH,
        NovelReaderAction.AUTO_SCROLL,
        NovelReaderAction.BOOK_INFORMATION,
    )

    /** Today's bar, so a reader who never opens the setting keeps the one they have. */
    val DEFAULT = listOf(
        NovelReaderAction.VISUAL_OPTIONS,
        NovelReaderAction.CONTROL_OPTIONS,
        NovelReaderAction.MISCELLANEOUS,
        NovelReaderAction.ADVANCED_OPTIONS,
        NovelReaderAction.ADDITIONAL_OPTIONS,
    )

    /** How many the bar has room for. Past six they stop being tappable on a phone. */
    const val SLOTS = 6

    /**
     * The bar the chosen slots add up to.
     *
     * Duplicates collapse and unset slots drop out, so the order of the rows is the order of the
     * bar and nothing else has to be enforced while editing. An empty result falls back to
     * [DEFAULT] rather than leaving a bar with nothing on it — a reader who has cleared every slot
     * has not asked for the chrome to disappear, and there would be no way back if they had.
     */
    fun resolve(chosen: List<NovelReaderAction>): List<NovelReaderAction> =
        chosen.filter { it != NovelReaderAction.NONE }
            .distinct()
            .take(SLOTS)
            .ifEmpty { DEFAULT }

    /** The glyph for a candidate. Null for anything that is not one. */
    fun iconFor(action: NovelReaderAction): ImageVector? = when (action) {
        NovelReaderAction.VISUAL_OPTIONS -> MaterialSymbols.Rounded.Palette
        // The controls group takes the rotation glyph because orientation is one of its settings.
        NovelReaderAction.CONTROL_OPTIONS -> MaterialSymbols.Rounded.ScreenRotation
        NovelReaderAction.MISCELLANEOUS -> MaterialSymbols.Rounded.ViewModule
        NovelReaderAction.ADVANCED_OPTIONS -> MaterialSymbols.Rounded.Settings
        NovelReaderAction.ADDITIONAL_OPTIONS -> MaterialSymbols.AutoMirroredRounded.Sort
        NovelReaderAction.SHOW_CHAPTERS -> MaterialSymbols.Rounded.FormatListNumbered
        NovelReaderAction.SEARCH -> MaterialSymbols.Rounded.Search
        NovelReaderAction.AUTO_SCROLL -> MaterialSymbols.Rounded.PlayArrow
        NovelReaderAction.BOOK_INFORMATION -> MaterialSymbols.Rounded.Info
        else -> null
    }
}
