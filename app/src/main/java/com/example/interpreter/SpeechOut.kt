package com.example.interpreter

import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import java.util.Locale
import java.util.WeakHashMap

// Steadier speech start. The first fraction of a second of a new utterance is often clipped or
// wobbly while the audio output wakes up (worse on Bluetooth), and changing the voice language
// before every sentence makes the engine reload the voice. So we:
//  - give speech its own media audio attributes once per engine,
//  - change the language only when it actually changes,
//  - play a short silence first when starting fresh, so the audio path is already open
//    when the first syllable arrives.
object SpeechOut {
    const val WARM_ID = "warmup"
    private const val WARM_MS = 180L

    private val configured = WeakHashMap<TextToSpeech, Boolean>()
    private val lastLocale = WeakHashMap<TextToSpeech, String>()

    fun speak(engine: TextToSpeech, text: String, voice: String, flush: Boolean, id: String) {
        if (configured[engine] != true) {
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            configured[engine] = true
        }
        if (lastLocale[engine] != voice) {
            engine.language = Locale.forLanguageTag(voice)
            lastLocale[engine] = voice
        }
        val fresh = flush || !engine.isSpeaking
        if (fresh) {
            engine.playSilentUtterance(WARM_MS, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, WARM_ID)
        }
        engine.speak(text, TextToSpeech.QUEUE_ADD, null, id)
    }
}
