package leaf.novel.ui.reader

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ViewModelAssistedFactoryKey
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.track.interactor.TrackChapter
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import leaf.novel.api.NovelChapterContent
import leaf.novel.api.NovelSource
import leaf.novel.data.epub.NovelEpubException
import leaf.novel.data.epub.novelEpubReader
import leaf.novel.source.local.LocalNovelSource
import leaf.novel.source.local.io.NovelFileSystem
import leaf.novel.ui.reader.loader.EpubContentProvider
import leaf.novel.ui.reader.loader.NovelContentProvider
import leaf.novel.ui.reader.loader.NovelEpubAssetServer
import leaf.novel.ui.reader.loader.SourceContentProvider
import leaf.novel.ui.reader.setting.NovelReaderPreferences
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock

/** Why the reader could not show something. The first four are fatal; the last is per-chapter. */
enum class NovelReaderError {
    MANGA_NOT_FOUND,
    SOURCE_MISSING,
    BOOK_MISSING,
    BOOK_UNREADABLE,
    NO_CHAPTERS,
    CHAPTER_MISSING,
}

/**
 * Owns one reading session: the chapter list, which chapter is open, and how far through it the
 * reader has scrolled.
 *
 * It deliberately does not know how a chapter's text is fetched. [NovelContentProvider] hides that,
 * because a web novel streams from its extension while an imported one is read out of an archive,
 * and only [load] has to tell the two apart.
 *
 * Positions are not written on every scroll. They accumulate in [pendingProgress] and are flushed
 * on a debounce, and again on pause, on chapter change and on close, so a long chapter that is
 * never left still records where the reader got to.
 */
