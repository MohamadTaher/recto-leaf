package leaf.novel.presentation.reader.comments

import android.content.Context
import android.os.Build
import android.widget.VideoView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder

/**
 * The pictures in a comment, beneath its words, with GIFs moving.
 *
 * The app's own image loader decodes a GIF to its first frame, which is right for a cover and wrong
 * for a reaction GIF. So comments load through a copy of it with an animated decoder added — the
 * same network client and caches, one more decoder, and nothing changed for the rest of the app.
 *
 * GIF pickers mostly hand out short videos now, which no image decoder reads; those play as the
 * site plays them, muted and looping, in the platform's own video view.
 *
 * A tap opens the picture itself, the same way a link in the comment opens.
 */
@Composable
internal fun NovelCommentImages(urls: List<String>, modifier: Modifier = Modifier) {
    if (urls.isEmpty()) return
    val loader = commentImageLoader(LocalContext.current)
    val uriHandler = LocalUriHandler.current
    Column(modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        urls.forEach { url ->
            val media = Modifier
                .heightIn(max = 240.dp)
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable { uriHandler.openUri(url) }
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
                            setVideoURI(url.toUri())
                        }
                    },
                    onRelease = { it.stopPlayback() },
                    modifier = media,
                )
            } else {
                AsyncImage(
                    model = url,
                    imageLoader = loader,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = media,
                )
            }
        }
    }
}

private val VIDEO = Regex("""\.(mp4|webm)$""", RegexOption.IGNORE_CASE)

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
