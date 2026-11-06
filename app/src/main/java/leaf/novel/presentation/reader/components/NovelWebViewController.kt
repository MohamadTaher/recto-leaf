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
    var visibleSpeechAnchor: NovelSpeech.Anchor? by mutableStateOf(null)
        internal set

    // Speech owns the location until deliberate navigation resumes. Resizing the viewport when
    // its panel closes must not replace that location with a different scroll percentage.
    var tracksScrollProgress: Boolean = true
        private set

    internal fun trackManualProgress() {
        tracksScrollProgress = true
    }

    private var webView: WebView? = null
    private var pendingMatch: NovelSpeech.Position? = null
    private var matchMarked = false
    private var speechHighlight: NovelSpeech.Position? = null
    private var speechHighlighter: ((NovelSpeech.Position?, Boolean) -> Unit)? = null
    private var chapterAppender: ((String) -> Unit)? = null
    private var chapterPrepender: ((String) -> Unit)? = null
    private var chapterScroller: ((Long, Int) -> Unit)? = null
    private var chapterKeeper: ((List<Long>) -> Unit)? = null
    private var styleApplier: ((String) -> Unit)? = null
    private var commentsShower: ((Long, String) -> Unit)? = null

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
        highlightSpeech: (NovelSpeech.Position?, Boolean) -> Unit,
        applyStylesheet: (String) -> Unit,
        showComments: (Long, String) -> Unit,
    ) {
        webView = view
        turner = turnPages
        chapterAppender = appendChapter
        chapterPrepender = prependChapter
        chapterScroller = scrollToChapter
        chapterKeeper = keepChapters
        speechHighlighter = highlightSpeech
        styleApplier = applyStylesheet
        commentsShower = showComments
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
        commentsShower = null
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
        trackManualProgress()
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
        trackManualProgress()
        chapterScroller?.invoke(chapterId, percent)
    }

    /** Repaints the open document in [css], which is how a theme change avoids a reload. */
    fun applyStylesheet(css: String) {
        styleApplier?.invoke(css)
    }

    /** Writes a chapter's top comments under its last line, or with an empty [html] removes them. */
    fun showComments(chapterId: Long, html: String) {
        commentsShower?.invoke(chapterId, html)
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
     * Observable state because only the mini status bar displays it, and threading a second
     * progress callback down through the screen would be plumbing for one short label.
     */
    var screens: NovelStatusLine.Screens by mutableStateOf(NovelStatusLine.Screens.NONE)
        internal set

    /**
     * Marks one search result and brings it into view, with the same mark speech uses.
     *
     * With [afterLoad] the chapter is about to be opened, so the mark waits for it to load and
     * settle where its saved position puts it; sent now, it would reach the document being left.
     */
    fun showMatch(match: NovelSpeech.Position, afterLoad: Boolean) {
        trackManualProgress()
        speechHighlight = null
        matchMarked = true
        if (afterLoad) {
            pendingMatch = match
        } else {
            pendingMatch = null
            speechHighlighter?.invoke(match, false)
        }
    }

    /** Takes the mark off again, which the next tap on the page does. */
    fun clearMatch() {
        pendingMatch = null
        if (!matchMarked) return
        matchMarked = false
        if (speechHighlight == null) speechHighlighter?.invoke(null, true)
    }

    /** Highlights only the chapter and block belonging to the spoken unit. */
    fun highlightSpeech(highlight: NovelSpeech.Position) {
        tracksScrollProgress = false
        if (speechHighlight == highlight) return
        speechHighlight = highlight
        pendingMatch = null
        matchMarked = false
        speechHighlighter?.invoke(highlight, false)
    }

    /**
     * Drops the speech highlight, leaving the page at [stoppedAt] if speech still owns the location.
     *
     * The page follows speech only while the reader is composed, so speech that ended with the
     * screen off left it where the voice started, and the first drag would save that spot over
     * speech's progress. Where the page did follow, the place is already on screen and nothing moves.
     * With nothing marked and nowhere to go it sends nothing, so a search result's mark is left be.
     */
    fun clearSpeechHighlight(stoppedAt: NovelSpeech.Position? = null) {
        val marked = speechHighlight != null
        speechHighlight = null
        val target = stoppedAt.takeUnless { tracksScrollProgress }
        if (marked || target != null) speechHighlighter?.invoke(target, true)
    }

    /**
     * Puts the speech highlight back after the document has been rebuilt, or shows the search
     * result the new chapter was opened for.
     *
     * Changing a style setting reloads the page, which takes the highlighting with it. A search
     * mark is shown once, on the load it was waiting for; a later reload drops it rather than pull
     * the reader back to a place they have since read on from.
     */
    internal fun reapplyHighlight() {
        val highlight = speechHighlight ?: pendingMatch
        pendingMatch = null
        if (highlight != null) speechHighlighter?.invoke(highlight, false) else matchMarked = false
    }
}
