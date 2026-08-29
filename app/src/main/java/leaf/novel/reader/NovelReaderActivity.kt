package leaf.novel.reader

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import dev.zacsweers.metro.Inject
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.view.setComposeContent
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.sample
import mihon.app.di.AppGraph
import mihon.core.metro.metroGraph
import kotlin.time.Duration.Companion.seconds

/**
 * The novel reader.
 *
 * Reached only through [NovelReaderRouter]'s redirect out of `ReaderActivity`, so every existing and
 * future entry point — manga screen, library, History, Updates, notifications, deep links — routes
 * here without any of them knowing it exists.
 */
class NovelReaderActivity : BaseActivity() {

    private val graph: AppGraph by lazy { metroGraph() }

    companion object {
        fun newIntent(context: Context, mangaId: Long, chapterId: Long?): Intent {
            return Intent(context, NovelReaderActivity::class.java).apply {
                putExtra("manga", mangaId)
                putExtra("chapter", chapterId ?: -1L)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }
    }

    @Inject private lateinit var readerPreferences: ReaderPreferences

    @Inject private lateinit var preferences: BasePreferences

    private val viewModel by viewModels<NovelReaderViewModel> { graph.viewModelFactory }

    private val windowInsetsController by lazy { WindowInsetsControllerCompat(window, window.decorView) }

    override fun onCreate(savedInstanceState: Bundle?) {
        graph.inject(this)
        registerSecureActivity(this)

        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        super.onCreate(savedInstanceState)

        if (!viewModel.hasValidArgs) {
            finish()
            return
        }

        applyReaderConfig()

        // Finish when incognito mode is disabled, matching the image reader.
        preferences.incognitoMode.changes()
            .drop(1)
            .onEach { if (!it) finish() }
            .launchIn(lifecycleScope)

        setComposeContent {
            NovelReaderScreen(
                viewModel = viewModel,
                onBack = { finish() },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.restartReadTimer()
    }

    override fun onPause() {
        viewModel.saveOnPause()
        super.onPause()
    }

    /**
     * The novel reader's equivalent of `ReaderActivity.ReaderConfig`. It cannot reuse that class —
     * it is a private inner class bound to the image reader's binding — but it reads exactly the
     * same preference objects, which is the reuse that matters.
     */
    private fun applyReaderConfig() {
        readerPreferences.keepScreenOn.changes()
            .onEach(::setKeepScreenOn)
            .launchIn(lifecycleScope)

        readerPreferences.customBrightness.changes()
            .onEach(::setCustomBrightness)
            .launchIn(lifecycleScope)

        readerPreferences.fullscreen.changes()
            .onEach(::setFullscreen)
            .launchIn(lifecycleScope)
    }

    private fun setKeepScreenOn(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun setCustomBrightness(enabled: Boolean) {
        if (enabled) {
            readerPreferences.customBrightnessValue.changes()
                .sample(0.1.seconds)
                .onEach(::setCustomBrightnessValue)
                .launchIn(lifecycleScope)
        } else {
            setCustomBrightnessValue(0)
        }
    }

    /**
     * Range is [-75, 100]. Below zero the window keeps minimum brightness and the difference is made
     * up by the shared [eu.kanade.presentation.reader.ReaderContentOverlay] dim.
     */
    private fun setCustomBrightnessValue(value: Int) {
        val readerBrightness = when {
            value > 0 -> value / 100f
            value < 0 -> 0.01f
            else -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        window.attributes = window.attributes.apply { screenBrightness = readerBrightness }
        viewModel.setBrightnessOverlayValue(value)
    }

    private fun setFullscreen(enabled: Boolean) {
        if (enabled) {
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
