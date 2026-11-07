package com.nelson.yomukai.ui.reader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.nelson.yomukai.MainActivity
import com.nelson.yomukai.R
import com.nelson.yomukai.i18n.tr

/**
 * Keeps reading aloud alive with the screen off: a foreground service (media playback) with its notification, a
 * partial wake lock while it reads, and a media session so the lock screen, headphones and car buttons can stop it.
 * The reading itself stays in [NovelSpeech]; this only tells Android that the app is busy playing and relays the
 * buttons through [ReadAloud].
 */
class ReadAloudService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var session: MediaSession? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                ReadAloud.onStop?.invoke()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> show(intent?.getStringExtra(EXTRA_TITLE).orEmpty(), intent?.getStringExtra(EXTRA_TEXT).orEmpty())
        }
        return START_NOT_STICKY
    }

    private fun show(title: String, text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, tr("Lectura en voz alta"), NotificationManager.IMPORTANCE_LOW))
        }
        session ?: MediaSession(this, "yomukai-read-aloud").also { s ->
            s.setCallback(object : MediaSession.Callback() {
                override fun onPause() { ReadAloud.onStop?.invoke() }
                override fun onStop() { ReadAloud.onStop?.invoke() }
                override fun onSkipToNext() { ReadAloud.onNext?.invoke() }
            })
            s.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_PLAY_PAUSE)
                    .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                    .build()
            )
            s.isActive = true
            session = s
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, ReadAloudService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_book)
            .setContentTitle(title.ifBlank { tr("Leyendo en voz alta") })
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .addAction(0, tr("Parar"), stop)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NOTIFICATION_ID, notification)
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "yomukai:read-aloud").apply {
                setReferenceCounted(false)
                // Safety net: a reading session never keeps the phone awake for more than 6 hours.
                acquire(6 * 3_600_000L)
            }
        }
    }

    override fun onDestroy() {
        wakeLock?.runCatching { if (isHeld) release() }
        wakeLock = null
        session?.release()
        session = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "read_aloud"
        private const val NOTIFICATION_ID = 4711
        private const val ACTION_STOP = "com.nelson.yomukai.READ_ALOUD_STOP"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TEXT = "text"

        fun start(context: Context, title: String, text: String) {
            val intent = Intent(context, ReadAloudService::class.java).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_TEXT, text)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ReadAloudService::class.java))
        }
    }
}

/** What the notification and the media buttons do, set by the reader that is reading. */
object ReadAloud {
    @Volatile
    var onStop: (() -> Unit)? = null

    @Volatile
    var onNext: (() -> Unit)? = null
}
