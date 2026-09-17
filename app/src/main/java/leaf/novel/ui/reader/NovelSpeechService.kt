package leaf.novel.ui.reader

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import leaf.novel.ui.reader.setting.NovelReaderAction
import leaf.novel.ui.reader.setting.NovelReaderKey
import leaf.novel.ui.reader.setting.NovelReaderPreferences
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.i18n.MR

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
 * The [MediaSession] is what a Bluetooth headset's own button, or its in-ear detection taking a bud
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
    private var lastTitle = ""
    private var lastChapter = ""
    private var lastPaused = false

    private var mediaSession: MediaSession? = null
    private var routingAudio: AudioTrack? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val preferences by lazy { NovelReaderPreferences(AndroidPreferenceStore(this)) }

    /** Routes a hardware play/pause/stop press to whatever is currently speaking. */
    private val sessionCallback = object : MediaSession.Callback() {
        override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
            val event = IntentCompat.getParcelableExtra(mediaButtonIntent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                ?: return super.onMediaButtonEvent(mediaButtonIntent)
            if (NovelReaderKey.of(event.keyCode) == null) return super.onMediaButtonEvent(mediaButtonIntent)
            dispatchNovelReaderKey(
                keyCode = event.keyCode,
                eventAction = event.action,
                repeatCount = event.repeatCount,
                binding = { preferences.keys.getValue(it).get() },
                perform = ::performAction,
            )
            // An explicitly unbound media key must not fall through to the platform's default action.
            return true
        }

        override fun onPlay() {
            performKey(NovelReaderKey.MEDIA_PLAY)
        }

        override fun onPause() {
            performKey(NovelReaderKey.MEDIA_PAUSE)
        }

        override fun onStop() {
            performKey(NovelReaderKey.MEDIA_STOP)
        }

        override fun onSkipToNext() {
            performKey(NovelReaderKey.MEDIA_NEXT)
        }

        override fun onSkipToPrevious() {
            performKey(NovelReaderKey.MEDIA_PREVIOUS)
        }
    }

    private fun performKey(key: NovelReaderKey) {
        performAction(key.resolve(preferences.keys.getValue(key).get()))
    }

    private fun performAction(action: NovelReaderAction) {
        when (action) {
            NovelReaderAction.NONE, NovelReaderAction.TEXT_SELECTION -> Unit
            NovelReaderAction.START_SPEAKING -> controls?.play()
            NovelReaderAction.PAUSE_SPEAKING -> controls?.pause()
            NovelReaderAction.TOGGLE_SPEECH -> controls?.togglePlayback()
            NovelReaderAction.STOP_SPEAKING -> controls?.stop()
            else -> readerAction?.invoke(action)
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
        mediaSession = MediaSession(this, "NovelSpeech").apply {
            setCallback(sessionCallback)
            isActive = true
        }
        // The reader's collector disappears when it closes; hardware playback state must not.
        NovelSpeechSession.speakerOrNull()?.speech
            ?.filter { it.speaking }
            ?.map { it.paused }
            ?.distinctUntilChanged()
            ?.onEach {
                if (!it) registerAudioPlayback()
                update(lastTitle, lastChapter, it)
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
            ACTION_PLAY_PAUSE -> controls?.togglePlayback()
            ACTION_STOP -> {
                controls?.stop()
                stopSelf()
                return START_NOT_STICKY
            }
        }

        if (intent?.hasExtra(EXTRA_TITLE) == true) {
            lastTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
            lastChapter = intent.getStringExtra(EXTRA_CHAPTER).orEmpty()
            lastPaused = intent.getBooleanExtra(EXTRA_PAUSED, false)
            updatePlaybackState(lastPaused)
        }

        // The one call this makes to the system: every later update goes through update(), which
        // just reposts the notification. Calling this again for those would ask the system to
        // start the service afresh each time, which is exactly what a background chapter change
        // is not allowed to do on Android 12+.
        startForeground(NOTIFICATION_ID, notification(lastTitle, lastChapter, lastPaused))
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        routingAudio?.release()
        routingAudio = null
        instance = null
        controls = null
        unregisterReceiver(becomingNoisyReceiver)
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    /**
     * Android sends media keys to an app that has played audio. TTS plays under the engine's UID,
     * so an active MediaSession alone never qualifies this app. A single 100 ms silent PCM buffer
     * registers our playback when speech starts or resumes; it does not loop or request focus.
     */
    private fun registerAudioPlayback() {
        routingAudio?.release()
        routingAudio = null
        val silence = ByteArray(4800) // 2400 mono PCM16 frames at 24 kHz.
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(24000)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(silence.size)
            .build()
        routingAudio = track
        track.write(silence, 0, silence.size)
        track.notificationMarkerPosition = silence.size / 2
        track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(completed: AudioTrack) {
                if (routingAudio !== completed) return
                completed.release()
                routingAudio = null
            }

            override fun onPeriodicNotification(track: AudioTrack) = Unit
        })
        track.play()
    }

    /**
     * The task hosting the app was swiped out of Recents. Unlike a reader closing or the app being
     * backgrounded — both of which leave speech running — nothing can reattach to a task that no
     * longer exists, so this is where the survive-in-the-background guarantee ends: stop the same
     * way the notification's own Stop button does, rather than ride out `stopWithTask=false` the way
     * every other kind of "off screen" is meant to.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        controls?.stop()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    /** Redraws the already-posted notification. Called only while this instance is alive. */
    private fun update(title: String, chapter: String, paused: Boolean) {
        lastTitle = title
        lastChapter = chapter
        lastPaused = paused
        updatePlaybackState(paused)
        notify(NOTIFICATION_ID, notification(title, chapter, paused))
    }

    /**
     * Keeps system playback controls in sync, including while no reader is attached.
     */
    private fun updatePlaybackState(paused: Boolean) {
        val state = if (paused) PlaybackState.STATE_PAUSED else PlaybackState.STATE_PLAYING
        mediaSession?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_STOP or
                        PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS,
                )
                .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, if (paused) 0f else 1f)
                .build(),
        )
    }

    private fun notification(title: String, chapter: String, paused: Boolean) = notificationBuilder(
        Notifications.CHANNEL_COMMON,
    ) {
        setSmallIcon(R.drawable.ic_mihon)
        setContentTitle(title.ifBlank { stringResource(MR.strings.leaf_novel_action_speak) })
        setContentText(chapter)
        setOngoing(true)
        setShowWhen(false)
        setSilent(true)
        setContentIntent(readerIntent())
        addAction(
            0,
            stringResource(if (paused) MR.strings.action_resume else MR.strings.action_pause),
            command(ACTION_PLAY_PAUSE),
        )
        addAction(0, stringResource(MR.strings.leaf_novel_action_stop_speaking), command(ACTION_STOP))
    }.build()

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
    }

    companion object {
        private const val ACTION_PLAY_PAUSE = "leaf.novel.SPEECH_PLAY_PAUSE"
        private const val ACTION_STOP = "leaf.novel.SPEECH_STOP"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_CHAPTER = "chapter"
        private const val EXTRA_PAUSED = "paused"

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

        /** Only the resumed reader may receive commands that need its screen. */
        var readerAction: ((NovelReaderAction) -> Unit)? = null

        /** The running instance, so a content update can be posted without asking to be started again. */
        @Volatile
        private var instance: NovelSpeechService? = null

        /** Starts the service, or updates what the notification says when it is already up. */
        fun show(context: Context, title: String, chapter: String, paused: Boolean) {
            val running = instance
            if (running != null) {
                running.update(title, chapter, paused)
                return
            }
            val intent = Intent(context, NovelSpeechService::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_CHAPTER, chapter)
                .putExtra(EXTRA_PAUSED, paused)
            ContextCompat.startForegroundService(context, intent)
        }

        fun hide(context: Context) {
            controls = null
            context.stopService(Intent(context, NovelSpeechService::class.java))
        }
    }
}
