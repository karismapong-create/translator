package com.example.interpreter

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Draws a picture from a description with the user's own key. Free services first: Cloudflare
 * Workers AI (FLUX.1 schnell, a free daily allowance) and Pollinations (free credit that refills),
 * then Gemini image models (the cheapest model the key can use is looked up) and OpenAI, which
 * draw only with billing turned on.
 */
object ImageGen {
    class Failed(message: String) : Exception(message)

    // Picture-only keys, kept apart from the AI engine keys.
    fun cfAccount(prefs: SharedPreferences) = prefs.getString("cf_account", "").orEmpty().trim()
    fun cfToken(prefs: SharedPreferences) = prefs.getString("cf_token", "").orEmpty().trim()
    fun pollKey(prefs: SharedPreferences) = prefs.getString("poll_key", "").orEmpty().trim()

    fun available(prefs: SharedPreferences) =
        (cfAccount(prefs).isNotEmpty() && cfToken(prefs).isNotEmpty()) || pollKey(prefs).isNotEmpty() ||
            Providers.key(prefs, "gemini").isNotEmpty() || Providers.key(prefs, "openai").isNotEmpty()

    // After a quota or billing refusal, that service gets no picture requests for a while (the others
    // are still tried), so a shared key keeps its allowance for making lessons.
    private val pausedUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val pausedReason = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun refused(code: Int) = code == 429 || code == 403 || code == 402

    private fun pause(service: String, reason: String) {
        pausedReason[service] = reason
        pausedUntil[service] = System.currentTimeMillis() + 10 * 60_000L
    }

    /** JPEG bytes, at most 1024 px wide. Runs on a background thread. */
    fun draw(prefs: SharedPreferences, prompt: String): ByteArray {
        val tries = ArrayList<Pair<String, () -> ByteArray>>()
        val cfA = cfAccount(prefs)
        val cfT = cfToken(prefs)
        val pk = pollKey(prefs)
        val g = Providers.key(prefs, "gemini")
        val o = Providers.key(prefs, "openai")
        if (cfA.isNotEmpty() && cfT.isNotEmpty()) tries += "cloudflare" to { cloudflare(cfA, cfT, prompt) }
        if (pk.isNotEmpty()) tries += "pollinations" to { pollinations(pk, prompt) }
        if (g.isNotEmpty()) tries += "gemini" to { gemini(prefs, g, prompt) }
        if (o.isNotEmpty()) tries += "openai" to { openai(o, prompt) }
        var last: Exception? = null
        for ((service, run) in tries) {
            if (System.currentTimeMillis() < (pausedUntil[service] ?: 0L)) {
                if (last == null) last = Failed(pausedReason[service].orEmpty())
                continue
            }
            try { return shrink(run()) } catch (e: Exception) { last = Failed(service + ": " + (e.message ?: "")) }
        }
        throw last ?: Failed("no key")
    }

