package leaf.novel.ui.reader.setting

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR

/**
 * The font families every Android system ships, which the reader can ask the WebView to use.
 *
 * [cssFamily] is the family's name in the system font configuration, which Chromium and
 * `Typeface.create` both resolve, so the settings preview and the page agree without bundling fonts.
 */
enum class NovelReaderFont(
    val titleRes: StringResource,
    val cssFamily: String?,
) {
    SYSTEM(MR.strings.label_default, null),
    SANS_SERIF(MR.strings.leaf_novel_font_sans_serif, "sans-serif"),
    SERIF(MR.strings.leaf_novel_font_serif, "serif"),
    MONOSPACE(MR.strings.leaf_novel_font_monospace, "monospace"),
    CONDENSED(MR.strings.leaf_novel_font_condensed, "sans-serif-condensed"),
    CASUAL(MR.strings.leaf_novel_font_casual, "casual"),
}
