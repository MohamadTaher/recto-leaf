package leaf.novel.presentation.reader.comments

import android.content.Context
import android.net.Uri
import android.os.Build
import android.webkit.WebResourceResponse
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
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import leaf.novel.api.NovelComment
import leaf.novel.ui.reader.comments.NovelCommentMarkup
import leaf.novel.ui.reader.comments.NovelCommentPreview
import leaf.novel.ui.reader.comments.NovelCommentReview
import leaf.novel.ui.reader.loader.NovelEpubAssetServer
import leaf.novel.ui.reader.loader.VIRTUAL_ORIGIN
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Close
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.clickableNoIndication
import java.io.ByteArrayInputStream
import kotlin.time.Duration.Companion.seconds

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
    if (NovelCommentPreview.VIDEO.containsMatchIn(url.substringBefore('?'))) {
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
            model = remember(url, referer) { commentImageRequest(context, url, referer).build() },
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

/** What the sheet, the chapter's preview and [prefetchCommentMedia] all ask for, so a picture is one fetch for all three. */
private fun commentImageRequest(context: Context, url: String, referer: String?) = ImageRequest.Builder(context)
    .data(url)
    .apply { referer?.let { httpHeaders(NetworkHeaders.Builder().set("Referer", it).build()) } }

/**
 * Into the disk cache and nowhere else: decoded at a pixel, which is all a download costs Coil, and
 * kept out of memory, where a bitmap that size would only be a miss for the sheet's real one.
 */
private fun downloadRequest(
    context: Context,
    url: String,
    referer: String?,
) = commentImageRequest(context, url, referer)
    .size(1, 1)
    .memoryCachePolicy(CachePolicy.DISABLED)
    .build()

/**
 * Fetches the pictures and avatars of [comments] and their replies as they arrive, so the sheet and
 * the preview under the chapter draw them at once rather than when they come on screen. A video GIF
 * is not an image Coil reads, so it still waits for the sheet.
 */
internal fun prefetchCommentMedia(
    context: Context,
    comments: List<NovelComment>,
    avatars: Boolean,
    referer: (NovelComment) -> String?,
    seen: MutableSet<String>,
) {
    val loader = SingletonImageLoader.get(context)
    fun visit(comment: NovelComment) {
        if (!comment.deleted) {
            val urls = NovelCommentMarkup.parse(NovelCommentReview.parse(comment.body).body).mapNotNull { it.image } +
                listOfNotNull(comment.avatarUrl?.takeIf { avatars })
            urls.filter { !NovelCommentPreview.VIDEO.containsMatchIn(it.substringBefore('?')) && seen.add(it) }
                .forEach { loader.enqueue(downloadRequest(context, it, referer(comment))) }
        }
        comment.replies.forEach(::visit)
    }
    comments.forEach(::visit)
}

/**
 * Answers the chapter page's request for a comment's picture out of the image cache the sheet draws
 * from, fetching it there first if it has not landed yet; null for every other request.
 *
 * Blocking, as `shouldInterceptRequest` is, and bounded for the same reason the chapter's own
 * pictures are: the WebView waits on its own thread, and a missing picture is the better failure.
 */
internal fun commentMediaResponse(context: Context, url: Uri): WebResourceResponse? {
    if (!url.toString().startsWith(VIRTUAL_ORIGIN + NovelCommentPreview.MEDIA)) return null
    val image = url.lastPathSegment ?: return null
    val loader = SingletonImageLoader.get(context)
    val cache = loader.diskCache
    fun cached() = cache?.openSnapshot(image)?.use { cache.fileSystem.read(it.data) { readByteArray() } }
    val bytes = runCatching {
        cached() ?: run {
            val referer = url.getQueryParameter(NovelCommentPreview.REFERER)
            runBlocking {
                withTimeoutOrNull(MEDIA_TIMEOUT) { loader.execute(downloadRequest(context, image, referer)) }
            }
            cached()
        }
    }.getOrNull()
    return WebResourceResponse(
        NovelEpubAssetServer.mimeTypeOf(image.substringBefore('?')),
        null,
        if (bytes != null) 200 else 404,
        if (bytes != null) "OK" else "Not Found",
        emptyMap(),
        ByteArrayInputStream(bytes ?: ByteArray(0)),
    )
}

private val MEDIA_TIMEOUT = 15.seconds
