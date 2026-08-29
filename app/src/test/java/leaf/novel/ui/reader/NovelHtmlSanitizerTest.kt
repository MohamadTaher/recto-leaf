package leaf.novel.ui.reader

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import leaf.novel.ui.reader.loader.NovelEpubAssetServer
import leaf.novel.ui.reader.loader.VIRTUAL_ORIGIN
import org.junit.jupiter.api.Test

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
}
