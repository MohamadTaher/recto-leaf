package leaf.novel.presentation.reader

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import leaf.novel.ui.reader.NovelSearch
import leaf.novel.ui.reader.NovelSearchResult
import leaf.novel.ui.reader.NovelSearchState
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Close
import mihon.icons.materialsymbols.rounded.KeyboardArrowDown
import mihon.icons.materialsymbols.rounded.KeyboardArrowUp
import mihon.icons.materialsymbols.rounded.Schedule
import mihon.icons.materialsymbols.rounded.Search
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.secondaryItemAlpha

/**
 * The book search, as a sheet over the page like the settings and the comments.
 *
 * The search and its results live in [NovelSearch], not here, so closing the sheet to read a result
 * and opening it again comes back to the same list at the same result.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NovelSearchSheet(
    search: NovelSearch,
    currentIndex: Int,
    onSelect: (NovelSearchResult) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val state by search.state.collectAsState()
    val recent by search.recent.collectAsState()

    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        Column(modifier = Modifier.fillMaxHeight(0.9f).navigationBarsPadding().imePadding()) {
            SearchField(state.query, search::setQuery)
            SearchOptions(state, search)

            if (state.running) {
                LinearProgressIndicator(
                    progress = { if (state.toSearch > 0) state.searched / state.toSearch.toFloat() else 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Spacer(Modifier.height(4.dp))
            }
            Summary(state)

            val groups = remember(state.results) { state.results.groupBy { it.chapterIndex } }
            // Reopened, the list comes back on the result the reader went to, under its chapter.
            val listState = rememberLazyListState(
                initialFirstVisibleItemIndex = remember {
                    var row = 0
                    groups.forEach { (_, results) ->
                        val at = results.indexOfFirst { it.key == state.selected }
                        if (at >= 0) return@remember row + at
                        row += results.size + 1
                    }
                    0
                },
            )
            val request = listOf(state.query, state.wholeBook, state.matchCase, state.wholeWords)
            var shownRequest by remember { mutableStateOf(request) }
            LaunchedEffect(request) {
                if (request != shownRequest) {
                    shownRequest = request
                    listState.scrollToItem(0)
                }
            }
            val keyboard = LocalSoftwareKeyboardController.current
            LaunchedEffect(listState.isScrollInProgress) {
                if (listState.isScrollInProgress) keyboard?.hide()
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when {
                    state.query.isBlank() && recent.isNotEmpty() ->
                        RecentSearches(recent, onSearch = search::setQuery, onClear = search::clearHistory)
                    state.query.isBlank() -> Hint(stringResource(MR.strings.leaf_novel_search_empty))
                    state.results.isEmpty() && !state.running -> Hint(stringResource(MR.strings.leaf_novel_search_none))
                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxHeight(),
                        contentPadding = PaddingValues(bottom = MaterialTheme.padding.small),
                    ) {
                        groups.forEach { (chapterIndex, results) ->
                            stickyHeader(key = "chapter:$chapterIndex") {
                                ChapterHeader(results.first().chapterName, results.size, chapterIndex == currentIndex)
                            }
                            items(results, key = { it.key }) { result ->
                                ResultItem(result, result.key == state.selected) { onSelect(result) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    // Held here rather than read back from the search, so the cursor never trails a keystroke.
    var value by remember { mutableStateOf(TextFieldValue(query, TextRange(query.length))) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // Opened to search, the keyboard comes up; reopened on a list of results, the list is the point.
    LaunchedEffect(Unit) { if (query.isEmpty()) focus.requestFocus() }
    // A recent search tapped below fills the field in.
    LaunchedEffect(query) {
        if (query != value.text) value = TextFieldValue(query, TextRange(query.length))
    }

    OutlinedTextField(
        value = value,
        onValueChange = {
            value = it
            onQueryChange(it.text)
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.padding.medium)
            .padding(top = MaterialTheme.padding.medium)
            .focusRequester(focus),
        placeholder = { Text(stringResource(MR.strings.leaf_novel_search_hint)) },
        leadingIcon = { Icon(MaterialSymbols.Rounded.Search, contentDescription = null) },
        trailingIcon = if (value.text.isNotEmpty()) {
            {
                IconButton(
                    onClick = {
                        value = TextFieldValue()
                        onQueryChange("")
                        focus.requestFocus()
                    },
                ) {
                    Icon(MaterialSymbols.Rounded.Close, stringResource(MR.strings.action_reset))
                }
            }
        } else {
            null
        },
        singleLine = true,
        shape = MaterialTheme.shapes.extraLarge,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
    )
}

@Composable
private fun SearchOptions(state: NovelSearchState, search: NovelSearch) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = MaterialTheme.padding.medium),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
    ) {
        FilterChip(
            selected = !state.wholeBook,
            onClick = { search.setWholeBook(false) },
            label = { Text(stringResource(MR.strings.leaf_novel_search_chapter)) },
        )
        FilterChip(
            selected = state.wholeBook,
            onClick = { search.setWholeBook(true) },
            label = { Text(stringResource(MR.strings.leaf_novel_search_book)) },
        )
        FilterChip(
            selected = state.matchCase,
            onClick = { search.setMatchCase(!state.matchCase) },
            label = { Text(stringResource(MR.strings.leaf_novel_search_match_case)) },
        )
        FilterChip(
            selected = state.wholeWords,
            onClick = { search.setWholeWords(!state.wholeWords) },
            label = { Text(stringResource(MR.strings.leaf_novel_search_whole_words)) },
        )
    }
}

/** How far the search has got, what it found, and what it could not look at. */
@Composable
private fun Summary(state: NovelSearchState) {
    if (state.query.isBlank()) return
    val lines = listOfNotNull(
        when {
            state.running -> stringResource(MR.strings.leaf_novel_search_progress, state.searched, state.toSearch)
            state.total > state.results.size ->
                stringResource(MR.strings.leaf_novel_search_results_capped, state.results.size, state.total)
            state.total > 0 -> stringResource(MR.strings.leaf_novel_search_results, state.total, state.chapterCount)
            else -> null
        },
        state.skipped.takeIf { it > 0 }?.let { stringResource(MR.strings.leaf_novel_search_skipped, it) },
    )
    lines.forEach {
        Text(
            text = it,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier
                .padding(horizontal = MaterialTheme.padding.medium, vertical = 2.dp)
                .secondaryItemAlpha(),
        )
    }
    Spacer(Modifier.height(MaterialTheme.padding.extraSmall))
}

