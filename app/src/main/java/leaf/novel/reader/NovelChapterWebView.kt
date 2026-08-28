package leaf.novel.reader

import android.annotation.SuppressLint
import android.content.Context
import android.view.GestureDetector
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

/**
 * A WebView that reports its own scroll position and exposes its scroll range.
 *
 * `computeVerticalScrollRange` is protected on `View`, and the restore logic needs it to turn a
 * stored percent back into a pixel offset, so the subclass exists purely to widen those two.
 */
@SuppressLint("ViewConstructor")
private class NovelWebView(context: Context) : WebView(context) {

    var onScroll: ((scrollY: Int, range: Int) -> Unit)? = null

    val verticalScrollRange: Int get() = computeVerticalScrollRange()

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        onScroll?.invoke(t, computeVerticalScrollRange())
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
 */
@Composable
fun NovelChapterWebView(
    document: String,
    baseUrl: String,
    initialPercent: Int,
    assetServer: NovelEpubAssetServer?,
    backgroundColor: Int,
    onProgress: (Int) -> Unit,
    onTap: () -> Unit,
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
    val currentOnTap by rememberUpdatedState(onTap)
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
                attachTapDetector { currentOnTap() }
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
        // still settling — so restoring there lands in the wrong place on a long chapter (risk T6).
        val maxScroll = view.awaitStableMaxScroll()
        if (maxScroll > 0 && initialPercent > 0) {
            view.scrollTo(0, (maxScroll * initialPercent / 100f).roundToInt().coerceIn(0, maxScroll))
        }
        restored.value = true

        // A chapter shorter than the viewport can never be scrolled, so it is complete on sight.
        if (maxScroll <= 0) currentOnProgress(100)
    }
}

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
        if (range > 0 && range == previous) return (range - height).coerceAtLeast(0)
        previous = range
        delay(HEIGHT_POLL_INTERVAL_MS)
    }
    return (verticalScrollRange - height).coerceAtLeast(0)
}

@SuppressLint("SetJavaScriptEnabled")
private fun WebView.configure(backgroundColor: Int) {
    with(settings) {
        // Nothing in the reader needs scripting, and a book is not trusted content (risk T10).
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
private fun WebView.attachTapDetector(onTap: () -> Unit) {
    val detector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onTap()
                return false
            }
        },
    )
    // Returning false leaves the WebView's own scrolling untouched.
    setOnTouchListener { _, event ->
        detector.onTouchEvent(event)
        false
    }
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
