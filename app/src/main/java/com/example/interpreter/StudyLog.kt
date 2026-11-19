package com.example.interpreter

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * What was studied each day, kept on the phone for the daily report: time, sentences listened to,
 * news and book parts read, words saved, quiz and review results, AI talk turns.
 * One small file per day; kept 120 days.
 */
object StudyLog {
    private val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /** Set at app start, for screens that report without a context at hand. */
    @Volatile var app: Context? = null

    fun today(): String = day.format(Date())

    private fun dir(ctx: Context) = File(ctx.filesDir, "studylog").apply { mkdirs() }
    private fun file(ctx: Context, date: String) = File(dir(ctx), "$date.json")

    @Synchronized
    fun load(ctx: Context, date: String = today()): JSONObject = try {
        file(ctx, date).takeIf { it.exists() }?.readText()?.let { JSONObject(it) } ?: JSONObject()
    } catch (_: Exception) { JSONObject() }

    @Synchronized
    private fun update(ctx: Context, change: (JSONObject) -> Unit) {
        val date = today()
        val o = load(ctx, date)
        change(o)
        try { file(ctx, date).writeText(o.toString()) } catch (_: Exception) {}
        prune(ctx)
    }

    private var pruned = ""
    private fun prune(ctx: Context) {
        val t = today()
        if (pruned == t) return
        pruned = t
        val cut = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -120) }.time.let { day.format(it) }
        dir(ctx).listFiles()?.filter { it.name.removeSuffix(".json") < cut }?.forEach { it.delete() }
    }

    private fun add(ctx: Context, key: String, n: Int) = update(ctx) { it.put(key, it.optInt(key) + n) }

    private fun addItem(ctx: Context, key: String, item: String, max: Int = 50) = update(ctx) { o ->
        val a = o.optJSONArray(key) ?: JSONArray()
        for (i in 0 until a.length()) if (a.optString(i) == item) return@update
        if (a.length() < max) a.put(item)
        o.put(key, a)
    }

    /* ---------- What the screens report ---------- */

    fun studied(ctx: Context, seconds: Long) { if (seconds in 3..4 * 3600) add(ctx, "seconds", seconds.toInt()) }
    fun listened(ctx: Context) = add(ctx, "sentences", 1)
    fun read(ctx: Context, title: String) = addItem(ctx, "read", title)
    fun saved(ctx: Context, word: String) = addItem(ctx, "words", word, 100)
    fun talked(ctx: Context) = add(ctx, "talk", 1)

    fun reviewed(ctx: Context, word: String, knew: Boolean) = update(ctx) { o ->
        o.put("review", o.optInt("review") + 1)
        if (knew) o.put("review_ok", o.optInt("review_ok") + 1)
        else {
            val a = o.optJSONArray("missed") ?: JSONArray()
            if ((0 until a.length()).none { a.optString(it) == word } && a.length() < 50) a.put(word)
            o.put("missed", a)
        }
    }

    fun quiz(ctx: Context, correct: Int, total: Int, missed: List<String>) = update(ctx) { o ->
        o.put("quiz", o.optInt("quiz") + total)
        o.put("quiz_ok", o.optInt("quiz_ok") + correct)
        val a = o.optJSONArray("missed") ?: JSONArray()
        for (w in missed) if ((0 until a.length()).none { a.optString(it) == w } && a.length() < 50) a.put(w)
        o.put("missed", a)
    }

    private fun active(o: JSONObject) =
        o.optInt("seconds") >= 60 || o.optInt("sentences") > 0 || o.optInt("review") > 0 || o.optInt("quiz") > 0 ||
            (o.optJSONArray("read")?.length() ?: 0) > 0 || o.optInt("talk") > 0

    /** Days in a row with some study, counting today if studied. */
    fun streak(ctx: Context): Int {
        val c = Calendar.getInstance()
        var n = 0
        if (!active(load(ctx, day.format(c.time)))) c.add(Calendar.DAY_OF_YEAR, -1)
        while (n < 365 && active(load(ctx, day.format(c.time)))) { n++; c.add(Calendar.DAY_OF_YEAR, -1) }
        return n
    }

    /* ---------- The report ---------- */

    class Report(val title: String, val lines: List<String>, val text: String, val empty: Boolean)

    /** Today's summary in my language, as lines (for the card) and as one message. */
    fun report(ctx: Context, name: String, home: String, studyLang: String): Report {
        val o = load(ctx)
        val t = ReportText
        val minutes = (o.optInt("seconds") + 30) / 60
        val date = SimpleDateFormat(t.t("date_format"), Locale.forLanguageTag(home)).format(Date())
        val title = t.f("title", date)
        val lines = ArrayList<String>()
        if (studyLang.isNotEmpty()) lines += t.f("lang", studyLang)
        lines += t.f("time", minutes)
        if (o.optInt("sentences") > 0) lines += t.f("sentences", o.optInt("sentences"))
        val read = o.optJSONArray("read")
        if (read != null && read.length() > 0) {
            lines += t.f("read", read.length())
            for (i in 0 until minOf(read.length(), 5)) lines += "  · " + read.optString(i)
        }
        val words = o.optJSONArray("words")
        if (words != null && words.length() > 0) {
            lines += t.f("words", words.length()) + ": " + (0 until minOf(words.length(), 8)).joinToString(", ") { words.optString(it) }
        }
        val tests = o.optInt("quiz") + o.optInt("review")
        val ok = o.optInt("quiz_ok") + o.optInt("review_ok")
        if (tests > 0) lines += t.f("test", tests, ok, ok * 100 / tests)
        val missed = o.optJSONArray("missed")
        if (missed != null && missed.length() > 0) {
            lines += t.t("missed") + ": " + (0 until minOf(missed.length(), 8)).joinToString(", ") { missed.optString(it) }
        }
        if (o.optInt("talk") > 0) lines += t.f("talk", o.optInt("talk"))
        val streak = streak(ctx)
        if (streak > 1) lines += t.f("streak", streak)
        val greeting = if (name.isNotBlank()) t.f("greeting", name) + "\n\n" else ""
        val empty = !active(o)
        val text = greeting + title + "\n\n" + (if (empty) t.t("none") else lines.joinToString("\n")) + "\n\n— " + t.t("sign")
        return Report(title, lines, text, empty)
    }
}

