package leaf.novel.ui.reader

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * The two reading aids that read the book's own structural markup rather than its prose.
 *
 * EPUB marks what a piece of text *is* with `epub:type`, and its accessibility twin `role`. A
 * footnote reference and a print page break both say so there, which is what lets the reader act on
 * them at all — nothing in the text itself distinguishes a footnote link from any other link.
 *
 * Both run at document-build time, alongside the other reading aids, so toggling one re-renders the
 * open chapter without re-fetching it. Free of Android types, so both are tested on the JVM.
 */
object NovelEpubMarkup {

    /** The class the stylesheet styles a folded-in note with. */
    const val NOTE_CLASS = "leaf-note"

    /** The class the stylesheet styles a print page number with. */
    const val PAGE_CLASS = "leaf-page"

    /**
     * Folds each footnote into the text that references it.
     *
     * Only notes living in the same document are folded. An EPUB is equally free to put them in a
     * file of their own, and the reader already follows a link into another document as a chapter
     * jump — pulling a whole other spine item inline is a different feature, and not one Moon+
     * offers either.
     *
     * The note is placed after the block that referenced it rather than at the reference itself, so
     * a paragraph is not broken in half by its own footnote, and the original is removed so the
     * chapter does not end with a second copy of every note in it.
     */
    fun inlineFootnotes(html: String): String {
        val document = Jsoup.parse(html)

        document.select("a[href]")
            .filter { it.isType("noteref") && it.attr("href").startsWith("#") }
            .forEach { reference ->
                val target = document.getElementById(reference.attr("href").removePrefix("#"))
                    ?: return@forEach
                val text = target.text().trim()
                if (text.isEmpty()) return@forEach

                val host = reference.closest(BLOCK_SELECTOR) ?: reference.parent() ?: return@forEach
                host.after(Element("aside").addClass(NOTE_CLASS).text(text))
                target.remove()
            }

        return document.body().html()
    }

    /**
     * Makes the book's own print page breaks visible.
     *
     * A page break is an empty element carrying the number it precedes, so it renders as nothing at
     * all until something puts the number into it. Which means the setting needs no "off" pass: the
     * markup is already invisible when it is not wanted.
     *
     * A book with no page breaks in it shows nothing, and there is no way to know that from the
     * settings screen without opening the book — the heading says so instead.
     */
    fun showPageNumbers(html: String): String {
        val document = Jsoup.parse(html)

        document.allElements
            .filter { it.isType("pagebreak") }
            .forEach { pageBreak ->
                val label = LABEL_ATTRIBUTES
                    .firstNotNullOfOrNull { pageBreak.attr(it).trim().ifBlank { null } }
                    ?: pageBreak.text().trim()
                if (label.isEmpty()) return@forEach

                pageBreak.empty()
                pageBreak.addClass(PAGE_CLASS).text(label)
            }

        return document.body().html()
    }

    /**
     * Whether an element declares itself to be [type].
     *
     * Checked in Kotlin rather than with a selector: `epub:type` reads as a namespaced attribute to
     * a CSS selector, and the escaping that avoids that is harder to read than the filter. `role`
     * is the same statement in accessibility terms and books carry one or the other.
     */
    private fun Element.isType(type: String): Boolean =
        attr("epub:type").split(' ').contains(type) || attr("role") == "doc-$type"

    /** Where a page break keeps its number, in the order the specification prefers. */
    private val LABEL_ATTRIBUTES = listOf("title", "aria-label", "value")

    /** What counts as the block a footnote reference sits in. */
    private const val BLOCK_SELECTOR = "p, li, blockquote, div, h1, h2, h3, h4, h5, h6"
}
