package leaf.novel.ui.reader.setting

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR
import kotlin.math.abs

/**
 * The four directions a swipe across the page can be bound to.
 *
 * All four start unbound. The vertical pair especially: the page scrolls vertically, so a bound
 * top-to-bottom swipe is competing with the reader's primary gesture, and that has to be something
 * a reader opts into rather than something they discover by accident.
 */
enum class NovelReaderSwipe(val titleRes: StringResource) {
    RIGHT_TO_LEFT(MR.strings.leaf_novel_swipe_right_to_left),
    LEFT_TO_RIGHT(MR.strings.leaf_novel_swipe_left_to_right),
    TOP_TO_BOTTOM(MR.strings.leaf_novel_swipe_top_to_bottom),
    BOTTOM_TO_TOP(MR.strings.leaf_novel_swipe_bottom_to_top),
    ;

    companion object {
        /**
         * Below this a movement is a drag or a stray flick rather than a swipe worth acting on.
         *
         * In dp, and converted by the caller. Touch coordinates arrive in physical pixels, so a
         * pixel threshold would ask for three times the finger travel on a low-density screen that
         * it does on a high-density one.
         */
        const val MIN_DISTANCE_DP = 32f

        /**
         * The direction a fling travelled, or null when it was too short to mean anything.
         *
         * The larger axis wins outright, so a diagonal reads as whichever of the two it is more of
         * and there is no dead band in which an almost-straight swipe does nothing at all.
         */
        fun of(dx: Float, dy: Float, minDistancePx: Float): NovelReaderSwipe? {
            val horizontal = abs(dx) >= abs(dy)
            val distance = if (horizontal) abs(dx) else abs(dy)
            if (distance < minDistancePx) return null

            return when {
                horizontal && dx < 0 -> RIGHT_TO_LEFT
                horizontal -> LEFT_TO_RIGHT
                dy < 0 -> BOTTOM_TO_TOP
                else -> TOP_TO_BOTTOM
            }
        }
    }
}
