package leaf.novel.presentation.reader.appbars

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.reader.appbars.ReaderTopBar
import eu.kanade.presentation.reader.components.ChapterNavigator
import eu.kanade.presentation.reader.components.ChapterNavigatorType
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

private val readerBarsSlideAnimationSpec = tween<IntOffset>(200)
private val readerBarsFadeAnimationSpec = tween<Float>(150)

/**
 * The novel reader's chrome, assembled from the image reader's own bars.
 *
 * It cannot call `ReaderAppBars` itself: that composite requires a reading mode, an orientation and a
 * crop-borders toggle, and always renders [eu.kanade.presentation.reader.appbars.ReaderBottomBar],
 * none of which mean anything to text. Rather than widen an upstream signature (rule 1) this mirrors
 * its structure and reuses the two parts that are genuinely shared — [ReaderTopBar] and
 * [ChapterNavigator] — so both readers animate, colour and lay out identically.
 */
@Composable
fun NovelReaderAppBars(
    visible: Boolean,

    novelTitle: String?,
    chapterTitle: String?,
    navigateUp: () -> Unit,
    bookmarked: Boolean,
    onToggleBookmarked: () -> Unit,

    onPreviousChapter: () -> Unit,
    enabledPrevious: Boolean,
    onNextChapter: () -> Unit,
    enabledNext: Boolean,
    percentRead: Int,
    onPercentChange: (Int) -> Unit,

    onClickSettings: () -> Unit,
) {
    val backgroundColor = MaterialTheme.colorScheme
        .surfaceColorAtElevation(3.dp)
        .copy(alpha = if (isSystemInDarkTheme()) 0.9f else 0.95f)

    Column(modifier = Modifier.fillMaxHeight()) {
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(readerBarsSlideAnimationSpec) { -it } + fadeIn(readerBarsFadeAnimationSpec),
            exit = slideOutVertically(readerBarsSlideAnimationSpec) { -it } + fadeOut(readerBarsFadeAnimationSpec),
        ) {
            ReaderTopBar(
                modifier = Modifier.background(backgroundColor),
                mangaTitle = novelTitle,
                chapterTitle = chapterTitle,
                navigateUp = navigateUp,
                bookmarked = bookmarked,
                onToggleBookmarked = onToggleBookmarked,
                // A novel has no page to open, share or view on the web; the entry screen owns those.
                onOpenInWebView = null,
                onOpenInBrowser = null,
                onShare = null,
            )
        }

        Spacer(Modifier.weight(1f))

        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(readerBarsSlideAnimationSpec) { it } + fadeIn(readerBarsFadeAnimationSpec),
            exit = slideOutVertically(readerBarsSlideAnimationSpec) { it } + fadeOut(readerBarsFadeAnimationSpec),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
                ChapterNavigator(
                    type = ChapterNavigatorType.HORIZONTAL_LTR,
                    onNextChapter = onNextChapter,
                    enabledNext = enabledNext,
                    onPreviousChapter = onPreviousChapter,
                    enabledPrevious = enabledPrevious,
                    currentPage = percentRead.coerceIn(MIN_PERCENT, MAX_PERCENT),
                    totalPages = MAX_PERCENT,
                    onPageIndexChange = { onPercentChange(it + 1) },
                    onPageIndexChangeFinished = {},
                )
                NovelReaderBottomBar(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(backgroundColor)
                        .padding(horizontal = MaterialTheme.padding.small)
                        .windowInsetsPadding(WindowInsets.navigationBars),
                    onClickSettings = onClickSettings,
                )
            }
        }
    }
}

@Composable
private fun NovelReaderBottomBar(
    onClickSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        // Swallows taps so the bar itself never toggles the menu, as ReaderBottomBar does.
        modifier = modifier.pointerInput(Unit) {},
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClickSettings) {
            Icon(
                imageVector = Icons.Outlined.Settings,
                contentDescription = stringResource(MR.strings.action_settings),
            )
        }
    }
}

/**
 * The slider spans the chapter, not the book: where the image reader feeds [ChapterNavigator] a page
 * number, this feeds it a scroll percent. It is 1-based like a page count, so the top of a chapter
 * reads as 1 rather than 0 — a difference of less than one line of text.
 */
private const val MIN_PERCENT = 1
private const val MAX_PERCENT = 100
