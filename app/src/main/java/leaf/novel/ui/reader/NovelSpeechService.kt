package leaf.novel.ui.reader

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.notificationBuilder
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

/**
 * Keeps read-aloud running while the reader is not on screen.
 *
 * Android stops giving a backgrounded process anything to run on, so speech that outlives the
 * screen has to be attached to something the system has been told about. That is all this is: it
 * owns no speech of its own, it holds the process open and puts the controls where they can be
 * reached with the app away — the voice itself stays with the reader that started it.
 *
 * It is bound to nothing and started with an explicit intent, so it lives exactly as long as speech
 * does. Swiping the reader out of recents takes the whole task down with it, which is the right
 * answer: the queue and the position belong to a reading session that no longer exists.
 */
class NovelSpeechService : Service() {

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

        startForeground(
            NOTIFICATION_ID,
            notification(
                title = intent?.getStringExtra(EXTRA_TITLE).orEmpty(),
                chapter = intent?.getStringExtra(EXTRA_CHAPTER).orEmpty(),
                paused = intent?.getBooleanExtra(EXTRA_PAUSED, false) == true,
            ),
        )
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        controls = null
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

    /** What the notification's buttons do. Implemented by whatever is currently speaking. */
    interface Controls {
        fun togglePlayback()
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

        /** Starts the service, or updates what the notification says when it is already up. */
        fun show(context: Context, title: String, chapter: String, paused: Boolean) {
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
