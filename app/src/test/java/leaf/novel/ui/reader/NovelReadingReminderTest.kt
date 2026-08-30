package leaf.novel.ui.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

private const val MINUTE = 60_000L

/**
 * The value is typed by hand and the wait wraps around midnight, so both halves have a way of being
 * quietly wrong rather than loudly.
 */
class NovelReadingReminderTest {

    @Test
    fun `reads a time as minutes since midnight`() {
        NovelReadingReminder.minutesOfDay("00:00") shouldBe 0
        NovelReadingReminder.minutesOfDay("07:30") shouldBe 450
        NovelReadingReminder.minutesOfDay("23:59") shouldBe 1439
    }

    @Test
    fun `treats an empty setting as off`() {
        NovelReadingReminder.minutesOfDay("") shouldBe null
    }

    /** Half-written input is the normal case for a field typed by hand, not the exceptional one. */
    @Test
    fun `treats anything that is not a time as off`() {
        NovelReadingReminder.minutesOfDay("7") shouldBe null
        NovelReadingReminder.minutesOfDay("07:") shouldBe null
        NovelReadingReminder.minutesOfDay("bedtime") shouldBe null
        NovelReadingReminder.minutesOfDay("24:00") shouldBe null
        NovelReadingReminder.minutesOfDay("07:60") shouldBe null
    }

    @Test
    fun `waits until later today when the time is still ahead`() {
        NovelReadingReminder.millisUntilNext(now = 400, target = 450) shouldBe 50 * MINUTE
    }

    @Test
    fun `waits for tomorrow when the time has already passed`() {
        NovelReadingReminder.millisUntilNext(now = 500, target = 450) shouldBe 1390 * MINUTE
    }

    /** Otherwise it would fire again and again for the whole minute it stays true. */
    @Test
    fun `waits a whole day when the time is now`() {
        NovelReadingReminder.millisUntilNext(now = 450, target = 450) shouldBe 1440 * MINUTE
    }
}
