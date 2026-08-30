package leaf.novel.presentation.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.reader.ReaderContentOverlay
import eu.kanade.presentation.reader.ReaderPageIndicator
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.system.readerBackgroundColor
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import leaf.novel.api.NovelChapterContent
import leaf.novel.presentation.reader.appbars.NovelReaderAppBars
import leaf.novel.presentation.reader.components.NovelChapterWebView
import leaf.novel.presentation.reader.components.NovelWebViewController
import leaf.novel.presentation.reader.settings.NovelReaderSettingsDialog
import leaf.novel.presentation.reader.settings.NovelReaderSettingsTab
import leaf.novel.ui.reader.NovelReaderCss
import leaf.novel.ui.reader.NovelReaderError
import leaf.novel.ui.reader.NovelReaderViewModel
import leaf.novel.ui.reader.NovelReadingReminder
import leaf.novel.ui.reader.NovelReadingTime
import leaf.novel.ui.reader.setting.NovelReaderAction
import leaf.novel.ui.reader.setting.NovelReaderPreferences
import leaf.novel.ui.reader.setting.NovelReaderStyle
import leaf.novel.ui.reader.setting.NovelReaderSwipe
import leaf.novel.ui.reader.setting.NovelTapGrid
import tachiyomi.core.common.preference.getAndSet
import tachiyomi.core.common.preference.toggle
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import java.time.LocalTime
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.minutes

/**
 * The reader screen: one chapter at a time in a WebView, with menu-on-tap chrome over it.
 *
 * A single WebView rather than a pager over all chapters. It bounds memory absolutely and keeps
 * the WebView's vertical scrolling from fighting a horizontal pager for the same drag.
 */
