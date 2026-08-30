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

    /** Space below each paragraph, in hundredths of an em. */
    val paragraphSpacing: Preference<Int> = preferenceStore.getInt("leaf_novel_paragraph_spacing", 60)

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

    // region Focused reading

    val readingRuler: Preference<Boolean> = preferenceStore.getBoolean("leaf_novel_reading_ruler", false)

    val highlightFirstWord: Preference<Boolean> =
        preferenceStore.getBoolean("leaf_novel_highlight_first_word", false)

    val highlightInitialChars: Preference<Boolean> =
        preferenceStore.getBoolean("leaf_novel_highlight_initial_chars", false)

    // region Screen and UI

    val disableTouchEdge: Preference<Boolean> =
        preferenceStore.getBoolean("leaf_novel_disable_touch_edge", false)

    val showRemainingTime: Preference<Boolean> =
        preferenceStore.getBoolean("leaf_novel_show_remaining_time", false)

    // region Typesetting

    val indentFirstLine: Preference<Boolean> =
        preferenceStore.getBoolean("leaf_novel_indent_first_line", true)

    val trimBlankLines: Preference<Boolean> =
        preferenceStore.getBoolean("leaf_novel_trim_blank_lines", false)

    // region Eye care

    val bluelight: Preference<Boolean> = preferenceStore.getBoolean("leaf_novel_bluelight", false)

    val bluelightIntensity: Preference<Int> =
        preferenceStore.getInt("leaf_novel_bluelight_intensity", 50)

    /** Minutes of reading before a nudge to rest. Zero is off. */
    val reminderMinutes: Preference<Int> = preferenceStore.getInt("leaf_novel_reminder_minutes", 0)

    /** A time of day as HH:mm. Empty is off. */
    val reminderAt: Preference<String> = preferenceStore.getString("leaf_novel_reminder_at", "")

    // region Value gestures

    val edgeSwipeBrightness: Preference<Boolean> =
        preferenceStore.getBoolean("leaf_novel_edge_swipe_brightness", false)

    val edgeSwipeFontSize: Preference<Boolean> =
        preferenceStore.getBoolean("leaf_novel_edge_swipe_font_size", false)

    val pinchFontSize: Preference<Boolean> =
        preferenceStore.getBoolean("leaf_novel_pinch_font_size", false)

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
            preferenceStore.getEnum("leaf_novel_swipe_${swipe.name.lowercase()}", NovelReaderAction.NONE)
        }

    // endregion

    companion object {
        const val DEFAULT_FONT_SIZE = 18
        const val MIN_FONT_SIZE = 10
        const val MAX_FONT_SIZE = 32

        val PARAGRAPH_SPACING_RANGE = 0..200
        val LINE_SPACING_RANGE = -5..20
        val FONT_SPACING_RANGE = -4..20
        val FONT_SCALE_RANGE = -4..20
        val MARGIN_RANGE = 0..200
        val AUTO_SCROLL_SPEED_RANGE = 1..20
        val BLUELIGHT_INTENSITY_RANGE = 0..100
        val REMINDER_MINUTES_RANGE = 0..120

        /** The image reader brightness range: below zero the overlay dims rather than the window. */
        val BRIGHTNESS_RANGE = -75..100

        /** Mihon own list minus DEFAULT, which means "inherit" and has nothing here to inherit. */
        val ORIENTATIONS = ReaderOrientation.entries - ReaderOrientation.DEFAULT
    }
}

/**
 * Only the middle and the bottom left start bound: the middle so tapping the page still opens the
 * menu as it always has, the bottom left because the imported configuration puts day/night there.
 */
private fun defaultTapAction(cell: Int): NovelReaderAction = when (cell) {
    NovelTapGrid.CENTRE -> NovelReaderAction.OPTIONS_MENU
    NovelTapGrid.BOTTOM_LEFT -> NovelReaderAction.DAY_NIGHT_MODE
    else -> NovelReaderAction.NONE
}
