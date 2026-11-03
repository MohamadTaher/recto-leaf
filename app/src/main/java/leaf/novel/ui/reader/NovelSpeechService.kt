package leaf.novel.ui.reader

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.applyCanvas
import androidx.core.graphics.createBitmap
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.notify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import leaf.novel.ui.reader.setting.NovelReaderAction
import leaf.novel.ui.reader.setting.NovelReaderPreferences
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Keeps read-aloud running whenever the reader is merely off screen — backgrounded, or closed
 * outright while its chapter keeps talking — but not once the task itself is swiped out of Recents.
 *
 * Android stops giving a backgrounded process anything to run on, so speech that outlives the
 * screen has to be attached to something the system has been told about. That is all this is: it
 * owns no speech of its own, it holds the process open and puts the controls where they can be
 * reached with the app away. The voice itself lives in [NovelSpeechSession], at process scope, not
 * with whichever reader happened to start it — a reader being destroyed is not a reason to stop
 * talking partway through a chapter.
 *
 * A swiped-away task is a reason: [onTaskRemoved] is the one signal telling those two cases apart,
 * so it is also the one place this stops speech itself rather than just outliving whatever asked it
 * to. `stopWithTask` stays false so nothing happens without that call actually running.
 *
 * It is bound to nothing and started with an explicit intent, so it lives exactly as long as speech
 * does.
 *
 * The [NovelReaderMediaSession] is what a Bluetooth headset's own button, or its in-ear detection taking a bud
 * out, actually reaches — both arrive as ordinary media-button events, the same as a wired remote,
 * with no code of this fork's own involved on the device end. [becomingNoisyReceiver] covers the
 * other half of "a headset stopped being the way this is heard": a disconnect rather than a button,
 * which is a route change the system announces instead.
 */
class NovelSpeechService : Service() {

    /**
     * What the notification last showed, so an action intent — which carries none of this — has
     * something to redraw from.
     */
    private var lastChapter = ""
    private var lastPaused = false
    private var lastIndex = 0

    /** The media player's background, drawn once per service from the wallpaper's palette. */
    private val artwork by lazy(::drawArtwork)

    private val clock = NovelSpeechClock(SystemClock::elapsedRealtime)

    /** The chapter and length last put on the session, which is re-sent only when they change. */
    private var shownMetadata: Pair<String, Long>? = null

    /** Runs while speech is paused; see [keepVisibleWhilePaused]. */
    private var keepVisible: Job? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val preferences by lazy { NovelReaderPreferences(AndroidPreferenceStore(this)) }

    private fun performAction(action: NovelReaderAction) {
        when (action) {
            NovelReaderAction.NONE, NovelReaderAction.TEXT_SELECTION -> Unit
            NovelReaderAction.START_SPEAKING -> controls?.play()
            NovelReaderAction.PAUSE_SPEAKING -> controls?.pause()
            NovelReaderAction.TOGGLE_SPEECH -> controls?.togglePlayback()
            NovelReaderAction.STOP_SPEAKING -> controls?.stop()
            NovelReaderAction.PREVIOUS_SPEECH -> controls?.seek(-1)
            NovelReaderAction.NEXT_SPEECH -> controls?.seek(1)
            else -> NovelReaderMediaSession.dispatchReaderAction(action)
        }
    }