@Composable
fun NovelReaderScreen(
    viewModel: NovelReaderViewModel,
    onBack: () -> Unit,
    onOpenEntry: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    val readerTheme by viewModel.readerPreferences.readerTheme.collectAsState()
    val showPageNumber by viewModel.readerPreferences.showPageNumber.collectAsState()
    val style = novelReaderStyle(viewModel.novelReaderPreferences)
    val backgroundColor = remember(readerTheme) { context.readerBackgroundColor(readerTheme) }

    var settingsTab by remember { mutableStateOf<NovelReaderSettingsTab?>(null) }

    var additionalOptionsExpanded by remember { mutableStateOf(false) }
    var showChapters by remember { mutableStateOf(false) }
    val webViewController = remember { NovelWebViewController() }

    // The one place an action becomes an effect. Taps bind to it here; keys and swipes follow.
    fun performAction(action: NovelReaderAction) {
        when (action) {
            NovelReaderAction.NONE -> Unit
            NovelReaderAction.OPTIONS_MENU -> viewModel.toggleMenu()
            NovelReaderAction.PAGE_UP -> webViewController.pageUp()
            NovelReaderAction.PAGE_DOWN -> webViewController.pageDown()
            NovelReaderAction.AUTO_SCROLL -> viewModel.setAutoScrolling(!state.autoScrolling)
            NovelReaderAction.READING_RULER -> viewModel.novelReaderPreferences.readingRuler.toggle()
            NovelReaderAction.SHOW_CHAPTERS -> showChapters = true
            NovelReaderAction.BOOK_INFORMATION -> onOpenEntry()
            NovelReaderAction.SEARCH -> {
                viewModel.showMenu()
                viewModel.setSearchQuery("")
            }
            NovelReaderAction.DAY_NIGHT_MODE -> viewModel.toggleDayNightMode()
            NovelReaderAction.SCREEN_ORIENTATION -> viewModel.cycleOrientation()
            // The WebView starts selection on long press itself, so choosing it here means
            // leaving that gesture alone rather than doing something of our own with it.
            NovelReaderAction.TEXT_SELECTION -> Unit
            // The brightness slider lives on the visual page, so the action opens that page.
            NovelReaderAction.BRIGHTNESS, NovelReaderAction.VISUAL_OPTIONS ->
                settingsTab = NovelReaderSettingsTab.VISUAL
            NovelReaderAction.CONTROL_OPTIONS -> settingsTab = NovelReaderSettingsTab.CONTROL
            NovelReaderAction.MISCELLANEOUS -> settingsTab = NovelReaderSettingsTab.MISCELLANEOUS
            NovelReaderAction.ADDITIONAL_OPTIONS -> {
                // The menu hangs off the bottom bar, so the bar has to be up for it to anchor to.
                viewModel.showMenu()
                additionalOptionsExpanded = true
            }
            NovelReaderAction.CLOSE -> onBack()
        }
    }

    // Bindings are read when the gesture happens rather than collected. Nothing on screen displays
    // one, so subscribing to all fourteen would be a dozen subscriptions with nothing to show.

    /** Runs a bound action, reporting whether it claimed the gesture. */
    fun performBinding(action: NovelReaderAction): Boolean {
        if (action == NovelReaderAction.NONE) return false
        performAction(action)
        return true
    }

    fun performLongPress(): Boolean {
        val action = viewModel.novelReaderPreferences.longTap.get()
        // Choosing text selection means leaving the WebView's own long press alone.
        if (action == NovelReaderAction.TEXT_SELECTION) return false
        return performBinding(action)
    }

    fun performEdgeDrag(edge: NovelTapGrid.Edge, steps: Int): Boolean {
        val preferences = viewModel.novelReaderPreferences
        val enabled = when (edge) {
            NovelTapGrid.Edge.LEFT -> preferences.edgeSwipeBrightness.get()
            NovelTapGrid.Edge.RIGHT -> preferences.edgeSwipeFontSize.get()
        }
        if (enabled && steps != 0) {
            when (edge) {
                NovelTapGrid.Edge.LEFT -> adjustBrightness(viewModel.readerPreferences, steps)
                NovelTapGrid.Edge.RIGHT -> adjustFontSize(preferences, steps)
            }
        }
        return enabled
    }

    fun performPinch(scale: Float) {
        viewModel.novelReaderPreferences.fontSize.getAndSet {
            (it * scale).roundToInt().coerceIn(
                NovelReaderPreferences.MIN_FONT_SIZE,
                NovelReaderPreferences.MAX_FONT_SIZE,
            )
        }
    }

    // Keys are dispatched by the activity, which cannot reach the composition, so they arrive as
    // requests and are performed here alongside the taps.
    LaunchedEffect(Unit) {
        viewModel.actions.collect { performAction(it) }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val reminderMinutes by viewModel.novelReaderPreferences.reminderMinutes.collectAsState()
    val reminderAt by viewModel.novelReaderPreferences.reminderAt.collectAsState()
    val reminderMessage = stringResource(MR.strings.leaf_novel_reader_reminder_message)

    // Nudges rather than alarms: both only run while the reader is open, so neither is scheduled
    // with the system.
    LaunchedEffect(reminderMinutes) {
        if (reminderMinutes <= 0) return@LaunchedEffect
        while (true) {
            delay(reminderMinutes.minutes)
            snackbarHostState.showSnackbar(reminderMessage)
        }
    }

    LaunchedEffect(reminderAt) {
        val target = NovelReadingReminder.minutesOfDay(reminderAt) ?: return@LaunchedEffect
        while (true) {
            val now = LocalTime.now()
            delay(NovelReadingReminder.millisUntilNext(now.hour * 60 + now.minute, target))
            snackbarHostState.showSnackbar(reminderMessage)
        }
    }

    // Both of these hang off the chrome, so when it goes they go with it. An expanded menu left
    // set would pop open on its own the next time the bar came back, and a search left open would
    // go on swallowing the key bindings with nothing on screen to explain why.
    LaunchedEffect(state.menuVisible) {
        if (!state.menuVisible) {
            additionalOptionsExpanded = false
            viewModel.setSearchQuery(null)
        }
    }

    // Back closes the search rather than the book, which is what the gesture means everywhere else.
    BackHandler(enabled = state.searchQuery != null) {
        viewModel.setSearchQuery(null)
    }

    // Live find-in-page: every keystroke re-searches, and closing the bar drops the highlighting.
    // A stale highlight outliving its search is the characteristic bug here.
    LaunchedEffect(state.searchQuery) {
        val query = state.searchQuery
        if (query.isNullOrBlank()) webViewController.clearFind() else webViewController.find(query)
    }

    val autoScrollSpeed by viewModel.novelReaderPreferences.autoScrollSpeed.collectAsState()
    val readingRuler by viewModel.novelReaderPreferences.readingRuler.collectAsState()
    val showRemainingTime by viewModel.novelReaderPreferences.showRemainingTime.collectAsState()
    val disableTouchEdge by viewModel.novelReaderPreferences.disableTouchEdge.collectAsState()
    val pinchFontSize by viewModel.novelReaderPreferences.pinchFontSize.collectAsState()

    // A short tick with a fractional step, carried between ticks, so even the slowest speed creeps
    // instead of jumping a whole pixel at a time. Scrolling through the view keeps progress
    // recording, because it reports position from the same callback a finger drives.
    LaunchedEffect(state.autoScrolling, autoScrollSpeed) {
        if (!state.autoScrolling) return@LaunchedEffect

        val perTick = autoScrollSpeed * AUTO_SCROLL_PX_PER_STEP * AUTO_SCROLL_TICK_MS / 1000f
        var carry = 0f
        while (true) {
            delay(AUTO_SCROLL_TICK_MS)
            if (!webViewController.canScrollDown) {
                viewModel.setAutoScrolling(false)
                break
            }
            carry += perTick
            val step = carry.toInt()
            if (step > 0) {
                webViewController.scrollBy(step)
                carry -= step
            }
        }
    }

    val chapter = state.currentChapter

    // Where the reader currently is, seeded from the stored position and updated as it scrolls.
    // Changing font size or theme rebuilds the document, and the reload restores from *this* rather
    // than from the database, so adjusting type size does not throw the reader back to where it was
    // when the chapter opened. It sits this high up because the chapter slider both displays it and
    // drives it.
    var livePercent by remember(chapter?.id) {
        mutableIntStateOf(chapter?.lastPageRead?.toInt()?.coerceIn(0, 100) ?: 0)
    }
    val seekRequests = remember {
        MutableSharedFlow<Int>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(backgroundColor)),
    ) {
        when {
            state.isLoading -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
            state.error != null -> {
                NovelReaderErrorMessage(
                    error = state.error!!,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(MaterialTheme.padding.large),
                )
            }
            else -> {
                if (chapter != null) {
                    ChapterContent(
                        viewModel = viewModel,
                        chapter = chapter,
                        style = style,
                        backgroundColor = backgroundColor,
                        percentRead = livePercent,
                        onPercentChange = { livePercent = it },
                        seekRequests = seekRequests,
                        controller = webViewController,
                        ignoreEdgeTaps = disableTouchEdge,
                        pinchEnabled = pinchFontSize,
                        onPinch = ::performPinch,
                        onEdgeDrag = ::performEdgeDrag,
                        onTapCell = { cell ->
                            performAction(viewModel.novelReaderPreferences.tapZones[cell].get())
                        },
                        onSwipe = { swipe ->
                            performBinding(viewModel.novelReaderPreferences.swipes.getValue(swipe).get())
                        },
                        onLongPress = ::performLongPress,
                        onExternalLink = { uriHandler.openUri(it) },
                    )
                }
            }
        }

        // Where in the *book* the reader is. The slider below says where in the chapter, so the two
        // never say the same thing twice.
        if (!state.menuVisible && (showPageNumber || showRemainingTime)) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding(),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showPageNumber) {
                    ReaderPageIndicator(
                        currentPage = state.currentIndex + 1,
                        totalPages = state.chapters.size,
                    )
                }

                if (showRemainingTime && state.chapterWords > 0) {
                    NovelReaderRemainingTime(
                        minutes = NovelReadingTime.minutesRemaining(state.chapterWords, livePercent),
                    )
                }
            }
        }

        ContentOverlay(
            state = state,
            novelReaderPreferences = viewModel.novelReaderPreferences,
            readerPreferences = viewModel.readerPreferences,
        )

        // Decoration only. It takes no pointer input, so scrolling, tapping and long-press
        // selection all carry on underneath the band.
        if (readingRuler) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .height(READING_RULER_HEIGHT)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = READING_RULER_ALPHA)),
            )
        }

        NovelReaderAppBars(
            visible = state.menuVisible,
            novelTitle = state.manga?.title,
            chapterTitle = chapter?.name,
            navigateUp = onBack,
            bookmarked = chapter?.bookmark == true,
            onToggleBookmarked = viewModel::toggleBookmark,
            searchQuery = state.searchQuery,
            onChangeSearchQuery = viewModel::setSearchQuery,
            findMatches = webViewController.findMatches,
            onFindNext = { webViewController.findNext(it) },
            onPreviousChapter = { viewModel.setCurrentChapter(state.currentIndex - 1) },
            enabledPrevious = state.currentIndex > 0,
            onNextChapter = { viewModel.setCurrentChapter(state.currentIndex + 1) },
            enabledNext = state.currentIndex < state.chapters.lastIndex,
            percentRead = livePercent,
            onPercentChange = { seekRequests.tryEmit(it) },
            onClickSettings = { settingsTab = it },
            onToggleDayNight = viewModel::toggleDayNightMode,
            autoScrolling = state.autoScrolling,
            readingRuler = readingRuler,
            onToggleReadingRuler = { viewModel.novelReaderPreferences.readingRuler.toggle() },
            onShowChapters = { showChapters = true },
            onOpenEntry = onOpenEntry,
            onStartSearch = {
                viewModel.showMenu()
                viewModel.setSearchQuery("")
            },
            onToggleAutoScroll = { viewModel.setAutoScrolling(!state.autoScrolling) },
            additionalOptionsExpanded = additionalOptionsExpanded,
            onAdditionalOptionsExpandedChange = { additionalOptionsExpanded = it },
        )

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding(),
        )
    }

    settingsTab?.let { tab ->
        NovelReaderSettingsDialog(
            initialTab = tab,
            novelReaderPreferences = viewModel.novelReaderPreferences,
            readerPreferences = viewModel.readerPreferences,
            onDismissRequest = { settingsTab = null },
        )
    }

    if (showChapters) {
        NovelChapterSheet(
            chapters = state.chapters,
            currentIndex = state.currentIndex,
            onSelectChapter = viewModel::setCurrentChapter,
            onDismissRequest = { showChapters = false },
        )
    }
}

