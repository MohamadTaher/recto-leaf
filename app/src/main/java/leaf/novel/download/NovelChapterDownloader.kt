package leaf.novel.download

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.util.storage.DiskUtil
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import leaf.novel.api.NovelSource
import java.io.IOException

/** One downloaded novel chapter, stored inside the chapter's download directory. */
@Serializable
data class StoredNovelChapter(
    val html: String,
    val baseUrl: String? = null,
    val title: String? = null,
)

/** Name of the file [NovelChapterDownloader] writes, and [readStoredChapter] reads back. */
const val NOVEL_CHAPTER_FILE = "chapter.json"

/**
 * Downloads a novel chapter, standing in for the image pipeline in `Downloader.downloadChapter`.
 *
 * Only the innermost step differs from a manga download. The directory layout, the temp-then-rename
 * dance, the queue, the notifications and the download cache are all upstream's, which is what makes
 * the download badge, "delete after read" and the offline check work with no further changes.
 *
 * The HTML is stored **as the source returned it**, not sanitised. Sanitising happens once, on the
 * read path, so that a fix to `NovelHtmlSanitizer` also applies to chapters already on disk.
 */
class NovelChapterDownloader(
    private val context: Context,
    private val cache: DownloadCache,
) {

    private val json = Json { encodeDefaults = true }

    /**
     * Writes [download]'s chapter into [tmpDir] and publishes it as [chapterDirName].
     *
     * Throws rather than reporting failure itself: the caller already wraps this in the error
     * handling that sets the download state and raises the notification.
     *
     * No progress is reported between the two state changes. `Download.progress` is an average over
     * its page list, which a novel does not have, and a chapter is a single small request — so there
     * is nothing to report that would not be a fiction.
     */
    suspend fun download(download: Download, mangaDir: UniFile, tmpDir: UniFile, chapterDirName: String) {
        val source = download.source as? NovelSource
            ?: throw IllegalStateException("${download.source.name} is not a novel source")

        download.status = Download.State.DOWNLOADING

        val content = source.getChapterContent(download.chapter.toSChapter())
        val stored = StoredNovelChapter(
            html = content.html,
            baseUrl = content.baseUrl,
            title = content.title,
        )

        val file = tmpDir.createFile(NOVEL_CHAPTER_FILE)
            ?: throw IOException("Could not create $NOVEL_CHAPTER_FILE in ${tmpDir.name}")
        file.openOutputStream().use { it.write(json.encodeToString(stored).toByteArray()) }

        // Written before the rename, while the handle is unambiguously valid. Upstream's image path
        // writes it afterwards, which is certainly a no-op in its CBZ branch because the temp
        // directory has been deleted by then; whether it still lands in the rename branch depends on
        // UniFile.renameTo updating the handle, which is not worth relying on either way.
        DiskUtil.createNoMediaFile(tmpDir, context)

        if (!tmpDir.renameTo(chapterDirName)) {
            throw IOException("Could not publish $chapterDirName")
        }
        cache.addChapter(chapterDirName, mangaDir, download.manga)

        download.status = Download.State.DOWNLOADED
    }
}

/** Reads back what [NovelChapterDownloader] wrote, or null when the directory holds no chapter. */
fun readStoredChapter(chapterDir: UniFile, json: Json): StoredNovelChapter? {
    val file = chapterDir.findFile(NOVEL_CHAPTER_FILE)?.takeIf { it.isFile } ?: return null
    return runCatching {
        file.openInputStream().use { json.decodeFromString<StoredNovelChapter>(it.readBytes().decodeToString()) }
    }.getOrNull()
}
