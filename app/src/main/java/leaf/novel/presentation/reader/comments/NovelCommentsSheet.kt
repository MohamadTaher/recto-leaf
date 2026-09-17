package leaf.novel.presentation.reader.comments

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.components.DropdownMenu
import kotlinx.coroutines.launch
import leaf.novel.api.NovelCommentScope
import leaf.novel.api.NovelCommentSort
import leaf.novel.ui.reader.comments.NovelCommentLocalSort
import leaf.novel.ui.reader.comments.NovelCommentRow
import leaf.novel.ui.reader.comments.NovelComments
import leaf.novel.ui.reader.comments.NovelCommentsState
import leaf.novel.ui.reader.setting.NovelReaderPreferences
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.ExpandLess
import mihon.icons.materialsymbols.rounded.ExpandMore
import mihon.icons.materialsymbols.rounded.MoreVert
import mihon.icons.materialsymbols.rounded.Refresh
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import tachiyomi.presentation.core.util.secondaryItemAlpha

/**
 * The comments, over the chapter.
 *
 * Takes the controller rather than two dozen callbacks. Every control on this sheet is one call on
 * [NovelComments], the sheet holds no state of its own beyond the composer's draft and which menu
 * is open, and threading twenty lambdas through the reader screen to say the same thing would be
 * the larger file, not the smaller one.
 *
 * On the reader's own [AdaptiveSheet], so it is a bottom sheet on a phone and a centred dialog on a
 * tablet without a second layout, exactly as the chapter list is.
 */
@Composable
fun NovelCommentsSheet(
    comments: NovelComments,
    preferences: NovelReaderPreferences,
    onDismissRequest: () -> Unit,
) {
    val state by comments.state.collectAsState()
    val capabilities = state.capabilities ?: return
    val showAvatars by preferences.commentsShowAvatars.collectAsState()
    val spoilerGuard by preferences.commentsSpoilerGuard.collectAsState()
    val uriHandler = LocalUriHandler.current

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Focusing re-roots the list, so it has to start at the top rather than wherever the previous
    // thread happened to be scrolled to.
    LaunchedEffect(state.focus, state.scope, state.sortKey) {
        listState.scrollToItem(0)
    }

    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        Column(modifier = Modifier.navigationBarsPadding().imePadding()) {
            NovelCommentsHeader(
                state = state,
                onRefresh = comments::reload,
                onSetScope = comments::setScope,
                onSetSort = comments::setSort,
                onSetLocalSort = comments::setLocalSort,
                onCollapseAll = comments::collapseAll,
                onExpandAll = comments::expandAll,
                onClearFocus = { comments.focus(null) },
                onNextComment = {
                    scope.launch {
                        val from = listState.firstVisibleItemIndex + 1
                        val next = state.rows.withIndex()
                            .firstOrNull { (index, row) -> index >= from && row.ancestors.isEmpty() }
                        next?.let { listState.animateScrollToItem(it.index) }
                    }
                },
            )

            HorizontalDivider()

            Box(modifier = Modifier.weight(1f, fill = false)) {
                when {
                    state.loading -> Loading()

                    // Only reachable with auto-load off, which is a reader who asked to be asked.
                    !state.loaded && state.error == null -> Message(
                        text = null,
                        action = stringResource(MR.strings.leaf_novel_comments_load),
                        onAction = comments::reload,
                    )

                    state.error != null && state.rows.isEmpty() -> Message(
                        text = state.error.orEmpty(),
                        action = stringResource(MR.strings.action_retry),
                        onAction = comments::reload,
                    )

                    state.isEmpty -> Message(
                        text = stringResource(
                            if (state.scope == NovelCommentScope.NOVEL) {
                                MR.strings.leaf_novel_comments_empty_novel
                            } else {
                                MR.strings.leaf_novel_comments_empty
                            },
                        ),
                    )

                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.heightIn(max = MAX_LIST_HEIGHT),
                        contentPadding = ListPadding,
                    ) {
                        items(state.rows, key = { it.key }) { row ->
                            NovelCommentThreadRow(
                                ancestors = row.ancestors,
                                onCollapse = comments::toggleCollapsed,
                            ) {
                                when (row) {
                                    is NovelCommentRow.Body -> NovelCommentItem(
                                        comment = row.comment,
                                        collapsed = row.collapsed,
                                        hiddenCount = row.hiddenCount,
                                        capabilities = capabilities,
                                        showAvatar = showAvatars,
                                        spoilerGuard = spoilerGuard,
                                        onToggleCollapsed = { comments.toggleCollapsed(row.comment.id) },
                                        onVote = { vote -> comments.vote(row.comment, vote) },
                                        onReply = { comments.replyTo(row.comment) },
                                        onFocus = { comments.focus(row.comment.id) },
                                        onOpenLink = uriHandler::openUri,
                                    )

                                    is NovelCommentRow.MoreReplies -> ThreadAction(
                                        label = stringResource(
                                            MR.strings.leaf_novel_comments_show_replies,
                                            row.count,
                                        ),
                                        loading = row.loading,
                                        onClick = { comments.loadReplies(row.comment) },
                                    )

                                    is NovelCommentRow.ContinueThread -> ThreadAction(
                                        label = stringResource(
                                            MR.strings.leaf_novel_comments_continue_thread,
                                            row.count,
                                        ),
                                        loading = false,
                                        onClick = { comments.focus(row.comment.id) },
                                    )
                                }
                            }
                        }

                        if (state.hasMore) {
                            item(key = "more") {
                                ThreadAction(
                                    label = stringResource(MR.strings.leaf_novel_comments_load_more),
                                    loading = state.loadingMore,
                                    onClick = comments::loadMore,
                                )
                            }
                        }
                    }
                }
            }

            // A failure with comments already on screen is a banner rather than a replacement: the
            // thread that did load is still worth reading, and the vote that did not is worth
            // knowing about.
            if (state.error != null && state.rows.isNotEmpty()) {
                ErrorBanner(message = state.error.orEmpty(), onDismiss = comments::dismissError)
            }

            if (capabilities.posting) {
                HorizontalDivider()
                NovelCommentComposer(state = state, comments = comments)
            }
        }
    }
}