@Composable
private fun ChapterContent(
    viewModel: NovelReaderViewModel,
    chapter: Chapter,
    style: NovelReaderStyle,
    backgroundColor: Int,
    percentRead: Int,
    onPercentChange: (Int) -> Unit,
    seekRequests: Flow<Int>,
    controller: NovelWebViewController,
    ignoreEdgeTaps: Boolean,
    pinchEnabled: Boolean,
    onPinch: (Float) -> Unit,
    onEdgeDrag: (NovelTapGrid.Edge, Int) -> Boolean,
    onTapCell: (Int) -> Unit,
    onLongPress: () -> Boolean,
    onSwipe: (NovelReaderSwipe) -> Boolean,
    onExternalLink: (String) -> Unit,
) {
    val assetServer = remember(viewModel) { viewModel.assetServer() }

    val content by produceState<Result<NovelChapterContent>?>(initialValue = null, chapter.id) {
        value = viewModel.chapterContent(chapter)
    }

    val result = content
    val chapterContent = result?.getOrNull()
    when {
        result == null -> {
            Box(modifier = Modifier.fillMaxSize()) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }
        chapterContent == null -> {
            Box(modifier = Modifier.fillMaxSize()) {
                NovelReaderErrorMessage(
                    error = NovelReaderError.CHAPTER_MISSING,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(MaterialTheme.padding.large),
                )
            }
        }
        else -> {
            val document = remember(chapterContent, style, backgroundColor) {
                NovelReaderCss.document(chapterContent, style, backgroundColor)
            }
            NovelChapterWebView(
                document = document,
                baseUrl = chapterContent.baseUrl.orEmpty(),
                initialPercent = percentRead,
                seekRequests = seekRequests,
                assetServer = assetServer,
                backgroundColor = backgroundColor,
                onProgress = {
                    onPercentChange(it)
                    viewModel.reportProgress(chapter.id, it)
                },
                controller = controller,
                ignoreEdgeTaps = ignoreEdgeTaps,
                pinchEnabled = pinchEnabled,
                onPinch = onPinch,
                onEdgeDrag = onEdgeDrag,
                onTapCell = onTapCell,
                onLongPress = onLongPress,
                onSwipe = onSwipe,
                onInternalLink = viewModel::openChapterByEntry,
                onExternalLink = onExternalLink,
                // The reader is edge-to-edge, so without this the first and last lines of a chapter
                // sit under the status and navigation bars. Insets are zero once fullscreen hides them.
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding(),
            )
        }
    }
}

