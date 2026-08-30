package leaf.novel.ui.reader.setting

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR

/**
 * Where in the mini status bar an item sits, or that it does not appear at all.
 *
 * [OFF] is a placement rather than a switch of its own, so choosing what the bar shows is one
 * single-select row per item and the settings screen needs no multi-select control it does not
 * already have. The other three are the bar's sections; [SECTIONS] is that list without [OFF],
 * which is what the bar lays out and what [NovelStatusBarTap] binds against.
 */
enum class NovelStatusPlacement(val titleRes: StringResource) {
    OFF(MR.strings.off),
    LEFT(MR.strings.leaf_novel_status_left),
    MIDDLE(MR.strings.leaf_novel_status_middle),
    RIGHT(MR.strings.leaf_novel_status_right),
    ;

    companion object {
        /** The three sections, in the order they are laid out. */
        val SECTIONS = entries - OFF
    }
}

/**
 * What the mini status bar can show, and where each starts out.
 *
 * The defaults are the imported Moon+ configuration: battery and clock on the left, the chapter and
 * how far into it in the middle, how far through the book on the right.
 *
 * Declaration order is render order — a section shows the items placed in it in the order they
 * appear here, so moving an entry moves it on the bar.
 *
 * Stored with `getEnum`, so the constant names are persisted: reorder them freely, but renaming one
 * loses where a reader had put it.
 */
enum class NovelStatusItem(val titleRes: StringResource, val default: NovelStatusPlacement) {
    BATTERY(MR.strings.leaf_novel_status_battery, NovelStatusPlacement.LEFT),
    CLOCK(MR.strings.leaf_novel_status_clock, NovelStatusPlacement.LEFT),
    REMAINING_TIME(MR.strings.leaf_novel_status_remaining_time, NovelStatusPlacement.OFF),
    CHAPTER_NAME(MR.strings.leaf_novel_status_chapter_name, NovelStatusPlacement.MIDDLE),
    POSITION_IN_CHAPTER(MR.strings.leaf_novel_status_position, NovelStatusPlacement.MIDDLE),
    CHAPTER_NUMBER(MR.strings.leaf_novel_status_chapter_number, NovelStatusPlacement.OFF),
    CHAPTER_PERCENT(MR.strings.leaf_novel_status_chapter_percent, NovelStatusPlacement.OFF),
    BOOK_PERCENT(MR.strings.leaf_novel_status_book_percent, NovelStatusPlacement.RIGHT),
}
