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
        val keyUrl: String,
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
            "https://aistudio.google.com/apikey",
            "① 아래 버튼으로 Google AI Studio를 열고 구글 계정으로 로그인하세요.\n" +
            "② 'API 키 만들기(Create API key)'를 누르세요. 결제 정보는 필요 없어요.\n" +
            "③ 만들어진 키(AIza로 시작)를 복사해서 아래에 붙여넣으세요.\n" +
            "무료 사용분의 내용은 Google 서비스 개선에 쓰일 수 있어요.",
            "gemini-2.5-flash"
        ),
        Provider(
            "claude", "Claude (Anthropic)", "https://api.anthropic.com/v1", "sk-ant-…",
            "https://console.anthropic.com/settings/keys",
            "① 아래 버튼으로 Anthropic Console을 열고 가입·로그인하세요.\n" +
            "② 결제 수단을 등록하고 크레딧을 충전하세요. 사용한 만큼 요금이 나가요.\n" +
            "③ 'Create Key'로 키를 만들어 복사해서 붙여넣으세요.",
            "claude-haiku-5-5"
        ),
        Provider(
            "openai", "OpenAI", "https://api.openai.com/v1", "sk-…",
            "https://platform.openai.com/api-keys",
            "① 아래 버튼으로 OpenAI Platform을 열고 로그인하세요.\n" +
            "② 결제 수단을 등록하고 'Create new secret key'를 누르세요.\n" +
            "③ 키를 복사해서 붙여넣고, '모델 목록 불러오기'로 모델을 고르세요.",
            ""
        ),
        Provider(
            "groq", "Groq (무료 가능)", "https://api.groq.com/openai/v1", "gsk_…",
            "https://console.groq.com/keys",
            "① 아래 버튼으로 Groq Console을 열고 가입·로그인하세요.\n" +
            "② 'Create API Key'를 누르세요. 무료로 시작할 수 있어요.\n" +
            "③ 키를 복사해서 붙여넣고, '모델 목록 불러오기'로 모델을 고르세요. 응답이 아주 빨라요.",
            ""
        ),
        Provider(
            "openrouter", "OpenRouter (무료 모델 있음)", "https://openrouter.ai/api/v1", "sk-or-…",
            "https://openrouter.ai/keys",
            "① 아래 버튼으로 OpenRouter를 열고 가입·로그인하세요.\n" +
            "② 'Create Key'로 키를 만들어 복사해서 붙여넣으세요.\n" +
            "③ '모델 목록 불러오기'에서 이름 끝이 :free인 모델을 고르면 무료예요.",
            ""
        ),
        Provider(
            "mistral", "Mistral", "https://api.mistral.ai/v1", "",
            "https://console.mistral.ai/api-keys",
            "① 아래 버튼으로 Mistral Console을 열고 가입·로그인하세요.\n" +
            "② 요금제를 고른 뒤 'Create new key'를 누르세요.\n" +
            "③ 키를 복사해서 붙여넣으세요.",
            "mistral-small-latest"
        ),
        Provider(
            "deepseek", "DeepSeek", "https://api.deepseek.com/v1", "sk-…",
            "https://platform.deepseek.com/api_keys",
            "① 아래 버튼으로 DeepSeek Platform을 열고 가입·로그인하세요.\n" +
            "② 크레딧을 충전하고 'Create new API key'를 누르세요. 저렴한 편이에요.\n" +
            "③ 키를 복사해서 붙여넣으세요.",
            "deepseek-chat"
        ),
        Provider(
            "xai", "xAI Grok", "https://api.x.ai/v1", "xai-…",
            "https://console.x.ai",
            "① 아래 버튼으로 xAI Console을 열고 가입·로그인하세요.\n" +
            "② 'API Keys' 메뉴에서 키를 만드세요.\n" +
            "③ 키를 복사해서 붙여넣고, '모델 목록 불러오기'로 모델을 고르세요.",
            ""
        ),
        Provider(
            "local", "로컬 LLM (맥북)", "", "",
            "https://ollama.com/download",
            "① 아래 버튼으로 Ollama를 맥북에 내려받아 설치하세요.\n" +
            "② 맥 터미널에서 launchctl setenv OLLAMA_HOST \"0.0.0.0\" 실행 후 Ollama를 다시 켜세요.\n" +
            "③ 맥 주소를 넣으세요. 같은 와이파이: http://192.168.0.10:11434/v1\n" +
            "   WireGuard로 연결 중이면 맥의 WireGuard 주소: http://10.0.0.2:11434/v1\n" +
            "키는 비워두고, '모델 목록 불러오기'로 모델을 고르세요.",
            "", needsKey = false, editableUrl = true
        ),
        Provider(
            "custom", "직접 입력 (OpenAI 호환)", "", "", "",
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
        OnDevice.ID -> OnDevice.label()
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

    // How a Gemini model shows in the picker: free on a key without billing, or paid only.
    // Since April 2026 Google keeps Pro models for projects with billing; Flash and Flash-Lite stay free.
    fun geminiNote(model: String): String = when {
        model.contains("pro", true) -> I18n.t("g_paid")
        model.contains("lite", true) -> I18n.t("g_lite")
        model.contains("flash", true) -> I18n.t("g_free")
        else -> ""
    }

    // Recommended first: the newest Flash, then Pro, then Flash-Lite, then the rest; newest version first.
    fun geminiOrder(models: List<String>): List<String> {
        fun version(m: String) = Regex("(\\d+(?:\\.\\d+)?)").find(m)?.value?.toDoubleOrNull() ?: 0.0
        fun rank(m: String) = when {
            m.contains("lite", true) -> 2
            m.contains("flash", true) -> 0
            m.contains("pro", true) -> 1
            else -> 3
        }
        val preview = { m: String -> if (m.contains("preview", true) || m.contains("exp", true)) 1 else 0 }
        return models.sortedWith(compareBy<String>({ rank(it) }, { preview(it) }, { -version(it) }, { it }))
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