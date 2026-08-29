package leaf.novel.api

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter

/**
 * A source whose chapters are text documents rather than sequences of images.
 *
 * [NovelSource] widens [Source] instead of replacing it, so a novel source is browsable,
 * searchable, trackable and library-able through every existing screen with no changes. Only the
 * chapter-content half of [Source] is image-specific, and [getChapterContent] replaces it;
 * implementations are expected to throw from [Source.getPageList], as
 * `tachiyomi.source.local.LocalSource` already does.
 *
 * This lives in a fork-owned module rather than in `:source-api` so that the fork adds no files to
 * upstream. Extensions must take it as `compileOnly`: the extension class loader resolves
 * child-first, so a bundled copy would be a different class and `is NovelSource` would silently
 * return false.
 *
 * Loaders that do not know about this interface are unaffected: an extension whose source merely
 * implements one extra interface is an ordinary extension to them.
 */
interface NovelSource : Source {

    /**
     * Returns the readable content of [chapter] as an HTML document.
     */
    suspend fun getChapterContent(chapter: SChapter): NovelChapterContent
}

/**
 * The readable content of one chapter.
 *
 * [html] is a body fragment, not a document: the reader owns the shell, the `<head>` and the
 * injected stylesheet, which is what lets one set of reader styles apply whatever the content came
 * from.
 *
 * The consuming app does not trust this HTML. It sanitises the fragment before rendering it, so a
 * source should not rely on scripts, event handlers or remote resource loads surviving.
 */
class NovelChapterContent(
    /** Chapter HTML — the contents of `<body>`. */
    val html: String,
    /** The source document's own `<style>` and `<link rel="stylesheet">` elements, verbatim. */
    val head: String = "",
    /** Absolute URL the fragment's relative references resolve against. */
    val baseUrl: String? = null,
    /** Per-chapter title discovered while parsing, if any. */
    val title: String? = null,
)
