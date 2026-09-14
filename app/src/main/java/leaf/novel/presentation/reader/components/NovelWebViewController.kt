package leaf.novel.presentation.reader.components

import android.webkit.WebView
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import leaf.novel.ui.reader.NovelSpeech
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
    private var speechHighlight: NovelSpeech.Position? = null
    private var speechHighlighter: ((NovelSpeech.Position?) -> Unit)? = null
    private var chapterAppender: ((String) -> Unit)? = null
    private var chapterPrepender: ((String) -> Unit)? = null
    private var chapterScroller: ((Long, Int) -> Unit)? = null
    private var chapterKeeper: ((List<Long>) -> Unit)? = null
    private var styleApplier: ((String) -> Unit)? = null

    /**
     * Moving by whole screenfuls, which the view supplies because only it knows which axis the
     * chapter is laid out on and how much of a line to leave behind.
     */
    private var turner: ((pages: Int) -> Unit)? = null

    internal fun attach(
        view: WebView,
        turnPages: (pages: Int) -> Unit,
        appendChapter: (String) -> Unit,
        prependChapter: (String) -> Unit,
        scrollToChapter: (Long, Int) -> Unit,
        keepChapters: (List<Long>) -> Unit,
        highlightSpeech: (NovelSpeech.Position?) -> Unit,
        applyStylesheet: (String) -> Unit,
    ) {
        webView = view
        turner = turnPages
        chapterAppender = appendChapter
        chapterPrepender = prependChapter
        chapterScroller = scrollToChapter
        chapterKeeper = keepChapters
        speechHighlighter = highlightSpeech
        styleApplier = applyStylesheet
    }

    internal fun detach() {
        webView = null
        turner = null
        chapterAppender = null
        chapterPrepender = null
        chapterScroller = null
        chapterKeeper = null
        speechHighlighter = null
        styleApplier = null
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

    /** Adds one above it instead, without moving the words the reader is looking at. */
    fun prependChapter(section: String) {
        chapterPrepender?.invoke(section)
    }

    /** Moves to a chapter section already present in the rolling document. */
    fun scrollToChapter(chapterId: Long, percent: Int = 0) {
        chapterScroller?.invoke(chapterId, percent)
    }

    /** Repaints the open document in [css], which is how a theme change avoids a reload. */
    fun applyStylesheet(css: String) {
        styleApplier?.invoke(css)
    }

    /** Drops every section outside the window the reader is in, on either side of it. */
    fun keepChapters(chapterIds: List<Long>) {
        chapterKeeper?.invoke(chapterIds)
    }

    /** Whether there is any page left below, so auto scroll can stop at the end of a chapter. */
    val canScrollDown: Boolean get() = webView?.canScrollVertically(1) == true

    /** And above, which is what the speech page buttons stop at rather than a chapter boundary. */
    val canScrollUp: Boolean get() = webView?.canScrollVertically(-1) == true

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
     * Ordinary search uses the view's find-in-page; speech follows its own source locations.
     */
    fun find(query: String) {
        speechHighlight = null
        speechHighlighter?.invoke(null)
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

    /** Highlights only the chapter and block belonging to the spoken unit. */
    fun highlightSpeech(highlight: NovelSpeech.Position) {
        if (speechHighlight == highlight) return
        speechHighlight = highlight
        activeQuery = null
        findMatches = FindMatches.NONE
        webView?.clearMatches()
        speechHighlighter?.invoke(highlight)
    }

    fun clearSpeechHighlight() {
        speechHighlight = null
        speechHighlighter?.invoke(null)
        webView?.clearMatches()
        activeQuery?.let { webView?.findAllAsync(it) }
    }

    /** Search callbacks cannot move the independent speech highlight. */
    internal fun onFindResult(activeMatchOrdinal: Int, numberOfMatches: Int) {
        if (speechHighlight != null) return
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
        val highlight = speechHighlight
        if (highlight != null) {
            speechHighlighter?.invoke(highlight)
        } else {
            activeQuery?.let { webView?.findAllAsync(it) }
        }
    }
}

/** How many matches a search found and which of them is showing, both as the view reports them. */
data class FindMatches(val activeOrdinal: Int, val total: Int) {
    companion object {
        val NONE = FindMatches(activeOrdinal = 0, total = 0)
    }
}
