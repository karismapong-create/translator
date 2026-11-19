package com.example.interpreter

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.google.mlkit.nl.languageid.LanguageIdentification
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Hands-free listening that decides who spoke by itself, sentence by sentence, with no turn order:
 * the app records one sentence from the microphone, then the phone's on-device recogniser hears that
 * same recording in my language and in theirs, and the better match wins (script of the words,
 * recogniser confidence, and on-device language identification of each result). Either person can
 * talk several sentences in a row.
 *
 * Needs Android 13+ with on-device speech recognition that accepts recorded audio; otherwise
 * [supported] is false and the caller keeps its live-microphone way.
 */
class DualListener(private val ctx: Context) {
    class Heard(val text: String, val lang: String)

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var capturing = false
    @Volatile private var cancelled = false
    private var failures = 0
    private val langId by lazy { LanguageIdentification.getClient() }

    companion object {
        private const val RATE = 16000
        private const val FRAME = 320 // 20 ms

        @Volatile var disabled = false

        fun supported(ctx: Context): Boolean =
            !disabled && Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)
    }

    /* ---------------- 1. One sentence from the microphone ---------------- */

    /**
     * Records until a sentence ends (a pause after speech). [thresholdDb] is the loudness (dBFS) that
     * counts as speech: higher sensitivity = lower threshold. Calls back on the main thread:
     * [onSpeech] when speech starts, [done] with the samples, or null when cancelled or nothing heard.
     */
    @SuppressLint("MissingPermission")
    fun capture(thresholdDb: Float, onSpeech: () -> Unit, done: (ShortArray?) -> Unit) {
        if (capturing) return
        capturing = true
        cancelled = false
        Thread {
            var result: ShortArray? = null
            var rec: AudioRecord? = null
            try {
                val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(min, FRAME * 8))
                rec.startRecording()
                val frame = ShortArray(FRAME)
                val pre = ArrayDeque<ShortArray>()      // 300 ms before speech starts
                val speech = ArrayList<ShortArray>()
                var loud = 0
                var quiet = 0
                var started = false
                val maxFrames = 15 * 1000 / 20         // 15 s at most
                val idleFrames = 30 * 1000 / 20        // gives up after 30 s of nothing
                var idle = 0
                while (!cancelled) {
                    val n = rec.read(frame, 0, FRAME)
                    if (n <= 0) continue
                    var sum = 0.0
                    for (i in 0 until n) sum += frame[i] * frame[i].toDouble()
                    val db = (20 * log10(sqrt(sum / n) / 32768.0 + 1e-9)).toFloat()
                    val copy = frame.copyOf(n)
                    if (!started) {
                        pre.addLast(copy)
                        if (pre.size > 15) pre.removeFirst()
                        loud = if (db > thresholdDb) loud + 1 else 0
                        if (loud >= 3) {
                            started = true
                            speech.addAll(pre)
                            main.post(onSpeech)
                        } else if (++idle > idleFrames) break
                    } else {
                        speech.add(copy)
                        quiet = if (db > thresholdDb - 6) 0 else quiet + 1
                        // A sentence ends after 1.2 s of quiet.
                        if (quiet >= 60 || speech.size >= maxFrames) break
                    }
                }
                if (started && !cancelled && speech.size > 25) {
                    val total = speech.sumOf { it.size }
                    val out = ShortArray(total)
                    var p = 0
                    for (f in speech) { f.copyInto(out, p); p += f.size }
                    result = out
                }
            } catch (_: Exception) {
            } finally {
                try { rec?.stop() } catch (_: Exception) {}
                rec?.release()
                capturing = false
            }
            val r = if (cancelled) null else result
            main.post { done(r) }
        }.start()
    }

    fun cancel() { cancelled = true }

    val isCapturing get() = capturing

    /* ---------------- 2. The same sentence heard in a language ---------------- */

    private class Pass(val text: String, val confidence: Float, val ok: Boolean, val error: Int)

    private fun recognize(samples: ShortArray, tag: String, done: (Pass) -> Unit) {
        if (Build.VERSION.SDK_INT < 33) { done(Pass("", -1f, false, -1)); return }
        val r = try { SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx) } catch (_: Exception) { null }
        if (r == null) { done(Pass("", -1f, false, -1)); return }
        val pipe = ParcelFileDescriptor.createPipe()
        var finished = false
        fun finish(p: Pass) {
            if (finished) return
            finished = true
            try { r.destroy() } catch (_: Exception) {}
            try { pipe[0].close() } catch (_: Exception) {}
            done(p)
        }
        val timeout = Runnable { finish(Pass("", -1f, false, SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) }
        main.postDelayed(timeout, 12000)
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(b: Bundle?) {
                main.removeCallbacks(timeout)
                val list = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val conf = b?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.firstOrNull() ?: -1f
                val text = list?.firstOrNull().orEmpty().trim()
                finish(Pass(text, conf, text.isNotEmpty(), 0))
            }
            override fun onError(error: Int) {
                main.removeCallbacks(timeout)
                finish(Pass("", -1f, false, error))
            }
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(b: Bundle?) {}
            override fun onEvent(type: Int, p: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, RATE)
        }
        try { r.startListening(intent) } catch (_: Exception) { main.removeCallbacks(timeout); finish(Pass("", -1f, false, -1)); return }
        // A missing language pack: ask the phone to download it for next time.
        Thread {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { out ->
                    val buf = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                    for (s in samples) buf.putShort(s)
                    out.write(buf.array())
                }
            } catch (_: Exception) {}
        }.start()
    }

    private fun downloadPack(tag: String) {
        if (Build.VERSION.SDK_INT < 33) return
        main.post {
            try {
                val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
                r.triggerModelDownload(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag))
                main.postDelayed({ try { r.destroy() } catch (_: Exception) {} }, 3000)
            } catch (_: Exception) {}
        }
    }

    /* ---------------- 3. Which language it was ---------------- */

    private fun scriptScore(text: String, code: String, otherCode: String): Float {
        val mine = LangCheck.looks(text, code)
        val other = LangCheck.looks(text, otherCode)
        return when {
            mine && !other -> 0.6f
            other && !mine -> -1f
            else -> 0f
        }
    }

    private fun identify(text: String, done: (Map<String, Float>) -> Unit) {
        if (text.isBlank()) { done(emptyMap()); return }
        langId.identifyPossibleLanguages(text)
            .addOnSuccessListener { list -> done(list.associate { it.languageTag.substringBefore('-') to it.confidence }) }
            .addOnFailureListener { done(emptyMap()) }
    }

    private fun score(p: Pass, code: String, other: String, ids: Map<String, Float>): Float {
        if (!p.ok) return -10f
        val conf = if (p.confidence >= 0f) p.confidence else 0.5f
        val id = ids[code] ?: 0f
        val len = minOf(p.text.length, 40) / 400f
        return conf + scriptScore(p.text, code, other) + id * 0.6f + len
    }

    /**
     * Hears [samples] in both languages and returns the better one on the main thread (null = nothing
     * clear). [first] is tried first; a clear, confident match there skips the second pass.
     */
    fun decide(samples: ShortArray, first: Lang, second: Lang, done: (Heard?) -> Unit) {
        recognize(samples, first.speechTag) { a ->
            if (a.error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE || a.error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED) downloadPack(first.speechTag)
            identify(a.text) { idsA ->
                val clear = a.ok && a.confidence >= 0.8f && scriptScore(a.text, first.code, second.code) >= 0f &&
                    (idsA[first.code] ?: 0f) >= 0.5f
                if (clear) { failures = 0; done(Heard(a.text, first.code)); return@identify }
                recognize(samples, second.speechTag) { b ->
                    if (b.error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE || b.error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED) downloadPack(second.speechTag)
                    identify(b.text) { idsB ->
                        if (!a.ok && !b.ok) {
                            // Twice nothing from a real sentence: this phone doesn't take recordings.
                            if (++failures >= 3) disabled = true
                            done(null)
                            return@identify
                        }
                        failures = 0
                        val sa = score(a, first.code, second.code, idsA)
                        val sb = score(b, second.code, first.code, idsB)
                        done(if (sa >= sb) Heard(a.text, first.code) else Heard(b.text, second.code))
                    }
                }
            }
        }
    }
}
