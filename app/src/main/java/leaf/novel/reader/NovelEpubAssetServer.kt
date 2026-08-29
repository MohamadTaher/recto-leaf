package leaf.novel.reader

import android.net.Uri
import android.webkit.WebResourceResponse
import leaf.novel.epub.EpubPath
import java.io.ByteArrayInputStream

/**
 * Answers the WebView's resource requests out of the novel, and refuses everything else.
 *
 * The routing decisions — [pathFor], [isRemote], [mimeTypeOf] — are pure and live in the companion,
 * so they can be tested on the JVM; only the `WebResourceResponse` plumbing needs Android.
 */
class NovelEpubAssetServer(
    private val provider: NovelContentProvider,
) {

    fun handle(url: Uri): WebResourceResponse? {
        val target = url.toString()
        val path = pathFor(target)
            // Only remote schemes are refused outright. Everything else — `data:` images the book
            // inlines above all — is left to the WebView, which already has `allowFileAccess` and
            // `allowContentAccess` off and `blockNetworkLoads` on.
            ?: return if (isRemote(target)) blocked() else null

        val stream = provider.resourceStream(path) ?: return notFound()
        return WebResourceResponse(mimeTypeOf(path), "utf-8", stream)
    }

    private fun blocked() = emptyResponse(statusCode = 403, reason = "Blocked")

    private fun notFound() = emptyResponse(statusCode = 404, reason = "Not Found")

    private fun emptyResponse(statusCode: Int, reason: String) = WebResourceResponse(
        "text/plain",
        "utf-8",
        statusCode,
        reason,
        emptyMap(),
        ByteArrayInputStream(ByteArray(0)),
    )

    companion object {

        /** The archive entry a virtual-origin URL refers to, or null when the URL is not ours. */
        fun pathFor(url: String): String? {
            if (!url.startsWith(VIRTUAL_ORIGIN)) return null
            val relative = url.removePrefix(VIRTUAL_ORIGIN).substringBefore('#').substringBefore('?')
            return EpubPath.percentDecode(relative).takeIf { it.isNotEmpty() }
        }

        /** True for a scheme that could reach the network, and so must be refused rather than passed on. */
        fun isRemote(url: String): Boolean =
            url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

        fun mimeTypeOf(path: String): String = when (path.substringAfterLast('.').lowercase()) {
            "html", "htm", "xhtml" -> "text/html"
            "css" -> "text/css"
            "js" -> "application/javascript"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            else -> "application/octet-stream"
        }
    }
}
