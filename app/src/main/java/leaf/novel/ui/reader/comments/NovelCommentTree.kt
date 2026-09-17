package leaf.novel.ui.reader.comments

import leaf.novel.api.NovelComment

/**
 * Turns whatever a site returned into the list of rows the sheet draws.
 *
 * All of the feature's real logic is here, and none of it touches Android or the network, so the
 * awkward cases — a flat list that has to be re-nested, a reply whose parent was deleted, a thread
 * forty levels deep, a source that returns the same comment twice — are unit tests rather than
 * something to find on a phone.
 *
 * Three steps, in order:
 *
 *  1. [build] nests a flat list by [NovelComment.parentId]. A site that already nests its replies
 *     passes through untouched.
 *  2. [sortedBy] reorders siblings, but only for a site that offers no sort of its own — when the
 *     site sorted the list, the order it chose is the answer and reordering it locally would be
 *     the app second-guessing a server that knows more.
 *  3. [flatten] walks the forest into rows, applying the collapse set and the display depth cap.
 *
 * Everything is defensive about the source, which is code this app did not compile: cycles are
 * broken, duplicate ids are dropped, and a reply naming a parent that is not in the page becomes a
 * root rather than disappearing.
 */
object NovelCommentTree {

    /**
     * How deep the sheet indents before it offers "continue this thread" instead.
     *
     * Five ancestors is about where a phone runs out of width. Past that, indenting further makes
     * the text column narrower than the byline above it, which is when a thread stops being
     * readable and Reddit's own answer — a row that opens the subtree as its own root — is better.
     */
    const val DISPLAY_DEPTH = 5

    /**
     * Nests a flat list, or passes a nested one through.
     *
     * Detected rather than declared: if nothing in the list names a parent that is also in the
     * list, there is nothing to nest and the list is already the forest. That makes the same
     * function correct for both source styles without the caller having to know which it got.
     */
    fun build(comments: List<NovelComment>): List<NovelComment> {
        // A source that repeats an id would otherwise produce two rows with the same Compose key.
        val unique = comments.distinctBy { it.id }
        val byId = unique.associateBy { it.id }
        if (unique.none { it.parentId != null && byId.containsKey(it.parentId) }) return unique

        val children = unique
            .filter { it.parentId != null && byId.containsKey(it.parentId) && it.parentId != it.id }
            .groupBy { it.parentId!! }

        val attached = mutableSetOf<String>()

        // Depth-first from each root, carrying the ancestors so a parentId cycle cannot loop.
        fun attach(comment: NovelComment, seen: Set<String>): NovelComment {
            attached += comment.id
            val nested = children[comment.id]
                .orEmpty()
                .filterNot { it.id in seen }
                .map { attach(it, seen + it.id) }
            return when {
                nested.isEmpty() -> comment
                // A source that both nested and flattened the same replies would double them.
                comment.replies.isNotEmpty() -> comment
                else -> comment.copy(
                    replies = nested,
                    replyCount = maxOf(comment.replyCount, nested.size),
                )
            }
        }

        val forest = unique
            .filter { it.parentId == null || !byId.containsKey(it.parentId) || it.parentId == it.id }
            .map { attach(it, setOf(it.id)) }
            .toMutableList()

        // A closed parentId cycle has no member that qualifies as a root, so without this the whole
        // cycle would vanish. Whichever of them is seen first becomes the root instead: the comments
        // are real even where the ids the site gave them are not.
        unique.forEach { comment ->
            if (comment.id !in attached) forest += attach(comment, setOf(comment.id))
        }

        return forest
    }