    /** The output is about to switch to the speaker — a Bluetooth or wired headset disconnecting. */
    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            controls?.pause()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        NovelReaderMediaSession.attachSpeech(this, preferences, readerIntent(), ::performAction)
        // The reader's collector disappears when it closes; hardware playback state must not.
        NovelSpeechSession.speakerOrNull()?.speech
            ?.filter { it.speaking }
            ?.map { it.paused to it.index }
            ?.distinctUntilChanged()
            ?.onEach { (paused, index) ->
                lastIndex = index
                if (paused != lastPaused) update(lastChapter, paused) else publish()
            }
            ?.launchIn(scope)
        ContextCompat.registerReceiver(
            this,
            becomingNoisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> performAction(NovelReaderAction.TOGGLE_SPEECH)
            ACTION_STOP -> {
                performAction(NovelReaderAction.STOP_SPEAKING)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        if (intent?.hasExtra(EXTRA_CHAPTER) == true) {
            lastChapter = intent.getStringExtra(EXTRA_CHAPTER).orEmpty()
            lastPaused = intent.getBooleanExtra(EXTRA_PAUSED, false)
            publish()
        }

        // The one call this makes to the system: every later update goes through update(), which
        // just reposts the notification. Calling this again for those would ask the system to
        // start the service afresh each time, which is exactly what a background chapter change
        // is not allowed to do on Android 12+.
        startForeground(NOTIFICATION_ID, notification(lastChapter, lastPaused))
        starting = false
        if (stopOnceStarted) {
            stopOnceStarted = false
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        instance = null
        controls = null
        unregisterReceiver(becomingNoisyReceiver)
        NovelReaderMediaSession.detachSpeech()
        super.onDestroy()
    }

    /**
     * The task hosting the app was swiped out of Recents. Unlike a reader closing or the app being
     * backgrounded — both of which leave speech running — nothing can reattach to a task that no
     * longer exists, so this is where the survive-in-the-background guarantee ends: stop the same
     * way the notification's own Stop button does, rather than ride out `stopWithTask=false` the way
     * every other kind of "off screen" is meant to.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        NovelReaderMediaSession.detachReader()
        controls?.stop()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    /** Redraws the already-posted notification. Called only while this instance is alive. */
    private fun update(chapter: String, paused: Boolean) {
        lastChapter = chapter
        lastPaused = paused
        publish()
        notify(NOTIFICATION_ID, notification(chapter, paused))
    }

    /**
     * The player stays for as long as speech does, paused or not, rather than vanishing after a
     * pause the way a music app's does: speech held paused is still a session being listened to.
     */
    private fun keepVisibleWhilePaused() {
        if (lastPaused == (keepVisible != null)) return
        keepVisible?.cancel()
        keepVisible = if (lastPaused) {
            scope.launch {
                while (true) {
                    delay(KEEP_VISIBLE_MS)
                    NovelReaderMediaSession.showPlaying()
                    delay(KEEP_VISIBLE_FLIP_MS)
                    publish()
                }
            }
        } else {
            null
        }
    }

    /** Puts where speech is in its chapter onto the session, for the player's seek bar. */
    private fun publish() {
        keepVisibleWhilePaused()
        val reading = NovelSpeechClock.chapterChars(NovelSpeechSession.queue.positions, lastIndex)?.let {
            clock.read(it.chapterId, it.before, it.total, playing = !lastPaused)
        }
        NovelReaderMediaSession.update(
            lastPaused,
            reading?.position ?: PlaybackState.PLAYBACK_POSITION_UNKNOWN,
            reading?.speed ?: 1f,
        )
        val metadata = lastChapter to (reading?.duration ?: 0L)
        if (metadata != shownMetadata) {
            shownMetadata = metadata
            NovelReaderMediaSession.setMetadata(metadata.first, artwork, metadata.second)
        }
    }

    /**
     * The system's media player, as video and music apps use: no app header, and the session's
     * artwork as its background. The platform builder rather than Mihon's compat one, since only
     * the platform media style takes the platform session token. Android 13+ draws the buttons
     * from the session's playback state; these actions serve older versions.
     */
    private fun notification(chapter: String, paused: Boolean): Notification {
        val playPause = Notification.Action.Builder(
            Icon.createWithResource(
                this,
                if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
            ),
            stringResource(if (paused) MR.strings.action_resume else MR.strings.action_pause),
            command(ACTION_PLAY_PAUSE),
        ).build()
        val stop = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            stringResource(MR.strings.leaf_novel_action_stop_speaking),
            command(ACTION_STOP),
        ).build()
        return Notification.Builder(this, Notifications.CHANNEL_COMMON)
            // Blank: One UI draws the small icon beside the title, which is to read the chapter alone.
            .setSmallIcon(Icon.createWithBitmap(createBitmap(1, 1)))
            .setLargeIcon(artwork)
            .setContentTitle(chapter.ifBlank { stringResource(MR.strings.leaf_novel_action_speak) })
            .setContentIntent(readerIntent())
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .addAction(stop)
            .addAction(playPause)
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(NovelReaderMediaSession.token)
                    .setShowActionsInCompactView(0, 1),
            )
            .build()
    }

