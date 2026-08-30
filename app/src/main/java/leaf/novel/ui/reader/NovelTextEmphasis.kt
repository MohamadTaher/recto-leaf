package leaf.novel.ui.reader

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import kotlin.math.ceil

/**
 * The reading aids that work by emphasising part of the text rather than by styling all of it.
 *
 * Both run when the document is built, on the parsed tree rather than on the markup as a string.
 * Nothing is re-serialised and re-parsed, so a book's own elements, attributes and entities come
 * through untouched — an `&amp;` stays one ampersand instead of turning into a literal `&amp;amp;`
 * the second time round.
 *
 * Free of Android types, so the part that is actually arithmetic can be tested on the JVM.
 */
object NovelTextEmphasis {

    /**
     * The leading share of a word that gets emphasised, rounded up.
     *
     * Two fifths is the ratio bionic reading settled on: enough of the word to anchor the eye,
     * little enough that the rest still reads as ordinary text. It is not an arbitrary number and
     * changing it changes how the whole page feels.
     */
    private const val INITIAL_CHARS_SHARE = 0.4

    /**
     * Elements whose text is left alone.
     *
     * A heading is already emphasis, and code and preformatted text mean what they say
     * character-for-character — bolding part of a word inside them is a lie about the content.
     */
    private val SKIPPED_TAGS = setOf("h1", "h2", "h3", "h4", "h5", "h6", "code", "pre", "kbd", "samp")

    private val SENTENCE_ENDINGS = setOf('.', '!', '?', '…')

    /** Emphasises the word that opens each sentence. */
    fun firstWordOfSentence(html: String): String =
        emphasise(html) { word, opensSentence -> if (opensSentence) word.length else 0 }

    /** Emphasises the leading characters of every word, as bionic reading does. */
    fun initialCharsOfWord(html: String): String =
        emphasise(html) { word, _ -> ceil(word.length * INITIAL_CHARS_SHARE).toInt() }

    /**
     * How a stretch of text divides into emphasised and plain runs.
     *
     * Internal so the splitting can be tested directly: it is the whole of the logic, and going
     * through the parser to check it would test jsoup rather than this.
     */
    internal data class Run(val text: String, val emphasised: Boolean)

    /**
     * Splits [text] into runs, asking [emphasisOf] how many of each word's leading characters to
     * emphasise. Returning zero leaves the word alone.
     */
    internal fun runsOf(text: String, emphasisOf: (word: String, opensSentence: Boolean) -> Int): List<Run> {
        val runs = mutableListOf<Run>()
        val plain = StringBuilder()
        var index = 0
        var opensSentence = true

        fun flushPlain() {
            if (plain.isNotEmpty()) {
                runs += Run(plain.toString(), emphasised = false)
                plain.clear()
            }
        }

        while (index < text.length) {
            val char = text[index]
            if (!char.isWordChar()) {
                if (char in SENTENCE_ENDINGS) opensSentence = true
                plain.append(char)
                index++
                continue
            }

            val start = index
            while (index < text.length && text[index].isWordChar()) index++
            val word = text.substring(start, index)

            val emphasised = emphasisOf(word, opensSentence).coerceIn(0, word.length)
            opensSentence = false

            if (emphasised == 0) {
                plain.append(word)
            } else {
                flushPlain()
                runs += Run(word.take(emphasised), emphasised = true)
                plain.append(word.drop(emphasised))
            }
        }

        flushPlain()
        return runs
    }

    /** Apostrophes are inside words, so "don't" is one word rather than "don" and "t". */
    private fun Char.isWordChar(): Boolean = isLetterOrDigit() || this == '\'' || this == '’'

    private fun emphasise(html: String, emphasisOf: (String, Boolean) -> Int): String {
        val document = Jsoup.parseBodyFragment(html)
        // Pretty printing would indent block elements, and the newlines it adds render as spaces.
        document.outputSettings().prettyPrint(false)
        val body = document.body()

        // Collected before anything is changed: the replacement below detaches nodes, and mutating
        // the tree underneath a live traversal skips siblings.
        val targets = body.select("*").flatMap { it.textNodes() }

        targets.forEach { node ->
            if (node.isSkipped() || node.text().isBlank()) return@forEach

            val runs = runsOf(node.text(), emphasisOf)
            if (runs.none { it.emphasised }) return@forEach

            var anchor: Node = node
            runs.forEach { run ->
                val replacement: Node = if (run.emphasised) {
                    Element("b").appendChild(TextNode(run.text))
                } else {
                    TextNode(run.text)
                }
                anchor.after(replacement)
                anchor = replacement
            }
            node.remove()
        }

        return body.html()
    }

    private fun TextNode.isSkipped(): Boolean =
        generateSequence(parent()) { it.parent() }
            .filterIsInstance<Element>()
            .any { it.normalName() in SKIPPED_TAGS }
}
