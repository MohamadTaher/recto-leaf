package leaf.novel.ui.reader

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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

    private var utterances: List<String> = emptyList()

    private var engine: TextToSpeech? = null

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            state.update { it.copy(available = status == TextToSpeech.SUCCESS) }
        }.apply {
            setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        val index = utteranceId?.toIntOrNull() ?: return
                        state.update { it.copy(index = index) }
                    }

                    override fun onDone(utteranceId: String?) {
                        // The chapter is finished when its last utterance is. Speech deliberately
                        // does not roll into the next chapter: crossing a boundary unattended is
                        // how a listener loses their place, the same reason auto scroll stops.
                        // Qualified: inside `apply` on the engine, a bare stop() is its own.
                        if (utteranceId?.toIntOrNull() == utterances.lastIndex) this@NovelSpeaker.stop()
                    }

                    @Deprecated("Required by the base class; the newer overload delegates to it.")
                    override fun onError(utteranceId: String?) = this@NovelSpeaker.stop()
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
        val tts = engine ?: return
        if (text.isEmpty()) return

        utterances = text
        tts.setSpeechRate(rate / RATE_SCALE)
        tts.stop()
        text.forEachIndexed { index, utterance ->
            val mode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            tts.speak(utterance, mode, null, index.toString())
        }
        state.update { it.copy(speaking = true, index = 0) }
    }

    fun stop() {
        engine?.stop()
        state.update { it.copy(speaking = false) }
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
        val speaking: Boolean = false,
        val index: Int = 0,
    )

    private companion object {
        /** The rate preference counts tenths, where the engine wants a multiplier. */
        const val RATE_SCALE = 10f
    }
}
