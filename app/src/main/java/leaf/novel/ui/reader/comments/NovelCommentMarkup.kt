package leaf.novel.ui.reader.comments

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * Turns a comment's HTML into the handful of spans a comment actually uses.
 *
 * Chapter text goes through a WebView because a chapter is a document with the publisher's own
 * styling to honour. A comment is three lines someone typed, and forty WebViews in a scrolling list
 * would be absurd — so this produces a plain model that Compose draws as an `AnnotatedString`.
 *
 * What survives is what people write comments in: emphasis, links, quotes, code, strikethrough,
 * lists and spoilers. Everything else is unwrapped to its text, which is the safe direction to
 * fail: an unknown tag loses its formatting, never its words.
 *
 * Nothing here trusts the source. It is jsoup parsing into a model with nothing executable in it,
 * and the only links kept are `http`, `https` and `mailto`.
 *
 * Free of Android and Compose types, so the whole thing tests on the JVM.
 */
object NovelCommentMarkup {

    /** Prefix for a list item. The sheet has no list layout, and a bullet reads the same. */
    private const val BULLET = "• "

    private val BLOCKS = setOf(
        "p", "div", "li", "blockquote", "pre",
        "h1", "h2", "h3", "h4", "h5", "h6",
    )

    /**
     * The spans of [html], in document order.
     *
     * [baseUrl] resolves relative links, which a site that returns a bare `/u/someone` needs; a
     * fragment with relative links and no base simply loses them.
     *
     * Adjacent spans with identical styling are merged, because a site that wraps every other word
     * in its own `<span>` would otherwise produce a hundred-element list for one sentence.
     */
    fun parse(html: String, baseUrl: String? = null): List<NovelCommentSpan> {
        val body = Jsoup.parseBodyFragment(html, baseUrl.orEmpty()).body()
        // Dropped outright rather than unwrapped: their text is code, not prose, and unwrapping
        // would paste a stylesheet into the middle of someone's comment.
        body.select("script, style, noscript, template, iframe").remove()

        val spans = mutableListOf<NovelCommentSpan>()
        body.childNodes().forEach { walk(it, Style(), spans) }

        val merged = mutableListOf<NovelCommentSpan>()
        spans.forEach { span ->
            if (span.text.isEmpty() && span.image == null) return@forEach
            val last = merged.lastOrNull()
            if (last != null && last.sameStyleAs(span)) {
                merged[merged.lastIndex] = last.copy(text = last.text + span.text)
            } else {
                merged += span
            }
        }

        // A fragment that opens or closes with an empty <p> is common enough to be worth trimming;
        // left alone it draws a blank line above or below every one of those comments.
        while (merged.isNotEmpty() && merged.first().isBlank()) merged.removeAt(0)
        // Pictures are drawn beneath the words, so text is trimmed as if they were not there: a line
        // break that only led up to a picture would otherwise leave a blank line above it.
        val lastWords = merged.indexOfLast { it.image == null && it.text.isNotBlank() }
        return merged.mapIndexedNotNull { index, span ->
            when {
                span.image != null || index < lastWords -> span
                index == lastWords -> span.copy(text = span.text.trimEnd())
                else -> null
            }
        }
    }

    /** The comment as one string, for copying, sharing and searching. */
    fun plainText(html: String, baseUrl: String? = null): String =
        parse(html, baseUrl).joinToString("") { it.text }.trim()

    private fun walk(node: Node, style: Style, out: MutableList<NovelCommentSpan>) {
        when (node) {
            is TextNode -> {
                val text = node.wholeText.replace(' ', ' ')
                if (text.isNotEmpty()) out += style.span(text)
            }
            is Element -> {
                val tag = node.normalName()
                if (tag == "br") {
                    out += style.span("\n")
                    return
                }
                // A GIF from a picker is a short video now as often as it is a GIF.
                if (tag == "img" || tag == "video") {
                    node.imageSource()?.let { out += style.span("").copy(image = it) }
                    return
                }

                // A block that follows content starts on its own line. Done on the way in rather
                // than on the way out so that nested blocks do not each add one of their own.
                if (tag in BLOCKS && out.isNotEmpty() && !out.last().text.endsWith("\n")) {
                    out += style.span("\n")
                }

                val nested = style.with(node)
                if (tag == "li") out += nested.span(BULLET)
                node.childNodes().forEach { walk(it, nested, out) }
            }
            else -> Unit
        }
    }

