package leaf.novel.ui.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.history.model.HistoryUpdate
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * [NovelSpeechHistory] is what moves History along while speech runs with no reader attached. A
 * reader writes the chapter it leaves as it changes chapter; this has to do the same, and write the
 * chapter speech ends in, or History's Resume opens a chapter speech has already finished.
 */
class NovelSpeechHistoryTest {

    private val start = Instant.fromEpochMilliseconds(1_000_000)

    /** The entry for [chapterId], written [at] after [start] for [read] of listening. */
    private fun entry(chapterId: Long, at: Duration, read: Duration) =
        HistoryUpdate(chapterId, start + at, read.inWholeMilliseconds)

    @Test
    fun `writes nothing until speech moves on from the chapter it was left in`() {
        val history = NovelSpeechHistory()

        history.advance(chapterId = 5, now = start, ended = false) shouldBe null
        history.advance(chapterId = 5, now = start + 3.minutes, ended = false) shouldBe null
    }

    @Test
    fun `writes the chapter left, timed from when speech entered it`() {
        val history = NovelSpeechHistory()
        history.advance(chapterId = 5, now = start, ended = false)

        history.advance(chapterId = 6, now = start + 12.minutes, ended = false) shouldBe
            entry(5, at = 12.minutes, read = 12.minutes)
        history.advance(chapterId = 7, now = start + 20.minutes, ended = false) shouldBe
            entry(6, at = 20.minutes, read = 8.minutes)
    }

    @Test
    fun `writes the chapter speech ends in`() {
        val history = NovelSpeechHistory()
        history.advance(chapterId = 5, now = start, ended = false)

        history.advance(chapterId = 5, now = start + 4.minutes, ended = true) shouldBe
            entry(5, at = 4.minutes, read = 4.minutes)
    }

    @Test
    fun `a reader attaching starts the timing afresh`() {
        val history = NovelSpeechHistory()
        history.advance(chapterId = 5, now = start, ended = false)

        history.clear()

        history.advance(chapterId = 6, now = start + 30.minutes, ended = false) shouldBe null
    }
}
