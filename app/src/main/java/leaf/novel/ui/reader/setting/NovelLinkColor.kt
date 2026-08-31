package leaf.novel.ui.reader.setting

import androidx.annotation.ColorInt
import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR

/**
 * What colour a link in the book's own text is drawn in.
 *
 * [DEFAULT] carries no colour: the stylesheet goes on deriving one from the reader theme, which is
 * what the reader did before this was a setting. Every other entry is a fixed mid-tone, chosen so
 * that one palette stays legible on the black, grey and white themes without a per-theme table.
 *
 * Stored with `getEnum`, so the constant names are persisted: reorder them freely, but renaming one
 * loses whatever a reader had chosen.
 */
enum class NovelLinkColor(
    val titleRes: StringResource,
    @ColorInt val argb: Int?,
) {
    DEFAULT(MR.strings.label_default, null),
    BLUE(MR.strings.leaf_novel_color_blue, 0xFF4A90D9.toInt()),
    TEAL(MR.strings.leaf_novel_color_teal, 0xFF26A69A.toInt()),
    GREEN(MR.strings.leaf_novel_color_green, 0xFF4CAF50.toInt()),
    AMBER(MR.strings.leaf_novel_color_amber, 0xFFD69E2E.toInt()),
    RED(MR.strings.leaf_novel_color_red, 0xFFE05252.toInt()),
    PURPLE(MR.strings.leaf_novel_color_purple, 0xFFA06CD5.toInt()),
}
