package leaf.novel.presentation.reader.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.view.ActionMode
import android.view.GestureDetector
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import leaf.novel.ui.reader.loader.NovelEpubAssetServer
import leaf.novel.ui.reader.loader.VIRTUAL_ORIGIN
import leaf.novel.ui.reader.setting.NovelReaderSwipe
import leaf.novel.ui.reader.setting.NovelTapGrid
import kotlin.math.roundToInt

/**
 * A WebView that reports its own scroll position, exposes its scroll range, and knows when text is
 * selected.
 *
 * `computeVerticalScrollRange` is protected on `View` and the restore logic needs it to turn a
 * stored percent back into a pixel offset. Selection state has no accessor at all, and a bound
 * swipe has to stay out of the way of a reader dragging a selection handle, so the subclass widens
 * both.
 */
@SuppressLint("ViewConstructor")
private class NovelWebView(context: Context) : WebView(context) {

    var onScroll: ((scrollY: Int, range: Int) -> Unit)? = null

    private var selectionMode: ActionMode? = null

    /** Selection runs in an action mode, which is the only signal the view offers that it is on. */
    val isSelecting: Boolean get() = selectionMode != null

    val verticalScrollRange: Int get() = computeVerticalScrollRange()

    /** The furthest the content can scroll, which is what a stored percent is a fraction of. */
    val maxScroll: Int get() = (verticalScrollRange - height).coerceAtLeast(0)

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        onScroll?.invoke(t, computeVerticalScrollRange())
    }

    override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? {
        val mode = super.startActionMode(callback?.let(::TrackedCallback), type)
        selectionMode = mode
        return mode
    }

    /**
     * Delegates all of it but the end, which is the part that clears [isSelecting].
     *
     * A [ActionMode.Callback2] rather than the narrower [ActionMode.Callback], because the
     * selection callback the WebView hands us is one, and `onGetContentRect` is what puts the
     * floating toolbar beside the selected text instead of over the whole page. Wrapping it in the
     * plain interface would drop that override on the floor and the toolbar would lose its place.
     */
    private inner class TrackedCallback(
        private val delegate: ActionMode.Callback,
    ) : ActionMode.Callback2() {

        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean =
            delegate.onCreateActionMode(mode, menu)

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean =
            delegate.onPrepareActionMode(mode, menu)

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean =
            delegate.onActionItemClicked(mode, item)

        override fun onDestroyActionMode(mode: ActionMode) {
            selectionMode = null
            delegate.onDestroyActionMode(mode)
        }

        // The view is nullable here: the default implementation null-checks it before measuring.
        override fun onGetContentRect(mode: ActionMode, view: View?, outRect: Rect) {
            if (delegate is ActionMode.Callback2) {
                delegate.onGetContentRect(mode, view, outRect)
            } else {
                super.onGetContentRect(mode, view, outRect)
            }
        }
    }
}

/**
 * Renders one chapter and keeps its scroll position in sync with the database.
 *
 * The document is handed over inline with the virtual origin as its base URL, so every relative
 * reference inside it — images, the book's own stylesheet, fonts — comes back through
 * [NovelEpubAssetServer] and out of the zip. Nothing is written to disk.
 *
 * Everything the WebView's own callbacks touch is deliberately `remember`ed *without* a key and read
 * through [rememberUpdatedState]. The view is built once by `AndroidView`'s factory, so a callback
 * that closed over a keyed `remember` would keep reading the value from the composition that created
 * it — which silently breaks the reload path when the font size or theme changes.
 *
 * [seekRequests] is a stream of events rather than a percent parameter on purpose. The view reports
 * its own position back through [onProgress], so a state parameter would feed every scroll straight
 * back in as a seek and pin the reader in place.
 */
@Composable
fun NovelChapterWebView(
    document: String,
    baseUrl: String,
    initialPercent: Int,
    seekRequests: Flow<Int>,
    assetServer: NovelEpubAssetServer?,
    controller: NovelWebViewController,
    backgroundColor: Int,
    onProgress: (Int) -> Unit,
    onTapCell: (Int) -> Unit,
    onLongPress: () -> Boolean,
    onSwipe: (NovelReaderSwipe) -> Boolean,
    onInternalLink: (String) -> Unit,
    onExternalLink: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var webView by remember { mutableStateOf<NovelWebView?>(null) }

    // Stable for the life of the composable; both are reset by the load effect, not by a key.
    val pageFinished = remember { MutableStateFlow(false) }
    val restored = remember { mutableStateOf(false) }

    val currentAssetServer by rememberUpdatedState(assetServer)
    val currentOnProgress by rememberUpdatedState(onProgress)
    val currentOnTapCell by rememberUpdatedState(onTapCell)
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val currentOnSwipe by rememberUpdatedState(onSwipe)
    val currentOnInternalLink by rememberUpdatedState(onInternalLink)
    val currentOnExternalLink by rememberUpdatedState(onExternalLink)

    AndroidView(
        modifier = modifier,
        factory = { context ->
            NovelWebView(context).apply {
                configure(backgroundColor)
                webViewClient = novelWebViewClient(
                    assetServer = { currentAssetServer },
                    onPageFinished = { pageFinished.value = true },
                    onInternalLink = { currentOnInternalLink(it) },
                    onExternalLink = { currentOnExternalLink(it) },
                )
                attachTapDetector(
                    onTapCell = { currentOnTapCell(it) },
                    onLongPress = { currentOnLongPress() },
                    onSwipe = { currentOnSwipe(it) },
                )
                controller.attach(this)
                onScroll = { scrollY, range ->
                    if (restored.value) currentOnProgress(percentOf(scrollY, range, height))
                }
                webView = this
            }
        },
        update = { view ->
            view.setBackgroundColor(backgroundColor)
        },
        onRelease = { view ->
            controller.detach()
            view.onScroll = null
            view.stopLoading()
            view.destroy()
        },
    )

    LaunchedEffect(webView, document) {
        val view = webView ?: return@LaunchedEffect
        restored.value = false
        pageFinished.value = false
        view.loadDataWithBaseURL(baseUrl, document, "text/html", "utf-8", null)

        pageFinished.first { it }

        // Content height is not final at onPageFinished — images and the book's own stylesheet are
        // still settling — so restoring there lands in the wrong place on a long chapter.
        val maxScroll = view.awaitStableMaxScroll()
        if (maxScroll > 0 && initialPercent > 0) {
            view.scrollTo(0, view.scrollTargetOf(initialPercent))
        }
        restored.value = true

        // A chapter shorter than the viewport can never be scrolled, so it is complete on sight.
        if (maxScroll <= 0) currentOnProgress(100)
    }

    LaunchedEffect(webView, seekRequests) {
        val view = webView ?: return@LaunchedEffect
        seekRequests.collect { percent ->
            if (view.maxScroll > 0) view.scrollTo(0, view.scrollTargetOf(percent))
        }
    }
}

