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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import coil3.compose.AsyncImage
import eu.kanade.presentation.util.relativeTimeSpanString
import eu.kanade.tachiyomi.util.system.copyToClipboard
import leaf.novel.api.NovelComment
import leaf.novel.api.NovelCommentCapabilities
import leaf.novel.api.NovelCommentFeedback
import leaf.novel.api.NovelCommentSentiment
import leaf.novel.api.NovelCommentVote
import leaf.novel.ui.reader.comments.NovelCommentBlock
import leaf.novel.ui.reader.comments.NovelCommentMarkup
import leaf.novel.ui.reader.comments.NovelCommentReview
import leaf.novel.ui.reader.comments.NovelCommentSpan
import leaf.novel.ui.reader.comments.NovelCommentTree
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ArrowForward
import mihon.icons.materialsymbols.automirroredrounded.OpenInNew
import mihon.icons.materialsymbols.rounded.ContentCopy
import mihon.icons.materialsymbols.rounded.ExpandLess
import mihon.icons.materialsymbols.rounded.ExpandMore
import mihon.icons.materialsymbols.rounded.MoreVert
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * One comment: a header row, the body beneath it, and the line that ties it to its replies — the
 * same shape whichever extension the comment came from.
 *
 * The header is one row, everything in it centred on the avatar: the name over the date on the
 * left, the rating over the extension on the right (the extension alone, in the rating's place,
 * when there is no rating), and the menu at the end. Whatever a site adds of its own to a user — a
 * tier, a title, a tagline — is left out, because no two sites mean the same thing by one.
 *
 * A tap anywhere on the header folds the comment down to that header alone, the way Reddit does, and
 * another opens it; a tap on the avatar's picture opens the picture instead.
 *
 * A comment with replies draws a line down from its avatar, the way YouTube does. Closed, it turns
 * into the "replies" row at the foot of the comment; open, it runs on past every reply to the
 * [NovelCommentRepliesRow] that closes them, drawn by the rails of the rows between.
 */