@Composable
private fun ContentOverlay(
    state: NovelReaderViewModel.State,
    novelReaderPreferences: NovelReaderPreferences,
    readerPreferences: ReaderPreferences,
) {
    val bluelight by novelReaderPreferences.bluelight.collectAsState()
    val bluelightIntensity by novelReaderPreferences.bluelightIntensity.collectAsState()

    val colorOverlayEnabled by readerPreferences.colorFilter.collectAsState()
    val colorOverlay by readerPreferences.colorFilterValue.collectAsState()
    val colorOverlayMode by readerPreferences.colorFilterMode.collectAsState()
    val colorOverlayBlendMode: BlendMode? = remember(colorOverlayMode) {
        ReaderPreferences.ColorFilterMode.getOrNull(colorOverlayMode)?.second
    }

    // A new input to the overlay the reader already draws, not a second one stacked on it. The
    // warm filter takes the colour when it is on; brightness is a separate input and keeps
    // working underneath either, which is what stops the two settings fighting.
    ReaderContentOverlay(
        brightness = state.brightnessOverlayValue,
        color = when {
            bluelight -> bluelightColor(bluelightIntensity)
            colorOverlayEnabled -> colorOverlay
            else -> null
        },
        colorBlendMode = if (bluelight) null else colorOverlayBlendMode,
    )
}

