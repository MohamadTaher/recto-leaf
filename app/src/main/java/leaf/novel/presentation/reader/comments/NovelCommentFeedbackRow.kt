package leaf.novel.presentation.reader.comments

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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import leaf.novel.api.NovelComment
import leaf.novel.api.NovelCommentCapabilities
import leaf.novel.api.NovelCommentFeedback
import leaf.novel.api.NovelCommentRating
import leaf.novel.api.NovelCommentVote
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun NovelCommentRatingRow(rating: NovelCommentRating) {
    if (!rating.value.isFinite() || !rating.maximum.isFinite() || rating.maximum <= 0 ||
        rating.value !in 0.0..rating.maximum
    ) {
        return
    }
    val value = rating.value.toString().removeSuffix(".0")
    val maximum = rating.maximum.toString().removeSuffix(".0")
    val description = stringResource(MR.strings.leaf_novel_comments_rating, value, maximum)
    Row(
        modifier = Modifier.padding(vertical = 4.dp).semantics(mergeDescendants = true) {
            contentDescription = description
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        repeat(5) { index ->
            val fraction = (rating.value / rating.maximum * 5 - index).toFloat().coerceIn(0f, 1f)
            Box(Modifier.size(16.dp)) {
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
        Text("$value / $maximum", style = MaterialTheme.typography.labelMedium)
    }
}

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
