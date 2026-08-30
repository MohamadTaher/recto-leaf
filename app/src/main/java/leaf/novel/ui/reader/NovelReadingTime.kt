package leaf.novel.ui.reader

import org.jsoup.Jsoup
import kotlin.math.ceil

/**
 * How much of the chapter is still ahead, in minutes.
 *
 * An estimate and openly one: it assumes an even pace and counts words rather than measuring
 * anything, which is what every reader that offers this does.
 *
 * Free of Android types, so the arithmetic can be tested on the JVM.
 */
object NovelReadingTime {

    /** A middling adult reading pace. Moon+ does not expose it, and neither does this. */
    const val WORDS_PER_MINUTE = 200

    private val WHITESPACE = Regex("\\s+")

    /**
     * Words in the chapter.
     *
     * Counted from the markup the reader already holds — the chapter is not fetched or loaded again
     * to work this out, only read a second time.
     */
    fun wordsIn(html: String): Int = words(html).size

    /**
     * The chapter's words in reading order.
     *
     * Speed reading shows them one or two at a time and the estimate above counts them, so both
     * come from the one walk of the markup rather than each doing their own.
     */
    fun words(html: String): List<String> =
        Jsoup.parse(html).text().split(WHITESPACE).filter { it.isNotBlank() }

    /**
     * Minutes left, from the chapter's length and how far through it the reader has scrolled.
     *
     * Rounded up, so it reads zero only at the very end rather than for the whole last minute.
     */
    fun minutesRemaining(words: Int, percentRead: Int): Int {
        val remaining = words * (100 - percentRead.coerceIn(0, 100)) / 100.0
        return ceil(remaining / WORDS_PER_MINUTE).toInt()
    }
}
