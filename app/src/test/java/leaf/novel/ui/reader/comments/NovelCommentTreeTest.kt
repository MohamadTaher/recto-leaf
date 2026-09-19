package leaf.novel.ui.reader.comments

import io.kotest.matchers.shouldBe
import leaf.novel.api.NovelComment
import org.junit.jupiter.api.Test

/**
 * The tree is where every site's idea of a comment thread has to become one shape, so the cases
 * worth holding are the ones a site can actually hand over: a flat list, a nested one, a reply
 * whose parent never arrived, and a source that contradicts itself.
 */
class NovelCommentTreeTest {

    private fun comment(
        id: String,
        parentId: String? = null,
        score: Int? = null,
        postedAt: Long = 0L,
        pinned: Boolean = false,
        replies: List<NovelComment> = emptyList(),
        replyCount: Int = replies.size,
    ) = NovelComment(
        id = id,
        body = id,
        author = "author-$id",
        parentId = parentId,
        score = score,
        postedAt = postedAt,
        pinned = pinned,
        replies = replies,
        replyCount = replyCount,
    )

    // region build

    @Test
    fun `nests a flat list by its parent ids`() {
        val flat = listOf(
            comment("1"),
            comment("2", parentId = "1"),
            comment("3", parentId = "2"),
            comment("4"),
        )

        val roots = NovelCommentTree.build(flat)

        roots.map { it.id } shouldBe listOf("1", "4")
        roots[0].replies.map { it.id } shouldBe listOf("2")
        roots[0].replies[0].replies.map { it.id } shouldBe listOf("3")
    }

    @Test
    fun `leaves an already nested list alone`() {
        val nested = listOf(comment("1", replies = listOf(comment("2", parentId = "1"))))

        NovelCommentTree.build(nested) shouldBe nested
    }

    /** A reply whose parent is on the previous page. Dropping it would lose a real comment. */
    @Test
    fun `treats a reply with no parent in the page as a root`() {
        val flat = listOf(comment("2", parentId = "missing"), comment("3"))

        NovelCommentTree.build(flat).map { it.id } shouldBe listOf("2", "3")
    }

    @Test
    fun `drops a repeated id rather than drawing it twice`() {
        val flat = listOf(comment("1"), comment("1"), comment("2", parentId = "1"))

        val roots = NovelCommentTree.build(flat)

        roots.size shouldBe 1
        roots[0].replies.map { it.id } shouldBe listOf("2")
    }

    /** A source that names itself as its own parent would otherwise recurse forever. */
    @Test
    fun `survives a comment that is its own parent`() {
        val flat = listOf(comment("1", parentId = "1"))

        NovelCommentTree.build(flat).map { it.id } shouldBe listOf("1")
    }

    @Test
    fun `survives a parent cycle between two comments`() {
        val flat = listOf(comment("1", parentId = "2"), comment("2", parentId = "1"))

        // Whichever way it is broken, both comments survive and nothing loops.
        NovelCommentTree.count(NovelCommentTree.build(flat)) shouldBe 2
    }

    /**
     * A source that nests some replies and flattens others contradicts itself, and the reply it
     * sent only the flat way is still a comment somebody wrote.
     */
    @Test
    fun `keeps a flat reply whose parent already carries nested ones`() {
        val mixed = listOf(
            comment("1", replies = listOf(comment("1a", parentId = "1"))),
            comment("1b", parentId = "1"),
        )

        val roots = NovelCommentTree.build(mixed)

        roots.map { it.id } shouldBe listOf("1")
        roots[0].replies.map { it.id } shouldBe listOf("1a", "1b")
    }

    /** The same reply sent both ways is one comment, and must not become two rows. */
    @Test
    fun `does not double a reply that arrived nested and flat`() {
        val mixed = listOf(
            comment("1", replies = listOf(comment("1a", parentId = "1"))),
            comment("1a", parentId = "1"),
        )

        val roots = NovelCommentTree.build(mixed)

        roots[0].replies.map { it.id } shouldBe listOf("1a")
        NovelCommentTree.count(roots) shouldBe 2
    }

    /** `distinctBy` only ever sees the top level, and a repeat can be buried anywhere. */
    @Test
    fun `drops a repeated id nested inside a reply chain`() {
        val nested = listOf(
            comment("1", replies = listOf(comment("dupe"))),
            comment("2", replies = listOf(comment("dupe"))),
        )

        val roots = NovelCommentTree.build(nested)

        NovelCommentTree.flatten(roots).map { it.key } shouldBe listOf("1", "dupe", "hide:1", "2")
    }

    // endregion

    // region sorting

