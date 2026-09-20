package leaf.novel.ui.reader.comments

import eu.kanade.domain.manga.model.toSManga
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
     * [novel] on every other source that serves [scope], searched in parallel.
     *
     * A source that fails to answer has no match this time rather than failing the rest; its
     * failure is not remembered, so the next visit asks it again.
     */
    suspend fun find(
        primary: Source,
        novel: SManga,
        scope: NovelCommentScope,
    ): List<Pair<NovelCommentSource, SManga>> = coroutineScope {
        sources()
            .filterIsInstance<NovelCommentSource>()
            .filter { it.id != primary.id && it.serves(scope) }
            .map { source -> async { match(source, novel.title)?.let { source to it } } }
            .awaitAll()
            .filterNotNull()
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

/** Whether this source can serve [scope] at all, through its own capabilities or any of its feeds. */
private fun NovelCommentSource.serves(scope: NovelCommentScope): Boolean =
    scope in commentCapabilities.scopes ||
        (this as? NovelCommentFeedSource)?.commentFeeds.orEmpty().any { scope in it.capabilities.scopes }
