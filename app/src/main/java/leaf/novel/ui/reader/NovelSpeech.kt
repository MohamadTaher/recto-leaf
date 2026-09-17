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

    /** The top visible character, also used by speech to advance the shared reading location. */
    data class Anchor(val chapterId: Long, val block: Int, val start: Int)

    fun positions(html: String, division: NovelSpeechDivision, chapterId: Long): List<Position> =
        blocks(Jsoup.parse(html)).flatMapIndexed { block, element ->
            val paragraph = element.text().trim()
            val pieces = when (division) {
                NovelSpeechDivision.PARAGRAPH -> listOf(paragraph)
                NovelSpeechDivision.SENTENCE -> sentencesIn(paragraph)
                NovelSpeechDivision.COMMA -> paragraph.split(
                    Regex("(?<=[,，،])"),
                ).map(String::trim).filter(String::isNotBlank)
                NovelSpeechDivision.WORD -> paragraph.split(Regex("\\s+")).filter(String::isNotBlank)
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

    /** Retain the exact spoken unit across Stop; percentages alone round down into earlier units. */
    fun resumeIndex(percent: Int, positions: List<Position>, bookmark: Position?, anchor: Anchor? = null): Int {
        if (anchor != null) {
            val inBlock = positions.indices.filter {
                positions[it].chapterId == anchor.chapterId && positions[it].block == anchor.block
            }
            if (inBlock.isNotEmpty()) {
                return inBlock.lastOrNull { positions[it].start <= anchor.start } ?: inBlock.first()
            }
        }
        val exact = positions.indexOf(bookmark)
        return if (exact >= 0) exact else indexAt(percent / 100f, positions.map { it.text })
    }

    /**
     * Consecutive units from [fromIndex], joined into utterances of at most [maxLength] characters.
     *
     * The engine pads every utterance with its own silence, so small utterances stutter. Joined
     * with a space they are said as the prose they came from. A group ends with its paragraph, which
     * is where a pause belongs, unless [acrossParagraphs]: when every unit already is a paragraph,
     * stopping at each one would join nothing.
     */
    fun groups(positions: List<Position>, fromIndex: Int, maxLength: Int, acrossParagraphs: Boolean): List<IntRange> {
        val groups = mutableListOf<IntRange>()
        var first = fromIndex
        var length = 0
        for (index in fromIndex..positions.lastIndex) {
            val position = positions[index]
            val previous = positions.getOrNull(index - 1)
            val newParagraph = previous == null ||
                previous.chapterId != position.chapterId ||
                previous.block != position.block
            if (index > first &&
                (length + 1 + position.text.length > maxLength || (newParagraph && !acrossParagraphs))
            ) {
                groups += first..<index
                first = index
                length = position.text.length
            } else {
                length += position.text.length + if (index == first) 0 else 1
            }
        }
        if (first <= positions.lastIndex) groups += first..positions.lastIndex
        return groups
    }

    /** The unit of [group] that [offset] into its joined text falls in. */
    fun unitAt(positions: List<Position>, group: IntRange, offset: Int): Int {
        var end = 0
        for (index in group) {
            end += positions[index].text.length + 1
            if (offset < end) return index
        }
        return group.last
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
