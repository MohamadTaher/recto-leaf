package leaf.novel.ui.reader

import io.kotest.matchers.shouldBe
import leaf.novel.api.NovelChapterContent
import leaf.novel.ui.reader.setting.NovelLinkColor
import leaf.novel.ui.reader.setting.NovelReaderFont
import leaf.novel.ui.reader.setting.NovelReaderStyle
import leaf.novel.ui.reader.setting.NovelReaderTheme
import org.junit.jupiter.api.Test

private const val WHITE = 0xFFFFFFFF.toInt()
private const val BLACK = 0xFF000000.toInt()
private const val MIHON_GRAY = 0xFF2B2B2B.toInt()

/** What the screen resolves before calling: Follow Mihon derives the foreground, as it always did. */
private fun colors(background: Int) = NovelReaderTheme.FOLLOW_MIHON.colors(background)

private fun style(
    fontSizePx: Int = 18,
    font: NovelReaderFont = NovelReaderFont.SYSTEM,
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
    marginLeft: Int = 14,
    marginRight: Int = 10,
    marginTop: Int = 3,
    marginBottom: Int = 3,
    highlightFirstWord: Boolean = false,
    highlightInitialChars: Boolean = false,
    indentFirstLine: Boolean = true,
    trimBlankLines: Boolean = false,
    linkColor: NovelLinkColor = NovelLinkColor.DEFAULT,
    noteColor: NovelLinkColor = NovelLinkColor.DEFAULT,
    disableBookCss: Boolean = true,
    useBookFonts: Boolean = false,
    inlineFootnotes: Boolean = true,
    printPageNumbers: Boolean = false,
) = NovelReaderStyle(
    fontSizePx = fontSizePx,
    font = font,
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
    marginLeft = marginLeft,
    marginRight = marginRight,
    marginTop = marginTop,
    marginBottom = marginBottom,
    highlightFirstWord = highlightFirstWord,
    highlightInitialChars = highlightInitialChars,
    indentFirstLine = indentFirstLine,
    trimBlankLines = trimBlankLines,
    linkColor = linkColor,
    noteColor = noteColor,
    disableBookCss = disableBookCss,
    useBookFonts = useBookFonts,
    inlineFootnotes = inlineFootnotes,
    printPageNumbers = printPageNumbers,
)

/**
 * The reader's stylesheet has to win the cascade against whatever the book brought with it without
 * discarding it, and pick a foreground its text stays legible against. Both are ordering and
 * arithmetic rather than layout, so they are checked here instead of on screen.
 */
class NovelReaderCssTest {

    private val content = NovelChapterContent(html = "<p>text</p>", head = "", baseUrl = null)

    private val styledContent = NovelChapterContent(
        html = "<p>text</p>",
        head = "<style>body { background: #fff; color: #000 }</style>",
        baseUrl = null,
    )

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
        val document = NovelReaderCss.document(content, style(), colors = colors(BLACK))