@AssistedInject
class NovelReaderViewModel(
    @Assisted private val savedState: SavedStateHandle,
    private val context: Context,
    private val getManga: GetManga,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val updateChapter: UpdateChapter,
    private val upsertHistory: UpsertHistory,
    private val trackChapter: TrackChapter,
    private val trackPreferences: TrackPreferences,
    private val getIncognitoState: GetIncognitoState,
    private val fileSystem: NovelFileSystem,
    private val downloadProvider: DownloadProvider,
    private val sourceManager: SourceManager,
    val readerPreferences: ReaderPreferences,
    val novelReaderPreferences: NovelReaderPreferences,
) : ViewModel() {

    @AssistedFactory
    @ViewModelAssistedFactoryKey(NovelReaderViewModel::class)
    @ContributesIntoMap(AppScope::class)
    fun interface Factory : ViewModelAssistedFactory {
        override fun create(extras: CreationExtras): NovelReaderViewModel {
            return create(extras.createSavedStateHandle())
        }

        fun create(@Assisted savedState: SavedStateHandle): NovelReaderViewModel
    }

    private val mangaId = savedState.get<Long>(EXTRA_MANGA) ?: INVALID_ID
    private val initialChapterId = savedState.get<Long>(EXTRA_CHAPTER) ?: INVALID_ID

    val hasValidArgs = mangaId != INVALID_ID

    /** Survives process death, mirroring the manga reader's `chapter_id` key. */
    private var restoredChapterId: Long
        get() = savedState.get<Long>(SAVED_CHAPTER_ID) ?: INVALID_ID
        set(value) {
            savedState[SAVED_CHAPTER_ID] = value
        }

    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState.asStateFlow()

    private var provider: NovelContentProvider? = null

    private val incognitoMode: Boolean by lazy { getIncognitoState.await(state.value.manga?.source) }

    /** Latest reported scroll percent per chapter, flushed on pause and on chapter change. */
    private val pendingProgress = ConcurrentHashMap<Long, Int>()

    private val chaptersMarkedRead = mutableSetOf<Long>()

    private var chapterReadStartTime: Long? = null

    /**
     * Debounce signal for [pendingProgress]. Without it the reader would only write on pause and on
     * chapter change, so a crash or a kill part-way through a long chapter would lose the position
     * entirely — and the chapter would not be marked read until the reader was left.
     */
    private val progressTicks = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    init {
        if (hasValidArgs) {
            viewModelScope.launch { load() }
            progressTicks
                .debounce(PROGRESS_DEBOUNCE_MS)
                .onEach { flushProgress() }
                .launchIn(viewModelScope)
        }
    }

    override fun onCleared() {
        runCatching { provider?.close() }
        provider = null
    }

    private suspend fun load() {
        val manga = getManga.await(mangaId)
        if (manga == null) {
            mutableState.update { it.copy(isLoading = false, error = NovelReaderError.MANGA_NOT_FOUND) }
            return
        }

        val chapters = getChaptersByMangaId.await(manga.id, applyScanlatorFilter = true)
            // Ascending reading order, the same ordering `GetNextChapters` uses.
            .sortedWith(getChapterSort(manga, sortDescending = false))
        if (chapters.isEmpty()) {
            mutableState.update { it.copy(manga = manga, isLoading = false, error = NovelReaderError.NO_CHAPTERS) }
            return
        }

        // A web novel streams its chapters from the source; an imported one is read out of its
        // archive. LocalNovelSource is not a NovelSource and correctly takes the EPUB path.
        val contentProvider: NovelContentProvider
        val source = sourceManager.get(manga.source)
        if (source == null) {
            // A web novel whose extension has been uninstalled. Without this it would fall through
            // to the EPUB branch and report a missing book file, which names the wrong cause.
            mutableState.update { it.copy(manga = manga, isLoading = false, error = NovelReaderError.SOURCE_MISSING) }
            return
        }
        if (source is NovelSource) {
            contentProvider = SourceContentProvider(source, manga, downloadProvider)
        } else {
            val bookFile = fileSystem.getBookFile(manga.url)
            if (bookFile == null) {
                mutableState.update { it.copy(manga = manga, isLoading = false, error = NovelReaderError.BOOK_MISSING) }
                return
            }

            val opened = withIOContext {
                runCatching { EpubContentProvider(bookFile.novelEpubReader(context), manga.url) }
            }
            contentProvider = opened.getOrElse { failure ->
                logcat(LogPriority.ERROR, failure) { "Could not open book.epub for ${manga.url}" }
                val reason = if (failure is NovelEpubException) {
                    NovelReaderError.BOOK_UNREADABLE
                } else {
                    NovelReaderError.BOOK_MISSING
                }
                mutableState.update { it.copy(manga = manga, isLoading = false, error = reason) }
                return
            }
        }
        provider = contentProvider

        // A restored session wins over the requested chapter, so a process kill puts the reader back
        // where it was rather than where the notification originally pointed.
        val targetId = listOf(restoredChapterId, initialChapterId).firstOrNull { id ->
            id != INVALID_ID && chapters.any { it.id == id }
        }
        val startIndex = chapters.indexOfFirst { it.id == targetId }.coerceAtLeast(0)

        chaptersMarkedRead += chapters.filter { it.read }.map { it.id }
        restoredChapterId = chapters[startIndex].id
        restartReadTimer()

        mutableState.update {
            it.copy(manga = manga, chapters = chapters, currentIndex = startIndex, isLoading = false)
        }
    }

    /** Loads one chapter's body. The reader never touches [leaf.novel.data.epub.NovelEpubReader] itself. */
    suspend fun chapterContent(chapter: Chapter): Result<NovelChapterContent> {
        val contentProvider = provider ?: return Result.failure(IllegalStateException("Reader closed"))
        return runCatching { contentProvider.content(chapter) }
            .onFailure { logcat(LogPriority.WARN, it) { "Could not load chapter ${chapter.url}" } }
    }

    fun assetServer(): NovelEpubAssetServer? = provider?.let(::NovelEpubAssetServer)

    fun setCurrentChapter(index: Int) {
        val chapter = state.value.chapters.getOrNull(index) ?: return
        if (state.value.currentIndex == index) return

        viewModelScope.launchNonCancellable { flushProgress() }
        restoredChapterId = chapter.id
        restartReadTimer()
        mutableState.update { it.copy(currentIndex = index) }
    }

    /**
     * Follows a link to another document in the same book, which is how an EPUB's own footnote and
     * "next chapter" links are written. Silently ignored when it does not name a chapter we have.
     */
    fun openChapterByEntry(entry: String) {
        val novelUrl = state.value.manga?.url ?: return
        val target = "$novelUrl/${entry.substringBefore('#')}"
        val index = state.value.chapters.indexOfFirst { it.url == target }
        if (index >= 0) setCurrentChapter(index)
    }

    fun toggleMenu() = mutableState.update { it.copy(menuVisible = !it.menuVisible) }

    fun setBrightnessOverlayValue(value: Int) = mutableState.update { it.copy(brightnessOverlayValue = value) }

    /** Records how far through [chapterId] the reader has scrolled, as a percent in 0..100. */
    fun reportProgress(chapterId: Long, percent: Int) {
        if (incognitoMode) return
        pendingProgress[chapterId] = percent.coerceIn(0, 100)
        progressTicks.tryEmit(Unit)
    }

    /** Writes every pending position. Called on pause, on chapter change and on close. */
    suspend fun flushProgress() {
        if (incognitoMode) {
            pendingProgress.clear()
            return
        }
        val snapshot = pendingProgress.toMap()
        pendingProgress.keys.removeAll(snapshot.keys)
        snapshot.forEach { (chapterId, percent) -> persistProgress(chapterId, percent) }
    }

    private suspend fun persistProgress(chapterId: Long, percent: Int) {
        val chapter = state.value.chapters.firstOrNull { it.id == chapterId } ?: return
        val completed = percent >= COMPLETION_THRESHOLD
        val alreadyRead = chapterId in chaptersMarkedRead

        updateChapter.await(
            ChapterUpdate(
                id = chapterId,
                read = alreadyRead || completed,
                lastPageRead = percent.toLong(),
            ),
        )

        if (completed && !alreadyRead) {
            chaptersMarkedRead += chapterId
            trackChapterRead(chapter)
        }
    }

    private suspend fun trackChapterRead(chapter: Chapter) {
        if (!trackPreferences.autoUpdateTrack.get()) return
        if (!chapter.isRecognizedNumber) return
        runCatching { trackChapter.await(context, mangaId, chapter.chapterNumber) }
            .onFailure { logcat(LogPriority.WARN, it) { "Could not push tracking progress" } }
    }

    fun restartReadTimer() {
        chapterReadStartTime = Clock.System.now().toEpochMilliseconds()
    }

    /** Writes the reading-session entry. Mirrors `ReaderViewModel.updateHistory`. */
    private suspend fun updateHistory() {
        if (incognitoMode) return
        val chapter = state.value.currentChapter ?: return
        val endTime = Date()
        val duration = chapterReadStartTime?.let { endTime.time - it } ?: 0
        upsertHistory.await(HistoryUpdate(chapter.id, endTime, duration))
        chapterReadStartTime = null
    }

    /** Saves progress and history without being cancelled by the activity going away. */
    fun saveOnPause() {
        viewModelScope.launchNonCancellable {
            flushProgress()
            updateHistory()
        }
    }

    fun toggleBookmark() {
        val chapter = state.value.currentChapter ?: return
        val bookmarked = !chapter.bookmark
        viewModelScope.launchNonCancellable {
            updateChapter.await(ChapterUpdate(id = chapter.id, bookmark = bookmarked))
            mutableState.update { current ->
                current.copy(
                    chapters = current.chapters.map {
                        if (it.id == chapter.id) it.copy(bookmark = bookmarked) else it
                    },
                )
            }
        }
    }

    @Immutable
    data class State(
        val manga: Manga? = null,
        val chapters: List<Chapter> = emptyList(),
        val currentIndex: Int = 0,
        val isLoading: Boolean = true,
        val menuVisible: Boolean = false,
        val error: NovelReaderError? = null,
        val brightnessOverlayValue: Int = 0,
    ) {
        val currentChapter: Chapter? get() = chapters.getOrNull(currentIndex)
    }

    companion object {
        /** Trailing whitespace and short final paragraphs mean a reader rarely hits a literal 100. */
        const val COMPLETION_THRESHOLD = 95

        /** How long the reader must sit still before its position is written. */
        private const val PROGRESS_DEBOUNCE_MS = 400L

        const val EXTRA_MANGA = "manga"
        const val EXTRA_CHAPTER = "chapter"

        private const val SAVED_CHAPTER_ID = "chapter_id"
        private const val INVALID_ID = -1L
    }
}
