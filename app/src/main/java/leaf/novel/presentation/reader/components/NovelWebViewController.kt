package leaf.novel.presentation.reader.components

import android.webkit.WebView
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import leaf.novel.ui.reader.NovelStatusLine

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
    private var speechHighlight: SpeechHighlight? = null
    private var speechMatchesToAdvance = 0
    private var chapterAppender: ((String) -> Unit)? = null
    private var chapterScroller: ((Long, Int) -> Unit)? = null
    private var chapterPruner: ((Long) -> Unit)? = null

    /**
     * Moving by whole screenfuls, which the view supplies because only it knows which axis the
     * chapter is laid out on and how much of a line to leave behind.
     */
    private var turner: ((pages: Int) -> Unit)? = null

    internal fun attach(
        view: WebView,
        turnPages: (pages: Int) -> Unit,
        appendChapter: (String) -> Unit,
        scrollToChapter: (Long, Int) -> Unit,
        pruneBeforeChapter: (Long) -> Unit,
    ) {
        webView = view
        turner = turnPages
        chapterAppender = appendChapter
        chapterScroller = scrollToChapter
        chapterPruner = pruneBeforeChapter
    }

    internal fun detach() {
        webView = null
        turner = null
        chapterAppender = null
        chapterScroller = null
        chapterPruner = null
    }

    /** Back one page, which in a paged chapter is one column and otherwise one viewport. */
    fun pageUp() {
        turner?.invoke(-1)
    }

    /** On one page. */
    fun pageDown() {
        turner?.invoke(1)
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

    /** Adds one already fetched chapter beneath the rolling document. */
    fun appendChapter(section: String) {
        chapterAppender?.invoke(section)
    }

    /** Moves to a chapter section already present in the rolling document. */
    fun scrollToChapter(chapterId: Long, percent: Int = 0) {
        chapterScroller?.invoke(chapterId, percent)
    }

    /** Drops sections older than the one chapter kept behind the reader. */
    fun pruneBeforeChapter(chapterId: Long) {
        chapterPruner?.invoke(chapterId)
    }

    /** Whether there is any page left below, so auto scroll can stop at the end of a chapter. */
    val canScrollDown: Boolean get() = webView?.canScrollVertically(1) == true

    /**
     * Which screenful of the chapter is showing, as the view reports its own scrolling.
     *
     * Observable state for the same reason [findMatches] is: only the mini status bar displays it,
     * and threading a second progress callback down through the screen would be plumbing for one
     * short label.
     */
    var screens: NovelStatusLine.Screens by mutableStateOf(NovelStatusLine.Screens.NONE)
        internal set

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
        speechHighlight = null
        speechMatchesToAdvance = 0
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
        findMatches = FindMatches.NONE
        if (speechHighlight == null) webView?.clearMatches()
    }

    /** Highlights and follows one spoken unit with WebView's native, script-free text search. */
    fun highlightSpeech(text: String, occurrence: Int) {
        val highlight = SpeechHighlight(text, occurrence.coerceAtLeast(0))
        if (speechHighlight == highlight) return
        speechHighlight = highlight
        activeQuery = null
        findMatches = FindMatches.NONE
        applySpeechHighlight(highlight)
    }

    fun clearSpeechHighlight() {
        speechHighlight = null
        speechMatchesToAdvance = 0
        webView?.clearMatches()
        activeQuery?.let { webView?.findAllAsync(it) }
    }

    /** Routes WebView's one find callback to either chapter search or speech highlighting. */
    internal fun onFindResult(activeMatchOrdinal: Int, numberOfMatches: Int, doneCounting: Boolean) {
        if (speechHighlight != null) {
            if (doneCounting && numberOfMatches > 0 && speechMatchesToAdvance > 0) {
                speechMatchesToAdvance--
                webView?.findNext(true)
            }
            return
        }
        findMatches = FindMatches(activeMatchOrdinal, numberOfMatches)
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
        speechHighlight?.let(::applySpeechHighlight) ?: activeQuery?.let { webView?.findAllAsync(it) }
    }

    private fun applySpeechHighlight(highlight: SpeechHighlight) {
        speechMatchesToAdvance = highlight.occurrence
        webView?.findAllAsync(highlight.text)
    }

    private data class SpeechHighlight(val text: String, val occurrence: Int)
}

/** How many matches a search found and which of them is showing, both as the view reports them. */
data class FindMatches(val activeOrdinal: Int, val total: Int) {
    companion object {
        val NONE = FindMatches(activeOrdinal = 0, total = 0)
    }
}
