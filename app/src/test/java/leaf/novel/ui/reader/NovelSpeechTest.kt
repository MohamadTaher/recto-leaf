package leaf.novel.ui.reader

import io.kotest.matchers.shouldBe
import leaf.novel.ui.reader.setting.NovelSpeechDivision
import leaf.novel.ui.reader.setting.NovelTextReplacement
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

    @Test
    fun `text replacement rules can filter what is spoken`() {
        val rules = NovelTextReplacements.encode(
            listOf(NovelTextReplacement(pattern = "Translator note", replacement = "")),
        )
        val html = NovelTextReplacements.apply("<p>Read me.</p><p>Translator note</p>", rules)

        NovelSpeech.utterances(html, NovelSpeechDivision.PARAGRAPH) shouldBe listOf("Read me.")
    }

    // region Position

    @Test
    fun `starts speaking at the visible weighted position`() {
        val utterances = listOf("aaaaaaaaa", "b")

        NovelSpeech.indexAt(0.5f, utterances) shouldBe 0
        NovelSpeech.indexAt(0.95f, utterances) shouldBe 1
    }

    @Test
    fun `clamps a visible position to the chapter`() {
        val utterances = listOf("one", "two")

        NovelSpeech.indexAt(-1f, utterances) shouldBe 0
        NovelSpeech.indexAt(2f, utterances) shouldBe 1
        NovelSpeech.indexAt(0.5f, emptyList()) shouldBe 0
    }

    @Test
    fun `identifies the matching repeated utterance`() {
        val utterances = listOf("Again.", "Different.", "Again.")

        NovelSpeech.occurrenceAt(0, utterances) shouldBe 0
        NovelSpeech.occurrenceAt(2, utterances) shouldBe 1
    }

    // endregion
}
