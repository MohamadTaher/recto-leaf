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
    /** Separate totals, never reconstructed from the net score. Null means not supplied. */
    val likes: Int? = null,
    val dislikes: Int? = null,
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
    /**
     * Whether this reaction is the site saying something good or something bad.
     *
     * Stated by the source rather than read off [label], because the label is the site's own word
     * and the app has no business deciding that "Recommended" is praise and "Mid" is not. A source
     * that sets anything but [NovelCommentSentiment.NEUTRAL] is drawn where a star rating would go,
     * in green or red, which is the whole reason this exists: a site that judges a review instead
     * of scoring it has nothing to put in that slot otherwise.
     */
    val sentiment: NovelCommentSentiment = NovelCommentSentiment.NEUTRAL,
)

/** What a reaction means, for a site whose verdict is a word rather than a number of stars. */
enum class NovelCommentSentiment {
    POSITIVE,
    NEUTRAL,
    NEGATIVE,
}