    @Test
    fun `orders siblings at every level by likes`() {
        val roots = listOf(
            comment("low", score = 1, replies = listOf(comment("a", score = 1), comment("b", score = 9))),
            comment("high", score = 5),
        )

        val sorted = NovelCommentTree.sortedBy(roots, compareByDescending { it.score ?: 0 })

        sorted.map { it.id } shouldBe listOf("high", "low")
        sorted[1].replies.map { it.id } shouldBe listOf("b", "a")
    }

    @Test
    fun `leaves ties in the order they arrived`() {
        val roots = listOf(comment("1"), comment("2"), comment("3"))

        NovelCommentTree.sortedBy(roots, compareBy { 0 }).map { it.id } shouldBe listOf("1", "2", "3")
    }

    // endregion

    // region flatten

    @Test
    fun `reply expansion keeps the parent visible and opens each nesting level independently`() {
        val roots = listOf(comment("1", replies = listOf(comment("1a", replies = listOf(comment("1b"))))))

        NovelCommentTree.flatten(roots, expandedReplies = emptySet()).map { it.key } shouldBe listOf("1")
        NovelCommentTree.flatten(roots, expandedReplies = setOf("1")).map { it.key } shouldBe
            listOf("1", "1a", "hide:1")
        NovelCommentTree.flatten(roots, expandedReplies = setOf("1", "1a")).map { it.key } shouldBe
            listOf("1", "1a", "1b", "hide:1a", "hide:1")
    }

    /**
     * An open comment's thread line runs past every reply, and past the row offering the replies
     * still to come, before it ends — so the row that closes it has to come after all of them, at
     * the parent's own depth.
     */
    @Test
    fun `closes an open comment after its replies and the ones still to come`() {
        val roots = listOf(comment("1", replies = listOf(comment("1a")), replyCount = 5), comment("2"))

        val rows = NovelCommentTree.flatten(roots, lazyReplies = true, expandedReplies = setOf("1"))

        rows.map { it.key } shouldBe listOf("1", "1a", "more:1", "hide:1", "2")
        rows[1].ancestors shouldBe listOf("1")
        rows[3].ancestors shouldBe emptyList()
    }

    @Test
    fun `closed replies do not offer an extra paging row`() {
        val roots = listOf(comment("1", replyCount = 12))

        NovelCommentTree.flatten(roots, lazyReplies = true, expandedReplies = emptySet())
            .map { it.key } shouldBe listOf("1")
        NovelCommentTree.flatten(roots, lazyReplies = true, expandedReplies = setOf("1"))
            .filterIsInstance<NovelCommentRow.MoreReplies>().single().count shouldBe 12
    }

    @Test
    fun `walks the forest depth first, carrying the ancestors`() {
        val roots = listOf(
            comment("1", replies = listOf(comment("1a"), comment("1b"))),
            comment("2"),
        )

        val rows = NovelCommentTree.flatten(roots)

        rows.map { it.key } shouldBe listOf("1", "1a", "1b", "hide:1", "2")
        rows[1].ancestors shouldBe listOf("1")
    }

    @Test
    fun `hides a collapsed subtree and says how much it hid`() {
        val roots = listOf(
            comment("1", replies = listOf(comment("1a", replies = listOf(comment("1a1"))))),
        )

        val rows = NovelCommentTree.flatten(roots, collapsed = setOf("1"))

        rows.map { it.key } shouldBe listOf("1")
        (rows[0] as NovelCommentRow.Body).hiddenCount shouldBe 2
    }

    @Test
    fun `offers to continue a thread rather than indenting past the cap`() {
        // One root plus enough replies to run past DISPLAY_DEPTH.
        var deepest = comment("d${NovelCommentTree.DISPLAY_DEPTH}")
        for (level in NovelCommentTree.DISPLAY_DEPTH - 1 downTo 0) {
            deepest = comment("d$level", replies = listOf(deepest))
        }

        val rows = NovelCommentTree.flatten(listOf(deepest))

        rows.count { it is NovelCommentRow.ContinueThread } shouldBe 1
        rows.filterIsInstance<NovelCommentRow.Body>().size shouldBe NovelCommentTree.DISPLAY_DEPTH
    }

    /** Focusing re-bases the depth, which is what makes the cap a fold rather than a wall. */
    @Test
    fun `shows the rest of a thread once it is focused`() {
        var deepest = comment("d${NovelCommentTree.DISPLAY_DEPTH}")
        for (level in NovelCommentTree.DISPLAY_DEPTH - 1 downTo 0) {
            deepest = comment("d$level", replies = listOf(deepest))
        }

        val rows = NovelCommentTree.flatten(listOf(deepest), root = "d1")

        rows.first().key shouldBe "d1"
        rows.none { it is NovelCommentRow.ContinueThread } shouldBe true
    }

