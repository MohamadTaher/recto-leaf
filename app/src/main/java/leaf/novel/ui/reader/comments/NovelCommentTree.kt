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
        // [dedupe] rather than the list itself: a nested source can repeat an id inside a reply
        // chain, where distinctBy — which only ever sees the top level — cannot reach it.
        if (unique.none { it.parentId != null && byId.containsKey(it.parentId) }) return dedupe(unique)

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
            if (nested.isEmpty()) return comment

            // A source that both nested and flattened the same reply would double it, so the flat
            // copies of replies already attached are dropped — and only those. Dropping the whole
            // batch would lose the replies the site sent only the flat way, which is a comment
            // disappearing rather than a comment drawn twice.
            val known = comment.replies.mapTo(mutableSetOf()) { it.id }
            val added = nested.filterNot { it.id in known }
            if (added.isEmpty()) return comment

            return comment.copy(
                replies = comment.replies + added,
                replyCount = maxOf(comment.replyCount, comment.replies.size + added.size),
            )
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

        return dedupe(forest)
    }

    /**
     * [addition] appended to [roots] with everything already in them left out.
     *
     * The page a site returns is not always disjoint from the one before it — a comment posted
     * between the two requests shifts the rest along, and a comment that arrived as a reply on the
     * first page can come back as a root on the second. Appending both copies would draw the
     * comment twice under one row key, so the later copy is dropped and the one already on screen,
     * with whatever replies have since been fetched under it, is the one that stays.
     */
    fun merge(roots: List<NovelComment>, addition: List<NovelComment>): List<NovelComment> =
        roots + dedupe(addition, ids(roots))

    /** Every id in the forest, replies included. */
    fun ids(roots: List<NovelComment>): Set<String> {
        val ids = mutableSetOf<String>()
        val stack = ArrayDeque(roots)
        while (stack.isNotEmpty()) {
            val comment = stack.removeLast()
            ids += comment.id
            stack.addAll(comment.replies)
        }
        return ids
    }

    /**
     * Siblings at every level, in [comparator]'s order.
     *
     * One order for every source, because a thread gathers several and no one site's ranking can
     * place another's comments. Stable, so comments that tie keep the order they arrived in.
     */
    fun sortedBy(roots: List<NovelComment>, comparator: Comparator<NovelComment>): List<NovelComment> {
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
     * @param expandedReplies parents whose children are visible. The sheet starts with an empty set;
     * callers flattening a whole tree can omit it.
     */
    fun flatten(
        roots: List<NovelComment>,
        collapsed: Set<String> = emptySet(),
        loadingReplies: Set<String> = emptySet(),
        lazyReplies: Boolean = false,
        root: String? = null,
        expandedReplies: Set<String> = ids(roots),
    ): List<NovelCommentRow> {
        val start = if (root == null) roots else listOfNotNull(find(roots, root))
        val rows = mutableListOf<NovelCommentRow>()

        // Ancestors are carried per entry rather than recomputed: the sheet draws one indent rail
        // per ancestor and each rail collapses the ancestor it belongs to, so the row needs their
        // ids, not just how many there are. A row already built waits on the stack for the rows
        // that must come before it: a subtree's closing row goes on before its replies do.
        data class Pending(val comment: NovelComment, val ancestors: List<String>, val row: NovelCommentRow? = null)

        val stack = ArrayDeque<Pending>()
        // Reversed so that pushing onto a stack pops them back in the order the site gave.
        start.asReversed().forEach { stack.addLast(Pending(it, emptyList())) }

        while (stack.isNotEmpty()) {
            val (comment, ancestors, built) = stack.removeLast()
            if (built != null) {
                rows += built
                continue
            }
            val depth = ancestors.size
            val folded = comment.id in collapsed
            val descendants = if (folded) countDescendants(comment) else 0

            rows += NovelCommentRow.Body(
                comment = comment,
                ancestors = ancestors,
                collapsed = folded,
                hiddenCount = descendants,
            )
            if (folded || comment.id !in expandedReplies) continue

            val missing = comment.replyCount - comment.replies.size
            val hasMore = lazyReplies && missing > 0
            if (comment.replies.isEmpty() && !hasMore) continue

            // Everything under an open comment ends with the row that closes it, so its thread line
            // has somewhere to end. Pushed first, it is drawn last.
            val loadingFirst = comment.replies.isEmpty() && comment.id in loadingReplies
            stack.addLast(Pending(comment, ancestors, NovelCommentRow.HideReplies(comment, ancestors, loadingFirst)))

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

            // After the replies already here, which is where the ones still to come will go.
            if (hasMore && !loadingFirst) {
                val more = NovelCommentRow.MoreReplies(
                    comment = comment,
                    ancestors = ancestors + comment.id,
                    count = missing,
                    loading = comment.id in loadingReplies,
                )
                stack.addLast(Pending(comment, ancestors, more))
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

    /**
     * Appends a lazily fetched page of replies to the comment that asked for them.
     *
     * @param complete when the site has no more to give, which settles [NovelComment.replyCount] at
     * what actually arrived. Without that a site whose count includes replies it will not serve —
     * deleted ones, usually — leaves a "show three more replies" row that can never be satisfied,
     * and every tap on it is another request for the same page.
     */
    fun addReplies(
        roots: List<NovelComment>,
        parentId: String,
        replies: List<NovelComment>,
        complete: Boolean = false,
    ): List<NovelComment> = roots.map {
        when {
            it.id == parentId -> {
                val known = it.replies.mapTo(mutableSetOf()) { reply -> reply.id }
                val merged = it.replies + replies.filterNot { reply -> reply.id in known }
                it.copy(
                    replies = merged,
                    replyCount = if (complete) merged.size else maxOf(it.replyCount, merged.size),
                )
            }
            it.replies.isEmpty() -> it
            else -> it.copy(replies = addReplies(it.replies, parentId, replies, complete))
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

    /**
     * The forest with every repeated id removed, wherever in it the repeat sits.
     *
     * Looked for iteratively and rebuilt only when something is found: a repeat is rare, a thread
     * can be as deep as the site allowed, and a walk that finds nothing must not be the thing that
     * overflows the stack.
     */
    private fun dedupe(roots: List<NovelComment>, known: Set<String> = emptySet()): List<NovelComment> {
        val seen = known.toMutableSet()
        var repeated = false
        val stack = ArrayDeque(roots)
        while (stack.isNotEmpty()) {
            val comment = stack.removeLast()
            if (!seen.add(comment.id)) {
                repeated = true
                break
            }
            stack.addAll(comment.replies)
        }
        return if (repeated) prune(roots, known.toMutableSet()) else roots
    }

    /**
     * [roots] without the comments in [seen], which it fills as it goes.
     *
     * A whole subtree goes rather than just its head: a duplicate is the same comment arriving
     * twice, so its replies are the replies already under the copy that is kept, and promoting them
     * would strand a reply at the top level with nothing above it.
     */
    private fun prune(roots: List<NovelComment>, seen: MutableSet<String>): List<NovelComment> =
        roots.mapNotNull { comment ->
            when {
                !seen.add(comment.id) -> null
                comment.replies.isEmpty() -> comment
                else -> comment.copy(replies = prune(comment.replies, seen))
            }
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

    /**
     * The foot of an open comment's replies, where they are closed again.
     *
     * Carries the parent's own ancestors rather than the replies', because it is drawn at the
     * parent's depth: the parent's thread line runs down past every reply and ends here.
     */
    data class HideReplies(
        val comment: NovelComment,
        override val ancestors: List<String>,
        /** The first replies are still on their way, so there is nothing above this row yet. */
        val loading: Boolean,
    ) : NovelCommentRow {
        override val key: String get() = "hide:${comment.id}"
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
