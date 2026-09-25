package leaf.novel.api

/**
 * The comment contract, as something that can be run rather than read.
 *
 * Everything here is stated in `docs/leaf/comments/PROVIDERS.md`, and until now that was the only
 * place it was stated: a new extension was correct if its author had read the document and
 * remembered it, and wrong in ways that surface as an empty sheet or another novel's discussion
 * rather than as an error. These are the rules that can be checked without knowing anything about a
 * site, so they are checked.
 *
 * Deliberately not a test framework and not a set of assertions — it returns what it found, so an
 * extension's own unit test can fail on it and the app can log it while a new extension is being
 * written, without either depending on the other's way of reporting.
 *
 * It cannot tell whether a site's comments are the *right* comments; that is what the manual pass
 * in `PROVIDERS.md` is for. What it does catch is the shape being wrong, which is most of what goes
 * wrong.
 */
object NovelCommentConformance {

    /**
     * What a source says about itself, checked before a single request is made.
     *
     * The capabilities are the whole of the app's knowledge about a site, so a source that
     * describes itself wrongly gets a sheet built for a different site.
     */
    fun check(source: NovelCommentSource): List<String> = buildList {
        val feeds = (source as? NovelCommentFeedSource)?.commentFeeds.orEmpty()

        if (source.commentCapabilities.scopes.isEmpty() && feeds.isEmpty()) {
            add("declares no scopes and no feeds, so it is never asked for anything")
        }
        feeds.groupBy { it.key }.filterValues { it.size > 1 }.keys.forEach {
            add("declares more than one feed keyed \"$it\"; the app keeps feeds apart by key alone")
        }
        feeds.filter { it.key.isBlank() }.forEach {
            add("declares a feed labelled \"${it.label}\" with a blank key")
        }
        feeds.forEach { feed ->
            if (feed.label.isBlank()) add("feed \"${feed.key}\" has no label to show in the filter")
            addAll(check(feed.capabilities).map { "feed \"${feed.key}\" $it" })
        }
        addAll(check(source.commentCapabilities).map { "commentCapabilities $it" })

        if (feeds.none { it.key == NovelCommentFeed.REVIEWS }) {
            feeds.filter { it.key.contains("review") || it.key.contains("rating") }.forEach {
                add(
                    "feed \"${it.key}\" looks like reviews but is not keyed NovelCommentFeed.REVIEWS, " +
                        "so the sheet files it as comments",
                )
            }
        }
    }

    /** A capability set that contradicts itself, which produces a control that cannot work. */
    fun check(capabilities: NovelCommentCapabilities): List<String> = buildList {
        if (capabilities.scopes.isEmpty()) add("claims no scope at all")
        if (capabilities.maxDepth < 1) add("has maxDepth ${capabilities.maxDepth}; a flat list is 1")
        if (capabilities.lazyReplies && !capabilities.threaded) {
            add("claims lazyReplies with maxDepth 1, so there are no replies to load")
        }
        if (capabilities.downvotes && !capabilities.voting) {
            add("claims downvotes without voting, so neither arrow is drawn")
        }
        capabilities.sorts.groupBy { it.key }.filterValues { it.size > 1 }.keys.forEach {
            add("offers the sort key \"$it\" twice")
        }
        capabilities.sorts.filter { it.label.isBlank() }.forEach {
            add("offers the sort \"${it.key}\" with no label")
        }
    }

    /**
     * One real response, against what the feed that served it promised.
     *
     * The checks that need a site are here, and they are the ones worth running against a live
     * chapter: an id that cannot survive being merged with another source's, a reply nested deeper
     * than the source said its site goes, a field filled that the capabilities said would be empty.
     *
     * @param known the ids already in hand from earlier pages, so a page that repeats one is caught
     * where it happens rather than as a thread that silently stopped early.
     */
    fun check(
        page: NovelCommentPage,
        capabilities: NovelCommentCapabilities,
        known: Set<String> = emptySet(),
    ): List<String> = buildList {
        val seen = known.toMutableSet()

        fun walk(comments: List<NovelComment>, depth: Int) {
            comments.forEach { comment ->
                val where = "comment \"${comment.id}\""
                when {
                    comment.id.isBlank() -> add("a comment has a blank id")
                    !seen.add(comment.id) -> add("$where appears twice; ids must be unique within a feed")
                }
                // The app prefixes another source's ids with a control character to keep the merged
                // thread apart, and reads the prefix back off. An id carrying one is unroutable.
                if (comment.id.any { it.isISOControl() }) {
                    add("$where has a control character in its id, which the app uses to keep sources apart")
                }
                if (comment.body.isBlank() && !comment.deleted) add("$where has an empty body and is not deleted")
                if (comment.postedAt < 0) add("$where has a negative postedAt; zero is how a site says it has none")
                if (comment.replies.isNotEmpty() && comment.parentId != null) {
                    add("$where both nests its replies and sets parentId, which would double them")
                }
                if (comment.replyCount < comment.replies.size) {
                    add("$where reports ${comment.replyCount} replies but carries ${comment.replies.size}")
                }
                if (comment.score != null && !capabilities.scored) {
                    add("$where has a score although the feed is not scored, so it is never drawn")
                }
                if (comment.vote != NovelCommentVote.NONE && !capabilities.voting) {
                    add("$where reports a vote although the feed cannot vote")
                }
                if (comment.avatarUrl != null && !capabilities.avatars) {
                    add("$where has an avatar although the feed does not claim avatars")
                }
                if (depth > capabilities.maxDepth) {
                    add("$where is $depth deep, past the maxDepth of ${capabilities.maxDepth}")
                }
                walk(comment.replies, depth + 1)
            }
        }
        walk(page.comments, depth = 1)

        if (page.hasNextPage && !capabilities.paginated) {
            add("promises a next page although the feed is not paginated, so it is never asked for one")
        }
        if (page.nextCursor != null && !page.hasNextPage) {
            add("gives a cursor for a page it says is the last")
        }
        page.total?.let {
            if (it < page.comments.size) add("reports a total of $it but sent ${page.comments.size} on one page")
        }
    }

    /**
     * That [NovelCommentFeedbackSource.getCommentFeedback] is a lookup and not a second parse.
     *
     * The app reads it once per comment as a page lands and keeps the answer, so an implementation
     * that consumes what it returns, or answers differently the second time, loses whichever call
     * it did not win. Asking twice is the cheapest way to catch that.
     */
    fun check(source: NovelCommentFeedbackSource, comments: List<NovelComment>): List<String> = buildList {
        comments.forEach { comment ->
            val first = source.getCommentFeedback(comment)
            val second = source.getCommentFeedback(comment)
            if (first != second) {
                add("getCommentFeedback is not a lookup: comment \"${comment.id}\" answered $first then $second")
            }
            first.rating?.let {
                if (it.maximum <= 0 || it.value !in 0.0..it.maximum) {
                    add("comment \"${comment.id}\" is rated ${it.value} out of ${it.maximum}")
                }
            }
            if ((first.likes ?: 0) < 0 || (first.dislikes ?: 0) < 0) {
                add("comment \"${comment.id}\" has a negative like count; null is how a site says it has none")
            }
            first.reactions.filter { it.label.isBlank() }.forEach { _ ->
                add("comment \"${comment.id}\" has a reaction with no label")
            }
            addAll(check(source, comment.replies))
        }
    }
}
