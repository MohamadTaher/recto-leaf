package leaf.novel.presentation.reader.comments

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The fork's two speech-bubble glyphs, drawn here rather than generated.
 *
 * Everything else on the bottom bar and in the novel's action row comes from
 * `:icons:material-symbols`, which builds its vectors from SVGs under
 * `icons/material-symbols/src/main/valkyrieResources/`. That tree is upstream's, and dropping a file
 * into it would be a permanent diff against Mihon for the sake of a shape — which is exactly what
 * rule 1 exists to stop. The generated set has no comment glyph, so the fork draws its own, in a
 * file the fork owns.
 *
 * Both are outlines rather than solids, which is the whole point of them being here twice: the
 * rounded Material Symbols set the fork draws beside is an outline set, and a filled bubble sitting
 * between an outlined palette and an outlined rotation arrow reads as a different icon family. They
 * are stroked rather than traced as a filled outline because a stroke is a dozen path commands
 * where an outline is fifty, and nothing downstream can tell the difference.
 *
 * Kept to these two on purpose. Votes reuse the arrows, pinning reuses the pin, and opening the
 * thread on the site reuses the globe; all three are already generated.
 */
object NovelCommentGlyphs {

    /**
     * One bubble, with a tail at the lower left: the reader's chapter comments.
     *
     * Built on the Material 24dp grid at the weight the rounded symbols are drawn at, with round
     * caps and joins, so it sits on the bar beside the generated icons without looking like a guest.
     */
    val Comment: ImageVector by lazy {
        glyph("NovelComment") {
            // The body, clockwise from the end of the top-left corner.
            moveTo(6.5f, 4f)
            lineTo(17.5f, 4f)
            arcTo(3f, 3f, 0f, false, true, 20.5f, 7f)
            lineTo(20.5f, 13.5f)
            arcTo(3f, 3f, 0f, false, true, 17.5f, 16.5f)
            // The tail, out to its point and back up to the body.
            lineTo(11.5f, 16.5f)
            lineTo(7.6f, 20.4f)
            lineTo(7.6f, 16.5f)
            lineTo(6.5f, 16.5f)
            arcTo(3f, 3f, 0f, false, true, 3.5f, 13.5f)
            lineTo(3.5f, 7f)
            arcTo(3f, 3f, 0f, false, true, 6.5f, 4f)
            close()
        }
    }

    /**
     * Two bubbles, one behind the other: the novel's own comments, on the screen that describes it.
     *
     * A second bubble rather than a different shape entirely, because it is the same feature about a
     * different thing — and the row it sits in is labelled, so the glyph only has to say "comments"
     * and "more than one chapter's worth".
     */
    val Comments: ImageVector by lazy {
        glyph("NovelComments") {
            // The bubble in front, clockwise, with the same tail as [Comment].
            moveTo(5f, 8f)
            lineTo(13f, 8f)
            arcTo(2.5f, 2.5f, 0f, false, true, 15.5f, 10.5f)
            lineTo(15.5f, 15f)
            arcTo(2.5f, 2.5f, 0f, false, true, 13f, 17.5f)
            lineTo(9.5f, 17.5f)
            lineTo(6.2f, 20.8f)
            lineTo(6.2f, 17.5f)
            lineTo(5f, 17.5f)
            arcTo(2.5f, 2.5f, 0f, false, true, 2.5f, 15f)
            lineTo(2.5f, 10.5f)
            arcTo(2.5f, 2.5f, 0f, false, true, 5f, 8f)
            close()

            // Only the part of the bubble behind that the one in front does not cover, so the two
            // read as stacked rather than as a rectangle with a line through it. Left open at both
            // ends for the same reason: each end stops on an edge of the bubble in front.
            moveTo(8f, 8f)
            lineTo(8f, 5.5f)
            arcTo(2.5f, 2.5f, 0f, false, true, 10.5f, 3f)
            lineTo(19f, 3f)
            arcTo(2.5f, 2.5f, 0f, false, true, 21.5f, 5.5f)
            lineTo(21.5f, 11f)
            arcTo(2.5f, 2.5f, 0f, false, true, 19f, 13.5f)
            lineTo(15.5f, 13.5f)
        }
    }

    /** One stroked path on the 24dp grid, which is all either glyph is. */
    private fun glyph(name: String, pathBuilder: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = pathBuilder,
            )
        }.build()

    /** The weight the rounded Material Symbols outlines are drawn at, on the same 24 grid. */
    private const val STROKE = 1.8f
}
