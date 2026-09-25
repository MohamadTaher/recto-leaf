package leaf.novel.api

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga

/**
 * One comment, as flat as a site with a flat list can supply and as deep as a site with threads
 * needs.
 *
 * Almost every field is optional, and that is the point: a source fills in what its site has and
 * leaves the rest, and the reader draws what arrived. A site with no scores returns a null [score]
 * and no vote control appears; one with no avatars returns no [avatarUrl] and the row loses an
 * indent. Nothing here is required to be faked to satisfy the shape.
 *
 * Replies may arrive either way. A site that nests them puts them in [replies]; a site that returns
 * one flat list sets [parentId] on each and `NovelCommentTree` nests them. Sources should not do
 * both for the same comment.
 *
 * A data class rather than the plain [NovelChapterContent] style because a comment is copied — an
 * optimistic vote is `copy(score = …, vote = …)` — and compared, which a write-once chapter body
 * never is.
 */
data class NovelComment(
    /** Stable within one thread, and the only thing that identifies a comment to the reader. */
    val id: String,
    /** The comment itself, as an HTML fragment. Plain text is a valid fragment. */
    val body: String,
    val author: String,
    /** Set only when the site has one *and* it is worth a round trip; see `showAvatars`. */
    val avatarUrl: String? = null,
    /**
     * The site's own word for what this person is — "Translator", "Mod", "VIP", a tier name.
     *
     * Passed through verbatim and never interpreted. Sites disagree about what the roles mean and
     * the reader has no business guessing.
     */
    val badge: String? = null,
    /** Epoch milliseconds, or zero when the site only gives an unparseable "3 days ago". */
    val postedAt: Long = 0L,
    /** Net score, or the like count on a site with no downvote. Null where the site has neither. */
    val score: Int? = null,
    /** Which way the signed-in reader has already voted, where the site says. */
    val vote: NovelCommentVote = NovelCommentVote.NONE,
    /**
     * How many replies exist on the site, which is not always how many are in [replies].
     *
     * A site that returns the first two of forty sets both, and the reader offers the other
     * thirty-eight rather than pretending there were two.
     */
    val replyCount: Int = 0,
    val replies: List<NovelComment> = emptyList(),
    /** The comment this one answers, for a source that returns one flat list. */
    val parentId: String? = null,
    /** Kept at the top of its level by the site — an announcement, a translator's note. */
    val pinned: Boolean = false,
    /** Posted by whoever owns the novel on that site: the uploader, translator or author. */
    val byUploader: Boolean = false,
    /**
     * Removed, but still present because something replies to it.
     *
     * Kept rather than dropped so the chain above a surviving reply does not lose its root.
     */
    val deleted: Boolean = false,
    /** The comment's own page on the site, for sharing and for opening the thread in a browser. */
    val permalink: String? = null,
    /**
     * Which chapter the comment is on, when a novel-wide listing mixes several.
     *
     * The site's own label, not a chapter this app necessarily knows about.
     */
    val chapterLabel: String? = null,
)

/** Which way a reader has voted, or wants to. [NONE] is both "no vote" and "take mine back". */
enum class NovelCommentVote {
    UP,
    NONE,
    DOWN,
}

/**
 * What a listing was asked for.
 *
 * One request type for both the top level and a lazy reply expansion: they differ only by [parent],
 * and a source that fetches replies per-comment reads it while one that returns everything at once
 * never sees it set.
 */
class NovelCommentRequest(
    val target: NovelCommentTarget,
    /** Always one of the source's own declared sorts; the app never invents a key. */
    val sort: NovelCommentSort,
    /** One-based, for a site that pages by number. */
    val page: Int = 1,
    /** [NovelCommentPage.nextCursor] from the previous page, for a site that pages by cursor. */
    val cursor: String? = null,
    /** Set when the reader has asked for one comment's replies rather than the top level. */
    val parent: NovelComment? = null,
)

/**
 * What the comments are attached to.
 *
 * A null [chapter] means the novel as a whole — its landing page, where most sites keep reviews and
 * general discussion. Sources declare which of the two they can serve in
 * [NovelCommentCapabilities.scopes]; the reader only asks for one it said yes to.
 */
class NovelCommentTarget(
    val novel: SManga,
    val chapter: SChapter? = null,
) {
    val scope: NovelCommentScope
        get() = if (chapter == null) NovelCommentScope.NOVEL else NovelCommentScope.CHAPTER
}

enum class NovelCommentScope {
    NOVEL,
    CHAPTER,
}

/**
 * One way a site can order a thread.
 *
 * Deliberately not an enum. "Top", "Best", "Hot", "Most helpful" and "Controversial" are not the
 * same list on any two sites, and a fixed set would mean either mapping a site's options onto names
 * it does not use or dropping the ones that do not fit. A source declares its own, the reader shows
 * the labels, and [key] goes back to the source untouched.
 */
data class NovelCommentSort(
    val key: String,
    val label: String,
)

/** One page of a thread, plus whatever the site said about there being more. */
class NovelCommentPage(
    val comments: List<NovelComment>,
    val hasNextPage: Boolean = false,
    /** Opaque, and handed straight back in the next [NovelCommentRequest]. */
    val nextCursor: String? = null,
    /** The whole thread's size where the site reports it, for the header count. */
    val total: Int? = null,
)

/** A comment about to be posted. [parentId] null means a new top-level comment. */
class NovelCommentDraft(
    val target: NovelCommentTarget,
    val body: String,
    val parentId: String? = null,
)
