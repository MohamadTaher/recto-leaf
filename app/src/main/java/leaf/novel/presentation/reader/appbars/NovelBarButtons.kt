package leaf.novel.presentation.reader.appbars

import androidx.compose.ui.graphics.vector.ImageVector
import leaf.novel.presentation.reader.comments.NovelCommentGlyphs
import leaf.novel.ui.reader.setting.NovelReaderAction
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.Sort
import mihon.icons.materialsymbols.rounded.FormatListNumbered
import mihon.icons.materialsymbols.rounded.Info
import mihon.icons.materialsymbols.rounded.Palette
import mihon.icons.materialsymbols.rounded.ScreenRotation
import mihon.icons.materialsymbols.rounded.Search
import mihon.icons.materialsymbols.rounded.Settings
import mihon.icons.materialsymbols.rounded.ViewModule
import mihon.icons.materialsymbols.roundedfilled.PlayArrow

/**
 * Which of the reader's actions can go on the bottom bar, and what each looks like there.
 *
 * A bar button is just an action with a glyph, so this is a lookup rather than a type of its own —
 * the picker in the settings offers [CANDIDATES] and the bar draws whatever it finds.
 *
 * The candidate set is the actions there is a glyph for. That is the generated Material Symbols set
 * for all but one of them — comments, whose speech bubble the fork draws itself in
 * [NovelCommentGlyphs] because the generated set has none and the tree it is generated from is
 * upstream's. Day and night mode and the reading ruler are absent for the same reason in reverse:
 * there is no glyph, drawing one would be a second new icon for something that is already an item in
 * the additional options menu, and an unlabelled button nobody can identify is worse than one more
 * line in a menu.
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
        NovelReaderAction.COMMENTS,
        NovelReaderAction.SHOW_CHAPTERS,
        NovelReaderAction.SEARCH,
        NovelReaderAction.AUTO_SCROLL,
        NovelReaderAction.BOOK_INFORMATION,
    )

    /**
     * The bar out of the box: the four settings tabs, comments, and the overflow menu last.
     *
     * Comments takes the fifth slot and pushes the overflow into the sixth, which was empty, so a
     * default bar gains a button rather than losing one. It comes before the overflow rather than
     * after it because an overflow menu belongs at the end of a bar, and it is on the bar by default
     * because a comments button nobody can find is a comments button nobody uses. It draws nothing
     * on a source that has no comments — see [resolve]'s `unavailable`.
     */
    val DEFAULT = listOf(
        NovelReaderAction.VISUAL_OPTIONS,
        NovelReaderAction.CONTROL_OPTIONS,
        NovelReaderAction.MISCELLANEOUS,
        NovelReaderAction.ADVANCED_OPTIONS,
        NovelReaderAction.COMMENTS,
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
     *
     * @param unavailable actions this novel cannot carry out — comments on a source that has none.
     * They are taken out here rather than by the caller so that the fallback drops them too: a
     * reader whose one chosen button was comments would otherwise be left with a bar of nothing,
     * which is the very state [DEFAULT] exists to prevent.
     */
    fun resolve(
        chosen: List<NovelReaderAction>,
        unavailable: Set<NovelReaderAction> = emptySet(),
    ): List<NovelReaderAction> =
        chosen.filter { it != NovelReaderAction.NONE && it !in unavailable }
            .distinct()
            .take(SLOTS)
            .ifEmpty { DEFAULT.filterNot { it in unavailable } }

    /** The glyph for a candidate. Null for anything that is not one. */
    fun iconFor(action: NovelReaderAction): ImageVector? = when (action) {
        NovelReaderAction.VISUAL_OPTIONS -> MaterialSymbols.Rounded.Palette
        // The controls group takes the rotation glyph because orientation is one of its settings.
        NovelReaderAction.CONTROL_OPTIONS -> MaterialSymbols.Rounded.ScreenRotation
        NovelReaderAction.MISCELLANEOUS -> MaterialSymbols.Rounded.ViewModule
        NovelReaderAction.ADVANCED_OPTIONS -> MaterialSymbols.Rounded.Settings
        NovelReaderAction.ADDITIONAL_OPTIONS -> MaterialSymbols.AutoMirroredRounded.Sort
        NovelReaderAction.COMMENTS -> NovelCommentGlyphs.Comment
        NovelReaderAction.SHOW_CHAPTERS -> MaterialSymbols.Rounded.FormatListNumbered
        NovelReaderAction.SEARCH -> MaterialSymbols.Rounded.Search
        NovelReaderAction.AUTO_SCROLL -> MaterialSymbols.RoundedFilled.PlayArrow
        NovelReaderAction.BOOK_INFORMATION -> MaterialSymbols.Rounded.Info
        else -> null
    }
}