    @Test
    fun `offers the replies a lazy site has not sent`() {
        val roots = listOf(comment("1", replies = listOf(comment("1a")), replyCount = 12))

        val rows = NovelCommentTree.flatten(roots, lazyReplies = true)

        val more = rows.filterIsInstance<NovelCommentRow.MoreReplies>().single()
        more.count shouldBe 11
    }

    @Test
    fun `does not offer replies on a site that sends them all`() {
        val roots = listOf(comment("1", replies = listOf(comment("1a")), replyCount = 12))

        NovelCommentTree.flatten(roots, lazyReplies = false)
            .none { it is NovelCommentRow.MoreReplies } shouldBe true
    }

    /** A thread deeper than the JVM stack is a crash on someone's phone, not a layout problem. */
    @Test
    fun `flattens a thread far deeper than anything a screen could show`() {
        var deepest = comment("d10000")
        repeat(10_000) { level -> deepest = comment("d$level", replies = listOf(deepest)) }

        NovelCommentTree.flatten(listOf(deepest)).isNotEmpty() shouldBe true
    }

    // endregion

    // region editing

    @Test
    fun `replaces a comment without losing the replies already fetched`() {
        val roots = listOf(comment("1", replies = listOf(comment("1a"))))

        val updated = NovelCommentTree.replace(roots, comment("1", score = 5))

        updated[0].score shouldBe 5
        updated[0].replies.map { it.id } shouldBe listOf("1a")
    }

    @Test
    fun `puts a new reply at the top of its parent's replies`() {
        val roots = listOf(comment("1", replies = listOf(comment("1a"))))

        val updated = NovelCommentTree.insert(roots, parentId = "1", comment = comment("mine"))

        updated[0].replies.map { it.id } shouldBe listOf("mine", "1a")
        updated[0].replyCount shouldBe 2
    }

    @Test
    fun `puts a new top level comment at the top of the thread`() {
        val roots = listOf(comment("1"))

        NovelCommentTree.insert(roots, parentId = null, comment = comment("mine"))
            .map { it.id } shouldBe listOf("mine", "1")
    }

    @Test
    fun `merges lazily fetched replies without repeating the ones already there`() {
        val roots = listOf(comment("1", replies = listOf(comment("1a")), replyCount = 3))

        val updated = NovelCommentTree.addReplies(roots, "1", listOf(comment("1a"), comment("1b")))

        updated[0].replies.map { it.id } shouldBe listOf("1a", "1b")
    }

    /**
     * A site whose reply count includes replies it will not serve — deleted ones, usually — would
     * otherwise leave a row offering them that every tap asks for again.
     */
    @Test
    fun `settles the reply count once the site says there is no more`() {
        val roots = listOf(comment("1", replies = listOf(comment("1a")), replyCount = 9))

        val updated = NovelCommentTree.addReplies(roots, "1", listOf(comment("1b")), complete = true)

        updated[0].replies.map { it.id } shouldBe listOf("1a", "1b")
        updated[0].replyCount shouldBe 2
    }

    @Test
    fun `counts every comment in the forest`() {
        val roots = listOf(
            comment("1", replies = listOf(comment("1a"), comment("1b", replies = listOf(comment("1b1"))))),
            comment("2"),
        )

        NovelCommentTree.count(roots) shouldBe 5
    }

    /**
     * A second page overlaps the first as soon as somebody posts between the two requests, and a
     * comment can come back as a root having arrived as a reply. Both copies would be two rows
     * under one key.
     */
    @Test
    fun `merges a later page without repeating what is already in the thread`() {
        val roots = listOf(comment("1", replies = listOf(comment("1a", parentId = "1"))))

        val merged = NovelCommentTree.merge(roots, listOf(comment("1a", parentId = "1"), comment("2")))

        merged.map { it.id } shouldBe listOf("1", "2")
        NovelCommentTree.flatten(merged).map { it.key } shouldBe listOf("1", "1a", "hide:1", "2")
    }

    @Test
    fun `knows every id in the forest, replies included`() {
        val roots = listOf(comment("1", replies = listOf(comment("1a"))), comment("2"))

        NovelCommentTree.ids(roots) shouldBe setOf("1", "1a", "2")
    }

    @Test
    fun `finds the ancestors a comment is buried under`() {
        val roots = listOf(comment("1", replies = listOf(comment("1a", replies = listOf(comment("1a1"))))))

        NovelCommentTree.ancestorsOf(roots, "1a1") shouldBe listOf("1", "1a")
        NovelCommentTree.ancestorsOf(roots, "nope") shouldBe emptyList()
    }

    // endregion
}
