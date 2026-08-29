package leaf.novel.ui.reader

import leaf.novel.ui.reader.loader.NovelEpubAssetServer
import leaf.novel.ui.reader.loader.VIRTUAL_ORIGIN
import org.jsoup.Jsoup
import org.jsoup.safety.Safelist

/**
 * Makes third-party chapter HTML safe to hand to the reader's WebView.
 *
 * An extension is code the app did not compile, parsing a page the app does not control, and its
 * output is rendered in a WebView. Extensions are expected to strip their own site's furniture —
 * they know it best — but the app cannot depend on that having happened.
 *
 * The filtering itself is jsoup's [Safelist], which is already a dependency and far better tested
 * than a hand-written pass: it drops every element and attribute not on the allowed list (so
 * `script`, `iframe`, `style`, forms, `on*` handlers and `javascript:` URLs all go), and resolves
 * what remains against the base URL. Only the image rewrite below is ours, because no library knows
 * about the reader's virtual origin.
 *
 * Kept free of Android types so it can be tested on the JVM.
 *
 * Note what this is *not* relied upon for. `NovelChapterWebView` already disables JavaScript, file
 * and content access, and sets `blockNetworkLoads`. Sanitising as well means a bug in any one of
 * those settings is not on its own enough to matter.
 */
object NovelHtmlSanitizer {

    private val SAFELIST: Safelist = Safelist.relaxed()
        // Images the source inlined directly. They are already local and cannot reach the network,
        // and `relaxed` would otherwise drop them for having a non-http protocol.
        .addProtocols("img", "src", "data")
        // Scene breaks. `relaxed` omits `hr`, and it is the one omission that loses content rather
        // than formatting: the cleaner unwraps a disallowed element and keeps its text, but `hr` has
        // no text, so a scene break would disappear with nothing left in its place.
        .addTags("hr")

    private val UNRESERVED = (('a'..'z') + ('A'..'Z') + ('0'..'9')).toSet() + setOf('-', '_', '.', '~')

    /**
     * Returns a body fragment safe to render.
     *
     * [baseUrl] is the chapter's own absolute URL, used to resolve relative references before they
     * are rewritten; a fragment with relative links and no base simply loses them.
     */
    fun sanitize(html: String, baseUrl: String?): String {
        val base = baseUrl.orEmpty()
        val body = Jsoup.parseBodyFragment(html, base).body()

        // The cleaner drops these elements but keeps the text of anything it merely unwraps, so they
        // are removed outright first to guarantee no stylesheet or script body survives as prose.
        body.select("script, style, noscript, template, iframe").remove()

        // <source> carries its own srcset, which a browser prefers over the <img> it wraps, so it
        // would bypass the rewrite below. A `srcset` left on the <img> itself needs no handling:
        // the safelist does not allow the attribute, so the clean at the end drops it.
        body.select("source").remove()

        body.select("img").forEach { image ->
            if (image.attr("src").startsWith("data:", ignoreCase = true)) return@forEach

            val absolute = image.absUrl("src")
            if (isHttp(absolute)) {
                image.attr("src", VIRTUAL_ORIGIN + percentEncode(absolute))
            } else {
                image.remove()
            }
        }

        return Jsoup.clean(body.html(), base, SAFELIST)
    }

    private fun isHttp(url: String): Boolean =
        url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

    /**
     * Escapes everything outside the unreserved set, so the result survives
     * [NovelEpubAssetServer.pathFor] — which strips at the first `?` or `#` before decoding — and
     * comes back out of [leaf.novel.data.epub.EpubPath.percentDecode] byte-identical.
     *
     * Hand-rolled rather than `Uri.encode` for the same reason [leaf.novel.data.epub.EpubPath] decodes by
     * hand: this has to be testable on the JVM, where the Android framework is not available.
     */
    fun percentEncode(value: String): String = buildString(value.length) {
        value.toByteArray(Charsets.UTF_8).forEach { byte ->
            val char = byte.toInt().toChar()
            if (char in UNRESERVED) {
                append(char)
            } else {
                append('%').append("%02X".format(byte.toInt() and 0xFF))
            }
        }
    }
}
