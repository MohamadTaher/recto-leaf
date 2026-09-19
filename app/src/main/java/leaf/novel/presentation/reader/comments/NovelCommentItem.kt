package leaf.novel.presentation.reader.comments

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.presentation.util.relativeTimeSpanString
import eu.kanade.tachiyomi.util.system.copyToClipboard
import leaf.novel.api.NovelComment
import leaf.novel.api.NovelCommentCapabilities
import leaf.novel.api.NovelCommentFeedback
import leaf.novel.api.NovelCommentVote
import leaf.novel.ui.reader.comments.NovelCommentMarkup
import leaf.novel.ui.reader.comments.NovelCommentReview
import leaf.novel.ui.reader.comments.NovelCommentSpan
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.ExpandMore
import mihon.icons.materialsymbols.rounded.MoreVert
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/** Avatar gutter, compact byline, readable body and a consistent reaction row. */
@Composable
fun NovelCommentItem(
    comment: NovelComment,
    collapsed: Boolean,
    hiddenCount: Int,
    capabilities: NovelCommentCapabilities,
    feedback: NovelCommentFeedback,
    voting: Boolean,
    canReply: Boolean,
    showAvatar: Boolean,
    spoilerGuard: Boolean,
    onToggleCollapsed: () -> Unit,
    onVote: (NovelCommentVote) -> Unit,
    onReply: () -> Unit,
    onFocus: () -> Unit,
    onOpenLink: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val review = remember(comment.body) { NovelCommentReview.parse(comment.body) }
    val spans = remember(review.body, comment.permalink) { NovelCommentMarkup.parse(review.body, comment.permalink) }
    val hasSpoilers = remember(spans) { spans.any { it.spoiler } }
    var revealed by remember(comment.id) { mutableStateOf(false) }
    var expanded by rememberSaveable(comment.id, comment.body) { mutableStateOf(false) }
    var overflows by remember(comment.body) { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    val hidden = (spoilerGuard || hasSpoilers) && !revealed

    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (showAvatar && capabilities.avatars && !comment.deleted) CommentAvatar(comment)
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f).padding(top = 4.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = if (comment.deleted) {
                                stringResource(MR.strings.leaf_novel_comments_deleted)
                            } else {
                                comment.author
                            },
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = if (comment.byUploader) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                        if (comment.postedAt > 0) {
                            Text(
                                text = relativeTimeSpanString(comment.postedAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (comment.byUploader) Badge(stringResource(MR.strings.leaf_novel_comments_uploader))
                        if (comment.pinned) Badge(stringResource(MR.strings.leaf_novel_comments_pinned))
                        comment.badge?.takeIf { it.isNotBlank() }?.let { Badge(it) }
                        comment.chapterLabel?.takeIf { it.isNotBlank() }?.let { Badge(it) }
                    }
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(40.dp)) {
                        Icon(
                            MaterialSymbols.Rounded.MoreVert,
                            stringResource(MR.strings.action_menu_overflow_description),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        if (collapsed) {
                                            MR.strings.leaf_novel_comments_expand
                                        } else {
                                            MR.strings.leaf_novel_comments_collapse
                                        },
                                    ),
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onToggleCollapsed()
                            },
                        )
                        if (comment.replies.isNotEmpty() || comment.replyCount > 0) {
                            DropdownMenuItem(
                                text = { Text(stringResource(MR.strings.leaf_novel_comments_focus)) },
                                onClick = {
                                    menuExpanded = false
                                    onFocus()
                                },
                            )
                        }
                        if (!comment.deleted) {
                            DropdownMenuItem(
                                text = { Text(stringResource(MR.strings.action_copy_to_clipboard)) },
                                onClick = {
                                    menuExpanded = false
                                    context.copyToClipboard(comment.author, NovelCommentMarkup.plainText(comment.body))
                                },
                            )
                        }
                        comment.permalink?.let { url ->
                            DropdownMenuItem(
                                text = { Text(stringResource(MR.strings.action_open_in_browser)) },
                                onClick = {
                                    menuExpanded = false
                                    onOpenLink(url)
                                },
                            )
                        }
                    }
                }
            }
            if (collapsed) {
                TextButton(onClick = onToggleCollapsed) {
                    Icon(MaterialSymbols.Rounded.ExpandMore, null, modifier = Modifier.size(18.dp))
                    Text(
                        stringResource(MR.strings.leaf_novel_comments_expand) +
                            if (hiddenCount > 0) " · $hiddenCount" else "",
                    )
                }
                return@Column
            }
            if (!comment.deleted && !hidden) {
                (feedback.rating ?: review.rating)?.let { NovelCommentRatingRow(it) }
            }
            when {
                comment.deleted -> Unit
                hidden -> Text(
                    text = stringResource(MR.strings.leaf_novel_comments_spoiler),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .clickable(role = Role.Button, onClick = { revealed = true }).padding(12.dp),
                )
                else -> {
                    Text(
                        text = spans.toAnnotatedString(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = if (expanded) Int.MAX_VALUE else 6,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
                    )
                    if (expanded || overflows) {
                        Text(
                            text = stringResource(
                                if (expanded) {
                                    MR.strings.manga_info_collapse
                                } else {
                                    MR.strings.leaf_novel_comments_read_more
                                },
                            ),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.heightIn(min = 40.dp)
                                .clickable(role = Role.Button, onClick = { expanded = !expanded })
                                .padding(vertical = 10.dp),
                        )
                    }
                }
            }
            if (!comment.deleted) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    NovelCommentFeedbackRow(comment, feedback, capabilities, voting, onVote)
                    if (canReply) {
                        TextButton(onClick = onReply) { Text(stringResource(MR.strings.leaf_novel_comments_reply)) }
                    }
                }
                if (feedback.reactions.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        feedback.reactions.forEach { reaction ->
                            Badge(
                                listOfNotNull(
                                    reaction.emoji,
                                    reaction.label,
                                    reaction.count?.toString(),
                                    if (reaction.selected) {
                                        stringResource(MR.strings.leaf_novel_comments_your_reaction)
                                    } else {
                                        null
                                    },
                                ).joinToString(" "),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommentAvatar(comment: NovelComment) {
    var failed by remember(comment.avatarUrl) { mutableStateOf(false) }
    if (comment.avatarUrl != null && !failed) {
        AsyncImage(
            model = comment.avatarUrl,
            contentDescription = null,
            onError = { failed = true },
            modifier = Modifier.size(32.dp).clip(CircleShape),
        )
    } else {
        Box(
            modifier = Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                comment.author.take(1).uppercase(),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun Badge(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

/**
 * One vertical line per ancestor, tappable to fold that ancestor.
 *
 * The rails are what make a deep thread readable without counting indents, and making each one
 * collapse its own ancestor means getting out of a long sub-thread is one tap on the line beside
 * it rather than a scroll back up to find its head.
 */
@Composable
private fun NovelCommentRails(
    ancestors: List<String>,
    onCollapse: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxHeight()) {
        ancestors.forEach { id ->
            Box(
                modifier = Modifier
                    .width(RAIL_SPACING)
                    .fillMaxHeight()
                    .clickable { onCollapse(id) },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .width(RAIL_WIDTH)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
        }
    }
}

/**
 * A row with its rails beside it, which is every row the sheet draws.
 *
 * `IntrinsicSize.Min` is what lets the rails run the exact height of the content next to them; a
 * `fillMaxHeight` in a `Row` with no height of its own would otherwise measure as zero.
 */
@Composable
fun NovelCommentThreadRow(
    ancestors: List<String>,
    onCollapse: (String) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
    ) {
        NovelCommentRails(ancestors = ancestors, onCollapse = onCollapse)
        Box(modifier = Modifier.weight(1f)) { content() }
    }
}

/**
 * The spans as Compose sees them.
 *
 * Links become real [LinkAnnotation]s so the platform handles the tap, the long press and the
 * accessibility of a link, none of which a hand-rolled clickable would get right.
 */
private fun List<NovelCommentSpan>.toAnnotatedString(
    linkColor: Color,
    quoteColor: Color,
): AnnotatedString =
    buildAnnotatedString {
        this@toAnnotatedString.forEach { span ->
            val style = SpanStyle(
                // A quote is set in italics and a quieter colour rather than behind a rule: a rule
                // would have to be its own element, and this is one string of text.
                color = if (span.quote) quoteColor else Color.Unspecified,
                fontWeight = if (span.bold) FontWeight.Bold else null,
                fontStyle = if (span.italic || span.quote) FontStyle.Italic else null,
                fontFamily = if (span.code) FontFamily.Monospace else null,
                textDecoration = if (span.strikethrough) TextDecoration.LineThrough else null,
            )
            if (span.link != null) {
                withLink(
                    LinkAnnotation.Url(
                        url = span.link,
                        styles = TextLinkStyles(style = style.copy(color = linkColor)),
                    ),
                ) {
                    append(span.text)
                }
            } else {
                withStyle(style) { append(span.text) }
            }
        }
    }

private val RAIL_SPACING = 10.dp
private val RAIL_WIDTH = 1.dp
