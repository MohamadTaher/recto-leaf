package leaf.novel.library

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
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
     * Persists across restarts rather than living in app state: this is a view mode, and a user
     * expects a view mode to still be set when they come back.
     */
    val libraryContentType: Preference<LibraryContentType> =
        preferenceStore.getEnum("leaf_library_content_type", LibraryContentType.ALL)

    /**
     * Cached answer to "does the library hold a novel?", written by [LibraryContentTypeFilter].
     *
     * It is derived state, but it has to be readable *before* the library flow's first emission:
     * the selector lives in the toolbar, which composes first, so without a cached value the
     * library would visibly jump down on every cold start as the row appeared.
     */
    val hasAnyNovel: Preference<Boolean> =
        preferenceStore.getBoolean(Preference.appStateKey("leaf_library_has_novel"), false)
}