/** A warm amber laid over the page. Only its strength changes with the intensity chosen. */
private fun bluelightColor(intensity: Int): Int {
    val alpha = intensity.coerceIn(0, 100) * BLUELIGHT_MAX_ALPHA / 100
    return (alpha shl 24) or BLUELIGHT_AMBER
}

@Composable
private fun NovelReaderErrorMessage(error: NovelReaderError, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(
            when (error) {
                NovelReaderError.MANGA_NOT_FOUND -> MR.strings.leaf_novel_reader_error_not_found
                NovelReaderError.SOURCE_MISSING -> MR.strings.leaf_novel_reader_error_source_missing
                NovelReaderError.BOOK_MISSING -> MR.strings.leaf_novel_reader_error_book_missing
                NovelReaderError.BOOK_UNREADABLE -> MR.strings.leaf_novel_reader_error_book_unreadable
                NovelReaderError.NO_CHAPTERS -> MR.strings.leaf_novel_reader_error_no_chapters
                NovelReaderError.CHAPTER_MISSING -> MR.strings.leaf_novel_reader_error_chapter_missing
            },
        ),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.error,
        modifier = modifier,
    )
}

/**
 * Collects the settings the stylesheet needs into one value.
 *
 * The typography stages add a preference here and a field to [NovelReaderStyle], which has no
 * defaults, so a field left unwired fails to compile. No remember: the style compares by content,
 * so the document below it re-keys only when a value actually changed.
 */
