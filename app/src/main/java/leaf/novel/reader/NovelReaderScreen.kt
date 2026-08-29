package leaf.novel.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.NavigateBefore
import androidx.compose.material.icons.automirrored.outlined.NavigateNext
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.reader.ReaderContentOverlay
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.system.readerBackgroundColor
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

/**
 * The reader screen: one chapter at a time in a WebView, with menu-on-tap chrome over it.
 *
 * A single WebView rather than a pager over all chapters — see progress/03. It bounds memory
 * absolutely (risk T4) and keeps the WebView's vertical scrolling from fighting a horizontal pager
 * for the same drag.
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
    val fontSize by viewModel.novelReaderPreferences.fontSize.collectAsState()
    val backgroundColor = remember(readerTheme) { context.readerBackgroundColor(readerTheme) }

    var showSettings by remember { mutableStateOf(false) }

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
                val chapter = state.currentChapter
                if (chapter != null) {
                    ChapterContent(
                        viewModel = viewModel,
                        chapter = chapter,
                        fontSize = fontSize,
                        backgroundColor = backgroundColor,
                        onTap = viewModel::toggleMenu,
                        onExternalLink = { uriHandler.openUri(it) },
                    )
                }
            }
        }

        ContentOverlay(state = state, readerPreferences = viewModel.readerPreferences)

        NovelReaderChrome(
            visible = state.menuVisible,
            title = state.manga?.title.orEmpty(),
            chapter = state.currentChapter,
            position = state.currentIndex + 1,
            total = state.chapters.size,
            hasPrevious = state.currentIndex > 0,
            hasNext = state.currentIndex < state.chapters.lastIndex,
            onBack = onBack,
            onPrevious = { viewModel.setCurrentChapter(state.currentIndex - 1) },
            onNext = { viewModel.setCurrentChapter(state.currentIndex + 1) },
            onToggleBookmark = viewModel::toggleBookmark,
            onSettings = { showSettings = true },
        )
    }

    if (showSettings) {
        NovelReaderSettingsDialog(
            fontSizePreference = viewModel.novelReaderPreferences.fontSize,
            readerPreferences = viewModel.readerPreferences,
            onDismissRequest = { showSettings = false },
        )
    }
}

@Composable
private fun ChapterContent(
    viewModel: NovelReaderViewModel,
    chapter: Chapter,
    fontSize: Int,
    backgroundColor: Int,
    onTap: () -> Unit,
    onExternalLink: (String) -> Unit,
) {
    val assetServer = remember(viewModel) { viewModel.assetServer() }

    val content by produceState<Result<NovelChapterContent>?>(initialValue = null, chapter.id) {
        value = viewModel.chapterContent(chapter)
    }

    // Where the reader currently is, seeded from the stored position and updated as it scrolls.
    // Changing font size or theme rebuilds the document, and the reload restores from *this* rather
    // than from the database, so adjusting type size does not throw the reader back to where it was
    // when the chapter opened.
    var livePercent by remember(chapter.id) {
        mutableIntStateOf(chapter.lastPageRead.toInt().coerceIn(0, 100))
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
            val document = remember(chapterContent, fontSize, backgroundColor) {
                NovelReaderCss.document(chapterContent, fontSize, backgroundColor)
            }
            NovelChapterWebView(
                document = document,
                baseUrl = chapterContent.baseUrl.orEmpty(),
                initialPercent = livePercent,
                assetServer = assetServer,
                backgroundColor = backgroundColor,
                onProgress = {
                    livePercent = it
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
private fun NovelReaderChrome(
    visible: Boolean,
    title: String,
    chapter: Chapter?,
    position: Int,
    total: Int,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onBack: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggleBookmark: () -> Unit,
    onSettings: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically { -it },
            exit = slideOutVertically { -it },
        ) {
            TopAppBar(
                title = {
                    Column {
                        Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (chapter != null) {
                            Text(
                                text = chapter.name,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(MR.strings.action_close),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onToggleBookmark) {
                        Icon(
                            imageVector = if (chapter?.bookmark == true) {
                                Icons.Outlined.Bookmark
                            } else {
                                Icons.Outlined.BookmarkBorder
                            },
                            contentDescription = stringResource(
                                if (chapter?.bookmark == true) {
                                    MR.strings.action_remove_bookmark
                                } else {
                                    MR.strings.action_bookmark
                                },
                            ),
                        )
                    }
                },
            )
        }

        Box(modifier = Modifier.weight(1f))

        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            Surface(tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = MaterialTheme.padding.small),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    IconButton(onClick = onPrevious, enabled = hasPrevious) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.NavigateBefore,
                            contentDescription = stringResource(MR.strings.leaf_novel_reader_previous_chapter),
                        )
                    }
                    Text(
                        text = stringResource(MR.strings.leaf_novel_reader_chapter_position, position, total),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Row {
                        IconButton(onClick = onSettings) {
                            Icon(
                                imageVector = Icons.Outlined.Settings,
                                contentDescription = stringResource(MR.strings.action_settings),
                            )
                        }
                        IconButton(onClick = onNext, enabled = hasNext) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.NavigateNext,
                                contentDescription = stringResource(MR.strings.leaf_novel_reader_next_chapter),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NovelReaderErrorMessage(error: NovelReaderError, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(
            when (error) {
                NovelReaderError.MANGA_NOT_FOUND -> MR.strings.leaf_novel_reader_error_not_found
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
