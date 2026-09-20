package leaf.novel.api

/** Optional separate discussions and reviews. Existing comment sources keep their single feed. */
interface NovelCommentFeedSource : NovelCommentSource {
    val commentFeeds: List<NovelCommentFeed>

    suspend fun getComments(request: NovelCommentRequest, feed: NovelCommentFeed): NovelCommentPage
}

data class NovelCommentFeed(
    /**
     * Unique among this source's feeds, and [REVIEWS] for the one that holds reviews.
     *
     * The app files a feed as reviews by this key and by nothing else — not the label, not the
     * scope — so a review feed keyed anything else is silently a comment feed. It also separates
     * one feed's comments from another's, so a source whose feeds can return the same id twice
     * must make them distinct itself; see `docs/leaf/comments/PROVIDERS.md`.
     */
    val key: String,
    val label: String,
    val capabilities: NovelCommentCapabilities,
) {
    companion object {
        /**
         * The key a feed of reviews must use.
         *
         * A constant rather than a literal in every extension because the app's reviews filter
         * compares against exactly this, and a source that spells it `review` or `ratings` loses
         * the filter with nothing to say it did.
         */
        const val REVIEWS = "reviews"

        /** The feed a source that declares none is taken to have. Anything but [REVIEWS] is comments. */
        const val COMMENTS = "comments"
    }
}
