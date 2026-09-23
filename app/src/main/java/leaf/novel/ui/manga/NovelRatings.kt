package leaf.novel.ui.manga

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import leaf.novel.api.NovelRating
import leaf.novel.api.NovelSource
import leaf.novel.api.novelRating
import leaf.novel.ui.reader.comments.COMMENT_TIMEOUT
import leaf.novel.ui.reader.comments.NovelCommentCache
import leaf.novel.ui.reader.comments.NovelCommentMatcher
import leaf.novel.ui.reader.comments.withCommentTimeout
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * A novel's rating on every site that has it, and the one rating that stands for them all.
 *
 * The other sites are found the way the comments find them, through [NovelCommentMatcher], so a
 * novel searched for by one is never searched for again by the other. Each site's rating is that
 * site's own figure, carried in the novel's details as a [NovelRating]; nothing here averages a
 * review.
 */
class NovelRatings(
    private val scope: CoroutineScope,
    private val matcher: NovelCommentMatcher,
    private val cache: NovelCommentCache = RATINGS,
    private val timeout: Duration = COMMENT_TIMEOUT,
) {

    /** One site's rating. [own] is the source the novel is read from. */
    data class Site(val order: Int, val name: String, val rating: NovelRating, val own: Boolean = false)

    data class State(
        /** The novel's own source, when its saved details had no rating and the site was asked. */
        val own: Site? = null,
        /** Every other site, in the order the sources are listed. */
        val others: List<Site> = emptyList(),
        val searching: Boolean = false,
    )

    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState.asStateFlow()

    private var bound = false

    /**
     * Starts asking. [saved] is the rating already stored with the novel; only when there is none is
     * its own site asked again, which covers a novel added before its extension knew ratings.
     */
    fun bind(source: Source?, novel: SManga, saved: NovelRating?) {
        if (bound) return
        bound = true
        mutableState.update { it.copy(searching = true) }
        scope.launch {
            try {
                // Everything asked is a child of this, so the search counts as over only once the
                // last site has answered, not once the last site has been found.
                coroutineScope {
                    if (saved == null && source is HttpSource) {
                        launch {
                            val rating = rating(source, novel) ?: return@launch
                            mutableState.update { it.copy(own = Site(-1, source.name, rating, own = true)) }
                        }
                    }
                    matcher.find(source, novel) { it as? NovelSource }.collect { match ->
                        // Asked in its own coroutine so one slow site holds up nobody else's rating.
                        launch {
                            val rating = rating(match.source, match.novel) ?: return@launch
                            val site = Site(match.order, match.source.name, rating)
                            mutableState.update { state ->
                                state.copy(others = (state.others + site).sortedBy { it.order })
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logcat(LogPriority.WARN, e) { "Could not look for ${novel.title} on the other sources" }
            } finally {
                mutableState.update { it.copy(searching = false) }
            }
        }
    }

    /**
     * [novel]'s rating on [source]: from the search result when the site put it there, and from the
     * novel's details otherwise. Remembered, including a site that has none, for [MAX_AGE].
     */
    private suspend fun rating(source: Source, novel: SManga): NovelRating? {
        // Only a counted one: without a count it would stand in for a figure the details might weigh.
        novel.novelRating?.takeIf { it.count != null }?.let { return it }
        val key = Key(source.id, novel.url)
        cache.get<Found>(key, MAX_AGE)?.let { return it.rating }
        val rating = try {
            withCommentTimeout(timeout) {
                withIOContext {
                    source.getMangaUpdate(novel, emptyList(), fetchDetails = true, fetchChapters = false)
                }
            }.manga.novelRating
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            // Any throwable, not only exceptions: an extension built against a newer app fails with a
            // linkage error, and one that escaped here would cancel every other site's answer too.
            // Not remembered, so the next visit asks again.
            logcat(LogPriority.WARN, e) { "Could not read the rating of ${novel.title} on ${source.name}" }
            return null
        }
        cache.put(key, Found(rating))
        return rating
    }

    private data class Key(val source: Long, val url: String)

    /** One site's answer, including that it has no rating. */
    private class Found(val rating: NovelRating?)

    companion object {
        /** A rating moves slowly; a few hours of one is still the site's figure. */
        private val MAX_AGE = 6.hours.inWholeMilliseconds

        private val RATINGS = NovelCommentCache(256)
    }
}

/**
 * The rating that stands for every site in [sites]: each one's figure on five stars, weighted by how
 * many people gave it.
 *
 * Which is the same as the average of every single rating on every site, without needing any of
 * them. A site that does not say how many rated it has no weight to give, and is left out rather
 * than guessed at. Null when no site says.
 */
fun acrossSites(sites: List<NovelRating>): NovelRating? {
    val weighed = sites.filter { it.valid && (it.count ?: 0) > 0 }
    if (weighed.isEmpty()) return null
    val total = weighed.sumOf { it.count!!.toLong() }
    val value = weighed.sumOf { it.outOfFive() * it.count!! } / total
    return NovelRating(value, 5.0, total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
}

/** The rating on five stars, unrounded, so weighing several loses nothing before the end. */
fun NovelRating.outOfFive(): Double = value / maximum * 5
