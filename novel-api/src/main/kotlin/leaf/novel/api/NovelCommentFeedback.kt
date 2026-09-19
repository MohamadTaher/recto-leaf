package leaf.novel.api

/**
 * Optional presentation metadata, implemented alongside [NovelCommentSource].
 *
 * Kept separate so installed extensions retain the same constructors and copy methods. Read from
 * the source's cached comment response; this method must not make network requests. Reactions are
 * display-only: voting continues through [NovelCommentSource.voteComment], and other actions can
 * be performed using the comment's permalink.
 */
interface NovelCommentFeedbackSource {
    fun getCommentFeedback(comment: NovelComment): NovelCommentFeedback
}

data class NovelCommentFeedback(
    val positiveVote: NovelCommentPositiveVote = NovelCommentPositiveVote.LIKE,
    val rating: NovelCommentRating? = null,
    val reactions: List<NovelCommentReaction> = emptyList(),
)

enum class NovelCommentPositiveVote {
    UPVOTE,
    LIKE,
    HEART,
    STAR,
}

/** A review rating, distinct from votes on whether the review is useful. */
data class NovelCommentRating(val value: Double, val maximum: Double = 5.0)

/** The site's label and optional emoji, with an unknown count kept distinct from zero. */
data class NovelCommentReaction(
    val label: String,
    val count: Int? = null,
    val emoji: String? = null,
    val selected: Boolean = false,
)
