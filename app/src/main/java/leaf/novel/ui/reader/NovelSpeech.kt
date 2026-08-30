package leaf.novel.ui.reader

import leaf.novel.ui.reader.setting.NovelSpeechDivision
import org.jsoup.Jsoup

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
        val paragraphs = Jsoup.parse(html)
            .select(BLOCK_SELECTOR)
            // A block inside a block would otherwise be spoken twice, once on its own and once as
            // part of its parent. Searched from the children rather than the element, because
            // jsoup's `select` matches the element it is called on as well as its descendants.
            .filter { element -> element.children().select(BLOCK_SELECTOR).isEmpty() }
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }

        return when (division) {
            NovelSpeechDivision.PARAGRAPH -> paragraphs
            NovelSpeechDivision.SENTENCE -> paragraphs.flatMap(::sentencesIn)
        }
    }

    /**
     * How far through the chapter's text the utterance at [index] begins, as a fraction.
     *
     * Weighted by length rather than by count, because a page of prose is about as tall as it is
     * long: counting utterances would race ahead through dialogue and lag through description. It
     * is still an estimate — the reader cannot ask the WebView where a paragraph is without
     * JavaScript, and the reader does not run any.
     */
    fun fractionAt(index: Int, utterances: List<String>): Float {
        if (utterances.isEmpty()) return 0f
        val total = utterances.sumOf { it.length }
        if (total <= 0) return 0f
        val before = utterances.take(index.coerceIn(0, utterances.size)).sumOf { it.length }
        return (before.toFloat() / total).coerceIn(0f, 1f)
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

    /** The blocks a chapter's prose lives in. */
    private const val BLOCK_SELECTOR = "p, li, blockquote, h1, h2, h3, h4, h5, h6, dd, dt"
}
