package com.example.interpreter

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Client for Owl Studio on the user's Mac (tools/owl-studio): a part's pictures are drawn there with
 * local Qwen models. The app sends the job, then fetches each picture as soon as it is ready; the
 * job goes on on the Mac if the phone sleeps, and sending the same part again resumes it.
 */
object MacStudio {
    fun base(raw: String): String {
        var b = raw.trim().trimEnd('/')
        if (b.isEmpty()) return ""
        if (!b.startsWith("http://") && !b.startsWith("https://")) b = "http://$b"
        if (!Regex(":\\d+$").containsMatchIn(b.substringAfter("://"))) b += ":8765"
        return b
    }

    fun configured(prefs: SharedPreferences) = base(prefs.getString("mac_url", "").orEmpty()).isNotEmpty()
    private fun url(prefs: SharedPreferences) = base(prefs.getString("mac_url", "").orEmpty())
    private fun token(prefs: SharedPreferences) = prefs.getString("mac_token", "").orEmpty().trim()

    private fun call(base: String, token: String, method: String, path: String, body: String? = null, timeout: Int = 20000): Pair<Int, ByteArray> {
        val c = URL(base + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 8000
        c.readTimeout = timeout
        if (token.isNotEmpty()) c.setRequestProperty("X-Owl-Token", token)
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        return try {
            val code = c.responseCode
            val bytes = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
            code to bytes
        } finally { c.disconnect() }
    }

    /** One line for the "test the connection" button. */
    fun health(base: String, token: String): String {
        if (base.isEmpty()) return "✘ address?"
        val (code, b) = call(base, token, "GET", "/health")
        if (code == 401) return "✘ token"
        if (code !in 200..299) return "✘ HTTP $code"
        val o = JSONObject(String(b))
        return "OK · Owl Studio · " + o.optString("llm") + " · " + o.optString("edit_model").substringAfterLast('/') +
            " · " + (o.optJSONArray("size")?.let { "${it.optInt(0)}×${it.optInt(1)}" } ?: "")
    }

    class Status(val state: String, val total: Int, val done: List<Int>, val failed: List<Int>, val current: Int,
                 val queue: Int, val error: String, val lastError: String)

    private fun status(o: JSONObject): Status {
        fun ints(a: JSONArray?) = (0 until (a?.length() ?: 0)).map { a!!.optInt(it) }
        return Status(o.optString("state"), o.optInt("total"), ints(o.optJSONArray("done")), ints(o.optJSONArray("failed")),
            o.optInt("current", -1), o.optInt("queue"), o.optString("error"), o.optString("last_error"))
    }

    /** Starts (or resumes) the part's job; returns its id and status. [restart] draws everything again. */
    fun submit(prefs: SharedPreferences, key: String, bookId: Int, title: String, author: String, chapter: String,
               sentences: List<String>, restart: Boolean, before: String = "", after: String = ""): Pair<String, Status> {
        val body = JSONObject().put("id", key).put("book_id", bookId.toString()).put("title", title).put("author", author)
            .put("chapter", chapter).put("sentences", JSONArray(sentences)).put("restart", restart)
            .put("before", before).put("after", after)
        val (code, b) = call(url(prefs), token(prefs), "POST", "/jobs", body.toString())
        if (code == 401) throw IllegalStateException("Owl Studio: token")
        if (code !in 200..299) throw IllegalStateException("Owl Studio: HTTP $code " + String(b).take(160))
        val o = JSONObject(String(b))
        return o.optString("id") to status(o)
    }

    fun poll(prefs: SharedPreferences, id: String): Status {
        val (code, b) = call(url(prefs), token(prefs), "GET", "/jobs/$id")
        if (code !in 200..299) throw IllegalStateException("Owl Studio: HTTP $code")
        return status(JSONObject(String(b)))
    }

    fun fetch(prefs: SharedPreferences, id: String, i: Int, to: File): Boolean {
        val (code, b) = call(url(prefs), token(prefs), "GET", "/jobs/$id/art/$i.jpg", timeout = 60000)
        if (code !in 200..299 || b.isEmpty()) return false
        to.writeBytes(b)
        return true
    }

    /**
     * One sentence's picture, drawn on the Mac as a one-sentence job (its neighbours go along as context,
     * and the book's character sheet on the Mac keeps the characters the same). Waits for it: a picture
     * takes minutes, longer if the Mac is busy with a video. Runs on a background thread.
     */
    fun drawOne(prefs: SharedPreferences, key: String, bookId: Int, title: String, author: String, chapter: String,
                sentence: String, before: String, after: String, again: Boolean, status: ((String) -> Unit)? = null): ByteArray {
        var (id, st) = submit(prefs, key, bookId, title, author, chapter, listOf(sentence), again, before, after)
        var misses = 0
        val until = System.currentTimeMillis() + 3 * 60 * 60_000L
        while (System.currentTimeMillis() < until) {
            when {
                0 in st.done -> {
                    val f = File.createTempFile("mac", ".jpg")
                    try { if (fetch(prefs, id, 0, f)) return f.readBytes() } finally { f.delete() }
                }
                st.state == "done" -> throw IllegalStateException("Owl Studio: " + st.lastError.ifBlank { "no picture" }.take(200))
                st.state == "error" -> throw IllegalStateException("Owl Studio: " + st.error.ifBlank { st.lastError }.take(200))
                st.state == "cancelled" -> throw IllegalStateException("Owl Studio: cancelled")
            }
            status?.invoke(if (st.queue > 1) StudyText.f("mac_waiting", st.queue - 1) else StudyText.f("video_step_mac", 1, 1))
            Thread.sleep(4000)
            st = try { poll(prefs, id).also { misses = 0 } } catch (e: Exception) {
                if (++misses > 40) throw IllegalStateException(StudyText.f("mac_offline", e.message ?: ""))
                st
            }
        }
        throw IllegalStateException("Owl Studio: timeout")
    }

    fun cancel(prefs: SharedPreferences, id: String) {
        try { call(url(prefs), token(prefs), "POST", "/jobs/$id/cancel", "{}") } catch (_: Exception) {}
    }
}
