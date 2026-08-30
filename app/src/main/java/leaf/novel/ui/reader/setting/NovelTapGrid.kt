package leaf.novel.ui.reader.setting

/**
 * The nine regions a tap on the page can land in.
 *
 * Cells are numbered left to right then top to bottom, the way Moon+ numbers them, so cell 0 is the
 * top left and cell 6 the bottom left. This grid subsumes a separate "tap left" and "tap right" —
 * they are the same bindings at lower resolution.
 *
 * Free of Android types so the resolution can be tested on the JVM, which is the only part of the
 * tap handling that is arithmetic rather than plumbing.
 */
object NovelTapGrid {

    const val SIDE = 3
    const val COUNT = SIDE * SIDE

    /** Bound to the options menu by default, so tapping the middle of the page still opens it. */
    const val CENTRE = 4

    /** Bound to day/night mode by default, per the imported configuration. */
    const val BOTTOM_LEFT = 6

    /** How close to an edge counts as an edge tap, when the reader asks those to be ignored. */
    const val EDGE_MARGIN_DP = 24f

    /**
     * Resolves a tap into its cell.
     *
     * A tap on a boundary belongs to the cell below and to the right of it. A point outside the
     * view — which a stray pointer or a fling can report — is pulled back to the nearest cell, and
     * a view that has not been measured yet resolves to the centre rather than dividing by zero.
     */
    fun cellOf(x: Float, y: Float, width: Int, height: Int): Int {
        if (width <= 0 || height <= 0) return CENTRE

        val column = ((x / width) * SIDE).toInt().coerceIn(0, SIDE - 1)
        val row = ((y / height) * SIDE).toInt().coerceIn(0, SIDE - 1)
        return row * SIDE + column
    }

    /**
     * Whether a tap landed close enough to an edge to be ambiguous with a system gesture.
     *
     * Gesture navigation claims the sides and the foot of the screen, so on those phones an edge
     * tap is as likely to have been a missed swipe as a deliberate tap on the page.
     */
    fun isNearEdge(x: Float, y: Float, width: Int, height: Int, marginPx: Float): Boolean =
        x < marginPx || y < marginPx || x > width - marginPx || y > height - marginPx
}
