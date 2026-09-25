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
 * [NovelComments] still expires its threads, and a match is kept until evicted, because it does not
 * change while someone reads. A chapter list does, by growing, so [NovelCommentMatcher] reads an old
 * one again when a chapter is missing from it.
 *
 * Bounded by entry count, least recently used first. The entries are small — a thread is a few
 * pages of text — and a count is the one bound that needs no guess at their size.
 */
class NovelCommentCache(
    private val capacity: Int = THREADS,
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
        /**
         * The process's threads, shared by the reader and the novel screen.
         *
         * Sized for the fan-out rather than for one source: the reader prefetches the chapters
         * either side of the open one, so a chapter turn holds three chapters' worth of every feed
         * of every source that has the novel. A dozen comment extensions is fifty-odd entries
         * before the novel screen has asked for anything.
         */
        val shared = NovelCommentCache(THREADS)

        /**
         * Which novel is which on another source.
         *
         * An `SManga` each, so they cost nothing to keep, and they are worth keeping far longer
         * than a thread: nothing about a match goes stale while someone is reading, and losing one
         * means searching every source again.
         */
        val matches = NovelCommentCache(MATCHES)

        /**
         * Another source's chapter list, by number.
         *
         * Thousands of chapters each and the most expensive thing here to fetch again, so they are
         * kept apart and kept few. Held with the threads they were evicted by, a long novel's table
         * of contents was refetched every time the fan-out grew.
         */
        val chapters = NovelCommentCache(CHAPTER_LISTS)

        private const val THREADS = 128

        private const val MATCHES = 256

        private const val CHAPTER_LISTS = 16
    }
}
