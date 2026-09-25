package leaf.novel.ui.reader

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.view.KeyEvent
import androidx.core.content.IntentCompat
import leaf.novel.ui.reader.setting.NovelReaderAction
import leaf.novel.ui.reader.setting.NovelReaderKey
import leaf.novel.ui.reader.setting.NovelReaderPreferences
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * One media session shared by the loaded reader and background speech. Stopping speech leaves
 * Play available while the reader's ViewModel is alive, including with the app in the background.
 * All owners attach, update and detach on the main thread.
 */
internal object NovelReaderMediaSession {
    private var session: MediaSession? = null
    private var routingAudio: AudioTrack? = null
    private var preferences: NovelReaderPreferences? = null
    private var readerAction: ((NovelReaderAction) -> Unit)? = null
    private var speechAction: ((NovelReaderAction) -> Unit)? = null
    private var paused = true

    fun attachReader(context: Context, preferences: NovelReaderPreferences, action: (NovelReaderAction) -> Unit) {
        readerAction = action
        ensureSession(context, preferences)
        if (speechAction == null) {
            update(paused = true)
            registerAudioPlayback()
        }
    }

    fun detachReader() {
        readerAction = null
        releaseIfUnused()
    }

    fun attachSpeech(context: Context, preferences: NovelReaderPreferences, action: (NovelReaderAction) -> Unit) {
        speechAction = action
        ensureSession(context, preferences)
    }

    fun detachSpeech() {
        speechAction = null
        if (readerAction != null) update(paused = true)
        releaseIfUnused()
    }

    fun dispatchReaderAction(action: NovelReaderAction) {
        readerAction?.invoke(action)
    }

    private fun performAction(action: NovelReaderAction) {
        (speechAction ?: readerAction)?.invoke(action)
    }

    private fun performKey(key: NovelReaderKey) {
        val binding = preferences?.keys?.getValue(key)?.get() ?: return
        performAction(key.resolve(binding))
    }

    private val callback = object : MediaSession.Callback() {
        override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
            val event = IntentCompat.getParcelableExtra(mediaButtonIntent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                ?: return super.onMediaButtonEvent(mediaButtonIntent)
            if (NovelReaderKey.of(event.keyCode) == null) return super.onMediaButtonEvent(mediaButtonIntent)
            dispatchNovelReaderKey(
                keyCode = event.keyCode,
                eventAction = event.action,
                repeatCount = event.repeatCount,
                binding = { preferences?.keys?.getValue(it)?.get() ?: NovelReaderAction.NONE },
                perform = ::performAction,
            )
            // Disabled media bindings must not fall through to the platform's default action.
            return true
        }

        override fun onPlay() = performKey(NovelReaderKey.MEDIA_PLAY)
        override fun onPause() = performKey(NovelReaderKey.MEDIA_PAUSE)
        override fun onStop() = performKey(NovelReaderKey.MEDIA_STOP)
        override fun onSkipToNext() = performKey(NovelReaderKey.MEDIA_NEXT)
        override fun onSkipToPrevious() = performKey(NovelReaderKey.MEDIA_PREVIOUS)
    }

    private fun ensureSession(context: Context, preferences: NovelReaderPreferences) {
        this.preferences = preferences
        if (session != null) return
        session = MediaSession(context.applicationContext, "NovelSpeech").apply {
            setCallback(callback)
            isActive = true
        }
        paused = true
    }

    fun update(paused: Boolean) {
        val resuming = this.paused && !paused
        this.paused = paused
        session?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS,
                )
                .setState(
                    if (paused) PlaybackState.STATE_PAUSED else PlaybackState.STATE_PLAYING,
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    if (paused) 0f else 1f,
                )
                .build(),
        )
        if (resuming) registerAudioPlayback()
    }

    private fun releaseIfUnused() {
        if (readerAction != null || speechAction != null) return
        routingAudio?.release()
        routingAudio = null
        session?.release()
        session = null
        preferences = null
    }

    /**
     * Android selects media-button recipients by audio playback UID. TTS plays under its engine's
     * UID, so a short silent buffer registers this app when speech resumes or an idle reader opens.
     * It does not loop or request audio focus.
     *
     * Only a routing hint, so a device out of audio tracks, or one refusing this format, loses the
     * hint rather than the reader: both throw from the builder or from `play`.
     */
    private fun registerAudioPlayback() {
        routingAudio?.release()
        routingAudio = null
        runCatching(::playSilence).onFailure {
            routingAudio?.release()
            routingAudio = null
            logcat(LogPriority.WARN, it) { "Could not register for media buttons" }
        }
    }

    private fun playSilence() {
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
}
