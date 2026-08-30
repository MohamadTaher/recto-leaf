package leaf.novel.ui.reader

import androidx.annotation.ColorInt
import leaf.novel.api.NovelChapterContent
import leaf.novel.ui.reader.setting.NovelReaderColors
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
        colors: NovelReaderColors,
        publisherFormatting: Boolean = false,
    ): String {
        if (publisherFormatting) return publisherDocument(content, colors)

        val background = colors.background.toCssColor()
        val foreground = colors.foreground.toCssColor()
        val muted = colors.foreground.withAlpha(ACCENT_ALPHA).toCssColor()
        // A chosen colour is a fixed one; the default keeps following whatever the theme reads as.
        val link = style.linkColor.argb?.toCssColor() ?: muted
        val note = style.noteColor.argb?.toCssColor() ?: muted
        // Asking for the book's fonts means having no opinion of our own, so the declaration is
        // dropped rather than overridden — and when we do have one it has to beat the book's rules
        // on `p`, which are more specific than ours on `body`.
        val fontFamily = style.font.cssFamily
            ?.takeUnless { style.useBookFonts }
            ?.let { "font-family: $it !important;" }
            .orEmpty()
        val bookHead = if (style.disableBookCss) "" else content.head
        // Centring is a block-level trick, so it has to be part of the same rule as the sizing.
        val imageRules = style.imageSize.css +
            if (style.centerImages) " display: block; margin-left: auto; margin-right: auto;" else ""

        val fontSizePx = scaledFontSizePx(style)
        val lineHeight = tenths(
            (LINE_HEIGHT_BASE_TENTHS + style.lineSpacing).coerceAtLeast(MIN_LINE_HEIGHT_TENTHS),
        )
        val letterSpacing = hundredths(style.fontSpacing)
        val paragraphSpacing = hundredths(style.paragraphSpacing)
        // The reading aids rewrite the book's own markup, so they run before it is embedded.
        val firstLineIndent = if (style.indentFirstLine) FIRST_LINE_INDENT_EM else NO_INDENT_EM
        val chapterHtml = content.html
            .let { if (style.trimBlankLines) NovelHtmlSanitizer.trimBlankLines(it) else it }
            .let { if (style.inlineFootnotes) NovelEpubMarkup.inlineFootnotes(it) else it }
            .let { if (style.printPageNumbers) NovelEpubMarkup.showPageNumbers(it) else it }
            .let { if (style.highlightFirstWord) NovelTextEmphasis.firstWordOfSentence(it) else it }
            .let { if (style.highlightInitialChars) NovelTextEmphasis.initialCharsOfWord(it) else it }

        return """
            <!DOCTYPE html>
            <html>
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
            $bookHead
            <style>
            :root { color-scheme: ${if (isDark(colors.background)) "dark" else "light"}; }
            html { -webkit-text-size-adjust: 100%; }
            body {
              background: $background !important;
              color: $foreground !important;
              font-size: ${fontSizePx}px;
              $fontFamily
              font-weight: ${if (style.bold) "bold" else "normal"};
              font-style: ${if (style.italic) "italic" else "normal"};
              text-decoration: ${if (style.underline) "underline" else "none"};
              text-shadow: ${if (style.shadow) TEXT_SHADOW else "none"};
              -webkit-font-smoothing: ${if (style.antialias) "antialiased" else "auto"};
              letter-spacing: ${letterSpacing}em;
              line-height: $lineHeight;
              margin: 0;
              padding: ${style.marginTop}px ${style.marginRight}px ${style.marginBottom}px ${style.marginLeft}px;
              text-align: ${if (style.justified) "justify" else "start"};
              hyphens: ${if (style.hyphenation) "auto" else "manual"};
              word-break: break-word;
              overflow-wrap: break-word;
            }
            /* Descendants only: including `body` here would beat its own background above. */
            body * { color: $foreground !important; background-color: transparent !important; }
            p { margin: 0 0 ${paragraphSpacing}em; text-indent: ${firstLineIndent}em; }
            h1, h2, h3, h4, h5, h6 { text-align: start; line-height: 1.3; }
            img, svg, video { $imageRules }
            pre, table { overflow-x: auto; display: block; max-width: 100%; }
            hr { border: none; border-top: 1px solid $muted; }
            a { color: $link !important; }
            aside.${NovelEpubMarkup.NOTE_CLASS} {
              color: $note !important;
              font-size: 0.9em;
              margin: 0 0 ${paragraphSpacing}em;
              padding-left: 0.9em;
              border-left: 2px solid $note;
              text-indent: 0;
            }
            .${NovelEpubMarkup.PAGE_CLASS} { color: $muted !important; font-size: 0.7em; vertical-align: super; }
            ::selection { background: $muted; }
            </style>
            </head>
            <body>
            $chapterHtml
            </body>
            </html>
        """.trimIndent()
    }

    /**
     * The chapter as its publisher laid it out, for the preview.
     *
     * The book's own head is kept whatever the CSS setting says — that is the whole point — and the
     * reader's stylesheet shrinks to the two colours that keep the page from being white-on-white
     * against the reader's background. The text colour is deliberately *not* `!important` here,
     * where everywhere else it is: a book that sets its own colours is showing them.
     */
    private fun publisherDocument(content: NovelChapterContent, colors: NovelReaderColors): String {
        return """
            <!DOCTYPE html>
            <html>
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
            ${content.head}
            <style>
            :root { color-scheme: ${if (isDark(colors.background)) "dark" else "light"}; }
            html { -webkit-text-size-adjust: 100%; }
            body { background: ${colors.background.toCssColor()} !important; color: ${colors.foreground.toCssColor()}; }
            img, svg, video { max-width: 100%; height: auto; }
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
     * The caller clamps before calling, so this never sees a negative and never has to format one.
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

    /** The usual novel indent: enough to see, not enough to notice. */
    private const val FIRST_LINE_INDENT_EM = "1.2"
    private const val NO_INDENT_EM = "0.0"

    /** Soft enough to lift text off the page without smearing it at small sizes. */
    private const val TEXT_SHADOW = "0 1px 2px rgba(0, 0, 0, 0.35)"

    /** A line spacing of 0 is single-spaced; the imported default of 4 lands on 1.6. */
    private const val LINE_HEIGHT_BASE_TENTHS = 12

    /**
     * The floor the line height is clamped to.
     *
     * The slider cannot go below it, but a preference restored from a backup or written by an
     * older range can, and an unclamped negative would format as "0.-8" and take the whole
     * declaration down with it.
     */
    private const val MIN_LINE_HEIGHT_TENTHS = 7

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
