package leaf.novel.presentation.reader.comments

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import leaf.novel.api.NovelComment
import leaf.novel.api.NovelCommentCapabilities
import leaf.novel.api.NovelCommentFeedback
import leaf.novel.api.NovelCommentRating
import leaf.novel.api.NovelCommentReaction
import leaf.novel.api.NovelCommentSentiment
import leaf.novel.api.NovelCommentVote
import leaf.novel.ui.reader.comments.outOfFive
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/** Always on five stars, whatever scale the site rates on, so every source reads the same. */
@Composable
fun NovelCommentRatingRow(rating: NovelCommentRating, modifier: Modifier = Modifier) {
    if (!rating.value.isFinite() || !rating.maximum.isFinite() || rating.maximum <= 0 ||
        rating.value !in 0.0..rating.maximum
    ) {
        return
    }
    val stars = rating.outOfFive().value
    val value = stars.toString().removeSuffix(".0")
    val description = stringResource(MR.strings.leaf_novel_comments_rating, value, "5")
    Row(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = description
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        repeat(5) { index ->
            val fraction = (stars - index).toFloat().coerceIn(0f, 1f)
            Box(Modifier.size(12.dp)) {
                Icon(NovelCommentGlyphs.Star, null, tint = MaterialTheme.colorScheme.outlineVariant)
                Icon(
                    NovelCommentGlyphs.FilledStar,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.drawWithContent {
                        clipRect(right = size.width * fraction) { this@drawWithContent.drawContent() }
                    },
                )
            }
        }
        Spacer(Modifier.width(4.dp))
        Text(
            value,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

/**
 * A verdict in the rating's place.
 *
 * Some sites do not score a review at all; they ask only whether the reader recommends the novel,
 * and the star slot would otherwise sit empty. The colour comes from the source's own
 * [NovelCommentSentiment] rather than from reading the label, so a site whose words are
 * "Recommended" and a site whose words are something else both land the same way.
 */
@Composable
fun NovelCommentReactionRow(reaction: NovelCommentReaction, modifier: Modifier = Modifier) {
    val colour = when (reaction.sentiment) {
        NovelCommentSentiment.POSITIVE -> if (isSystemInDarkTheme()) POSITIVE_DARK else POSITIVE_LIGHT
        NovelCommentSentiment.NEGATIVE -> MaterialTheme.colorScheme.error
        NovelCommentSentiment.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val label = listOfNotNull(reaction.label, reaction.count?.toString()).joinToString(" ")
    Row(
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        val emoji = reaction.emoji
        if (emoji != null) {
            Text(emoji, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        } else if (reaction.sentiment != NovelCommentSentiment.NEUTRAL) {
            val against = reaction.sentiment == NovelCommentSentiment.NEGATIVE
            Icon(
                NovelCommentGlyphs.Like,
                null,
                tint = colour,
                // The same thumb the vote row uses, turned over for a verdict against.
                modifier = Modifier.size(12.dp).rotate(if (against) 180f else 0f),
            )
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = colour, maxLines = 1)
    }
}

private val VOTE_PADDING = 8.dp
private val VOTE_HEIGHT = 32.dp

/** Green reads as approval in both themes only if it is darkened for light backgrounds. */
private val POSITIVE_LIGHT = Color(0xFF1B873F)
private val POSITIVE_DARK = Color(0xFF6FD08C)

/**
 * One compact, consistent pair. Site votes use the same thumbs as likes and dislikes.
 *
 * Where the source cannot vote, the thumbs still answer a tap the way a site's do — the count moves
 * and the thumb lights — but the vote stays in this sheet and nothing is sent anywhere.
 */
@Composable
fun NovelCommentFeedbackRow(
    comment: NovelComment,
    feedback: NovelCommentFeedback,
    capabilities: NovelCommentCapabilities,
    voting: Boolean,
    onVote: (NovelCommentVote) -> Unit,
) {
    var localVote by rememberSaveable(comment.id) { mutableStateOf(NovelCommentVote.NONE) }
    val chosen = if (capabilities.voting) comment.vote else localVote
    val likes = feedback.likes ?: comment.score.takeIf { capabilities.scored && !capabilities.downvotes }
    val dislikes = feedback.dislikes
    // Pulled back by the buttons' own padding, so the first thumb lines up with the text above it.
    Row(modifier = Modifier.offset(x = -VOTE_PADDING), verticalAlignment = Alignment.CenterVertically) {
        listOf(NovelCommentVote.UP to likes, NovelCommentVote.DOWN to dislikes).forEach { (vote, siteCount) ->
            if (vote == NovelCommentVote.DOWN && !capabilities.downvotes && siteCount == null) return@forEach
            if (siteCount == null && !capabilities.voting) return@forEach
            val count = siteCount?.plus(if (!capabilities.voting && localVote == vote) 1 else 0)
            val label = when (vote) {
                NovelCommentVote.UP -> stringResource(MR.strings.leaf_novel_comments_like)
                else -> stringResource(MR.strings.leaf_novel_comments_dislike)
            }
            val description = if (count != null) "$label: $count" else label
            val active = chosen == vote
            TextButton(
                onClick = {
                    if (capabilities.voting) {
                        onVote(vote)
                    } else {
                        localVote = if (localVote == vote) NovelCommentVote.NONE else vote
                    }
                },
                enabled = !voting,
                contentPadding = PaddingValues(horizontal = VOTE_PADDING),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = if (active) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                ),
                // Shorter than a button's usual 40dp, so the row does not open a gap under the text.
                modifier = Modifier.height(VOTE_HEIGHT).semantics {
                    contentDescription = description
                    selected = active
                },
            ) {
                Icon(
                    if (active) NovelCommentGlyphs.FilledLike else NovelCommentGlyphs.Like,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp).rotate(if (vote == NovelCommentVote.DOWN) 180f else 0f),
                )
                if (count != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(count.toString(), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        // Old extensions may supply only a net total. Keep it without inventing either count.
        if (likes == null && dislikes == null && capabilities.scored && comment.score != null) {
            Text(comment.score.toString(), style = MaterialTheme.typography.labelMedium)
        }
    }
}
