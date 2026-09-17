package leaf.novel.ui.reader

import io.kotest.matchers.shouldBe
import leaf.novel.ui.reader.setting.NovelSpeechDivision
import leaf.novel.ui.reader.setting.NovelTextReplacement
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test

/**
 * Cutting prose into sentences is the only part of speech with genuine edge cases, and every one of
 * them is a place where a naive split on full stops says half a sentence and then stops.
 */
class NovelSpeechTest {

    @Test
    fun `comma division preserves punctuation and source offsets`() {
        NovelSpeech.positions("<p>One, two，three، four.</p>", NovelSpeechDivision.COMMA, 7) shouldBe listOf(
            NovelSpeech.Position(7, 0, 0, "One,"),
            NovelSpeech.Position(7, 0, 5, "two，"),
            NovelSpeech.Position(7, 0, 9, "three،"),
            NovelSpeech.Position(7, 0, 16, "four."),
        )
    }

    @Test
    fun `word division preserves repeated words inline markup and punctuation`() {
        NovelSpeech.positions("<p>Yes <em>yes</em>&nbsp; yes.</p><p>Next!</p>", NovelSpeechDivision.WORD, 7) shouldBe
            listOf(
                NovelSpeech.Position(7, 0, 0, "Yes"),
                NovelSpeech.Position(7, 0, 4, "yes"),
                NovelSpeech.Position(7, 0, 8, "yes."),
                NovelSpeech.Position(7, 1, 0, "Next!"),
            )
    }

    @Test
    fun `speech groups end with their paragraph and fit the engine`() {
        val units = NovelSpeech.positions("<p>One two three.</p><p>Four five.</p>", NovelSpeechDivision.WORD, 7)
        NovelSpeech.groups(units, fromIndex = 1, maxLength = 100, acrossParagraphs = false) shouldBe
            listOf(1..2, 3..4)
        NovelSpeech.groups(units, fromIndex = 0, maxLength = 7, acrossParagraphs = false) shouldBe
            listOf(0..1, 2..2, 3..3, 4..4)
    }

    @Test
    fun `paragraph speech groups across paragraphs up to the engine limit`() {
        val units = NovelSpeech.positions("<p>One.</p><p>Two.</p><p>Three.</p>", NovelSpeechDivision.PARAGRAPH, 7)
        NovelSpeech.groups(units, fromIndex = 0, maxLength = 100, acrossParagraphs = true) shouldBe listOf(0..2)
        NovelSpeech.groups(units, fromIndex = 0, maxLength = 9, acrossParagraphs = true) shouldBe
            listOf(0..1, 2..2)
    }

    @Test
    fun `a character offset inside a group names the unit being said`() {
        val units = NovelSpeech.positions("<p>One two three. Four</p>", NovelSpeechDivision.WORD, 7)
        // "two three. Four": two at 0, three. at 4, Four at 11.
        listOf(0, 3, 4, 10, 11, 99).map { NovelSpeech.unitAt(units, 1..3, it) } shouldBe
            listOf(1, 1, 2, 2, 3, 3)
    }

    @Test
    fun `visible location selects its containing speech unit instead of a percentage estimate`() {
        val html = "<p>Earlier text.</p><p>First sentence. Second, sentence here.</p><p>Later.</p>"
        val anchor = NovelSpeech.Anchor(7, 1, 26)
        val expected = mapOf(
            NovelSpeechDivision.PARAGRAPH to "First sentence. Second, sentence here.",
            NovelSpeechDivision.SENTENCE to "Second, sentence here.",
            NovelSpeechDivision.COMMA to "sentence here.",
            NovelSpeechDivision.WORD to "sentence",
        )
        expected.forEach { (division, text) ->
            val positions = NovelSpeech.positions(html, division, 7)
            positions[NovelSpeech.resumeIndex(99, positions, null, anchor)].text shouldBe text
        }
    }

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
    fun `repeated dialogue retains its chapter block and sentence offset`() {
        val html = "<p>Yes.</p><p>He said yes. Yes. Yes.</p><p>Yes.</p>"
        NovelSpeech.positions(html, NovelSpeechDivision.SENTENCE, chapterId = 42) shouldBe listOf(
            NovelSpeech.Position(42, 0, 0, "Yes."),
            NovelSpeech.Position(42, 1, 0, "He said yes."),
            NovelSpeech.Position(42, 1, 13, "Yes."),
            NovelSpeech.Position(42, 1, 18, "Yes."),
            NovelSpeech.Position(42, 2, 0, "Yes."),
        )
    }

    @Test
    fun `rendered anchors agree with speech after blank and nested blocks are filtered`() {
        val html = "<p> </p><blockquote><p>One <em>word</em>.</p><p>Again.</p></blockquote><p>Again.</p>"
        val positions = NovelSpeech.positions(html, NovelSpeechDivision.PARAGRAPH, chapterId = 42)
        val document = Jsoup.parse(NovelSpeech.anchorBlocks(html))
        positions.forEach { position ->
            document.select("[${NovelSpeech.BLOCK_ATTRIBUTE}=${position.block}]").single().text() shouldBe position.text
        }
        positions.map { it.block } shouldBe listOf(0, 1, 2)
    }

    @Test
    fun `queued source locations survive chapter extension and seeking backwards`() {
        val first = NovelSpeech.positions("<p>Again.</p>", NovelSpeechDivision.PARAGRAPH, chapterId = 10)
        val next = NovelSpeech.positions("<p>Again.</p>", NovelSpeechDivision.PARAGRAPH, chapterId = 11)
        val queue = first + next
        queue[1] shouldBe NovelSpeech.Position(11, 0, 0, "Again.")
        queue[0] shouldBe NovelSpeech.Position(10, 0, 0, "Again.")
    }

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
    fun `reports how far a spoken unit sits, weighted the same way indexAt reads it back`() {
        val utterances = listOf("aaaaaaaaa", "b")

        NovelSpeech.percentAt(0, utterances) shouldBe 0
        NovelSpeech.percentAt(1, utterances) shouldBe 90
    }

    @Test
    fun `clamps a spoken position to the chapter`() {
        val utterances = listOf("one", "two")

        NovelSpeech.percentAt(-1, utterances) shouldBe 0
        NovelSpeech.percentAt(5, utterances) shouldBe 50
        NovelSpeech.percentAt(0, emptyList()) shouldBe 0
    }

    // endregion
}
