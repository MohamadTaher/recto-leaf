package leaf.novel.ui.reader.setting

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum

/**
 * The reader's own preferences — the ones text needs and images have no equivalent for.
 *
 * Theme, brightness, colour filter, grayscale, inverted colours, keep-screen-on and fullscreen are
 * all read from the existing [eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences], so there is
 * no second settings system and no duplicated keys.
 *
 * Everything visual here is collected into a [NovelReaderStyle] and handed to the stylesheet as one
 * value.
 */
@Inject
@SingleIn(AppScope::class)
class NovelReaderPreferences(
    preferenceStore: PreferenceStore,
) {

    val fontSize: Preference<Int> = preferenceStore.getInt("leaf_novel_font_size", DEFAULT_FONT_SIZE)

    val font: Preference<NovelReaderFont> =
        preferenceStore.getEnum("leaf_novel_font", NovelReaderFont.SYSTEM)

    // region Text styling

    val bold: Preference<Boolean> = preferenceStore.getBoolean("leaf_novel_bold", false)

    val italic: Preference<Boolean> = preferenceStore.getBoolean("leaf_novel_italic", false)

    val underline: Preference<Boolean> = preferenceStore.getBoolean("leaf_novel_underline", false)

    val shadow: Preference<Boolean> = preferenceStore.getBoolean("leaf_novel_shadow", false)

    val antialias: Preference<Boolean> = preferenceStore.getBoolean("leaf_novel_antialias", true)

    val justified: Preference<Boolean> = preferenceStore.getBoolean("leaf_novel_justified", false)

    val hyphenation: Preference<Boolean> = preferenceStore.getBoolean("leaf_novel_hyphenation", false)

    // endregion

    // region Spacing and scale

    /** Space below each paragraph before the reader's 1.5 rendering scale is applied. */
    val paragraphSpacing: Preference<Int> = preferenceStore.getInt("leaf_novel_paragraph_spacing", 100)

    /** Steps of a tenth added to a single-spaced line. The default lands on the familiar 1.6. */
    val lineSpacing: Preference<Int> = preferenceStore.getInt("leaf_novel_line_spacing", 4)

    /** Space between letters, in hundredths of an em. Negative tightens. */
    val fontSpacing: Preference<Int> = preferenceStore.getInt("leaf_novel_font_spacing", 0)

    /** Fine adjustment to [fontSize], in steps of 2.5%, for sizes between its whole numbers. */
    val fontScale: Preference<Int> = preferenceStore.getInt("leaf_novel_font_scale", 0)

    // endregion

    // region Margins

    /** Page padding in CSS pixels, which the viewport meta tag makes equivalent to dp. */
    val marginLeft: Preference<Int> = preferenceStore.getInt("leaf_novel_margin_left", 14)

    val marginRight: Preference<Int> = preferenceStore.getInt("leaf_novel_margin_right", 10)

    val marginTop: Preference<Int> = preferenceStore.getInt("leaf_novel_margin_top", 3)

    val marginBottom: Preference<Int> = preferenceStore.getInt("leaf_novel_margin_bottom", 3)

    // region Colours

    /** The colour of a link in the book's text. [NovelLinkColor.DEFAULT] leaves it to the theme. */
    val linkColor: Preference<NovelLinkColor> =
        preferenceStore.getEnum("leaf_novel_link_color", NovelLinkColor.DEFAULT)

    /**
     * The background and text colour the page is drawn in.
     *
     * [NovelReaderTheme.FOLLOW_MIHON] defers to the shared reader theme and derives the text colour
     * from it, which is what the reader did before this was a setting.
     */
    val theme: Preference<NovelReaderTheme> =
        preferenceStore.getEnum("leaf_novel_theme", NovelReaderTheme.FOLLOW_MIHON)

    /**
     * The pair day/night mode flips between.
     *
     * Both start at [NovelReaderTheme.FOLLOW_MIHON], where there is nothing to flip — so the action
     * goes on flipping the shared reader theme between white and black, exactly as it does today.
     */
    val dayTheme: Preference<NovelReaderTheme> =
        preferenceStore.getEnum("leaf_novel_day_theme", NovelReaderTheme.FOLLOW_MIHON)

    val nightTheme: Preference<NovelReaderTheme> =
        preferenceStore.getEnum("leaf_novel_night_theme", NovelReaderTheme.FOLLOW_MIHON)

    /**
     * The reader's own colours, one entry per [NovelReaderTheme] custom slot.
     *
     * A fixed count rather than a growable list: three preferences a slot and no serialization,
     * where a list would need a format, an escape for the names in it, and a migration the first
     * time a field was added.
     */
    val customThemes: List<NovelCustomTheme> = List(NovelReaderTheme.CUSTOM_SLOTS) { slot ->
        NovelCustomTheme(
            name = preferenceStore.getString("leaf_novel_custom_theme_${slot + 1}_name", ""),
            background = preferenceStore.getInt(
                "leaf_novel_custom_theme_${slot + 1}_background",
                NovelCustomTheme.UNSET,
            ),
            foreground = preferenceStore.getInt(
                "leaf_novel_custom_theme_${slot + 1}_foreground",
                NovelCustomTheme.UNSET,
            ),
        )
    }

    // region Focused reading

    val readingRuler: Preference<Boolean> = preferenceStore.getBoolean("leaf_novel_reading_ruler", false)

    val highlightFirstWord: Preference<Boolean> =
        preferenceStore.getBoolean("leaf_novel_highlight_first_word", false)

    val highlightInitialChars: Preference<Boolean> =
        preferenceStore.getBoolean("leaf_novel_highlight_initial_chars", false)

    // region Screen and UI

    val disableTouchEdge: Preference<Boolean> =
        preferenceStore.getBoolean("leaf_novel_disable_touch_edge", false)

    // region Mini status bar

    val showStatusBar: Preference<Boolean> =
        preferenceStore.getBoolean("leaf_novel_show_status_bar", true)

    /**
     * Where each item of the bar sits, or that it does not appear.
     *
     * A placement per item rather than a list per section: every row is then a single choice, which
     * the settings screen can already show, and a section is whatever points at it.
     */
    val statusSlots: Map<NovelStatusItem, Preference<NovelStatusPlacement>> =
        NovelStatusItem.entries.associateWith { item ->
            preferenceStore.getEnum("leaf_novel_status_slot_${item.name.lowercase()}", item.default)
        }

    // region Typesetting

    private val legacyIndentFirstLine =
        preferenceStore.getBoolean("leaf_novel_indent_first_line", true)

    /** First-line indent before the reader's 1.5 rendering scale is applied. */
    val paragraphIndent: Preference<Int> = preferenceStore.getInt(
        "leaf_novel_paragraph_indent",
        if (legacyIndentFirstLine.get()) 100 else 0,
    )

    val trimBlankLines: Preference<Boolean> =
        preferenceStore.getBoolean("leaf_novel_trim_blank_lines", false)

    /**
     * Find-and-replace rules, as a JSON list.
     *
     * One preference rather than a fixed set of slots, because how many rules a book needs is the
     * reader's business — unlike the custom themes, where three was more than anyone wanted.
     */
    val textReplacements: Preference<String> =
        preferenceStore.getString("leaf_novel_text_replacements", "")

    // region Format specific

    /**
     * Whether the book's own `<head>` is injected ahead of the reader's stylesheet.
     *
     * On by default, per the imported configuration: a book's own CSS is what makes it render
     * unlike every other book in the library, and the reader's settings exist to override it.
     */
    val disableBookCss: Preference<Boolean> =
        preferenceStore.getBoolean("leaf_novel_disable_book_css", true)

    /** Suppresses the reader's own font-family, leaving whatever the book names to apply. */
    val useBookFonts: Preference<Boolean> =
        preferenceStore.getBoolean("leaf_novel_use_book_fonts", false)

    val inlineFootnotes: Preference<Boolean> =
        preferenceStore.getBoolean("leaf_novel_inline_footnotes", true)

    val printPageNumbers: Preference<Boolean> =
        preferenceStore.getBoolean("leaf_novel_print_page_numbers", false)

    /** Whether the publisher-formatting preview is offered in the additional options menu. */
    val publisherPreview: Preference<Boolean> =
        preferenceStore.getBoolean("leaf_novel_publisher_preview", false)

    /** The colour a folded-in footnote is drawn in. Shares the link palette. */
    val noteColor: Preference<NovelLinkColor> =
        preferenceStore.getEnum("leaf_novel_note_color", NovelLinkColor.DEFAULT)

    // region Images

    val imageSize: Preference<NovelImageSize> =
        preferenceStore.getEnum("leaf_novel_image_size", NovelImageSize.FIT_WIDTH)

    val centerImages: Preference<Boolean> =
        preferenceStore.getBoolean("leaf_novel_center_images", true)

    val tapImageToOpen: Preference<Boolean> =
        preferenceStore.getBoolean("leaf_novel_tap_image_to_open", true)

    // region Speech

    val speechDivision: Preference<NovelSpeechDivision> =
        preferenceStore.getEnum("leaf_novel_tts_divide_by", NovelSpeechDivision.PARAGRAPH)

    /** Speaking pace in tenths, so 10 is the engine's own normal. */
    val speechRate: Preference<Int> = preferenceStore.getInt("leaf_novel_tts_rate", 10)

    /** Voice pitch in tenths, where 10 leaves the system voice unchanged. */
    val speechPitch: Preference<Int> = preferenceStore.getInt("leaf_novel_tts_pitch", 10)

    val speechIntervalMs: Preference<Int> =
        preferenceStore.getInt("leaf_novel_tts_interval_ms", 30)

    /** Zero leaves speech running until the chapter ends or the reader stops it. */
    val speechStopAfterMinutes: Preference<Int> =
        preferenceStore.getInt("leaf_novel_tts_stop_after_minutes", 0)

    val speechConfirmBeforeSpeak: Preference<Boolean> =
        preferenceStore.getBoolean("leaf_novel_tts_confirm_before_speak", false)

    /** Opts out of Android audio focus so another audio app may keep playing. */
    val speechMixAudio: Preference<Boolean> =
        preferenceStore.getBoolean("leaf_novel_tts_mix_audio", false)

    // region Speed reading

    val speedReadWpm: Preference<Int> = preferenceStore.getInt("leaf_novel_speed_read_wpm", 300)

    /** How many words go on screen at once. Beyond three it stops being speed reading. */
    val speedReadChunk: Preference<Int> = preferenceStore.getInt("leaf_novel_speed_read_chunk", 1)

    // region Eye care

    val bluelight: Preference<Boolean> = preferenceStore.getBoolean("leaf_novel_bluelight", false)

    val bluelightIntensity: Preference<Int> =
        preferenceStore.getInt("leaf_novel_bluelight_intensity", 50)

    // region Value gestures

    val edgeSwipeBrightness: Preference<Boolean> =
        preferenceStore.getBoolean("leaf_novel_edge_swipe_brightness", false)

    val edgeSwipeFontSize: Preference<Boolean> =
        preferenceStore.getBoolean("leaf_novel_edge_swipe_font_size", false)

    val pinchFontSize: Preference<Boolean> =
        preferenceStore.getBoolean("leaf_novel_pinch_font_size", false)

    // region Paging

    /**
     * Lays the chapter out in columns a viewport wide rather than one long scroll.
     *
     * Off by default: the reader has scrolled for seventeen stages and should not start paging
     * without being asked. Every preference below configures this one and is inert while it is off.
     */
    val paged: Preference<Boolean> = preferenceStore.getBoolean("leaf_novel_paged", false)

    val keepOneLineWhenPaging: Preference<Boolean> =
        preferenceStore.getBoolean("leaf_novel_keep_one_line_when_paging", false)

    val trimTopBlankLines: Preference<Boolean> =
        preferenceStore.getBoolean("leaf_novel_trim_top_blank_lines", false)

    val tiltToTurnPage: Preference<Boolean> =
        preferenceStore.getBoolean("leaf_novel_tilt_to_turn_page", false)

    val flingHorizontallyToTurnPage: Preference<Boolean> =
        preferenceStore.getBoolean("leaf_novel_fling_horizontally_to_turn_page", false)

    val disableVerticalScroll: Preference<Boolean> =
        preferenceStore.getBoolean("leaf_novel_disable_vertical_scroll", false)

    val dualPageLayout: Preference<Boolean> =
        preferenceStore.getBoolean("leaf_novel_dual_page_layout", false)

    val pageTurnSound: Preference<Boolean> =
        preferenceStore.getBoolean("leaf_novel_page_turn_sound", false)

    // endregion

    // endregion

    // endregion

    // endregion

    // endregion

    // endregion

    // endregion

    // endregion

    // endregion

    // endregion

    // endregion

    // endregion

    // endregion

    // region Controls

    /**
     * One binding per cell of [NovelTapGrid], indexed the way that grid numbers its cells. The
     * stored keys are one-based, matching how Moon+ and the settings screen both count them.
     */
    val tapZones: List<Preference<NovelReaderAction>> = List(NovelTapGrid.COUNT) { cell ->
        preferenceStore.getEnum("leaf_novel_tap_${cell + 1}", defaultTapAction(cell))
    }

    val longTap: Preference<NovelReaderAction> =
        preferenceStore.getEnum("leaf_novel_long_tap", NovelReaderAction.TEXT_SELECTION)

    /**
     * Reuses the image reader orientation model, but deliberately not its key.
     *
     * That key is the image reader default, and writing it from here would change how manga opens.
     */
    val orientation: Preference<ReaderOrientation> =
        preferenceStore.getEnum("leaf_novel_orientation", ReaderOrientation.PORTRAIT)

    /** How fast auto scroll creeps, in steps of six pixels a second. */
    val autoScrollSpeed: Preference<Int> = preferenceStore.getInt("leaf_novel_autoscroll_speed", 5)

    /** One binding per key, defaulted from [NovelReaderKey]. */
    val keys: Map<NovelReaderKey, Preference<NovelReaderAction>> =
        NovelReaderKey.entries.associateWith { key ->
            preferenceStore.getEnum("leaf_novel_key_${key.name.lowercase()}", key.default)
        }

    /** One binding per swipe direction. All start unbound; see [NovelReaderSwipe]. */
    val swipes: Map<NovelReaderSwipe, Preference<NovelReaderAction>> =
        NovelReaderSwipe.entries.associateWith { swipe ->
            preferenceStore.getEnum("leaf_novel_swipe_${swipe.name.lowercase()}", swipe.default)
        }

    /**
     * Which action each slot of the bottom bar carries, in order.
     *
     * The defaults are the four buttons the bar has always had, so a reader who never opens the
     * setting keeps the bar they know. Resolving the slots into a bar is presentation's job — only
     * it knows which actions have a glyph.
     */
    val barButtons: List<Preference<NovelReaderAction>> = List(BAR_SLOTS) { slot ->
        preferenceStore.getEnum("leaf_novel_bar_button_${slot + 1}", defaultBarButton(slot))
    }

    /** One binding per section of the mini status bar, tap and long tap. */
    val statusTaps: Map<NovelStatusBarTap, Preference<NovelReaderAction>> =
        NovelStatusBarTap.entries.associateWith { tap ->
            preferenceStore.getEnum("leaf_novel_status_${tap.name.lowercase()}", tap.default)
        }

    // endregion

    companion object {
        const val DEFAULT_FONT_SIZE = 18
        const val MIN_FONT_SIZE = 10
        const val MAX_FONT_SIZE = 32

        val PARAGRAPH_SPACING_RANGE = 0..200
        val PARAGRAPH_INDENT_RANGE = 0..200
        val LINE_SPACING_RANGE = -5..20
        val FONT_SPACING_RANGE = -4..20
        val FONT_SCALE_RANGE = -4..20
        val MARGIN_RANGE = 0..200
        val AUTO_SCROLL_SPEED_RANGE = 1..20
        val SPEECH_RATE_RANGE = 3..25
        val SPEECH_PITCH_RANGE = 5..20
        val SPEECH_INTERVAL_RANGE = 0..1_000
        val SPEECH_STOP_AFTER_RANGE = 0..120
        val SPEED_READ_WPM_RANGE = 100..900
        val SPEED_READ_CHUNK_RANGE = 1..3
        val BLUELIGHT_INTENSITY_RANGE = 0..100

        /** The image reader brightness range: below zero the overlay dims rather than the window. */
        val BRIGHTNESS_RANGE = -75..100

        /** Mihon own list minus DEFAULT, which means "inherit" and has nothing here to inherit. */
        val ORIENTATIONS = ReaderOrientation.entries - ReaderOrientation.DEFAULT

        /** How many buttons the bottom bar has room for. */
        const val BAR_SLOTS = 6
    }
}

/** Today's bar: the four settings tabs and the additional options menu, in that order. */
private fun defaultBarButton(slot: Int): NovelReaderAction = when (slot) {
    0 -> NovelReaderAction.VISUAL_OPTIONS
    1 -> NovelReaderAction.CONTROL_OPTIONS
    2 -> NovelReaderAction.MISCELLANEOUS
    3 -> NovelReaderAction.ADVANCED_OPTIONS
    4 -> NovelReaderAction.ADDITIONAL_OPTIONS
    else -> NovelReaderAction.NONE
}

/**
 * Only the middle, the top left and the bottom left start bound: the middle so tapping the page
 * still opens the menu as it always has, and the other two because the imported configuration puts
 * speech and day/night there.
 */
private fun defaultTapAction(cell: Int): NovelReaderAction = when (cell) {
    NovelTapGrid.CENTRE -> NovelReaderAction.OPTIONS_MENU
    NovelTapGrid.TOP_LEFT -> NovelReaderAction.SPEAK
    NovelTapGrid.BOTTOM_LEFT -> NovelReaderAction.DAY_NIGHT_MODE
    else -> NovelReaderAction.NONE
}
