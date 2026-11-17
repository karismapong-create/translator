package com.example.interpreter

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// Online translation for the camera screen. Uses the same settings as the main screen.
object Engines {

    private fun p(prefs: SharedPreferences, key: String) = prefs.getString(key, "").orEmpty().trim()
    private fun name(code: String) = Languages.byCode(code).english

    fun active(prefs: SharedPreferences): String {
        val id = prefs.getString("engine", null) ?: if (p(prefs, "deepl").isNotEmpty()) "deepl" else "mlkit"
        val ready = when (id) {
            "deepl" -> p(prefs, "deepl").isNotEmpty()
            "mlkit" -> false
            else -> Providers.ready(prefs, id)
        }
        return if (ready) id else "mlkit"
    }

    fun label(id: String) = if (id == "mlkit") I18n.t("engine_device") else Providers.label(id)

    // Runs on a background thread. Throws on failure.
    fun translate(prefs: SharedPreferences, id: String, text: String, source: String, target: String): String =
        if (id == "deepl") deepl(p(prefs, "deepl"), text, source, target)
        else Providers.complete(prefs, id, prompt(source, target), listOf("user" to text), 1024, 20000)
            .trim().trim('"', '“', '”').trim()

    private fun prompt(source: String, target: String): String {
        val s = name(source)
        val t = name(target)
        return "You translate text read from a camera image, such as signs, menus, labels and documents, " +
               "from $s to $t. The text comes from OCR, so it may contain odd line breaks or small recognition " +
               "errors; quietly fix obvious errors. Reply with the $t translation only, with no quotes, notes " +
               "or explanations."
    }

    private fun deepl(key: String, text: String, source: String, target: String): String {
        val host = if (key.endsWith(":fx")) "api-free.deepl.com" else "api.deepl.com"
        val tgt = if (target == "en") "EN-US" else target.uppercase()
        val body = JSONObject()
            .put("text", JSONArray().put(text))
            .put("source_lang", source.uppercase())
            .put("target_lang", tgt)
            .put("formality", "prefer_more")
        val conn = URL("https://$host/v2/translate").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 4000
        conn.readTimeout = 8000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "DeepL-Auth-Key $key")
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) throw Providers.HttpException(code, "")
            val json = JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            return json.getJSONArray("translations").getJSONObject(0).getString("text")
        } finally {
            conn.disconnect()
        }
    }
}