package com.example.interpreter

import android.app.Activity
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
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.widget.Toast

// Keeps phrasebook building running in the background, with a progress notification and a stop button.
class BuildService : Service() {

    companion object {
        private const val ACTION_STOP = "com.example.interpreter.STOP_BUILD"
        private const val EXTRA_FOREIGN = "foreign"
        private const val EXTRA_HOME = "home"
        private const val CHANNEL = "content_build"
        private const val ID = 7001
        private const val DONE_ID = 7002

        fun start(ctx: Context, foreign: String, home: String) {
            val i = Intent(ctx, BuildService::class.java)
                .putExtra(EXTRA_FOREIGN, foreign)
                .putExtra(EXTRA_HOME, home)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ContentBuilder.stop()
            return START_NOT_STICKY
        }
        goForeground("준비 중…")
        if (ContentBuilder.running) return START_NOT_STICKY
        val foreign = intent?.getStringExtra(EXTRA_FOREIGN)
        val home = intent?.getStringExtra(EXTRA_HOME)
        if (foreign == null || home == null) {
            finish(false)
            return START_NOT_STICKY
        }
        acquire()
        ContentBuilder.serviceListener = { msg -> update(msg) }
        val prefs = getSharedPreferences("interpreter", MODE_PRIVATE)
        if (!ContentBuilder.run(this, prefs, foreign, home) { finish(true) }) finish(false)
        return START_NOT_STICKY
    }

    // Only called when the app targets a newer Android with time limits on background work.
    override fun onTimeout(startId: Int) {
        ContentBuilder.stop()
        finish(true)
    }

    private fun manager() = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

    private fun builder(): Notification.Builder {
        if (Build.VERSION.SDK_INT >= 26) {
            manager().createNotificationChannel(
                NotificationChannel(CHANNEL, "콘텐츠 생성", NotificationManager.IMPORTANCE_LOW)
            )
            return Notification.Builder(this, CHANNEL)
        }
        @Suppress("DEPRECATION")
        return Notification.Builder(this)
    }

    private fun openApp(): PendingIntent? {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE)
    }

    private fun title(): String =
        "콘텐츠 생성" + ContentBuilder.runningCombo.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()

    private fun notification(text: String): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BuildService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return builder()
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title())
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .addAction(Notification.Action.Builder(null as Icon?, "멈추기", stop).build())
            .build()
    }

    private fun goForeground(text: String) {
        val n = notification(text)
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ID, n)
    }

    private fun update(text: String) {
        if (ContentBuilder.running) manager().notify(ID, notification(text))
    }

    private fun acquire() {
        if (wakeLock != null) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "interpreter:content_build").apply {
            setReferenceCounted(false)
            acquire(24 * 60 * 60 * 1000L)
        }
    }

    private fun release() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun finish(showResult: Boolean) {
        ContentBuilder.serviceListener = null
        release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (showResult && ContentBuilder.lastMessage.isNotBlank()) {
            val n = builder()
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("콘텐츠 생성")
                .setContentText(ContentBuilder.lastMessage)
                .setStyle(Notification.BigTextStyle().bigText(ContentBuilder.lastMessage))
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .build()
            manager().notify(DONE_ID, n)
        }
        stopSelf()
    }

    override fun onDestroy() {
        ContentBuilder.serviceListener = null
        release()
        super.onDestroy()
    }
}

// Opens the file picker, merges the chosen situations.zip into this phone's content, then closes.
class ImportActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")
            )
        try {
            startActivityForResult(pick, 1)
        } catch (_: Exception) {
            Toast.makeText(this, Ui.plain("파일 선택 창을 열지 못했어요."), Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            finish()
            return
        }
        val app = applicationContext
        val main = Handler(Looper.getMainLooper())
        Thread {
            val msg = try {
                val n = ContentBuilder.importZip(app, uri)
                if (n > 0) "📥 파일 ${n}개를 가져와 합쳤어요."
                else "가져올 회화가 없어요. 상황별 회화 DB(situations.zip)인지 확인하세요. 책 DB 파일이라면 '책 DB 가져오기'를 쓰세요."
            } catch (e: Exception) {
                "가져오지 못했어요: ${e.message}"
            }
            ContentBuilder.report(msg)
            main.post { Toast.makeText(app, Ui.plain(msg), Toast.LENGTH_LONG).show() }
        }.start()
        finish()
    }
}