// Texts of the daily report. Order: ko, ja, en, zh (other languages use English).
object ReportText {
    fun t(key: String): String = table[key]?.let { it[I18n.lang] ?: it["en"] } ?: key
    fun f(key: String, vararg args: Any): String = String.format(t(key), *args)
    private fun m(ko: String, ja: String, en: String, zh: String) = mapOf("ko" to ko, "ja" to ja, "en" to en, "zh" to zh)

    private val table = mapOf(
        "date_format" to m("M월 d일 (E)", "M月d日 (E)", "EEE, MMM d", "M月d日 (E)"),
        "title" to m("%s 학습 리포트", "%s の学習レポート", "Study report · %s", "%s 学习报告"),
        "greeting" to m("%s께,", "%sへ", "Dear %s,", "%s："),
        "lang" to m("공부한 언어: %s", "学んだ言語: %s", "Language: %s", "学习语言：%s"),
        "time" to m("공부 시간: %d분", "学習時間: %d分", "Study time: %d min", "学习时间：%d 分钟"),
        "sentences" to m("들은 문장: %d개", "聞いた文: %d", "Sentences listened to: %d", "听过的句子：%d"),
        "read" to m("읽은 글: %d개", "読んだ文章: %d", "Read: %d", "阅读：%d 篇"),
        "words" to m("새 단어 %d개", "新しい単語 %d", "%d new words", "新单词 %d 个"),
        "test" to m("시험·복습: %d문제 중 %d개 정답 (%d%%)", "テスト・復習: %d問中 %d問正解 (%d%%)", "Tests & review: %2\$d of %1\$d right (%3\$d%%)", "测验·复习：%d 题中答对 %d 题（%d%%）"),
        "missed" to m("다시 볼 단어", "見直す単語", "Words to review", "需复习的单词"),
        "talk" to m("AI와 회화: %d번 말함", "AIと会話: %d回発話", "Spoke with the AI: %d times", "与 AI 对话：%d 次"),
        "streak" to m("연속 학습 %d일째", "連続学習 %d日目", "%d-day streak", "连续学习第 %d 天"),
        "none" to m("오늘은 아직 공부 기록이 없어요.", "今日はまだ学習記録がありません。", "No study recorded today yet.", "今天还没有学习记录。"),
        "sign" to m("부엉이 통역", "フクロウ通訳", "Owl Interpreter", "猫头鹰翻译"),
        "menu" to m("✉ 오늘의 학습 리포트 보내기", "✉ 今日の学習レポートを送る", "✉ Send today's study report", "✉ 发送今日学习报告"),
        "send" to m("보내기 (카톡·문자·메일)", "送る（LINE・SMS・メール）", "Send (chat, SMS, mail)", "发送（聊天·短信·邮件）"),
        "settings" to m("받는 사람·알림 시간", "宛先・通知時間", "Recipient & reminder", "收件人·提醒时间"),
        "name_hint" to m("받는 분 (예: 엄마, 아빠)", "宛先（例: お母さん）", "Recipient (e.g. Mom)", "收件人（例如：妈妈）"),
        "remind" to m("매일 알림 시간", "毎日の通知時間", "Daily reminder time", "每日提醒时间"),
        "remind_off" to m("알림 끄기", "通知オフ", "No reminder", "关闭提醒"),
        "notify_title" to m("오늘의 학습 리포트가 준비됐어요", "今日の学習レポートができました", "Today's study report is ready", "今日学习报告已准备好"),
        "notify_text" to m("눌러서 보내기", "タップして送る", "Tap to send", "点按发送"),
        "saved" to m("저장했어요", "保存しました", "Saved", "已保存")
    )
}
