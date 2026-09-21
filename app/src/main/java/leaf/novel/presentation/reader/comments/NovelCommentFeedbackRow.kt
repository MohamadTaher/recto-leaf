package leaf.novel.presentation.reader.comments

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun NovelCommentRatingRow(rating: NovelCommentRating, modifier: Modifier = Modifier) {
    if (!rating.value.isFinite() || !rating.maximum.isFinite() || rating.maximum <= 0 ||
        rating.value !in 0.0..rating.maximum
    ) {
        return
    }
    val value = rating.value.toString().removeSuffix(".0")
    val maximum = rating.maximum.toString().removeSuffix(".0")
    val description = stringResource(MR.strings.leaf_novel_comments_rating, value, maximum)
    Row(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = description
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        repeat(5) { index ->
            val fraction = (rating.value / rating.maximum * 5 - index).toFloat().coerceIn(0f, 1f)
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
            if (rating.maximum == 5.0) value else "$value / $maximum",
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
            Icon(
                NovelCommentGlyphs.Like,
                null,
                tint = colour,
                // The same thumb the vote row uses, turned over for a verdict against.
                modifier = Modifier.size(12.dp)
                    .then(
                        if (reaction.sentiment ==
                            NovelCommentSentiment.NEGATIVE
                        ) {
                            Modifier.rotate(180f)
                        } else {
                            Modifier
                        },
                    ),
            )
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = colour, maxLines = 1)
    }
}

/** Green reads as approval in both themes only if it is darkened for light backgrounds. */
private val POSITIVE_LIGHT = Color(0xFF1B873F)
private val POSITIVE_DARK = Color(0xFF6FD08C)

/** One compact, consistent pair. Site votes use the same thumbs as likes and dislikes. */
@Composable
fun NovelCommentFeedbackRow(
    comment: NovelComment,
    feedback: NovelCommentFeedback,
    capabilities: NovelCommentCapabilities,
    voting: Boolean,
    onVote: (NovelCommentVote) -> Unit,
) {
    val likes = feedback.likes ?: comment.score.takeIf { capabilities.scored && !capabilities.downvotes }
    val dislikes = feedback.dislikes
    Row(verticalAlignment = Alignment.CenterVertically) {
        listOf(NovelCommentVote.UP to likes, NovelCommentVote.DOWN to dislikes).forEach { (vote, count) ->
            if (vote == NovelCommentVote.DOWN && !capabilities.downvotes && count == null) return@forEach
            if (count == null && !capabilities.voting) return@forEach
            val label = stringResource(
                if (vote ==
                    NovelCommentVote.UP
                ) {
                    MR.strings.leaf_novel_comments_like
                } else {
                    MR.strings.leaf_novel_comments_dislike
                },
            )
            val description = if (count != null) "$label: $count" else label
            val active = comment.vote == vote
            val content: @Composable () -> Unit = {
                Icon(
                    NovelCommentGlyphs.Like,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp).rotate(if (vote == NovelCommentVote.DOWN) 180f else 0f),
                )
                if (count != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(count.toString(), style = MaterialTheme.typography.labelMedium)
                }
            }
            if (capabilities.voting) {
                TextButton(
                    onClick = { onVote(vote) },
                    enabled = !voting,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = if (active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    ),
                    modifier = Modifier.semantics {
                        contentDescription = description
                        selected = active
                    },
                ) { content() }
            } else {
                Row(
                    modifier = Modifier.padding(end = 20.dp, top = 10.dp, bottom = 10.dp)
                        .semantics(mergeDescendants = true) { contentDescription = description },
                    verticalAlignment = Alignment.CenterVertically,
                ) { content() }
            }
        }
        // Old extensions may supply only a net total. Keep it without inventing either count.
        if (likes == null && dislikes == null && capabilities.scored && comment.score != null) {
            Text(comment.score.toString(), style = MaterialTheme.typography.labelMedium)
        }
    }
}
