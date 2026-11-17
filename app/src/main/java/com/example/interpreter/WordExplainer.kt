package com.example.interpreter

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

// Breaks a saved sentence into words with reading, Hangul pronunciation, part of speech and meaning.
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

    const val OFFLINE = "기기 분석"
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val tokenizer by lazy { com.atilika.kuromoji.ipadic.Tokenizer() }
    private val names = mapOf(
        "ko" to "Korean", "ja" to "Japanese", "en" to "English",
        "fr" to "French", "es" to "Spanish", "zh" to "Simplified Chinese"
    )
    private val posKo = mapOf(
        "名詞" to "명사", "動詞" to "동사", "形容詞" to "형용사", "副詞" to "부사", "助詞" to "조사",
        "助動詞" to "조동사", "接続詞" to "접속사", "連体詞" to "연체사", "感動詞" to "감탄사",
        "接頭詞" to "접두사", "フィラー" to "간투사"
    )

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

    fun label(id: String) = if (id.isEmpty()) OFFLINE else Providers.label(id)

    /* ---------- LLM explanation ---------- */

    private fun prompt(code: String): String {
        val lang = names[code] ?: code
        return "You are a friendly $lang tutor for Korean speakers. Explain the user's $lang text word by word. " +
            "Reply with JSON only, no code fences, in exactly this shape: " +
            "{\"words\":[{\"word\":\"the word as it appears\",\"base\":\"dictionary form\"," +
            "\"reading\":\"reading in $lang (kana for Japanese, pinyin with tone marks for Chinese, IPA otherwise)\"," +
            "\"hangul\":\"pronunciation written in Hangul\",\"pos\":\"part of speech in Korean\"," +
            "\"meaning\":\"Korean meaning in this sentence\"," +
            "\"note\":\"short Korean note on conjugation, grammar or nuance, or empty\"}]," +
            "\"grammar\":\"Korean explanation of the key grammar points\"," +
            "\"natural\":\"natural Korean translation of the whole text\"} " +
            "Keep particles and auxiliary verbs as separate words, skip punctuation, " +
            "and write every explanation in Korean."
    }

    // Runs on a background thread. Throws on failure.
    fun explainWithLlm(prefs: SharedPreferences, engine: String, text: String, code: String): Result {
        val raw = Providers.complete(prefs, engine, prompt(code), listOf("user" to text), 4096, 40000)
        val r = fromJson(raw) ?: throw IllegalStateException("설명 형식을 읽지 못했어요")
        if (r.words.isEmpty()) throw IllegalStateException("단어 설명이 비어 있어요")
        return Result(r.words, r.grammar, r.natural, label(engine))
    }

    /* ---------- Offline explanation ---------- */

    private fun hangul(ctx: Context, word: String, code: String): String = try {
        when (code) {
            TranslateLanguage.JAPANESE -> Japanese.convert(word)
            TranslateLanguage.CHINESE -> Chinese.convert(word) ?: ""
            TranslateLanguage.ENGLISH -> English.convert(ctx, word)
            TranslateLanguage.SPANISH -> Spanish.convert(word)
            TranslateLanguage.FRENCH -> French.convert(word)
            else -> ""
        }
    } catch (_: Throwable) {
        ""
    }

    private fun split(ctx: Context, text: String, code: String): List<Piece> = when (code) {
        TranslateLanguage.JAPANESE -> tokenizer.tokenize(text)
            .filter { it.partOfSpeechLevel1 != "記号" }
            .map { t ->
                val base = t.baseForm.takeIf { it != null && it != "*" } ?: t.surface
                val reading = t.reading.takeIf { it != null && it != "*" } ?: ""
                Piece(t.surface, base, reading, hangul(ctx, t.surface, code), posKo[t.partOfSpeechLevel1] ?: t.partOfSpeechLevel1)
            }
        TranslateLanguage.CHINESE -> listOf(Piece(text, text, "", hangul(ctx, text, code), "문장"))
        else -> text.split(Regex("\\s+"))
            .map { w -> w.trim { c -> !c.isLetterOrDigit() && c != '\'' } }
            .filter { it.isNotEmpty() }
            .map { w -> Piece(w, w, "", hangul(ctx, w, code), "") }
    }

    // Splits and reads words on a background thread, then adds meanings with on-device translation.
    // Calls done on the main thread.
    fun explainOffline(context: Context, text: String, code: String, done: (Result) -> Unit) {
        val app = context.applicationContext
        worker.execute {
            val pieces = try { split(app, text, code) } catch (_: Throwable) { emptyList() }
            main.post { addMeanings(text, code, pieces, done) }
        }
    }

    private fun addMeanings(text: String, code: String, pieces: List<Piece>, done: (Result) -> Unit) {
        val note = if (code == TranslateLanguage.CHINESE)
            "중국어는 띄어쓰기가 없어서, 단어별 설명에는 Gemini, Claude, 맥북 LLM 같은 LLM 엔진이 필요해요." else ""
        if (code == TranslateLanguage.KOREAN) {
            done(Result(pieces.map { Word(it.word, it.base, it.reading, it.hangul, it.pos, "", "") }, note, text, OFFLINE))
            return
        }
        val t = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(code)
                .setTargetLanguage(TranslateLanguage.KOREAN)
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