package com.example.interpreter

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.Locale

class MainActivity : Activity() {

    private data class Lang(val label: String, val speechTag: String, val mlkit: String)

    private val langs = listOf(
        Lang("한국어", "ko-KR", TranslateLanguage.KOREAN),
        Lang("日本語", "ja-JP", TranslateLanguage.JAPANESE),
        Lang("English", "en-US", TranslateLanguage.ENGLISH),
        Lang("Français", "fr-FR", TranslateLanguage.FRENCH),
        Lang("Español", "es-ES", TranslateLanguage.SPANISH),
        Lang("中文", "zh-CN", TranslateLanguage.CHINESE)
    )

    private lateinit var fromSpinner: Spinner
    private lateinit var toSpinner: Spinner
    private lateinit var segments: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var interimView: TextView
    private lateinit var statusView: TextView
    private lateinit var speakBox: CheckBox
    private lateinit var micButton: Button
    private var hint: TextView? = null

    private var recognizer: SpeechRecognizer? = null
    private var translator: Translator? = null
    private var tts: TextToSpeech? = null
    private var listening = false
    private var committed = 0
    private var seq = 0
    private var nextToSpeak = 1
    private val speechBuf = HashMap<Int, String?>()

    private val from: Lang get() = langs[fromSpinner.selectedItemPosition]
    private val to: Lang get() = langs[toSpinner.selectedItemPosition]
    private val noSpaces: Boolean get() = from.speechTag.startsWith("ja") || from.speechTag.startsWith("zh")

    private val restartRunnable = Runnable { if (listening) { stop(); start() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val labels = langs.map { it.label }
        fromSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, labels)
            setSelection(1)
        }
        toSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, labels)
            setSelection(0)
        }
        val swap = Button(this).apply {
            text = "⇄"
            textSize = 20f
            setOnClickListener {
                val f = fromSpinner.selectedItemPosition
                fromSpinner.setSelection(toSpinner.selectedItemPosition)
                toSpinner.setSelection(f)
            }
        }
        val onChange = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                scroll.removeCallbacks(restartRunnable)
                scroll.postDelayed(restartRunnable, 300)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(fromSpinner, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(swap, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(toSpinner, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }

        segments = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        hint = TextView(this).apply {
            text = "언어를 고르고 시작을 누르세요. 상대가 말하는 도중에도 구절 단위로 바로 번역합니다."
            textSize = 16f
            setTextColor(Color.GRAY)
        }
        segments.addView(hint)
        scroll = ScrollView(this).apply { addView(segments) }

        interimView = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.GRAY)
            setTypeface(null, Typeface.ITALIC)
        }
        statusView = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.GRAY)
        }
        speakBox = CheckBox(this).apply { text = "음성으로 읽기" }
        micButton = Button(this).apply {
            text = "시작"
            textSize = 18f
            setOnClickListener { if (listening) stop() else start() }
        }
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(speakBox, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(micButton, LinearLayout.LayoutParams(dp(140), dp(64)))
        }

        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            addView(top, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(interimView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(statusView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(bottom, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        setContentView(root)

        fromSpinner.onItemSelectedListener = onChange
        toSpinner.onItemSelectedListener = onChange
        tts = TextToSpeech(this) { }
    }

    private fun start() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status("이 폰에서는 음성 인식을 쓸 수 없어요. Google 앱이 설치되어 있고 켜져 있는지 확인하세요.")
            return
        }
        if (from == to) {
            status("서로 다른 두 언어를 고르세요.")
            return
        }
        listening = true
        micButton.text = "정지"
        tts?.language = Locale.forLanguageTag(to.speechTag)

        translator?.close()
        val t = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(from.mlkit)
                .setTargetLanguage(to.mlkit)
                .build()
        )
        translator = t
        status("언어팩 준비 중… 처음 한 번은 인터넷이 필요해요.")
        t.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                if (listening && translator === t) {
                    status("듣는 중 · ${from.label} → ${to.label}")
                    startRecognizer()
                }
            }
            .addOnFailureListener {
                stop()
                status("언어팩을 받지 못했어요. 인터넷 연결을 확인하세요.")
            }
    }

    private fun stop() {
        listening = false
        recognizer?.destroy()
        recognizer = null
        interimView.text = ""
        micButton.text = "시작"
        status("")
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(rc, perms, res)
        if (res.firstOrNull() == PackageManager.PERMISSION_GRANTED) start()
        else status("마이크 권한을 허용해야 통역할 수 있어요.")
    }

    private fun startRecognizer() {
        recognizer?.destroy()
        committed = 0
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(b: Bundle?) = handle(firstResult(b), false)
            override fun onResults(b: Bundle?) {
                handle(firstResult(b), true)
                restartRecognizer()
            }
            override fun onError(error: Int) {
                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    stop()
                    status("마이크 권한이 없어요.")
                    return
                }
                restartRecognizer()
            }
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(type: Int, p: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, from.speechTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
        }
        r.startListening(intent)
    }

    private fun restartRecognizer() {
        if (listening) scroll.postDelayed({ if (listening) startRecognizer() }, 100)
    }

    private fun firstResult(b: Bundle?): String =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""

    // Send phrases for translation while the speaker is still talking.
    private fun handle(text: String, isFinal: Boolean) {
        if (!listening) return
        if (committed > text.length) committed = text.length
        val rest = text.substring(committed)
        if (isFinal) {
            if (rest.isNotBlank()) translate(rest.trim())
            committed = 0
            interimView.text = ""
            return
        }
        val size = if (noSpaces) rest.length / 2.5
                   else rest.trim().split(Regex("\\s+")).size.toDouble()
        if (size >= 9) {
            val stable = stablePart(rest)
            if (stable.isNotBlank()) {
                translate(stable.trim())
                committed += stable.length
            }
        }
        interimView.text = text.substring(committed)
    }

    // Hold back the last few words, which the recognizer may still revise.
    private fun stablePart(rest: String): String {
        if (noSpaces) return if (rest.length > 4) rest.substring(0, rest.length - 4) else ""
        val words = rest.split(" ")
        if (words.size <= 4) return ""
        return words.dropLast(4).joinToString(" ") + " "
    }

    private fun translate(src: String) {
        val t = translator ?: return
        val id = ++seq
        hint?.let { segments.removeView(it); hint = null }

        val trView = TextView(this).apply {
            text = "…"
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
        }
        val srcView = TextView(this).apply {
            text = src
            textSize = 14f
            setTextColor(Color.GRAY)
        }
        segments.addView(trView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(14) })
        segments.addView(srcView)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }

        t.translate(src)
            .addOnSuccessListener { tr ->
                trView.text = tr
                Pronouncer.attach(this, segments, trView, tr, to.mlkit)
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                queueSpeech(id, tr)
            }
            .addOnFailureListener {
                trView.text = "번역 실패"
                queueSpeech(id, null)
            }
    }

    // Speak translations in the order the phrases were heard.
    private fun queueSpeech(id: Int, text: String?) {
        speechBuf[id] = text
        while (speechBuf.containsKey(nextToSpeak)) {
            val t = speechBuf.remove(nextToSpeak)
            if (t != null && speakBox.isChecked) {
                tts?.speak(t, TextToSpeech.QUEUE_ADD, null, "seg$nextToSpeak")
            }
            nextToSpeak++
        }
    }

    private fun status(msg: String) { statusView.text = msg }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        listening = false
        recognizer?.destroy()
        translator?.close()
        tts?.shutdown()
        super.onDestroy()
    }
}