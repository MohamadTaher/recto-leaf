package leaf.novel.ui.reader.setting

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

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

    companion object {
        const val DEFAULT_FONT_SIZE = 18
        const val MIN_FONT_SIZE = 10
        const val MAX_FONT_SIZE = 32
    }
}
