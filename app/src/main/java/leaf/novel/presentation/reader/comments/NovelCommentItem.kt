package leaf.novel.presentation.reader.comments

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.presentation.util.relativeTimeSpanString
import eu.kanade.tachiyomi.util.system.copyToClipboard
import leaf.novel.api.NovelComment
import leaf.novel.api.NovelCommentCapabilities
import leaf.novel.api.NovelCommentVote
import leaf.novel.ui.reader.comments.NovelCommentMarkup
import leaf.novel.ui.reader.comments.NovelCommentSpan
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.ArrowDownward
import mihon.icons.materialsymbols.rounded.ArrowUpward
import mihon.icons.materialsymbols.rounded.Favorite
import mihon.icons.materialsymbols.rounded.MoreVert
import mihon.icons.materialsymbols.rounded.Person
import mihon.icons.materialsymbols.rounded.PushPin
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.secondaryItemAlpha

/**
 * One comment: its rails, its byline, its text and whatever the site lets you do to it.
 *
 * Everything below the byline is conditional on a capability, so a site with no scores draws no
 * arrows and a site with no replies draws no reply button. The alternative — drawing them greyed
 * out — tells the reader the site has a feature it does not have.
 */
@Composable
fun NovelCommentItem(
    comment: NovelComment,
    collapsed: Boolean,
    hiddenCount: Int,
    capabilities: NovelCommentCapabilities,
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
    val spans = remember(comment.body) { NovelCommentMarkup.parse(comment.body, comment.permalink) }
    val hasSpoilers = remember(spans) { spans.any { it.spoiler } }
    // Per comment and not remembered across a reload: a thread that refreshes should not silently
    // uncover what the reader covered back up.
    var revealed by remember(comment.id) { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }

    val hidden = (spoilerGuard || hasSpoilers) && !revealed

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(end = MaterialTheme.padding.small, bottom = MaterialTheme.padding.small),
    ) {
        Byline(
            comment = comment,
            collapsed = collapsed,
            hiddenCount = hiddenCount,
            showAvatar = showAvatar && capabilities.avatars,
            onClick = onToggleCollapsed,
        )

        if (collapsed) return@Column

        Spacer(Modifier.height(MaterialTheme.padding.extraSmall))
        when {
            comment.deleted -> Text(
                text = stringResource(MR.strings.leaf_novel_comments_deleted),
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic,
                modifier = Modifier.secondaryItemAlpha(),
            )
            hidden -> SpoilerCurtain(onReveal = { revealed = true })
            else -> Text(
                text = spans.toAnnotatedString(
                    linkColor = MaterialTheme.colorScheme.primary,
                    quoteColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (capabilities.scored || capabilities.voting) {
                Votes(
                    comment = comment,
                    capabilities = capabilities,
                    onVote = onVote,
                )
            }

            if (capabilities.posting) {
                TextButton(onClick = onReply) {
                    Text(stringResource(MR.strings.leaf_novel_comments_reply))
                }
            }

            Spacer(Modifier.weight(1f))

            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = MaterialSymbols.Rounded.MoreVert,
                        contentDescription = null,
                        modifier = Modifier.size(COMPACT_ICON),
                    )
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    // Only worth offering where there is something under it to focus on.
                    if (comment.replies.isNotEmpty() || comment.replyCount > 0) {
                        DropdownMenuItem(
                            text = { Text(stringResource(MR.strings.leaf_novel_comments_focus)) },
                            onClick = {
                                menuExpanded = false
                                onFocus()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(MR.strings.action_copy_to_clipboard)) },
                        onClick = {
                            menuExpanded = false
                            val text = NovelCommentMarkup.plainText(comment.body)
                            context.copyToClipboard(comment.author, text)
                        },
                    )
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
    }
}

/**
 * Who said it and when, plus the two flags that change how it is read.
 *
 * The whole line is the collapse control, which is Reddit's own arrangement: a wide, obvious target
 * that is exactly the thing being folded, and no extra chevron competing with the text.
 */
@Composable
private fun Byline(
    comment: NovelComment,
    collapsed: Boolean,
    hiddenCount: Int,
    showAvatar: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = MaterialTheme.padding.extraSmall),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
    ) {
        if (showAvatar) {
            // A site hands out avatar URLs that 404 — for a deleted account, usually — and a failed
            // load draws nothing while still taking its width, which leaves the byline indented past
            // its own comment and reads as a broken thread rail. A URL that does not resolve is the
            // same thing as no URL, so it gets the same silhouette.
            var failed by remember(comment.avatarUrl) { mutableStateOf(false) }
            if (comment.avatarUrl != null && !failed) {
                AsyncImage(
                    model = comment.avatarUrl,
                    contentDescription = null,
                    onError = { failed = true },
                    modifier = Modifier
                        .size(AVATAR_SIZE)
                        .clip(CircleShape),
                )
            } else {
                Icon(
                    imageVector = MaterialSymbols.Rounded.Person,
                    contentDescription = null,
                    modifier = Modifier
                        .size(AVATAR_SIZE)
                        .secondaryItemAlpha(),
                )
            }
        }

        Text(
            text = comment.author,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (comment.byUploader) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )

        if (comment.byUploader) {
            Chip(stringResource(MR.strings.leaf_novel_comments_uploader))
        }
        comment.badge?.takeIf { it.isNotBlank() }?.let { Chip(it) }

        if (comment.pinned) {
            Icon(
                imageVector = MaterialSymbols.Rounded.PushPin,
                contentDescription = stringResource(MR.strings.leaf_novel_comments_pinned),
                modifier = Modifier
                    .size(COMPACT_ICON)
                    .secondaryItemAlpha(),
            )
        }

        // Zero means the site gave no date, not the epoch — the reader is told nothing rather than
        // being told it was posted in 1970.
        if (comment.postedAt > 0L) {
            Text(
                text = relativeTimeSpanString(comment.postedAt),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.secondaryItemAlpha(),
            )
        }

        if (collapsed && hiddenCount > 0) {
            Spacer(Modifier.weight(1f))
            Chip(stringResource(MR.strings.leaf_novel_comments_hidden, hiddenCount))
        }
    }
}

