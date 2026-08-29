package leaf.novel.ui.reader.setting

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/**
 * The reader's only new preference.
 *
 * Theme, brightness, colour filter, grayscale, inverted colours, keep-screen-on and fullscreen are
 * all read from the existing [eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences], so there is
 * no second settings system and no duplicated keys.
 */
@Inject
@SingleIn(AppScope::class)
class NovelReaderPreferences(
    preferenceStore: PreferenceStore,
) {

    val fontSize: Preference<Int> = preferenceStore.getInt("leaf_novel_font_size", DEFAULT_FONT_SIZE)

    companion object {
        const val DEFAULT_FONT_SIZE = 18
        const val MIN_FONT_SIZE = 10
        const val MAX_FONT_SIZE = 32
    }
}
