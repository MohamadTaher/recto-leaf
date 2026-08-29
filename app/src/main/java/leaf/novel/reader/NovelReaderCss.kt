package leaf.novel.reader

import androidx.annotation.ColorInt

/**
 * The reader's injected stylesheet.
 *
 * Everything the MVP hardcodes lives here as a named constant, so promoting any of it to a
 * preference later is "expose this", not "find this" — roadmap item R1 depends on that.
 */
object NovelReaderCss {

    const val LINE_HEIGHT = 1.6
    const val SIDE_MARGIN_PX = 16
    const val TOP_MARGIN_PX = 24
    const val PARAGRAPH_SPACING_EM = 0.8
    const val TEXT_ALIGN = "justify"
    const val FIRST_LINE_INDENT_EM = 0.0

    /**
     * Builds the document the WebView actually loads.
     *
     * The book's own `<head>` styles come first so its structure and emphasis survive; ours comes
     * after and marks colour `!important`, because a book that hardcodes black text is otherwise
     * unreadable on a black reader theme and D10 leaves no per-book override to fix it with.
     */
    fun document(
        content: NovelChapterContent,
        fontSizePx: Int,
        @ColorInt backgroundColor: Int,
    ): String {
        val background = backgroundColor.toCssColor()
        val foreground = foregroundFor(backgroundColor).toCssColor()
        val muted = foregroundFor(backgroundColor).withAlpha(ACCENT_ALPHA).toCssColor()

        return """
            <!DOCTYPE html>
            <html>
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
            ${content.head}
            <style>
            :root { color-scheme: ${if (isDark(backgroundColor)) "dark" else "light"}; }
            html { -webkit-text-size-adjust: 100%; }
            body {
              background: $background !important;
              color: $foreground !important;
              font-size: ${fontSizePx}px;
              line-height: $LINE_HEIGHT;
              margin: 0;
              padding: ${TOP_MARGIN_PX}px ${SIDE_MARGIN_PX}px;
              text-align: $TEXT_ALIGN;
              hyphens: auto;
              word-break: break-word;
              overflow-wrap: break-word;
            }
            /* Descendants only: including `body` here would beat its own background above. */
            body * { color: $foreground !important; background-color: transparent !important; }
            p { margin: 0 0 ${PARAGRAPH_SPACING_EM}em; text-indent: ${FIRST_LINE_INDENT_EM}em; }
            h1, h2, h3, h4, h5, h6 { text-align: left; line-height: 1.3; }
            img, svg, video { max-width: 100%; height: auto; }
            pre, table { overflow-x: auto; display: block; max-width: 100%; }
            hr { border: none; border-top: 1px solid $muted; }
            a { color: $muted !important; }
            ::selection { background: $muted; }
            </style>
            </head>
            <body>
            ${content.html}
            </body>
            </html>
        """.trimIndent()
    }

    /** Mirrors how the manga reader picks its own foreground: white on dark, black on light. */
    @ColorInt
    fun foregroundFor(@ColorInt backgroundColor: Int): Int =
        if (isDark(backgroundColor)) READER_TEXT_ON_DARK else READER_TEXT_ON_LIGHT

    fun isDark(@ColorInt color: Int): Boolean {
        val luminance = 0.299 * color.red() + 0.587 * color.green() + 0.114 * color.blue()
        return luminance < DARK_LUMINANCE_THRESHOLD
    }

    // The same arithmetic android.graphics.Color performs, done here so the whole object stays
    // free of Android types and therefore testable on the JVM.
    private fun Int.alpha(): Int = (this ushr 24) and 0xFF
    private fun Int.red(): Int = (this shr 16) and 0xFF
    private fun Int.green(): Int = (this shr 8) and 0xFF
    private fun Int.blue(): Int = this and 0xFF

    private fun Int.withAlpha(alpha: Int): Int = (this and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)

    private fun Int.toCssColor(): String =
        "rgba(${red()}, ${green()}, ${blue()}, ${alpha() / 255f})"

    private const val ACCENT_ALPHA = 168
    private const val DARK_LUMINANCE_THRESHOLD = 128

    @ColorInt
    private const val READER_TEXT_ON_DARK = 0xFFDEDEDE.toInt()

    @ColorInt
    private const val READER_TEXT_ON_LIGHT = 0xFF1A1A1A.toInt()
}
