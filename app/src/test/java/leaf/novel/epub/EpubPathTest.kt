package leaf.novel.epub

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Every chapter URL in the database comes out of [EpubPath.resolve], and it replaced upstream's
 * `File(...).canonicalPath` approach, so the arithmetic is worth pinning down precisely.
 */
class EpubPathTest {

    private val forward = EpubPath.FORWARD_SLASH
    private val backslash = EpubPath.BACKSLASH

    @Test
    fun `resolves a sibling reference against the opf directory`() {
        EpubPath.resolve("OEBPS", "Text/ch1.xhtml", forward) shouldBe "OEBPS/Text/ch1.xhtml"
    }

    @Test
    fun `resolves a parent reference`() {
        EpubPath.resolve("OEBPS/Text", "../Images/cover.jpg", forward) shouldBe "OEBPS/Images/cover.jpg"
    }

    @Test
    fun `resolves a current-directory reference`() {
        EpubPath.resolve("OEBPS/Text", "./ch1.xhtml", forward) shouldBe "OEBPS/Text/ch1.xhtml"
    }

    @Test
    fun `treats an empty base as the archive root`() {
        EpubPath.resolve("", "content.opf", forward) shouldBe "content.opf"
    }

    @Test
    fun `strips the leading separator from an absolute href`() {
        EpubPath.resolve("OEBPS", "/Text/ch1.xhtml", forward) shouldBe "Text/ch1.xhtml"
    }

    @Test
    fun `does not walk above the archive root`() {
        EpubPath.resolve("OEBPS", "../../../etc/passwd", forward) shouldBe "etc/passwd"
    }

    @Test
    fun `emits the archive separator even when the href uses the other one`() {
        EpubPath.resolve("OEBPS", "Text/ch1.xhtml", backslash) shouldBe "OEBPS\\Text\\ch1.xhtml"
    }

    @Test
    fun `accepts a base that already uses the archive separator`() {
        EpubPath.resolve("OEBPS\\Text", "ch1.xhtml", backslash) shouldBe "OEBPS\\Text\\ch1.xhtml"
    }

    @Test
    fun `drops the fragment from a href`() {
        EpubPath.resolve("OEBPS", "Text/ch1.xhtml#part2", forward) shouldBe "OEBPS/Text/ch1.xhtml"
    }

    @Test
    fun `percent-decodes a href`() {
        EpubPath.resolve("OEBPS", "Text/Chapter%201.xhtml", forward) shouldBe "OEBPS/Text/Chapter 1.xhtml"
    }

    @Test
    fun `collapses redundant separators`() {
        EpubPath.resolve("OEBPS//Text", "//ch1.xhtml", forward) shouldBe "ch1.xhtml"
    }

    @Test
    fun `parentOf returns the containing directory`() {
        EpubPath.parentOf("OEBPS/Text/ch1.xhtml", forward) shouldBe "OEBPS/Text"
    }

    @Test
    fun `parentOf returns empty at the archive root`() {
        EpubPath.parentOf("content.opf", forward) shouldBe ""
    }

    @Test
    fun `withSeparator rewrites a literal to the archive separator`() {
        EpubPath.withSeparator("META-INF/container.xml", backslash) shouldBe "META-INF\\container.xml"
        EpubPath.withSeparator("META-INF/container.xml", forward) shouldBe "META-INF/container.xml"
    }

    @Test
    fun `percentDecode leaves an unescaped string untouched`() {
        EpubPath.percentDecode("Text/ch1.xhtml") shouldBe "Text/ch1.xhtml"
    }

    @Test
    fun `percentDecode does not treat plus as a space`() {
        // URLDecoder would return "C  Programming"; a filename containing '+' must survive.
        EpubPath.percentDecode("C++%20Programming") shouldBe "C++ Programming"
    }

    @Test
    fun `percentDecode handles multi-byte utf8 escapes`() {
        EpubPath.percentDecode("%E5%BA%8F%E7%AB%A0.xhtml") shouldBe "序章.xhtml"
    }

    @Test
    fun `percentDecode keeps a malformed escape verbatim`() {
        // A replacement character could never match an archive entry; a literal '%' might.
        EpubPath.percentDecode("100%_done.xhtml") shouldBe "100%_done.xhtml"
        EpubPath.percentDecode("trailing%") shouldBe "trailing%"
        EpubPath.percentDecode("short%2") shouldBe "short%2"
    }

    @Test
    fun `percentDecode accepts both hex cases`() {
        EpubPath.percentDecode("%2f%2F") shouldBe "//"
    }
}
