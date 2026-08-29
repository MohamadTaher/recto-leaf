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
import tachiyomi.data.Database
import tachiyomi.data.subscribeToOne

/**
 * Narrows the library to one content type.
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
@Inject
@SingleIn(AppScope::class)
class LibraryContentTypeFilter(
    private val preferences: NovelLibraryPreferences,
    database: Database,
) {

    private val hasAnyNovel: Flow<Boolean> = database.mangasQueries
        .hasNovelInLibrary()
        .subscribeToOne()
        .distinctUntilChanged()

    /**
     * Maintains the cold-start cache the toolbar reads before the library flow has emitted, and
     * clears a stale choice so importing a novel later does not drop the user into a filtered view.
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
                if (!anyNovel && preferences.libraryContentType.get() != LibraryContentType.ALL) {
                    preferences.libraryContentType.set(LibraryContentType.ALL)
                }
            }
            .launchIn(scope)
    }

    fun apply(favorites: Flow<List<LibraryItem>>): Flow<List<LibraryItem>> =
        combine(
            favorites,
            preferences.libraryContentType.changes(),
            hasAnyNovel,
        ) { items, contentType, anyNovel ->
            // With nothing to choose between, the selector is hidden; ignore any stale choice here
            // rather than waiting for keepPreferencesCurrent to reset it, so the list never blinks.
            if (!anyNovel) return@combine items

            when (contentType) {
                LibraryContentType.ALL -> items
                LibraryContentType.MANGA -> items.filterNot { it.libraryManga.manga.isNovel }
                LibraryContentType.NOVELS -> items.filter { it.libraryManga.manga.isNovel }
            }
        }
}
