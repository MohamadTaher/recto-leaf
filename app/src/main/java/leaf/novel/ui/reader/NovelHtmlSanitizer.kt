package leaf.novel.ui.reader

import leaf.novel.ui.reader.loader.NovelEpubAssetServer
import leaf.novel.ui.reader.loader.VIRTUAL_ORIGIN
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.safety.Safelist

/**
 * Makes third-party chapter HTML safe to hand to the reader's WebView.
 *
 * An extension is code the app did not compile, parsing a page the app does not control, and its
 * output is rendered in a WebView. Extensions are expected to strip their own site's furniture —
 * they know it best — but the app cannot depend on that having happened.
 *
 * The filtering itself is jsoup's [Safelist], which is already a dependency and far better tested
 * than a hand-written pass: it drops every element and attribute not on the allowed list (so
 * `script`, `iframe`, `style`, forms, `on*` handlers and `javascript:` URLs all go), and resolves
 * what remains against the base URL. Only the image rewrite below is ours, because no library knows
 * about the reader's virtual origin.
 *
 * Kept free of Android types so it can be tested on the JVM.
 *
 * Note what this is *not* relied upon for. `NovelChapterWebView` disables book scripts with its
 * document policy, disables file and content access, and sets `blockNetworkLoads`. Sanitising as
 * well means a bug in any one of those settings is not on its own enough to matter.
 */
object NovelHtmlSanitizer {

    private val SAFELIST: Safelist = Safelist.relaxed()
        // Images the source inlined directly. They are already local and cannot reach the network,
        // and `relaxed` would otherwise drop them for having a non-http protocol.
        .addProtocols("img", "src", "data")
        // Scene breaks. `relaxed` omits `hr`, and it is the one omission that loses content rather
        // than formatting: the cleaner unwraps a disallowed element and keeps its text, but `hr` has
        // no text, so a scene break would disappear with nothing left in its place.
        .addTags("hr")

    private val UNRESERVED = (('a'..'z') + ('A'..'Z') + ('0'..'9')).toSet() + setOf('-', '_', '.', '~')

    /**
     * Returns a body fragment safe to render.
     *
     * [baseUrl] is the chapter's own absolute URL, used to resolve relative references before they
     * are rewritten; a fragment with relative links and no base simply loses them.
     */
    fun sanitize(html: String, baseUrl: String?): String {
        val base = baseUrl.orEmpty()
        val body = Jsoup.parseBodyFragment(html, base).body()

        // The cleaner drops these elements but keeps the text of anything it merely unwraps, so they
        // are removed outright first to guarantee no stylesheet or script body survives as prose.
        body.select("script, style, noscript, template, iframe").remove()

        // <source> carries its own srcset, which a browser prefers over the <img> it wraps, so it
        // would bypass the rewrite below. A `srcset` left on the <img> itself needs no handling:
        // the safelist does not allow the attribute, so the clean at the end drops it.
        body.select("source").remove()

        body.select("img").forEach { image ->
            if (image.attr("src").startsWith("data:", ignoreCase = true)) return@forEach

            val absolute = image.absUrl("src")
            if (isHttp(absolute)) {
                image.attr("src", VIRTUAL_ORIGIN + percentEncode(absolute))
            } else {
                image.remove()
            }
        }

        return Jsoup.clean(body.html(), base, SAFELIST)
    }

    /**
     * Drops the blank space a book pads its paragraphs out with.
     *
     * Empty and whitespace-only paragraphs go, and a run of line breaks collapses to one. Rules
     * stay: a horizontal rule is a scene break, which is content rather than padding, and the
     * stylesheet already renders it as such.
     *
     * Called where the document is built rather than from [sanitize], and that matters. An
     * imported EPUB never passes through [sanitize] — it is read straight out of the archive — so
     * trimming there would quietly do nothing for the case most books are.
     */
    fun trimBlankLines(html: String): String {
        val document = Jsoup.parseBodyFragment(html)
        // Indenting block elements would add newlines, which render as spaces.
        document.outputSettings().prettyPrint(false)
        val body = document.body()

        body.select("p").forEach { paragraph ->
            // Blank of text is not blank of content: a paragraph holding only an image stays.
            if (paragraph.text().isBlank() && paragraph.select("img, svg, video, hr").isEmpty()) {
                paragraph.remove()
            }
        }

        // A break following another break is padding; the first of the run is a line meant.
        body.select("br").forEach { lineBreak ->
            val previous = lineBreak.previousNonBlank()
            if (previous is Element && previous.normalName() == "br") lineBreak.remove()
        }

        return body.html()
    }