/** The title, the counts, and everything that changes what the list below is. */
@Composable
private fun NovelCommentsHeader(
    state: NovelCommentsState,
    onRefresh: () -> Unit,
    onSetScope: (NovelCommentScope) -> Unit,
    onSetSort: (NovelCommentSort) -> Unit,
    onSetLocalSort: (NovelCommentLocalSort) -> Unit,
    onCollapseAll: () -> Unit,
    onExpandAll: () -> Unit,
    onClearFocus: () -> Unit,
    onNextComment: () -> Unit,
) {
    val capabilities = state.capabilities ?: return
    var menuExpanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = MaterialTheme.padding.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(MR.strings.leaf_novel_comments),
                style = MaterialTheme.typography.titleMedium,
            )
            if (state.loaded) {
                Spacer(Modifier.width(MaterialTheme.padding.small))
                Text(
                    text = stringResource(MR.strings.leaf_novel_comments_count, state.count),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.secondaryItemAlpha(),
                )
            }

            Spacer(Modifier.weight(1f))

            if (state.loaded && state.rows.isNotEmpty()) {
                IconButton(onClick = onNextComment) {
                    Icon(
                        imageVector = MaterialSymbols.Rounded.ExpandMore,
                        contentDescription = stringResource(MR.strings.leaf_novel_comments_next),
                    )
                }
            }

            IconButton(onClick = onRefresh) {
                Icon(
                    imageVector = MaterialSymbols.Rounded.Refresh,
                    contentDescription = stringResource(MR.strings.action_retry),
                )
            }

            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(imageVector = MaterialSymbols.Rounded.MoreVert, contentDescription = null)
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(MR.strings.leaf_novel_comments_collapse_all)) },
                        onClick = {
                            menuExpanded = false
                            onCollapseAll()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(MR.strings.leaf_novel_comments_expand_all)) },
                        onClick = {
                            menuExpanded = false
                            onExpandAll()
                        },
                    )
                }
            }
        }

        // Only where the site has both. One chip that cannot be turned off is not a choice.
        if (capabilities.scopes.size > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall)) {
                NovelCommentScope.entries
                    .filter { it in capabilities.scopes }
                    .forEach { scope ->
                        FilterChip(
                            selected = state.scope == scope,
                            onClick = { onSetScope(scope) },
                            label = {
                                Text(
                                    stringResource(
                                        if (scope == NovelCommentScope.CHAPTER) {
                                            MR.strings.leaf_novel_comments_scope_chapter
                                        } else {
                                            MR.strings.leaf_novel_comments_scope_novel
                                        },
                                    ),
                                )
                            },
                        )
                    }
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall)) {
            if (state.sorts.isNotEmpty()) {
                state.sorts.forEach { sort ->
                    FilterChip(
                        selected = state.sortKey == sort.key ||
                            (state.sortKey == null && sort == state.sorts.first()),
                        onClick = { onSetSort(sort) },
                        label = { Text(sort.label) },
                    )
                }
            } else {
                NovelCommentLocalSort.entries.forEach { sort ->
                    FilterChip(
                        selected = state.localSort == sort,
                        onClick = { onSetLocalSort(sort) },
                        label = { Text(stringResource(sort.titleRes)) },
                    )
                }
            }
        }

        // Said out loud, because a local sort over a paginated site orders the page rather than the
        // thread and a reader who is not told will read it as the site's own answer.
        if (state.sorts.isEmpty() && state.hasMore) {
            Text(
                text = stringResource(MR.strings.leaf_novel_comments_sort_local),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.secondaryItemAlpha(),
            )
        }

        if (state.focus != null) {
            TextButton(
                onClick = onClearFocus,
                contentPadding = PaddingValues(0.dp),
            ) {
                Icon(
                    imageVector = MaterialSymbols.Rounded.ExpandLess,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(MaterialTheme.padding.extraSmall))
                Text(stringResource(MR.strings.leaf_novel_comments_back_to_thread))
            }
        }
    }
}

