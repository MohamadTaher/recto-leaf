package leaf.novel.ui.reader

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes

/**
 * [NovelSpeechTimer] is what the sleep timer runs on now that it has to survive the reader that
 * armed it — see F010. Its whole job is a plain `delay`, so virtual time is enough to prove it
 * fires exactly once, exactly when armed, and not when cancelled or superseded.
 */
class NovelSpeechTimerTest {

    @Test
    fun `fires after the armed duration and not before`() = runTest {
        val timer = NovelSpeechTimer(this)
        var fired = false

        timer.arm(10.minutes) { fired = true }

        advanceTimeBy(9.minutes)
        runCurrent()
        fired shouldBe false

        advanceTimeBy(2.minutes)
        runCurrent()
        fired shouldBe true
    }

    @Test
    fun `cancelling before it fires stops it firing`() = runTest {
        val timer = NovelSpeechTimer(this)
        var fired = false
        timer.arm(10.minutes) { fired = true }

        timer.cancel()
        advanceTimeBy(11.minutes)
        runCurrent()

        fired shouldBe false
    }

    @Test
    fun `re-arming replaces the pending timer rather than stacking a second`() = runTest {
        val timer = NovelSpeechTimer(this)
        var fireCount = 0
        timer.arm(10.minutes) { fireCount++ }

        advanceTimeBy(5.minutes)
        runCurrent()
        timer.arm(10.minutes) { fireCount++ }

        // The first arming's original deadline (10 minutes from the start) has now passed; if it
        // had not been replaced, it would fire here on top of the second.
        advanceTimeBy(6.minutes)
        runCurrent()
        fireCount shouldBe 0

        advanceTimeBy(4.minutes)
        runCurrent()
        fireCount shouldBe 1
    }

    @Test
    fun `a non-positive duration arms nothing`() = runTest {
        val timer = NovelSpeechTimer(this)
        var fired = false

        timer.arm(0.minutes) { fired = true }
        advanceTimeBy(60.minutes)
        runCurrent()

        fired shouldBe false
    }
}
