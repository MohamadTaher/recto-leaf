package leaf.novel.ui.reader

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * The read-aloud engine and its queue, held at process scope rather than by the reader's
 * ViewModel.
 *
 * [NovelReaderViewModel] used to own both directly and tear them down in `onCleared()`, which is
 * why swiping the reader out of recents killed the voice. [NovelSpeechService] is what keeps this
 * object's process alive; this is what it is keeping alive, and it is also the
 * [NovelSpeechService.Controls] the notification's buttons reach, so they still work once the
 * reader that started speech is gone.
 *
 * A reader is not the only thing that can be gone when speech ends — the last utterance can finish,
 * the sleep timer can fire, or the notification's own Stop button can be pressed, all with no reader
 * attached to notice. So this object watches its own engine and releases itself, but only when that
 * is actually true: see [attach], [detach] and [NovelSpeechAttachment].
 *
 * [engine], [appContext] and [observerJob] are written from whichever reader last called [speaker]
 * or [detach] — ordinarily the main thread — and read from the observer coroutine on [scope]'s own
 * dispatcher, so all three are `@Volatile`.
 */
object NovelSpeechSession : NovelSpeechService.Controls {
    val queue = NovelSpeechQueue()
    private val attachment = NovelSpeechAttachment()

    /**
     * Outlives any one reader by construction — nothing ever cancels it, which is the whole point:
     * a sleep timer armed on `viewModelScope` used to die with the reader while the speech it was
     * meant to stop kept going.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The sleep timer, armed and cancelled by whichever reader is currently attached. */
    val stopTimer = NovelSpeechTimer(scope)

    @Volatile
    private var engine: NovelSpeaker? = null

    /** Set alongside [engine], so [reset] can hide the notification with no reader left to ask. */
    @Volatile
    private var appContext: Context? = null

    /** Cancelled in [reset] so an ended session leaves no collector behind it (M5). */
    @Volatile
    private var observerJob: Job? = null

    /** Which run of the engine a reader is wired to — see [NovelSpeechAttachment]. */
    val generation: Int get() = attachment.generation

    /**
     * The running engine, built against the application context on first use so it is never tied
     * to whichever Activity happened to start speaking.
     */
    fun speaker(context: Context): NovelSpeaker {
        engine?.let { return it }
        val created = NovelSpeaker(context.applicationContext)
        engine = created
        appContext = context.applicationContext
        attachment.newGeneration()
        observe(created)
        return created
    }

    /** The engine already built, if any — for callers that must not create one just to check. */
    fun speakerOrNull(): NovelSpeaker? = engine

    /** Whether the engine is currently speaking — the test for whether a session exists to keep. */
    fun isSpeaking(): Boolean = engine?.speech?.value?.speaking == true

    /** Called once a reader has wired a collector to the engine [generation] now names. */
    fun attach() = attachment.attach()

    /**
     * Called when a reader is no longer watching — destroyed, in [NovelReaderViewModel.onCleared].
     * Speech that is still running is left alone; the next [Transition.ENDED][NovelSpeechLifecycle]
     * the observer sees will release it. Speech already stopped has nothing left to fire that edge,
     * so it is released immediately.
     */
    fun detach() {
        attachment.detach()
        if (engine?.speech?.value?.speaking != true) reset()
    }

    override fun togglePlayback() {
        val speaker = engine ?: return
        if (speaker.speech.value.paused) speaker.resume() else speaker.pause()
    }

    override fun stop() {
        engine?.stop()
    }

    /**
     * Watches the engine this object just built, so speech ending has somewhere to be noticed even
     * with no reader attached. [NovelSpeechLifecycle] is what tells "ended" apart from "paused" —
     * `NovelSpeaker.State.speaking` stays true while paused, so only a speaking→not-speaking edge
     * counts — and [NovelSpeechAttachment.mayResetOn] is what tells "nobody is watching" apart from
     * "a reader is right there and will hide the notification itself" (M4).
     */
    private fun observe(speaker: NovelSpeaker) {
        val lifecycle = NovelSpeechLifecycle()
        observerJob = speaker.speech
            .onEach { state ->
                val ended = lifecycle.observe(state.speaking) == NovelSpeechLifecycle.Transition.ENDED
                if (attachment.mayResetOn(ended)) reset()
            }
            .launchIn(scope)
    }

    /**
     * Tears the session down: cancels the observer (M5), releases the engine, clears the queue and
     * the timer, and hides the notification. Only correct once nothing is left that could reattach
     * to it — [detach] and the observer above are the only callers, and both check first.
     */
    fun reset() {
        val context = appContext
        observerJob?.cancel()
        observerJob = null
        engine?.shutdown()
        engine = null
        appContext = null
        queue.clear()
        stopTimer.cancel()
        if (context != null) NovelSpeechService.hide(context)
    }
}

