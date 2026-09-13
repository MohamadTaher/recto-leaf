package leaf.novel.ui.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * [NovelSpeechQueue] is the half of speech's process-scoped ownership that has no Android types in
 * it, so it is the only half a JVM test can drive directly. What it has to prove: the queue and the
 * engine's own reported position outlive whatever reader last touched them, a reader that attaches
 * to a session already running adopts that position instead of resetting it, and a session records
 * which novel it belongs to so a different novel's reader cannot mistake it for its own (M2).
 */
class NovelSpeechQueueTest {

    private fun position(block: Int) = NovelSpeech.Position(chapterId = 1L, block = block, start = 0, text = "p$block")

    @Test
    fun `records which novel is speaking, for readers of a different novel to tell apart`() {
        val queue = NovelSpeechQueue()

        queue.start(mangaId = 1L, positions = listOf(position(0)), chapterIndex = 0)

        queue.belongsTo(1L) shouldBe true
        queue.belongsTo(2L) shouldBe false
    }

    @Test
    fun `starting for one novel replaces whatever an earlier novel had queued`() {
        val queue = NovelSpeechQueue()
        queue.start(mangaId = 1L, positions = listOf(position(0), position(1)), chapterIndex = 3)

        queue.start(mangaId = 2L, positions = listOf(position(9)), chapterIndex = 0)

        queue.belongsTo(1L) shouldBe false
        queue.belongsTo(2L) shouldBe true
        queue.positions shouldBe listOf(position(9))
    }

    @Test
    fun `extending keeps earlier positions and moves the chapter index on`() {
        val queue = NovelSpeechQueue()
        queue.start(mangaId = 1L, positions = listOf(position(0)), chapterIndex = 5)

        queue.extend(listOf(position(1), position(2)), chapterIndex = 6)

        queue.positions shouldBe listOf(position(0), position(1), position(2))
        queue.chapterIndex shouldBe 6
    }

    @Test
    fun `a reader attaching adopts the engine's index and paused state rather than resetting them`() {
        val queue = NovelSpeechQueue()
        queue.start(mangaId = 1L, positions = listOf(position(0), position(1), position(2)), chapterIndex = 0)

        // What the engine reports once speech has moved on while no reader was attached to hear it.
        val running = NovelSpeaker.State(
            available = true,
            initialised = true,
            speaking = true,
            paused = true,
            index = 2,
        )

        val snapshot = queue.snapshot(running)

        snapshot.speaking shouldBe true
        snapshot.paused shouldBe true
        snapshot.index shouldBe 2
        snapshot.position shouldBe position(2)
        snapshot.count shouldBe 3
    }

    @Test
    fun `clearing drops the queued positions, chapter index and novel`() {
        val queue = NovelSpeechQueue()
        queue.start(mangaId = 1L, positions = listOf(position(0)), chapterIndex = 4)

        queue.clear()

        queue.positions shouldBe emptyList()
        queue.chapterIndex shouldBe 0
        queue.belongsTo(1L) shouldBe false
    }
}
