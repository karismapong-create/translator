package com.example.interpreter

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// All LLM providers in one place: settings, readiness, model lists and chat requests.
object Providers {

    class Provider(
        val id: String,
        val label: String,
        val baseUrl: String,
        val keyHint: String,
        val help: String,
        val defaultModel: String,
        val needsKey: Boolean = true,
        val editableUrl: Boolean = false
    )

    class HttpException(val code: Int, val body: String) : Exception("HTTP $code ${body.take(200)}")

    val llms = listOf(
        Provider(
            "gemini", "Google Gemini (무료 가능)",
            "https://generativelanguage.googleapis.com/v1beta/openai", "AIza…",
            "aistudio.google.com에서 구글 계정으로 무료 API 키를 만들 수 있어요. 무료 사용분의 내용은 Google 서비스 개선에 쓰일 수 있어요.",
            "gemini-2.5-flash"
        ),
        Provider(
            "claude", "Claude (Anthropic)", "https://api.anthropic.com/v1", "sk-ant-…",
            "console.anthropic.com에서 받은 API 키를 넣으세요. 사용한 만큼 요금이 나가요.",
            "claude-haiku-5-5"
        ),
        Provider(
            "openai", "OpenAI", "https://api.openai.com/v1", "sk-…",
            "platform.openai.com에서 받은 API 키를 넣고, 모델 목록에서 모델을 고르세요.", ""
        ),
        Provider(
            "groq", "Groq (무료 가능)", "https://api.groq.com/openai/v1", "gsk_…",
            "console.groq.com에서 무료 API 키를 받을 수 있어요. 응답이 아주 빨라요.", ""
        ),
        Provider(
            "openrouter", "OpenRouter (무료 모델 있음)", "https://openrouter.ai/api/v1", "sk-or-…",
            "openrouter.ai 키 하나로 여러 회사의 모델을 써요. 이름 끝이 :free인 모델은 무료예요.", ""
        ),
        Provider(
            "mistral", "Mistral", "https://api.mistral.ai/v1", "",
            "console.mistral.ai에서 받은 API 키를 넣으세요.", "mistral-small-latest"
        ),
        Provider(
            "deepseek", "DeepSeek", "https://api.deepseek.com/v1", "sk-…",
            "platform.deepseek.com에서 받은 API 키를 넣으세요. 저렴한 편이에요.", "deepseek-chat"
        ),
        Provider(
            "xai", "xAI Grok", "https://api.x.ai/v1", "xai-…",
            "console.x.ai에서 받은 API 키를 넣고, 모델 목록에서 모델을 고르세요.", ""
        ),
        Provider(
            "local", "로컬 LLM (맥북)", "", "",
            "맥북에서 Ollama나 LM Studio 서버를 켜고, 폰과 같은 와이파이에서 맥 주소를 넣으세요. 예: http://192.168.0.10:11434/v1",
            "", needsKey = false, editableUrl = true
        ),
        Provider(
            "custom", "직접 입력 (OpenAI 호환)", "", "",
            "OpenAI와 같은 형식을 쓰는 다른 서비스의 주소, 키, 모델 이름을 넣으세요.",
            "", needsKey = false, editableUrl = true
        )
    )

    fun find(id: String?): Provider? = llms.firstOrNull { it.id == id }

    private fun p(prefs: SharedPreferences, key: String) = prefs.getString(key, "").orEmpty().trim()

    fun key(prefs: SharedPreferences, id: String) = p(prefs, "${id}_key")
    fun rawUrl(prefs: SharedPreferences, id: String) = p(prefs, "${id}_url")
    fun model(prefs: SharedPreferences, id: String) =
        p(prefs, "${id}_model").ifEmpty { find(id)?.defaultModel.orEmpty() }

    fun baseUrl(prefs: SharedPreferences, id: String): String {
        val pr = find(id) ?: return ""
        if (!pr.editableUrl) return pr.baseUrl
        var base = rawUrl(prefs, id).trimEnd('/')
        if (base.isEmpty()) return ""
        if (!base.startsWith("http://") && !base.startsWith("https://")) base = "http://$base"
        if (!base.contains("/v1")) base += "/v1"
        return base
    }

    fun ready(prefs: SharedPreferences, id: String): Boolean {
        val pr = find(id) ?: return false
        if (pr.editableUrl && rawUrl(prefs, id).isEmpty()) return false
        if (pr.needsKey && key(prefs, id).isEmpty()) return false
        return model(prefs, id).isNotEmpty()
    }

