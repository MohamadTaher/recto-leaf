package leaf.novel.ui.reader.setting

import androidx.annotation.ColorInt
import tachiyomi.core.common.preference.Preference

/**
 * One slot of the reader's own colours: a name and the pair of colours it stands for.
 *
 * Three preferences rather than one serialized value, so there is no format to escape a name
 * against and no migration to write when a field is added. [NovelReaderPreferences] holds a fixed
 * number of these and [NovelReaderTheme] has an entry per slot, so a custom theme is selectable
 * everywhere a preset is.
 */
class NovelCustomTheme(
    val name: Preference<String>,
    val background: Preference<Int>,
    val foreground: Preference<Int>,
) {

    /**
     * Whether the slot has ever been given colours.
     *
     * A fully transparent background is the sentinel: the editor only ever writes opaque colours,
     * so no choice a reader can make collides with it, and it needs no fourth preference to say so.
     */
    val isSet: Boolean get() = background.get() != UNSET

    /** The colours to draw with, or null while the slot is still empty. */
    fun colors(): NovelReaderColors? = if (isSet) {
        NovelReaderColors(background = background.get(), foreground = foreground.get())
    } else {
        null
    }

    /**
     * Fills an empty slot from what is currently on screen.
     *
     * Selecting a slot that has never been set has to leave the page readable — starting every
     * custom theme at transparent-on-transparent would mean the first drag of a slider is the only
     * thing between the reader and a blank page.
     */
    fun seedFrom(colors: NovelReaderColors) {
        if (isSet) return
        background.set(colors.background)
        foreground.set(colors.foreground)
    }

    companion object {
        @ColorInt
        const val UNSET = 0
    }
}
