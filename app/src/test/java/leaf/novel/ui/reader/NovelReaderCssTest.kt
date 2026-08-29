package leaf.novel.ui.reader

import io.kotest.matchers.shouldBe
import leaf.novel.api.NovelChapterContent
import leaf.novel.ui.reader.setting.NovelReaderStyle
import org.junit.jupiter.api.Test

private const val WHITE = 0xFFFFFFFF.toInt()
private const val BLACK = 0xFF000000.toInt()
private const val MIHON_GRAY = 0xFF2B2B2B.toInt()

private fun style(
    fontSizePx: Int = 18,
    bold: Boolean = false,
    italic: Boolean = false,
    underline: Boolean = false,
    shadow: Boolean = false,
    antialias: Boolean = true,
    justified: Boolean = false,
    hyphenation: Boolean = false,
    paragraphSpacing: Int = 60,
    lineSpacing: Int = 4,
    fontSpacing: Int = 0,
    fontScale: Int = 0,
) = NovelReaderStyle(
    fontSizePx = fontSizePx,
    bold = bold,
    italic = italic,
    underline = underline,
    shadow = shadow,
    antialias = antialias,
    justified = justified,
    hyphenation = hyphenation,
    paragraphSpacing = paragraphSpacing,
    lineSpacing = lineSpacing,
    fontSpacing = fontSpacing,
    fontScale = fontScale,
)

/**
 * The reader's stylesheet has to win the cascade against whatever the book brought with it without
 * discarding it, and pick a foreground its text stays legible against. Both are ordering and
 * arithmetic rather than layout, so they are checked here instead of on screen.
 */
class NovelReaderCssTest {

    private val content = NovelChapterContent(html = "<p>text</p>", head = "", baseUrl = null)

    @Test
    fun `treats black as dark`() {
        NovelReaderCss.isDark(BLACK) shouldBe true
    }

    @Test
    fun `treats white as light`() {
        NovelReaderCss.isDark(WHITE) shouldBe false
    }

    @Test
    fun `treats the reader gray as dark`() {
        NovelReaderCss.isDark(MIHON_GRAY) shouldBe true
    }

    @Test
    fun `picks a light foreground on a dark background`() {
        NovelReaderCss.foregroundFor(BLACK) shouldBe 0xFFDEDEDE.toInt()
    }

    @Test
    fun `picks a dark foreground on a light background`() {
        NovelReaderCss.foregroundFor(WHITE) shouldBe 0xFF1A1A1A.toInt()
    }

    /**
     * Regression test. `body *` used to be written as `body, body *`, which at equal specificity
     * came after the `body` rule and so cancelled the reader's own background with
     * `background-color: transparent`.
     */
    @Test
    fun `the descendant reset does not target body itself`() {
        val document = NovelReaderCss.document(content, style(), backgroundColor = BLACK)

        document.contains("body, body *") shouldBe false
        document.contains("body * { color:") shouldBe true
    }

    @Test
    fun `body keeps a background declared after the book's own head styles`() {
        val styled = NovelChapterContent(
            html = "<p>text</p>",
            head = "<style>body { background: #fff; color: #000 }</style>",
            baseUrl = null,
        )

        val document = NovelReaderCss.document(styled, style(), backgroundColor = BLACK)

        // The book's stylesheet must survive, but ours has to come after it to win the cascade.
        document.indexOf("<style>body { background: #fff") shouldBe document.indexOf(styled.head)
        (document.indexOf(styled.head) < document.indexOf("-webkit-text-size-adjust")) shouldBe true
    }

    @Test
    fun `renders the requested font size`() {
        val document = NovelReaderCss.document(content, style(fontSizePx = 22), backgroundColor = WHITE)
        document.contains("font-size: 22px") shouldBe true
    }

    @Test
    fun `keeps wide content from scrolling the page sideways`() {
        val document = NovelReaderCss.document(content, style(), backgroundColor = WHITE)
        document.contains("max-width: 100%") shouldBe true
        document.contains("overflow-x: auto") shouldBe true
    }