/** A "load more", "show replies" or "continue this thread" row — the same shape for all three. */
@Composable
private fun ThreadAction(label: String, loading: Boolean, onClick: () -> Unit) {
    if (loading) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MaterialTheme.padding.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        }
    } else {
        TextButton(onClick = onClick) { Text(label) }
    }
}

@Composable
private fun Loading() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(MaterialTheme.padding.large),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

/** An empty thread, an unloaded one or a failure. All three are one line and at most one button. */
@Composable
private fun Message(text: String?, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(MaterialTheme.padding.large),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Null where the button says everything: a line of explanation above a button labelled
        // "load comments" would only be the button again in a quieter colour.
        if (text != null) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.secondaryItemAlpha(),
            )
        }
        if (action != null && onAction != null) {
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.padding.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss) { Text(stringResource(MR.strings.action_close)) }
    }
}

/**
 * The composer, for the sites that take a comment back.
 *
 * Only drawn when the source says it can post. A field that collects what someone typed and then
 * cannot send it is the worst possible version of this feature.
 */
@Composable
private fun NovelCommentComposer(state: NovelCommentsState, comments: NovelComments) {
    var draft by remember(state.replyingTo?.id) { mutableStateOf("") }

    Column(modifier = Modifier.padding(MaterialTheme.padding.medium)) {
        state.replyingTo?.let { parent ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(MR.strings.leaf_novel_comments_replying_to, parent.author),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .weight(1f)
                        .secondaryItemAlpha(),
                )
                TextButton(onClick = { comments.replyTo(null) }) {
                    Text(stringResource(MR.strings.action_cancel))
                }
            }
        }

        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(MR.strings.leaf_novel_comments_write)) },
            enabled = !state.posting,
            maxLines = COMPOSER_MAX_LINES,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.capabilities?.requiresAccount == true) {
                Text(
                    text = stringResource(MR.strings.leaf_novel_comments_requires_account),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .weight(1f)
                        .secondaryItemAlpha(),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }

            TextButton(
                onClick = {
                    comments.post(draft)
                    draft = ""
                },
                enabled = draft.isNotBlank() && !state.posting,
            ) {
                Text(stringResource(MR.strings.leaf_novel_comments_post))
            }
        }
    }
}

/** Tall enough to be worth opening, short enough to leave the chapter visible behind it. */
private val MAX_LIST_HEIGHT = 520.dp

private const val COMPOSER_MAX_LINES = 6

private val ListPadding = PaddingValues(all = 8.dp)
