package leaf.novel.ui.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * [NovelSpeechLifecycle] is what both fixes it backs read the same edges through:
 * [NovelSpeechSession] uses `ENDED` to know when to release the engine with no reader left to say
 * so (M1), and [NovelReaderViewModel] uses `STARTED`/`ENDED` to arm and cancel the sleep timer —
 * seeded correctly on attach, an already-speaking session must not look like one just starting
 * (M3).
 */
class NovelSpeechLifecycleTest {

    @Test
    fun `reports STARTED the first time speaking becomes true`() {
        val lifecycle = NovelSpeechLifecycle()

        lifecycle.observe(speaking = true) shouldBe NovelSpeechLifecycle.Transition.STARTED
    }

    @Test
    fun `reports ENDED when speaking stops, not while merely reported again`() {
        val lifecycle = NovelSpeechLifecycle()
        lifecycle.observe(speaking = true)

        lifecycle.observe(speaking = true) shouldBe NovelSpeechLifecycle.Transition.NONE
        lifecycle.observe(speaking = false) shouldBe NovelSpeechLifecycle.Transition.ENDED
        lifecycle.observe(speaking = false) shouldBe NovelSpeechLifecycle.Transition.NONE
    }

    @Test
    fun `pausing is not reported as ending`() {
        // NovelSpeaker.State.speaking stays true while paused; only stop() sets it false. This
        // class only ever sees `speaking`, so a pause that never changes it produces no edge — the
        // predicate M1 needs: "ended", not "paused".
        val lifecycle = NovelSpeechLifecycle()
        lifecycle.observe(speaking = true)

        lifecycle.observe(speaking = true) shouldBe NovelSpeechLifecycle.Transition.NONE
    }

    @Test
    fun `seeding from an already-speaking engine does not report a false start`() {
        // This is M3: attaching to a session already running must not look like a transition into
        // speaking, or it re-arms a sleep timer that was already counting down.
        val lifecycle = NovelSpeechLifecycle(initiallySpeaking = true)

        // The StateFlow this models replays its current value to a new collector first.
        lifecycle.observe(speaking = true) shouldBe NovelSpeechLifecycle.Transition.NONE
    }

    @Test
    fun `seeding from a stopped engine still reports the next real start`() {
        val lifecycle = NovelSpeechLifecycle(initiallySpeaking = false)

        lifecycle.observe(speaking = true) shouldBe NovelSpeechLifecycle.Transition.STARTED
    }
}