    /**
     * Material 3 Expressive shapes — scalloped circles — over a soft glow, in tones of the
     * wallpaper's hue. The player darkens artwork heavily under its white text, which turns pale
     * Material tones to mud, so only the hue is taken and the tones are drawn saturated. The shapes
     * keep to the corners, clear of the title and buttons.
     */
    private fun drawArtwork(): Bitmap {
        val hue = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            FloatArray(3).also { Color.colorToHSV(getColor(android.R.color.system_accent1_500), it) }[0]
        } else {
            DEFAULT_HUE
        }
        fun tone(shift: Float, saturation: Float, value: Float) =
            Color.HSVToColor(floatArrayOf((hue + shift + 360) % 360, saturation, value))
        return createBitmap(ARTWORK_WIDTH, ARTWORK_HEIGHT).applyCanvas {
            val w = width.toFloat()
            val h = height.toFloat()
            drawColor(tone(-20f, 0.9f, 0.5f))
            glow(w * 0.5f, h * 0.5f, w * 0.25f, ColorUtils.setAlphaComponent(tone(0f, 0.85f, 1f), 160))
            scallop(w * 0.11f, h * 0.07f, w * 0.27f, 12, 0.06f, 0.1f, tone(35f, 0.8f, 1f))
            scallop(w * 0.92f, h * 0.93f, w * 0.3f, 8, 0.1f, 0.3f, tone(-55f, 0.75f, 0.95f))
            scallop(w * 0.68f, h * 0.17f, w * 0.09f, 6, 0.12f, 0f, tone(10f, 0.55f, 1f))
            grain()
        }
    }

    /** A blurred disc: solid to the blur's edge, half strength at [radius], gone two blurs out. */
    private fun Canvas.glow(x: Float, y: Float, radius: Float, color: Int) {
        val blur = width * 0.13f
        val outer = radius + 2 * blur
        val half = ColorUtils.setAlphaComponent(color, Color.alpha(color) / 2)
        val stops = floatArrayOf(0f, (radius - blur).coerceAtLeast(0f) / outer, radius / outer, 1f)
        val colors = intArrayOf(color, color, half, Color.TRANSPARENT)
        drawCircle(
            x,
            y,
            outer,
            Paint().apply {
                shader =
                    RadialGradient(x, y, outer, colors, stops, Shader.TileMode.CLAMP)
            },
        )
    }

    /** A circle whose edge swells into [lobes] soft scallops, [depth] of its radius deep. */
    private fun Canvas.scallop(x: Float, y: Float, radius: Float, lobes: Int, depth: Float, turn: Float, color: Int) {
        val path = Path()
        for (step in 0..SHAPE_STEPS) {
            val angle = 2 * PI * step / SHAPE_STEPS
            val r = radius * (1 + depth * cos(lobes * (angle + turn)))
            val px = x + (r * cos(angle)).toFloat()
            val py = y + (r * sin(angle)).toFloat()
            if (step == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            maskFilter = BlurMaskFilter(width * 0.005f, BlurMaskFilter.Blur.NORMAL)
        }
        drawPath(path, paint)
    }

    /** Faint noise, so the player's dark scrim does not band the smooth tones into steps. */
    private fun Canvas.grain() {
        val random = Random(GRAIN_SEED)
        val paint = Paint()
        for (shade in intArrayOf(Color.WHITE, Color.BLACK)) {
            paint.color = ColorUtils.setAlphaComponent(shade, 10)
            val points = FloatArray(width * height / 6) {
                if (it % 2 == 0) random.nextFloat() * width else random.nextFloat() * height
            }
            drawPoints(points, paint)
        }
    }

    /** Tapping the notification returns to the reader rather than opening a second one. */
    private fun readerIntent(): PendingIntent? {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    private fun command(action: String): PendingIntent = PendingIntent.getService(
        this,
        action.hashCode(),
        Intent(this, NovelSpeechService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * What the notification's buttons, and now the hardware ones, do. Implemented by whatever is
     * currently speaking.
     */
    interface Controls {
        fun togglePlayback()
        fun play()
        fun pause()
        fun stop()
        fun seek(units: Int)
    }

    companion object {
        private const val ACTION_PLAY_PAUSE = "leaf.novel.SPEECH_PLAY_PAUSE"
        private const val ACTION_STOP = "leaf.novel.SPEECH_STOP"
        private const val EXTRA_CHAPTER = "chapter"
        private const val EXTRA_PAUSED = "paused"

        // About the shape of the One UI player card, which crops the artwork to fill it.
        private const val ARTWORK_WIDTH = 960
        private const val ARTWORK_HEIGHT = 432
        private const val DEFAULT_HUE = 330f
        private const val SHAPE_STEPS = 360
        private const val GRAIN_SEED = 7

        /** Inside System UI's ten minute timeout for a paused player. */
        private const val KEEP_VISIBLE_MS = 9 * 60_000L

        /** Long enough for System UI to be handed the playing state before paused replaces it. */
        private const val KEEP_VISIBLE_FLIP_MS = 500L

        /** Outside the block upstream numbers its own notifications in. */
        private const val NOTIFICATION_ID = -801

        /**
         * The reader currently speaking.
         *
         * A single reference rather than binding: only one reader is open at a time, and a bound
         * service would have to outlive the very unbinding that backgrounding causes.
         */
        @Volatile
        var controls: Controls? = null

        /** The running instance, so a content update can be posted without asking to be started again. */
        @Volatile
        private var instance: NovelSpeechService? = null

        /**
         * Between asking for the service and its going foreground. Stopped in that window, a
         * service started with `startForegroundService` takes the app down with it, and a single
         * short utterance ends speech that fast. So a stop then waits for the start instead.
         */
        @Volatile
        private var starting = false

        @Volatile
        private var stopOnceStarted = false

        /** Starts the service, or updates what the notification says when it is already up. */
        fun show(context: Context, chapter: String, paused: Boolean) {
            val running = instance
            if (running != null) {
                running.update(chapter, paused)
                return
            }
            val intent = Intent(context, NovelSpeechService::class.java)
                .putExtra(EXTRA_CHAPTER, chapter)
                .putExtra(EXTRA_PAUSED, paused)
            starting = true
            stopOnceStarted = false
            // Refused from the background on Android 12+. Speech goes on without the notification
            // rather than the refusal crashing the app.
            runCatching { ContextCompat.startForegroundService(context, intent) }.onFailure {
                starting = false
                logcat(LogPriority.WARN, it) { "Could not start the read-aloud service" }
            }
        }

        fun hide(context: Context) {
            controls = null
            if (starting) {
                stopOnceStarted = true
                return
            }
            context.stopService(Intent(context, NovelSpeechService::class.java))
        }
    }
}