    @Test
    fun `embeds the chapter body`() {
        val document = NovelReaderCss.document(content, style(), backgroundColor = WHITE)
        document.contains("<p>text</p>") shouldBe true
    }

    @Test
    fun `emits every text styling flag when it is on`() {
        val document = NovelReaderCss.document(
            content,
            style(
                bold = true,
                italic = true,
                underline = true,
                shadow = true,
                justified = true,
                hyphenation = true,
            ),
            backgroundColor = WHITE,
        )

        document.contains("font-weight: bold") shouldBe true
        document.contains("font-style: italic") shouldBe true
        document.contains("text-decoration: underline") shouldBe true
        document.contains("text-shadow: 0 1px 2px") shouldBe true
        document.contains("text-align: justify") shouldBe true
        document.contains("hyphens: auto") shouldBe true
    }

    @Test
    fun `emits the off value for every text styling flag by default`() {
        val document = NovelReaderCss.document(content, style(), backgroundColor = WHITE)

        document.contains("font-weight: normal") shouldBe true
        document.contains("font-style: normal") shouldBe true
        document.contains("text-decoration: none") shouldBe true
        document.contains("text-shadow: none") shouldBe true
        document.contains("text-align: left") shouldBe true
        document.contains("hyphens: manual") shouldBe true
    }

    @Test
    fun `smooths text by default and stops when asked`() {
        NovelReaderCss.document(content, style(), backgroundColor = WHITE)
            .contains("-webkit-font-smoothing: antialiased") shouldBe true

        NovelReaderCss.document(content, style(antialias = false), backgroundColor = WHITE)
            .contains("-webkit-font-smoothing: auto") shouldBe true
    }

    /** Justifying the body must not reach the headings, which the stylesheet aligns itself. */
    @Test
    fun `headings keep their own alignment when the body is justified`() {
        val doc = NovelReaderCss.document(content, style(justified = true), backgroundColor = WHITE)

        doc.contains("text-align: justify") shouldBe true
        doc.contains("h1, h2, h3, h4, h5, h6 { text-align: left") shouldBe true
    }

    @Test
    fun `maps line spacing across its range`() {
        fun doc(v: Int) = NovelReaderCss.document(content, style(lineSpacing = v), backgroundColor = WHITE)

        doc(-5).contains("line-height: 0.7") shouldBe true
        doc(4).contains("line-height: 1.6") shouldBe true
        doc(20).contains("line-height: 3.2") shouldBe true
    }

    @Test
    fun `maps paragraph spacing across its range`() {
        fun doc(v: Int) = NovelReaderCss.document(content, style(paragraphSpacing = v), backgroundColor = WHITE)

        doc(0).contains("margin: 0 0 0.00em") shouldBe true
        doc(60).contains("margin: 0 0 0.60em") shouldBe true
        doc(200).contains("margin: 0 0 2.00em") shouldBe true
    }

    /** Font spacing goes below zero to tighten, so the sign has to survive the formatting. */
    @Test
    fun `maps font spacing across its range, negatives included`() {
        fun doc(v: Int) = NovelReaderCss.document(content, style(fontSpacing = v), backgroundColor = WHITE)

        doc(-4).contains("letter-spacing: -0.04em") shouldBe true
        doc(0).contains("letter-spacing: 0.00em") shouldBe true
        doc(20).contains("letter-spacing: 0.20em") shouldBe true
    }

    @Test
    fun `font scale nudges the chosen size and at zero leaves it alone`() {
        fun doc(scale: Int) =
            NovelReaderCss.document(content, style(fontScale = scale), backgroundColor = WHITE)

        doc(0).contains("font-size: 18px") shouldBe true
        doc(-4).contains("font-size: 16px") shouldBe true
        doc(20).contains("font-size: 27px") shouldBe true
    }
}