    private data class Style(
        val bold: Boolean = false,
        val italic: Boolean = false,
        val strikethrough: Boolean = false,
        val code: Boolean = false,
        val quote: Boolean = false,
        val spoiler: Boolean = false,
        val link: String? = null,
    ) {
        fun span(text: String) = NovelCommentSpan(
            text = text,
            bold = bold,
            italic = italic,
            strikethrough = strikethrough,
            code = code,
            quote = quote,
            spoiler = spoiler,
            link = link,
        )

        fun with(element: Element): Style {
            val inherited = if (element.isSpoilerMarker()) copy(spoiler = true) else this
            return when (element.normalName()) {
                "b", "strong" -> inherited.copy(bold = true)
                "i", "em", "cite" -> inherited.copy(italic = true)
                "s", "del", "strike" -> inherited.copy(strikethrough = true)
                "code", "kbd", "samp", "tt", "pre" -> inherited.copy(code = true)
                "blockquote", "q" -> inherited.copy(quote = true)
                "a" -> inherited.copy(link = element.linkTarget())
                else -> inherited
            }
        }
    }
}

/**
 * Whether an element marks a spoiler.
 *
 * Matched on the class name, because that is the one thing the conventions share — `spoiler`,
 * `spoiler-content`, `js-spoiler`, `md-spoiler-text` — across every comment system that has the
 * feature. Guessing wrong blurs a paragraph that did not need it, which the reader undoes with a
 * tap; not guessing gives away a plot twist, which nothing undoes.
 */
private fun Element.isSpoilerMarker(): Boolean =
    hasAttr("data-spoiler") || classNames().any { it.contains("spoiler", ignoreCase = true) }

/** The absolute target of an `<a>`, or null when it is relative, empty or not a safe scheme. */
private fun Element.linkTarget(): String? {
    val href = attr("abs:href").ifEmpty { attr("href") }
    return href.takeIf { url -> SAFE_SCHEMES.any { url.startsWith(it, ignoreCase = true) } }
}

private val SAFE_SCHEMES = listOf("http://", "https://", "mailto:")

/**
 * The absolute address of an `<img>`, or null when there is no web address to load.
 *
 * `data-src` first: a lazy-loading comment system puts a placeholder in `src` and the picture there.
 */
private fun Element.imageSource(): String? =
    sequenceOf("data-src", "data-lazy-src", "src")
        .map { absUrl(it) }
        .firstOrNull { url -> IMAGE_SCHEMES.any { url.startsWith(it, ignoreCase = true) } }

private val IMAGE_SCHEMES = listOf("http://", "https://")

/**
 * A run of comment text and how it is drawn.
 *
 * Flags rather than a nested tree: a comment's formatting does not nest meaningfully past the
 * combinations here, and a flat list is what `AnnotatedString` wants anyway.
 */
data class NovelCommentSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strikethrough: Boolean = false,
    val code: Boolean = false,
    val quote: Boolean = false,
    /** Drawn hidden until tapped. */
    val spoiler: Boolean = false,
    /** Absolute, and only ever `http`, `https` or `mailto`. */
    val link: String? = null,
    /**
     * A picture or a video GIF rather than text, with an empty [text]: absolute, and only ever
     * `http` or `https`.
     *
     * The sheet draws a comment's pictures beneath its words, since a picture cannot sit inside a
     * line of an `AnnotatedString` without knowing its size first.
     */
    val image: String? = null,
) {
    fun isBlank(): Boolean = image == null && text.isBlank()

    fun sameStyleAs(other: NovelCommentSpan): Boolean =
        image == null &&
            other.image == null &&
            bold == other.bold &&
            italic == other.italic &&
            strikethrough == other.strikethrough &&
            code == other.code &&
            quote == other.quote &&
            spoiler == other.spoiler &&
            link == other.link
}
