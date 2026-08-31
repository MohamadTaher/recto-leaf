package leaf.novel.presentation.reader.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.clickableNoIndication

/**
 * One illustration, full screen, over a dark scrim.
 *
 * Deliberately not Mihon's own cover viewer. That one loads through coil from a `Manga`, and an
 * EPUB's images live inside a zip behind the reader's virtual origin, which coil cannot reach —
 * getting there would mean duplicating both of its rendering paths against bytes we decode
 * ourselves. This is the small half of that: fit to the screen, pinch to zoom, tap to leave.
 *
 * The bytes are read off the main thread by [loadBytes], because a full-page illustration in a
 * light novel is routinely a megabyte or more.
 */
@Composable
fun NovelImageDialog(
    url: String,
    loadBytes: suspend (String) -> ByteArray?,
    onDismissRequest: () -> Unit,
) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, url) {
        value = loadBytes(url)?.let { bytes ->
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                .getOrNull()
                ?.asImageBitmap()
        }
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(SCRIM)
                .clickableNoIndication(onClick = onDismissRequest),
            contentAlignment = Alignment.Center,
        ) {
            when (val image = bitmap) {
                null -> CircularProgressIndicator()
                else -> ZoomableImage(image)
            }
        }
    }
}

/**
 * The image, pinchable and draggable within the dialog.
 *
 * Zoom is the reader's only one: the page itself keeps `setSupportZoom(false)` because font size is
 * its zoom control, and pinch on the page is already bound to changing that size.
 */
@Composable
private fun ZoomableImage(image: ImageBitmap) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    val state = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(MIN_SCALE, MAX_SCALE)
        // Panning is only meaningful once there is more image than screen.
        if (scale > 1f) {
            offsetX += panChange.x
            offsetY += panChange.y
        } else {
            offsetX = 0f
            offsetY = 0f
        }
    }

    Image(
        bitmap = image,
        contentDescription = stringResource(MR.strings.leaf_novel_reader_image),
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, translationY = offsetY)
            .transformable(state),
    )
}

/** Dark enough that the page behind it stops competing, light enough to show it is still there. */
private val SCRIM = Color(0xE6000000)

private const val MIN_SCALE = 1f
private const val MAX_SCALE = 8f
