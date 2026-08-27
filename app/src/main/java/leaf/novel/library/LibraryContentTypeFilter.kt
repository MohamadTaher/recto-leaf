package leaf.novel.library

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.ui.library.LibraryItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import leaf.novel.data.isNovel

/**
 * Narrows the library to one content type, and keeps [NovelLibraryPreferences.hasAnyNovel] current.
 *
 * It hangs off `LibraryViewModel.getFavoritesFlow()` because that is the one place the *unfiltered*
 * favourites exist as a flow. Filtering there means `applyFilters`, the search DSL, `applyGrouping`
 * and `applySort` all run on the narrowed list, so category tabs, count badges, select-all and the
 * empty state stay consistent for free. See plans/03.
 *
 * `hasAnyNovel` is maintained here rather than added to `LibraryData` so the upstream seam stays a
 * single wrapped expression: the wrap is what hides the unfiltered list from the `combine` lambda
 * where `LibraryData` is built, so the flag has nowhere else to come from.
 */
@Inject
@SingleIn(AppScope::class)
class LibraryContentTypeFilter(
    private val preferences: NovelLibraryPreferences,
) {

    fun apply(favorites: Flow<List<LibraryItem>>): Flow<List<LibraryItem>> =
        combine(favorites, preferences.libraryContentType.changes()) { items, contentType ->
            val anyNovel = items.any { it.libraryManga.manga.isNovel() }
            if (preferences.hasAnyNovel.get() != anyNovel) {
                preferences.hasAnyNovel.set(anyNovel)
            }

            if (!anyNovel) {
                // Nothing to choose between, and the selector is hidden. Clear a stale choice so
                // importing a novel later does not drop the user into a pre-filtered view.
                if (contentType != LibraryContentType.ALL) {
                    preferences.libraryContentType.set(LibraryContentType.ALL)
                }
                return@combine items
            }

            when (contentType) {
                LibraryContentType.ALL -> items
                LibraryContentType.MANGA -> items.filterNot { it.libraryManga.manga.isNovel() }
                LibraryContentType.NOVELS -> items.filter { it.libraryManga.manga.isNovel() }
            }
        }
}