    /**
     * Drops the chapter's own copies of its title from the top of its text.
     *
     * The reader already prints the title above every chapter, and many sources scrape it into the
     * text as well — sometimes twice, or split as "Chapter 12" then "The Return". Only the leading
     * blocks are looked at, a wrapper around the whole chapter is looked into, and the first block
     * that is not the title ends the search, so prose is never touched. A rule left directly under
     * a removed title goes with it, since the reader's own heading already draws one.
     */
    fun dropRepeatedTitle(html: String, titles: List<String>): String {
        val names = titles.map(::normalized).filter { it.isNotEmpty() }.distinct()
        if (names.isEmpty()) return html

        val document = Jsoup.parseBodyFragment(html)
        document.outputSettings().prettyPrint(false)
        var container: Element = document.body()
        var removed = 0
        while (removed < MAX_TITLE_REPEATS) {
            val first = container.firstSignificant() ?: break
            val text = normalized(if (first is Element) first.text() else (first as TextNode).text())
            when {
                // Looked into before it is matched, or a short chapter would match as "title and more".
                first is Element && first.normalName() in WRAPPERS && first.childrenSize() > 0 -> container = first
                names.any { isTitle(text, it, removed > 0) } -> {
                    first.remove()
                    removed++
                }
                else -> break
            }
        }
        if (removed == 0) return html

        while (true) {
            val lineBreak = container.childNodes().firstOrNull { !(it is TextNode && it.isBlank) }
            if (lineBreak is Element && lineBreak.normalName() == "br") lineBreak.remove() else break
        }
        container.firstSignificant()?.takeIf { it is Element && it.normalName() == "hr" }?.remove()
        return document.body().html()
    }

    /** True for a block that is the title, part of it, or the title with little more. */
    private fun isTitle(block: String, title: String, afterTitle: Boolean): Boolean = when {
        block.isEmpty() -> false
        block == title -> true
        // "Chapter 12" from "Chapter 12: The Return"; the number keeps a stray word from matching.
        block.any(Char::isDigit) && title.contains(block) -> true
        // The rest of a title split across two blocks.
        afterTitle && title.endsWith(block) -> true
        // "Chapter 12: The Return" when the chapter is only named "Chapter 12".
        else -> block.startsWith(title) && block.length <= title.length * 2
    }

    private fun normalized(text: String): String = text.lowercase().filter(Char::isLetterOrDigit)

    /** The first child that shows anything, looking past whitespace and empty paragraphs. */
    private fun Element.firstSignificant(): Node? = childNodes().firstOrNull { node ->
        when (node) {
            is TextNode -> !node.isBlank
            is Element -> node.normalName() == "hr" || node.text().isNotBlank() ||
                node.select("img, svg, video").isNotEmpty()
            else -> false
        }
    }

    private const val MAX_TITLE_REPEATS = 3
    private val WRAPPERS = setOf("div", "section", "article", "main")

    /** The sibling before this one, looking past the whitespace between tags. */
    private fun Node.previousNonBlank(): Node? {
        var previous = previousSibling()
        while (previous is TextNode && previous.text().isBlank()) {
            previous = previous.previousSibling()
        }
        return previous
    }

    private fun isHttp(url: String): Boolean =
        url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

    /**
     * Escapes everything outside the unreserved set, so the result survives
     * [NovelEpubAssetServer.pathFor] — which strips at the first `?` or `#` before decoding — and
     * comes back out of [leaf.novel.data.epub.EpubPath.percentDecode] byte-identical.
     *
     * Hand-rolled rather than `Uri.encode` for the same reason [leaf.novel.data.epub.EpubPath] decodes by
     * hand: this has to be testable on the JVM, where the Android framework is not available.
     */
    fun percentEncode(value: String): String = buildString(value.length) {
        value.toByteArray(Charsets.UTF_8).forEach { byte ->
            val char = byte.toInt().toChar()
            if (char in UNRESERVED) {
                append(char)
            } else {
                append('%').append("%02X".format(byte.toInt() and 0xFF))
            }
        }
    }
}
