package leaf.novel.ui.reader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration

/**
 * Fires once after a set duration, on a scope of the caller's choosing rather than one of its own.
 *
 * That is what makes it testable — a JVM test drives it with a `TestScope`'s virtual time — and
 * what lets [NovelSpeechSession] give it a scope that outlives any one reader, the same problem
 * [NovelSpeechQueue] solves for the rest of what a reader leaves behind.
 */
class NovelSpeechTimer(private val scope: CoroutineScope) {
    private var job: Job? = null

    /** Arms a fresh timer, replacing any pending one. A non-positive duration arms nothing. */
    fun arm(after: Duration, onFire: () -> Unit) {
        cancel()
        if (after <= Duration.ZERO) return
        job = scope.launch {
            delay(after)
            onFire()
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }
}
