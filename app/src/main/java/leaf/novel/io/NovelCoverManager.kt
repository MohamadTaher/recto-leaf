package leaf.novel.io

import android.content.Context
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.storage.DiskUtil
import tachiyomi.core.common.storage.nameWithoutExtension
import tachiyomi.core.common.util.system.ImageUtil
import java.io.InputStream

private const val DEFAULT_COVER_NAME = "cover.jpg"

/**
 * Mirror of [tachiyomi.source.local.image.LocalCoverManager] for the `novels/` root.
 *
 * The upstream class resolves paths through `LocalSourceFileSystem`, so it is bound to `local/` and
 * cannot be reused without parameterising an upstream file. See plans/04.
 */
@Inject
@SingleIn(AppScope::class)
class NovelCoverManager(
    private val context: Context,
    private val fileSystem: NovelFileSystem,
) {

    /** A user-supplied or previously extracted `cover.*` in the novel folder, if any. */
    fun find(novelUrl: String): UniFile? = fileSystem.getNovelDirectory(novelUrl)?.let(::findIn)

    /** As [find], but for a folder that is not yet resolvable by name — an import in progress. */
    fun findIn(directory: UniFile): UniFile? {
        return directory.listFiles().orEmpty()
            .filter { it.isFile && it.nameWithoutExtension.equals("cover", ignoreCase = true) }
            .firstOrNull { ImageUtil.isImage(it.name) { it.openInputStream() } }
    }

    fun update(manga: SManga, inputStream: InputStream): UniFile? {
        val directory = fileSystem.getNovelDirectory(manga.url)
        if (directory == null) {
            inputStream.close()
            return null
        }
        return write(directory, findIn(directory), inputStream)?.also {
            manga.thumbnail_url = it.uri.toString()
        }
    }

    /**
     * Writes a cover into [directory] directly, for use during import when the folder is still
     * under its temporary name.
     */
    fun writeInto(directory: UniFile, inputStream: InputStream): UniFile? {
        return write(directory, existing = null, inputStream = inputStream)
    }

    private fun write(directory: UniFile, existing: UniFile?, inputStream: InputStream): UniFile? {
        val targetFile = existing ?: directory.createFile(DEFAULT_COVER_NAME) ?: run {
            inputStream.close()
            return null
        }

        inputStream.use { input ->
            targetFile.openOutputStream().use { output ->
                input.copyTo(output)
            }
        }

        DiskUtil.createNoMediaFile(directory, context)

        return targetFile
    }
}
