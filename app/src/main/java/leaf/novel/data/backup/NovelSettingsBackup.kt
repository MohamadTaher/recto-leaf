package leaf.novel.data.backup

import kotlinx.serialization.Serializable
import tachiyomi.core.common.preference.PreferenceStore

/**
 * The reader's settings as a file.
 *
 * Grouped by type rather than tagged one by one, because a preference store is typed and a restore
 * has to know which setter to call. Six maps of primitives serialize with no custom serializer, no
 * type tag to keep in step, and a file a person can read.
 */
@Serializable
data class NovelSettingsBackup(
    val booleans: Map<String, Boolean> = emptyMap(),
    val ints: Map<String, Int> = emptyMap(),
    val longs: Map<String, Long> = emptyMap(),
    val floats: Map<String, Float> = emptyMap(),
    val strings: Map<String, String> = emptyMap(),
    val stringSets: Map<String, List<String>> = emptyMap(),
)

/**
 * Moving the reader's settings in and out of a [NovelSettingsBackup].
 *
 * The preference store already walks and stores everything, so this is a filter and a type
 * dispatch rather than a second settings system — writing one of those is what rule 2 exists to
 * prevent.
 *
 * Free of Android types, so the filtering and the round trip are tested on the JVM.
 */
object NovelSettingsTransfer {

    /**
     * What marks a preference as the novel reader's.
     *
     * Every key the fork owns carries it by convention, so the filter needs no list to maintain
     * and cannot fall behind a stage that adds one.
     */
    const val PREFIX = "leaf_novel_"

    /**
     * Everything the reader owns, out of everything the app stores.
     *
     * The shared keys — reader theme, brightness, fullscreen, keep screen on — are deliberately
     * left behind. They belong to the app, Mihon's own backup already carries them, and a novel
     * settings file that could change how manga reads would be a trap.
     */
    fun capture(all: Map<String, *>): NovelSettingsBackup {
        val mine = all.filterKeys { it.startsWith(PREFIX) }
        return NovelSettingsBackup(
            booleans = mine.pick(),
            ints = mine.pick(),
            longs = mine.pick(),
            floats = mine.pick(),
            strings = mine.pick(),
            // Stored as a list because a set has no stable order in JSON and a diff of two exports
            // should not depend on one.
            stringSets = mine.entries
                .mapNotNull { (key, value) ->
                    @Suppress("UNCHECKED_CAST")
                    (value as? Set<String>)?.let { key to it.sorted() }
                }
                .toMap(),
        )
    }

    /**
     * Writes a backup back over the current settings.
     *
     * A key that no longer exists is skipped rather than failing the restore: the point of the file
     * is to survive the fork's own churn, and one that a later stage renamed should not take the
     * rest of it down. A key *missing* from the file is left alone rather than reset, so restoring
     * an older export does not silently undo settings that export never knew about.
     */
    fun apply(backup: NovelSettingsBackup, store: PreferenceStore) {
        backup.booleans.forEach { (key, value) -> store.getBoolean(key).set(value) }
        backup.ints.forEach { (key, value) -> store.getInt(key).set(value) }
        backup.longs.forEach { (key, value) -> store.getLong(key).set(value) }
        backup.floats.forEach { (key, value) -> store.getFloat(key).set(value) }
        backup.strings.forEach { (key, value) -> store.getString(key).set(value) }
        backup.stringSets.forEach { (key, value) -> store.getStringSet(key).set(value.toSet()) }
    }

    /** The entries of one primitive type, keyed as they were stored. */
    private inline fun <reified T> Map<String, *>.pick(): Map<String, T> =
        mapNotNull { (key, value) -> (value as? T)?.let { key to it } }.toMap()
}
