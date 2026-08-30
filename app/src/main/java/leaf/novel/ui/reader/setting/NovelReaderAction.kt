package leaf.novel.ui.reader.setting

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR

/**
 * What a tap, a key or a gesture can be bound to.
 *
 * Only actions whose feature exists are listed. An action a reader can select that silently does
 * nothing is worse than one that is not offered, so each later stage adds its own constant when it
 * lands the thing behind it — auto scroll, the reading ruler, search, the chapter list, book
 * information, screen orientation and speech are all still to come.
 *
 * Stored with `getEnum`, so the constant names are persisted: reorder them freely, but renaming one
 * loses whatever a reader had bound to it.
 */
enum class NovelReaderAction(val titleRes: StringResource) {
    NONE(MR.strings.none),
    OPTIONS_MENU(MR.strings.leaf_novel_action_options_menu),
    PAGE_UP(MR.strings.leaf_novel_action_page_up),
    PAGE_DOWN(MR.strings.leaf_novel_action_page_down),
    AUTO_SCROLL(MR.strings.leaf_novel_action_auto_scroll),
    READING_RULER(MR.strings.leaf_novel_reader_reading_ruler),
    SEARCH(MR.strings.action_search),
    SHOW_CHAPTERS(MR.strings.chapters),
    BOOK_INFORMATION(MR.strings.leaf_novel_action_book_information),
    DAY_NIGHT_MODE(MR.strings.leaf_novel_reader_day_night_mode),
    CHANGE_THEME(MR.strings.leaf_novel_action_change_theme),
    SCREEN_ORIENTATION(MR.strings.rotation_type),
    TEXT_SELECTION(MR.strings.leaf_novel_action_text_selection),
    BRIGHTNESS(MR.strings.leaf_novel_action_brightness),
    VISUAL_OPTIONS(MR.strings.leaf_novel_reader_tab_visual),
    CONTROL_OPTIONS(MR.strings.leaf_novel_reader_tab_control),
    MISCELLANEOUS(MR.strings.leaf_novel_reader_tab_misc),
    ADDITIONAL_OPTIONS(MR.strings.leaf_novel_reader_additional_options),
    CLOSE(MR.strings.action_close),
}
