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

    /** Drops what is kept in memory, after the files changed (import). */
    @Synchronized
    fun forget() = memory.clear()

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
     * Words (offline, instant) with the idioms found for this sentence laid over them.
     * Any thread; Japanese analysis is slow the first time.
     */
    fun full(ctx: Context, code: String, sentence: String, tag: String): List<Unit> {
        val known = cached(ctx, code, sentence) ?: emptyList()
        // Older saves hold every word already.
        if (known.any { !it.idiom }) return known
        val words = offline(sentence, code, tag).filter { w -> known.none { w.start < it.end && it.start < w.end } }
        return (known + words).sortedBy { it.start }
    }

    /** Saves idioms that came with a lesson, so no extra request is needed. */
    fun seed(ctx: Context, code: String, sentences: List<String>, idioms: JSONArray) {
        val found = HashMap<String, JSONArray>()
        for (sentence in sentences) {
            if (sentence.isBlank() || cached(ctx, code, sentence) != null) continue
            val arr = JSONArray()
            for (k in 0 until idioms.length()) {
                val o = idioms.optJSONObject(k) ?: continue
                val t = o.optString("text").ifBlank { o.optString("t") }.trim()
                if (t.length < 2 || !sentence.contains(t)) continue
                arr.put(JSONObject().put("t", t).put("m", o.optString("meaning").ifBlank { o.optString("m") })
                    .put("r", o.optString("reading").ifBlank { o.optString("r") }).put("i", true))
            }
            found[sentence] = arr
        }
        if (found.isEmpty()) return
        synchronized(this) { for ((k, v) in found) memory["$code|$k"] = v }
        store(ctx, code, found)
    }

    /**
     * Asks the AI for the idioms of the sentences not checked yet (only idioms, so the answer is short
     * and quick), a few sentences per request, several requests at once. Runs on a background thread.
     * Returns true when something new was saved.
     */
    fun fetch(ctx: Context, prefs: SharedPreferences, code: String, sentences: List<String>): Boolean {
        val engine = Providers.llmEngine(prefs) ?: return false
        val todo = sentences.distinct().filter { it.isNotBlank() && cached(ctx, code, it) == null }
        if (todo.isEmpty()) return false
        val lang = Languages.byCode(code).english
        val mine = Languages.home(prefs).english
        val reading = when (code) {
            "ja" -> "hiragana reading"
            "zh" -> "pinyin with tone marks"
            else -> "empty string"
        }
        val system = "Find the idioms in each $lang sentence for a $mine-speaking learner: idioms, set phrases, fixed " +
            "greetings and polite formulas, phrasal verbs, and common multi-word collocations or grammar patterns a learner " +
            "should learn as one piece. Single words are NOT idioms. \"t\" must be copied exactly as it appears in the sentence. " +
            "\"m\" is the $mine meaning of the whole expression here. \"r\" is the $reading. " +
            "Reply with JSON only, no code fences: {\"s\":[[{\"t\":\"\",\"m\":\"\",\"r\":\"\"}]]} " +
            "with one inner list per input sentence in the same order (an empty list when a sentence has none)."
        val found = java.util.concurrent.ConcurrentHashMap<String, JSONArray>()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(3)
        for (chunk in todo.chunked(8)) pool.execute {
            val input = JSONArray().apply { chunk.forEach { put(it) } }.toString()
            val raw = try {
                Providers.complete(prefs, engine, system, listOf("user" to input), 2048, 40000)
            } catch (_: Exception) { return@execute }
            val o = try {
                val a = raw.indexOf('{')
                val b = raw.lastIndexOf('}')
                if (a < 0 || b <= a) null else JSONObject(raw.substring(a, b + 1))
            } catch (_: Exception) { null } ?: return@execute
            val s = o.optJSONArray("s") ?: return@execute
            for (i in chunk.indices) {
                val list = s.optJSONArray(i) ?: continue
                val arr = JSONArray()
                for (k in 0 until list.length()) {
                    val u = list.optJSONObject(k) ?: continue
                    val t = u.optString("t").trim()
                    // Only real multi-word pieces that are in the sentence.
                    if (t.length < 2 || !chunk[i].contains(t)) continue
                    if (code !in setOf("ja", "zh") && !t.contains(' ')) continue
                    arr.put(u.put("i", true))
                }
                found[chunk[i]] = arr
            }
        }
        pool.shutdown()
        try { pool.awaitTermination(90, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}
        if (found.isEmpty()) return false
        synchronized(this) { for ((k, v) in found) memory["$code|$k"] = v }
        store(ctx, code, found)
        return true
    }

    fun at(units: List<Unit>, offset: Int): Unit? =
        units.firstOrNull { offset >= it.start && offset < it.end }
}
