package com.example.interpreter

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale
import java.util.WeakHashMap

// Steadier speech.
//  - Uses a voice installed on the phone. Without this the engine may pick a network voice,
//    which streams from a server and stutters on a weak connection.
//  - Speaks at normal rate and pitch. Slowing or pitch-shifting makes some engines (Samsung)
//    stretch the audio, which sounds choppy. Two speakers get two different voices instead.
//  - The first syllable after a quiet moment gets cut or wobbles while the phone wakes its audio
//    output (speaker amp or Bluetooth). So before a cold start we play real silence through our
//    own audio track, wait for the output to open, and keep it open while speech continues.
object SpeechOut {
    const val WARM_ID = "warmup"
    private const val WAKE_MS = 350L      // time for the output to open from cold
    private const val IDLE_MS = 8000L     // keep the output open this long after the last speech

    private val main = Handler(Looper.getMainLooper())
    private val gen = WeakHashMap<TextToSpeech, Int>()

    private val configured = WeakHashMap<TextToSpeech, Boolean>()
    private val current = WeakHashMap<TextToSpeech, String>()
    private val voiceCache = WeakHashMap<TextToSpeech, HashMap<String, List<Voice>>>()

    // Offline voices for a language tag, best quality first.
    private fun offlineVoices(engine: TextToSpeech, tag: String): List<Voice> {
        val cache = voiceCache.getOrPut(engine) { HashMap() }
        cache[tag]?.let { return it }
        val want = Locale.forLanguageTag(tag)
        val all = try { engine.voices?.toList() ?: emptyList() } catch (e: Exception) { emptyList() }
        val usable = all.filter { v ->
            v.locale.language == want.language &&
                !v.isNetworkConnectionRequired &&
                !v.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
        }
        val sameCountry = usable.filter { want.country.isEmpty() || it.locale.country == want.country }
        val list = (sameCountry.ifEmpty { usable }).sortedWith(
            compareByDescending<Voice> { it.quality }.thenBy { it.latency }.thenBy { it.name }
        )
        if (list.isNotEmpty()) cache[tag] = list
        return list
    }

    // alt = true picks a second voice (for the other speaker) when the phone has one.
    // Returns true when a distinct voice was found for alt, so callers can skip pitch tricks.
    fun useVoice(engine: TextToSpeech, tag: String, alt: Boolean = false): Boolean {
        val voices = offlineVoices(engine, tag)
        val v = if (alt && voices.size > 1) voices[1] else voices.firstOrNull()
        val key = v?.name ?: "locale:$tag"
        if (current[engine] != key) {
            if (v != null) engine.voice = v else engine.language = Locale.forLanguageTag(tag)
            current[engine] = key
        }
        return !alt || voices.size > 1
    }

    fun speak(engine: TextToSpeech, text: String, voice: String, flush: Boolean, id: String, alt: Boolean = false) {
        if (configured[engine] != true) {
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            configured[engine] = true
        }
        val wasWarm = KeepAlive.touch()
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        if (flush) {
            engine.stop()
            gen[engine] = (gen[engine] ?: 0) + 1
        }
        if (wasWarm || engine.isSpeaking) {
            say(engine, text, voice, mode, id, alt)
        } else {
            // Cold output: wait for it to wake. A stop() in the meantime cancels this start.
            val g = gen[engine] ?: 0
            main.postDelayed({ if ((gen[engine] ?: 0) == g) say(engine, text, voice, mode, id, alt) }, WAKE_MS)
        }
    }

    private fun say(engine: TextToSpeech, text: String, voice: String, mode: Int, id: String, alt: Boolean) {
        KeepAlive.touch()
        val distinct = useVoice(engine, voice, alt)
        engine.setSpeechRate(1.0f)
        // Only when the phone has a single voice: a light pitch change to tell speakers apart.
        engine.setPitch(if (distinct) 1.0f else 0.9f)
        engine.speak(text, mode, null, id)
    }

    // Stops speech, including a start that is still waiting for the audio output to wake.
    fun stop(engine: TextToSpeech?) {
        engine ?: return
        gen[engine] = (gen[engine] ?: 0) + 1
        engine.stop()
    }

    // Plays silence on a background thread so the audio output stays awake between sentences.
    private object KeepAlive {
        @Volatile private var lastUse = 0L
        @Volatile private var thread: Thread? = null
        @Volatile private var warmSince = 0L

        // Returns true when the output has been awake long enough to speak right away.
        fun touch(): Boolean {
            val now = System.currentTimeMillis()
            lastUse = now
            val running = thread?.isAlive == true
            if (!running) {
                warmSince = now
                thread = Thread({ loop() }, "audio-keepalive").apply { isDaemon = true; start() }
                return false
            }
            return now - warmSince >= WAKE_MS
        }

        private fun loop() {
            val rate = 22050
            val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val track = try {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build()
                    )
                    .setBufferSizeInBytes(maxOf(min, rate / 5 * 2))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } catch (e: Exception) {
                thread = null
                return
            }
            val zeros = ShortArray(rate / 50) // 20 ms
            try {
                track.play()
                while (System.currentTimeMillis() - lastUse < IDLE_MS) {
                    if (track.write(zeros, 0, zeros.size) < 0) break
                }
            } catch (e: Exception) {
            } finally {
                try { track.stop() } catch (e: Exception) {}
                track.release()
                thread = null
            }
        }
    }
}
