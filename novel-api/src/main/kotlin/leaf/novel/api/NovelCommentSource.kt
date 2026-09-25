package leaf.novel.api

import eu.kanade.tachiyomi.source.Source

/**
 * A source that can also serve the discussion its site hosts.
 *
 * Like [NovelSource], it widens [Source] instead of replacing it, so implementing it costs an
 * extension one interface and changes nothing about how it is browsed, searched or read. A source
 * that does not implement it is simply never asked, and the reader's comments button does not
 * appear for it — which is the correct outcome for the many sites that have no comments at all.
 *
 * It is separate from [NovelSource] on purpose. The two are orthogonal: a manga source could serve
 * comments, and plenty of novel sources never will, so requiring one to get the other would put a
 * `TODO()` in every extension that has nothing to say.
 *
 * As with [NovelSource], extensions must take this as `compileOnly` — the extension class loader
 * resolves child-first, so a bundled copy would be a different class and `is NovelCommentSource`
 * would silently return false.
 *
 * ### Implementing one
 *
 * Declare [commentCapabilities] honestly and implement [getComments]. That is the whole minimum: a
 * site with one flat unsorted list is a valid implementation, and the reader will not offer a sort
 * control, a vote button or a reply field it was not told about. Only implement [voteComment] and
 * [postComment] if the capability says so — the reader reads the capability, not the method.
 *
 * Throwing from [getComments] is fine and expected; the reader shows the message. Returning an
 * empty page means "this chapter has no comments", which is a different thing and is drawn
 * differently.
 */
interface NovelCommentSource : Source {

    /** What this site can actually do. Everything the reader offers is gated on it. */
    val commentCapabilities: NovelCommentCapabilities

    /**
     * One page of comments for [NovelCommentRequest.target].
     *
     * Called for the top level, and again with [NovelCommentRequest.parent] set when the reader
     * expands a comment on a site that serves replies separately.
     */
    suspend fun getComments(request: NovelCommentRequest): NovelCommentPage

    /**
     * Casts, changes or withdraws a vote, returning the comment as the site now reports it.
     *
     * The reader updates optimistically and replaces the row with whatever comes back, so a site
     * that recomputes the score server-side gets the right number without a reload. Only called
     * when [NovelCommentCapabilities.voting] is set.
     */
    suspend fun voteComment(comment: NovelComment, vote: NovelCommentVote): NovelComment =
        throw UnsupportedOperationException("$name cannot vote on comments")

    /**
     * Posts [draft], returning the comment the site created.
     *
     * Only called when [NovelCommentCapabilities.posting] is set. A site that needs an account
     * should say so with [NovelCommentCapabilities.requiresAccount] and throw here when there is
     * none, rather than posting as nobody.
     */
    suspend fun postComment(draft: NovelCommentDraft): NovelComment =
        throw UnsupportedOperationException("$name cannot post comments")
}

/**
 * What one site's comments can do.
 *
 * Every control in the reader is gated on a field here, so the honest answer is always the useful
 * one: claiming a capability the site lacks produces a button that fails, and omitting one the site
 * has only loses a feature. The defaults describe the least a site can be — a flat, unsorted,
 * unscored, read-only list of chapter comments — so a minimal implementation is a one-liner.
 */
data class NovelCommentCapabilities(
    /**
     * The orders the site can return, in the order to offer them. The first is the default.
     *
     * Empty means the site has exactly one order and no control is drawn for it.
     */
    val sorts: List<NovelCommentSort> = emptyList(),

    /** Whether the site keeps comments per chapter, on the novel as a whole, or both. */
    val scopes: Set<NovelCommentScope> = setOf(NovelCommentScope.CHAPTER),

    /**
     * How deep the site's own threads go: 1 for a flat list, 2 for one level of reply, and
     * [UNLIMITED] for arbitrary nesting.
     *
     * This is the site's limit, not the reader's. The reader has its own display cap, past which it
     * offers "continue this thread" rather than indenting into a two-character column.
     */
    val maxDepth: Int = 1,

    /**
     * Whether replies come from a second call rather than inside [NovelComment.replies].
     *
     * Set it and the reader shows a "load replies" affordance for any comment whose
     * [NovelComment.replyCount] exceeds what arrived; leave it and the reader assumes what it has
     * is all there is.
     */
    val lazyReplies: Boolean = false,

    /** Whether there is a second page to ask for. A site that returns everything sets false. */
    val paginated: Boolean = true,

    /** Whether [NovelComment.score] means anything. Without it no score is drawn. */
    val scored: Boolean = false,

    /** Whether the site has a downvote, or only a like. Decides one arrow or two. */
    val downvotes: Boolean = false,

    /** Whether [NovelCommentSource.voteComment] works. */
    val voting: Boolean = false,

    /** Whether [NovelCommentSource.postComment] works. */
    val posting: Boolean = false,

    /**
     * Whether [NovelComment.avatarUrl] is worth fetching.
     *
     * A site that hands out a default silhouette for everyone should say false: forty identical
     * images is forty requests for nothing.
     */
    val avatars: Boolean = false,

    /**
     * Whether an account is needed, and for what.
     *
     * The reader uses it to explain a failure rather than to prevent one — an extension that can
     * sign in through its own settings is welcome to say false.
     */
    val requiresAccount: Boolean = false,
) {
    val threaded: Boolean get() = maxDepth > 1

    companion object {
        /** [maxDepth] for a site whose threads nest as far as anyone takes them. */
        const val UNLIMITED = Int.MAX_VALUE
    }
}
