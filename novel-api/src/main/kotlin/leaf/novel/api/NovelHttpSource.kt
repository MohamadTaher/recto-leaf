package leaf.novel.api

import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import kotlin.time.Duration.Companion.seconds

/**
 * Base class for a web-novel source, holding the two things every one of them gets right the same
 * way: refusing to serve pages, and being polite to the site.
 *
 * A source that needs more than one rate-limited host can still add to this rather than replace it:
 * its own `client` override starts from `super.client`, which is already limited for [baseUrl].
 */
abstract class NovelHttpSource : HttpSource(), NovelSource {

    /** Requests per second allowed against [baseUrl]. */
    protected open val requestsPerSecond: Int = 3

    /**
     * Lazy on purpose, and this is load-bearing: a base-class property initialiser runs *before* the
     * subclass sets [baseUrl], so building the client eagerly here would read an uninitialised value
     * and fail at construction. Deferring it means [baseUrl] is set by the time it is read.
     */
    override val client: OkHttpClient by lazy {
        super.client.newBuilder()
            .rateLimitHost(baseUrl.toHttpUrl(), permits = requestsPerSecond, period = 1.seconds)
            .build()
    }

    /** Text sources never serve pages, matching `LocalSource`'s precedent. */
    final override suspend fun getPageList(chapter: SChapter): List<Page> =
        throw UnsupportedOperationException("Novel sources serve chapter content, not pages")
}
