package leaf.novel.ui.reader.comments

import eu.kanade.domain.manga.model.toSManga
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import leaf.novel.api.NovelCommentFeedSource
import leaf.novel.api.NovelCommentScope
import leaf.novel.api.NovelCommentSource
import logcat.LogPriority
import mihon.feature.migration.list.search.SmartSourceSearchEngine
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.service.ChapterRecognition
import tachiyomi.domain.source.service.SourceManager
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

/**
 * The same novel on the other installed comment sources, so its comments can be read together.
 *
 * Found the way migration finds a novel's new home — [SmartSourceSearchEngine] searching each source
 * by title — and then held to a much stricter standard. Migration shows its guess to someone who
 * confirms it; this merges comments without asking, and another novel's discussion in the sheet is
 * worse than none. So a result only counts when its title is the same once case and punctuation are
 * set aside, which also covers the engine accepting a search's only result whatever it is called.
 *
 * A chapter is matched by number, as migration carries read progress across, using the same
 * [ChapterRecognition] the library applies to its own chapters.
 */
class NovelCommentMatcher(
    /** Small, and kept for far longer than a thread; see [NovelCommentCache.matches]. */
    private val matches: NovelCommentCache = NovelCommentCache.matches,
    /** Large and few, and kept away from everything else; see [NovelCommentCache.chapters]. */
    private val lists: NovelCommentCache = NovelCommentCache.chapters,
    /** What one call to a source is given before it counts as no match; see `withCommentTimeout`. */
    private val timeout: Duration = COMMENT_TIMEOUT,
    /** Every source a match may come from. */
    private val sources: suspend () -> List<Source>,
) {

    private val search = SmartSourceSearchEngine(null)

    /** One lock per chapter list, so the reader's prefetch cannot fetch the same long list three times. */
    private val locks = ConcurrentHashMap<ChaptersKey, Mutex>()

    /**
     * [novel] on every other source that serves [scope], searched in parallel and **emitted as each
     * one answers** rather than gathered up and handed over at the end.
     *
     * The difference is the whole point. These searches are as slow as the slowest site, and a site
     * that paces itself to one request every few seconds is slow by design — so waiting for all of
     * them meant a source that had answered in half a second sat unread until the slowest finished.
     * Emitting one at a time lets the sheet show each site's comments the moment it has them.
     *
     * A source that fails to answer has no match this time rather than failing the rest; its
     * failure is not remembered, so the next visit asks it again. Each search carries its own
     * timeout, so one that never answers holds up nothing but itself.
     *
     * Each match carries the place its source held in the list, because arriving first is not a
     * reason to be listed first: which site answers soonest is a matter of how fast its server is
     * that second, and a sheet whose sources reshuffle between openings would be the result.
     */
    fun find(
        primary: Source,
        novel: SManga,
        scope: NovelCommentScope,
    ): Flow<NovelCommentMatch> = channelFlow {
        sources()
            .filterIsInstance<NovelCommentSource>()
            .filter { it.id != primary.id && it.serves(scope) }
            .forEachIndexed { order, source ->
                launch { match(source, novel.title)?.let { send(NovelCommentMatch(order, source, it)) } }
            }
    }

    /** [source]'s chapter numbered [number], or null when it has none or the number is unknown. */
    suspend fun chapter(source: Source, novel: SManga, number: Double): SChapter? {
        if (number < 0) return null
        val key = ChaptersKey(source.id, novel.url)
        val chapters = locks.getOrPut(key) { Mutex() }.withLock {
            lists.get<Map<Double, SChapter>>(key) ?: fetchChapters(source, novel).also { lists.put(key, it) }
        }
        return chapters[number]
    }

    private suspend fun fetchChapters(source: Source, novel: SManga): Map<Double, SChapter> =
        withCommentTimeout(timeout) {
            source.getMangaUpdate(novel, emptyList(), fetchDetails = false, fetchChapters = true)
        }
            .chapters
            .fold(mutableMapOf<Double, SChapter>()) { byNumber, chapter ->
                val parsed = ChapterRecognition.parseChapterNumber(
                    novel.title,
                    chapter.name,
                    chapter.chapter_number.toDouble(),
                )
                // The first of a repeated number wins, which is the site's own newest copy.
                byNumber.apply { putIfAbsent(parsed, chapter) }
            }

    private suspend fun match(source: NovelCommentSource, title: String): SManga? {
        val key = MatchKey(source.id, normalize(title))
        matches.get<Match>(key)?.let { return it.novel }
        val found = try {
            withCommentTimeout(timeout) { search.regularSearch(source, title) }
                ?.takeIf { sameTitle(it.title, title) }
                ?.toSManga()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logcat(LogPriority.WARN, e) { "Could not search ${source.name} for $title" }
            return null
        }
        matches.put(key, Match(found))
        return found
    }

    /** A remembered search, including one that found nothing: that answer is worth keeping too. */
    private class Match(val novel: SManga?)

    private data class MatchKey(val source: Long, val title: String)

    private data class ChaptersKey(val source: Long, val url: String)

    companion object {

        /** The sources global search would use: installed, enabled, and in an enabled language. */
        fun installed(sourceManager: SourceManager, preferences: SourcePreferences) = NovelCommentMatcher {
            val languages = preferences.enabledLanguages.get()
            val disabled = preferences.disabledSources.get()
            sourceManager.getOnlineSources().filter { it.lang in languages && it.id.toString() !in disabled }
        }

        fun sameTitle(a: String, b: String): Boolean = normalize(a).let { it.isNotEmpty() && it == normalize(b) }

        private fun normalize(title: String) = title.lowercase().replace(NOT_WORD, " ").trim()

        private val NOT_WORD = Regex("""[^\p{L}\p{N}]+""")
    }
}

/**
 * One novel found on one other source, and where that source stood in the list it was found from.
 *
 * [order] is the sheet's, not the search's: matches arrive in whatever order the sites answer, and
 * the sheet puts them back into the order the sources are listed in so it reads the same each time.
 */
data class NovelCommentMatch(val order: Int, val source: NovelCommentSource, val novel: SManga)

/** Whether this source can serve [scope] at all, through its own capabilities or any of its feeds. */
private fun NovelCommentSource.serves(scope: NovelCommentScope): Boolean =
    scope in commentCapabilities.scopes ||
        (this as? NovelCommentFeedSource)?.commentFeeds.orEmpty().any { scope in it.capabilities.scopes }