@Composable
private fun ChapterHeader(name: String, count: Int, current: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.titleSmall,
            color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier
                .padding(start = MaterialTheme.padding.small)
                .secondaryItemAlpha(),
        )
    }
}

@Composable
private fun ResultItem(result: NovelSearchResult, selected: Boolean, onClick: () -> Unit) {
    val mark = SpanStyle(
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        background = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
    )
    val text = remember(result, mark) {
        buildAnnotatedString {
            append(result.snippet.substring(0, result.highlight.first))
            withStyle(mark) { append(result.snippet.substring(result.highlight.first, result.highlight.last + 1)) }
            append(result.snippet.substring(result.highlight.last + 1))
        }
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (selected) Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest) else Modifier,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = MaterialTheme.padding.medium, vertical = 10.dp),
    )
}

@Composable
private fun RecentSearches(recent: List<String>, onSearch: (String) -> Unit, onClear: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.padding(start = MaterialTheme.padding.medium, end = MaterialTheme.padding.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(MR.strings.leaf_novel_search_recent),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClear) {
                Text(stringResource(MR.strings.pref_clear_history))
            }
        }
        recent.forEach { query ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSearch(query) }
                    .padding(horizontal = MaterialTheme.padding.medium, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium),
            ) {
                Icon(
                    imageVector = MaterialSymbols.Rounded.Schedule,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp).secondaryItemAlpha(),
                )
                Text(
                    text = query,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Column(
        modifier = Modifier
            .padding(MaterialTheme.padding.large)
            .secondaryItemAlpha(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
    ) {
        Icon(MaterialSymbols.Rounded.Search, contentDescription = null, modifier = Modifier.size(48.dp))
        Text(text = text, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    }
}

/**
 * Steps through the results from the page itself, after a jump, so the next one is a tap away
 * rather than back through the menu and the list. Tapping the count opens the list again.
 */
@Composable
fun NovelSearchNavigator(
    results: List<NovelSearchResult>,
    current: NovelSearchResult,
    onOpen: (NovelSearchResult) -> Unit,
    onShowList: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // A search run again since, for another chapter or other words, leaves nothing to step through.
    val index = results.indexOfFirst { it.key == current.key }
    if (index < 0) return
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 3.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onOpen(results[index - 1]) }, enabled = index > 0) {
                Icon(MaterialSymbols.Rounded.KeyboardArrowUp, stringResource(MR.strings.leaf_novel_search_previous))
            }
            Text(
                text = "${index + 1} / ${results.size}",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onShowList)
                    .padding(horizontal = MaterialTheme.padding.small, vertical = MaterialTheme.padding.small),
            )
            IconButton(onClick = { onOpen(results[index + 1]) }, enabled = index < results.lastIndex) {
                Icon(MaterialSymbols.Rounded.KeyboardArrowDown, stringResource(MR.strings.leaf_novel_search_next))
            }
            IconButton(onClick = onClose) {
                Icon(MaterialSymbols.Rounded.Close, stringResource(MR.strings.action_close))
            }
        }
    }
}