    /**
     * Reorders siblings at every level.
     *
     * Only ever used for a source with no sorts of its own: it works on the comments already
     * fetched and cannot reach the ones it has not, so on a paginated site it sorts a page rather
     * than a thread. That is worth having and worth being honest about — the sheet says so.
     *
     * Pinned comments stay at the top of their level whatever the order, because a site that pinned
     * one did so to be read first.
     */
    fun sortedBy(roots: List<NovelComment>, sort: NovelCommentLocalSort): List<NovelComment> {
        val comparator = compareByDescending<NovelComment> { it.pinned }.then(sort.comparator)
        fun order(level: List<NovelComment>): List<NovelComment> = level
            .sortedWith(comparator)
            .map { if (it.replies.isEmpty()) it else it.copy(replies = order(it.replies)) }
        return order(roots)
    }

    /**
     * The forest as the flat list of rows a `LazyColumn` wants.
     *
     * Iterative rather than recursive: the depth is whatever the site allowed, and a thread deep
     * enough to overflow the stack is a crash on someone's phone rather than a bad-looking sheet.
     *
     * @param collapsed ids whose subtree is folded away.
     * @param loadingReplies ids whose replies are being fetched right now.
     * @param root when set, only this comment and its descendants — the sheet's focus mode.
     */
    fun flatten(
        roots: List<NovelComment>,
        collapsed: Set<String> = emptySet(),
        loadingReplies: Set<String> = emptySet(),
        lazyReplies: Boolean = false,
        root: String? = null,
    ): List<NovelCommentRow> {
        val start = if (root == null) roots else listOfNotNull(find(roots, root))
        val rows = mutableListOf<NovelCommentRow>()

        // Ancestors are carried per entry rather than recomputed: the sheet draws one indent rail
        // per ancestor and each rail collapses the ancestor it belongs to, so the row needs their
        // ids, not just how many there are.
        data class Pending(val comment: NovelComment, val ancestors: List<String>)

        val stack = ArrayDeque<Pending>()
        // Reversed so that pushing onto a stack pops them back in the order the site gave.
        start.asReversed().forEach { stack.addLast(Pending(it, emptyList())) }

        while (stack.isNotEmpty()) {
            val (comment, ancestors) = stack.removeLast()
            val depth = ancestors.size
            val folded = comment.id in collapsed
            val descendants = if (folded) countDescendants(comment) else 0

            rows += NovelCommentRow.Body(
                comment = comment,
                ancestors = ancestors,
                collapsed = folded,
                hiddenCount = descendants,
            )
            if (folded) continue

            // The cap applies to what is *drawn*, so focusing a deep comment re-bases the depth and
            // lets the reader keep going. Without that the cap would be a wall rather than a fold.
            val beyondCap = depth + 1 >= DISPLAY_DEPTH
            if (beyondCap && comment.replies.isNotEmpty()) {
                rows += NovelCommentRow.ContinueThread(
                    comment = comment,
                    ancestors = ancestors + comment.id,
                    count = countDescendants(comment),
                )
                continue
            }

            val missing = comment.replyCount - comment.replies.size
            if (lazyReplies && missing > 0) {
                rows += NovelCommentRow.MoreReplies(
                    comment = comment,
                    ancestors = ancestors + comment.id,
                    count = missing,
                    loading = comment.id in loadingReplies,
                )
            }

            comment.replies.asReversed().forEach { stack.addLast(Pending(it, ancestors + comment.id)) }
        }

        return rows
    }

    /** The comment with this id, anywhere in the forest. */
    fun find(roots: List<NovelComment>, id: String): NovelComment? {
        val stack = ArrayDeque(roots)
        while (stack.isNotEmpty()) {
            val comment = stack.removeLast()
            if (comment.id == id) return comment
            stack.addAll(comment.replies)
        }
        return null
    }

    /**
     * Puts [updated] where the comment with its id was, keeping that comment's replies.
     *
     * A vote returns the comment as the site now reports it, which for most sites means a bare
     * comment with no replies attached — so the ones already fetched are kept rather than being
     * silently dropped by an update that was only ever about the score.
     */
    fun replace(roots: List<NovelComment>, updated: NovelComment): List<NovelComment> = roots.map {
        when {
            it.id == updated.id -> updated.copy(
                replies = updated.replies.ifEmpty { it.replies },
                replyCount = maxOf(updated.replyCount, it.replyCount),
            )
            it.replies.isEmpty() -> it
            else -> it.copy(replies = replace(it.replies, updated))
        }
    }

