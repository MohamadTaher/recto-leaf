package leaf.novel.ui.reader

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicInteger

/** Android's platform speech engine, with the queue controls the novel reader exposes. */
class NovelSpeaker(context: Context) {

    private val state = MutableStateFlow(State())

    /** What is being said, and how far through. */
    val speech: StateFlow<State> = state.asStateFlow()

    @Volatile
    private var utterances: List<String> = emptyList()

    private var configuration = Configuration()
    private var engine: TextToSpeech? = null
    private val run = AtomicInteger()
    private var pending: (() -> Unit)? = null

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var hasAudioFocus = false
    private var resumeAfterFocusGain = false

    private val audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .setOnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    if (resumeAfterFocusGain) {
                        resumeAfterFocusGain = false
                        resume()
                    }
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
                -> {
                    resumeAfterFocusGain = state.value.speaking && !state.value.paused
                    if (resumeAfterFocusGain) pause()
                }
                AudioManager.AUDIOFOCUS_LOSS -> stop()
            }
        }
        .build()

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            val ready = status == TextToSpeech.SUCCESS
            state.update { it.copy(available = ready, initialised = true) }
            // Binding is asynchronous; the first request waits rather than appearing to work and
            // saying nothing.
            if (ready) pending?.invoke()
            pending = null
        }.apply {
            setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        val index = positionOf(utteranceId) ?: return
                        state.update { it.copy(index = index, speaking = true, paused = false) }
                    }

                    override fun onDone(utteranceId: String?) = finishIfLast(utteranceId)

                    // A failed unit does not abandon the rest of the queue. Only the final unit
                    // ends the run, exactly as saying it would have.
                    @Deprecated("Required by the base class; the newer overload delegates to it.")
                    override fun onError(utteranceId: String?) = finishIfLast(utteranceId)

                    private fun finishIfLast(utteranceId: String?) {
                        val index = positionOf(utteranceId) ?: return
                        if (index == utterances.lastIndex) this@NovelSpeaker.stop()
                    }
                },
            )
        }
    }

    /** Starts [text] at [fromIndex], replacing anything already queued. */
    fun start(
        text: List<String>,
        fromIndex: Int,
        rate: Int,
        pitch: Int,
        intervalMs: Int,
        mixAudio: Boolean,
    ) {
        if (text.isEmpty()) return
        if (!state.value.initialised) {
            pending = { start(text, fromIndex, rate, pitch, intervalMs, mixAudio) }
            return
        }
        if (!state.value.available) return

        utterances = text
        configuration = Configuration(rate, pitch, intervalMs, mixAudio)
        queueFrom(fromIndex)
    }

    /** Pauses at unit granularity; resume repeats the interrupted unit. */
    fun pause() {
        if (!state.value.speaking || state.value.paused) return
        run.incrementAndGet()
        engine?.stop()
        state.update { it.copy(paused = true) }
    }

    fun resume() {
        if (!state.value.speaking || !state.value.paused) return
        queueFrom(state.value.index)
    }

    /** Moves by spoken units and preserves whether playback was paused. */
    fun seekBy(units: Int) {
        if (!state.value.speaking || utterances.isEmpty()) return
        val target = (state.value.index + units).coerceIn(0, utterances.lastIndex)
        if (state.value.paused) {
            state.update { it.copy(index = target) }
        } else {
            queueFrom(target)
        }
    }

    /** Applies changed voice parameters from the current unit. */
    fun update(rate: Int, pitch: Int, intervalMs: Int, mixAudio: Boolean) {
        configuration = Configuration(rate, pitch, intervalMs, mixAudio)
        if (state.value.speaking && !state.value.paused) {
            queueFrom(state.value.index)
        } else if (mixAudio) {
            abandonAudioFocus()
        }
    }

    fun stop() {
        run.incrementAndGet()
        pending = null
        resumeAfterFocusGain = false
        engine?.stop()
        abandonAudioFocus()
        state.update { it.copy(speaking = false, paused = false) }
    }

    private fun queueFrom(requestedIndex: Int) {
        val tts = engine.takeIf { state.value.available } ?: return
        val fromIndex = requestedIndex.coerceIn(0, utterances.lastIndex)
        val current = run.incrementAndGet()

        tts.stop()
        applyAudioFocus()
        tts.setSpeechRate(configuration.rate / RATE_SCALE)
        tts.setPitch(configuration.pitch / RATE_SCALE)

        var accepted = false
        utterances.drop(fromIndex).forEachIndexed { offset, utterance ->
            val index = fromIndex + offset
            val mode = if (offset == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            if (tts.speak(utterance, mode, null, "$current$ID_SEPARATOR$index") == TextToSpeech.SUCCESS) {
                accepted = true
            }
            if (configuration.intervalMs > 0 && index < utterances.lastIndex) {
                tts.playSilentUtterance(
                    configuration.intervalMs.toLong(),
                    TextToSpeech.QUEUE_ADD,
                    "$current$ID_SEPARATOR$SILENCE$index",
                )
            }
        }

        if (accepted) state.update { it.copy(speaking = true, paused = false, index = fromIndex) }
    }

    private fun applyAudioFocus() {
        if (configuration.mixAudio) {
            abandonAudioFocus()
            return
        }
        if (!hasAudioFocus) {
            hasAudioFocus = audioManager.requestAudioFocus(audioFocusRequest) ==
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        if (!hasAudioFocus) return
        audioManager.abandonAudioFocusRequest(audioFocusRequest)
        hasAudioFocus = false
    }

    /** Where an utterance sits, or null if its run has since been replaced. */
    private fun positionOf(utteranceId: String?): Int? {
        val parts = utteranceId?.split(ID_SEPARATOR)?.takeIf { it.size == 2 } ?: return null
        if (parts[0].toIntOrNull() != run.get() || parts[1].startsWith(SILENCE)) return null
        return parts[1].toIntOrNull()
    }

    fun shutdown() {
        stop()
        engine?.shutdown()
        engine = null
    }

    data class State(
        val available: Boolean = false,
        val initialised: Boolean = false,
        /** Active until explicitly stopped, including while paused. */
        val speaking: Boolean = false,
        val paused: Boolean = false,
        val index: Int = 0,
    )

    private data class Configuration(
        val rate: Int = 10,
        val pitch: Int = 10,
        val intervalMs: Int = 30,
        val mixAudio: Boolean = false,
    )

    private companion object {
        const val RATE_SCALE = 10f
        const val ID_SEPARATOR = ":"
        const val SILENCE = "silence-"
    }
}
