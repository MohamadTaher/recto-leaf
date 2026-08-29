package leaf.novel.ui.reader

import androidx.annotation.ColorInt
import leaf.novel.api.NovelChapterContent
import leaf.novel.ui.reader.setting.NovelReaderStyle

/**
 * The reader's injected stylesheet.
 *
 * What the reader lets you change arrives as a [NovelReaderStyle]; what it does not lives here as a
 * named constant, so promoting one to a preference later is "expose this", not "find this".
 *
 * Every mapping from a preference value to a declaration is integer arithmetic. Preference values
 * are whole numbers and the steps are exact fractions of one, so doing it in doubles would put
 * `1.6000000000000001` in the stylesheet for no gain.
 */
object NovelReaderCss {

    const val FIRST_LINE_INDENT_EM = 0.0

    /**
     * Builds the document the WebView actually loads.
     *
     * The book's own `<head>` styles come first so its structure and emphasis survive; ours comes
     * after and marks colour `!important`, because a book that hardcodes black text is otherwise
     * unreadable on a black reader theme, and there is no per-book override to fix it with.
     */
    fun document(
        content: NovelChapterContent,
        style: NovelReaderStyle,
        @ColorInt backgroundColor: Int,
    ): String {
        val background = backgroundColor.toCssColor()
        val foreground = foregroundFor(backgroundColor).toCssColor()
        val muted = foregroundFor(backgroundColor).withAlpha(ACCENT_ALPHA).toCssColor()

        val fontSizePx = scaledFontSizePx(style)
        val lineHeight = tenths(LINE_HEIGHT_BASE_TENTHS + style.lineSpacing)
        val letterSpacing = hundredths(style.fontSpacing)
        val paragraphSpacing = hundredths(style.paragraphSpacing)

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
              font-weight: ${if (style.bold) "bold" else "normal"};
              font-style: ${if (style.italic) "italic" else "normal"};
              text-decoration: ${if (style.underline) "underline" else "none"};
              text-shadow: ${if (style.shadow) TEXT_SHADOW else "none"};
              -webkit-font-smoothing: ${if (style.antialias) "antialiased" else "auto"};
              letter-spacing: ${letterSpacing}em;
              line-height: $lineHeight;
              margin: 0;
              padding: ${style.marginTop}px ${style.marginRight}px ${style.marginBottom}px ${style.marginLeft}px;
              text-align: ${if (style.justified) "justify" else "left"};
              hyphens: ${if (style.hyphenation) "auto" else "manual"};
              word-break: break-word;
              overflow-wrap: break-word;
            }
            /* Descendants only: including `body` here would beat its own background above. */
            body * { color: $foreground !important; background-color: transparent !important; }
            p { margin: 0 0 ${paragraphSpacing}em; text-indent: ${FIRST_LINE_INDENT_EM}em; }
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

    /**
     * The chosen size nudged by the fine scale, resolved to the one number the stylesheet needs.
     *
     * Done in per-mille so it stays integral, and rounded half-up. A scale of zero multiplies by
     * exactly one, so leaving the fine control alone leaves the chosen size alone.
     */
    private fun scaledFontSizePx(style: NovelReaderStyle): Int =
        (style.fontSizePx * (PER_MILLE + style.fontScale * FONT_SCALE_STEP) + PER_MILLE / 2) / PER_MILLE

    /**
     * Tenths as a plain decimal, for the unitless line height.
     *
     * Unitless rather than a percentage on purpose: a number is inherited as a number and recomputed
     * against each descendant's own font size, where a percentage would inherit one fixed length and
     * crowd any text the book sets larger.
     *
     * Only ever called with the line-height range, which cannot reach zero, so no sign handling.
     */
    private fun tenths(value: Int): String = "${value / 10}.${value % 10}"

    /** Hundredths as a plain decimal, for the em lengths. Letter spacing may be negative. */
    private fun hundredths(value: Int): String {
        val magnitude = if (value < 0) -value else value
        val sign = if (value < 0) "-" else ""
        return "$sign${magnitude / 100}.${(magnitude % 100).toString().padStart(2, '0')}"
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

    /** Soft enough to lift text off the page without smearing it at small sizes. */
    private const val TEXT_SHADOW = "0 1px 2px rgba(0, 0, 0, 0.35)"

    /** A line spacing of 0 is single-spaced; the imported default of 4 lands on 1.6. */
    private const val LINE_HEIGHT_BASE_TENTHS = 12

    /** One step of the fine font scale, in per-mille: 2.5% of the chosen size. */
    private const val FONT_SCALE_STEP = 25
    private const val PER_MILLE = 1000

    private const val ACCENT_ALPHA = 168
    private const val DARK_LUMINANCE_THRESHOLD = 128

    @ColorInt
    private const val READER_TEXT_ON_DARK = 0xFFDEDEDE.toInt()

    @ColorInt
    private const val READER_TEXT_ON_LIGHT = 0xFF1A1A1A.toInt()
}
