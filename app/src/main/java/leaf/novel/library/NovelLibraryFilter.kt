package leaf.novel.library

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.ui.library.LibraryItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import tachiyomi.core.common.preference.TriState
import tachiyomi.data.Database
import tachiyomi.data.subscribeToOne

/**
 * Mihon's library filters have no hook to extend, so the fork's "Novels" filter is applied here.
 *
 * It hangs off `LibraryViewModel.getFavoritesFlow()` because that is the one place the *unfiltered*
 * favourites exist as a flow. Filtering there means `applyFilters`, the search DSL, `applyGrouping`
 * and `applySort` all run on the narrowed list, so category tabs, count badges, select-all and the
 * empty state stay consistent for free.
 *
 * Since `is_novel` is a real column, "does the library hold a novel?" is a SQL question rather
 * than a scan of the emitted list, so [apply] is a pure transform and the preference writes it used
 * to perform as a side effect now live in [keepPreferencesCurrent].
 */
@SingleIn(AppScope::class)
class NovelLibraryFilter internal constructor(
    private val preferences: NovelLibraryPreferences,
    /** Whether the library holds a novel at all; everything here is gated on it. */
    private val hasAnyNovel: Flow<Boolean>,
) {

    @Inject
    constructor(preferences: NovelLibraryPreferences, database: Database) : this(
        preferences = preferences,
        hasAnyNovel = database.mangasQueries.hasNovelInLibrary().subscribeToOne().distinctUntilChanged(),
    )

    /** The filter's state, for upstream's "any filter active" indicator. */
    val filter: Flow<TriState> = combine(
        preferences.filterNovels.changes(),
        hasAnyNovel,
    ) { filter, anyNovel ->
        if (anyNovel) filter else TriState.DISABLED
    }
        .distinctUntilChanged()

    /**
     * Maintains the cold-start cache the filter sheet reads before the library flow has emitted,
     * and clears a stale choice so importing a novel later does not drop the user into a filtered
     * view.
     *
     * Kept out of [apply] so that stays a pure transform: writing preferences from inside a
     * `combine` lambda ran the write on every emission and made the flow's output depend on it.
     */
    fun keepPreferencesCurrent(scope: CoroutineScope) {
        hasAnyNovel
            .onEach { anyNovel ->
                if (preferences.hasAnyNovel.get() != anyNovel) {
                    preferences.hasAnyNovel.set(anyNovel)
                }
                if (!anyNovel && preferences.filterNovels.get() != TriState.DISABLED) {
                    preferences.filterNovels.set(TriState.DISABLED)
                }
            }
            .launchIn(scope)
    }

    fun apply(favorites: Flow<List<LibraryItem>>): Flow<List<LibraryItem>> =
        combine(favorites, filter) { items, filter ->
            when (filter) {
                TriState.DISABLED -> items
                TriState.ENABLED_IS -> items.filter { it.libraryManga.manga.isNovel }
                TriState.ENABLED_NOT -> items.filterNot { it.libraryManga.manga.isNovel }
            }
        }
}
