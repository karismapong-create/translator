package com.example.interpreter

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// Translation through the engine chosen in settings: DeepL, Gemini Nano on the phone, or an AI
// provider. Shared by the interpreter and live conversation. Every call runs on a background thread.
object RemoteTranslate {

    // The engine to use for refined translation, or null when only the offline engine is set up.
    fun engine(prefs: SharedPreferences): String? {
        val id = prefs.getString("engine", null)
            ?: if (prefs.getString("deepl", "").orEmpty().isNotBlank()) "deepl" else return null
        val ok = when (id) {
            "mlkit" -> false
            "deepl" -> prefs.getString("deepl", "").orEmpty().isNotBlank()
            OnDevice.ID -> OnDevice.ready
            else -> Providers.ready(prefs, id)
        }
        return if (ok) id else null
    }

    // True when the engine can also write free text (reply suggestions), not only translate.
    fun canWrite(id: String?) = id != null && id != "deepl" && id != "mlkit"

    // scene: a short description of where the conversation happens, e.g. "a restaurant".
    fun translate(
        prefs: SharedPreferences, id: String, text: String, sl: Lang, tl: Lang,
        context: String, scene: String = ""
    ): String = when (id) {
        "deepl" -> deepl(text, sl.code, tl.code, prefs.getString("deepl", "").orEmpty().trim(),
            listOf(scene.takeIf { it.isNotBlank() }?.let { "Setting: $it" }, context.takeIf { it.isNotBlank() })
                .filterNotNull().joinToString("\n"))
        OnDevice.ID -> OnDevice.translate(text, sl.code, tl.code,
            if (scene.isBlank()) context else "Setting: $scene\n$context")
        else -> clean(Providers.complete(prefs, id, systemPrompt(sl, tl, scene), listOf("user" to userPrompt(text, context)), 1024, 20000))
    }

    // Free-form AI call for features other than translation.
    fun ask(prefs: SharedPreferences, id: String, system: String, user: String, maxTokens: Int = 600): String =
        clean(Providers.complete(prefs, id, system, listOf("user" to user), maxTokens, 20000))

    /**
     * [ask] with the chosen engine, then, if it fails (credits used up, a wrong key, busy), the Mac's
     * local LLM, only when one is set up (no other paid service is asked on its own). [ok] decides whether an answer is
     * usable (for example JSON with sentences); an unusable one also moves on. Returns the first usable
     * answer, or throws the chosen engine's error.
     */
    fun <T> askAny(prefs: SharedPreferences, first: String, system: String, user: String, maxTokens: Int,
                   ok: (String) -> T?): T {
        val order = listOf(first) + listOf("local").filter { it != first && Providers.ready(prefs, it) }
        var error: Exception? = null
        for (id in order) {
            try {
                ok(ask(prefs, id, system, user, maxTokens))?.let { return it }
                if (error == null) error = IllegalStateException("format")
            } catch (e: Exception) {
                if (error == null) error = e
            }
        }
        throw error ?: IllegalStateException("format")
    }

    /** One plain line for an AI failure, instead of the service's raw error. */
    fun friendly(e: Throwable?, id: String?): String {
        val name = id?.let { Providers.label(it) }.orEmpty()
        val http = e as? Providers.HttpException
        val body = http?.body.orEmpty().lowercase()
        return when {
            http != null && (http.code == 402 || "credit" in body || "quota" in body || "billing" in body ||
                "insufficient" in body || "balance" in body) -> StudyText.f("ai_err_credit", name)
            http != null && (http.code == 401 || http.code == 403) -> StudyText.f("ai_err_key", name)
            http != null && (http.code == 429 || http.code >= 500) -> StudyText.f("ai_err_busy", name)
            e is java.net.SocketTimeoutException -> StudyText.f("ai_err_timeout", name)
            e is java.io.IOException -> StudyText.f("ai_err_offline", name)
            else -> StudyText.f("ai_err_other", name, (e?.message ?: "").take(120))
        }
    }

    private fun systemPrompt(sl: Lang, tl: Lang, scene: String) =
        "You are a professional simultaneous interpreter. Translate the user's text from ${sl.english} " +
        "to ${tl.english}. The text may be an unfinished fragment of ongoing speech; translate it as it is " +
        "without completing or guessing the rest. Lines marked as context are earlier conversation, given " +
        "only so you understand the flow; never translate or repeat them. Keep placeholder codes such as " +
        "XZ1 or XZ2 exactly as written. Use natural, polite spoken ${tl.english}. " +
        (if (scene.isNotBlank()) "The conversation takes place at $scene; choose the words people really use there. " else "") +
        "Reply with the translation only, with no quotes, notes, romanization or explanations."

    private fun userPrompt(text: String, context: String) =
        if (context.isBlank()) text
        else "Context (do not translate):\n$context\n\nTranslate:\n$text"

    fun clean(s: String) =
        s.replace(Regex("(?s)<think>.*?</think>"), "").trim().trim('"', '“', '”').trim()

    private fun deeplCode(code: String, asTarget: Boolean): String =
        if (code == "en" && asTarget) "EN-US" else code.uppercase()

    private fun deepl(text: String, source: String, target: String, key: String, context: String): String {
        val host = if (key.endsWith(":fx")) "api-free.deepl.com" else "api.deepl.com"
        val body = JSONObject()
            .put("text", JSONArray().put(text))
            .put("source_lang", deeplCode(source, false))
            .put("target_lang", deeplCode(target, true))
            .put("formality", "prefer_more")
        if (context.isNotBlank()) body.put("context", context)
        val conn = URL("https://$host/v2/translate").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 4000
        conn.readTimeout = 6000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "DeepL-Auth-Key $key")
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                throw Providers.HttpException(code, err)
            }
            val json = JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            return json.getJSONArray("translations").getJSONObject(0).getString("text")
        } finally {
            conn.disconnect()
        }
    }
}
