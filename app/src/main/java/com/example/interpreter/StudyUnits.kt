package com.example.interpreter

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.BreakIterator
import java.util.Locale

/**
 * Splits a sentence into study units: single words, and idioms or set phrases kept together as one
 * group. The app decides the groups, not the user.
 *
 * - With an AI engine: one request per dialogue, cached on the phone per language and sentence.
 * - Without one: words only (Japanese by morphological analysis, others by word boundaries).
 */
object StudyUnits {
    class Unit(
        val start: Int,
        val end: Int,
        val text: String,
        val meaning: String,
        val reading: String,
        val idiom: Boolean
    )

    private val memory = HashMap<String, JSONArray>()
    private val tokenizer by lazy { com.atilika.kuromoji.ipadic.Tokenizer() }

    private fun file(ctx: Context, code: String) = File(ctx.filesDir, "units/$code.json")

    private fun load(ctx: Context, code: String): JSONObject = try {
        val f = file(ctx, code)
        if (f.exists()) JSONObject(f.readText()) else JSONObject()
    } catch (_: Exception) { JSONObject() }

    @Synchronized
    private fun store(ctx: Context, code: String, found: Map<String, JSONArray>) {
        val o = load(ctx, code)
        for ((k, v) in found) o.put(k, v)
        // Keeps the cache small: drops the oldest sentences past 3000.
        if (o.length() > 3000) {
            val keys = o.keys().asSequence().toList()
            keys.take(o.length() - 3000).forEach { o.remove(it) }
        }
        val f = file(ctx, code)
        f.parentFile?.mkdirs()
        f.writeText(o.toString())
    }

    /** Units saved earlier for this sentence, or null. Any thread. */
    @Synchronized
    fun cached(ctx: Context, code: String, sentence: String): List<Unit>? {
        val key = "$code|$sentence"
        val arr = memory[key] ?: load(ctx, code).optJSONArray(sentence)?.also { memory[key] = it } ?: return null
        return place(sentence, arr)
    }

    // Finds where each unit sits in the sentence, in order.
    private fun place(sentence: String, arr: JSONArray): List<Unit> {
        val out = ArrayList<Unit>()
        var from = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val t = o.optString("t").trim()
            if (t.isEmpty()) continue
            var at = sentence.indexOf(t, from)
            if (at < 0) at = sentence.indexOf(t)
            if (at < 0) continue
            out += Unit(at, at + t.length, t, o.optString("m"), o.optString("r"), o.optBoolean("i"))
            if (at + t.length > from) from = at + t.length
        }
        return out.sortedBy { it.start }
    }

    /** Word units without an AI engine. Runs on a background thread (Japanese analysis is slow at first). */
    fun offline(sentence: String, code: String, tag: String): List<Unit> {
        val out = ArrayList<Unit>()
        try {
            if (code == "ja") {
                for (t in tokenizer.tokenize(sentence)) {
                    if (t.partOfSpeechLevel1 == "記号") continue
                    val s = t.position
                    val e = s + t.surface.length
                    if (e <= sentence.length && t.surface.isNotBlank()) out += Unit(s, e, t.surface, "", "", false)
                }
                return out
            }
        } catch (_: Throwable) { out.clear() }
        val it = BreakIterator.getWordInstance(Locale.forLanguageTag(tag))
        it.setText(sentence)
        var s = it.first()
        var e = it.next()
        while (e != BreakIterator.DONE) {
            val w = sentence.substring(s, e)
            if (w.any { c -> Character.isLetterOrDigit(c) }) out += Unit(s, e, w, "", "", false)
            s = e
            e = it.next()
        }
        return out
    }

    /**
     * Asks the AI to split the sentences that are not cached yet. Runs on a background thread.
     * Returns true when something new was saved.
     */
    fun fetch(ctx: Context, prefs: SharedPreferences, code: String, sentences: List<String>): Boolean {
        val engine = Providers.llmEngine(prefs) ?: return false
        val todo = sentences.distinct().filter { it.isNotBlank() && cached(ctx, code, it) == null }
        if (todo.isEmpty()) return false
        val lang = Languages.byCode(code).english
        val home = Languages.home(prefs)
        val mine = home.english
        val reading = when (code) {
            "ja" -> "hiragana reading"
            "zh" -> "pinyin with tone marks"
            else -> "empty string"
        }
        val system = "You split $lang sentences into study units for a $mine-speaking learner. " +
            "Keep every idiom, set phrase, fixed greeting, phrasal verb and common collocation together as ONE unit " +
            "with \"i\":true (for example a whole polite set expression). Everything else is a single word with \"i\":false; " +
            "keep particles and auxiliaries as their own units. Skip punctuation. " +
            "\"t\" must be copied exactly as it appears in the sentence. " +
            "\"m\" is the $mine meaning in this sentence (for an idiom, the meaning of the whole expression). " +
            "\"r\" is the $reading. " +
            "Reply with JSON only, no code fences: {\"s\":[[{\"t\":\"\",\"m\":\"\",\"r\":\"\",\"i\":false}]]} " +
            "with one inner list per input sentence, in the same order."
        val found = HashMap<String, JSONArray>()
        for (chunk in todo.chunked(12)) {
            val input = JSONArray().apply { chunk.forEach { put(it) } }.toString()
            val raw = try {
                Providers.complete(prefs, engine, system, listOf("user" to input), 4096, 45000)
            } catch (_: Exception) { continue }
            val o = try {
                val a = raw.indexOf('{')
                val b = raw.lastIndexOf('}')
                if (a < 0 || b <= a) null else JSONObject(raw.substring(a, b + 1))
            } catch (_: Exception) { null } ?: continue
            val s = o.optJSONArray("s") ?: continue
            for (i in chunk.indices) {
                val units = s.optJSONArray(i) ?: continue
                // Keeps the answer only when it really covers this sentence.
                if (place(chunk[i], units).isNotEmpty()) found[chunk[i]] = units
            }
        }
        if (found.isEmpty()) return false
        synchronized(this) { for ((k, v) in found) memory["$code|$k"] = v }
        store(ctx, code, found)
        return true
    }

    fun at(units: List<Unit>, offset: Int): Unit? =
        units.firstOrNull { offset >= it.start && offset < it.end }
}
