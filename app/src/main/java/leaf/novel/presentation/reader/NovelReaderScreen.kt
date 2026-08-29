package leaf.novel.presentation.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import eu.kanade.presentation.reader.ReaderContentOverlay
import eu.kanade.presentation.reader.ReaderPageIndicator
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.system.readerBackgroundColor
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import leaf.novel.api.NovelChapterContent
import leaf.novel.presentation.reader.appbars.NovelReaderAppBars
import leaf.novel.presentation.reader.components.NovelChapterWebView
import leaf.novel.presentation.reader.settings.NovelReaderSettingsDialog
import leaf.novel.presentation.reader.settings.NovelReaderSettingsTab
import leaf.novel.ui.reader.NovelReaderCss
import leaf.novel.ui.reader.NovelReaderError
import leaf.novel.ui.reader.NovelReaderViewModel
import leaf.novel.ui.reader.setting.NovelReaderPreferences
import leaf.novel.ui.reader.setting.NovelReaderStyle
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

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
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    val readerTheme by viewModel.readerPreferences.readerTheme.collectAsState()
    val showPageNumber by viewModel.readerPreferences.showPageNumber.collectAsState()
    val style = rememberNovelReaderStyle(viewModel.novelReaderPreferences)
    val backgroundColor = remember(readerTheme) { context.readerBackgroundColor(readerTheme) }

    var settingsTab by remember { mutableStateOf<NovelReaderSettingsTab?>(null) }

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
                        onTap = viewModel::toggleMenu,
                        onExternalLink = { uriHandler.openUri(it) },
                    )
                }
            }
        }

        // Where in the *book* the reader is. The slider below says where in the chapter, so the two
        // never say the same thing twice.
        if (!state.menuVisible && showPageNumber) {
            ReaderPageIndicator(
                currentPage = state.currentIndex + 1,
                totalPages = state.chapters.size,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding(),
            )
        }

        ContentOverlay(state = state, readerPreferences = viewModel.readerPreferences)

        NovelReaderAppBars(
            visible = state.menuVisible,
            novelTitle = state.manga?.title,
            chapterTitle = chapter?.name,
            navigateUp = onBack,
            bookmarked = chapter?.bookmark == true,
            onToggleBookmarked = viewModel::toggleBookmark,
            onPreviousChapter = { viewModel.setCurrentChapter(state.currentIndex - 1) },
            enabledPrevious = state.currentIndex > 0,
            onNextChapter = { viewModel.setCurrentChapter(state.currentIndex + 1) },
            enabledNext = state.currentIndex < state.chapters.lastIndex,
            percentRead = livePercent,
            onPercentChange = { seekRequests.tryEmit(it) },
            onClickSettings = { settingsTab = it },
            onToggleDayNight = viewModel::toggleDayNightMode,
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
    onTap: () -> Unit,
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
                onTap = onTap,
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
    readerPreferences: ReaderPreferences,
) {
    val colorOverlayEnabled by readerPreferences.colorFilter.collectAsState()
    val colorOverlay by readerPreferences.colorFilterValue.collectAsState()
    val colorOverlayMode by readerPreferences.colorFilterMode.collectAsState()
    val colorOverlayBlendMode: BlendMode? = remember(colorOverlayMode) {
        ReaderPreferences.ColorFilterMode.getOrNull(colorOverlayMode)?.second
    }

    ReaderContentOverlay(
        brightness = state.brightnessOverlayValue,
        color = colorOverlay.takeIf { colorOverlayEnabled },
        colorBlendMode = colorOverlayBlendMode,
    )
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
 * The typography stages add a preference here and a field to [NovelReaderStyle]; nothing else in
 * the screen has to change, and the document keeps keying on a single value.
 */
@Composable
private fun rememberNovelReaderStyle(preferences: NovelReaderPreferences): NovelReaderStyle {
    val fontSize by preferences.fontSize.collectAsState()
    return remember(fontSize) { NovelReaderStyle(fontSizePx = fontSize) }
}
