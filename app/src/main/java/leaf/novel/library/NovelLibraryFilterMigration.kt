package leaf.novel.library

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import tachiyomi.core.common.preference.PreferenceStore

/**
 * Drops the preference behind the library's old content-type selector.
 *
 * The selector was a three-way view mode of its own — all, manga, novels — kept under
 * `leaf_library_content_type`. It is now [NovelLibraryPreferences.filterNovels], one of Mihon's own
 * library filters under a different key and a different type, so nothing reads the old one again.
 *
 * Worth a migration rather than leaving it: a preference that nothing reads is still copied into
 * every backup and restored onto every device, so an abandoned key does not go away on its own —
 * it spreads. Mihon deletes its own the same way, in `ContentWarningMigration`.
 *
 * Anyone who had the selector on "Manga" or "Novels" lands on an unfiltered library, because
 * [NovelLibraryPreferences.filterNovels] starts at `TriState.DISABLED`. That is the right place to
 * land: the two settings do not mean the same thing, and showing someone their whole library is
 * recoverable in a way that silently hiding half of it is not.
 *
 * It runs with the first release after version code 30, not before. The fork shares upstream's
 * version code, and Mihon runs no migration at all, [Migration.ALWAYS] included, until that changes.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class NovelLibraryFilterMigration(
    private val preferenceStore: PreferenceStore,
) : Migration {

    override val version: Float = 31f

    override suspend fun invoke(migrationContext: MigrationContext): Boolean {
        preferenceStore.getString(OLD_CONTENT_TYPE_KEY).delete()
        return true
    }

    private companion object {
        const val OLD_CONTENT_TYPE_KEY = "leaf_library_content_type"
    }
}
