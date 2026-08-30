package leaf.novel.ui.reader

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicInteger

/**
 * The platform's own speech engine, reading a chapter out loud.
 *
 * Whatever engine the phone has, through [TextToSpeech] — no network voice, no bundled model and no
 * new dependency. A reader feature is not a reason for the fork to take on a speech vendor.
 *
 * The whole chapter is queued at once and the engine reports which utterance it has reached, which
 * is what the screen follows the page along with. Queueing rather than feeding one at a time is
 * what keeps the pauses between paragraphs the engine's own rather than a round trip's.
 *
 * Created once per reading session and shut down with it: [TextToSpeech] holds a service binding,
 * and one left open outlives the reader.
 */
class NovelSpeaker(context: Context) {

    private val state = MutableStateFlow(State())

    /** What is being said, and how far through. */
    val speech: StateFlow<State> = state.asStateFlow()

    @Volatile
    private var utterances: List<String> = emptyList()

    private var engine: TextToSpeech? = null

    /**
     * Which run of speech the engine's reports belong to.
     *
     * Flushing the queue makes the engine report every dropped utterance, asynchronously and after
     * the fact. Tagging each utterance with the run that queued it is what stops a report from a
     * run that has already been replaced arriving late and silencing the one now playing.
     */
    private val run = AtomicInteger()

    /** Queued while the engine was still binding, and spoken the moment it finishes. */
    private var pending: (() -> Unit)? = null

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            val ready = status == TextToSpeech.SUCCESS
            state.update { it.copy(available = ready, initialised = true) }
            // Binding is asynchronous and `speak` before it completes is dropped on the floor, so
            // the first request has to wait here rather than appear to work and say nothing.
            if (ready) pending?.invoke()
            pending = null
        }.apply {
            setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        val index = positionOf(utteranceId) ?: return
                        state.update { it.copy(index = index) }
                    }

                    override fun onDone(utteranceId: String?) = finishIfLast(utteranceId)

                    // One utterance the engine cannot pronounce is not a reason to abandon the
                    // chapter — the queue carries on to the next of its own accord. Only failing
                    // the last one ends the run, exactly as saying it would have.
                    @Deprecated("Required by the base class; the newer overload delegates to it.")
                    override fun onError(utteranceId: String?) = finishIfLast(utteranceId)

                    /**
                     * Speech deliberately does not roll into the next chapter: crossing a boundary
                     * unattended is how a listener loses their place, the same reason auto scroll
                     * stops. Qualified because inside `apply` a bare `stop()` is the engine's.
                     */
                    private fun finishIfLast(utteranceId: String?) {
                        val index = positionOf(utteranceId) ?: return
                        if (index == utterances.lastIndex) this@NovelSpeaker.stop()
                    }
                },
            )
        }
    }

    /**
     * Starts saying [text] from its first entry, replacing anything already queued.
     *
     * [rate] is the preference's tenths, so 10 is the engine's own normal pace.
     */
    fun start(text: List<String>, rate: Int) {
        if (text.isEmpty()) return
        if (!state.value.initialised) {
            pending = { start(text, rate) }
            return
        }

        val tts = engine.takeIf { state.value.available } ?: return
        // Claimed before anything is queued, so that the reports the flush below provokes are
        // already stale by the time they arrive.
        val current = run.incrementAndGet()
        utterances = text
        tts.setSpeechRate(rate / RATE_SCALE)
        var accepted = true
        text.forEachIndexed { index, utterance ->
            // The first flushes whatever was playing; the rest queue behind it.
            val mode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            if (tts.speak(utterance, mode, null, "$current$ID_SEPARATOR$index") != TextToSpeech.SUCCESS) {
                accepted = false
            }
        }
        // Only once the engine has taken it. Saying it is speaking when nothing was queued is the
        // failure this whole path exists to avoid.
        if (accepted) state.update { it.copy(speaking = true, index = 0) }
    }

    fun stop() {
        // Stopping is a flush too, so it claims a run of its own for the same reason.
        run.incrementAndGet()
        pending = null
        engine?.stop()
        state.update { it.copy(speaking = false) }
    }

    /** Where an utterance sits, or null if its run has since been replaced. */
    private fun positionOf(utteranceId: String?): Int? {
        val parts = utteranceId?.split(ID_SEPARATOR)?.takeIf { it.size == 2 } ?: return null
        if (parts[0].toIntOrNull() != run.get()) return null
        return parts[1].toIntOrNull()
    }

    fun shutdown() {
        engine?.stop()
        engine?.shutdown()
        engine = null
    }

    /**
     * Whether the phone has an engine at all.
     *
     * Not every device does, and an action that silently says nothing is the thing the reader's
     * bindings are careful to avoid — so the menu item reports this rather than appearing to work.
     */
    data class State(
        val available: Boolean = false,
        /** Whether the engine has finished binding, whether or not it found a voice. */
        val initialised: Boolean = false,
        val speaking: Boolean = false,
        val index: Int = 0,
    )

    private companion object {
        /** The rate preference counts tenths, where the engine wants a multiplier. */
        const val RATE_SCALE = 10f

        /** Splits the run from the position in an utterance id. Neither part contains it. */
        const val ID_SEPARATOR = ":"
    }
}
