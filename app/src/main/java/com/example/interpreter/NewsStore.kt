package com.example.interpreter

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// World news for study, one file per day. Source: Wikipedia's "Current events" portal, a daily
// summary of world news under CC BY-SA 4.0 (free to use commercially with attribution). Each item
// is later rewritten by the AI for the learner's language and level (cached per item, language, level).
object NewsStore {
    private const val KEEP_DAYS = 60
    // Wikipedia asks API clients to identify themselves with a way to reach the maintainer.
    private const val UA = "OwlInterpreter/1.0 (Android app; https://karismapong-create.github.io/translator/)"

    class Item(val topic: String, val text: String, val section: String, var category: Int, var read: Boolean)

    private fun dir(ctx: Context) = File(ctx.filesDir, "news").apply { mkdirs() }
    private fun lessonDir(ctx: Context) = File(dir(ctx), "lessons").apply { mkdirs() }
    private fun dayFile(ctx: Context, date: String) = File(dir(ctx), "$date.json")

    // Portal pages are dated in UTC.
    private fun utc() = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    fun recentDates(n: Int): List<String> {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        return (0 until n).map {
            val d = utc().format(cal.time)
            cal.add(Calendar.DAY_OF_MONTH, -1)
            d
        }
    }

    // Saved days, newest first.
    fun dates(ctx: Context): List<String> =
        dir(ctx).listFiles()?.map { it.name }?.filter { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}\\.json")) }
            ?.map { it.removeSuffix(".json") }?.sortedDescending().orEmpty()

