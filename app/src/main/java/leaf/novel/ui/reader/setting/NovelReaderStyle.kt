package leaf.novel.ui.reader.setting

/**
 * Every visual setting the reader's stylesheet needs, resolved into one value.
 *
 * It exists so [leaf.novel.ui.reader.NovelReaderCss.document] takes one argument rather than the
 * dozen-plus that text styling, spacing and margins would otherwise add to its signature, and so
 * the document the screen builds keys on a single value that compares by content.
 *
 * Values are the preferences as stored, not as rendered: the stylesheet owns every mapping from one
 * to the other, and owns it in one place so it can be tested there.
 *
 * No property carries a default, deliberately. Every field has to be named where the style is built
 * from preferences, so a field added here and left unwired fails to compile rather than shipping as
 * a setting that quietly does nothing. Test call sites get their defaults from a local helper.
 *
 * Deliberately plain data otherwise. A data class whose properties are all `val` primitives is
 * already inferred stable by the Compose compiler, so it needs no annotation, and staying clear of
 * Compose and Android types is what keeps [leaf.novel.ui.reader.NovelReaderCss] testable on the JVM.
 */
data class NovelReaderStyle(
    val fontSizePx: Int,
    val font: NovelReaderFont,
    val bold: Boolean,
    val italic: Boolean,
    val underline: Boolean,
    val shadow: Boolean,
    val antialias: Boolean,
    val justified: Boolean,
    val hyphenation: Boolean,
    val paragraphSpacing: Int,
    val lineSpacing: Int,
    val fontSpacing: Int,
    val fontScale: Int,
    val marginLeft: Int,
    val marginRight: Int,
    val marginTop: Int,
    val marginBottom: Int,
    val highlightFirstWord: Boolean,
    val highlightInitialChars: Boolean,
    val paragraphIndent: Int,
    val trimBlankLines: Boolean,
    val linkColor: NovelLinkColor,
    val noteColor: NovelLinkColor,
    val disableBookCss: Boolean,
    val useBookFonts: Boolean,
    val inlineFootnotes: Boolean,
    val printPageNumbers: Boolean,
    val imageSize: NovelImageSize,
    val centerImages: Boolean,
    val paged: Boolean,
    val dualPageLayout: Boolean,
    val trimTopBlankLines: Boolean,
    val textReplacements: String,
)
