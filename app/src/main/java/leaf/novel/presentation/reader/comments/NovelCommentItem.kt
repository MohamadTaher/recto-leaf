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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import leaf.novel.ui.reader.comments.NovelCommentTree
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.ExpandMore
import mihon.icons.materialsymbols.rounded.MoreVert
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Avatar gutter, byline, readable body and a consistent reaction row — the same shape whichever
 * extension the comment came from.
 *
 * The byline is the part that has to agree across sites: the name top left with the date beneath
 * it, the rating top right with the extension beneath it, and for a comment with no rating the
 * extension in the rating's place. Whatever a site adds of its own to a user — a tier, a title, a
 * tagline — is left out, because no two sites mean the same thing by one.
 */
@Composable
fun NovelCommentItem(
    comment: NovelComment,
    collapsed: Boolean,
    hiddenCount: Int,
    capabilities: NovelCommentCapabilities,
    feedback: NovelCommentFeedback,
    /** The extension the comment came from, set unless the sheet is filtered to one. */
    sourceName: String?,
    /** Whether to name the chapter a comment is on, which only a novel-wide listing needs. */
    showChapter: Boolean,
    voting: Boolean,
    repliesExpanded: Boolean,
    loadingReplies: Boolean,
    showAvatar: Boolean,
    spoilerGuard: Boolean,
    onToggleCollapsed: () -> Unit,
    onVote: (NovelCommentVote) -> Unit,
    onToggleReplies: () -> Unit,
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
    val rating = (feedback.rating ?: review.rating).takeUnless { comment.deleted || hidden }
    val hasAvatar = showAvatar && capabilities.avatars && !comment.deleted
    val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    val replyCount = maxOf(
        NovelCommentTree.count(comment.replies),
        if (capabilities.lazyReplies) comment.replyCount else 0,
    )

    Row(
        modifier = modifier.fillMaxWidth()
            .drawBehind {
                drawLine(
                    color = dividerColor,
                    start = Offset((if (hasAvatar) 42.dp else 8.dp).toPx(), size.height),
                    end = Offset(size.width - 8.dp.toPx(), size.height),
                    strokeWidth = 0.5.dp.toPx(),
                )
            }
            .padding(top = 14.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (hasAvatar) CommentAvatar(comment)
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f).padding(top = 3.dp)) {
                    Text(
                        text = if (comment.deleted) {
                            stringResource(MR.strings.leaf_novel_comments_deleted)
                        } else {
                            comment.author
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (comment.byUploader) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    val details = listOfNotNull(
                        comment.postedAt.takeIf { it > 0 }?.let { relativeTimeSpanString(it) },
                        comment.chapterLabel?.takeIf { showChapter && it.isNotBlank() },
                        stringResource(MR.strings.leaf_novel_comments_pinned).takeIf { comment.pinned },
                    )
                    if (details.isNotEmpty()) {
                        Text(
                            text = details.joinToString(" · "),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (rating != null || sourceName != null) {
                    Column(
                        modifier = Modifier.padding(start = 8.dp, top = 5.dp),
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        rating?.let { NovelCommentRatingRow(it) }
                        sourceName?.let {
                            Text(
                                text = it,
                                maxLines = 1,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(32.dp)) {
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
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = if (expanded) Int.MAX_VALUE else 4,
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.heightIn(min = 40.dp)
                                .clickable(role = Role.Button, onClick = { expanded = !expanded })
                                .padding(vertical = 10.dp),
                        )
                    }
                }
            }
            if (!comment.deleted) {
                NovelCommentFeedbackRow(comment, feedback, capabilities, voting, onVote)
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
            if (replyCount > 0) {
                val label = stringResource(
                    if (replyCount ==
                        1
                    ) {
                        MR.strings.leaf_novel_comments_one_reply
                    } else {
                        MR.strings.leaf_novel_comments_replies
                    },
                    replyCount,
                )
                val action = if (repliesExpanded) stringResource(MR.strings.leaf_novel_comments_hide_replies) else label
                Row(
                    modifier = Modifier.heightIn(min = 44.dp)
                        .clickable(role = Role.Button, onClick = onToggleReplies)
                        .semantics { contentDescription = action }
                        .padding(end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    if (loadingReplies && comment.replies.isEmpty()) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
                    } else {
                        Icon(
                            MaterialSymbols.Rounded.ExpandMore,
                            null,
                            modifier = Modifier.size(18.dp).rotate(if (repliesExpanded) 0f else -90f),
                        )
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
