package leaf.novel.ui.reader.comments

import leaf.novel.api.NovelCommentRating

/** Recognizes the review header emitted by existing extensions without changing their ABI. */
data class NovelCommentReview(val body: String, val rating: NovelCommentRating?) {
    companion object {
        private val header = Regex("^\\s*<b>★\\s*(\\d+(?:\\.\\d+)?)\\s*/\\s*(\\d+(?:\\.\\d+)?)</b><br\\s*/?>")

        fun parse(body: String): NovelCommentReview {
            val match = header.find(body) ?: return NovelCommentReview(body, null)
            val value = match.groupValues[1].toDoubleOrNull() ?: return NovelCommentReview(body, null)
            val maximum = match.groupValues[2].toDoubleOrNull() ?: return NovelCommentReview(body, null)
            if (!value.isFinite() || !maximum.isFinite() || maximum <= 0 || value !in 0.0..maximum) {
                return NovelCommentReview(body, null)
            }
            return NovelCommentReview(body.substring(match.range.last + 1), NovelCommentRating(value, maximum))
        }
    }
}
