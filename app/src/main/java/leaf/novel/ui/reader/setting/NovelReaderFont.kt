package leaf.novel.ui.reader.setting

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR

/** The generic Android font families the reader can ask the WebView to use. */
enum class NovelReaderFont(
    val titleRes: StringResource,
    val cssFamily: String?,
) {
    SYSTEM(MR.strings.label_default, null),
    SANS_SERIF(MR.strings.leaf_novel_font_sans_serif, "sans-serif"),
    SERIF(MR.strings.leaf_novel_font_serif, "serif"),
    MONOSPACE(MR.strings.leaf_novel_font_monospace, "monospace"),
}
