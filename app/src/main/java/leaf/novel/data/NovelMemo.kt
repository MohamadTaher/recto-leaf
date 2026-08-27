package leaf.novel.data

import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import leaf.novel.source.LocalNovelSource
import tachiyomi.domain.manga.model.Manga

/**
 * Recto Leaf stores its own data inside the existing `memo` JSON column, under a namespace of its
 * own, so novels need no schema migration and no mapper or backup changes. See plans/02 (D1, D6).
 */
private const val NAMESPACE = "rectoleaf"
private const val KEY_TYPE = "type"
private const val KEY_SCHEMA = "schema"
private const val KEY_EPUB_HASH = "epubHash"

private const val TYPE_NOVEL = "novel"

/** Shape version of the payload below, so it can change later without a migration. */
private const val SCHEMA_VERSION = 1

private fun JsonObject.namespace(): JsonObject? = this[NAMESPACE] as? JsonObject

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/** The Recto Leaf content type of this entry, or null if it was never stamped. */
fun JsonObject.rectoLeafType(): String? = namespace()?.string(KEY_TYPE)

/** MD5 of the `book.epub` this entry was imported from, or null. */
fun JsonObject.rectoLeafEpubHash(): String? = namespace()?.string(KEY_EPUB_HASH)

/**
 * Returns a copy of this memo carrying the novel flag, preserving every other namespace.
 *
 * [epubHash] is kept from the existing memo when null, so a refresh never drops it.
 */
fun JsonObject.withNovelMemo(epubHash: String? = null): JsonObject {
    val hash = epubHash ?: rectoLeafEpubHash()
    return buildJsonObject {
        this@withNovelMemo.forEach { (key, value) -> if (key != NAMESPACE) put(key, value) }
        put(
            NAMESPACE,
            buildJsonObject {
                put(KEY_TYPE, TYPE_NOVEL)
                put(KEY_SCHEMA, SCHEMA_VERSION)
                if (hash != null) put(KEY_EPUB_HASH, hash)
            },
        )
    }
}

/** Stamps the novel flag onto a source model. Called for every [SManga] the novel source returns. */
fun SManga.markAsNovel(epubHash: String? = null): SManga = apply {
    memo = memo.withNovelMemo(epubHash)
}

/**
 * The memo flag is authoritative because it survives the source being uninstalled; the source id is
 * the fallback for a row that has not been refreshed yet. See plans/02 (D6).
 */
fun Manga.isNovel(): Boolean = memo.rectoLeafType() == TYPE_NOVEL || source == LocalNovelSource.ID
