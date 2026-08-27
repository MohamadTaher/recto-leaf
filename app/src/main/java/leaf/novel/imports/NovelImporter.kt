package leaf.novel.imports

import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.util.storage.DiskUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.serialization.json.Json
import leaf.novel.data.markAsNovel
import leaf.novel.data.withNovelMemo
import leaf.novel.epub.NovelEpubException
import leaf.novel.epub.NovelEpubFailure
import leaf.novel.epub.novelEpubReader
import leaf.novel.io.NOVEL_BOOK_FILE
import leaf.novel.io.NOVEL_TMP_SUFFIX
import leaf.novel.io.NovelCoverManager
import leaf.novel.io.NovelFileSystem
import leaf.novel.source.LocalNovelSource
import leaf.novel.source.NOVEL_DETAILS_FILE
import logcat.LogPriority
import mihon.domain.manga.model.toDomainManga
import mihon.domain.source.interactor.UpdateMangaFromRemote
import tachiyomi.core.common.storage.nameWithoutExtension
import tachiyomi.core.common.util.system.logcat
import tachiyomi.core.metadata.tachiyomi.MangaDetails
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.MangaUpdate
import java.security.DigestInputStream
import java.security.MessageDigest

/** How to proceed when a novel folder of the same name already exists. */
enum class NovelImportConflict {
    /** Stop and report the conflict so the user can choose. */
    ASK,

    /** Overwrite `book.epub` in place, keeping the library row, its categories and progress. */
    REPLACE,

    /** Import into a suffixed folder as a separate entry. */
    KEEP_BOTH,
}

/** Every way an import can fail. The UI maps these to messages. */
enum class NovelImportFailure {
    NO_STORAGE_LOCATION,
    UNREADABLE,
    MISSING_PACKAGE,
    EMPTY_SPINE,
    DRM_PROTECTED,
    WRITE_FAILED,
}

sealed interface NovelImportResult {
    data class Success(val mangaId: Long, val title: String) : NovelImportResult

    /** The folder already exists; re-run with [NovelImportConflict.REPLACE] or `KEEP_BOTH`. */
    data class Conflict(val existingTitle: String) : NovelImportResult

    data class Failure(val reason: NovelImportFailure) : NovelImportResult
}

/**
 * Copies a picked EPUB into `<storage>/novels/<Title>/book.epub` and creates its library row.
 *
 * Mihon has no in-app file import anywhere, so there is nothing to reuse here. Everything is
 * written under a temporary name and renamed on success, so an interrupted import is never visible
 * to the scanner. See plans/04 (D2).
 */
