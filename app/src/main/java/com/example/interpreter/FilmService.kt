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

/**
 * Keeps "making the video" going when the app is in the background or the screen is off: a foreground
 * service with a progress notification and a stop button, holding a partial wake lock. The work itself
 * runs where it started (NewsActivity's film thread); this only keeps the process awake and shows where
 * it is.
 */
class FilmService : Service() {

    companion object {
        private const val ACTION_STOP = "com.example.interpreter.STOP_FILM"
        private const val ACTION_UPDATE = "com.example.interpreter.UPDATE_FILM"
        private const val ACTION_DONE = "com.example.interpreter.DONE_FILM"
        private const val CHANNEL = "film_make"
        private const val ID = 7101
        private const val DONE_ID = 7102

        @Volatile var running = false
            private set
        /** Set by the film thread; the notification's stop button calls it. */
        @Volatile var onStop: (() -> Unit)? = null
        /** Where the video is, for the progress dialog (which can be closed and opened again). */
        @Volatile var text = ""
        @Volatile var pct = 0
        @Volatile var preview: android.graphics.Bitmap? = null

        private fun send(ctx: Context, action: String?, text: String, pct: Int, first: Boolean) {
            val i = Intent(ctx, FilmService::class.java).setAction(action).putExtra("text", text).putExtra("pct", pct)
            try {
                if (first && Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (_: Exception) {}
        }

        fun start(ctx: Context, text: String) {
            running = true; this.text = text; pct = 0; preview = null
            send(ctx, null, text, 0, true)
        }
        fun update(ctx: Context, text: String, pct: Int) { if (running) send(ctx, ACTION_UPDATE, text, pct, false) }
        /** [text] empty: no "done" notification (cancelled). */
        fun finish(ctx: Context, text: String) {
            onStop = null
            if (running) send(ctx, ACTION_DONE, text, 100, false)
            running = false
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var lastText = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra("text").orEmpty()
        val pct = intent?.getIntExtra("pct", 0) ?: 0
        when (intent?.action) {
            ACTION_STOP -> { onStop?.invoke(); update(StudyText.t("video_cancel") + "…", pct) }
            ACTION_UPDATE -> update(text, pct)
            ACTION_DONE -> finish(text)
            else -> { goForeground(text); acquire() }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int) {
        onStop?.invoke()
        finish("")
    }

    private fun manager() = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

    private fun builder(): Notification.Builder {
        if (Build.VERSION.SDK_INT >= 26) {
            manager().createNotificationChannel(NotificationChannel(CHANNEL, Ui.plain(StudyText.t("video_make")), NotificationManager.IMPORTANCE_LOW))
            return Notification.Builder(this, CHANNEL)
        }
        @Suppress("DEPRECATION")
        return Notification.Builder(this)
    }

    private fun openApp(): PendingIntent? {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE)
    }

    private fun notification(text: String, pct: Int): Notification {
        val stop = PendingIntent.getService(this, 1, Intent(this, FilmService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return builder()
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(Ui.plain("🎬 " + StudyText.t("video_make")))
            .setContentText(Ui.plain(text))
            .setStyle(Notification.BigTextStyle().bigText(Ui.plain(text)))
            .setProgress(100, pct, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .addAction(Notification.Action.Builder(null as Icon?, StudyText.t("video_cancel"), stop).build())
            .build()
    }

    private fun goForeground(text: String) {
        val n = notification(text, 0)
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ID, n)
    }

    private fun update(text: String, pct: Int) {
        if (text == lastText && pct == 0) return
        lastText = text
        manager().notify(ID, notification(text, pct))
    }

    private fun acquire() {
        if (wakeLock != null) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "interpreter:film").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)
        }
    }

    private fun finish(text: String) {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (text.isNotBlank()) {
            manager().notify(DONE_ID, builder()
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(Ui.plain("🎬 " + StudyText.t("video_make")))
                .setContentText(Ui.plain(text))
                .setStyle(Notification.BigTextStyle().bigText(Ui.plain(text)))
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .build())
        }
        stopSelf()
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }
}
