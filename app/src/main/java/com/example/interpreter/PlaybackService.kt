package com.example.interpreter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

// Keeps situation dialogue playing with the screen off: a media foreground service with a
// notification (tap to open, button to stop) and a CPU wake lock while it plays.
class PlaybackService : Service() {

    companion object {
        private const val ACTION_STOP = "com.example.interpreter.STOP_PLAYBACK"
        private const val EXTRA_TITLE = "title"
        private const val CHANNEL = "situation_playback"
        private const val ID = 7101

        // Set by the screen that plays, so the notification's stop button can reach it.
        @Volatile var onStopRequested: (() -> Unit)? = null
        @Volatile private var running = false

        fun start(ctx: Context, title: String) {
            val i = Intent(ctx, PlaybackService::class.java).putExtra(EXTRA_TITLE, title)
            try {
                ctx.startForegroundService(i)
            } catch (e: Exception) {
                // Starting from the background can be refused; playback still works on screen.
            }
        }

        fun stop(ctx: Context) {
            if (!running) return
            ctx.stopService(Intent(ctx, PlaybackService::class.java))
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            onStopRequested?.invoke()
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
        val n = notification(title)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(ID, n)
        }
        if (wakeLock == null) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "interpreter:situation")
                .apply { acquire(60 * 60 * 1000L) }
        }
        return START_NOT_STICKY
    }

    private fun notification(title: String): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, TalkText.t("s_bg_channel"), NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                ?: Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, PlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(Ui.plain(TalkText.t("s_bg_playing")))
            .setContentText(title)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_media_pause), TalkText.t("s_stop"), stop
                ).build()
            )
            .build()
    }

    override fun onDestroy() {
        running = false
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }
}
