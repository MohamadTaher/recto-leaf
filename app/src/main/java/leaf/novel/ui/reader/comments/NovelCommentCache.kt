package leaf.novel.ui.reader.comments

/**
 * What the comments feature has fetched, kept for the life of the process rather than of a screen.
 *
 * Leaving a novel and coming back, or closing the reader and opening it again, used to fetch every
 * thread a second time, and with the same novel's comments now gathered from every source that has
 * it, that is one round of requests per source per visit. So the threads, the matches and the
 * chapter lists outlive the screen that asked for them.
 *
 * Memory only, on purpose. A comment thread goes stale, and a copy on disk would go on being served
 * long after the site had moved on; one that dies with the process never gets old enough to matter.
 * [NovelComments] still expires its threads, while a match or a chapter list is kept until evicted,
 * because neither changes while someone reads.
 *
 * Bounded by entry count, least recently used first. The entries are small — a thread is a few
 * pages of text — and a count is the one bound that needs no guess at their size.
 */
class NovelCommentCache(
    private val capacity: Int = CAPACITY,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private class Entry(val value: Any, val at: Long)

    private val entries = object : LinkedHashMap<Any, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Any, Entry>) = size > capacity
    }

    /** The value stored under [key], unless it is older than [maxAge] milliseconds. */
    @Synchronized
    fun <T : Any> get(key: Any, maxAge: Long = Long.MAX_VALUE): T? {
        val entry = entries[key] ?: return null
        if (clock() - entry.at > maxAge) {
            entries.remove(key)
            return null
        }
        @Suppress("UNCHECKED_CAST")
        return entry.value as T
    }

    /**
     * Stores [value], keeping the age of whatever it replaces.
     *
     * A thread is rewritten on every page, vote and fetched reply, and none of those is a fresh copy
     * of it: a thread that kept renewing itself would never expire however stale its first page was.
     */
    @Synchronized
    fun put(key: Any, value: Any) {
        entries[key] = Entry(value, entries[key]?.at ?: clock())
    }

    @Synchronized
    fun remove(key: Any) {
        entries.remove(key)
    }

    companion object {
        /** The process's own, shared by the reader and the novel screen. */
        val shared = NovelCommentCache()

        private const val CAPACITY = 64
    }
}
