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
import eu.kanade.tachiyomi.util.system.notify
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

/**
 * Keeps read-aloud running whether or not the reader is on screen — including after its task has
 * been swiped away.
 *
 * Android stops giving a backgrounded process anything to run on, so speech that outlives the
 * screen has to be attached to something the system has been told about. That is all this is: it
 * owns no speech of its own, it holds the process open and puts the controls where they can be
 * reached with the app away. The voice itself lives in [NovelSpeechSession], at process scope, not
 * with whichever reader happened to start it — a reader being destroyed is not a reason to stop
 * talking partway through a chapter.
 *
 * It is bound to nothing and started with an explicit intent, so it lives exactly as long as speech
 * does. Swiping the task away does not take it with it: `stopWithTask` already defaults to false,
 * and the manifest says so out loud only because a guarantee this change rests on should not be
 * left implicit.
 */
class NovelSpeechService : Service() {

    /**
     * What the notification last showed, so an action intent — which carries none of this — has
     * something to redraw from.
     */
    private var lastTitle = ""
    private var lastChapter = ""
    private var lastPaused = false

    override fun onCreate() {
        super.onCreate()
        instance = this
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
        }

        // The one call this makes to the system: every later update goes through update(), which
        // just reposts the notification. Calling this again for those would ask the system to
        // start the service afresh each time, which is exactly what a background chapter change
        // is not allowed to do on Android 12+.
        startForeground(NOTIFICATION_ID, notification(lastTitle, lastChapter, lastPaused))
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        instance = null
        controls = null
    }

    /** Redraws the already-posted notification. Called only while this instance is alive. */
    private fun update(title: String, chapter: String, paused: Boolean) {
        lastTitle = title
        lastChapter = chapter
        lastPaused = paused
        notify(NOTIFICATION_ID, notification(title, chapter, paused))
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