/** Turns a percent through the chapter into the pixel offset that shows it. */
private fun NovelWebView.scrollTargetOf(percent: Int): Int =
    (maxScroll * percent / 100f).roundToInt().coerceIn(0, maxScroll)

/** Scroll position as a percent, guarding the division for content shorter than the viewport. */
internal fun percentOf(scrollY: Int, range: Int, viewportHeight: Int): Int {
    val maxScroll = range - viewportHeight
    if (maxScroll <= 0) return 100
    return (scrollY * 100f / maxScroll).roundToInt().coerceIn(0, 100)
}

/**
 * Waits for two consecutive identical measurements before trusting the scroll range, then returns
 * the furthest the content can scroll.
 */
private suspend fun NovelWebView.awaitStableMaxScroll(): Int {
    var previous = -1
    repeat(MAX_HEIGHT_POLLS) {
        val range = verticalScrollRange
        if (range > 0 && range == previous) return maxScroll
        previous = range
        delay(HEIGHT_POLL_INTERVAL_MS)
    }
    return maxScroll
}

@SuppressLint("SetJavaScriptEnabled")
private fun WebView.configure(backgroundColor: Int) {
    with(settings) {
        // Nothing in the reader needs scripting, and a book is not trusted content.
        javaScriptEnabled = false
        domStorageEnabled = false
        // Everything the page may load comes through shouldInterceptRequest.
        allowFileAccess = false
        allowContentAccess = false
        @Suppress("DEPRECATION")
        blockNetworkLoads = true
        cacheMode = WebSettings.LOAD_NO_CACHE
        mediaPlaybackRequiresUserGesture = true
        // Font size is the zoom control.
        setSupportZoom(false)
        builtInZoomControls = false
        displayZoomControls = false
        textZoom = 100
    }
    setBackgroundColor(backgroundColor)
    overScrollMode = View.OVER_SCROLL_NEVER
    isHorizontalScrollBarEnabled = false
}

@SuppressLint("ClickableViewAccessibility")
private fun NovelWebView.attachTapDetector(
    onTapCell: (Int) -> Unit,
    onLongPress: () -> Boolean,
    onSwipe: (NovelReaderSwipe) -> Boolean,
) {
    val minSwipeDistancePx = NovelReaderSwipe.MIN_DISTANCE_DP * resources.displayMetrics.density
    val detector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onTapCell(NovelTapGrid.cellOf(e.x, e.y, width, height))
                return false
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                // A selection handle being dragged across the page is a fling like any other, so
                // selection wins outright while it is up.
                if (e1 == null || isSelecting) return false
                val swipe = NovelReaderSwipe.of(e2.x - e1.x, e2.y - e1.y, minSwipeDistancePx)
                    ?: return false
                return onSwipe(swipe)
            }
        },
    )
    // Only a claimed swipe is consumed. Everything else reports false and leaves the WebView to
    // scroll exactly as it did before, which is why an unbound direction costs nothing.
    setOnTouchListener { _, event -> detector.onTouchEvent(event) }
    // Consuming the long click is what suppresses the WebView's own text selection, so a binding
    // that means "leave selection alone" reports false and lets the default behaviour run.
    setOnLongClickListener { onLongPress() }
}

private fun novelWebViewClient(
    assetServer: () -> NovelEpubAssetServer?,
    onPageFinished: () -> Unit,
    onInternalLink: (String) -> Unit,
    onExternalLink: (String) -> Unit,
) = object : WebViewClient() {

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        return assetServer()?.handle(request.url)
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        when {
            url.startsWith(VIRTUAL_ORIGIN) -> onInternalLink(url.removePrefix(VIRTUAL_ORIGIN))
            url.startsWith("http://") || url.startsWith("https://") -> onExternalLink(url)
            // Anything else — intents, file, javascript — is refused outright.
            else -> Unit
        }
        return true
    }

    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        onPageFinished()
    }
}

private const val MAX_HEIGHT_POLLS = 40
private const val HEIGHT_POLL_INTERVAL_MS = 50L
