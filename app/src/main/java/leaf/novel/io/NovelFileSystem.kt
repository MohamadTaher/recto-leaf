package leaf.novel.io

import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.domain.storage.service.StorageManager

/** Name of the imported EPUB inside every novel folder. It is never modified after import. */
const val NOVEL_BOOK_FILE = "book.epub"

/** Suffix of an in-progress import. Scans ignore these, so a killed copy is never listed. */
const val NOVEL_TMP_SUFFIX = ".tmp"

/**
 * Mirror of [tachiyomi.source.local.io.LocalSourceFileSystem] pointed at the `novels/` root.
 *
 * Novels cannot share `local/` because `Format.valueOf` claims `.epub` for the image reader.
 * See plans/04 (D2).
 */
@Inject
@SingleIn(AppScope::class)
class NovelFileSystem(
    private val storageManager: StorageManager,
) {

    fun getBaseDirectory(): UniFile? {
        return storageManager.getNovelsDirectory()
    }

    fun getFilesInBaseDirectory(): List<UniFile> {
        return getBaseDirectory()?.listFiles().orEmpty().toList()
    }

    fun getNovelDirectory(name: String): UniFile? {
        return getBaseDirectory()
            ?.findFile(name)
            ?.takeIf { it.isDirectory }
    }

    fun getFilesInNovelDirectory(name: String): List<UniFile> {
        return getNovelDirectory(name)?.listFiles().orEmpty().toList()
    }

    /** The imported EPUB of [name], or null when the folder is missing or the book was deleted. */
    fun getBookFile(name: String): UniFile? {
        return getNovelDirectory(name)
            ?.findFile(NOVEL_BOOK_FILE)
            ?.takeIf { it.isFile }
    }
}
