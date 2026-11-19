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
 * Draws a picture from a description with the user's own key: Gemini image models first (the
 * cheapest image model the key can use is looked up, so renamed models keep working), then OpenAI.
 * Image generation is a paid feature on both services.
 */
object ImageGen {
    class Failed(message: String) : Exception(message)

    fun available(prefs: SharedPreferences) =
        Providers.key(prefs, "gemini").isNotEmpty() || Providers.key(prefs, "openai").isNotEmpty()

    /** JPEG bytes, at most 1024 px wide. Runs on a background thread. */
    fun draw(prefs: SharedPreferences, prompt: String): ByteArray {
        val g = Providers.key(prefs, "gemini")
        val o = Providers.key(prefs, "openai")
        var last: Exception? = null
        if (g.isNotEmpty()) {
            try { return shrink(gemini(prefs, g, prompt)) } catch (e: Exception) { last = e }
        }
        if (o.isNotEmpty()) {
            try { return shrink(openai(o, prompt)) } catch (e: Exception) { last = e }
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
    private fun geminiModels(key: String): List<String> {
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
        return out.sortedWith(compareBy({ rank(it) }, { if (it.contains("preview")) 1 else 0 }, { -it.length }))
    }

    private fun gemini(prefs: SharedPreferences, key: String, prompt: String): ByteArray {
        val saved = prefs.getString("art_model", null)
        val models = (listOfNotNull(saved) + try { geminiModels(key) } catch (e: Exception) {
            if (saved == null) throw e else emptyList()
        }).distinct()
        if (models.isEmpty()) throw Failed("no image model for this key")
        var last: Exception? = null
        for (m in models.take(3)) {
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
            }
        }
        throw last ?: Failed("failed")
    }

    private fun shrink(bytes: ByteArray): ByteArray {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw Failed("bad picture")
        val scaled = if (bmp.width > 1024) Bitmap.createScaledBitmap(bmp, 1024, bmp.height * 1024 / bmp.width, true) else bmp
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return out.toByteArray()
    }
}