/**
 * What survives a reader detaching that the engine itself does not track: which novel is speaking,
 * the queued positions, and which chapter they reach. [NovelSpeaker]'s own `StateFlow` already
 * outlives the reader — it is held by [NovelSpeechSession], not by the ViewModel — so it remains
 * the one source of truth for whether speech is running, paused, and at which unit; this only adds
 * what the engine, working in plain strings, cannot report back.
 *
 * Kept free of Android types so this half of ownership can be driven from a JVM test; the engine
 * itself, wrapping `TextToSpeech`, cannot be.
 */
class NovelSpeechQueue {
    var mangaId: Long = NO_MANGA
        private set
    var positions: List<NovelSpeech.Position> = emptyList()
        private set
    var chapterIndex: Int = 0
        private set

    /** Starts a fresh run for [mangaId], replacing whatever was queued before, for any novel. */
    fun start(mangaId: Long, positions: List<NovelSpeech.Position>, chapterIndex: Int) {
        this.mangaId = mangaId
        this.positions = positions
        this.chapterIndex = chapterIndex
    }

    /** Appends the next chapter's pieces as speech carries across the boundary. */
    fun extend(more: List<NovelSpeech.Position>, chapterIndex: Int) {
        positions = positions + more
        this.chapterIndex = chapterIndex
    }

    fun clear() {
        mangaId = NO_MANGA
        positions = emptyList()
        chapterIndex = 0
    }

    /** Whether a reader for [mangaId] is the one this queue is currently speaking for. */
    fun belongsTo(mangaId: Long): Boolean = this.mangaId == mangaId

    /** What a reader attaching now should show, built from the engine's own report. */
    fun snapshot(engine: NovelSpeaker.State) = Snapshot(
        speaking = engine.speaking,
        paused = engine.paused,
        index = engine.index,
        count = positions.size,
        position = positions.getOrNull(engine.index),
        unavailable = engine.initialised && !engine.available,
    )

    data class Snapshot(
        val speaking: Boolean,
        val paused: Boolean,
        val index: Int,
        val count: Int,
        val position: NovelSpeech.Position?,
        val unavailable: Boolean,
    )

    private companion object {
        const val NO_MANGA = -1L
    }
}

/**
 * Tells a speaking→not-speaking edge apart from a not-speaking→speaking one, from nothing but the
 * engine's own reported state — so both [NovelSpeechSession] (deciding when to release the engine)
 * and [NovelReaderViewModel] (deciding when to arm or cancel the sleep timer) read the same edges
 * the same way.
 *
 * Seed [initiallySpeaking] from the engine's current state before collecting anything from it: a
 * `StateFlow` replays its latest value to a new collector, and without this an already-speaking
 * session looks, on that first replay, exactly like one just starting.
 */
class NovelSpeechLifecycle(initiallySpeaking: Boolean = false) {
    private var wasSpeaking = initiallySpeaking

    /** Call on every engine state report. Returns which edge, if any, this report crossed. */
    fun observe(speaking: Boolean): Transition {
        val transition = when {
            !wasSpeaking && speaking -> Transition.STARTED
            wasSpeaking && !speaking -> Transition.ENDED
            else -> Transition.NONE
        }
        wasSpeaking = speaking
        return transition
    }

    enum class Transition { STARTED, ENDED, NONE }
}

/**
 * Which run of the engine a reader is wired to, and whether anyone is watching right now.
 *
 * [NovelSpeechSession] bumps [generation] every time it builds a new engine, so a reader comparing
 * the generation it last attached to against the current one knows whether attaching again would
 * wire a second collector to the same engine, or the first to a new one — the question
 * `sessionAttached: Boolean` got wrong, because it answered "has this reader ever attached" rather
 * than "is it attached to the engine that exists now" (M4). A stop-then-play cycle builds a fresh
 * engine and therefore a new generation, so the old boolean stayed permanently "already attached"
 * and wired no collector to it.
 *
 * [attached] answers a different question: is any reader watching at all, right now? That is what
 * makes it safe for the session to release the engine when speech ends with [attached] false, and
 * unsafe when it is true — a reader that is present already hides the notification itself through
 * its own collector, and tearing the engine down under it would orphan that collector instead.
 */
class NovelSpeechAttachment {
    @Volatile
    var generation: Int = 0
        private set

    @Volatile
    var attached: Boolean = false
        private set

    /** Called whenever a new engine is built. Marks it unattached until a reader claims it. */
    fun newGeneration() {
        generation++
        attached = false
    }

    fun attach() {
        attached = true
    }

    fun detach() {
        attached = false
    }

    /** Speech just crossed the given edge — may the session release the engine because of it? */
    fun mayResetOn(ended: Boolean): Boolean = ended && !attached
}
