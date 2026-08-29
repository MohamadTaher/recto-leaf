package leaf.novel.ui.reader

import android.content.Context
import android.content.Intent
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.runBlocking
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.GetManga

/**
 * Decides whether an incoming reader intent belongs to the text reader.
 *
 * `ReaderActivity.newIntent` has seven call sites and upstream adds more over time, so the fork
 * redirects once inside `ReaderActivity.onCreate` rather than patching each caller.
 */
@Inject
@SingleIn(AppScope::class)
class NovelReaderRouter(
    private val getManga: GetManga,
) {

    /**
     * The novel-reader intent for [intent], or null when it is a manga and the image reader should
     * carry on.
     *
     * This runs on the main thread before `super.onCreate`, so it is one indexed single-row read on
     * a bundled, mmap'd SQLite driver — sub-millisecond in practice. If it ever shows up in a trace,
     * the fix is to have the call sites that already hold a `Manga` pass a boolean extra and keep
     * this as the fallback.
     */
    fun novelIntentFor(context: Context, intent: Intent): Intent? {
        val mangaId = intent.extras?.getLong(EXTRA_MANGA, INVALID_ID) ?: return null
        if (mangaId == INVALID_ID) return null

        // Nothing here may throw. This runs inside `ReaderActivity.onCreate` for *every* chapter
        // opened, manga included, so an exception escaping would take the image reader down with it.
        // Falling through means at worst a novel opens in the image reader and reports that it
        // cannot load — recoverable, unlike a crash on the app's most-used screen.
        val manga = runCatching { runBlocking { getManga.await(mangaId) } }
            .onFailure { logcat(LogPriority.ERROR, it) { "Novel routing lookup failed for $mangaId" } }
            .getOrNull()
            ?: return null
        if (!manga.isNovel) return null

        val chapterId = intent.extras?.getLong(EXTRA_CHAPTER, INVALID_ID) ?: INVALID_ID
        return NovelReaderActivity.newIntent(
            context = context,
            mangaId = mangaId,
            chapterId = chapterId.takeIf { it != INVALID_ID },
        )
    }

    private companion object {
        const val EXTRA_MANGA = "manga"
        const val EXTRA_CHAPTER = "chapter"
        const val INVALID_ID = -1L
    }
}
