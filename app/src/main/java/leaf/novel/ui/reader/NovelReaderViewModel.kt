package leaf.novel.ui.reader

import android.content.Context
import android.net.Uri
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
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.track.interactor.TrackChapter
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import leaf.novel.api.NovelChapterContent
import leaf.novel.api.NovelSource
import leaf.novel.data.backup.NovelSettingsTransfer
import leaf.novel.data.epub.NovelEpubException
import leaf.novel.data.epub.novelEpubReader
import leaf.novel.source.local.LocalNovelSource
import leaf.novel.source.local.io.NovelFileSystem
import leaf.novel.ui.reader.comments.NovelCommentCache
import leaf.novel.ui.reader.comments.NovelCommentMatcher
import leaf.novel.ui.reader.comments.NovelComments
import leaf.novel.ui.reader.loader.EpubContentProvider
import leaf.novel.ui.reader.loader.NovelContentProvider
import leaf.novel.ui.reader.loader.NovelEpubAssetServer
import leaf.novel.ui.reader.loader.SourceContentProvider
import leaf.novel.ui.reader.setting.NovelReaderAction
import leaf.novel.ui.reader.setting.NovelReaderPreferences
import leaf.novel.ui.reader.setting.NovelReaderTheme
import leaf.novel.ui.reader.setting.NovelSpeechDivision
import logcat.LogPriority
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getAndSet
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.source.service.SourceManager
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

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
    private val updateManga: UpdateManga,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val updateChapter: UpdateChapter,
    private val upsertHistory: UpsertHistory,
    private val trackChapter: TrackChapter,
    private val trackPreferences: TrackPreferences,
    private val getIncognitoState: GetIncognitoState,
    private val fileSystem: NovelFileSystem,
    private val downloadProvider: DownloadProvider,
    private val downloadManager: DownloadManager,
    private val downloadPreferences: DownloadPreferences,
    private val sourceManager: SourceManager,
    private val sourcePreferences: SourcePreferences,
    private val preferenceStore: PreferenceStore,
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

    /**
     * The discussion under whatever chapter is open, for a source that serves one.
     *
     * Not part of [State]: nothing about a comment changes how the chapter is drawn, and folding a
     * reply has no business recomposing the reader. The chapter's own comments only — the novel's
     * belong to the screen that describes the novel, not to a tab over the chapter being read.
     */
    val comments = NovelComments(
        scope = viewModelScope,
        preferences = novelReaderPreferences,
        matcher = NovelCommentMatcher.installed(sourceManager, sourcePreferences),
        cache = NovelCommentCache.shared,
    )

    private var provider: NovelContentProvider? = null

    /** Chapter bodies already fetched for the open chapter and its three-chapter lookahead. */
    private val chapterLoads = ConcurrentHashMap<Long, Deferred<Result<NovelChapterContent>>>()

    // Resolved in load(): the incognito check is suspend, so it cannot be a lazy property.
    private var incognitoMode: Boolean = false

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
        NovelReaderMediaSession.detachReader()
        // Speech that is still running belongs to NovelSpeechSession now, not to this reader —
        // detach() only tears it down if nothing is left running to fire that later itself.
        NovelSpeechSession.detach()
        runCatching { provider?.close() }
        provider = null
    }

    private suspend fun load() {
        val manga = getManga.await(mangaId)
        if (manga == null) {
            mutableState.update { it.copy(isLoading = false, error = NovelReaderError.MANGA_NOT_FOUND) }
            return
        }

        incognitoMode = getIncognitoState.await(manga.source)

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
            it.copy(
                manga = manga,
                chapters = chapters.openedAt(startIndex),
                currentIndex = startIndex,
                isLoading = false,
            )
        }
        comments.bind(source, manga)
        comments.setChapter(chapters[startIndex])
        preloadChapters(startIndex)
        // Speech may already be running from a reader that has since been destroyed — attach to
        // it rather than showing a stopped reader over audio that is still playing. Only when it
        // is this novel's own session: a running session belonging to a different novel is left
        // alone, not renamed and not extended with this novel's chapters.
        if (NovelSpeechSession.isSpeaking() && NovelSpeechSession.queue.belongsTo(mangaId)) {
            attachToSession()
        }
    }

    /**
     * One chapter's body, without making it the open one.
     *
     * Speech reads ahead into the chapter after the one on screen, and that fetch must not disturb
     * anything the reader is currently looking at.
     */
    private suspend fun loadChapter(chapter: Chapter): Result<NovelChapterContent> {
        val contentProvider = provider ?: return Result.failure(IllegalStateException("Reader closed"))
        return runCatching { contentProvider.content(chapter) }
            .onFailure { logcat(LogPriority.WARN, it) { "Could not load chapter ${chapter.url}" } }
    }

    /** One cached fetch, shared by the visible document, chapter skips and speech read-ahead. */
    private fun chapterLoad(chapter: Chapter): Deferred<Result<NovelChapterContent>> =
        chapterLoads.computeIfAbsent(chapter.id) {
            viewModelScope.async { loadChapter(chapter) }
        }

    /** Failed speculative fetches are not cached; opening the chapter gets a real retry. */
    private suspend fun awaitChapter(chapter: Chapter): Result<NovelChapterContent> {
        val load = chapterLoad(chapter)
        return load.await().also { result ->
            if (result.isFailure) chapterLoads.remove(chapter.id, load)
        }
    }

    /** Starts the current chapter and the next three without waiting for the reader to reach them. */
    private fun preloadChapters(index: Int) {
        state.value.chapters
            .drop(index.coerceAtLeast(0))
            .take(PRELOAD_CHAPTER_COUNT + 1)
            .forEach(::chapterLoad)
        preloadComments(index)
    }

    /**
     * Starts the comments on the chapters either side of the open one.
     *
     * Either side rather than the same three-chapter lookahead the text gets: comments are one
     * request per page at a site that may be rate limiting, where a chapter's text is one request
     * at a host that expects to serve it. The open chapter's own are already started by
     * [NovelComments.setChapter].
     */
    private fun preloadComments(index: Int) {
        val chapters = state.value.chapters
        comments.prefetch(listOfNotNull(chapters.getOrNull(index - 1), chapters.getOrNull(index + 1)))
    }

    /** Keeps one chapter behind and the same three-chapter lookahead in the in-memory cache. */
    fun trimChapterCache(index: Int) {
        val keep = state.value.chapters
            .drop((index - 1).coerceAtLeast(0))
            .take(PRELOAD_CHAPTER_COUNT + 2)
            .mapTo(mutableSetOf()) { it.id }
        chapterLoads.keys.filterNot(keep::contains).forEach(chapterLoads::remove)
        comments.trim(keep)
    }

    /** A fetched chapter paired back to the row whose title and progress identify it. */
    data class LoadedChapter(
        val chapter: Chapter,
        val content: Result<NovelChapterContent>,
    )

    /** Fetches one more chapter as a continuous document rolls its preload window forward. */
    suspend fun loadedChapter(index: Int): LoadedChapter? {
        val chapter = state.value.chapters.getOrNull(index) ?: return null
        val content = awaitChapter(chapter)
        if (state.value.currentIndex == index) {
            content.getOrNull()?.let {
                currentHtml = it.html
                val words = withIOContext { NovelReadingTime.wordsIn(it.html) }
                if (state.value.currentIndex == index) {
                    mutableState.update { state -> state.copy(chapterWords = words) }
                }
            }
        }
        return LoadedChapter(chapter, content)
    }

    fun assetServer(): NovelEpubAssetServer? = provider?.let(::NovelEpubAssetServer)

    /**
     * The bytes behind an image in the open chapter, for the full-screen view.
     *
     * Only images the reader itself is serving: anything outside the virtual origin is either a
     * remote URL the WebView already refused to load or a data URI the page has inline, and neither
     * has bytes to fetch from here.
     */
    suspend fun imageBytes(url: String): ByteArray? = withIOContext {
        val path = NovelEpubAssetServer.pathFor(url) ?: return@withIOContext null
        runCatching { provider?.resourceStream(path)?.use { it.readBytes() } }
            .onFailure { logcat(LogPriority.WARN, it) { "Could not read image $path" } }
            .getOrNull()
    }

    /** Opens another chapter. */
    fun setCurrentChapter(index: Int, continuous: Boolean = false) {
        val chapter = state.value.chapters.getOrNull(index) ?: return
        if (state.value.currentIndex == index) return

        // The time spent in the chapter being left is written against it before the clock restarts,
        // as ReaderViewModel does on every chapter change.
        val history = historyUpdate()
        viewModelScope.launchNonCancellable {
            flushProgress()
            history?.let { upsertHistory.await(it) }
        }
        restoredChapterId = chapter.id
        restartReadTimer()
        // Comments belong to the chapter they are under, so crossing a boundary invalidates them
        // exactly as it invalidates the search and the auto scroll below.
        comments.setChapter(chapter)
        // Neither auto scroll nor a search carries across a chapter boundary. Speech does, and a
        // continuous document is one the reader crosses by scrolling, so neither stops there.
        if (!continuous) stopSpeaking()
        stopSpeedReading()
        mutableState.update {
            it.copy(
                // Scrolled into, a chapter is already on screen where it should be.
                chapters = if (continuous) it.chapters else it.chapters.openedAt(index),
                currentIndex = index,
                autoScrolling = it.autoScrolling && continuous,
                searchQuery = it.searchQuery.takeIf { continuous },
            )
        }
        preloadChapters(index)

        viewModelScope.launch {
            awaitChapter(chapter).getOrNull()?.let { content ->
                currentHtml = content.html
                val words = withIOContext { NovelReadingTime.wordsIn(content.html) }
                if (state.value.currentIndex == index) {
                    mutableState.update { it.copy(chapterWords = words) }
                }
            }
        }
    }

    /**
     * Opens a chapter already read at its start, as `ChapterLoader` does for the image reader.
     *
     * Only in memory: the stored position is left until the reader moves, so a chapter opened and
     * closed again has lost nothing.
     */
    private fun List<Chapter>.openedAt(index: Int): List<Chapter> {
        val chapter = getOrNull(index)?.takeIf { it.read && it.lastPageRead > 0 } ?: return this
        return toMutableList().apply { this[index] = chapter.copy(lastPageRead = 0) }
    }

    /**
     * Finds the chapter named by an EPUB's own footnote or "next chapter" link.
     */
    fun chapterIndexByEntry(entry: String): Int? {
        val novelUrl = state.value.manga?.url ?: return null
        val target = "$novelUrl/${entry.substringBefore('#')}"
        val index = state.value.chapters.indexOfFirst { it.url == target }
        return index.takeIf { it >= 0 }
    }

    /**
     * Auto scroll stops whenever the chrome comes up. A page still creeping behind an open
     * settings dialog is the obvious way for this to go wrong.
     */
    fun toggleMenu() = mutableState.update {
        val visible = !it.menuVisible
        it.copy(menuVisible = visible, autoScrolling = it.autoScrolling && !visible)
    }

    /** Forces the chrome up, for an action that needs something anchored to it. */
    fun showMenu() = mutableState.update { it.copy(menuVisible = true, autoScrolling = false) }

    /** Auto scroll and speech both move the page; starting either has to stop the other. */
    fun setAutoScrolling(enabled: Boolean) {
        if (enabled) stopSpeaking()
        mutableState.update { it.copy(autoScrolling = enabled, speedReading = it.speedReading && !enabled) }
    }

    // region Speech

    /** The chapter's own markup, kept so speech can be cut from it without re-fetching. */
    private var currentHtml: String? = null

    /**
     * Which [NovelSpeechSession.generation] this ViewModel last wired into [state], or
     * [NO_GENERATION] if it never has. A stop-then-play cycle builds a new engine and a new
     * generation, so comparing against the *current* generation — rather than a one-way "have I
     * ever attached" flag — is what lets this reader wire a fresh collector to it (M4).
     */
    private var attachedGeneration = NO_GENERATION

    private var speechExtendJob: Job? = null

    /**
     * Starts at the same tracked chapter position used by manual reading and speech progress.
     *
     * The utterances are cut afresh each time, so changing how the chapter is divided takes effect
     * on the next start rather than needing the chapter reopened.
     */
    fun startSpeaking() {
        if (state.value.speaking) return
        queueSpeech(state.value.currentChapter?.lastPageRead?.toInt() ?: 0, state.value.readingPosition)
    }

    /** Rebuilds the queue when its division changes while the control sheet is open. */
    fun restartSpeaking(percentRead: Int, paused: Boolean, anchor: NovelSpeech.Anchor? = state.value.readingPosition) {
        queueSpeech(percentRead, anchor)
        if (paused) NovelSpeechSession.speakerOrNull()?.pause()
    }

    /**
     * One chapter's markup as the pieces speech says.
     *
     * The replacement rules are also the TTS character filters: visible and spoken prose use the
     * same existing mechanism rather than maintaining two almost-identical rule lists.
     */
    private fun utterancesOf(html: String, chapterId: Long): List<NovelSpeech.Position> {
        val spokenHtml = NovelTextReplacements.apply(
            html,
            NovelTextReplacements.combine(
                novelReaderPreferences.textReplacements.get(),
                novelTextReplacements(),
            ),
        )
        return NovelSpeech.positions(spokenHtml, novelReaderPreferences.speechDivision.get(), chapterId)
    }

    private fun queueSpeech(percentRead: Int, anchor: NovelSpeech.Anchor?) {
        val html = currentHtml ?: return
        val chapterId = state.value.chapters.getOrNull(state.value.currentIndex)?.id ?: return
        val utterances = utterancesOf(html, chapterId)
        if (utterances.isEmpty()) return

        stopSpeedReading()
        // A fresh queue: whatever had been read ahead belongs to a run that no longer exists.
        speechExtendJob?.cancel()
        val engine = attachToSession()

        val bookmark = state.value.speechPosition.takeIf {
            percentRead == state.value.currentChapter?.lastPageRead?.toInt()
        }
        val fromIndex = NovelSpeech.resumeIndex(percentRead, utterances, bookmark, anchor)
        NovelSpeechSession.queue.start(mangaId, utterances, state.value.currentIndex, records = !incognitoMode)
        mutableState.update {
            it.copy(
                autoScrolling = false,
                searchQuery = null,
                speechPosition = utterances[fromIndex],
                speechIndex = fromIndex,
                speechCount = utterances.size,
            )
        }
        engine.start(
            text = utterances,
            fromIndex = fromIndex,
            acrossParagraphs = novelReaderPreferences.speechDivision.get() == NovelSpeechDivision.PARAGRAPH,
            rate = novelReaderPreferences.speechRate.get(),
            pitch = novelReaderPreferences.speechPitch.get(),
            intervalMs = novelReaderPreferences.speechIntervalMs.get()
                .coerceIn(NovelReaderPreferences.SPEECH_INTERVAL_RANGE),
            mixAudio = novelReaderPreferences.speechMixAudio.get(),
        )
    }

    /**
     * Wires the session's engine into [state] — either because this reader is about to start
     * speaking, or because it opened onto a session already running — exactly once per engine
     * [NovelSpeechSession] is currently holding.
     *
     * Checking [attachedGeneration] against [NovelSpeechSession.generation], rather than a plain
     * "have I attached before" flag, is what makes a stop-then-play cycle work: `reset()` builds a
     * new engine with a new generation, so the check below sees a mismatch and wires a fresh
     * collector rather than returning early onto an engine nothing is listening to (M4).
     *
     * The engine's [NovelSpeaker.speech] is a `StateFlow`, so collecting it here immediately
     * reports whatever it is already doing; that single mechanism both starts a fresh run's
     * mirroring and adopts an inherited one.
     */
    private fun attachToSession(): NovelSpeaker {
        val engine = NovelSpeechSession.speaker(context)
        val generation = NovelSpeechSession.generation
        if (attachedGeneration == generation) return engine
        attachedGeneration = generation
        NovelSpeechSession.attach()

        // Seeded from the engine's current state, not hardcoded false: a `StateFlow` replays its
        // latest value to a new collector, so adopting a session already speaking must not look
        // like a false→true transition — that would re-arm the sleep timer from zero (M3).
        val lifecycle = NovelSpeechLifecycle(initiallySpeaking = engine.speech.value.speaking)
        var previousIndex = engine.speech.value.index
        engine.speech
            .onEach { speech ->
                val transition = lifecycle.observe(speech.speaking)
                when (transition) {
                    NovelSpeechLifecycle.Transition.STARTED -> scheduleSpeechStop()
                    NovelSpeechLifecycle.Transition.ENDED -> cancelSpeechStop()
                    NovelSpeechLifecycle.Transition.NONE -> Unit
                }
                if (speech.speaking || transition == NovelSpeechLifecycle.Transition.ENDED) {
                    val queue = NovelSpeechSession.queue
                    // Recorded before the chapter changes, so the flush that change makes writes it.
                    queue.chapterFinished(previousIndex, speech.index)?.let {
                        recordProgress(it, FINISHED_PERCENT, fromSpeech = true)
                    }
                    queue.chapterProgress(speech.index)?.let { (chapterId, percent) ->
                        val index = state.value.chapters.indexOfFirst { it.id == chapterId }
                        if (index >= 0) setCurrentChapter(index, continuous = true)
                        recordProgress(chapterId, percent, fromSpeech = true)
                    }
                    // Saying the last unit finishes its chapter, which the unit's start never reads as.
                    if (speech.finished) {
                        queue.lastChapterId?.let { recordProgress(it, FINISHED_PERCENT, fromSpeech = true) }
                    }
                }
                previousIndex = speech.index
                if (speech.speaking) extendSpeech(speech.index)
                holdProcessOpen(speech.speaking, speech.paused)
                val snapshot = NovelSpeechSession.queue.snapshot(speech)
                mutableState.update {
                    it.copy(
                        speaking = snapshot.speaking,
                        speechPaused = snapshot.paused,
                        speechIndex = snapshot.index,
                        speechCount = snapshot.count,
                        speechPosition = snapshot.position,
                        readingPosition = snapshot.position?.let { position ->
                            NovelSpeech.Anchor(position.chapterId, position.block, position.start)
                        } ?: it.readingPosition,
                        speechUnavailable = snapshot.unavailable,
                    )
                }
            }
            .launchIn(viewModelScope)
        return engine
    }

    /**
     * Keeps the process alive, and the controls reachable, while speech runs off screen.
     *
     * Backgrounding the reader would otherwise take the speech with it: Android gives a process
     * with nothing on screen and nothing declared no reason to keep running.
     */
    private fun holdProcessOpen(speaking: Boolean, paused: Boolean) {
        val chapter = state.value.currentChapter?.name.orEmpty()
        // Speech reports itself on every unit; the notification only says three things, so it is
        // only touched when one of them has actually changed.
        val shown = if (speaking) "$paused/$chapter" else null
        if (shown == speechNotification) return
        speechNotification = shown

        if (shown == null) {
            NovelSpeechService.hide(context)
            return
        }
        // The controls now belong to NovelSpeechSession, which outlives this ViewModel — wiring
        // it here rather than once keeps the notification pointed at whichever reader most
        // recently touched it, with no cost, since a repeat assignment is free.
        NovelSpeechService.controls = NovelSpeechSession
        NovelSpeechService.show(
            context = context,
            title = state.value.manga?.title.orEmpty(),
            chapter = chapter,
            paused = paused,
        )
    }

    /** What the notification currently says, or null while there is none. */
    private var speechNotification: String? = null

    /**
     * Carries speech over the end of a chapter.
     *
     * The paragraphs after the ones queued are added a few pieces before the voice runs out, so it
     * never stops at a chapter boundary. Nothing here moves the reader: the continuous document
     * already holds the chapters on either side of the open one, so the page follows the voice by
     * scrolling to the highlighted text and reports the chapter it lands in for itself. A paged
     * document holds one chapter, so the screen opens the next when the voice reaches it.
     *
     * With the reader gone, [state] and [awaitChapter] belong to a destroyed session and this
     * simply stops finding a next chapter — the queue finishes what it already has and speech
     * ends there. Moving chapter loading itself into the process-scoped session is out of scope.
     */
    private fun extendSpeech(engineIndex: Int) {
        if (speechExtendJob?.isActive == true) return
        val queue = NovelSpeechSession.queue
        if (queue.positions.size - engineIndex > SPEECH_STAGE_LOOKAHEAD) return
        val index = queue.chapterIndex + 1
        val chapter = state.value.chapters.getOrNull(index) ?: return

        speechExtendJob = viewModelScope.launch {
            val content = awaitChapter(chapter).getOrNull() ?: return@launch
            val more = utterancesOf(content.html, chapter.id)
            // Checked again on the way out: the fetch is slow enough for speech to have been
            // stopped, restarted or seeked somewhere else entirely while it ran.
            if (more.isEmpty() || !state.value.speaking) return@launch
            if (index != queue.chapterIndex + 1) return@launch

            queue.extend(more, index)
            NovelSpeechSession.speakerOrNull()?.extend(more)
        }
    }

    fun toggleSpeechPlayback() {
        when {
            !state.value.speaking -> startSpeaking()
            state.value.speechPaused -> NovelSpeechSession.speakerOrNull()?.resume()
            else -> NovelSpeechSession.speakerOrNull()?.pause()
        }
    }

    fun resumeSpeaking() {
        if (ownsSession()) NovelSpeechSession.play()
    }

    fun pauseSpeaking() {
        if (ownsSession()) NovelSpeechSession.pause()
    }

    fun seekSpeech(units: Int) {
        if (!ownsSession()) return
        NovelSpeechSession.seek(units)
    }

    /** Applies changed controls without rebuilding or re-fetching the chapter. */
    fun applySpeechSettings() {
        if (!ownsSession()) return
        NovelSpeechSession.speakerOrNull()?.update(
            rate = novelReaderPreferences.speechRate.get(),
            pitch = novelReaderPreferences.speechPitch.get(),
            intervalMs = novelReaderPreferences.speechIntervalMs.get()
                .coerceIn(NovelReaderPreferences.SPEECH_INTERVAL_RANGE),
            mixAudio = novelReaderPreferences.speechMixAudio.get(),
        )
    }

    /**
     * A reader that never attached to the running session — because it opened onto a different
     * novel's speech and correctly left it alone — must not be able to touch it: not stop it by
     * jumping a chapter or turning on auto scroll, and not seek or re-queue it with its own rate,
     * pitch or division settings (M6).
     */
    private fun ownsSession(): Boolean =
        attachedGeneration != NO_GENERATION &&
            attachedGeneration == NovelSpeechSession.generation &&
            NovelSpeechSession.queue.belongsTo(mangaId)

    fun stopSpeaking() {
        if (!ownsSession()) return
        cancelSpeechStop()
        speechExtendJob?.cancel()
        NovelSpeechSession.speakerOrNull()?.stop()
    }

    fun applySpeechTimer() {
        if (state.value.speaking) scheduleSpeechStop()
    }

    /**
     * Arms the sleep timer on [NovelSpeechSession]'s own scope, not this ViewModel's — a reader
     * being destroyed must not silently disarm a timer the user set.
     */
    private fun scheduleSpeechStop() {
        val after = novelReaderPreferences.speechStopAfterMinutes.get()
        NovelSpeechSession.stopTimer.arm(after.minutes) { NovelSpeechSession.stop() }
    }

    private fun cancelSpeechStop() {
        NovelSpeechSession.stopTimer.cancel()
    }

    // endregion

    // region Speed reading

    private var speedReadPhrases: List<String> = emptyList()

    /**
     * Starts or stops showing the chapter a phrase at a time, from where the reader is now.
     *
     * The phrases come from the same walk of the markup the remaining-time estimate uses, so
     * entering the mode costs one parse and no fetch. Auto scroll and speech both stop: three
     * things moving the page at once is not a mode.
     */
    fun toggleSpeedReading(percentRead: Int) {
        if (state.value.speedReading) {
            stopSpeedReading()
            return
        }

        val html = currentHtml ?: return
        val chunk = novelReaderPreferences.speedReadChunk.get()
            .coerceIn(NovelReaderPreferences.SPEED_READ_CHUNK_RANGE)
        val phrases = NovelReadingTime.words(html)
            .chunked(chunk) { words -> words.joinToString(" ") }
        if (phrases.isEmpty()) return

        stopSpeaking()
        speedReadPhrases = phrases
        // Picking up where the page is, rather than at the top: the mode is for reading on, not
        // for starting the chapter again.
        val from = (phrases.size * percentRead.coerceIn(0, 100) / 100).coerceIn(0, phrases.lastIndex)
        mutableState.update {
            it.copy(autoScrolling = false, speedReading = true, speedReadIndex = from)
        }
    }

    /** Steps to the next phrase, stopping at the end of the chapter. */
    fun advanceSpeedReading() = mutableState.update {
        val next = it.speedReadIndex + 1
        if (next >= speedReadPhrases.size) it.copy(speedReading = false) else it.copy(speedReadIndex = next)
    }

    fun stopSpeedReading() = mutableState.update { it.copy(speedReading = false) }

    /** Saves a rule list with this novel, so it follows the title through Mihon's normal backup. */
    fun setNovelTextReplacements(rules: String) {
        val manga = state.value.manga ?: return
        val memo = if (rules.isBlank() || rules == "[]") {
            JsonObject(manga.memo - NovelTextReplacements.MANGA_MEMO_KEY)
        } else {
            JsonObject(manga.memo + (NovelTextReplacements.MANGA_MEMO_KEY to JsonPrimitive(rules)))
        }
        val updated = manga.copy(memo = memo)
        mutableState.update { it.copy(manga = updated) }
        viewModelScope.launch {
            runCatching { updateManga.await(MangaUpdate(id = manga.id, memo = memo)) }
                .onFailure { logcat(LogPriority.WARN, it) { "Could not save text replacements" } }
        }
    }

    fun novelTextReplacements(): String =
        state.value.manga
            ?.memo
            ?.get(NovelTextReplacements.MANGA_MEMO_KEY)
            ?.jsonPrimitive
            ?.contentOrNull
            .orEmpty()

    // endregion

    // region Settings transfer

    /**
     * Writes every `leaf_novel_` preference to [target].
     *
     * Through the document picker's own URI, so the file lands wherever the reader keeps things and
     * no storage permission is involved.
     */
    suspend fun exportSettings(target: Uri): Boolean = withIOContext {
        runCatching {
            val backup = NovelSettingsTransfer.capture(preferenceStore.getAll())
            context.contentResolver.openOutputStream(target, "wt")?.use { out ->
                out.write(settingsJson.encodeToString(backup).toByteArray())
            } ?: error("Could not open $target")
        }
            .onFailure { logcat(LogPriority.ERROR, it) { "Could not export reader settings" } }
            .isSuccess
    }

    /** Reads a settings file back over the current settings. Overwrites; the caller confirms. */
    suspend fun importSettings(source: Uri): Boolean = withIOContext {
        runCatching {
            val text = context.contentResolver.openInputStream(source)?.use { it.readBytes() }
                ?: error("Could not open $source")
            NovelSettingsTransfer.apply(settingsJson.decodeFromString(text.decodeToString()), preferenceStore)
        }
            .onFailure { logcat(LogPriority.WARN, it) { "Could not import reader settings" } }
            .isSuccess
    }

    /** Lenient on read so a file written by a later stage still restores what this one understands. */
    private val settingsJson = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    /** What is on the overlay now, or null when the mode is off. */
    val speedReadPhrase: String?
        get() = if (state.value.speedReading) speedReadPhrases.getOrNull(state.value.speedReadIndex) else null

    /** How far through the chapter the mode has reached, so the page beneath keeps up. */
    fun speedReadFraction(): Float {
        val size = speedReadPhrases.size
        return if (size <= 0) 0f else (state.value.speedReadIndex.toFloat() / size).coerceIn(0f, 1f)
    }

    // endregion

    /** Steps to the next orientation, wrapping, for whatever is bound to it. */
    fun cycleOrientation() = novelReaderPreferences.orientation.getAndSet {
        val orientations = NovelReaderPreferences.ORIENTATIONS
        orientations[(orientations.indexOf(it) + 1) % orientations.size]
    }

    /**
     * A null query means the search bar is closed.
     *
     * The activity reads this before deciding whether to claim a key: a reader typing into the
     * search field must not have their letters turned into page turns.
     */
    fun setSearchQuery(query: String?) = mutableState.update { it.copy(searchQuery = query) }

    /**
     * Actions raised outside the composition — from the key handler — for the screen to perform.
     *
     * The dispatcher lives in the screen because most of what it does is Compose state. Keys are
     * dispatched by the activity, which cannot reach that, so they arrive here instead.
     */
    private val actionRequests = MutableSharedFlow<NovelReaderAction>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val actions: SharedFlow<NovelReaderAction> = actionRequests.asSharedFlow()

    fun requestAction(action: NovelReaderAction) {
        actionRequests.tryEmit(action)
    }

    /** Flips between the reader's chosen day and night themes. */
    fun toggleDayNightMode() {
        val day = novelReaderPreferences.dayTheme.get()
        val night = novelReaderPreferences.nightTheme.get()
        novelReaderPreferences.theme.getAndSet { if (it == night) day else night }
    }

    /** Steps to the next theme, wrapping, for whatever is bound to it. */
    fun cycleTheme() = novelReaderPreferences.theme.getAndSet {
        val themes = NovelReaderTheme.entries
        themes[(themes.indexOf(it) + 1) % themes.size]
    }

    fun setBrightnessOverlayValue(value: Int) = mutableState.update { it.copy(brightnessOverlayValue = value) }

    /** Records how far through [chapterId] the reader has scrolled, as a percent in 0..100. */
    fun reportProgress(chapterId: Long, percent: Int) {
        recordProgress(chapterId, percent, fromSpeech = false)
    }

    fun reportVisiblePosition(position: NovelSpeech.Anchor) {
        mutableState.update {
            if (it.speaking) it else it.copy(readingPosition = position)
        }
    }

    private fun recordProgress(chapterId: Long, percent: Int, fromSpeech: Boolean) {
        if (state.value.speaking && !fromSpeech) return
        mutableState.update { it.withProgress(chapterId, percent, fromSpeech) }
        if (percent > DOWNLOAD_AHEAD_PERCENT) downloadNextChapters(chapterId)
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
            deleteChapterIfNeeded(chapterId)
        }
    }

    // region Downloads

    /** Chapters the next ones have already been queued from, so every scroll does not queue them. */
    private val downloadedAheadFrom = mutableSetOf<Long>()

    /**
     * Queues the chapters after [chapterId] once a quarter of it is read. Mirrors
     * `ReaderViewModel.downloadNextChapters`, down to only reading ahead from a downloaded chapter
     * followed by a downloaded one: a reader streaming from the web did not ask for downloads.
     */
    private fun downloadNextChapters(chapterId: Long) {
        val amount = downloadPreferences.autoDownloadWhileReading.get()
        if (amount == 0 || !downloadedAheadFrom.add(chapterId)) return
        val manga = state.value.manga ?: return
        val chapters = state.value.chapters
        val index = chapters.indexOfFirst { it.id == chapterId }
        val current = chapters.getOrNull(index) ?: return
        val next = chapters.getOrNull(index + 1) ?: return

        viewModelScope.launchIO {
            if (!current.isDownloaded(manga) || !next.isDownloaded(manga)) return@launchIO
            val toDownload = chapters.drop(index + 1).filterNot { it.read }.take(amount)
            downloadManager.downloadChapters(manga, toDownload)
        }
    }

    private fun Chapter.isDownloaded(manga: Manga): Boolean =
        downloadManager.isChapterDownloaded(name, scanlator, url, manga.title, manga.source)

    /**
     * Queues the chapter the "delete after reading" setting names, to go when the reader closes.
     * Mirrors `ReaderViewModel.deleteChapterIfNeeded`, which counts back from the chapter just read.
     */
    private suspend fun deleteChapterIfNeeded(chapterId: Long) {
        val slots = downloadPreferences.removeAfterReadSlots.get()
        if (slots == -1) return
        val manga = state.value.manga ?: return
        val chapters = state.value.chapters
        val toDelete = chapters.getOrNull(chapters.indexOfFirst { it.id == chapterId } - slots) ?: return
        if (toDelete.id !in chaptersMarkedRead) return
        downloadManager.enqueueChaptersToDelete(listOf(toDelete), manga)
    }

    /** Deletes what [deleteChapterIfNeeded] queued. Called as the reader is left, as Mihon's is. */
    fun onActivityFinish() {
        viewModelScope.launchNonCancellable { downloadManager.deletePendingChapters() }
    }

    // endregion

    private suspend fun trackChapterRead(chapter: Chapter) {
        if (!trackPreferences.autoUpdateTrack.get()) return
        if (!chapter.isRecognizedNumber) return
        runCatching { trackChapter.await(context, mangaId, chapter.chapterNumber) }
            .onFailure { logcat(LogPriority.WARN, it) { "Could not push tracking progress" } }
    }

    fun restartReadTimer() {
        chapterReadStartTime = Clock.System.now().toEpochMilliseconds()
    }

    /**
     * The reading-session entry for the open chapter, as of now. Mirrors
     * `ReaderViewModel.updateHistory`, split from the write so a chapter change can take it before
     * the chapter and the clock move on.
     */
    private fun historyUpdate(): HistoryUpdate? {
        if (incognitoMode) return null
        val chapter = state.value.currentChapter ?: return null
        val endTime = Date()
        val duration = chapterReadStartTime?.let { endTime.time - it } ?: 0
        chapterReadStartTime = null
        return HistoryUpdate(chapter.id, endTime, duration)
    }

    /**
     * Saves progress and history without being cancelled by the activity going away.
     *
     * Speech used to stop here too, back when it belonged to this ViewModel and a paused reader
     * was the only thing standing between it and running forever unsupervised. It now belongs to
     * [NovelSpeechSession], which the notification's own stop action reaches directly, so `onPause`
     * no longer has to be the backstop — and it should not be: `onPause` fires on a screen lock or
     * a switch to another app, neither of which means "stop reading to me" (D001).
     */
    fun saveOnPause() {
        val history = historyUpdate()
        viewModelScope.launchNonCancellable {
            flushProgress()
            history?.let { upsertHistory.await(it) }
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
        val autoScrolling: Boolean = false,
        val searchQuery: String? = null,
        val chapterWords: Int = 0,
        val speaking: Boolean = false,
        val speechPaused: Boolean = false,
        val speechIndex: Int = 0,
        val speechCount: Int = 0,
        val speechPosition: NovelSpeech.Position? = null,
        val speedReading: Boolean = false,
        val speedReadIndex: Int = 0,
        /** Set once the engine has bound and reported that the phone has no voice at all. */
        val speechUnavailable: Boolean = false,
        val readingPosition: NovelSpeech.Anchor? = null,
    ) {
        val currentChapter: Chapter? get() = chapters.getOrNull(currentIndex)

        /** Speech owns progress while active; its WebView highlight is only a visual follower. */
        fun withProgress(chapterId: Long, percent: Int, fromSpeech: Boolean): State {
            if (speaking && !fromSpeech) return this
            val index = chapters.indexOfFirst { it.id == chapterId }
            if (index < 0) return this
            val position = percent.coerceIn(0, 100).toLong()
            if (chapters[index].lastPageRead == position) return this
            return copy(
                chapters = chapters.toMutableList().apply { this[index] = this[index].copy(lastPageRead = position) },
                speechPosition = speechPosition.takeIf { fromSpeech },
            )
        }
    }

    companion object {
        /** Trailing whitespace and short final paragraphs mean a reader rarely hits a literal 100. */
        const val COMPLETION_THRESHOLD = 95

        /** What a chapter speech has finished is recorded as. */
        private const val FINISHED_PERCENT = 100

        /** How far into a chapter the ones after it are downloaded, as `ReaderViewModel` has it. */
        private const val DOWNLOAD_AHEAD_PERCENT = 25

        /**
         * How many pieces from the end of the queue the next chapter is fetched and added.
         *
         * Far enough that the fetch has time to finish, and that the engine is never told about
         * more to say after it has already decided it reached the last piece.
         */
        private const val SPEECH_STAGE_LOOKAHEAD = 3

        /** No [NovelSpeechSession.generation] is ever this — real generations start at 1. */
        private const val NO_GENERATION = -1

        /** The visible chapter plus this many successors are kept ready. */
        const val PRELOAD_CHAPTER_COUNT = 3

        /** How long the reader must sit still before its position is written. */
        private const val PROGRESS_DEBOUNCE_MS = 400L

        const val EXTRA_MANGA = "manga"
        const val EXTRA_CHAPTER = "chapter"

        private const val SAVED_CHAPTER_ID = "chapter_id"
        private const val INVALID_ID = -1L
    }
}
