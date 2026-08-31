package leaf.novel.ui.reader.setting

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR

/**
 * How large an illustration inside a chapter is allowed to be.
 *
 * [FIT_WIDTH] is the rule the stylesheet has always hardcoded, so it is the default and this stage
 * only turns a constant into a choice. [ORIGINAL] is deliberately allowed to be wider than the page
 * — the page scrolls sideways to reach it, which is what the setting means, and clamping it would
 * make it a second copy of [FIT_WIDTH].
 */
enum class NovelImageSize(val titleRes: StringResource, val css: String) {
    FIT_WIDTH(MR.strings.leaf_novel_image_fit_width, "max-width: 100%; height: auto;"),
    FIT_SCREEN(
        MR.strings.leaf_novel_image_fit_screen,
        "max-width: 100%; max-height: 100vh; width: auto; height: auto;",
    ),
    ORIGINAL(MR.strings.leaf_novel_image_original, "max-width: none; height: auto;"),
}
