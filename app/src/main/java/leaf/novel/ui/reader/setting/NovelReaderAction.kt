package leaf.novel.ui.reader.setting

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR

/**
 * What a tap, a key or a gesture can be bound to.
 *
 * Constant names are persisted. [fromPreference] preserves bindings from older command lists.
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
    COMMENTS(MR.strings.leaf_novel_comments),
    BOOK_INFORMATION(MR.strings.leaf_novel_action_book_information),
    DAY_NIGHT_MODE(MR.strings.leaf_novel_reader_day_night_mode),
    CHANGE_THEME(MR.strings.leaf_novel_action_change_theme),
    PUBLISHER_FORMATTING(MR.strings.leaf_novel_action_publisher_formatting),
    START_SPEAKING(MR.strings.leaf_novel_action_start_speaking),

    // Internal directional command: a headset's Pause event must never resume paused speech.
    PAUSE_SPEAKING(MR.strings.leaf_novel_action_pause_speaking),
    TOGGLE_SPEECH(MR.strings.leaf_novel_action_toggle_speech),
    STOP_SPEAKING(MR.strings.leaf_novel_action_stop_speaking),
    PREVIOUS_SPEECH(MR.strings.leaf_novel_reader_speech_previous),
    NEXT_SPEECH(MR.strings.leaf_novel_reader_speech_next),
    VOLUME_UP(MR.strings.leaf_novel_key_volume_up),
    VOLUME_DOWN(MR.strings.leaf_novel_key_volume_down),
    SPEED_READ(MR.strings.leaf_novel_action_speed_read),
    SCREEN_ORIENTATION(MR.strings.rotation_type),
    TEXT_SELECTION(MR.strings.leaf_novel_action_text_selection),
    BRIGHTNESS(MR.strings.leaf_novel_action_brightness),
    VISUAL_OPTIONS(MR.strings.leaf_novel_reader_tab_visual),
    CONTROL_OPTIONS(MR.strings.leaf_novel_reader_tab_control),
    MISCELLANEOUS(MR.strings.leaf_novel_reader_tab_misc),
    ADVANCED_OPTIONS(MR.strings.leaf_novel_reader_tab_advanced),
    ADDITIONAL_OPTIONS(MR.strings.leaf_novel_reader_additional_options),
    CLOSE(MR.strings.action_close),
    ;

    companion object {
        val assignable = entries.filter { it != PAUSE_SPEAKING }

        fun fromPreference(
            value: String,
            default: NovelReaderAction,
            legacySpeak: NovelReaderAction = START_SPEAKING,
        ): NovelReaderAction = when (value) {
            "SPEAK" -> legacySpeak
            "PAUSE_SPEAKING" -> TOGGLE_SPEECH
            else -> entries.find { it.name == value } ?: default
        }
    }
}
