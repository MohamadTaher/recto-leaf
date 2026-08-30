package leaf.novel.ui.reader

import io.kotest.matchers.shouldBe
import leaf.novel.ui.reader.setting.NovelSpeechDivision
import org.junit.jupiter.api.Test

/**
 * Cutting prose into sentences is the only part of speech with genuine edge cases, and every one of
 * them is a place where a naive split on full stops says half a sentence and then stops.
 */
class NovelSpeechTest {

    @Test
    fun `says one paragraph at a time by default`() {
        val html = "<p>First one.</p><p>Second one.</p>"

        NovelSpeech.utterances(html, NovelSpeechDivision.PARAGRAPH) shouldBe
            listOf("First one.", "Second one.")
    }

    @Test
    fun `skips blank blocks rather than saying nothing`() {
        val html = "<p>Words.</p><p>   </p><p></p><p>More.</p>"

        NovelSpeech.utterances(html, NovelSpeechDivision.PARAGRAPH) shouldBe listOf("Words.", "More.")
    }

    /** A blockquote wrapping paragraphs would otherwise be said once whole and once in pieces. */
    @Test
    fun `does not say a nested block twice`() {
        val html = "<blockquote><p>Inner.</p></blockquote>"

        NovelSpeech.utterances(html, NovelSpeechDivision.PARAGRAPH) shouldBe listOf("Inner.")
    }

    @Test
    fun `splits a paragraph into sentences when asked`() {
        val html = "<p>One. Two! Three?</p>"

        NovelSpeech.utterances(html, NovelSpeechDivision.SENTENCE) shouldBe
            listOf("One.", "Two!", "Three?")
    }

    @Test
    fun `does not break a name in half at its abbreviation`() {
        val html = "<p>He met Mr. Grey there. Then he left.</p>"

        NovelSpeech.utterances(html, NovelSpeechDivision.SENTENCE) shouldBe
            listOf("He met Mr. Grey there.", "Then he left.")
    }

    @Test
    fun `keeps a closing quote with the sentence it ends`() {
        val html = """<p>"Stop." He did not.</p>"""

        NovelSpeech.utterances(html, NovelSpeechDivision.SENTENCE) shouldBe
            listOf("\"Stop.\"", "He did not.")
    }

    @Test
    fun `treats an ellipsis as one ending, not three`() {
        val html = "<p>He paused… Then spoke.</p>"

        NovelSpeech.utterances(html, NovelSpeechDivision.SENTENCE) shouldBe
            listOf("He paused…", "Then spoke.")
    }

    @Test
    fun `leaves a paragraph with no terminal punctuation whole`() {
        val html = "<p>A chapter heading</p>"

        NovelSpeech.utterances(html, NovelSpeechDivision.SENTENCE) shouldBe listOf("A chapter heading")
    }

    // region Position

    @Test
    fun `is at the start before anything has been said`() {
        NovelSpeech.fractionAt(0, listOf("aaaa", "bbbb")) shouldBe 0f
    }

    /** Weighted by length, not by count: a short line of dialogue is not half the chapter. */
    @Test
    fun `weights an utterance by how much text is behind it`() {
        val utterances = listOf("aaaaaaaaa", "b")

        NovelSpeech.fractionAt(1, utterances) shouldBe 0.9f
    }

    @Test
    fun `has nothing to report for an empty chapter`() {
        NovelSpeech.fractionAt(3, emptyList()) shouldBe 0f
    }

    @Test
    fun `clamps an index past the end`() {
        NovelSpeech.fractionAt(99, listOf("aaaa", "bbbb")) shouldBe 1f
    }

    // endregion
}
