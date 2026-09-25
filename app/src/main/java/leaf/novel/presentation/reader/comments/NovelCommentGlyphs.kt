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
 * Stars and likes use the same outline weight; the upstream set does not contain those glyphs.
 */
object NovelCommentGlyphs {

    val Star: ImageVector by lazy { star(false) }
    val FilledStar: ImageVector by lazy { star(true) }

    private fun star(filled: Boolean): ImageVector =
        glyph("CommentStar", filled) {
            moveTo(12f, 3f)
            lineTo(14.8f, 8.7f)
            lineTo(21f, 9.6f)
            lineTo(16.5f, 14f)
            lineTo(17.6f, 20.2f)
            lineTo(12f, 17.3f)
            lineTo(6.4f, 20.2f)
            lineTo(7.5f, 14f)
            lineTo(3f, 9.6f)
            lineTo(9.2f, 8.7f)
            close()
        }

    val Like: ImageVector by lazy { like(false) }
    val FilledLike: ImageVector by lazy { like(true) }

    private fun like(filled: Boolean): ImageVector =
        glyph("CommentLike", filled) {
            moveTo(7f, 10f)
            lineTo(12f, 3f)
            quadTo(15f, 3f, 14f, 7f)
            lineTo(13.5f, 9f)
            lineTo(19f, 9f)
            quadTo(21f, 9f, 20.5f, 11f)
            lineTo(18.5f, 19f)
            quadTo(18.2f, 20f, 17f, 20f)
            lineTo(7f, 20f)
            close()
            moveTo(3f, 10f)
            lineTo(7f, 10f)
            lineTo(7f, 20f)
            lineTo(3f, 20f)
            close()
        }

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
     * Two bubbles answering each other, tails on opposite corners: the novel's discussion, in its
     * action row.
     *
     * Material's own "forum" shape, redrawn in the outline weight above. A conversation rather than
     * a stack of comments, which is what sets it apart from the reader's [Comment] at a glance: one
     * is a chapter's remarks, this is people talking about the book.
     */
    val Discussion: ImageVector by lazy {
        glyph("NovelDiscussion") {
            // The bubble in front, clockwise, its tail running down the left edge.
            moveTo(5f, 3f)
            lineTo(14f, 3f)
            arcTo(2f, 2f, 0f, false, true, 16f, 5f)
            lineTo(16f, 10.5f)
            arcTo(2f, 2f, 0f, false, true, 14f, 12.5f)
            lineTo(7.5f, 12.5f)
            lineTo(3f, 17f)
            lineTo(3f, 5f)
            arcTo(2f, 2f, 0f, false, true, 5f, 3f)
            close()

            // Only the part of the bubble behind that the one in front does not cover, its tail
            // down the right edge. Left open at both ends, each stopping on an edge of the bubble
            // in front, so the two read as overlapping rather than as one outline.
            moveTo(16f, 7f)
            lineTo(19f, 7f)
            arcTo(2f, 2f, 0f, false, true, 21f, 9f)
            lineTo(21f, 21f)
            lineTo(16.5f, 16.5f)
            lineTo(11f, 16.5f)
            arcTo(2f, 2f, 0f, false, true, 9f, 14.5f)
            lineTo(9f, 12.5f)
        }
    }

    /** One stroked path on the 24dp grid, which is all either glyph is. */
    private fun glyph(name: String, filled: Boolean = false, pathBuilder: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = if (filled) SolidColor(Color.Black) else null,
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
