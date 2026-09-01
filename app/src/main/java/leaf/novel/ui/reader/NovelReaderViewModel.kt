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
import eu.kanade.domain.track.interactor.TrackChapter
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
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
import leaf.novel.ui.reader.loader.EpubContentProvider
import leaf.novel.ui.reader.loader.NovelContentProvider
import leaf.novel.ui.reader.loader.NovelEpubAssetServer
import leaf.novel.ui.reader.loader.SourceContentProvider
import leaf.novel.ui.reader.setting.NovelReaderAction
import leaf.novel.ui.reader.setting.NovelReaderPreferences
import leaf.novel.ui.reader.setting.NovelReaderTheme
import logcat.LogPriority
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getAndSet
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
    private val sourceManager: SourceManager,
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

    private var provider: NovelContentProvider? = null

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
        speaker?.shutdown()
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
            it.copy(manga = manga, chapters = chapters, currentIndex = startIndex, isLoading = false)
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

    /** Loads one chapter's body. The reader never touches [leaf.novel.data.epub.NovelEpubReader] itself. */
    suspend fun chapterContent(chapter: Chapter): Result<NovelChapterContent> {
        val result = loadChapter(chapter)

        // Counted once per chapter for the remaining-time estimate, and off the main thread: a
        // long chapter is a lot of markup to walk. The markup itself is kept so speech can be cut
        // from it without fetching the chapter a second time.
        result.getOrNull()?.let { content ->
            currentHtml = content.html
            val words = withIOContext { NovelReadingTime.wordsIn(content.html) }
            mutableState.update { it.copy(chapterWords = words) }
        }
        return result
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

    /**
     * Opens another chapter.
     *
     * [keepSpeaking] is set only by speech itself, which has already read into the chapter being
     * opened — there the reader is catching up with the voice, so stopping it would cut off the
     * sentence that caused the move.
     */
    fun setCurrentChapter(index: Int, keepSpeaking: Boolean = false) {
        val chapter = state.value.chapters.getOrNull(index) ?: return
        if (state.value.currentIndex == index) return

        viewModelScope.launchNonCancellable { flushProgress() }
        restoredChapterId = chapter.id
        restartReadTimer()
        // Auto scroll does not carry across a chapter boundary.
        // Neither auto scroll nor a search carries across a chapter boundary.
        if (!keepSpeaking) stopSpeaking()
        stopSpeedReading()
        mutableState.update { it.copy(currentIndex = index, autoScrolling = false, searchQuery = null) }
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

    /**
     * Built on first use, not with the reader.
     *
     * [NovelSpeaker] binds a system service, and a reader who never asks for speech should not be
     * holding one open for the whole session — which is also why [onCleared] shuts down only what
     * was actually created.
     */
    private var speaker: NovelSpeaker? = null

    /** The chapter's own markup, kept so speech can be cut from it without re-fetching. */
    private var currentHtml: String? = null

    /** The open chapter's pieces, for exposing the active text and its repeated occurrence. */
    private var speechUtterances: List<String> = emptyList()

    /**
     * Where the open chapter's first piece sits in the engine's own numbering.
     *
     * The engine counts continuously across chapters, because reading into the next one appends to
     * the queue rather than starting a new one. The reader still counts from the chapter it shows.
     */
    private var speechChapterStart = 0

    /** The chapter already queued behind the open one, once speech has read that far ahead. */
    private var speechNextChapter: StagedChapter? = null

    private var speechStageJob: Job? = null

    private var speechStopJob: Job? = null

    /**
     * Starts reading the open chapter aloud at [percentRead].
     *
     * The utterances are cut afresh each time, so changing how the chapter is divided takes effect
     * on the next start rather than needing the chapter reopened.
     */
    fun startSpeaking(percentRead: Int) {
        if (state.value.speaking) return
        queueSpeech(percentRead)
    }

    /** Rebuilds the queue when its division changes while the control sheet is open. */
    fun restartSpeaking(percentRead: Int, paused: Boolean) {
        queueSpeech(percentRead)
        if (paused) speaker?.pause()
    }

    /**
     * One chapter's markup as the pieces speech says.
     *
     * The replacement rules are also the TTS character filters: visible and spoken prose use the
     * same existing mechanism rather than maintaining two almost-identical rule lists.
     */
    private fun utterancesOf(html: String): List<String> {
        val spokenHtml = NovelTextReplacements.apply(
            html,
            NovelTextReplacements.combine(
                novelReaderPreferences.textReplacements.get(),
                novelTextReplacements(),
            ),
        )
        return NovelSpeech.utterances(spokenHtml, novelReaderPreferences.speechDivision.get())
    }

    private fun queueSpeech(percentRead: Int) {
        val html = currentHtml ?: return
        val utterances = utterancesOf(html)
        if (utterances.isEmpty()) return

        stopSpeedReading()
        // A fresh queue: whatever had been read ahead belongs to a run that no longer exists.
        speechStageJob?.cancel()
        speechNextChapter = null
        speechChapterStart = 0
        val engine = speaker ?: NovelSpeaker(context).also { created ->
            speaker = created
            // Mirrored into the reader's own state so the screen has one thing to collect, and so
            // speech sits beside auto scroll rather than in a stream of its own.
            var wasSpeaking = false
            created.speech
                .onEach { speech ->
                    if (!wasSpeaking && speech.speaking) scheduleSpeechStop()
                    if (wasSpeaking && !speech.speaking) cancelSpeechStop()
                    wasSpeaking = speech.speaking
                    // Before the index is read: crossing a chapter moves where the count starts.
                    followSpeechAcrossChapters(speech.index, speech.speaking)
                    val index = speech.index - speechChapterStart
                    mutableState.update {
                        it.copy(
                            speaking = speech.speaking,
                            speechPaused = speech.paused,
                            speechIndex = index,
                            speechCount = speechUtterances.size,
                            speechText = speechUtterances.getOrNull(index),
                            speechOccurrence = NovelSpeech.occurrenceAt(index, speechUtterances),
                            speechUnavailable = speech.initialised && !speech.available,
                        )
                    }
                }
                .launchIn(viewModelScope)
        }

        speechUtterances = utterances
        mutableState.update { it.copy(autoScrolling = false, searchQuery = null) }
        engine.start(
            text = utterances,
            fromIndex = NovelSpeech.indexAt(percentRead / 100f, utterances),
            rate = novelReaderPreferences.speechRate.get(),
            pitch = novelReaderPreferences.speechPitch.get(),
            intervalMs = novelReaderPreferences.speechIntervalMs.get(),
            mixAudio = novelReaderPreferences.speechMixAudio.get(),
        )
    }

    /**
     * Carries speech over the end of a chapter.
     *
     * The next chapter is fetched and queued a few pieces before the current one runs out, so the
     * voice never stops at a chapter boundary — and the screen follows the voice rather than the
     * other way round, catching up only once the first piece of the new chapter is reached.
     */
    private fun followSpeechAcrossChapters(engineIndex: Int, speaking: Boolean) {
        if (!speaking) return

        val staged = speechNextChapter
        if (staged != null) {
            if (engineIndex < staged.startsAt) return
            speechChapterStart = staged.startsAt
            speechUtterances = staged.utterances
            currentHtml = staged.html
            speechNextChapter = null
            setCurrentChapter(staged.index, keepSpeaking = true)
            return
        }

        if (speechStageJob?.isActive == true) return
        val remaining = speechChapterStart + speechUtterances.size - engineIndex
        if (remaining > SPEECH_STAGE_LOOKAHEAD) return
        stageNextChapter()
    }

    /** Reads the chapter after the open one and adds it to what is already being said. */
    private fun stageNextChapter() {
        val index = state.value.currentIndex + 1
        val chapter = state.value.chapters.getOrNull(index) ?: return
        val startsAt = speechChapterStart + speechUtterances.size

        speechStageJob = viewModelScope.launch {
            val content = loadChapter(chapter).getOrNull() ?: return@launch
            val utterances = utterancesOf(content.html)
            // Checked again on the way out: the fetch is slow enough for speech to have been
            // stopped, restarted or seeked somewhere else entirely while it ran.
            if (utterances.isEmpty() || !state.value.speaking) return@launch
            if (startsAt != speechChapterStart + speechUtterances.size) return@launch

            speechNextChapter = StagedChapter(index, content.html, utterances, startsAt)
            speaker?.extend(utterances)
        }
    }

    fun toggleSpeechPlayback(percentRead: Int) {
        when {
            !state.value.speaking -> startSpeaking(percentRead)
            state.value.speechPaused -> speaker?.resume()
            else -> speaker?.pause()
        }
    }

    fun seekSpeech(units: Int) {
        speaker?.seekBy(units)
    }

    /** Applies changed controls without rebuilding or re-fetching the chapter. */
    fun applySpeechSettings() {
        speaker?.update(
            rate = novelReaderPreferences.speechRate.get(),
            pitch = novelReaderPreferences.speechPitch.get(),
            intervalMs = novelReaderPreferences.speechIntervalMs.get(),
            mixAudio = novelReaderPreferences.speechMixAudio.get(),
        )
    }

    fun stopSpeaking() {
        cancelSpeechStop()
        speechStageJob?.cancel()
        speechNextChapter = null
        speaker?.stop()
    }

    fun applySpeechTimer() {
        if (state.value.speaking) scheduleSpeechStop()
    }

    private fun scheduleSpeechStop() {
        speechStopJob?.cancel()
        speechStopJob = null
        val after = novelReaderPreferences.speechStopAfterMinutes.get()
        if (after <= 0) return
        speechStopJob = viewModelScope.launch {
            delay(after.minutes)
            stopSpeaking()
        }
    }

    private fun cancelSpeechStop() {
        speechStopJob?.cancel()
        speechStopJob = null
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

    /**
     * Flips between the reader's chosen day and night themes.
     *
     * While both are still [NovelReaderTheme.FOLLOW_MIHON] there is nothing of the fork's own to
     * flip, so it falls back to flipping the shared reader theme between its black and white values
     * — which is all this action ever did, and it keeps the image reader following along. Once a
     * reader picks a pair, writing the shared key as well would move manga's background for a
     * setting that no longer decides this one.
     */
    fun toggleDayNightMode() {
        val day = novelReaderPreferences.dayTheme.get()
        val night = novelReaderPreferences.nightTheme.get()
        if (day == NovelReaderTheme.FOLLOW_MIHON && night == NovelReaderTheme.FOLLOW_MIHON) {
            readerPreferences.readerTheme.getAndSet {
                if (it == READER_THEME_WHITE) READER_THEME_BLACK else READER_THEME_WHITE
            }
            return
        }
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

    /**
     * Saves progress and history without being cancelled by the activity going away.
     *
     * Speech stops here too: a chapter still being read aloud from a backgrounded reader is a
     * support ticket, and a reader who has left the app has left the book.
     */
    fun saveOnPause() {
        stopSpeaking()
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
        val autoScrolling: Boolean = false,
        val searchQuery: String? = null,
        val chapterWords: Int = 0,
        val speaking: Boolean = false,
        val speechPaused: Boolean = false,
        val speechIndex: Int = 0,
        val speechCount: Int = 0,
        val speechText: String? = null,
        val speechOccurrence: Int = 0,
        val speedReading: Boolean = false,
        val speedReadIndex: Int = 0,
        /** Set once the engine has bound and reported that the phone has no voice at all. */
        val speechUnavailable: Boolean = false,
    ) {
        val currentChapter: Chapter? get() = chapters.getOrNull(currentIndex)
    }

    /** A chapter fetched and queued behind the open one, waiting for speech to reach it. */
    private data class StagedChapter(
        val index: Int,
        val html: String,
        val utterances: List<String>,
        /** Where its first piece sits in the engine's numbering. */
        val startsAt: Int,
    )

    companion object {
        /** Trailing whitespace and short final paragraphs mean a reader rarely hits a literal 100. */
        const val COMPLETION_THRESHOLD = 95

        /**
         * How many pieces from the end of a chapter the next one is fetched and queued.
         *
         * Far enough that the fetch has time to finish, and that the engine is never told about
         * more to say after it has already decided it reached the last piece.
         */
        private const val SPEECH_STAGE_LOOKAHEAD = 3

        /** How long the reader must sit still before its position is written. */
        private const val PROGRESS_DEBOUNCE_MS = 400L

        // Upstream stores readerTheme as a bare int with no named constants of its own.
        private const val READER_THEME_WHITE = 0
        private const val READER_THEME_BLACK = 1

        const val EXTRA_MANGA = "manga"
        const val EXTRA_CHAPTER = "chapter"

        private const val SAVED_CHAPTER_ID = "chapter_id"
        private const val INVALID_ID = -1L
    }
}
