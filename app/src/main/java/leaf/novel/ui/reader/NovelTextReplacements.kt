package leaf.novel.ui.reader

import kotlinx.serialization.json.Json
import leaf.novel.ui.reader.setting.NovelTextReplacement
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Applies the reader's own find-and-replace rules to a chapter.
 *
 * Adapted from `RegexReplacementsProcessor` in `tsundoku-otaku/tsundoku`, Apache-2.0 like this fork.
 * The shape is theirs — one JSON list, a compile cache keyed on it, a rule that will not compile
 * skipped rather than fatal — because it is a good shape and there is nothing to gain by arriving
 * at a different one.
 *
 * It takes the rules as a string rather than a preference store, which is the one thing done
 * differently: it keeps the whole object free of Android and of the store, so the tests need no
 * mocking framework, as with every other piece of arithmetic in this reader.
 */
object NovelTextReplacements {

    /**
     * Every enabled rule applied in order, or [html] untouched when there are none.
     *
     * Runs before the reading aids, so a rule matches what the book actually said rather than what
     * this reader has since done to it. That also means a rule can break the markup — replacing an
     * opening tag with nothing is a thing a person may want and a thing that will look wrong — but
     * the damage stops at this chapter, because jsoup reparses afterwards and closes what was left
     * open.
     */
    fun apply(html: String, rulesJson: String): String {
        if (rulesJson.isBlank() || rulesJson == EMPTY_RULES) return html

        var result = html
        compile(rulesJson).forEach { (regex, replacement) ->
            result = runCatching { regex.replace(result, replacement) }
                .onFailure { logcat(LogPriority.WARN, it) { "Text replacement failed" } }
                .getOrDefault(result)
        }
        return result
    }

    /** Parses the rules for the settings screen, which edits them as objects rather than as text. */
    fun parse(rulesJson: String): List<NovelTextReplacement> {
        if (rulesJson.isBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<NovelTextReplacement>>(rulesJson) }
            .onFailure { logcat(LogPriority.WARN, it) { "Could not read the text replacement rules" } }
            .getOrDefault(emptyList())
    }

    fun encode(rules: List<NovelTextReplacement>): String = json.encodeToString(rules)

    /**
     * The rules as compiled patterns, remembered against the text they came from.
     *
     * Keyed on the whole list rather than rule by rule: editing any of them invalidates all of
     * them, which is what should happen and needs no bookkeeping to arrange. Compiling is the
     * expensive part and this runs on every render.
     */
    private fun compile(rulesJson: String): List<Pair<Regex, String>> = synchronized(cache) {
        cache.getOrPut(rulesJson) {
            parse(rulesJson).mapNotNull { rule ->
                if (!rule.enabled || rule.pattern.isBlank()) return@mapNotNull null
                runCatching { rule.toRegex() to rule.replacement }
                    .onFailure { logcat(LogPriority.WARN, it) { "Bad pattern in '${rule.title}'" } }
                    .getOrNull()
            }
        }
    }

    private fun NovelTextReplacement.toRegex(): Regex {
        val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        if (isRegex) return Regex(pattern, options)

        // Escaped, because the switch being off is a promise that the pattern means itself.
        val literal = Regex.escape(pattern)
        // Unicode classes rather than `\b`, which is defined against ASCII word characters and so
        // finds a boundary in the middle of an accented word — matching `caf` inside `café`.
        // Neither spelling helps a language written without spaces, where every neighbour is a
        // letter and the boundary never holds; there the rule is simply left switched off.
        val bounded = if (matchWholeWord) "(?<![\\p{L}\\p{N}_])(?:$literal)(?![\\p{L}\\p{N}_])" else literal
        return Regex(bounded, options)
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** What an empty list serializes to, worth recognising before parsing it. */
    private const val EMPTY_RULES = "[]"

    /** Few enough that a reader switching between two rule sets never recompiles either. */
    private const val CACHE_SIZE = 8

    private val cache = object : LinkedHashMap<String, List<Pair<Regex, String>>>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, List<Pair<Regex, String>>>) = size > CACHE_SIZE
    }
}
