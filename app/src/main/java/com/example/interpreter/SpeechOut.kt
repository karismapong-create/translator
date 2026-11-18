package com.example.interpreter

import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale
import java.util.WeakHashMap

// Steadier speech.
//  - Uses a voice installed on the phone. Without this the engine may pick a network voice,
//    which streams from a server and stutters on a weak connection.
//  - Speaks at normal rate and pitch. Slowing or pitch-shifting makes some engines (Samsung)
//    stretch the audio, which sounds choppy. Two speakers get two different voices instead.
//  - Media audio attributes, language set only when it changes, and a short silence before a
//    fresh start so the audio output is open when the first syllable arrives.
object SpeechOut {
    const val WARM_ID = "warmup"
    private const val WARM_MS = 150L

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
    private fun useVoice(engine: TextToSpeech, tag: String, alt: Boolean): Boolean {
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
        val distinct = useVoice(engine, voice, alt)
        engine.setSpeechRate(1.0f)
        // Only when the phone has a single voice: a light pitch change to tell speakers apart.
        engine.setPitch(if (distinct) 1.0f else 0.9f)
        val fresh = flush || !engine.isSpeaking
        if (fresh) {
            engine.playSilentUtterance(WARM_MS, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, WARM_ID)
        }
        engine.speak(text, TextToSpeech.QUEUE_ADD, null, id)
    }
}
