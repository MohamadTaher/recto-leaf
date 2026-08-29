package leaf.novel.ui.reader.setting

/**
 * Every visual setting the reader's stylesheet needs, resolved into one value.
 *
 * It exists so [leaf.novel.ui.reader.NovelReaderCss.document] takes one argument rather than the
 * dozen-plus that text styling, spacing and margins would otherwise add to its signature, and so
 * the document the screen builds keys on a single value that compares by content.
 *
 * Deliberately plain data. A data class whose properties are all `val` primitives is already
 * inferred stable by the Compose compiler, so it needs no annotation, and staying clear of Compose
 * and Android types is what keeps [leaf.novel.ui.reader.NovelReaderCss] testable on the JVM.
 */
data class NovelReaderStyle(
    val fontSizePx: Int,
)
