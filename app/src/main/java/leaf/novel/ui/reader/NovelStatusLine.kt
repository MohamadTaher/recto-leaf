package leaf.novel.ui.reader

import kotlin.math.roundToInt

/**
 * The two sums the mini status bar shows and nothing else needs.
 *
 * Free of Android types so the arithmetic can be tested on the JVM, which is the same reason
 * [NovelReadingTime] and [leaf.novel.ui.reader.setting.NovelTapGrid] are. Kept in tenths rather
 * than doubles for the same reason [NovelReaderCss] is: the values are exact fractions of one and
 * a double would put `60.599999999999994` on the bar.
 */
object NovelStatusLine {

    /** Which screenful of the chapter is showing, and how many there are altogether. */
    data class Screens(val current: Int, val total: Int) {
        companion object {
            /** What a chapter reads as before the view has measured it. */
            val NONE = Screens(current = 1, total = 1)
        }
    }

    /**
     * Turns a scroll offset into a screen position.
     *
     * The reader scrolls continuously, so a "screen" is a viewport-worth of the chapter rather than
     * anything the document itself marks — an estimate, where pagination would have a real count.
     */
    fun screens(scrollY: Int, range: Int, viewportHeight: Int): Screens {
        if (viewportHeight <= 0) return Screens.NONE
        val total = ((range + viewportHeight - 1) / viewportHeight).coerceAtLeast(1)

        // Spread over how far the chapter can actually scroll, not over its length. The last
        // screenful is usually a partial one, so the furthest the reader can get is a viewport
        // short of the end — dividing by the viewport would leave the final screen unreachable and
        // a two-and-a-half screen chapter reading "2/3" at the bottom.
        val maxScroll = (range - viewportHeight).coerceAtLeast(0)
        if (maxScroll <= 0) return Screens(current = 1, total = total)

        val current = (scrollY.toFloat() / maxScroll * (total - 1)).roundToInt() + 1
        return Screens(current.coerceIn(1, total), total)
    }

    /**
     * How far through the book the reader is, to one decimal place.
     *
     * An estimate and openly one: it weights every chapter equally, because the reader knows how
     * many chapters a book has and not how long any of them is.
     */
    fun bookPercent(chapterIndex: Int, chapterCount: Int, percentRead: Int): String {
        if (chapterCount <= 0) return tenths(0)
        val index = chapterIndex.coerceAtLeast(0)
        val within = percentRead.coerceIn(0, 100)
        return tenths(((index * 100 + within) * 10 / chapterCount).coerceIn(0, PERCENT_TENTHS))
    }

    /** Tenths as a plain decimal. The caller clamps, so this never sees a negative to format. */
    private fun tenths(value: Int): String = "${value / 10}.${value % 10}"

    private const val PERCENT_TENTHS = 1000
}
