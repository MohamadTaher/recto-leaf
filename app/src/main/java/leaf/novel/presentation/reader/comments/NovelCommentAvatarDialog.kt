package leaf.novel.presentation.reader.comments

import android.content.Context
import android.net.Uri
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.size.Size
import eu.kanade.presentation.manga.components.MangaCoverDialog
import eu.kanade.tachiyomi.data.saver.Image
import eu.kanade.tachiyomi.data.saver.ImageSaver
import eu.kanade.tachiyomi.data.saver.Location
import eu.kanade.tachiyomi.util.system.getBitmapOrNull
import eu.kanade.tachiyomi.util.system.toShareIntent
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR

/**
 * A commenter's picture, full screen, in the same viewer a novel's cover opens in — share and save,
 * no edit.
 *
 * The viewer draws a [Manga]'s cover, so the picture goes in as one: not in the library and from no
 * source, which the cover fetcher loads like any browse thumbnail and never writes to the cover cache.
 */
@Composable
internal fun NovelCommentAvatarDialog(url: String, author: String, onDismissRequest: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val manga = remember(url, author) { Manga.create().copy(thumbnailUrl = url, title = author) }
    MangaCoverDialog(
        manga = manga,
        isCustomCover = false,
        snackbarHostState = snackbarHostState,
        onShareClick = {
            scope.launch {
                try {
                    val uri = save(context, manga, temp = true)
                    if (uri == null) {
                        snackbarHostState.showSnackbar(context.stringResource(MR.strings.error_sharing_cover))
                        return@launch
                    }
                    context.startActivity(uri.toShareIntent(context))
                } catch (e: Throwable) {
                    logcat(LogPriority.ERROR, e)
                    snackbarHostState.showSnackbar(context.stringResource(MR.strings.error_sharing_cover))
                }
            }
        },
        onSaveClick = {
            scope.launch {
                val message = try {
                    val saved = save(context, manga, temp = false)
                    if (saved != null) {
                        MR.strings.cover_saved
                    } else {
                        MR.strings.error_saving_cover
                    }
                } catch (e: Throwable) {
                    logcat(LogPriority.ERROR, e)
                    MR.strings.error_saving_cover
                }
                snackbarHostState.showSnackbar(context.stringResource(message), withDismissAction = true)
            }
        },
        onEditClick = null,
        onDismissRequest = onDismissRequest,
    )
}

/** What `MangaCoverViewModel` does for a cover, for a picture with no library entry behind it. */
private suspend fun save(context: Context, manga: Manga, temp: Boolean): Uri? {
    val request = ImageRequest.Builder(context).data(manga).size(Size.ORIGINAL).build()
    return withIOContext {
        val bitmap = context.imageLoader.execute(request).image?.asDrawable(context.resources)?.getBitmapOrNull()
            ?: return@withIOContext null
        ImageSaver(context).save(
            Image.Cover(
                bitmap = bitmap,
                name = manga.title,
                location = if (temp) Location.Cache else Location.Pictures.create(),
            ),
        )
    }
}
