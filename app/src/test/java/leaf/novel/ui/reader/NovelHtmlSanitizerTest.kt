package leaf.novel.ui.reader

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import leaf.novel.ui.reader.loader.NovelEpubAssetServer
import leaf.novel.ui.reader.loader.VIRTUAL_ORIGIN
import org.junit.jupiter.api.Test

/**
 * The boundary between an extension's output and the reader's WebView, so both directions are
 * pinned here: what the safelist drops is a security property, and what it keeps is the difference
 * between a chapter and a blank page.
 */
class NovelHtmlSanitizerTest {

    private val base = "https://example.test/novel/book/chapter-1"

    private fun sanitize(html: String) = NovelHtmlSanitizer.sanitize(html, base)

    @Test
    fun `drops executable and styling elements`() {
        val out = sanitize(
            "<p>keep</p><script>bad()</script><style>p{display:none}</style>" +
                "<iframe src='https://ad.test'></iframe><form><input></form>",
        )

        out shouldContain "keep"
        out shouldNotContain "script"
        out shouldNotContain "iframe"
        out shouldNotContain "display:none"
        out shouldNotContain "<input"
    }

    @Test
    fun `strips inline event handlers but keeps the element`() {
        val out = sanitize("<p onclick=\"steal()\" onmouseover=\"x()\">text</p>")

        out shouldContain "text"
        out shouldNotContain "onclick"
        out shouldNotContain "onmouseover"
    }

    @Test
    fun `absolutises real links and drops script urls`() {
        val out = sanitize("<a href='/other'>a</a><a href='javascript:evil()'>b</a>")

        out shouldContain "https://example.test/other"
        out shouldNotContain "javascript:"
    }

    @Test
    fun `rewrites remote images onto the virtual origin`() {
        val out = sanitize("<img src='/files/cover.jpg'>")

        out shouldContain VIRTUAL_ORIGIN
        // The remote host must not survive as a loadable URL.
        out shouldNotContain "src=\"https://example.test/files/cover.jpg\""
    }

    @Test
    fun `a rewritten image round-trips back to its original url`() {
        val original = "https://example.test/files/a b+c.jpg?v=2&x=1#frag"

        val encoded = VIRTUAL_ORIGIN + NovelHtmlSanitizer.percentEncode(original)

        // pathFor strips at the first ? and # before decoding, so the encoding has to hide both.
        NovelEpubAssetServer.pathFor(encoded) shouldBe original
    }

    @Test
    fun `keeps inline data images`() {
        val out = sanitize("<img src='data:image/png;base64,AAAA'>")

        out shouldContain "data:image/png;base64,AAAA"
    }

    @Test
    fun `removes srcset carriers so a rewritten image cannot be overridden`() {
        val out = sanitize(
            "<picture><source srcset='/files/big.webp 2x'><img src='/files/small.jpg'></picture>",
        )

        out shouldNotContain "srcset"
        out shouldNotContain "big.webp"
        out shouldContain VIRTUAL_ORIGIN
    }

    @Test
    fun `drops images that are neither http nor data`() {
        val out = sanitize("<img src='file:///etc/passwd'>")

        out shouldNotContain "file:"
    }

    @Test
    fun `keeps the markup prose is actually made of`() {
        val out = sanitize(
            "<p>plain</p><p><em>em</em> <strong>strong</strong> <i>i</i> <b>b</b></p>" +
                "<blockquote>quoted</blockquote><h2>heading</h2><br><ul><li>item</li></ul>",
        )

        listOf("<p>", "<em>", "<strong>", "<i>", "<b>", "<blockquote>", "<h2>", "<br>", "<li>")
            .forEach { out shouldContain it }
    }

    @Test
    fun `keeps a horizontal rule, which novels use as a scene break`() {
        val out = sanitize("<p>before</p><hr><p>after</p>")

        out shouldContain "<hr>"
    }

    @Test
    fun `handles a fragment with no base url`() {
        val out = NovelHtmlSanitizer.sanitize("<p>text</p><a href='/rel'>x</a>", null)

        out shouldContain "text"
        out shouldNotContain "href"
    }

    @Test
    fun `drops an empty paragraph`() {
        NovelHtmlSanitizer.trimBlankLines("<p>one</p><p></p><p>two</p>") shouldBe "<p>one</p><p>two</p>"
    }

    @Test
    fun `drops a paragraph holding only whitespace`() {
        NovelHtmlSanitizer.trimBlankLines("<p>one</p><p>   </p><p>two</p>") shouldBe "<p>one</p><p>two</p>"
    }

    @Test
    fun `collapses a run of line breaks to one`() {
        NovelHtmlSanitizer.trimBlankLines("<p>one<br><br><br>two</p>") shouldBe "<p>one<br>two</p>"
    }

    @Test
    fun `keeps a single line break`() {
        NovelHtmlSanitizer.trimBlankLines("<p>one<br>two</p>") shouldBe "<p>one<br>two</p>"
    }

    /** A rule is a scene break, which is content: trimming padding must not take it. */
    @Test
    fun `keeps a horizontal rule`() {
        NovelHtmlSanitizer.trimBlankLines("<p>one</p><hr><p>two</p>") shouldBe "<p>one</p><hr><p>two</p>"
    }

    /** Blank of text is not blank of content. */
    @Test
    fun `keeps a paragraph holding only an image`() {
        NovelHtmlSanitizer.trimBlankLines("<p><img src=\"a.png\"></p>") shouldBe "<p><img src=\"a.png\"></p>"
    }

    private fun dropTitle(html: String, title: String = "Chapter 12: The Return") =
        NovelHtmlSanitizer.dropRepeatedTitle(html, listOf(title))

    @Test
    fun `drops a heading that repeats the title`() {
        dropTitle("<h1>Chapter 12 - The Return</h1><p>Prose.</p>") shouldBe "<p>Prose.</p>"
    }

    @Test
    fun `drops every leading copy and the rule under them`() {
        dropTitle("<h2>Chapter 12: The Return</h2><p>Chapter 12: The Return</p><hr><p>Prose.</p>") shouldBe
            "<p>Prose.</p>"
    }

    @Test
    fun `drops a title split across two blocks`() {
        dropTitle("<p>Chapter 12</p><p>The Return</p><p>Prose.</p>") shouldBe "<p>Prose.</p>"
    }

    @Test
    fun `looks inside a wrapper around the chapter`() {
        dropTitle("<div><h3>Chapter 12: The Return</h3><p>Prose.</p></div>") shouldBe "<div><p>Prose.</p></div>"
    }

    @Test
    fun `drops a plain text title and its line break`() {
        dropTitle("Chapter 12: The Return<br>Prose.") shouldBe "Prose."
    }

    @Test
    fun `drops a fuller title when the chapter is named by number only`() {
        dropTitle("<h1>Chapter 12: The Return</h1><p>Prose.</p>", title = "Chapter 12") shouldBe "<p>Prose.</p>"
    }

    /** A first line that only shares a word with the title is prose. */
    @Test
    fun `keeps prose that is not the title`() {
        val html = "<p>Return.</p><p>Chapter 12 was long.</p>"
        dropTitle(html) shouldBe html
    }

    @Test
    fun `keeps a title further down the chapter`() {
        val html = "<p>Prose.</p><h1>Chapter 12: The Return</h1>"
        dropTitle(html) shouldBe html
    }

    /** A scene break that does not sit under a removed title is content. */
    @Test
    fun `keeps a leading rule when nothing was dropped`() {
        val html = "<hr><p>Prose.</p>"
        dropTitle(html) shouldBe html
    }
}
