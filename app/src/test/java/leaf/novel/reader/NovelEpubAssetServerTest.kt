package leaf.novel.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class NovelEpubAssetServerTest {

    @Test
    fun `maps a virtual-origin url to an archive entry`() {
        NovelEpubAssetServer.pathFor("${VIRTUAL_ORIGIN}OEBPS/Text/ch1.xhtml") shouldBe "OEBPS/Text/ch1.xhtml"
    }

    @Test
    fun `drops a fragment`() {
        NovelEpubAssetServer.pathFor("${VIRTUAL_ORIGIN}OEBPS/ch1.xhtml#note") shouldBe "OEBPS/ch1.xhtml"
    }

    @Test
    fun `drops a query string`() {
        NovelEpubAssetServer.pathFor("${VIRTUAL_ORIGIN}OEBPS/ch1.xhtml?v=2") shouldBe "OEBPS/ch1.xhtml"
    }

    @Test
    fun `percent-decodes the entry name`() {
        NovelEpubAssetServer.pathFor("${VIRTUAL_ORIGIN}OEBPS/Chapter%201.xhtml") shouldBe "OEBPS/Chapter 1.xhtml"
    }

    @Test
    fun `returns null for the bare origin`() {
        NovelEpubAssetServer.pathFor(VIRTUAL_ORIGIN) shouldBe null
    }

    @Test
    fun `returns null for a url that is not ours`() {
        NovelEpubAssetServer.pathFor("https://example.com/tracker.gif") shouldBe null
    }

    /**
     * Regression test. The server used to refuse every URL that was not on the virtual origin, which
     * would have blocked the `data:` images a book inlines.
     */
    @Test
    fun `only remote schemes count as remote`() {
        NovelEpubAssetServer.isRemote("https://example.com/tracker.gif") shouldBe true
        NovelEpubAssetServer.isRemote("http://example.com/tracker.gif") shouldBe true
        NovelEpubAssetServer.isRemote("data:image/png;base64,AAAA") shouldBe false
        NovelEpubAssetServer.isRemote("about:blank") shouldBe false
        NovelEpubAssetServer.isRemote("file:///sdcard/x.png") shouldBe false
    }

    @Test
    fun `types the formats a chapter actually references`() {
        NovelEpubAssetServer.mimeTypeOf("OEBPS/Text/ch1.xhtml") shouldBe "text/html"
        NovelEpubAssetServer.mimeTypeOf("OEBPS/Styles/main.css") shouldBe "text/css"
        NovelEpubAssetServer.mimeTypeOf("OEBPS/Images/cover.JPG") shouldBe "image/jpeg"
        NovelEpubAssetServer.mimeTypeOf("OEBPS/Images/art.png") shouldBe "image/png"
        NovelEpubAssetServer.mimeTypeOf("OEBPS/Fonts/serif.woff2") shouldBe "font/woff2"
    }

    @Test
    fun `falls back to octet-stream for an unknown extension`() {
        NovelEpubAssetServer.mimeTypeOf("OEBPS/mystery.dat") shouldBe "application/octet-stream"
        NovelEpubAssetServer.mimeTypeOf("noextension") shouldBe "application/octet-stream"
    }
}
