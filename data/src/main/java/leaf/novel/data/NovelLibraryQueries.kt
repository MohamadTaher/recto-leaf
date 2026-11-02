package leaf.novel.data

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import tachiyomi.data.Database
import tachiyomi.data.subscribeToOne

/** The fork's queries over the library, kept in `:data` since SQLDelight's types don't leave it. */
@Inject
@SingleIn(AppScope::class)
class NovelLibraryQueries(private val database: Database) {

    /** Whether the library holds a novel at all. */
    fun hasNovelInLibrary(): Flow<Boolean> {
        return database.mangaQueries.hasNovelInLibrary().subscribeToOne().distinctUntilChanged()
    }
}
