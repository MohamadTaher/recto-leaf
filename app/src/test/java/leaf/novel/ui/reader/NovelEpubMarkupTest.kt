package leaf.novel.ui.reader

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * Both passes act on what an EPUB says a piece of markup *is*, so the cases that matter are the
 * ones where it says nothing, says it the other way round, or points somewhere the pass cannot
 * follow. Leaving the chapter untouched is the right answer to all three.
 */
class NovelEpubMarkupTest {

    // region Footnotes

    @Test
    fun `folds a same-document note in after the paragraph that referenced it`() {
        val html = """
            <p>Text<a epub:type="noteref" href="#n1">1</a> more.</p>
            <aside id="n1">The note itself.</aside>
        """.trimIndent()

        val result = NovelEpubMarkup.inlineFootnotes(html)

        result shouldContain "class=\"${NovelEpubMarkup.NOTE_CLASS}\""
        result shouldContain "The note itself."
        // Exactly once: the original has to go, or the chapter ends with a second copy of them all.
        result.split("The note itself.").size shouldBe 2
        (result.indexOf("more.") < result.indexOf("The note itself.")) shouldBe true
    }

    @Test
    fun `recognises the accessibility spelling as well as the epub one`() {
        val html = """<p>Text<a role="doc-noteref" href="#n1">1</a></p><aside id="n1">Note.</aside>"""

        NovelEpubMarkup.inlineFootnotes(html) shouldContain NovelEpubMarkup.NOTE_CLASS
    }

    /** An EPUB is equally free to keep its notes in a file of their own; those stay links. */
    @Test
    fun `leaves a note in another document as a link`() {
        val html = """<p>Text<a epub:type="noteref" href="notes.xhtml#n1">1</a></p>"""

        val result = NovelEpubMarkup.inlineFootnotes(html)

        result shouldNotContain NovelEpubMarkup.NOTE_CLASS
        result shouldContain "notes.xhtml#n1"
    }

    @Test
    fun `leaves an ordinary link alone`() {
        val html = """<p>Text<a href="#somewhere">jump</a></p><p id="somewhere">Target.</p>"""

        val result = NovelEpubMarkup.inlineFootnotes(html)

        result shouldNotContain NovelEpubMarkup.NOTE_CLASS
        result shouldContain "Target."
    }

    @Test
    fun `leaves a reference whose target is missing alone`() {
        val html = """<p>Text<a epub:type="noteref" href="#gone">1</a></p>"""

        NovelEpubMarkup.inlineFootnotes(html) shouldNotContain NovelEpubMarkup.NOTE_CLASS
    }

    @Test
    fun `escapes markup that came out of the note`() {
        val html = """<p>T<a epub:type="noteref" href="#n1">1</a></p><aside id="n1">a &lt; b</aside>"""

        val result = NovelEpubMarkup.inlineFootnotes(html)

        result shouldContain "a &lt; b"
    }

    // endregion

    // region Print page numbers

    @Test
    fun `puts the page number into the break that carries it`() {
        val html = """<p>Before<span epub:type="pagebreak" title="42"></span>after.</p>"""

        val result = NovelEpubMarkup.showPageNumbers(html)

        result shouldContain "class=\"${NovelEpubMarkup.PAGE_CLASS}\""
        result shouldContain ">42<"
    }

    @Test
    fun `reads the number from whichever attribute the book used`() {
        val ariaLabel = """<span role="doc-pagebreak" aria-label="7"></span>"""
        val value = """<span epub:type="pagebreak" value="9"></span>"""

        NovelEpubMarkup.showPageNumbers(ariaLabel) shouldContain ">7<"
        NovelEpubMarkup.showPageNumbers(value) shouldContain ">9<"
    }

    @Test
    fun `leaves a chapter with no page breaks untouched`() {
        val html = "<p>Just prose.</p>"

        NovelEpubMarkup.showPageNumbers(html) shouldNotContain NovelEpubMarkup.PAGE_CLASS
    }

    @Test
    fun `skips a page break that names no page`() {
        val html = """<p>Text<span epub:type="pagebreak"></span></p>"""

        NovelEpubMarkup.showPageNumbers(html) shouldNotContain NovelEpubMarkup.PAGE_CLASS
    }

    // endregion
}
