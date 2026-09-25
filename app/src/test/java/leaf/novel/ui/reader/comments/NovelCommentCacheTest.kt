package leaf.novel.ui.reader.comments

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class NovelCommentCacheTest {

    /** A thread rewritten on every page and vote must still expire from when it was first fetched. */
    @Test
    fun `an update does not make an old thread new again`() {
        var now = 0L
        val cache = NovelCommentCache(clock = { now })

        cache.put("thread", "page 1")
        now = 50
        cache.put("thread", "page 2")
        now = 101

        cache.get<String>("thread", maxAge = 100) shouldBe null
        cache.put("thread", "fetched again")
        cache.get<String>("thread", maxAge = 100) shouldBe "fetched again"
    }

    @Test
    fun `lets the least recently used entry go first`() {
        val cache = NovelCommentCache(capacity = 2)

        cache.put("a", 1)
        cache.put("b", 2)
        cache.get<Int>("a")
        cache.put("c", 3)

        cache.get<Int>("a") shouldBe 1
        cache.get<Int>("b") shouldBe null
        cache.get<Int>("c") shouldBe 3
    }
}
