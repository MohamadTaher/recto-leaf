package leaf.novel.ui.reader.setting

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR

/**
 * What a tap or a long tap on one section of the mini status bar can be bound to.
 *
 * The same shape as [NovelReaderKey]: one entry per binding, each carrying what to call it and what
 * it starts out doing. The defaults are the imported Moon+ configuration, with one exception — the
 * import binds text selection to the middle long tap, and a status bar has no text to select, so it
 * starts unbound rather than doing nothing visible.
 *
 * [placement] is never [NovelStatusPlacement.OFF]; the six entries cover the bar's three sections
 * twice over, once per kind of press.
 */
enum class NovelStatusBarTap(
    val placement: NovelStatusPlacement,
    val longPress: Boolean,
    val titleRes: StringResource,
    val default: NovelReaderAction,
) {
    TAP_LEFT(
        NovelStatusPlacement.LEFT,
        false,
        MR.strings.leaf_novel_status_tap_left,
        NovelReaderAction.SHOW_CHAPTERS,
    ),
    TAP_MIDDLE(
        NovelStatusPlacement.MIDDLE,
        false,
        MR.strings.leaf_novel_status_tap_middle,
        NovelReaderAction.BOOK_INFORMATION,
    ),
    TAP_RIGHT(
        NovelStatusPlacement.RIGHT,
        false,
        MR.strings.leaf_novel_status_tap_right,
        NovelReaderAction.BOOK_INFORMATION,
    ),
    LONG_TAP_LEFT(
        NovelStatusPlacement.LEFT,
        true,
        MR.strings.leaf_novel_status_long_tap_left,
        NovelReaderAction.SHOW_CHAPTERS,
    ),
    LONG_TAP_MIDDLE(
        NovelStatusPlacement.MIDDLE,
        true,
        MR.strings.leaf_novel_status_long_tap_middle,
        NovelReaderAction.NONE,
    ),
    LONG_TAP_RIGHT(
        NovelStatusPlacement.RIGHT,
        true,
        MR.strings.leaf_novel_status_long_tap_right,
        NovelReaderAction.SHOW_CHAPTERS,
    ),
    ;

    companion object {
        private val byTarget = entries.associateBy { it.placement to it.longPress }

        /** The binding for one section and one kind of press. Null only for [NovelStatusPlacement.OFF]. */
        fun of(placement: NovelStatusPlacement, longPress: Boolean): NovelStatusBarTap? =
            byTarget[placement to longPress]
    }
}