@Composable
fun NovelCommentItem(
    comment: NovelComment,
    /** How many comments this one is nested under, which sets its avatar and its indent. */
    depth: Int,
    collapsed: Boolean,
    hiddenCount: Int,
    /** Whether the rule under a comment is drawn here, rather than under the rows that close its thread. */
    ruled: Boolean,
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
    /** The comment's page on its site, or its thread's when the source gives it none. */
    page: String?,
    onOpenPage: () -> Unit,
    /** Called when a long comment is closed again, so the sheet can bring its top back into view. */
    onShrink: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var expanded by rememberSaveable(comment.id, comment.body) { mutableStateOf(false) }
    val toggleBody = {
        expanded = !expanded
        if (!expanded) onShrink()
    }
    val review = remember(comment.body) { NovelCommentReview.parse(comment.body) }
    val spans = remember(review.body, page) { NovelCommentMarkup.parse(review.body, page) }
    val hasSpoilers = remember(spans) { spans.any { it.spoiler } }
    val blocks = remember(spans) { NovelCommentMarkup.blocks(spans) }
    // Per text block, as last laid out closed: its line count, and whether it ran past its share.
    val layouts = remember(comment.body) { mutableStateMapOf<Int, Pair<Int, Boolean>>() }
    var revealed by remember(comment.id) { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var viewingAvatar by remember { mutableStateOf<String?>(null) }
    val hidden = (spoilerGuard || hasSpoilers) && !revealed
    val rating = (feedback.rating ?: review.rating).takeUnless { comment.deleted || hidden }
    // A site that judges a review rather than scoring it puts its verdict where the stars would be.
    // Only one, and only a reaction that took a side: the rest stay at the foot of the comment.
    val verdict = feedback.reactions
        .firstOrNull { it.sentiment != NovelCommentSentiment.NEUTRAL }
        .takeUnless { comment.deleted || hidden || rating != null }
    val avatar = avatarSize(depth)
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    val replyCount = maxOf(
        NovelCommentTree.count(comment.replies),
        if (capabilities.lazyReplies) comment.replyCount else 0,
    )
    // The thread line: down from the avatar to the replies row, or on into the replies when open.
    val threaded = !collapsed && replyCount > 0
    val continues = threaded && repliesExpanded

    Column(
        modifier = modifier.fillMaxWidth()
            // A rule under the comment, except where its thread line carries on below it.
            .rule(ruled && !continues)
            .padding(top = 10.dp, bottom = if (collapsed) 10.dp else 0.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(HEADER_HEIGHT).clickable(onClick = onToggleCollapsed),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.width(avatar).fillMaxHeight()
                    .drawBehind {
                        if (threaded) {
                            val x = size.width / 2
                            drawLine(
                                lineColor,
                                Offset(x, (size.height + avatar.toPx()) / 2),
                                Offset(x, size.height),
                                LINE.toPx(),
                            )
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                CommentAvatar(
                    comment,
                    avatar,
                    showImage = showAvatar && capabilities.avatars,
                    onOpen = { viewingAvatar = it },
                )
            }
            Spacer(Modifier.width(GAP))
            val details = listOfNotNull(
                comment.postedAt.takeIf { it > 0 }?.let { relativeTimeSpanString(it) },
                comment.chapterLabel?.takeIf { showChapter && it.isNotBlank() },
                stringResource(MR.strings.leaf_novel_comments_pinned).takeIf { comment.pinned },
                "+$hiddenCount".takeIf { collapsed && hiddenCount > 0 },
            )
            val source: (@Composable () -> Unit)? = sourceName?.let { { HeaderLabel(it) } }
            HeaderGrid(
                modifier = Modifier.weight(1f),
                topStart = {
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
                },
                // The rating's place: the stars, else the site's verdict, else the extension.
                topEnd = rating?.let { { NovelCommentRatingRow(it) } }
                    ?: verdict?.let { { NovelCommentReactionRow(it) } }
                    ?: source,
                bottomStart = details.takeIf { it.isNotEmpty() }?.let { { HeaderLabel(it.joinToString(" · ")) } },
                bottomEnd = source.takeIf { rating != null || verdict != null },
            )
            Box {
                IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        MaterialSymbols.Rounded.MoreVert,
                        stringResource(MR.strings.action_menu_overflow_description),
                        modifier = Modifier.size(22.dp),
                    )
                }
                // A narrow column of small icons: the platform menu rather than the app's, which is
                // fixed at the width of a menu of words.
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    shape = RoundedCornerShape(20.dp),
                ) {
                    @Composable
                    fun Action(title: String, icon: ImageVector, onClick: () -> Unit) {
                        IconButton(
                            onClick = {
                                menuExpanded = false
                                onClick()
                            },
                            modifier = Modifier.padding(horizontal = 4.dp).size(MENU_BUTTON),
                        ) {
                            Icon(
                                icon,
                                title,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(MENU_ICON),
                            )
                        }
                    }
                    if (collapsed) {
                        Action(
                            stringResource(MR.strings.leaf_novel_comments_expand),
                            MaterialSymbols.Rounded.ExpandMore,
                            onToggleCollapsed,
                        )
                    } else {
                        Action(
                            stringResource(MR.strings.leaf_novel_comments_collapse),
                            MaterialSymbols.Rounded.ExpandLess,
                            onToggleCollapsed,
                        )
                    }
                    if (comment.replies.isNotEmpty() || comment.replyCount > 0) {
                        Action(
                            stringResource(MR.strings.leaf_novel_comments_focus),
                            MaterialSymbols.AutoMirroredRounded.ArrowForward,
                            onFocus,
                        )
                    }
                    if (!comment.deleted) {
                        Action(
                            stringResource(MR.strings.action_copy_to_clipboard),
                            MaterialSymbols.Rounded.ContentCopy,
                        ) {
                            context.copyToClipboard(comment.author, NovelCommentMarkup.plainText(comment.body))
                        }
                    }
                    if (page != null) {
                        Action(
                            stringResource(MR.strings.action_open_in_web_view),
                            MaterialSymbols.AutoMirroredRounded.OpenInNew,
                            onOpenPage,
                        )
                    }
                }
            }
        }

        viewingAvatar?.let { url ->
            NovelCommentAvatarDialog(url, comment.author, onDismissRequest = { viewingAvatar = null })
        }
        // Folded, the header is all there is: no body, no votes and no replies row beneath it.
        if (collapsed) return@Column

        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(
                modifier = Modifier.width(avatar).fillMaxHeight()
                    .then(if (threaded) Modifier.clickable(onClick = onToggleReplies) else Modifier)
                    .drawBehind {
                        if (threaded) {
                            val x = size.width / 2
                            drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), LINE.toPx())
                        }
                    },
            )
            Spacer(Modifier.width(GAP))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 2.dp, bottom = if (threaded && !continues) 0.dp else 6.dp),
            ) {
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
                        // Closed, a long comment shows its first lines, and the pictures among them,
                        // until those lines run out; each block is given what the ones above it left.
                        val shown = buildList {
                            var budget = if (expanded) Int.MAX_VALUE else COLLAPSED_LINES
                            blocks.forEachIndexed { index, block ->
                                if (budget <= 0) return@buildList
                                add(IndexedValue(index, budget))
                                if (block is NovelCommentBlock.Text && !expanded) {
                                    budget -= layouts[index]?.first ?: budget
                                }
                            }
                        }
                        // Cut short only once the text above the cut has been measured, so the label
                        // does not flash for a frame on every comment with a picture below its words.
                        val measured = shown.all { blocks[it.index] !is NovelCommentBlock.Text || it.index in layouts }
                        val long = (measured && shown.size < blocks.size) ||
                            shown.any { layouts[it.index]?.second == true }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            shown.forEach { (index, maxLines) ->
                                when (val block = blocks[index]) {
                                    is NovelCommentBlock.Media -> NovelCommentImages(block.urls, page)
                                    // The text itself is the control: a tap opens a long comment,
                                    // and another closes it. The label below only says which a tap
                                    // will do.
                                    is NovelCommentBlock.Text -> Text(
                                        text = block.spans.toAnnotatedString(
                                            MaterialTheme.colorScheme.primary,
                                            MaterialTheme.colorScheme.onSurfaceVariant,
                                        ),
                                        // bodyLarge's own leading is set for paragraphs of prose; a
                                        // comment is a few lines, and reads as one block with the
                                        // lines closer together.
                                        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 1.3.em),
                                        maxLines = maxLines,
                                        overflow = TextOverflow.Ellipsis,
                                        onTextLayout = {
                                            val layout = it.lineCount to it.hasVisualOverflow
                                            if (!expanded && layouts[index] != layout) layouts[index] = layout
                                        },
                                        modifier = Modifier.clickable(
                                            enabled = expanded || long,
                                            interactionSource = null,
                                            indication = null,
                                            onClick = toggleBody,
                                        ),
                                    )
                                }
                            }
                        }
                        if (expanded || long) {
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
                                modifier = Modifier
                                    .clickable(role = Role.Button, onClick = toggleBody)
                                    .padding(vertical = 4.dp),
                            )
                        }
                    }
                }
                if (!comment.deleted) {
                    NovelCommentFeedbackRow(comment, feedback, capabilities, voting, onVote)
                    // Whatever was promoted to the header is not repeated down here.
                    val remaining = feedback.reactions.filter { it !== verdict }
                    if (remaining.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            remaining.forEach { reaction ->
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

        if (threaded && !continues) {
            val replies = if (replyCount == 1) {
                MR.strings.leaf_novel_comments_one_reply
            } else {
                MR.strings.leaf_novel_comments_replies
            }
            NovelCommentRepliesRow(
                depth = depth,
                label = stringResource(replies, replyCount),
                open = false,
                loading = loadingReplies && comment.replies.isEmpty(),
                onClick = onToggleReplies,
            )
        }
    }
}

/**
 * The row a thread line turns into: "3 replies" under a closed comment, "Collapse" under the last of
 * an open one's replies.
 *
 * The whole row is the button, not just its words. The line comes down the avatar's centre and
 * bends into the label, so the row reads as the end of the thread it belongs to.
 */
@Composable
fun NovelCommentRepliesRow(
    /** The depth of the comment whose replies these are, which places the line under its avatar. */
    depth: Int,
    label: String,
    open: Boolean,
    loading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Whether this row ends a thread, and so carries the rule the comments above it left out. */
    ruled: Boolean = false,
) {
    val avatar = avatarSize(depth)
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    Row(
        // Shorter than a touch row and padded below instead, so the label sits as far under the
        // votes as the rule sits under it.
        modifier = modifier
            .rule(ruled)
            .padding(bottom = REPLIES_ROW_GAP)
            .fillMaxWidth()
            .height(REPLIES_ROW_HEIGHT)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.width(avatar).fillMaxHeight().drawBehind {
                val x = size.width / 2
                val y = size.height / 2
                val radius = ELBOW.toPx()
                val stroke = Stroke(width = LINE.toPx())
                drawLine(lineColor, Offset(x, 0f), Offset(x, y - radius), LINE.toPx())
                drawArc(
                    color = lineColor,
                    startAngle = 180f,
                    sweepAngle = -90f,
                    useCenter = false,
                    topLeft = Offset(x, y - 2 * radius),
                    size = Size(2 * radius, 2 * radius),
                    style = stroke,
                )
                drawLine(
                    lineColor,
                    Offset(x + radius, y),
                    Offset(size.width + GAP.toPx() - 4.dp.toPx(), y),
                    LINE.toPx(),
                )
            },
        )
        Spacer(Modifier.width(GAP))
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(4.dp))
        if (loading) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
        } else {
            Icon(
                MaterialSymbols.Rounded.ExpandMore,
                null,
                modifier = Modifier.size(18.dp).rotate(if (open) 180f else -90f),
            )
        }
    }
}