    fun load(ctx: Context, date: String): List<Item> {
        val f = dayFile(ctx, date)
        if (!f.exists()) return emptyList()
        val arr = try { JSONObject(f.readText()).optJSONArray("items") } catch (_: Exception) { null } ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let {
                Item(it.optString("topic"), it.optString("text"), it.optString("section"), it.optInt("c", 23), it.optBoolean("r"))
            }
        }
    }

    private fun save(ctx: Context, date: String, items: List<Item>, classified: Boolean) {
        val arr = JSONArray()
        items.forEach {
            arr.put(JSONObject().put("topic", it.topic).put("text", it.text).put("section", it.section)
                .put("c", it.category).put("r", it.read))
        }
        dayFile(ctx, date).writeText(
            JSONObject().put("date", date).put("fetched", System.currentTimeMillis())
                .put("classified", classified).put("items", arr).toString()
        )
    }

    private fun meta(ctx: Context, date: String): JSONObject? =
        dayFile(ctx, date).takeIf { it.exists() }?.let { try { JSONObject(it.readText()) } catch (_: Exception) { null } }

    @Synchronized
    fun markRead(ctx: Context, date: String, index: Int) {
        val m = meta(ctx, date) ?: return
        val items = load(ctx, date)
        items.getOrNull(index)?.read = true
        val arr = JSONArray()
        items.forEach {
            arr.put(JSONObject().put("topic", it.topic).put("text", it.text).put("section", it.section)
                .put("c", it.category).put("r", it.read))
        }
        dayFile(ctx, date).writeText(m.put("items", arr).toString())
    }

    // Fetches the last few days: missing days, and today/yesterday again when older than three hours
    // (the portal keeps growing during the day). Removes days older than two months. Network thread.
    @Synchronized
    fun update(ctx: Context, prefs: SharedPreferences?) {
        val now = System.currentTimeMillis()
        recentDates(4).forEachIndexed { i, date ->
            val m = meta(ctx, date)
            val stale = m == null || (i <= 1 && now - m.optLong("fetched") > 3 * 3600 * 1000L)
            if (!stale) return@forEachIndexed
            try {
                val items = fetch(date)
                if (items.isNotEmpty()) {
                    // Keep categories and read marks of items we already had.
                    val old = load(ctx, date).associateBy { it.text }
                    var known = true
                    items.forEach { n ->
                        val o = old[n.text]
                        if (o != null) { n.category = o.category; n.read = o.read } else known = false
                    }
                    save(ctx, date, items, known && m?.optBoolean("classified") == true)
                }
            } catch (_: Exception) {}
        }
        if (prefs != null) dates(ctx).take(4).forEach { try { classify(ctx, prefs, it) } catch (_: Exception) {} }
        val cutoff = recentDates(KEEP_DAYS).last()
        dir(ctx).listFiles()?.forEach { f ->
            val d = f.name.take(10)
            if (f.isFile && d.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) && d < cutoff) f.delete()
        }
        lessonDir(ctx).listFiles()?.forEach { f -> if (f.name.take(10) < cutoff) f.delete() }
    }

    private fun fetch(date: String): List<Item> {
        val d = utc().parse(date) ?: return emptyList()
        val title = "Portal:Current events/" + SimpleDateFormat("yyyy MMMM d", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(d)
        val url = "https://en.wikipedia.org/w/api.php?action=parse&prop=wikitext&format=json&formatversion=2&page=" +
            URLEncoder.encode(title, "UTF-8")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 12000
        conn.setRequestProperty("User-Agent", UA)
        try {
            if (conn.responseCode !in 200..299) return emptyList()
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val text = json.optJSONObject("parse")?.optString("wikitext").orEmpty()
            return parse(text)
        } finally {
            conn.disconnect()
        }
    }

    // Section headings are bold lines ('''Politics and elections'''); news sentences are the deepest
    // bullets, under topic bullets. Links, references and templates are reduced to plain text.
    fun parse(wikitext: String): List<Item> {
        val lines = wikitext.lines().map { it.trim() }
        val out = ArrayList<Item>()
        var section = ""
        val stack = ArrayList<String>()
        for ((i, line) in lines.withIndex()) {
            val heading = Regex("^'''(.+?)'''$").find(line)
            if (heading != null) {
                section = clean(heading.groupValues[1])
                stack.clear()
                continue
            }
            if (!line.startsWith("*")) continue
            val depth = line.takeWhile { it == '*' }.length
            val text = clean(line.drop(depth))
            while (stack.size >= depth) stack.removeAt(stack.size - 1)
            while (stack.size < depth - 1) stack.add("")
            stack.add(text)
            val next = lines.drop(i + 1).firstOrNull { it.isNotEmpty() }
            val nextDepth = if (next != null && next.startsWith("*")) next.takeWhile { it == '*' }.length else 0
            if (nextDepth <= depth && text.length > 25) {
                val topic = stack.dropLast(1).filter { it.isNotBlank() }.joinToString(" — ")
                out.add(Item(topic, text, section, sectionCategory(section), false))
            }
        }
        return out
    }

    private fun clean(s: String): String {
        var t = s
        t = t.replace(Regex("(?s)<ref[^>]*?/>"), "").replace(Regex("(?s)<ref.*?</ref>"), "")
        t = t.replace(Regex("<!--.*?-->"), "")
        // Source links at the end: ([https://… (Reuters)] [https://… (AP)])
        t = t.replace(Regex("\\(\\s*(\\[https?://[^\\]]*\\]\\s*)+\\)"), "")
        t = t.replace(Regex("\\[https?://\\S+\\s+([^\\]]*)\\]"), "$1").replace(Regex("\\[https?://\\S+\\]"), "")
        t = t.replace(Regex("\\[\\[(?:[^\\]|]*\\|)?([^\\]]*)\\]\\]"), "$1")
        repeat(3) { t = t.replace(Regex("\\{\\{[^{}]*\\}\\}"), "") }
        t = t.replace("'''", "").replace("''", "").replace(Regex("<[^>]+>"), "")
        return t.replace(Regex("\\s+"), " ").trim()
    }

    // The portal's ten sections, mapped to the app's categories (refined by the AI when available).
    private fun sectionCategory(section: String): Int {
        val s = section.lowercase()
        return when {
            "armed" in s || "attack" in s -> 6
            "art" in s || "culture" in s -> 18
            "business" in s || "economy" in s -> 2
            "disaster" in s || "accident" in s -> 9
            "health" in s || "environment" in s -> 10
            "international" in s -> 5
            "law" in s || "crime" in s -> 8
            "politic" in s || "election" in s -> 0
            "science" in s || "technology" in s -> 12
            "sport" in s -> 20
            else -> 23
        }
    }

    // One AI call sorts a day's items into the 24 categories.
    private fun classify(ctx: Context, prefs: SharedPreferences, date: String) {
        val m = meta(ctx, date) ?: return
        if (m.optBoolean("classified")) return
        val engine = RemoteTranslate.engine(prefs)
        if (!RemoteTranslate.canWrite(engine)) return
        val items = load(ctx, date)
        if (items.isEmpty()) return
        val names = StudyText.categories.mapIndexed { i, c -> "$i=${c["en"]}" }.joinToString(", ")
        val list = items.mapIndexed { i, it -> "$i. ${it.topic.take(80)} | ${it.text.take(200)}" }.joinToString("\n")
        val system = "Classify each news item into one category. Categories: $names. " +
            "Reply with JSON only: {\"c\":[category number for item 0, item 1, ...]} with exactly ${items.size} numbers."
        val raw = RemoteTranslate.ask(prefs, engine!!, system, list, 1200)
        val arr = Tutor.json(raw)?.optJSONArray("c") ?: return
        if (arr.length() != items.size) return
        items.forEachIndexed { i, it -> it.category = arr.optInt(i, it.category).coerceIn(0, StudyText.categories.size - 1) }
        save(ctx, date, items, true)
    }

    /* ---------- Lessons ---------- */

    private fun lessonFile(ctx: Context, date: String, index: Int, lang: String, level: Int) =
        File(lessonDir(ctx), "${date}_${index}_${lang}_$level.json")

    fun lesson(ctx: Context, date: String, index: Int, lang: String, level: Int): JSONObject? =
        lessonFile(ctx, date, index, lang, level).takeIf { it.exists() }
            ?.let { try { JSONObject(it.readText()) } catch (_: Exception) { null } }

    fun saveLesson(ctx: Context, date: String, index: Int, lang: String, level: Int, o: JSONObject) =
        lessonFile(ctx, date, index, lang, level).writeText(o.toString())

    fun portalUrl(date: String): String {
        val d = utc().parse(date) ?: Date()
        return "https://en.wikipedia.org/wiki/Portal:Current_events/" +
            SimpleDateFormat("yyyy_MMMM_d", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(d)
    }

    /* ---------- Daily background update ---------- */

    private const val JOB_ID = 7301

    fun schedule(ctx: Context) {
        val js = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        if (js.getPendingJob(JOB_ID) != null) return
        js.schedule(
            JobInfo.Builder(JOB_ID, ComponentName(ctx, NewsJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(24L * 3600 * 1000)
                .build()
        )
    }
}

// Runs once a day with network, so the day's news is ready when the user opens the screen.
class NewsJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            try { NewsStore.update(applicationContext, getSharedPreferences("interpreter", MODE_PRIVATE)) } catch (_: Exception) {}
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters) = true
}
