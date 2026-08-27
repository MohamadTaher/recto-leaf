package leaf.novel.data

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import leaf.novel.source.LocalNovelSource
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga

/**
 * The `memo` flag is what tells a novel from a manga everywhere in the fork (D6), and it has to
 * survive round trips through backup and refresh without disturbing anyone else's namespace.
 */
class NovelMemoTest {

    private val empty = JsonObject(emptyMap())

    @Test
    fun `stamps the novel type`() {
        empty.withNovelMemo().rectoLeafType() shouldBe "novel"
    }

    @Test
    fun `stores the epub hash when given one`() {
        empty.withNovelMemo("abc123").rectoLeafEpubHash() shouldBe "abc123"
    }

    @Test
    fun `keeps the existing hash when none is supplied`() {
        val stamped = empty.withNovelMemo("abc123")
        stamped.withNovelMemo().rectoLeafEpubHash() shouldBe "abc123"
    }

    @Test
    fun `replaces the hash when a new one is supplied`() {
        val stamped = empty.withNovelMemo("abc123")
        stamped.withNovelMemo("def456").rectoLeafEpubHash() shouldBe "def456"
    }

    @Test
    fun `omits the hash key entirely when there is none`() {
        empty.withNovelMemo().rectoLeafEpubHash() shouldBe null
    }

    @Test
    fun `preserves other namespaces`() {
        val foreign = buildJsonObject {
            put("mihon", buildJsonObject { put("something", "kept") })
            put("topLevel", 7)
        }

        val stamped = foreign.withNovelMemo("abc123")

        stamped["mihon"] shouldBe foreign["mihon"]
        stamped["topLevel"] shouldBe foreign["topLevel"]
        stamped.rectoLeafType() shouldBe "novel"
    }

    @Test
    fun `reports no type for an unstamped memo`() {
        empty.rectoLeafType() shouldBe null
    }

    @Test
    fun `survives a namespace of the wrong shape`() {
        val malformed = buildJsonObject { put("rectoleaf", "not an object") }
        malformed.rectoLeafType() shouldBe null
        malformed.rectoLeafEpubHash() shouldBe null
    }

    @Test
    fun `survives a type field of the wrong shape`() {
        val malformed = buildJsonObject {
            put("rectoleaf", buildJsonObject { put("type", JsonPrimitive(42)) })
        }
        malformed.rectoLeafType() shouldBe "42"
    }

    @Test
    fun `isNovel is true from the memo flag alone`() {
        mangaWith(source = 1234L, memo = empty.withNovelMemo()).isNovel() shouldBe true
    }

    @Test
    fun `isNovel is true from the source id alone`() {
        // The fallback covers a row inserted before its first refresh.
        mangaWith(source = LocalNovelSource.ID, memo = empty).isNovel() shouldBe true
    }

    @Test
    fun `isNovel is false for an ordinary manga`() {
        mangaWith(source = 1234L, memo = empty).isNovel() shouldBe false
    }

    @Test
    fun `isNovel is false for a local manga`() {
        mangaWith(source = 0L, memo = empty).isNovel() shouldBe false
    }

    private fun mangaWith(source: Long, memo: JsonObject): Manga =
        Manga.create().copy(id = 1L, source = source, url = "u", title = "t", memo = memo)
}