/**
 * The header beside the avatar, as two rows: the name with the rating across the top, the date with
 * the extension across the bottom.
 *
 * Rows rather than two side-by-side columns, because what has to line up is across: the date and
 * the extension are one line and are centred on it together, whatever either side is set in. Each
 * row takes half the height; with nothing for the bottom row, the top one takes the whole height
 * and sits level with the avatar.
 */
@Composable
private fun HeaderGrid(
    topStart: @Composable () -> Unit,
    topEnd: (@Composable () -> Unit)?,
    bottomStart: (@Composable () -> Unit)?,
    bottomEnd: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val twoRows = bottomStart != null || bottomEnd != null
    Column(modifier = modifier.fillMaxHeight()) {
        HeaderRow(topStart, topEnd, Modifier.weight(1f))
        if (twoRows) HeaderRow(bottomStart, bottomEnd, Modifier.weight(1f))
    }
}

@Composable
private fun HeaderRow(
    start: (@Composable () -> Unit)?,
    end: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.weight(1f)) { start?.invoke() }
        if (end != null) {
            Spacer(Modifier.width(8.dp))
            end()
        }
    }
}

@Composable
private fun HeaderLabel(text: String) {
    Text(
        text = text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The avatar, or its stand-in: every comment has one, so that every thread line has somewhere to
 * start. The picture itself only where the reader wants pictures and the site has real ones.
 */
@Composable
private fun CommentAvatar(comment: NovelComment, size: Dp, showImage: Boolean, onOpen: (String) -> Unit) {
    var failed by remember(comment.avatarUrl) { mutableStateOf(false) }
    val url = comment.avatarUrl?.takeIf { showImage && !comment.deleted && !failed }
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        // The initial sits *under* the picture rather than instead of it. A portrait that is still
        // arriving — or that never arrives and never says so — then leaves a letter rather than a
        // hole, which is what an empty circle in a thread actually looked like.
        if (!comment.deleted) {
            Text(
                comment.author.take(1).uppercase(),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                style = if (size < 32.dp) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelLarge,
            )
        }
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                // Avatars are not all square, and fitting one into a circle leaves bare edges.
                contentScale = ContentScale.Crop,
                onError = { failed = true },
                modifier = Modifier.matchParentSize().clickable { onOpen(url) },
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
 * One thread line per ancestor, each under that ancestor's avatar and tappable to close its replies.
 *
 * Each column is exactly as wide as its ancestor's avatar and the gap after it, so the line drawn
 * here continues the one drawn under the avatar itself, and a reply's own avatar starts where its
 * parent's text did.
 */
@Composable
private fun NovelCommentRails(
    ancestors: List<String>,
    onCollapse: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    Row(modifier = modifier.fillMaxHeight()) {
        ancestors.forEachIndexed { level, id ->
            val avatar = avatarSize(level)
            Box(
                modifier = Modifier
                    .width(avatar + GAP)
                    .fillMaxHeight()
                    .clickable { onCollapse(id) }
                    .drawBehind {
                        val x = avatar.toPx() / 2
                        drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), LINE.toPx())
                    },
            )
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
 * The rule between comments: along the bottom edge, fading in from nothing at either end to full by
 * the last 18% of the width.
 */
@Composable
private fun Modifier.rule(visible: Boolean): Modifier {
    if (!visible) return this
    val color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = DIVIDER_ALPHA)
    return drawBehind {
        drawLine(
            brush = Brush.horizontalGradient(
                0f to Color.Transparent,
                DIVIDER_FADE to color,
                1f - DIVIDER_FADE to color,
                1f to Color.Transparent,
            ),
            start = Offset(0f, size.height),
            end = Offset(size.width, size.height),
            strokeWidth = 1.dp.toPx(),
        )
    }
}

/** A top-level comment's avatar, and the smaller one every reply gets, as YouTube does. */
private fun avatarSize(depth: Int): Dp = if (depth == 0) 32.dp else 24.dp

private val GAP = 10.dp

/** How many lines of text a closed comment shows. */
private const val COLLAPSED_LINES = 4
private val HEADER_HEIGHT = 40.dp
private val REPLIES_ROW_HEIGHT = 32.dp
private val REPLIES_ROW_GAP = 8.dp
private val LINE = 1.5.dp
private val ELBOW = 10.dp
private val MENU_BUTTON = 32.dp
private val MENU_ICON = 16.dp
private const val DIVIDER_ALPHA = 0.7f
private const val DIVIDER_FADE = 0.18f

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
                fontSize = if (span.heading) 1.15.em else TextUnit.Unspecified,
                fontWeight = if (span.bold || span.heading) FontWeight.Bold else null,
                fontStyle = if (span.italic || span.quote) FontStyle.Italic else null,
                fontFamily = if (span.code) FontFamily.Monospace else null,
                textDecoration = listOfNotNull(
                    TextDecoration.LineThrough.takeIf { span.strikethrough },
                    TextDecoration.Underline.takeIf { span.underline },
                ).takeIf { it.isNotEmpty() }?.let(TextDecoration::combine),
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