/**
 * The score and, where the site takes them, the votes.
 *
 * One heart where the site has only a like and two arrows where it has both, because a permanently
 * disabled downvote is a promise the site never made.
 */
@Composable
private fun Votes(
    comment: NovelComment,
    capabilities: NovelCommentCapabilities,
    onVote: (NovelCommentVote) -> Unit,
) {
    val voted = comment.vote
    val active = MaterialTheme.colorScheme.primary
    val idle = MaterialTheme.colorScheme.onSurfaceVariant

    Row(verticalAlignment = Alignment.CenterVertically) {
        if (capabilities.voting) {
            IconButton(onClick = { onVote(NovelCommentVote.UP) }) {
                Icon(
                    imageVector = if (capabilities.downvotes) {
                        MaterialSymbols.Rounded.ArrowUpward
                    } else {
                        MaterialSymbols.Rounded.Favorite
                    },
                    contentDescription = stringResource(
                        if (capabilities.downvotes) {
                            MR.strings.leaf_novel_comments_upvote
                        } else {
                            MR.strings.leaf_novel_comments_like
                        },
                    ),
                    tint = if (voted == NovelCommentVote.UP) active else idle,
                    modifier = Modifier.size(COMPACT_ICON),
                )
            }
        }

        // Null is the site having no score for this comment, which is not the same as a score of
        // zero — and drawing one as the other invents a number the site never gave.
        val score = comment.score
        if (capabilities.scored && score != null) {
            Text(
                text = score.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = when (voted) {
                    NovelCommentVote.UP -> active
                    NovelCommentVote.DOWN -> MaterialTheme.colorScheme.error
                    NovelCommentVote.NONE -> idle
                },
            )
        }

        if (capabilities.voting && capabilities.downvotes) {
            IconButton(onClick = { onVote(NovelCommentVote.DOWN) }) {
                Icon(
                    imageVector = MaterialSymbols.Rounded.ArrowDownward,
                    contentDescription = stringResource(MR.strings.leaf_novel_comments_downvote),
                    tint = if (voted == NovelCommentVote.DOWN) MaterialTheme.colorScheme.error else idle,
                    modifier = Modifier.size(COMPACT_ICON),
                )
            }
        }
    }
}

/** What stands in for a hidden comment. Deliberately the size of a line, not of the comment. */
@Composable
private fun SpoilerCurtain(onReveal: () -> Unit) {
    Text(
        text = stringResource(MR.strings.leaf_novel_comments_spoiler),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(SPOILER_CORNER))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onReveal)
            .padding(MaterialTheme.padding.small),
    )
}

/** A small flat label. Material has no chip this size and a `Chip` here would be a button. */
@Composable
private fun Chip(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier
            .clip(RoundedCornerShape(CHIP_CORNER))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = CHIP_PADDING, vertical = 1.dp),
    )
}

/**
 * One vertical line per ancestor, tinted by depth and tappable to fold that ancestor.
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
        ancestors.forEachIndexed { index, id ->
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
                        .background(railColor(index)),
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

/** Five tints, cycled. Enough that neighbouring depths differ; few enough to stay a palette. */
@Composable
private fun railColor(depth: Int): Color {
    val scheme = MaterialTheme.colorScheme
    val palette = listOf(scheme.primary, scheme.tertiary, scheme.secondary, scheme.error, scheme.outline)
    return palette[depth % palette.size].copy(alpha = RAIL_ALPHA)
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

private val AVATAR_SIZE = 20.dp
private val COMPACT_ICON = 18.dp
private val RAIL_SPACING = 10.dp
private val RAIL_WIDTH = 2.dp
private val CHIP_CORNER = 4.dp
private val CHIP_PADDING = 4.dp
private val SPOILER_CORNER = 4.dp
private const val RAIL_ALPHA = 0.45f
