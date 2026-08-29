package leaf.novel.ui.reader

import io.kotest.matchers.shouldBe
import leaf.novel.api.NovelChapterContent
import org.junit.jupiter.api.Test

private const val WHITE = 0xFFFFFFFF.toInt()
private const val BLACK = 0xFF000000.toInt()
private const val MIHON_GRAY = 0xFF2B2B2B.toInt()

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
        val document = NovelReaderCss.document(content, fontSizePx = 18, backgroundColor = BLACK)

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

        val document = NovelReaderCss.document(styled, fontSizePx = 18, backgroundColor = BLACK)

        // The book's stylesheet must survive, but ours has to come after it to win the cascade.
        document.indexOf("<style>body { background: #fff") shouldBe document.indexOf(styled.head)
        (document.indexOf(styled.head) < document.indexOf("-webkit-text-size-adjust")) shouldBe true
    }

    @Test
    fun `renders the requested font size`() {
        val document = NovelReaderCss.document(content, fontSizePx = 22, backgroundColor = WHITE)
        document.contains("font-size: 22px") shouldBe true
    }

    @Test
    fun `keeps wide content from scrolling the page sideways`() {
        val document = NovelReaderCss.document(content, fontSizePx = 18, backgroundColor = WHITE)
        document.contains("max-width: 100%") shouldBe true
        document.contains("overflow-x: auto") shouldBe true
    }

    @Test
    fun `embeds the chapter body`() {
        val document = NovelReaderCss.document(content, fontSizePx = 18, backgroundColor = WHITE)
        document.contains("<p>text</p>") shouldBe true
    }
}
