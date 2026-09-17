package leaf.novel.presentation.reader.comments

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The reader's speech-bubble glyph, drawn here rather than generated.
 *
 * Everything else on the bottom bar comes from `:icons:material-symbols`, which builds its vectors
 * from SVGs under `icons/material-symbols/src/main/valkyrieResources/`. That tree is upstream's, and
 * dropping one file into it would be a permanent diff against Mihon for the sake of a shape — which
 * is exactly what rule 1 exists to stop. The generated set has no comment glyph, so the fork draws
 * its own, in a file the fork owns.
 *
 * Kept to one shape on purpose. Votes reuse the arrows, pinning reuses the pin, and opening the
 * thread on the site reuses the globe; all three are already generated. This is the only glyph the
 * feature genuinely could not find.
 */
object NovelCommentGlyphs {

    /**
     * A rounded bubble with a tail at the lower left.
     *
     * Built on the Material 24dp grid with the same 2dp corner rounding the rounded symbol set
     * uses, so it sits on the bar beside the generated icons without looking like a guest.
     */
    val Comment: ImageVector by lazy {
        ImageVector.Builder(
            name = "NovelComment",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                // The body, clockwise from the top left corner.
                moveTo(5f, 3f)
                horizontalLineTo(19f)
                curveTo(20.1f, 3f, 21f, 3.9f, 21f, 5f)
                verticalLineTo(15f)
                curveTo(21f, 16.1f, 20.1f, 17f, 19f, 17f)
                horizontalLineTo(12.6f)
                // The tail, out to its point and back up to the body.
                lineTo(8.8f, 20.6f)
                curveTo(8.1f, 21.2f, 7f, 20.7f, 7f, 19.8f)
                verticalLineTo(17f)
                horizontalLineTo(5f)
                curveTo(3.9f, 17f, 3f, 16.1f, 3f, 15f)
                verticalLineTo(5f)
                curveTo(3f, 3.9f, 3.9f, 3f, 5f, 3f)
                close()
            }
        }.build()
    }
}
