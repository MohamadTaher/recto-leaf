package leaf.novel.presentation.reader.components

import android.webkit.WebView
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

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
    private var activeQuery: String? = null

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

    /**
     * Scrolls on by [dy] pixels.
     *
     * Goes through the view the way a finger does, so `onScrollChanged` fires and reading progress
     * keeps being recorded while auto scroll runs.
     */
    fun scrollBy(dy: Int) {
        webView?.scrollBy(0, dy)
    }

    /** Whether there is any page left below, so auto scroll can stop at the end of a chapter. */
    val canScrollDown: Boolean get() = webView?.canScrollVertically(1) == true

    /**
     * Matches from the last search: which one is showing, and how many there are.
     *
     * Reported by the view as it counts, so it climbs while a long chapter is scanned.
     */
    var findMatches: FindMatches by mutableStateOf(FindMatches.NONE)
        internal set

    /**
     * Searches the chapter for [query], highlighting every match and scrolling to the first.
     *
     * This is the view's own find-in-page, which is a browser feature rather than a scripting one,
     * so it works with JavaScript off — the reason the reader can keep it off at all.
     */
    fun find(query: String) {
        activeQuery = query
        webView?.findAllAsync(query)
    }

    /** Steps to the next match, wrapping at the ends. */
    fun findNext(forward: Boolean) {
        webView?.findNext(forward)
    }

    /** Drops the highlighting. A stale highlight outliving its search is the bug to avoid here. */
    fun clearFind() {
        activeQuery = null
        webView?.clearMatches()
        findMatches = FindMatches.NONE
    }

    /**
     * Runs the search again after the document has been rebuilt.
     *
     * Changing a style setting reloads the page, which takes the highlighting and the count with
     * it. Without this the bar would sit there holding a query with nothing highlighted and no
     * matches counted, which reads as a search that found nothing.
     */
    internal fun reapplyFind() {
        findMatches = FindMatches.NONE
        activeQuery?.let { webView?.findAllAsync(it) }
    }
}

/** How many matches a search found and which of them is showing, both as the view reports them. */
data class FindMatches(val activeOrdinal: Int, val total: Int) {
    companion object {
        val NONE = FindMatches(activeOrdinal = 0, total = 0)
    }
}
