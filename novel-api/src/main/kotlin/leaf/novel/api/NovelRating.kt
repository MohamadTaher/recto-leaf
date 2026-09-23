package leaf.novel.api

import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * What a site's readers think of a novel, as the site itself totals it.
 *
 * The site's own final figure, never one worked out from its reviews: a site knows every rating it
 * was given, and a source that averaged the reviews it happened to fetch would publish a different,
 * worse number under the site's name. [count] is the other half of that figure, and it is what lets
 * the app weigh one site against another — a 4.9 from twelve readers says less than a 4.2 from ten
 * thousand, and the app's rating across every site says so.
 *
 * Carried in [SManga.memo], which is the channel Mihon keeps for exactly this — app-defined data a
 * source fills in — rather than in a new method. So a source sets it while parsing the details it
 * was already fetching, Mihon stores and backs it up with the rest of the novel, and the rating is
 * on the screen before anything has been asked of the network.
 */
data class NovelRating(
    val value: Double,
    /** The top of the site's own scale — 5, 10, 100. The app shows every rating on five stars. */
    val maximum: Double = 5.0,
    /**
     * How many people rated the novel, as the site reports it.
     *
     * Null when the site does not say. Such a rating is still shown, but it has no weight to carry
     * into the rating across sites, so it is left out of it rather than guessed at.
     */
    val count: Int? = null,
) {
    /**
     * Whether this is a rating at all: on its own scale, and given by somebody. A site that reports
     * 0 from 0 ratings has not rated the novel zero, it has not rated it.
     */
    val valid: Boolean
        get() = value.isFinite() && maximum.isFinite() && maximum > 0 && value in 0.0..maximum &&
            (count == null || count > 0)

    companion object {
        /** Namespaced, as [SManga.memo] asks, so no other app's key or a source's own can collide. */
        const val MEMO_KEY = "leaf.rating"

        /** The rating in [memo], for an app holding the memo without an [SManga] around it. */
        fun read(memo: JsonObject): NovelRating? {
            val json = memo[MEMO_KEY] as? JsonObject ?: return null
            val value = json["value"]?.jsonPrimitive?.doubleOrNull ?: return null
            val maximum = json["maximum"]?.jsonPrimitive?.doubleOrNull ?: return null
            val count = json["count"]?.jsonPrimitive?.intOrNull
            return NovelRating(value, maximum, count).takeIf { it.valid }
        }

        /**
         * The rating a page states for search engines, from the text of one of its
         * `<script type="application/ld+json">` elements.
         *
         * Most sites publish their figure as a schema.org `AggregateRating` there, and it is the
         * same figure they show, so reading it spares a source from depending on markup that changes
         * with every redesign. Found wherever it sits in the document, since sites nest it under the
         * book or state it on its own. Null when there is none, or the text is not JSON.
         */
        fun fromSchemaOrg(jsonLd: String): NovelRating? {
            val root = runCatching { Json.parseToJsonElement(jsonLd) }.getOrNull() ?: return null
            return aggregates(root).firstNotNullOfOrNull { rating ->
                // schema.org gives numbers as numbers or strings, and takes 5 as the top when unsaid.
                fun number(key: String) = (rating[key] as? JsonPrimitive)?.contentOrNull?.trim()?.toDoubleOrNull()
                val value = number("ratingValue") ?: return@firstNotNullOfOrNull null
                val count = number("ratingCount") ?: number("reviewCount")
                NovelRating(value, number("bestRating") ?: 5.0, count?.toInt()).takeIf { it.valid }
            }
        }

        private fun aggregates(element: JsonElement): Sequence<JsonObject> = when (element) {
            is JsonObject -> sequence {
                val type = element["@type"]
                if ((type as? JsonPrimitive)?.contentOrNull == "AggregateRating") yield(element)
                element.values.forEach { yieldAll(aggregates(it)) }
            }
            is JsonArray -> element.asSequence().flatMap(::aggregates)
            else -> emptySequence()
        }
    }
}

/**
 * The novel's [NovelRating], read from and written to [SManga.memo].
 *
 * Setting it keeps whatever else the memo holds, and null removes it. A rating that is not
 * [NovelRating.valid] reads as none, so an app never draws seven stars out of five because a site
 * changed its markup.
 */
var SManga.novelRating: NovelRating?
    get() = NovelRating.read(memo)
    set(rating) {
        val rest = memo - NovelRating.MEMO_KEY
        memo = if (rating == null) {
            JsonObject(rest)
        } else {
            JsonObject(
                rest + (
                    NovelRating.MEMO_KEY to buildJsonObject {
                        put("value", rating.value)
                        put("maximum", rating.maximum)
                        rating.count?.let { put("count", it) }
                    }
                    ),
            )
        }
    }
