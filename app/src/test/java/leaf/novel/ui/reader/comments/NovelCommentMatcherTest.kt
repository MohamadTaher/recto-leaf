package leaf.novel.ui.reader.comments

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import leaf.novel.api.NovelCommentCapabilities
import leaf.novel.api.NovelCommentChapterSource
import leaf.novel.api.NovelCommentPage
import leaf.novel.api.NovelCommentRequest
import leaf.novel.api.NovelCommentScope
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes

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

        val found = matcher.find(own, novel("Shadow Slave"), NovelCommentScope.CHAPTER).toList()

        found.map { it.source.id to it.novel.url } shouldBe listOf(2L to "/found")
        own.searches shouldBe emptyList()
    }

    /** A sequel that wins on raw similarity must not hide the title the rule accepts. */
    @Test
    fun `finds the same title even when a closer-looking one ranks above it`() = runBlocking<Unit> {
        val own = FakeCommentSource(id = 1L, respond = empty)
        val other = FakeCommentSource(id = 2L, respond = empty).apply {
            search = { listOf(novel("shadow slave", url = "/original"), novel("SHADOW SLAVE 2", url = "/sequel")) }
        }
        val matcher = NovelCommentMatcher(NovelCommentCache(), NovelCommentCache()) { listOf(own, other) }

        matcher.find(own, novel("SHADOW SLAVE"), NovelCommentScope.CHAPTER).toList()
            .map { it.novel.url } shouldBe listOf("/original")
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

        matcher.find(own, novel("Shadow Slave"), NovelCommentScope.CHAPTER).toList() shouldBe emptyList()
        novelOnly.searches shouldBe emptyList()
        matcher.find(own, novel("Shadow Slave"), NovelCommentScope.NOVEL).toList()
            .map { it.source.id } shouldBe listOf(2L)
    }

    @Test
    fun `remembers an answer but not a failure`() = runBlocking<Unit> {
        val own = FakeCommentSource(id = 1L, respond = empty)
        var failing = true
        val other = FakeCommentSource(id = 2L, respond = empty).apply {
            search = { if (failing) error("offline") else emptyList() }
        }
        val matcher = NovelCommentMatcher(NovelCommentCache(), NovelCommentCache()) { listOf(own, other) }

        matcher.find(own, novel("Shadow Slave"), NovelCommentScope.CHAPTER).toList() shouldBe emptyList()
        failing = false
        matcher.find(own, novel("Shadow Slave"), NovelCommentScope.CHAPTER).toList() shouldBe emptyList()
        matcher.find(own, novel("Shadow Slave"), NovelCommentScope.CHAPTER).toList() shouldBe emptyList()

        // The failure was asked again; the empty answer that followed it was not.
        other.searches.size shouldBe 2
    }

    /**
     * Why this emits instead of returning a list. A site that paces itself to one request every few
     * seconds is slow by design, and gathering every answer before handing any of them over meant
     * one such site kept every other site's comments off the screen.
     */
    @Test
    fun `a source that answers quickly is handed over while a slow one is still being asked`() = runBlocking<Unit> {
        val own = FakeCommentSource(id = 1L, respond = empty)
        val quick = FakeCommentSource(id = 2L, respond = empty).apply {
            search = { listOf(novel("Shadow Slave", url = "/quick")) }
        }
        val slow = FakeCommentSource(id = 3L, respond = empty).apply {
            search = {
                delay(300)
                listOf(novel("Shadow Slave", url = "/slow"))
            }
        }
        val matcher = NovelCommentMatcher(NovelCommentCache(), NovelCommentCache()) { listOf(own, quick, slow) }

        val started = System.currentTimeMillis()
        val arrivals = mutableListOf<Pair<Long, Long>>()
        matcher.find(own, novel("Shadow Slave"), NovelCommentScope.CHAPTER).collect {
            arrivals += it.source.id to (System.currentTimeMillis() - started)
        }

        // Both are found, the quick one first — and in hand well before the slow one answered,
        // which is exactly what waiting for the whole set could not do.
        arrivals.map { it.first } shouldBe listOf(2L, 3L)
        (arrivals.first().second < 150) shouldBe true
        (arrivals.last().second >= 300) shouldBe true
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
     * A site that pages its table of contents would otherwise be walked end to end — over a hundred
     * requests for a long novel — to find one chapter.
     */
    @Test
    fun `asks a source that can find a chapter itself instead of fetching its list`() = runBlocking<Unit> {
        val asked = mutableListOf<Double>()
        val other = object : FakeCommentSource(id = 2L, respond = empty), NovelCommentChapterSource {
            override suspend fun getCommentChapter(novel: SManga, number: Double): SChapter? {
                asked += number
                return if (number == 12.0) sourceChapter(12f, "/c/12") else null
            }
        }.apply { chapters = { listOf(sourceChapter(12f, "/listed/12")) } }
        val matcher = NovelCommentMatcher(NovelCommentCache(), NovelCommentCache()) { listOf(other) }
        val book = novel("Shadow Slave")

        matcher.chapter(other, book, 12.0)?.url shouldBe "/c/12"
        matcher.chapter(other, book, 13.0) shouldBe null
        matcher.chapter(other, book, 12.0)?.url shouldBe "/c/12"
        matcher.chapter(other, book, 13.0) shouldBe null

        // A chapter found is remembered. A missing one is not, since the site may publish it while
        // someone reads — and the list is never fetched.
        asked shouldBe listOf(12.0, 13.0, 13.0)
        other.chapterFetches.get() shouldBe 0
    }

    /**
     * A novel still coming out gains chapters while someone reads, so a number the list lacks is
     * looked for again once the list is old — and only then, so reading past what another site has
     * does not fetch its whole table of contents on every chapter turn.
     */
    @Test
    fun `looks again for a chapter missing from an old list, but not for one it has`() = runBlocking<Unit> {
        var now = 0L
        var published = listOf(sourceChapter(11f, "/c/11"))
        val other = FakeCommentSource(id = 2L, respond = empty).apply { chapters = { published } }
        val matcher = NovelCommentMatcher(NovelCommentCache(), NovelCommentCache(clock = { now })) { listOf(other) }
        val book = novel("Shadow Slave")

        matcher.chapter(other, book, 12.0) shouldBe null
        published = listOf(sourceChapter(12f, "/c/12"), sourceChapter(11f, "/c/11"))
        matcher.chapter(other, book, 12.0) shouldBe null
        other.chapterFetches.get() shouldBe 1

        now += 11.minutes.inWholeMilliseconds
        matcher.chapter(other, book, 11.0)?.url shouldBe "/c/11"
        other.chapterFetches.get() shouldBe 1
        matcher.chapter(other, book, 12.0)?.url shouldBe "/c/12"
        other.chapterFetches.get() shouldBe 2
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
        repeat(5) { matcher.find(own, novel("Some Other Novel $it"), NovelCommentScope.CHAPTER).toList() }
        matcher.chapter(other, book, 1.0)?.url shouldBe "/c/1"

        other.chapterFetches.get() shouldBe 1
    }
}
