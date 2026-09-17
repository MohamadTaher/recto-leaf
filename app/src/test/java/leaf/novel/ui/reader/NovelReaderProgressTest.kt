package leaf.novel.ui.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

class NovelReaderProgressTest {
    private val chapter = Chapter.create().copy(id = 1, lastPageRead = 20)
    private val bookmark = NovelSpeech.Position(chapterId = 1, block = 2, start = 0, text = "Third paragraph")

    @Test
    fun `manual reading updates the chapter position used to start speech`() {
        val state = NovelReaderViewModel.State(chapters = listOf(chapter))
        state.withProgress(1, 57, fromSpeech = false).currentChapter?.lastPageRead shouldBe 57
    }

    @Test
    fun `speech advances the same reading position and stale WebView reports cannot rewind it`() {
        val state = NovelReaderViewModel.State(chapters = listOf(chapter), speaking = true)
            .withProgress(1, 63, fromSpeech = true)
        state.currentChapter?.lastPageRead shouldBe 63
        state.withProgress(1, 0, fromSpeech = false) shouldBe state
    }

    @Test
    fun `stopping retains the spoken position for the next start`() {
        val state = NovelReaderViewModel.State(chapters = listOf(chapter), speaking = true, speechPosition = bookmark)
            .withProgress(1, 63, fromSpeech = true)
            .copy(speaking = false)
        state.currentChapter?.lastPageRead shouldBe 63
        state.withProgress(1, 63, fromSpeech = false).speechPosition shouldBe bookmark
    }

    @Test
    fun `manual movement after speech stops replaces the old speech bookmark`() {
        val state = NovelReaderViewModel.State(chapters = listOf(chapter), speechPosition = bookmark)
            .withProgress(1, 42, fromSpeech = false)
        state.currentChapter?.lastPageRead shouldBe 42
        state.speechPosition shouldBe null
    }

    @Test
    fun `progress belongs to its chapter even when speech has crossed into a later chapter`() {
        val next = chapter.copy(id = 2, lastPageRead = 0)
        val state = NovelReaderViewModel.State(chapters = listOf(chapter, next), currentIndex = 1, speaking = true)
            .withProgress(2, 34, fromSpeech = true)
        state.chapters[0].lastPageRead shouldBe 20
        state.currentChapter?.lastPageRead shouldBe 34
    }

    @Test
    fun `exact speech bookmark avoids percentage rounding into an earlier unit`() {
        val positions = listOf(
            bookmark.copy(block = 0, text = "aaaa"),
            bookmark.copy(block = 1, text = "bbbb"),
            bookmark.copy(block = 2, text = "cccc"),
        )
        NovelSpeech.resumeIndex(33, positions, positions[1]) shouldBe 1
        NovelSpeech.resumeIndex(70, positions, null) shouldBe 2
        NovelSpeech.resumeIndex(70, positions, positions[1].copy(chapterId = 2)) shouldBe 2
    }
}
