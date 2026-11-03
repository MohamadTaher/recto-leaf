package leaf.novel.ui.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The system player's seek bar runs on time, and speech only knows which unit it is on. These pin
 * down the conversion: proportional to characters, calibrated by speech itself, and deaf to seeks.
 */
class NovelSpeechClockTest {

    private var now = 0L
    private val clock = NovelSpeechClock { now }

    @Test
    fun `position and length are proportional to characters before any measurement`() {
        val reading = clock.read(chapterId = 1, charsBefore = 100, total = 1000, playing = true)
        reading.position shouldBe 100L * NovelSpeechClock.DEFAULT_MS_PER_CHAR.toLong()
        reading.duration shouldBe 1000L * NovelSpeechClock.DEFAULT_MS_PER_CHAR.toLong()
        reading.speed shouldBe 1f
    }

    @Test
    fun `a paused bar stands still`() {
        clock.read(chapterId = 1, charsBefore = 100, total = 1000, playing = false).speed shouldBe 0f
    }

    @Test
    fun `the rate comes from how long speech actually took`() {
        clock.read(chapterId = 1, charsBefore = 0, total = 1000, playing = true)
        now = 10_000
        clock.read(chapterId = 1, charsBefore = 100, total = 1000, playing = true)
        // A new chapter takes the measured 100 ms a character as its scale.
        clock.read(chapterId = 2, charsBefore = 0, total = 500, playing = true).duration shouldBe 50_000L
    }

    @Test
    fun `mid chapter the scale holds and the speed absorbs the difference`() {
        clock.read(chapterId = 1, charsBefore = 0, total = 1000, playing = true)
        now = 6_000
        val reading = clock.read(chapterId = 1, charsBefore = 100, total = 1000, playing = true)
        // Measured 60 ms a character against a default scale within the drift band, so no rescale.
        reading.duration shouldBe 1000L * NovelSpeechClock.DEFAULT_MS_PER_CHAR.toLong()
        reading.speed shouldBe NovelSpeechClock.DEFAULT_MS_PER_CHAR / 60f
    }

    @Test
    fun `time spent paused is not counted as speaking`() {
        clock.read(chapterId = 1, charsBefore = 0, total = 1000, playing = true)
        now = 1_000
        clock.read(chapterId = 1, charsBefore = 0, total = 1000, playing = false)
        now = 600_000
        clock.read(chapterId = 1, charsBefore = 0, total = 1000, playing = true)
        now = 610_000
        clock.read(chapterId = 1, charsBefore = 100, total = 1000, playing = true)
        clock.read(chapterId = 2, charsBefore = 0, total = 500, playing = true).duration shouldBe 50_000L
    }

    @Test
    fun `a seek once the rate is known does not skew it`() {
        clock.read(chapterId = 1, charsBefore = 0, total = 10_000, playing = true)
        repeat(3) {
            now += 10_000
            clock.read(chapterId = 1, charsBefore = (it + 1) * 100, total = 10_000, playing = true)
        }
        // A skip forward: 2000 characters in a tenth of a second.
        now += 100
        clock.read(chapterId = 1, charsBefore = 2_300, total = 10_000, playing = true)
        clock.read(chapterId = 2, charsBefore = 0, total = 500, playing = true).duration shouldBe 50_000L
    }

    @Test
    fun `chapter characters count only the unit's own chapter`() {
        val positions = listOf(
            NovelSpeech.Position(1, 0, 0, "aaaa"),
            NovelSpeech.Position(2, 0, 0, "bb"),
            NovelSpeech.Position(2, 1, 0, "ccc"),
            NovelSpeech.Position(2, 2, 0, "d"),
            NovelSpeech.Position(3, 0, 0, "eeeee"),
        )
        NovelSpeechClock.chapterChars(positions, 2) shouldBe NovelSpeechClock.ChapterChars(2, 2, 6)
        NovelSpeechClock.chapterChars(positions, 9) shouldBe null
    }
}
