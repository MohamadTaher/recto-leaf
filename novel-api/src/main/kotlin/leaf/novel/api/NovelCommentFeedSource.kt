package leaf.novel.api

/** Optional separate discussions and reviews. Existing comment sources keep their single feed. */
interface NovelCommentFeedSource : NovelCommentSource {
    val commentFeeds: List<NovelCommentFeed>

    suspend fun getComments(request: NovelCommentRequest, feed: NovelCommentFeed): NovelCommentPage
}

data class NovelCommentFeed(
    val key: String,
    val label: String,
    val capabilities: NovelCommentCapabilities,
)