    // The LLM for explanations, tutoring and phrasebooks: the chosen engine if it is an LLM, else the first one set up.
    fun llmEngine(prefs: SharedPreferences): String? {
        val active = prefs.getString("engine", null)
        if (active != null && ready(prefs, active)) return active
        return llms.firstOrNull { ready(prefs, it.id) }?.id
    }

    fun label(id: String): String = find(id)?.label ?: when (id) {
        "deepl" -> "DeepL"
        "mlkit" -> "기기 번역만"
        else -> id
    }

    // Moves settings saved by older versions, where "openai" meant any OpenAI-compatible service.
    fun migrate(prefs: SharedPreferences) {
        if (prefs.getBoolean("providers_v2", false)) return
        val url = p(prefs, "openai_url")
        val editor = prefs.edit()
        if (url.isNotEmpty() && !url.contains("api.openai.com")) {
            editor.putString("custom_url", url)
                .putString("custom_key", p(prefs, "openai_key"))
                .putString("custom_model", p(prefs, "openai_model"))
            if (prefs.getString("engine", null) == "openai") editor.putString("engine", "custom")
        }
        editor.putBoolean("providers_v2", true).apply()
    }

    private fun headers(prefs: SharedPreferences, id: String): Map<String, String> {
        val k = key(prefs, id)
        return if (id == "claude") mapOf("x-api-key" to k, "anthropic-version" to "2023-06-01")
               else if (k.isNotEmpty()) mapOf("Authorization" to "Bearer $k")
               else emptyMap()
    }

    private fun open(url: String, headers: Map<String, String>, timeout: Int): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = timeout
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        return conn
    }

    private fun read(conn: HttpURLConnection): JSONObject {
        val code = conn.responseCode
        if (code !in 200..299) {
            val err = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            throw HttpException(code, err)
        }
        return JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
    }

    private fun post(url: String, headers: Map<String, String>, body: JSONObject, timeout: Int): JSONObject {
        val conn = open(url, headers, timeout)
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            return read(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun get(url: String, headers: Map<String, String>, timeout: Int): JSONObject {
        val conn = open(url, headers, timeout)
        try {
            return read(conn)
        } finally {
            conn.disconnect()
        }
    }

    // Runs on a background thread. Turns are (role, content) pairs with roles "user" and "assistant".
    fun complete(
        prefs: SharedPreferences, id: String, system: String,
        turns: List<Pair<String, String>>, maxTokens: Int = 1024, timeout: Int = 40000
    ): String {
        val wait = if (id == "local") timeout * 2 else timeout
        val messages = JSONArray()
        turns.forEach { (role, content) -> messages.put(JSONObject().put("role", role).put("content", content)) }
        val text = if (id == "claude") {
            val body = JSONObject()
                .put("model", model(prefs, id))
                .put("max_tokens", maxTokens)
                .put("system", system)
                .put("messages", messages)
            val json = post("https://api.anthropic.com/v1/messages", headers(prefs, id), body, wait)
            val content = json.getJSONArray("content")
            val sb = StringBuilder()
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                if (block.optString("type") == "text") sb.append(block.optString("text"))
            }
            sb.toString()
        } else {
            val all = JSONArray().put(JSONObject().put("role", "system").put("content", system))
            for (i in 0 until messages.length()) all.put(messages.get(i))
            val body = JSONObject().put("model", model(prefs, id)).put("stream", false).put("messages", all)
            val json = post(baseUrl(prefs, id) + "/chat/completions", headers(prefs, id), body, wait)
            json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
        }
        return text.replace(Regex("(?s)<think>.*?</think>"), "").trim()
    }

    // Runs on a background thread. Returns the chat models the service offers right now.
    fun fetchModels(prefs: SharedPreferences, id: String): List<String> {
        val url = if (id == "claude") "https://api.anthropic.com/v1/models?limit=100" else baseUrl(prefs, id) + "/models"
        val json = get(url, headers(prefs, id), 15000)
        val arr = json.optJSONArray("data") ?: json.optJSONArray("models") ?: JSONArray()
        val skip = listOf("embed", "imagen", "veo", "tts", "image", "whisper", "moderation", "audio", "transcribe")
        return (0 until arr.length())
            .mapNotNull { i -> arr.optJSONObject(i)?.let { o -> o.optString("id").ifEmpty { o.optString("name") } } }
            .map { it.removePrefix("models/") }
            .filter { it.isNotEmpty() && skip.none { s -> it.contains(s, true) } }
            .distinct()
            .sorted()
    }
}