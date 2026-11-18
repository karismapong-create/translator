package com.example.interpreter

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

// Breaks a saved sentence into words with reading, pronunciation, part of speech and meaning in my language.
object WordExplainer {

    class Word(
        val word: String, val base: String, val reading: String, val hangul: String,
        val pos: String, val meaning: String, val note: String
    )

    class Result(val words: List<Word>, val grammar: String, val natural: String, val source: String) {
        fun toJson(): String {
            val arr = JSONArray()
            words.forEach { w ->
                arr.put(
                    JSONObject().put("word", w.word).put("base", w.base).put("reading", w.reading)
                        .put("hangul", w.hangul).put("pos", w.pos).put("meaning", w.meaning).put("note", w.note)
                )
            }
            return JSONObject().put("words", arr).put("grammar", grammar)
                .put("natural", natural).put("source", source).toString()
        }
    }

    private class Piece(val word: String, val base: String, val reading: String, val hangul: String, val pos: String)

    val OFFLINE: String get() = VocabText.t("offline_label")
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val tokenizer by lazy { com.atilika.kuromoji.ipadic.Tokenizer() }
    private val posNames = mapOf(
        "名詞" to "명사|名詞|noun|nom|sustantivo|Nomen|名词",
        "動詞" to "동사|動詞|verb|verbe|verbo|Verb|动词",
        "形容詞" to "형용사|形容詞|adjective|adjectif|adjetivo|Adjektiv|形容词",
        "副詞" to "부사|副詞|adverb|adverbe|adverbio|Adverb|副词",
        "助詞" to "조사|助詞|particle|particule|partícula|Partikel|助词",
        "助動詞" to "조동사|助動詞|auxiliary|auxiliaire|auxiliar|Hilfsverb|助动词",
        "接続詞" to "접속사|接続詞|conjunction|conjonction|conjunción|Konjunktion|连词",
        "連体詞" to "연체사|連体詞|prenoun|déterminant|determinante|Determinativ|连体词",
        "感動詞" to "감탄사|感動詞|interjection|interjection|interjección|Interjektion|感叹词",
        "接頭詞" to "접두사|接頭詞|prefix|préfixe|prefijo|Präfix|前缀"
    )
    private val nameOrder = listOf("ko", "ja", "en", "fr", "es", "de", "zh")

    private fun posLabel(jp: String): String {
        val names = posNames[jp] ?: return jp
        return names.split("|").getOrNull(nameOrder.indexOf(I18n.lang).coerceAtLeast(0)) ?: jp
    }

    fun fromJson(s: String?): Result? {
        if (s.isNullOrBlank()) return null
        return try {
            val start = s.indexOf('{')
            val end = s.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            val o = JSONObject(s.substring(start, end + 1))
            val arr = o.optJSONArray("words") ?: JSONArray()
            val words = (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { w ->
                    Word(
                        w.optString("word"), w.optString("base"), w.optString("reading"), w.optString("hangul"),
                        w.optString("pos"), w.optString("meaning"), w.optString("note")
                    )
                }
            }.filter { it.word.isNotBlank() }
            Result(words, o.optString("grammar"), o.optString("natural"), o.optString("source"))
        } catch (_: Exception) {
            null
        }
    }

    /* ---------- Which LLM to use ---------- */

    fun llmEngine(prefs: SharedPreferences): String? = Providers.llmEngine(prefs)

    // Words worth saving from a sentence, in dictionary form, for one-tap saving. Runs on a background thread.
    fun candidates(text: String, code: String): List<String> = try {
        when (code) {
            "ja" -> tokenizer.tokenize(text)
                .filter { it.partOfSpeechLevel1 !in setOf("記号", "助詞", "助動詞") }
                .map { t -> t.baseForm.takeIf { it != null && it != "*" } ?: t.surface }
            "zh" -> emptyList()
            else -> text.split(Regex("\\s+"))
                .map { w -> w.trim { c -> !c.isLetterOrDigit() && c != '\'' && c != '-' } }
        }.filter { it.isNotBlank() }.distinct()
    } catch (_: Throwable) {
        emptyList()
    }

    fun label(id: String) = if (id.isEmpty()) OFFLINE else Providers.label(id)

    /* ---------- LLM explanation ---------- */

