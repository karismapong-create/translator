package com.example.interpreter

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// Sends a multi-turn conversation to the configured LLM and returns its text reply.
object Tutor {
    private const val DEFAULT_CLAUDE_MODEL = "claude-haiku-5-5"

    private fun p(prefs: SharedPreferences, key: String) = prefs.getString(key, "").orEmpty().trim()

    // Runs on a background thread. Turns are (role, content) pairs with roles "user" and "assistant".
    fun send(prefs: SharedPreferences, engine: String, system: String, turns: List<Pair<String, String>>): String {
        val raw = when (engine) {
            "claude" -> claude(
                p(prefs, "claude_key"), p(prefs, "claude_model").ifEmpty { DEFAULT_CLAUDE_MODEL }, system, turns
            )
            "local" -> chat(p(prefs, "local_url"), p(prefs, "local_key"), p(prefs, "local_model"), system, turns, 90000)
            else -> chat(p(prefs, "openai_url"), p(prefs, "openai_key"), p(prefs, "openai_model"), system, turns, 40000)
        }
        return raw.replace(Regex("(?s)<think>.*?</think>"), "").trim()
    }

    fun json(text: String): JSONObject? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try { JSONObject(text.substring(start, end + 1)) } catch (_: Exception) { null }
    }

    private fun postJson(url: String, headers: Map<String, String>, body: JSONObject, readTimeout: Int): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 5000
        conn.readTimeout = readTimeout
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                throw IllegalStateException("HTTP $code ${err.take(200)}")
            }
            return JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    private fun messages(turns: List<Pair<String, String>>): JSONArray {
        val arr = JSONArray()
        turns.forEach { (role, content) -> arr.put(JSONObject().put("role", role).put("content", content)) }
        return arr
    }

    private fun claude(key: String, model: String, system: String, turns: List<Pair<String, String>>): String {
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 1024)
            .put("system", system)
            .put("messages", messages(turns))
        val json = postJson(
            "https://api.anthropic.com/v1/messages",
            mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"),
            body, 40000
        )
        val content = json.getJSONArray("content")
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val block = content.getJSONObject(i)
            if (block.optString("type") == "text") sb.append(block.optString("text"))
        }
        return sb.toString()
    }

    private fun chat(
        base0: String, key: String, model: String,
        system: String, turns: List<Pair<String, String>>, readTimeout: Int
    ): String {
        var base = base0.trim().trimEnd('/')
        if (!base.startsWith("http://") && !base.startsWith("https://")) base = "http://$base"
        if (!base.contains("/v1")) base += "/v1"
        val all = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        val rest = messages(turns)
        for (i in 0 until rest.length()) all.put(rest.get(i))
        val body = JSONObject().put("model", model).put("stream", false).put("messages", all)
        val headers = if (key.isNotEmpty()) mapOf("Authorization" to "Bearer $key") else emptyMap()
        val json = postJson("$base/chat/completions", headers, body, readTimeout)
        return json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
    }
}