@Inject
@SingleIn(AppScope::class)
class NovelImporter(
    private val context: Context,
    private val json: Json,
    private val fileSystem: NovelFileSystem,
    private val coverManager: NovelCoverManager,
    private val libraryPreferences: LibraryPreferences,
    private val networkToLocalManga: NetworkToLocalManga,
    private val updateManga: UpdateManga,
    private val updateMangaFromRemote: UpdateMangaFromRemote,
) {

    /**
     * Import work runs in this scope rather than the caller's. `MainActivity` declares no
     * `configChanges`, so a rotation destroys the composition that started the import; running here
     * means the copy still finishes and the folder is never left half-written.
     */
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    suspend fun import(uri: Uri, conflict: NovelImportConflict = NovelImportConflict.ASK): NovelImportResult =
        scope.async {
            // The caller is a Compose coroutine, where an escaping exception takes the app down.
            // Everything reaches the user as a Failure instead.
            try {
                runImport(uri, conflict)
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Unexpected error importing $uri" }
                NovelImportResult.Failure(NovelImportFailure.WRITE_FAILED)
            }
        }.await()

    private suspend fun runImport(uri: Uri, conflict: NovelImportConflict): NovelImportResult {
        val baseDirectory = fileSystem.getBaseDirectory()
            ?: return NovelImportResult.Failure(NovelImportFailure.NO_STORAGE_LOCATION)
        val sourceFile = UniFile.fromUri(context, uri)
            ?: return NovelImportResult.Failure(NovelImportFailure.UNREADABLE)

        // Validate before anything is written, so a rejected file leaves the library untouched.
        val epubTitle = try {
            sourceFile.novelEpubReader(context).use { it.metadata.title }
        } catch (e: NovelEpubException) {
            return NovelImportResult.Failure(e.failure.toImportFailure())
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error reading EPUB at $uri" }
            return NovelImportResult.Failure(NovelImportFailure.UNREADABLE)
        }

        val title = epubTitle ?: sourceFile.nameWithoutExtension ?: DEFAULT_TITLE
        val preferredName = buildFolderName(title)
        val existing = baseDirectory.findFile(preferredName)?.takeIf { it.isDirectory }

        return when {
            existing == null -> importIntoNewFolder(baseDirectory, sourceFile, preferredName)
            conflict == NovelImportConflict.ASK -> NovelImportResult.Conflict(preferredName)
            conflict == NovelImportConflict.REPLACE -> replaceBook(existing, sourceFile, preferredName)
            else -> {
                val freeName = nextFreeName(baseDirectory, preferredName)
                    ?: return NovelImportResult.Failure(NovelImportFailure.WRITE_FAILED)
                // Both folders hold the same book, so without an override both entries would take
                // the same dc:title and be indistinguishable in the library.
                importIntoNewFolder(baseDirectory, sourceFile, freeName, distinctTitle = freeName)
            }
        }
    }

    private suspend fun importIntoNewFolder(
        baseDirectory: UniFile,
        sourceFile: UniFile,
        folderName: String,
        distinctTitle: String? = null,
    ): NovelImportResult {
        val temporary = baseDirectory.createDirectory(folderName + NOVEL_TMP_SUFFIX)
            ?: return NovelImportResult.Failure(NovelImportFailure.WRITE_FAILED)

        // Null means the folder never made it to its final name, whatever the reason.
        val hash = try {
            val book = temporary.createFile(NOVEL_BOOK_FILE)
            if (book == null) {
                null
            } else {
                val computed = copyWithHash(sourceFile, book)
                extractCover(book, temporary)
                distinctTitle?.let { writeDetailsOverride(temporary, it) }
                DiskUtil.createNoMediaFile(temporary, context)
                computed.takeIf { temporary.renameTo(folderName) }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error importing novel into $folderName" }
            null
        }

        if (hash == null) {
            temporary.delete()
            return NovelImportResult.Failure(NovelImportFailure.WRITE_FAILED)
        }
        return registerNovel(folderName, hash)
    }

    /**
     * Swaps in a new `book.epub` without recreating the folder, so the library row and everything
     * hanging off it survive. This is the normal WebToEpub update path.
     */
    private suspend fun replaceBook(folder: UniFile, sourceFile: UniFile, folderName: String): NovelImportResult {
        val temporaryName = NOVEL_BOOK_FILE + NOVEL_TMP_SUFFIX
        val temporaryBook = folder.createFile(temporaryName)
            ?: return NovelImportResult.Failure(NovelImportFailure.WRITE_FAILED)

        val hash = try {
            val computed = copyWithHash(sourceFile, temporaryBook)

            // Keep the old book until the new one is in place: the user's imported original is the
            // only copy the library has, and losing it is the worst outcome available (risk L3).
            val previous = folder.findFile(NOVEL_BOOK_FILE)
            when {
                previous != null && !previous.renameTo(NOVEL_BOOK_FILE + PREVIOUS_SUFFIX) -> null
                !temporaryBook.renameTo(NOVEL_BOOK_FILE) -> {
                    folder.findFile(NOVEL_BOOK_FILE + PREVIOUS_SUFFIX)?.renameTo(NOVEL_BOOK_FILE)
                    null
                }
                else -> {
                    folder.findFile(NOVEL_BOOK_FILE + PREVIOUS_SUFFIX)?.delete()
                    computed
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error replacing book.epub in $folderName" }
            null
        }

        if (hash == null) {
            folder.findFile(temporaryName)?.delete()
            return NovelImportResult.Failure(NovelImportFailure.WRITE_FAILED)
        }

        folder.findFile(NOVEL_BOOK_FILE)?.let { extractCover(it, folder, overwrite = false) }
        return registerNovel(folderName, hash)
    }

    /**
     * Inserts the row if it is new, stamps the current EPUB hash either way, then pulls details and
     * chapters in.
     *
     * The refresh is not optional: `MangaViewModel` only fetches on its own when the row is
     * uninitialised or has no chapters, so replacing `book.epub` on a novel that is already in the
     * library would otherwise show none of its new chapters until a manual pull-to-refresh.
     */
    private suspend fun registerNovel(folderName: String, epubHash: String): NovelImportResult {
        val sManga = SManga.create().apply {
            url = folderName
            title = folderName
            update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
            markAsNovel(epubHash)
        }
        val manga = networkToLocalManga(sManga.toDomainManga(LocalNovelSource.ID))
        updateManga.await(MangaUpdate(id = manga.id, memo = manga.memo.withNovelMemo(epubHash)))

        updateMangaFromRemote(manga, fetchDetails = true, fetchChapters = true, manualFetch = true)
            .onFailure { logcat(LogPriority.WARN, it) { "Could not refresh $folderName after import" } }

        return NovelImportResult.Success(mangaId = manga.id, title = folderName)
    }

    private fun writeDetailsOverride(folder: UniFile, title: String) {
        val file = folder.createFile(NOVEL_DETAILS_FILE) ?: return
        val encoded = json.encodeToString(MangaDetails.serializer(), MangaDetails(title = title))
        file.openOutputStream().use { it.write(encoded.toByteArray()) }
    }

    private fun extractCover(book: UniFile, folder: UniFile, overwrite: Boolean = true) {
        if (!overwrite && coverManager.findIn(folder) != null) return
        try {
            book.novelEpubReader(context).use { epub ->
                val entry = epub.coverEntry ?: return
                epub.readEntry(entry)?.let { coverManager.writeInto(folder, it) }
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Could not extract a cover for ${folder.name}" }
        }
    }

    private fun copyWithHash(source: UniFile, target: UniFile): String {
        val digest = MessageDigest.getInstance("MD5")
        source.openInputStream().use { input ->
            DigestInputStream(input, digest).use { hashing ->
                target.openOutputStream().use { output -> hashing.copyTo(output) }
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun buildFolderName(title: String): String = DiskUtil.buildValidFilename(
        origName = title,
        maxBytes = DiskUtil.MAX_FILE_NAME_BYTES - SUFFIX_RESERVE_BYTES,
        disallowNonAscii = libraryPreferences.disallowNonAsciiFilenames.get(),
    )

    /** Null when too many copies of the same title already exist to keep counting. */
    private fun nextFreeName(baseDirectory: UniFile, folderName: String): String? {
        for (suffix in 2..MAX_DUPLICATE_SUFFIX) {
            val candidate = "$folderName ($suffix)"
            if (baseDirectory.findFile(candidate) == null) return candidate
        }
        return null
    }

    private fun NovelEpubFailure.toImportFailure(): NovelImportFailure = when (this) {
        NovelEpubFailure.UNREADABLE -> NovelImportFailure.UNREADABLE
        NovelEpubFailure.MISSING_PACKAGE -> NovelImportFailure.MISSING_PACKAGE
        NovelEpubFailure.EMPTY_SPINE -> NovelImportFailure.EMPTY_SPINE
        NovelEpubFailure.DRM_PROTECTED -> NovelImportFailure.DRM_PROTECTED
    }

    private companion object {
        const val DEFAULT_TITLE = "Novel"

        /** Holds the outgoing `book.epub` while a replacement is being swapped in. */
        const val PREVIOUS_SUFFIX = ".previous"

        /** Room left in the folder name for a " (99)" disambiguation suffix. */
        const val SUFFIX_RESERVE_BYTES = 6

        const val MAX_DUPLICATE_SUFFIX = 99
    }
}
