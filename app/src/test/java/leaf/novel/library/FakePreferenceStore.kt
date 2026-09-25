package leaf.novel.library

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import java.util.concurrent.ConcurrentHashMap

/**
 * A preference store whose [Preference.changes] actually emits.
 *
 * Mihon's own `InMemoryPreferenceStore` cannot be used for anything that *observes* a preference:
 * its `changes()` is `flow { data }`, which evaluates the field, emits nothing and completes. A
 * `combine` over one of those never produces a value, so a test hangs rather than fails — which is
 * why every existing fork test that touches preferences only calls `get` and `set`.
 *
 * Fixing that in place would be an edit to an upstream file in a shared module for the sake of a
 * test double, so the fork keeps its own. Backed by a `MutableStateFlow` per key, so a reader sees
 * the current value first and every write after it, which is what the real Android store does.
 */
internal class FakePreferenceStore : PreferenceStore {

    private val flows = ConcurrentHashMap<String, MutableStateFlow<Any?>>()

    override fun getString(key: String, defaultValue: String) = preference(key, defaultValue)

    override fun getLong(key: String, defaultValue: Long) = preference(key, defaultValue)

    override fun getInt(key: String, defaultValue: Int) = preference(key, defaultValue)

    override fun getFloat(key: String, defaultValue: Float) = preference(key, defaultValue)

    override fun getBoolean(key: String, defaultValue: Boolean) = preference(key, defaultValue)

    override fun getStringSet(key: String, defaultValue: Set<String>) = preference(key, defaultValue)

    /** Stored as the object itself; round-tripping through the string would only test the serializer. */
    override fun <T> getObjectFromString(
        key: String,
        defaultValue: T,
        serializer: (T) -> String,
        deserializer: (String) -> T,
    ) = preference(key, defaultValue)

    override fun <T> getObjectFromInt(
        key: String,
        defaultValue: T,
        serializer: (T) -> Int,
        deserializer: (Int) -> T,
    ) = preference(key, defaultValue)

    override fun <T> getObjectSetFromStringSet(
        key: String,
        defaultValue: Set<T>,
        serializer: (T) -> String,
        deserializer: (String) -> T?,
    ) = preference(key, defaultValue)

    override fun getAll(): Map<String, *> = flows.mapValues { it.value.value }

    private fun <T> preference(key: String, defaultValue: T): Preference<T> =
        FakePreference(key, defaultValue, flows.getOrPut(key) { MutableStateFlow(null) })

    private class FakePreference<T>(
        private val key: String,
        private val defaultValue: T,
        private val flow: MutableStateFlow<Any?>,
    ) : Preference<T> {

        override fun key(): String = key

        @Suppress("UNCHECKED_CAST")
        override fun get(): T = flow.value as? T ?: defaultValue

        override fun set(value: T) {
            flow.value = value
        }

        override fun isSet(): Boolean = flow.value != null

        override fun delete() {
            flow.value = null
        }

        override fun defaultValue(): T = defaultValue

        @Suppress("UNCHECKED_CAST")
        override fun changes(): Flow<T> = flow.map { it as? T ?: defaultValue }

        override fun stateIn(scope: CoroutineScope): StateFlow<T> =
            changes().stateIn(scope, SharingStarted.Eagerly, get())
    }
}
