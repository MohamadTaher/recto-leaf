package leaf.novel.ui.reader.comments

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import leaf.novel.api.NovelCommentCapabilities
import leaf.novel.api.NovelCommentPage
import leaf.novel.api.NovelCommentRequest
import leaf.novel.api.NovelCommentScope
import org.junit.jupiter.api.Test

/**
 * Finding the novel elsewhere is the one step that can put a stranger's discussion in the sheet, so
 * what is worth holding is what it refuses.
 */
class NovelCommentMatcherTest {

    private val empty: suspend (NovelCommentRequest) -> NovelCommentPage = { NovelCommentPage(emptyList()) }

    @Test
    fun `matches the same title across case and punctuation and nothing looser`() = runBlocking<Unit> {
        val own = FakeCommentSource(id = 1L, respond = empty)
        val same = FakeCommentSource(id = 2L, respond = empty).apply {
            search = { listOf(novel("Other Novel"), novel("shadow  slave!", url = "/found")) }
        }
        // The migration engine takes a search's only result whatever it is called. This must not.
        val lone = FakeCommentSource(id = 3L, respond = empty).apply { search = { listOf(novel("Shadow Slave 2")) } }
        val matcher = NovelCommentMatcher(NovelCommentCache(), NovelCommentCache()) { listOf(own, same, lone) }

        val found = matcher.find(own, novel("Shadow Slave"), NovelCommentScope.CHAPTER)

        found.map { (source, match) -> source.id to match.url } shouldBe listOf(2L to "/found")
        own.searches shouldBe emptyList()
    }

    @Test
    fun `only searches other comment sources that serve the scope`() = runBlocking<Unit> {
        val own = FakeCommentSource(id = 1L, respond = empty)
        val novelOnly = FakeCommentSource(
            NovelCommentCapabilities(scopes = setOf(NovelCommentScope.NOVEL)),
            id = 2L,
            respond = empty,
        ).apply { search = { listOf(novel("Shadow Slave")) } }
        val matcher = NovelCommentMatcher(NovelCommentCache(), NovelCommentCache()) { listOf(own, novelOnly) }

        matcher.find(own, novel("Shadow Slave"), NovelCommentScope.CHAPTER) shouldBe emptyList()
        novelOnly.searches shouldBe emptyList()
        matcher.find(own, novel("Shadow Slave"), NovelCommentScope.NOVEL).map { it.first.id } shouldBe listOf(2L)
    }

    @Test
    fun `remembers an answer but not a failure`() = runBlocking<Unit> {
        val own = FakeCommentSource(id = 1L, respond = empty)
        var failing = true
        val other = FakeCommentSource(id = 2L, respond = empty).apply {
            search = { if (failing) error("offline") else emptyList() }
        }
        val matcher = NovelCommentMatcher(NovelCommentCache(), NovelCommentCache()) { listOf(own, other) }

        matcher.find(own, novel("Shadow Slave"), NovelCommentScope.CHAPTER) shouldBe emptyList()
        failing = false
        matcher.find(own, novel("Shadow Slave"), NovelCommentScope.CHAPTER) shouldBe emptyList()
        matcher.find(own, novel("Shadow Slave"), NovelCommentScope.CHAPTER) shouldBe emptyList()

        // The failure was asked again; the empty answer that followed it was not.
        other.searches.size shouldBe 2
    }

    @Test
    fun `finds a chapter by number from one fetch of the list`() = runBlocking<Unit> {
        val other = FakeCommentSource(id = 2L, respond = empty).apply {
            chapters = {
                delay(50)
                listOf(sourceChapter(12f, "/c/12"), sourceChapter(11f, "/c/11"), sourceChapter(10f, "/c/10"))
            }
        }
        val matcher = NovelCommentMatcher(NovelCommentCache(), NovelCommentCache()) { listOf(other) }
        val book = novel("Shadow Slave")

        // Three at once, the way the reader's prefetch asks.
        val found = listOf(11.0, 12.0, 10.0).map { async { matcher.chapter(other, book, it)?.url } }.awaitAll()

        found shouldBe listOf("/c/11", "/c/12", "/c/10")
        matcher.chapter(other, book, 13.0) shouldBe null
        matcher.chapter(other, book, -1.0) shouldBe null
        other.chapterFetches.get() shouldBe 1
    }

    /**
     * A chapter list is a whole table of contents and a match is one novel's worth of fields, so the
     * two are not interchangeable entries in one cache: the more comment sources are installed, the
     * more matches there are to push the expensive thing out.
     */
    @Test
    fun `a crowd of matches does not cost a chapter list already fetched`() = runBlocking<Unit> {
        val own = FakeCommentSource(id = 1L, respond = empty)
        val other = FakeCommentSource(id = 2L, respond = empty).apply {
            search = { query -> listOf(novel(query)) }
            chapters = { listOf(sourceChapter(1f, "/c/1")) }
        }
        val matcher = NovelCommentMatcher(NovelCommentCache(capacity = 2), NovelCommentCache(capacity = 2)) {
            listOf(own, other)
        }
        val book = novel("Shadow Slave")

        matcher.chapter(other, book, 1.0)?.url shouldBe "/c/1"
        repeat(5) { matcher.find(own, novel("Some Other Novel $it"), NovelCommentScope.CHAPTER) }
        matcher.chapter(other, book, 1.0)?.url shouldBe "/c/1"

        other.chapterFetches.get() shouldBe 1
    }
}
