package leaf.novel.ui.reader.setting

import kotlinx.serialization.Serializable

/**
 * One find-and-replace rule the reader applies to a chapter before rendering it.
 *
 * Kept as a list in a single preference rather than a preference apiece, because the count is the
 * reader's to decide — see [leaf.novel.ui.reader.NovelTextReplacements].
 *
 * [enabled] exists so a rule can be turned off and kept. Working out which of six rules mangled a
 * chapter means switching them off one at a time, and deleting to test is how the other five get
 * lost.
 */
@Serializable
data class NovelTextReplacement(
    val title: String = "",
    val pattern: String = "",
    val replacement: String = "",
    /** Off by default, so a pattern containing `.` or `(` matches those characters. */
    val isRegex: Boolean = false,
    val caseSensitive: Boolean = false,
    val matchWholeWord: Boolean = false,
    val enabled: Boolean = true,
)
