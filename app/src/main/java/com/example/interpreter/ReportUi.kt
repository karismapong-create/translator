package com.example.interpreter

import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.Calendar

/** The daily study report: preview, a card picture, sharing to chat/SMS/mail, and a daily reminder. */
object ReportUi {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("interpreter", Context.MODE_PRIVATE)

    private fun studyLangName(ctx: Context): String {
        val code = prefs(ctx).getString("news_lang", null) ?: return ""
        return try { Languages.byCode(code).native } catch (_: Exception) { "" }
    }

    fun show(activity: Activity) {
        val p = prefs(activity)
        val home = Languages.home(p).code
        val r = StudyLog.report(activity, p.getString("report_name", "").orEmpty(), home, studyLangName(activity))
        val card = drawCard(activity, r)
        val dp = { v: Int -> Ui.dp(activity, v) }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            addView(ImageView(activity).apply {
                setImageBitmap(card)
                adjustViewBounds = true
            })
        }
        AlertDialog.Builder(activity)
            .setView(ScrollView(activity).apply { addView(box) })
            .setPositiveButton(ReportText.t("send")) { _, _ -> share(activity, r, card) }
            .setNeutralButton(ReportText.t("settings")) { _, _ -> settings(activity) }
            .setNegativeButton(I18n.t("close"), null)
            .show().also { Ui.watch(it) }
    }

    // A card for chat apps: title, then one line per item.
    private fun drawCard(ctx: Context, r: StudyLog.Report): Bitmap {
        val w = 1080
        val pad = 72f
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 60f; typeface = Typeface.DEFAULT_BOLD }
        val linePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.INK; textSize = 44f }
        val body = if (r.empty) ReportText.t("none") else r.lines.joinToString("\n")
        val titleLayout = StaticLayout.Builder.obtain(r.title, 0, r.title.length, titlePaint, (w - pad * 2).toInt()).build()
        val bodyLayout = StaticLayout.Builder.obtain(body, 0, body.length, linePaint, (w - pad * 2).toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(14f, 1f).build()
        val head = titleLayout.height + pad * 2
        val h = (head + bodyLayout.height + pad * 2 + 60).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(245, 247, 252))
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.PRIMARY }
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), head + 40f), 0f, 0f, bg)
        c.save(); c.translate(pad, pad); titleLayout.draw(c); c.restore()
        val sheet = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        c.drawRoundRect(RectF(36f, head, w - 36f, h - 36f), 40f, 40f, sheet)
        c.save(); c.translate(pad, head + pad * 0.8f); bodyLayout.draw(c); c.restore()
        val sign = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.INK_SOFT; textSize = 32f; textAlign = Paint.Align.RIGHT }
        c.drawText(ReportText.t("sign"), w - pad, h - 60f, sign)
        return bmp
    }

    private fun share(activity: Activity, r: StudyLog.Report, card: Bitmap) {
        val send = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, r.text).putExtra(Intent.EXTRA_SUBJECT, r.title)
        try {
            val dir = File(activity.cacheDir, "reports").apply { mkdirs() }
            val f = File(dir, "study-report.png")
            f.outputStream().use { card.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val uri = androidx.core.content.FileProvider.getUriForFile(activity, activity.packageName + ".files", f)
            send.type = "image/png"
            send.putExtra(Intent.EXTRA_STREAM, uri)
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
            send.type = "text/plain"
        }
        try { activity.startActivity(Intent.createChooser(send, Ui.plain(ReportText.t("send")))) } catch (_: Exception) {}
    }

    private fun settings(activity: Activity) {
        val p = prefs(activity)
        val dp = { v: Int -> Ui.dp(activity, v) }
        val name = EditText(activity).apply {
            hint = ReportText.t("name_hint")
            setText(p.getString("report_name", ""))
        }
        val hour = NumberPicker(activity).apply {
            minValue = 0; maxValue = 24
            displayedValues = (0..23).map { "%02d:00".format(it) }.toTypedArray() + ReportText.t("remind_off")
            value = p.getInt("report_hour", 21).let { if (it < 0) 24 else it }
            wrapSelectorWheel = false
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(name)
            addView(TextView(activity).apply { text = ReportText.t("remind"); setPadding(0, dp(12), 0, 0) })
            addView(hour)
        }
        AlertDialog.Builder(activity)
            .setTitle(ReportText.t("settings"))
            .setView(box)
            .setPositiveButton(StudyText.t("fix_save")) { _, _ ->
                p.edit().putString("report_name", name.text.toString().trim())
                    .putInt("report_hour", if (hour.value == 24) -1 else hour.value).apply()
                schedule(activity)
                // Android 13+: the reminder needs permission to show a notification.
                if (hour.value != 24 && android.os.Build.VERSION.SDK_INT >= 33 &&
                    activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    activity.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 77)
                }
                show(activity)
            }
            .setNegativeButton(I18n.t("close"), null)
            .show().also { Ui.watch(it) }
    }

    /* ---------- Daily reminder ---------- */

    /** Sets the next reminder (at the chosen hour), or clears it when off. */
    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(ctx, 7, Intent(ctx, ReportReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        am.cancel(pi)
        val h = prefs(ctx).getInt("report_hour", 21)
        if (h < 0) return
        val at = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
            if (timeInMillis <= System.currentTimeMillis() + 60_000) add(Calendar.DAY_OF_YEAR, 1)
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.timeInMillis, pi)
    }

    fun notifyReady(ctx: Context) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("report", Ui.plain(ReportText.t("menu")), NotificationManager.IMPORTANCE_DEFAULT))
            val open = Intent(ctx, MainActivity::class.java).putExtra("report", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pi = PendingIntent.getActivity(ctx, 8, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            nm.notify(8008, Notification.Builder(ctx, "report")
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentTitle(Ui.plain(ReportText.t("notify_title")))
                .setContentText(Ui.plain(ReportText.t("notify_text")))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build())
        } catch (_: Exception) {}
    }
}

// The daily reminder: a notification that opens the report, then the next day's reminder.
class ReportReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ReportUi.notifyReady(context)
        ReportUi.schedule(context)
    }
}
