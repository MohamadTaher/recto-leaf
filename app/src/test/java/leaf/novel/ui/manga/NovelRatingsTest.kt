package leaf.novel.ui.manga

import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import leaf.novel.api.NovelRating
import leaf.novel.api.novelRating
import org.junit.jupiter.api.Test

class NovelRatingsTest {

    @Test
    fun `weighs each site by how many rated it there, on five stars`() {
        // 4.0/5 from 100 readers, 9.0/10 (4.5/5) from 300: (4.0 * 100 + 4.5 * 300) / 400.
        val global = acrossSites(listOf(NovelRating(4.0, 5.0, 100), NovelRating(9.0, 10.0, 300)))!!
        global.value shouldBe (4.375 plusOrMinus 1e-9)
        global.maximum shouldBe 5.0
        global.count shouldBe 400
    }

    @Test
    fun `is not the plain average of the sites`() {
        // A 5.0 from two readers must not pull a 3.0 from a thousand up to 4.0.
        val global = acrossSites(listOf(NovelRating(5.0, 5.0, 2), NovelRating(3.0, 5.0, 1000)))!!
        global.value shouldBe (3.004 plusOrMinus 1e-3)
    }

    @Test
    fun `leaves out a site that gives no count rather than guessing its weight`() {
        acrossSites(listOf(NovelRating(1.0, 5.0, null), NovelRating(4.0, 5.0, 10))) shouldBe
            NovelRating(4.0, 5.0, 10)
        acrossSites(listOf(NovelRating(1.0, 5.0, null), NovelRating(4.0, 5.0, 0))) shouldBe null
        acrossSites(emptyList()) shouldBe null
    }

    @Test
    fun `leaves out a rating off its own scale`() {
        acrossSites(listOf(NovelRating(7.0, 5.0, 1000), NovelRating(4.0, 5.0, 10))) shouldBe
            NovelRating(4.0, 5.0, 10)
    }

    @Test
    fun `round trips through the memo without touching what else it holds`() {
        val manga = SManga.create()
        manga.memo = JsonObject(mapOf("other" to JsonPrimitive("kept")))

        manga.novelRating = NovelRating(8.6, 10.0, 1234)
        manga.novelRating shouldBe NovelRating(8.6, 10.0, 1234)
        manga.memo["other"] shouldBe JsonPrimitive("kept")

        manga.novelRating = NovelRating(4.2)
        manga.novelRating shouldBe NovelRating(4.2, 5.0, null)

        manga.novelRating = null
        manga.novelRating shouldBe null
        manga.memo shouldBe JsonObject(mapOf("other" to JsonPrimitive("kept")))
    }

    @Test
    fun `reads schema org ratings in the shapes sites publish them`() {
        // NovelFire: the rating on its own, every number a string.
        NovelRating.fromSchemaOrg(
            """{ "@context":"https://schema.org/","@type":"AggregateRating","ratingValue":"4.6",""" +
                """"ratingCount":"818","bestRating": "5","worstRating": "0.5",""" +
                """"itemReviewed":{"@type":"CreativeWorkSeries","name":"Shadow Slave"} }""",
        ) shouldBe NovelRating(4.6, 5.0, 818)
        // RoyalRoad: nested under the book, numbers as numbers.
        NovelRating.fromSchemaOrg(
            """{"@type":"Book","aggregateRating":{"@type":"AggregateRating","bestRating":5,"ratingValue":4.83,""" +
                """"worstRating":0.5,"ratingCount":17540}}""",
        ) shouldBe NovelRating(4.83, 5.0, 17540)
        // In a graph, with no top stated and only a review count.
        NovelRating.fromSchemaOrg(
            """{"@graph":[{"@type":"WebPage"},{"@type":"Book","aggregateRating":""" +
                """{"@type":"AggregateRating","ratingValue":"8.5","reviewCount":"12"}}]}""",
        ) shouldBe null
        NovelRating.fromSchemaOrg(
            """{"@graph":[{"@type":"WebPage"},{"@type":"Book","aggregateRating":""" +
                """{"@type":"AggregateRating","ratingValue":"4.5","reviewCount":"12"}}]}""",
        ) shouldBe NovelRating(4.5, 5.0, 12)
    }

    @Test
    fun `finds no schema org rating where there is none`() {
        NovelRating.fromSchemaOrg("""{"@type":"Book","name":"Shadow Slave"}""") shouldBe null
        NovelRating.fromSchemaOrg("""{"@type":"AggregateRating","ratingCount":"12"}""") shouldBe null
        NovelRating.fromSchemaOrg("not json {") shouldBe null
    }

    @Test
    fun `reads a rating off its own scale, or from nobody, as none`() {
        val manga = SManga.create()
        manga.novelRating = NovelRating(6.0, 5.0, 10)
        manga.novelRating shouldBe null
        manga.novelRating = NovelRating(0.0, 10.0, 0)
        manga.novelRating shouldBe null
    }
}