    /**
     * Adds a freshly posted comment under [parentId], or at the top when it is null.
     *
     * At the top of its level rather than the bottom: a reader who just wrote something wants to
     * see it, and hunting for it at the end of a page of forty is how a post looks like it failed.
     */
    fun insert(roots: List<NovelComment>, parentId: String?, comment: NovelComment): List<NovelComment> {
        if (parentId == null) return listOf(comment) + roots
        return roots.map {
            when {
                it.id == parentId -> it.copy(
                    replies = listOf(comment) + it.replies,
                    replyCount = it.replyCount + 1,
                )
                it.replies.isEmpty() -> it
                else -> it.copy(replies = insert(it.replies, parentId, comment))
            }
        }
    }

    /** Appends a lazily fetched page of replies to the comment that asked for them. */
    fun addReplies(
        roots: List<NovelComment>,
        parentId: String,
        replies: List<NovelComment>,
    ): List<NovelComment> = roots.map {
        when {
            it.id == parentId -> {
                val known = it.replies.mapTo(mutableSetOf()) { reply -> reply.id }
                val merged = it.replies + replies.filterNot { reply -> reply.id in known }
                it.copy(replies = merged, replyCount = maxOf(it.replyCount, merged.size))
            }
            it.replies.isEmpty() -> it
            else -> it.copy(replies = addReplies(it.replies, parentId, replies))
        }
    }

    /** Every comment in the forest, which is what the header counts. */
    fun count(roots: List<NovelComment>): Int = roots.sumOf { 1 + countDescendants(it) }

    /** Ids of every ancestor chain that has to be open for [id] to be visible. */
    fun ancestorsOf(roots: List<NovelComment>, id: String): List<String> {
        fun walk(level: List<NovelComment>, trail: List<String>): List<String>? {
            level.forEach { comment ->
                if (comment.id == id) return trail
                walk(comment.replies, trail + comment.id)?.let { return it }
            }
            return null
        }
        return walk(roots, emptyList()).orEmpty()
    }

    private fun countDescendants(comment: NovelComment): Int {
        var total = 0
        val stack = ArrayDeque(comment.replies)
        while (stack.isNotEmpty()) {
            val next = stack.removeLast()
            total++
            stack.addAll(next.replies)
        }
        return total
    }
}

/**
 * One line of the sheet.
 *
 * The rows that are not comments carry the comment they hang off rather than just its id, because
 * every one of them needs its indent rails drawn the same way a comment's are.
 */
sealed interface NovelCommentRow {

    /** Ids from the root down to this row's parent. Its size is the indent depth. */
    val ancestors: List<String>

    /** Stable across a reload, so a `LazyColumn` keeps its scroll position and its animations. */
    val key: String

    data class Body(
        val comment: NovelComment,
        override val ancestors: List<String>,
        val collapsed: Boolean,
        /** How many comments the fold is hiding, for the badge on a collapsed byline. */
        val hiddenCount: Int,
    ) : NovelCommentRow {
        override val key: String get() = comment.id
    }

    /** Replies the site has but has not sent yet. */
    data class MoreReplies(
        val comment: NovelComment,
        override val ancestors: List<String>,
        val count: Int,
        val loading: Boolean,
    ) : NovelCommentRow {
        override val key: String get() = "more:${comment.id}"
    }

    /** A subtree too deep to indent further, offered as its own root instead. */
    data class ContinueThread(
        val comment: NovelComment,
        override val ancestors: List<String>,
        val count: Int,
    ) : NovelCommentRow {
        override val key: String get() = "deep:${comment.id}"
    }
}