        document.contains("body, body *") shouldBe false
        document.contains("body * { color:") shouldBe true
    }

    @Test
    fun `body keeps a background declared after the book's own head styles`() {
        val document = NovelReaderCss.document(
            styledContent,
            style(disableBookCss = false),
            colors = colors(BLACK),
        )

        // The book's stylesheet must survive, but ours has to come after it to win the cascade.
        (document.indexOf(styledContent.head) > 0) shouldBe true
        (document.indexOf(styledContent.head) < document.indexOf("-webkit-text-size-adjust")) shouldBe true
    }

    @Test
    fun `drops the book's own head when its CSS is disabled`() {
        val document = NovelReaderCss.document(styledContent, style(), colors = colors(BLACK))

        document.contains(styledContent.head) shouldBe false
        // Ours is still there; only the book's went.
        document.contains("-webkit-text-size-adjust") shouldBe true
        document.contains("<p>text</p>") shouldBe true
    }

    /**
     * A book's rules land on `p`, which beats ours on `body` on specificity alone — so a chosen
     * face only actually wins if it says so.
     */
    @Test
    fun `forces a chosen face over whatever the book asks for`() {
        val document = NovelReaderCss.document(
            content,
            style(font = NovelReaderFont.SERIF),
            colors = colors(WHITE),
        )

        document.contains("font-family: serif !important;") shouldBe true
    }

    @Test
    fun `has no opinion on the face when the book's fonts are wanted`() {
        val document = NovelReaderCss.document(
            content,
            style(font = NovelReaderFont.SERIF, useBookFonts = true),
            colors = colors(WHITE),
        )

        document.contains("font-family:") shouldBe false
    }

    /**
     * The preview keeps the book's head whatever the CSS setting says, and drops the descendant
     * colour reset — that reset is exactly what hides the publisher's own colours.
     */
    @Test
    fun `the publisher preview keeps the book's formatting and drops ours`() {
        val document = NovelReaderCss.document(
            styledContent,
            style(marginLeft = 99, justified = true),
            colors = colors(WHITE),
            publisherFormatting = true,
        )

        document.contains(styledContent.head) shouldBe true
        document.contains("body * { color:") shouldBe false
        document.contains("padding: 3px") shouldBe false
        document.contains("text-align: justify") shouldBe false
        document.contains("<p>text</p>") shouldBe true
    }

    @Test
    fun `the publisher preview still forces the reader's background`() {
        val document = NovelReaderCss.document(
            styledContent,
            style(),
            colors = colors(BLACK),
            publisherFormatting = true,
        )

        document.contains("background: rgba(0, 0, 0, 1.0) !important") shouldBe true
        // Not important, unlike everywhere else: a book showing its own colours is the point.
        document.contains("color: rgba(222, 222, 222, 1.0);") shouldBe true
    }

    @Test
    fun `styles a folded-in note in its own colour`() {
        val document = NovelReaderCss.document(
            content,
            style(noteColor = NovelLinkColor.AMBER),
            colors = colors(WHITE),
        )

        document.contains("aside.${NovelEpubMarkup.NOTE_CLASS}") shouldBe true
        document.contains("color: rgba(214, 158, 46, 1.0) !important") shouldBe true
    }

    @Test
    fun `renders the requested font size`() {
        val document = NovelReaderCss.document(content, style(fontSizePx = 22), colors = colors(WHITE))
        document.contains("font-size: 22px") shouldBe true
    }

    @Test
    fun `leaves the book font alone by default`() {
        val document = NovelReaderCss.document(content, style(), colors = colors(WHITE))

        document.contains("font-family:") shouldBe false
    }

    @Test
    fun `keeps wide content from scrolling the page sideways`() {
        val document = NovelReaderCss.document(content, style(), colors = colors(WHITE))
        document.contains("max-width: 100%") shouldBe true
        document.contains("overflow-x: auto") shouldBe true
    }

    @Test
    fun `embeds the chapter body`() {
        val document = NovelReaderCss.document(content, style(), colors = colors(WHITE))
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
            colors = colors(WHITE),
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
        val document = NovelReaderCss.document(content, style(), colors = colors(WHITE))

        document.contains("font-weight: normal") shouldBe true
        document.contains("font-style: normal") shouldBe true
        document.contains("text-decoration: none") shouldBe true
        document.contains("text-shadow: none") shouldBe true
        document.contains("text-align: start") shouldBe true
        document.contains("hyphens: manual") shouldBe true
    }

    @Test
    fun `smooths text by default and stops when asked`() {
        NovelReaderCss.document(content, style(), colors = colors(WHITE))
            .contains("-webkit-font-smoothing: antialiased") shouldBe true

        NovelReaderCss.document(content, style(antialias = false), colors = colors(WHITE))
            .contains("-webkit-font-smoothing: auto") shouldBe true
    }

    /** Justifying the body must not reach the headings, which the stylesheet aligns itself. */
    @Test
    fun `headings keep their own alignment when the body is justified`() {
        val doc = NovelReaderCss.document(content, style(justified = true), colors = colors(WHITE))

        doc.contains("text-align: justify") shouldBe true
        doc.contains("h1, h2, h3, h4, h5, h6 { text-align: start") shouldBe true
    }

    @Test
    fun `maps line spacing across its range`() {
        fun doc(v: Int) = NovelReaderCss.document(content, style(lineSpacing = v), colors = colors(WHITE))

        doc(-5).contains("line-height: 0.7") shouldBe true
        doc(4).contains("line-height: 1.6") shouldBe true
        doc(20).contains("line-height: 3.2") shouldBe true
    }

    @Test
    fun `maps paragraph spacing across its range`() {
        fun doc(v: Int) = NovelReaderCss.document(content, style(paragraphSpacing = v), colors = colors(WHITE))

        doc(0).contains("margin: 0 0 0.00em") shouldBe true
        doc(60).contains("margin: 0 0 0.60em") shouldBe true
        doc(200).contains("margin: 0 0 2.00em") shouldBe true
    }

    /** Font spacing goes below zero to tighten, so the sign has to survive the formatting. */
    @Test
    fun `maps font spacing across its range, negatives included`() {
        fun doc(v: Int) = NovelReaderCss.document(content, style(fontSpacing = v), colors = colors(WHITE))

        doc(-4).contains("letter-spacing: -0.04em") shouldBe true
        doc(0).contains("letter-spacing: 0.00em") shouldBe true
        doc(20).contains("letter-spacing: 0.20em") shouldBe true
    }

    @Test
    fun `font scale nudges the chosen size and at zero leaves it alone`() {
        fun doc(scale: Int) =
            NovelReaderCss.document(content, style(fontScale = scale), colors = colors(WHITE))

        doc(0).contains("font-size: 18px") shouldBe true
        doc(-4).contains("font-size: 16px") shouldBe true
        doc(20).contains("font-size: 27px") shouldBe true
    }

    /**
     * CSS shorthand runs top, right, bottom, left. Four distinct values rather than the defaults,
     * because a swapped pair renders plausibly and is invisible in any test that reuses a number.
     */
    @Test
    fun `lays the four margins out in shorthand order`() {
        val document = NovelReaderCss.document(
            content,
            style(marginLeft = 1, marginRight = 2, marginTop = 3, marginBottom = 4),
            colors = colors(WHITE),
        )

        document.contains("padding: 3px 2px 4px 1px") shouldBe true
    }

    @Test
    fun `defaults to the imported asymmetric margins`() {
        val document = NovelReaderCss.document(content, style(), colors = colors(WHITE))

        document.contains("padding: 3px 10px 3px 14px") shouldBe true
    }

    @Test
    fun `allows a margin of zero on every side`() {
        val document = NovelReaderCss.document(
            content,
            style(marginLeft = 0, marginRight = 0, marginTop = 0, marginBottom = 0),
            colors = colors(WHITE),
        )

        document.contains("padding: 0px 0px 0px 0px") shouldBe true
    }

    /**
     * The slider cannot reach here, but a preference restored from a backup can, and the tenths
     * formatting would otherwise emit "0.-8" and take the whole declaration down with it.
     */
    @Test
    fun `clamps a line spacing from below the slider range`() {
        val document = NovelReaderCss.document(content, style(lineSpacing = -40), colors = colors(WHITE))

        document.contains("line-height: 0.7") shouldBe true
        document.contains("line-height: 0.-") shouldBe false
    }

    /**
     * The default link colour is the muted foreground the reader used before it was a setting, and
     * it has to keep following the theme. The hr rule and the selection highlight take the same
     * colour, so they are the check that a chosen colour has not leaked past the links.
     */
    @Test
    fun `leaves links to the theme by default`() {
        val onWhite = NovelReaderCss.document(content, style(), colors = colors(WHITE))
        val onBlack = NovelReaderCss.document(content, style(), colors = colors(BLACK))

        onWhite.contains("a { color: rgba(26, 26, 26, ") shouldBe true
        onBlack.contains("a { color: rgba(222, 222, 222, ") shouldBe true
    }

    @Test
    fun `draws links in a chosen colour, whatever the theme`() {
        val onWhite = NovelReaderCss.document(content, style(linkColor = NovelLinkColor.TEAL), colors = colors(WHITE))
        val onBlack = NovelReaderCss.document(content, style(linkColor = NovelLinkColor.TEAL), colors = colors(BLACK))

        onWhite.contains("a { color: rgba(38, 166, 154, 1.0) !important; }") shouldBe true
        onBlack.contains("a { color: rgba(38, 166, 154, 1.0) !important; }") shouldBe true
    }

    @Test
    fun `leaves the page furniture on the theme colour when links are not`() {
        val document = NovelReaderCss.document(content, style(linkColor = NovelLinkColor.RED), colors = colors(WHITE))

        document.contains("border-top: 1px solid rgba(26, 26, 26, ") shouldBe true
        document.contains("::selection { background: rgba(26, 26, 26, ") shouldBe true
    }
}