    private fun prompt(code: String, home: Lang): String {
        val lang = Languages.byCode(code).english
        val mine = home.english
        val hangulField = if (home.code == "ko") "\"hangul\":\"pronunciation written in Hangul\"," else "\"hangul\":\"\","
        return "You are a friendly $lang tutor for $mine speakers. Explain the user's $lang text word by word. " +
            "Reply with JSON only, no code fences, in exactly this shape: " +
            "{\"words\":[{\"word\":\"the word as it appears\",\"base\":\"dictionary form\"," +
            "\"reading\":\"reading in $lang (kana for Japanese, pinyin with tone marks for Chinese, IPA otherwise)\"," +
            hangulField + "\"pos\":\"part of speech in $mine\"," +
            "\"meaning\":\"$mine meaning in this sentence\"," +
            "\"note\":\"short $mine note on conjugation, grammar or nuance, or empty\"}]," +
            "\"grammar\":\"$mine explanation of the key grammar points\"," +
            "\"natural\":\"natural $mine translation of the whole text\"} " +
            "Keep particles and auxiliary verbs as separate words, skip punctuation, " +
            "and write every explanation in $mine."
    }

    // Runs on a background thread. Throws on failure.
    fun explainWithLlm(prefs: SharedPreferences, engine: String, text: String, code: String): Result {
        val home = Languages.home(prefs)
        val raw = Providers.complete(prefs, engine, prompt(code, home), listOf("user" to text), 4096, 40000)
        val r = fromJson(raw) ?: throw IllegalStateException("format")
        if (r.words.isEmpty()) throw IllegalStateException("empty")
        return Result(r.words, r.grammar, r.natural, label(engine))
    }

    /* ---------- Offline explanation ---------- */

    // Hangul pronunciation is only useful to Korean speakers.
    private fun hangul(ctx: Context, word: String, code: String, home: String): String {
        if (home != "ko") return ""
        return try {
            when (code) {
                "ja" -> Japanese.convert(word)
                "zh" -> Chinese.convert(word) ?: ""
                "en" -> English.convert(ctx, word)
                "es" -> Spanish.convert(word)
                "fr" -> French.convert(word)
                "de" -> German.convert(word)
                else -> ""
            }
        } catch (_: Throwable) {
            ""
        }
    }

    private fun split(ctx: Context, text: String, code: String, home: String): List<Piece> = when (code) {
        "ja" -> tokenizer.tokenize(text)
            .filter { it.partOfSpeechLevel1 != "記号" }
            .map { t ->
                val base = t.baseForm.takeIf { it != null && it != "*" } ?: t.surface
                val reading = t.reading.takeIf { it != null && it != "*" } ?: ""
                Piece(t.surface, base, reading, hangul(ctx, t.surface, code, home), posLabel(t.partOfSpeechLevel1))
            }
        "zh" -> listOf(Piece(text, text, "", hangul(ctx, text, code, home), ""))
        else -> text.split(Regex("\\s+"))
            .map { w -> w.trim { c -> !c.isLetterOrDigit() && c != '\'' } }
            .filter { it.isNotEmpty() }
            .map { w -> Piece(w, w, "", hangul(ctx, w, code, home), "") }
    }

    // Splits and reads words on a background thread, then adds meanings in my language with on-device translation.
    // Calls done on the main thread.
    fun explainOffline(context: Context, text: String, code: String, home: String, done: (Result) -> Unit) {
        val app = context.applicationContext
        worker.execute {
            val pieces = try { split(app, text, code, home) } catch (_: Throwable) { emptyList() }
            main.post { addMeanings(text, code, home, pieces, done) }
        }
    }

    private fun addMeanings(text: String, code: String, home: String, pieces: List<Piece>, done: (Result) -> Unit) {
        val note = if (code == "zh") VocabText.t("zh_note") else ""
        if (code == home) {
            done(Result(pieces.map { Word(it.word, it.base, it.reading, it.hangul, it.pos, "", "") }, note, text, OFFLINE))
            return
        }
        val t = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(code)
                .setTargetLanguage(home)
                .build()
        )
        val meanings = arrayOfNulls<String>(pieces.size)
        var natural = ""
        var pending = pieces.size + 1
        fun finishOne() {
            pending--
            if (pending > 0) return
            t.close()
            val words = pieces.mapIndexed { i, pc ->
                Word(pc.word, pc.base, pc.reading, pc.hangul, pc.pos, meanings[i].orEmpty(), "")
            }
            done(Result(words, note, natural, OFFLINE))
        }
        t.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                t.translate(text)
                    .addOnSuccessListener { natural = it }
                    .addOnCompleteListener { finishOne() }
                pieces.forEachIndexed { i, pc ->
                    t.translate(pc.base)
                        .addOnSuccessListener { meanings[i] = it }
                        .addOnCompleteListener { finishOne() }
                }
            }
            .addOnFailureListener { repeat(pieces.size + 1) { finishOne() } }
    }
}