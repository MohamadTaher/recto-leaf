package leaf.novel.source

import android.content.Context
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import leaf.novel.epub.NovelEpubReader
import leaf.novel.epub.novelEpubReader
import leaf.novel.io.NOVEL_BOOK_FILE
import leaf.novel.io.NOVEL_TMP_SUFFIX
import leaf.novel.io.NovelCoverManager
import leaf.novel.io.NovelFileSystem
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.core.metadata.tachiyomi.MangaDetails
import tachiyomi.i18n.MR
import tachiyomi.source.local.filter.OrderBy
import tachiyomi.domain.source.model.Source as DomainSource

/** Metadata override file inside a novel folder, using the existing [MangaDetails] shape. */
const val NOVEL_DETAILS_FILE = "details.json"

/**
 * Serves `<storage>/novels/` as a Mihon source, the way [tachiyomi.source.local.LocalSource] serves
 * `local/`. One folder is one novel; one EPUB spine item is one chapter.
 *
 * Novels cannot come from `LocalSource` because `Format.valueOf` classifies `.epub` as an image
 * format and hands it to the image reader. See plans/04 (D2, D7).
 */
@Inject
@SingleIn(AppScope::class)
class LocalNovelSource(
    private val context: Context,
    private val json: Json,
    private val fileSystem: NovelFileSystem,
    private val coverManager: NovelCoverManager,
) : Source, UnmeteredSource {

    private val popularFilters = FilterList(OrderBy.Popular(context))

    private val latestFilters = FilterList(OrderBy.Latest(context))

    override val id: Long = ID

    override val name: String = context.stringResource(MR.strings.leaf_novel_source)

    override val lang: String = "other"

    override val supportsLatest: Boolean = true

    override fun toString() = name

    override fun getFilterList() = FilterList(OrderBy.Popular(context))

    override suspend fun getPopularManga(page: Int) = getSearchManga(page, "", popularFilters)

    override suspend fun getLatestUpdates(page: Int) = getSearchManga(page, "", latestFilters)

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage = withIOContext {
        var novelDirs = fileSystem.getFilesInBaseDirectory()
            .filter { it.isNovelDirectory() }
            .distinctBy { it.name }
            .filter { query.isBlank() || it.name.orEmpty().contains(query, ignoreCase = true) }

        filters.forEach { filter ->
            when (filter) {
                is OrderBy.Popular -> {
                    novelDirs = if (filter.state!!.ascending) {
                        novelDirs.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name.orEmpty() })
                    } else {
                        novelDirs.sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.name.orEmpty() })
                    }
                }
                is OrderBy.Latest -> {
                    novelDirs = if (filter.state!!.ascending) {
                        novelDirs.sortedBy(UniFile::lastModified)
                    } else {
                        novelDirs.sortedByDescending(UniFile::lastModified)
                    }
                }
                else -> {
                    /* Do nothing */
                }
            }
        }

        val novels = novelDirs.map { dir ->
            val folderName = dir.name.orEmpty()
            SManga.create().apply {
                title = folderName
                url = folderName
                update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
                coverManager.find(folderName)?.let { thumbnail_url = it.uri.toString() }
            }
        }

        MangasPage(novels, false)
    }

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = withIOContext {
        // Re-assert the update strategy on every refresh, whatever was asked for:
        // UpdateMangaFromRemote writes update_strategy back regardless of the flags.
        manga.update_strategy = UpdateStrategy.ONLY_FETCH_ONCE

        val bookFile = fileSystem.getBookFile(manga.url)
            ?: return@withIOContext SMangaUpdate(manga, chapters)

        bookFile.novelEpubReader(context).use { epub ->
            if (fetchDetails) fillDetails(manga, epub)
            val updatedChapters = if (fetchChapters) buildChapters(manga, epub, bookFile) else chapters
            SMangaUpdate(manga, updatedChapters)
        }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = throw UnsupportedOperationException("Unused")

    /**
     * Metadata precedence: `details.json` beats the EPUB's Dublin Core, which beats the folder name.
     * Cover precedence: an existing `cover.*` beats the EPUB's declared cover.
     */
    private fun fillDetails(manga: SManga, epub: NovelEpubReader) {
        val metadata = epub.metadata
        manga.title = metadata.title ?: manga.url
        manga.author = metadata.author
        manga.description = metadata.description
        manga.genre = metadata.genres.takeIf { it.isNotEmpty() }?.joinToString()
        manga.status = SManga.UNKNOWN

        readDetailsOverride(manga.url)?.run {
            title?.let { manga.title = it }
            author?.let { manga.author = it }
            artist?.let { manga.artist = it }
            description?.let { manga.description = it }
            genre?.let { manga.genre = it.joinToString() }
            status?.let { manga.status = it }
        }

        val existingCover = coverManager.find(manga.url)
        if (existingCover != null) {
            manga.thumbnail_url = existingCover.uri.toString()
        } else {
            epub.coverEntry?.let { entry ->
                epub.readEntry(entry)?.let { coverManager.update(manga, it) }
            }
        }
    }

    private fun readDetailsOverride(novelUrl: String): MangaDetails? {
        val file = fileSystem.getFilesInNovelDirectory(novelUrl)
            .firstOrNull { it.isFile && it.name == NOVEL_DETAILS_FILE }
            ?: return null
        return try {
            file.openInputStream().use { json.decodeFromStream<MangaDetails>(it) }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error reading $NOVEL_DETAILS_FILE for $novelUrl" }
            null
        }
    }

    /**
     * One spine item is one chapter, keyed by `<folder>/<spine href>` so that replacing `book.epub`
     * with a longer version matches existing rows by URL and keeps their read state.
     *
     * The list is returned in reverse spine order because [SyncChaptersWithSource] assigns
     * `source_order` from the list index and Mihon reads a title from the highest `source_order`
     * down. Reversing here is what makes the chapter list and "continue reading" agree with the
     * novel's actual reading order.
     */
    private fun buildChapters(manga: SManga, epub: NovelEpubReader, bookFile: UniFile): List<SChapter> {
        val uploadDate = epub.metadata.date ?: bookFile.lastModified()
        return epub.spine.mapIndexed { index, item ->
            SChapter.create().apply {
                url = "${manga.url}/${item.href}"
                name = item.title ?: item.href.fileNameWithoutExtension()
                chapter_number = (index + 1).toFloat()
                date_upload = uploadDate
            }
        }.asReversed()
    }

    private fun UniFile.isNovelDirectory(): Boolean {
        val dirName = name.orEmpty()
        return isDirectory &&
            !dirName.startsWith('.') &&
            !dirName.endsWith(NOVEL_TMP_SUFFIX) &&
            findFile(NOVEL_BOOK_FILE)?.isFile == true
    }

    private fun String.fileNameWithoutExtension(): String =
        substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')

    companion object {
        /**
         * Extension source ids are the first 64 bits of an MD5 with the sign bit cleared, so a
         * negative id can never collide. `0L` is LocalSource and `-1L` is used as an invalid-id
         * sentinel elsewhere. See plans/02 (D7).
         */
        const val ID = -2L
    }
}

fun Source.isNovelSource(): Boolean = id == LocalNovelSource.ID

fun DomainSource.isNovelSource(): Boolean = id == LocalNovelSource.ID
