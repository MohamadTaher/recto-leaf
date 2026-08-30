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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.presentation.components.RadioMenuItem
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.presentation.reader.appbars.ReaderTopBar
import eu.kanade.presentation.reader.components.ChapterNavigator
import eu.kanade.presentation.reader.components.ChapterNavigatorType
import leaf.novel.presentation.reader.components.FindMatches
import leaf.novel.presentation.reader.settings.NovelReaderSettingsTab
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.Sort
import mihon.icons.materialsymbols.rounded.ExpandLess
import mihon.icons.materialsymbols.rounded.ExpandMore
import mihon.icons.materialsymbols.rounded.Palette
import mihon.icons.materialsymbols.rounded.ScreenRotation
import mihon.icons.materialsymbols.rounded.Settings
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
    searchQuery: String?,
    onChangeSearchQuery: (String?) -> Unit,
    findMatches: FindMatches,
    onFindNext: (Boolean) -> Unit,

    onPreviousChapter: () -> Unit,
    enabledPrevious: Boolean,
    onNextChapter: () -> Unit,
    enabledNext: Boolean,
    percentRead: Int,
    onPercentChange: (Int) -> Unit,

    onClickSettings: (NovelReaderSettingsTab) -> Unit,
    additionalOptionsExpanded: Boolean,
    onAdditionalOptionsExpandedChange: (Boolean) -> Unit,
    additionalOptions: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
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
            if (searchQuery == null) {
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
            } else {
                NovelReaderSearchBar(
                    modifier = Modifier.background(backgroundColor),
                    query = searchQuery,
                    onQueryChange = onChangeSearchQuery,
                    onClose = { onChangeSearchQuery(null) },
                    matches = findMatches,
                    onFindNext = onFindNext,
                )
            }
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
                    additionalOptionsExpanded = additionalOptionsExpanded,
                    onAdditionalOptionsExpandedChange = onAdditionalOptionsExpandedChange,
                    additionalOptions = additionalOptions,
                )
            }
        }
    }
}

/**
 * A button per group of settings, and a fourth for the things you can start.
 *
 * The icons are the closest the generated Material Symbols set carries. It has no touch or tune
 * glyph, so the controls group takes the screen-rotation icon — orientation is one of the settings
 * it holds — and the menu takes the three-line sort one.
 */
@Composable
private fun NovelReaderBottomBar(
    onClickSettings: (NovelReaderSettingsTab) -> Unit,
    additionalOptionsExpanded: Boolean,
    onAdditionalOptionsExpandedChange: (Boolean) -> Unit,
    additionalOptions: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        // Swallows taps so the bar itself never toggles the menu, as ReaderBottomBar does.
        modifier = modifier.pointerInput(Unit) {},
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { onClickSettings(NovelReaderSettingsTab.VISUAL) }) {
            Icon(
                imageVector = MaterialSymbols.Rounded.Palette,
                contentDescription = stringResource(MR.strings.leaf_novel_reader_tab_visual),
            )
        }

        IconButton(onClick = { onClickSettings(NovelReaderSettingsTab.CONTROL) }) {
            Icon(
                imageVector = MaterialSymbols.Rounded.ScreenRotation,
                contentDescription = stringResource(MR.strings.leaf_novel_reader_tab_control),
            )
        }

        IconButton(onClick = { onClickSettings(NovelReaderSettingsTab.MISCELLANEOUS) }) {
            Icon(
                imageVector = MaterialSymbols.Rounded.Settings,
                contentDescription = stringResource(MR.strings.leaf_novel_reader_tab_misc),
            )
        }

        AdditionalOptionsMenu(
            expanded = additionalOptionsExpanded,
            onExpandedChange = onAdditionalOptionsExpandedChange,
            content = additionalOptions,
        )
    }
}

/**
 * The menu of things you *start*, as opposed to the three tabs of things you *set*.
 *
 * Its items come from the screen rather than being threaded down here as a callback each. Every one
 * of them is an action a tap or a key can also be bound to, so letting the screen build them keeps
 * one path from an action to its effect instead of a second one running alongside the dispatcher.
 *
 * The same shape [eu.kanade.presentation.components.TabbedDialog] uses for its own overflow.
 */
@Composable
private fun AdditionalOptionsMenu(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    content: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
) {
    Box {
        IconButton(onClick = { onExpandedChange(true) }) {
            Icon(
                imageVector = MaterialSymbols.AutoMirroredRounded.Sort,
                contentDescription = stringResource(MR.strings.leaf_novel_reader_additional_options),
            )
        }

        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            content { onExpandedChange(false) }
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

/**
 * The top bar while a search is running, on Mihon's own search toolbar so the field, the close
 * button and the keyboard behave as they do everywhere else in the app.
 *
 * Its search and reset actions are turned off: this bar is only ever in search, and the room they
 * would take is where the match count and the step buttons go.
 */
@Composable
private fun NovelReaderSearchBar(
    query: String,
    onQueryChange: (String?) -> Unit,
    onClose: () -> Unit,
    matches: FindMatches,
    onFindNext: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    SearchToolbar(
        modifier = modifier,
        searchQuery = query,
        onChangeSearchQuery = onQueryChange,
        onClickCloseSearch = onClose,
        searchEnabled = false,
        actions = {
            if (matches.total > 0) {
                Text(
                    // The view counts matches from zero; a reader counts them from one.
                    text = "${matches.activeOrdinal + 1}/${matches.total}",
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            IconButton(onClick = { onFindNext(false) }, enabled = matches.total > 0) {
                Icon(
                    imageVector = MaterialSymbols.Rounded.ExpandLess,
                    contentDescription = stringResource(MR.strings.leaf_novel_reader_find_previous),
                )
            }

            IconButton(onClick = { onFindNext(true) }, enabled = matches.total > 0) {
                Icon(
                    imageVector = MaterialSymbols.Rounded.ExpandMore,
                    contentDescription = stringResource(MR.strings.leaf_novel_reader_find_next),
                )
            }
        },
    )
}
