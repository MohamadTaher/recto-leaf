package leaf.novel.library

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.preference.getEnum

/**
 * Fork-owned library preferences, kept out of upstream's [tachiyomi.domain.library.service.LibraryPreferences]
 * so that file is never touched. Keys are prefixed `leaf_` so they are greppable and cannot collide.
 */
@Inject
@SingleIn(AppScope::class)
class NovelLibraryPreferences(
    preferenceStore: PreferenceStore,
) {

    /**
     * Novels as one of Mihon's own library filters: ignored, only novels, or everything but novels.
     *
     * A [TriState] rather than a three-way view mode because that is exactly what the filter sheet
     * already renders, and because it makes the toolbar's "filters active" dot work for free.
     */
    val filterNovels: Preference<TriState> =
        preferenceStore.getEnum("leaf_library_filter_novels", TriState.DISABLED)

    /**
     * Cached answer to "does the library hold a novel?", written by [NovelLibraryFilter].
     *
     * It is derived state, but the filter sheet can be opened before the library flow has emitted,
     * so without a cached value the Novels row would pop in after the sheet was already on screen.
     */
    val hasAnyNovel: Preference<Boolean> =
        preferenceStore.getBoolean(Preference.appStateKey("leaf_library_has_novel"), false)
}