    private fun call(url: String, method: String, headers: Map<String, String>, body: String?): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 20000
        c.readTimeout = 120000
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        c.disconnect()
        return code to text
    }

    private fun errorText(code: Int, body: String): String {
        val msg = try { JSONObject(body).optJSONObject("error")?.optString("message") } catch (_: Exception) { null }
        return "HTTP $code" + (msg?.takeIf { it.isNotBlank() }?.let { ": " + it.take(160) } ?: "")
    }

    /* ---------------- Gemini ---------------- */

    private const val G = "https://generativelanguage.googleapis.com/v1beta"

    // The image models this key can use, cheapest first ("lite", then "flash", then the rest).
    private var listed: List<String>? = null

    private fun geminiModels(key: String): List<String> {
        listed?.let { return it }
        val (code, body) = call("$G/models?pageSize=1000", "GET", mapOf("x-goog-api-key" to key), null)
        if (code !in 200..299) throw Failed(errorText(code, body))
        val arr = JSONObject(body).optJSONArray("models") ?: JSONArray()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val name = m.optString("name")
            val methods = m.optJSONArray("supportedGenerationMethods")?.toString().orEmpty()
            if (name.contains("image") && !name.contains("imagen") && methods.contains("generateContent")) out += name
        }
        fun rank(n: String) = when {
            n.contains("lite") -> 0
            n.contains("flash") -> 1
            n.contains("pro") -> 3
            else -> 2
        }
        return out.sortedWith(compareBy({ rank(it) }, { if (it.contains("preview")) 1 else 0 }, { -it.length })).also { listed = it }
    }

    private fun gemini(prefs: SharedPreferences, key: String, prompt: String): ByteArray {
        val saved = prefs.getString("art_model", null)
        val models = (if (saved != null) listOf(saved) else geminiModels(key)).distinct()
        if (models.isEmpty()) throw Failed("no image model for this key")
        var last: Exception? = null
        for (m in models.take(2)) {
            for (withConfig in listOf(true, false)) {
                val config = JSONObject().put("responseModalities", JSONArray().put("TEXT").put("IMAGE"))
                if (withConfig) config.put("imageConfig", JSONObject().put("aspectRatio", "4:3"))
                val req = JSONObject()
                    .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                    .put("generationConfig", config)
                val (code, body) = call("$G/$m:generateContent", "POST", mapOf("x-goog-api-key" to key), req.toString())
                if (code in 200..299) {
                    val parts = JSONObject(body).optJSONArray("candidates")?.optJSONObject(0)
                        ?.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
                    for (k in 0 until parts.length()) {
                        val p = parts.optJSONObject(k) ?: continue
                        val data = (p.optJSONObject("inlineData") ?: p.optJSONObject("inline_data"))?.optString("data")
                        if (!data.isNullOrEmpty()) {
                            prefs.edit().putString("art_model", m).apply()
                            return Base64.decode(data, Base64.DEFAULT)
                        }
                    }
                    last = Failed("no picture in the answer")
                    break
                }
                last = Failed(errorText(code, body))
                if (refused(code)) {
                    pause("gemini", errorText(code, body))
                    throw last
                }
                // A 400 may be the picture settings; try once without them. Other errors: next model.
                if (code != 400) break
            }
        }
        if (saved != null) prefs.edit().remove("art_model").apply()
        throw last ?: Failed("failed")
    }

    /* ---------------- OpenAI ---------------- */

    private fun openai(key: String, prompt: String): ByteArray {
        var last: Exception? = null
        for (model in listOf("gpt-image-1-mini", "gpt-image-1")) {
            val req = JSONObject().put("model", model).put("prompt", prompt).put("size", "1536x1024").put("quality", "low").put("n", 1)
            val (code, body) = call("https://api.openai.com/v1/images/generations", "POST", mapOf("Authorization" to "Bearer $key"), req.toString())
            if (code in 200..299) {
                val b64 = JSONObject(body).optJSONArray("data")?.optJSONObject(0)?.optString("b64_json")
                if (!b64.isNullOrEmpty()) return Base64.decode(b64, Base64.DEFAULT)
                last = Failed("no picture in the answer")
            } else {
                last = Failed(errorText(code, body))
                if (refused(code)) {
                    pause("openai", errorText(code, body))
                    throw last
                }
            }
        }
        throw last ?: Failed("failed")
    }

    /* ---------------- Cloudflare Workers AI (free daily allowance) ---------------- */

    private fun cloudflare(account: String, token: String, prompt: String): ByteArray {
        val req = JSONObject().put("prompt", prompt.take(2000)).put("steps", 6)
        val (code, body) = call("https://api.cloudflare.com/client/v4/accounts/$account/ai/run/@cf/black-forest-labs/flux-1-schnell",
            "POST", mapOf("Authorization" to "Bearer $token"), req.toString())
        if (code in 200..299) {
            val img = try { JSONObject(body).optJSONObject("result")?.optString("image") } catch (_: Exception) { null }
            if (!img.isNullOrEmpty()) return Base64.decode(img, Base64.DEFAULT)
            throw Failed("no picture in the answer")
        }
        val msg = try {
            JSONObject(body).optJSONArray("errors")?.optJSONObject(0)?.optString("message")
        } catch (_: Exception) { null }
        val text = "HTTP $code" + (msg?.takeIf { it.isNotBlank() }?.let { ": " + it.take(160) } ?: "")
        // 429: today's free allowance is used up (it resets at 09:00 Korean time).
        if (refused(code)) pause("cloudflare", text)
        throw Failed(text)
    }

    /* ---------------- Pollinations (free credit that refills) ---------------- */

    private fun pollinations(key: String, prompt: String): ByteArray {
        val q = java.net.URLEncoder.encode(prompt.take(1500), "UTF-8").replace("+", "%20")
        val c = URL("https://gen.pollinations.ai/image/$q?model=flux&width=1024&height=768&nologo=true&safe=true")
            .openConnection() as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = 120000
        c.setRequestProperty("Authorization", "Bearer $key")
        try {
            val code = c.responseCode
            if (code in 200..299 && c.contentType.orEmpty().startsWith("image")) return c.inputStream.use { it.readBytes() }
            val body = c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val msg = try { JSONObject(body).let { it.optString("message").ifBlank { it.optJSONObject("error")?.optString("message").orEmpty() } } } catch (_: Exception) { body.take(160) }
            val text = "HTTP $code" + (msg.takeIf { it.isNotBlank() }?.let { ": " + it.take(160) } ?: "")
            if (refused(code)) pause("pollinations", text)
            throw Failed(text)
        } finally { c.disconnect() }
    }

    private fun shrink(bytes: ByteArray): ByteArray {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw Failed("bad picture")
        val scaled = if (bmp.width > 1024) Bitmap.createScaledBitmap(bmp, 1024, bmp.height * 1024 / bmp.width, true) else bmp
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return out.toByteArray()
    }
}
