package com.example.interpreter

import android.content.SharedPreferences
import com.google.mlkit.nl.translate.TranslateLanguage
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// Online translation engines for the camera screen. Reads the same settings as the main screen.
object Engines {
    class HttpException(val code: Int, val body: String) : Exception("HTTP $code")

    private const val DEFAULT_CLAUDE_MODEL = "claude-haiku-5-5"
    private val english = mapOf(
        TranslateLanguage.KOREAN to "Korean", TranslateLanguage.JAPANESE to "Japanese",
        TranslateLanguage.ENGLISH to "English", TranslateLanguage.FRENCH to "French",
        TranslateLanguage.SPANISH to "Spanish", TranslateLanguage.CHINESE to "Simplified Chinese"
    )

    private fun p(prefs: SharedPreferences, key: String) = prefs.getString(key, "").orEmpty().trim()

    fun active(prefs: SharedPreferences): String {
        val id = prefs.getString("engine", null) ?: if (p(prefs, "deepl").isNotEmpty()) "deepl" else "mlkit"
        val ready = when (id) {
            "deepl" -> p(prefs, "deepl").isNotEmpty()
            "claude" -> p(prefs, "claude_key").isNotEmpty()
            "openai" -> p(prefs, "openai_url").isNotEmpty() && p(prefs, "openai_key").isNotEmpty() &&
                        p(prefs, "openai_model").isNotEmpty()
            "local" -> p(prefs, "local_url").isNotEmpty() && p(prefs, "local_model").isNotEmpty()
            else -> false
        }
        return if (ready) id else "mlkit"
    }

    fun label(id: String) = when (id) {
        "deepl" -> "DeepL"
        "claude" -> "Claude"
        "openai" -> "OpenAI 호환 API"
        "local" -> "로컬 LLM (맥북)"
        else -> "기기 번역"
    }

    // Runs on a background thread. Throws on failure.
    fun translate(prefs: SharedPreferences, id: String, text: String, source: String, target: String): String =
        when (id) {
            "deepl" -> deepl(p(prefs, "deepl"), text, source, target)
            "claude" -> claude(p(prefs, "claude_key"), p(prefs, "claude_model").ifEmpty { DEFAULT_CLAUDE_MODEL }, text, source, target)
            "openai" -> chat(p(prefs, "openai_url"), p(prefs, "openai_key"), p(prefs, "openai_model"), text, source, target, 20000)
            "local" -> chat(p(prefs, "local_url"), p(prefs, "local_key"), p(prefs, "local_model"), text, source, target, 45000)
            else -> throw IllegalStateException("no engine")
        }

    private fun prompt(source: String, target: String): String {
        val s = english[source] ?: source
        val t = english[target] ?: target
        return "You translate text read from a camera image, such as signs, menus, labels and documents, " +
               "from $s to $t. The text comes from OCR, so it may contain odd line breaks or small recognition " +
               "errors; quietly fix obvious errors. Reply with the $t translation only, with no quotes, notes " +
               "or explanations."
    }

    private fun clean(s: String) =
        s.replace(Regex("(?s)<think>.*?</think>"), "").trim().trim('"', '“', '”').trim()

    private fun postJson(url: String, headers: Map<String, String>, body: JSONObject, readTimeout: Int): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 4000
        conn.readTimeout = readTimeout
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                throw HttpException(code, err.take(300))
            }
            return JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    private fun deepl(key: String, text: String, source: String, target: String): String {
        val host = if (key.endsWith(":fx")) "api-free.deepl.com" else "api.deepl.com"
        val tgt = if (target == TranslateLanguage.ENGLISH) "EN-US" else target.uppercase()
        val body = JSONObject()
            .put("text", JSONArray().put(text))
            .put("source_lang", source.uppercase())
            .put("target_lang", tgt)
            .put("formality", "prefer_more")
        val json = postJson("https://$host/v2/translate", mapOf("Authorization" to "DeepL-Auth-Key $key"), body, 8000)
        return json.getJSONArray("translations").getJSONObject(0).getString("text")
    }

    private fun claude(key: String, model: String, text: String, source: String, target: String): String {
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 1024)
            .put("system", prompt(source, target))
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", text)))
        val json = postJson(
            "https://api.anthropic.com/v1/messages",
            mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"),
            body, 20000
        )
        val content = json.getJSONArray("content")
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val block = content.getJSONObject(i)
            if (block.optString("type") == "text") sb.append(block.optString("text"))
        }
        return clean(sb.toString())
    }

    private fun chat(
        base0: String, key: String, model: String,
        text: String, source: String, target: String, readTimeout: Int
    ): String {
        var base = base0.trim().trimEnd('/')
        if (!base.startsWith("http://") && !base.startsWith("https://")) base = "http://$base"
        if (!base.contains("/v1")) base += "/v1"
        val body = JSONObject()
            .put("model", model)
            .put("stream", false)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", prompt(source, target)))
                .put(JSONObject().put("role", "user").put("content", text)))
        val headers = if (key.isNotEmpty()) mapOf("Authorization" to "Bearer $key") else emptyMap()
        val json = postJson("$base/chat/completions", headers, body, readTimeout)
        val msg = json.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        return clean(msg.optString("content"))
    }
}