@Composable
private fun novelReaderStyle(preferences: NovelReaderPreferences): NovelReaderStyle {
    val fontSize by preferences.fontSize.collectAsState()
    val bold by preferences.bold.collectAsState()
    val italic by preferences.italic.collectAsState()
    val underline by preferences.underline.collectAsState()
    val shadow by preferences.shadow.collectAsState()
    val antialias by preferences.antialias.collectAsState()
    val justified by preferences.justified.collectAsState()
    val hyphenation by preferences.hyphenation.collectAsState()
    val paragraphSpacing by preferences.paragraphSpacing.collectAsState()
    val lineSpacing by preferences.lineSpacing.collectAsState()
    val fontSpacing by preferences.fontSpacing.collectAsState()
    val fontScale by preferences.fontScale.collectAsState()
    val marginLeft by preferences.marginLeft.collectAsState()
    val marginRight by preferences.marginRight.collectAsState()
    val marginTop by preferences.marginTop.collectAsState()
    val marginBottom by preferences.marginBottom.collectAsState()
    val highlightFirstWord by preferences.highlightFirstWord.collectAsState()
    val highlightInitialChars by preferences.highlightInitialChars.collectAsState()
    val indentFirstLine by preferences.indentFirstLine.collectAsState()
    val trimBlankLines by preferences.trimBlankLines.collectAsState()

    return NovelReaderStyle(
        fontSizePx = fontSize,
        bold = bold,
        italic = italic,
        underline = underline,
        shadow = shadow,
        antialias = antialias,
        justified = justified,
        hyphenation = hyphenation,
        paragraphSpacing = paragraphSpacing,
        lineSpacing = lineSpacing,
        fontSpacing = fontSpacing,
        fontScale = fontScale,
        marginLeft = marginLeft,
        marginRight = marginRight,
        marginTop = marginTop,
        marginBottom = marginBottom,
        highlightFirstWord = highlightFirstWord,
        highlightInitialChars = highlightInitialChars,
        indentFirstLine = indentFirstLine,
        trimBlankLines = trimBlankLines,
    )
}

/** One frame, so the creep is smooth rather than a series of visible jumps. */
private const val AUTO_SCROLL_TICK_MS = 16L

/** Six pixels a second per speed step, so the default of 5 is roughly a line a second. */
private const val AUTO_SCROLL_PX_PER_STEP = 6

/** Tall enough to sit under a line of text at any size the reader offers. */
private val READING_RULER_HEIGHT = 28.dp

/** Visible as a band without washing out the words it sits behind. */
private const val READING_RULER_ALPHA = 0.18f

/** The amber the warm filter lays over the page; the intensity supplies its alpha. */
private const val BLUELIGHT_AMBER = 0x00FF9632
private const val BLUELIGHT_MAX_ALPHA = 180

/**
 * Minutes left, in the page indicator own outlined style.
 *
 * The shared indicator takes two integers and cannot carry text, so this matches how it looks
 * rather than trying to call it.
 */
@Composable
private fun NovelReaderRemainingTime(minutes: Int, modifier: Modifier = Modifier) {
    val text = stringResource(MR.strings.leaf_novel_reader_minutes_left, minutes)
    val style = TextStyle(
        color = Color(235, 235, 235),
        fontSize = MaterialTheme.typography.bodySmall.fontSize,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
    )

    Box(contentAlignment = Alignment.Center, modifier = modifier) {
        Text(text = text, style = style.copy(color = Color(45, 45, 45), drawStyle = Stroke(width = 4f)))
        Text(text = text, style = style)
    }
}

/**
 * Nudges the brightness the image reader own key holds.
 *
 * Switching custom brightness on is part of it: the value does nothing while it is off, and a drag
 * that visibly does nothing is worse than one that turns a setting on to obey.
 */
private fun adjustBrightness(preferences: ReaderPreferences, steps: Int) {
    preferences.customBrightness.set(true)
    preferences.customBrightnessValue.getAndSet {
        (it + steps).coerceIn(NovelReaderPreferences.BRIGHTNESS_RANGE)
    }
}

private fun adjustFontSize(preferences: NovelReaderPreferences, steps: Int) {
    preferences.fontSize.getAndSet {
        (it + steps).coerceIn(
            NovelReaderPreferences.MIN_FONT_SIZE,
            NovelReaderPreferences.MAX_FONT_SIZE,
        )
    }
}
