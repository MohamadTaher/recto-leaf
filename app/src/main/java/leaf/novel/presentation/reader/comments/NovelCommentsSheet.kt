package leaf.novel.presentation.reader.comments

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.components.DropdownMenu
import kotlinx.coroutines.launch
import leaf.novel.api.NovelComment
import leaf.novel.api.NovelCommentScope
import leaf.novel.ui.reader.comments.NovelCommentKind
import leaf.novel.ui.reader.comments.NovelCommentLocalSort
import leaf.novel.ui.reader.comments.NovelCommentRow
import leaf.novel.ui.reader.comments.NovelCommentTree
import leaf.novel.ui.reader.comments.NovelComments
import leaf.novel.ui.reader.comments.NovelCommentsState
import leaf.novel.ui.reader.setting.NovelReaderPreferences
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.Sort
import mihon.icons.materialsymbols.rounded.Check
import mihon.icons.materialsymbols.rounded.Close
import mihon.icons.materialsymbols.rounded.ExpandLess
import mihon.icons.materialsymbols.rounded.ExpandMore
import mihon.icons.materialsymbols.rounded.MoreVert
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

    LaunchedEffect(comments) { comments.open() }

    // Focusing re-roots the list, so it has to start at the top rather than wherever the previous
    // thread happened to be scrolled to.
    LaunchedEffect(state.focus, state.sort, state.kind, state.origin) {
        listState.scrollToItem(0)
    }

    // Closing something long — a comment's text, or a thread of replies — leaves the reader wherever
    // its end was, which is past everything that followed it. So a close brings the comment's own
    // top back into view, but only when that top has gone off the top of the list: a comment still
    // on screen stays exactly where it is.
    fun scrollBackTo(id: String) {
        val index = comments.state.value.rows.indexOfFirst { it is NovelCommentRow.Body && it.comment.id == id }
        if (index < 0) return
        val first = listState.firstVisibleItemIndex
        if (index < first || (index == first && listState.firstVisibleItemScrollOffset > 0)) {
            scope.launch { listState.animateScrollToItem(index) }
        }
    }

    fun toggleReplies(comment: NovelComment) {
        val closing = comment.id in comments.state.value.expandedReplies
        comments.toggleReplies(comment)
        if (closing) scrollBackTo(comment.id)
    }

    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        Column(modifier = Modifier.fillMaxHeight(0.9f).navigationBarsPadding().imePadding()) {
            NovelCommentsHeader(
                state = state,
                onRefresh = comments::reload,
                onSetKind = comments::setKind,
                onSetOrigin = comments::setOrigin,
                onSetSort = comments::setSort,
                onCollapseAll = comments::collapseAll,
                onExpandAll = comments::expandAll,
                onClearFocus = { comments.focus(null) },
                onDismiss = onDismissRequest,
                onNextComment = {
                    scope.launch {
                        val from = listState.firstVisibleItemIndex + 1
                        val next = state.rows.withIndex()
                            .firstOrNull { (index, row) -> index >= from && row.ancestors.isEmpty() }
                        next?.let { listState.animateScrollToItem(it.index) }
                    }
                },
            )

            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
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
                        modifier = Modifier.fillMaxHeight(),
                        contentPadding = ListPadding,
                    ) {
                        items(state.rows, key = { it.key }) { row ->
                            NovelCommentThreadRow(
                                ancestors = row.ancestors,
                                onCollapse = { id ->
                                    NovelCommentTree.find(state.roots, id)?.let(::toggleReplies)
                                },
                            ) {
                                when (row) {
                                    is NovelCommentRow.Body -> NovelCommentItem(
                                        comment = row.comment,
                                        depth = row.ancestors.size,
                                        collapsed = row.collapsed,
                                        hiddenCount = row.hiddenCount,
                                        capabilities = comments.capabilities(row.comment) ?: capabilities,
                                        feedback = comments.feedback(row.comment),
                                        sourceName = comments.sourceName(row.comment),
                                        showChapter = state.scope == NovelCommentScope.NOVEL,
                                        voting = row.comment.id in state.voting,
                                        repliesExpanded = row.comment.id in state.expandedReplies,
                                        loadingReplies = row.comment.id in state.loadingReplies,
                                        showAvatar = showAvatars,
                                        spoilerGuard = spoilerGuard,
                                        onToggleCollapsed = { comments.toggleCollapsed(row.comment.id) },
                                        onVote = { vote -> comments.vote(row.comment, vote) },
                                        onToggleReplies = { toggleReplies(row.comment) },
                                        onFocus = { comments.focus(row.comment.id) },
                                        onOpenLink = uriHandler::openUri,
                                        onShrink = { scrollBackTo(row.comment.id) },
                                    )

                                    is NovelCommentRow.MoreReplies -> ThreadAction(
                                        label = stringResource(
                                            MR.strings.leaf_novel_comments_show_replies,
                                            row.count,
                                        ),
                                        loading = row.loading,
                                        onClick = { comments.loadReplies(row.comment) },
                                    )

                                    is NovelCommentRow.HideReplies -> NovelCommentRepliesRow(
                                        depth = row.ancestors.size,
                                        label = stringResource(MR.strings.leaf_novel_comments_collapse_replies),
                                        open = true,
                                        loading = row.loading,
                                        onClick = { toggleReplies(row.comment) },
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

                        // No button while the rest is on its way — the next page is already
                        // being fetched, and a button that says "load more" over a fetch that is
                        // running is one the reader can only get wrong. It appears only where the
                        // fetch stopped short: at the page cap, or on a failure part way down.
                        if (state.loadingMore || state.hasMore) {
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
    onSetKind: (NovelCommentKind) -> Unit,
    onSetOrigin: (Long?) -> Unit,
    onSetSort: (NovelCommentLocalSort) -> Unit,
    onCollapseAll: () -> Unit,
    onExpandAll: () -> Unit,
    onClearFocus: () -> Unit,
    onNextComment: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (state.capabilities == null) return
    var menuExpanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = MaterialTheme.padding.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Named for what is showing: a discussion while reviews and comments are shown together,
            // and otherwise whichever of the two it is.
            val mixed = state.kind == NovelCommentKind.ALL &&
                NovelCommentKind.REVIEWS in state.kinds && NovelCommentKind.COMMENTS in state.kinds
            val reviews = !mixed &&
                (state.kind == NovelCommentKind.REVIEWS || state.kinds == setOf(NovelCommentKind.REVIEWS))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        when {
                            mixed -> MR.strings.leaf_novel_comments_discussion
                            reviews -> MR.strings.leaf_novel_comments_reviews
                            else -> MR.strings.leaf_novel_comments
                        },
                    ),
                    style = MaterialTheme.typography.titleLarge,
                )
                if (state.loaded) {
                    val reviewCount = stringResource(MR.strings.leaf_novel_comments_review_count, state.reviewCount)
                    val commentCount = stringResource(MR.strings.leaf_novel_comments_count, state.commentCount)
                    Text(
                        text = when {
                            mixed -> "$reviewCount · $commentCount"
                            reviews -> reviewCount
                            else -> commentCount
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = MaterialSymbols.Rounded.MoreVert,
                        contentDescription = stringResource(MR.strings.action_menu_overflow_description),
                    )
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(MR.strings.action_webview_refresh)) },
                        enabled = !state.loading && !state.posting && state.voting.isEmpty(),
                        onClick = {
                            menuExpanded = false
                            onRefresh()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(MR.strings.leaf_novel_comments_next)) },
                        enabled = state.rows.isNotEmpty(),
                        onClick = {
                            menuExpanded = false
                            onNextComment()
                        },
                    )
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
            IconButton(onClick = onDismiss) {
                Icon(MaterialSymbols.Rounded.Close, contentDescription = stringResource(MR.strings.action_close))
            }
        }

        state.chapterName?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        NovelCommentFilters(state, onSetKind, onSetOrigin, onSetSort)

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

/**
 * The order, then the two filters, nested: reviews or comments, and within that which extension.
 *
 * One row whatever the sources are, so the sheet looks the same on every novel. The review toggle
 * appears only where there are both reviews and comments to choose between, the extension picker
 * only once a second extension has the novel, and a spinner sits beside it while the others are
 * still being searched.
 */
@Composable
private fun NovelCommentFilters(
    state: NovelCommentsState,
    onSetKind: (NovelCommentKind) -> Unit,
    onSetOrigin: (Long?) -> Unit,
    onSetSort: (NovelCommentLocalSort) -> Unit,
) {
    val enabled = !state.posting && state.voting.isEmpty() && state.draft.isBlank()
    var sortExpanded by remember { mutableStateOf(false) }
    var originExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            IconButton(onClick = { sortExpanded = true }) {
                Icon(MaterialSymbols.AutoMirroredRounded.Sort, stringResource(MR.strings.action_sort))
            }
            DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                NovelCommentLocalSort.entries.forEach { sort ->
                    DropdownMenuItem(
                        text = { Text(stringResource(sort.titleRes)) },
                        trailingIcon = { if (sort == state.sort) Icon(MaterialSymbols.Rounded.Check, null) },
                        onClick = {
                            sortExpanded = false
                            onSetSort(sort)
                        },
                    )
                }
            }
        }

        if (NovelCommentKind.REVIEWS in state.kinds && NovelCommentKind.COMMENTS in state.kinds) {
            NovelCommentKindToggle(state.kind, enabled) { onSetKind(state.kind.next) }
        }

        Spacer(Modifier.weight(1f))

        if (state.searching) {
            val searching = stringResource(MR.strings.leaf_novel_comments_searching)
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(horizontal = MaterialTheme.padding.small)
                    .size(16.dp)
                    .semantics { contentDescription = searching },
                strokeWidth = 2.dp,
            )
        }

        if (state.origins.size > 1) {
            Box {
                TextButton(onClick = { originExpanded = true }, enabled = enabled) {
                    Text(
                        text = state.origins.firstOrNull { it.id == state.origin }?.name
                            ?: stringResource(MR.strings.leaf_novel_comments_all_extensions),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(MaterialSymbols.Rounded.ExpandMore, null, modifier = Modifier.size(18.dp))
                }
                DropdownMenu(expanded = originExpanded, onDismissRequest = { originExpanded = false }) {
                    NovelCommentOriginItem(
                        label = stringResource(MR.strings.leaf_novel_comments_all_extensions),
                        selected = state.origin == null,
                    ) {
                        originExpanded = false
                        onSetOrigin(null)
                    }
                    state.origins.forEach { origin ->
                        NovelCommentOriginItem(
                            label = origin.count?.let {
                                stringResource(MR.strings.leaf_novel_comments_feed_count, origin.name, it)
                            } ?: origin.name,
                            selected = state.origin == origin.id,
                        ) {
                            originExpanded = false
                            onSetOrigin(origin.id)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NovelCommentOriginItem(label: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        trailingIcon = { if (selected) Icon(MaterialSymbols.Rounded.Check, null) },
        onClick = onClick,
    )
}

/**
 * The review filter as one chip that cycles: grey for everything, green for reviews alone, red for
 * comments alone. A star, because a rating is what makes a review one.
 */
@Composable
private fun NovelCommentKindToggle(kind: NovelCommentKind, enabled: Boolean, onClick: () -> Unit) {
    val (description, tint) = when (kind) {
        NovelCommentKind.ALL -> MR.strings.leaf_novel_comments_kind_all to Color.Unspecified
        NovelCommentKind.REVIEWS -> MR.strings.leaf_novel_comments_kind_reviews to ReviewsGreen
        NovelCommentKind.COMMENTS -> MR.strings.leaf_novel_comments_kind_comments to CommentsRed
    }
    val label = stringResource(description)
    FilterChip(
        selected = kind != NovelCommentKind.ALL,
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.semantics { contentDescription = label },
        label = {
            Icon(
                imageVector = if (kind ==
                    NovelCommentKind.ALL
                ) {
                    NovelCommentGlyphs.Star
                } else {
                    NovelCommentGlyphs.FilledStar
                },
                contentDescription = null,
                modifier = Modifier.size(FilterChipDefaults.IconSize),
            )
        },
        colors = FilterChipDefaults.filterChipColors(
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            selectedContainerColor = tint.copy(alpha = 0.22f),
            selectedLabelColor = tint,
        ),
    )
}

private val ReviewsGreen = Color(0xFF43A047)
private val CommentsRed = Color(0xFFE53935)

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
                TextButton(onClick = { comments.replyTo(null) }, enabled = !state.posting) {
                    Text(stringResource(MR.strings.action_cancel))
                }
            }
        }

        OutlinedTextField(
            value = state.draft,
            onValueChange = comments::setDraft,
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

            FilledTonalButton(
                onClick = { comments.post(state.draft) },
                enabled = state.draft.isNotBlank() && !state.posting && state.loaded,
            ) {
                if (state.posting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(MR.strings.leaf_novel_comments_post))
            }
        }
    }
}

private const val COMPOSER_MAX_LINES = 6

private val ListPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
