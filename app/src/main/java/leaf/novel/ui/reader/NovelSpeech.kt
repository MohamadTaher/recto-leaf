package leaf.novel.ui.reader

import leaf.novel.ui.reader.setting.NovelSpeechDivision
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * Cutting a chapter into the pieces speech reads out, and working out where in the page a piece is.
 *
 * Both are arithmetic over text rather than anything Android does, so both are tested on the JVM —
 * the sentence splitter especially, which is the only part of speech with edge cases worth arguing
 * about.
 */
object NovelSpeech {

    /**
     * The chapter as a list of things to say, in reading order.
     *
     * Blank blocks are dropped rather than spoken as a pause: the engine already pauses between
     * utterances, and an empty one makes the position reported back meaningless.
     */
    fun utterances(html: String, division: NovelSpeechDivision): List<String> {
        return positions(html, division, chapterId = 0).map { it.text }
    }

    /** A source location stays unambiguous even when another paragraph or chapter says the same thing. */
    data class Position(val chapterId: Long, val block: Int, val start: Int, val text: String)

    fun positions(html: String, division: NovelSpeechDivision, chapterId: Long): List<Position> =
        blocks(Jsoup.parse(html)).flatMapIndexed { block, element ->
            val paragraph = element.text().trim()
            val pieces = when (division) {
                NovelSpeechDivision.PARAGRAPH -> listOf(paragraph)
                NovelSpeechDivision.SENTENCE -> sentencesIn(paragraph)
            }
            var cursor = 0
            pieces.map { text ->
                val start = paragraph.indexOf(text, cursor)
                cursor = start + text.length
                Position(chapterId, block, start, text)
            }
        }

    /** Mark before reading aids change the markup, using the same blocks as the speech queue. */
    fun anchorBlocks(html: String): String {
        val document = Jsoup.parse(html)
        document.outputSettings().prettyPrint(false)
        document.select("[$BLOCK_ATTRIBUTE]").removeAttr(BLOCK_ATTRIBUTE)
        blocks(document).forEachIndexed { index, element -> element.attr(BLOCK_ATTRIBUTE, index.toString()) }
        return document.body().html()
    }

    private fun blocks(root: Element): List<Element> = root.select(BLOCK_SELECTOR)
        .filter { it.children().select(BLOCK_SELECTOR).isEmpty() && it.text().isNotBlank() }

    /** The unit nearest [fraction] through the prose, weighted by text length. */
    fun indexAt(fraction: Float, utterances: List<String>): Int {
        if (utterances.isEmpty()) return 0
        val target = utterances.sumOf { it.length } * fraction.coerceIn(0f, 1f)
        var before = 0
        utterances.forEachIndexed { index, utterance ->
            if (before + utterance.length > target) return index
            before += utterance.length
        }
        return utterances.lastIndex
    }

    /**
     * [indexAt]'s inverse: how far through [utterances] the unit at [index] sits, as a percent.
     *
     * What lets speech checkpoint its own position to the same `lastPageRead` field the reader's
     * scroll percent writes, with no reader on screen to report one itself.
     */
    fun percentAt(index: Int, utterances: List<String>): Int {
        if (utterances.isEmpty()) return 0
        val total = utterances.sumOf { it.length }
        if (total <= 0) return 0
        val before = utterances.take(index.coerceIn(0, utterances.lastIndex)).sumOf { it.length }
        return (before * 100 / total).coerceIn(0, 100)
    }

    /**
     * One paragraph as sentences.
     *
     * Splitting on terminal punctuation alone breaks "Mr. Grey" in half, so a piece ending in a
     * known abbreviation is joined back onto the one after it. Ellipses and a closing quote after
     * the stop are handled by the pattern itself.
     */
    private fun sentencesIn(paragraph: String): List<String> {
        val pieces = paragraph.split(SENTENCE_BREAK).filter { it.isNotBlank() }
        val sentences = mutableListOf<String>()

        pieces.forEach { piece ->
            val previous = sentences.lastOrNull()
            if (previous != null && endsInAbbreviation(previous)) {
                sentences[sentences.lastIndex] = "$previous $piece"
            } else {
                sentences += piece.trim()
            }
        }

        return sentences.ifEmpty { listOf(paragraph) }
    }

    private fun endsInAbbreviation(piece: String): Boolean {
        val lastWord = piece.trimEnd('.', '"', '\'', '”', '’', ')', ']')
            .substringAfterLast(' ')
            .lowercase()
        return lastWord in ABBREVIATIONS
    }

    /**
     * The gap after a sentence ends.
     *
     * The closers are matched inside the lookbehind rather than in the pattern body, so splitting
     * takes only the whitespace: the quote that closes a line of dialogue then stays on the line it
     * closes instead of opening the next one. Bounded, because a lookbehind has to be.
     */
    private val SENTENCE_BREAK = Regex("(?<=[.!?…][\"'”’)\\]]{0,2})\\s+")

    /** The ones that actually turn up in prose. A longer list is a dictionary, not a splitter. */
    private val ABBREVIATIONS = setOf(
        "mr", "mrs", "ms", "dr", "prof", "st", "jr", "sr", "vs", "etc", "e.g", "i.e", "vol",
    )

    /** The attribute [anchorBlocks] stamps a block with, so speech can name one from Kotlin. */
    const val BLOCK_ATTRIBUTE = "data-leaf-speech-block"

    /** The blocks a chapter's prose lives in. A longer list is a dictionary, not a splitter. */
    private const val BLOCK_SELECTOR = "p, li, blockquote, h1, h2, h3, h4, h5, h6, dd, dt"
}
