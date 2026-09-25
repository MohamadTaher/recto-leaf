package leaf.novel.presentation.reader.comments

import android.content.Context
import android.os.Build
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Close
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.clickableNoIndication

/**
 * Pictures in a comment, drawn where they stood among its words, with GIFs moving.
 *
 * The app's own image loader decodes a GIF to its first frame, which is right for a cover and wrong
 * for a reaction GIF. So comments load through a copy of it with an animated decoder added — the
 * same network client and caches, one more decoder, and nothing changed for the rest of the app.
 *
 * GIF pickers mostly hand out short videos now, which no image decoder reads; those play as the
 * site plays them, muted and looping, in the platform's own video view.
 *
 * A tap opens the picture full screen, still moving, where it can be pinched; another tap closes it.
 *
 * Each is drawn at the size a browser gives it, one dp to an image pixel and never past [MAX_SIZE]:
 * left alone a picture takes one screen pixel per image pixel, which shrinks a site's 50-pixel
 * sticker to a third of the size its own page shows it at.
 *
 * Each is asked for with the comment's own site as its Referer, as a browser showing that comment
 * would: sticker and picture hosts commonly refuse anyone else. Only the origin is sent, which is
 * all a browser sends to another host.
 */
@Composable
internal fun NovelCommentImages(urls: List<String>, pageUrl: String?, modifier: Modifier = Modifier) {
    if (urls.isEmpty()) return
    var viewing by remember { mutableStateOf<String?>(null) }
    val referer = remember(pageUrl) { pageUrl?.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}/" } }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        urls.forEach { url ->
            CommentMedia(
                url = url,
                referer = referer,
                contentScale = ContentScale.Fit,
                bounds = MAX_SIZE,
                modifier = Modifier
                    .heightIn(max = MAX_SIZE.height)
                    .widthIn(max = MAX_SIZE.width)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { viewing = url },
            )
        }
    }
    viewing?.let { url -> CommentMediaDialog(url, referer, onDismissRequest = { viewing = null }) }
}

/** One picture or video GIF, full screen over a dark scrim: pinch to zoom, tap to leave. */
@Composable
private fun CommentMediaDialog(url: String, referer: String?, onDismissRequest: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val state = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, MAX_SCALE)
        // Panning only means something once there is more picture than screen.
        offset = if (scale > 1f) offset + pan else Offset.Zero
    }
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(SCRIM)
                .clickableNoIndication(onClick = onDismissRequest),
            contentAlignment = Alignment.Center,
        ) {
            CommentMedia(
                url = url,
                referer = referer,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)
                    .transformable(state),
            )
            IconButton(
                onClick = onDismissRequest,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(8.dp),
            ) {
                Icon(MaterialSymbols.Rounded.Close, stringResource(MR.strings.action_close), tint = Color.White)
            }
        }
    }
}

@Composable
private fun CommentMedia(
    url: String,
    referer: String?,
    contentScale: ContentScale,
    modifier: Modifier,
    /** Set to size the picture as a browser would, within these bounds; null leaves it to [modifier]. */
    bounds: DpSize? = null,
) {
    if (VIDEO.containsMatchIn(url.substringBefore('?'))) {
        AndroidView(
            factory = { context ->
                VideoView(context).apply {
                    setOnPreparedListener { player ->
                        player.setVolume(0f, 0f)
                        player.isLooping = true
                        start()
                    }
                    // A clip that will not play leaves an empty box, not a dialog.
                    setOnErrorListener { _, _, _ -> true }
                    setVideoURI(url.toUri(), referer?.let { mapOf("Referer" to it) })
                }
            },
            onRelease = { it.stopPlayback() },
            modifier = modifier,
        )
    } else {
        val context = LocalContext.current
        var size by remember(url) { mutableStateOf<DpSize?>(null) }
        AsyncImage(
            model = remember(url, referer) {
                ImageRequest.Builder(context)
                    .data(url)
                    .apply { referer?.let { httpHeaders(NetworkHeaders.Builder().set("Referer", it).build()) } }
                    .build()
            },
            imageLoader = commentImageLoader(context),
            contentDescription = null,
            contentScale = contentScale,
            onSuccess = { state ->
                if (bounds != null) {
                    val width = state.result.image.width.toFloat()
                    val height = state.result.image.height.toFloat()
                    val scale = minOf(1f, bounds.width.value / width, bounds.height.value / height)
                    size = DpSize((width * scale).dp, (height * scale).dp)
                }
            },
            modifier = modifier.then(size?.let { Modifier.size(it) } ?: Modifier),
        )
    }
}

private val MAX_SIZE = DpSize(320.dp, 240.dp)

private val VIDEO = Regex("""\.(mp4|webm)$""", RegexOption.IGNORE_CASE)

/** Dark enough that the sheet behind stops competing, as the chapter's own image viewer is. */
private val SCRIM = Color(0xE6000000)
private const val MAX_SCALE = 8f

private var commentLoader: ImageLoader? = null

/** Built once, from the app's loader, so memory and disk caches are the ones it already has. */
private fun commentImageLoader(context: Context): ImageLoader {
    commentLoader?.let { return it }
    val animated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        AnimatedImageDecoder.Factory()
    } else {
        GifDecoder.Factory()
    }
    // The app's decoder declines GIFs, so this is reached before the static fallback.
    return SingletonImageLoader.get(context).newBuilder()
        .components { add(animated) }
        .build()
        .also { commentLoader = it }
}
