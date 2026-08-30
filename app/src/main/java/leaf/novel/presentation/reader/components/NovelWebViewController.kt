package leaf.novel.presentation.reader.components

import android.webkit.WebView
import androidx.compose.runtime.Stable

/**
 * A handle on the WebView showing the current chapter, for the things the reader drives from
 * outside it.
 *
 * The screen holds one and [NovelChapterWebView] attaches itself for as long as its view lives, so
 * a command that arrives while no chapter is up is dropped rather than reaching a destroyed view.
 *
 * It grows one method at a time. Search and auto scroll want a handle too, and will add what they
 * need rather than this trying to anticipate them.
 */
@Stable
class NovelWebViewController {

    private var webView: WebView? = null

    internal fun attach(view: WebView) {
        webView = view
    }

    internal fun detach() {
        webView = null
    }

    /** Scrolls back by one viewport, animated, as the page-up key would. */
    fun pageUp() {
        webView?.pageUp(false)
    }

    /** Scrolls on by one viewport. */
    fun pageDown() {
        webView?.pageDown(false)
    }
}
