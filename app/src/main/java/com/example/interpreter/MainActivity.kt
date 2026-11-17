package com.example.interpreter

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private data class Lang(val label: String, val speechTag: String, val mlkit: String)
    private data class Term(val names: Map<String, String>, val variants: List<String>)
    private class DeepLException(val code: Int) : Exception("HTTP $code")

    companion object {
        const val DEFAULT_TERMS =
            "마운자로 | 마운 자로 | 일본어=マンジャロ, 영어=Mounjaro, 중국어=穆峰达, 스페인어=Mounjaro, 프랑스어=Mounjaro"
    }

    private val langs = listOf(
        Lang("한국어", "ko-KR", TranslateLanguage.KOREAN),
        Lang("日本語", "ja-JP", TranslateLanguage.JAPANESE),
        Lang("English", "en-US", TranslateLanguage.ENGLISH),
        Lang("Français", "fr-FR", TranslateLanguage.FRENCH),
        Lang("Español", "es-ES", TranslateLanguage.SPANISH),
        Lang("中文", "zh-CN", TranslateLanguage.CHINESE)
    )
    private val aliases = mapOf(
        "한국어" to TranslateLanguage.KOREAN, "일본어" to TranslateLanguage.JAPANESE,
        "영어" to TranslateLanguage.ENGLISH, "프랑스어" to TranslateLanguage.FRENCH,
        "스페인어" to TranslateLanguage.SPANISH, "중국어" to TranslateLanguage.CHINESE
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
    private var terms: List<Term> = emptyList()
    private val repaired = HashSet<String>()
    private val prefs by lazy { getSharedPreferences("interpreter", MODE_PRIVATE) }

    private val net = Executors.newSingleThreadExecutor()
    private var deeplBlockedUntil = 0L
    private val recent = ArrayDeque<String>()

    private val from: Lang get() = langs[fromSpinner.selectedItemPosition]
    private val to: Lang get() = langs[toSpinner.selectedItemPosition]
    private val noSpaces: Boolean get() = from.speechTag.startsWith("ja") || from.speechTag.startsWith("zh")
    private val deeplKey: String get() = prefs.getString("deepl", "").orEmpty().trim()

    private val restartRunnable = Runnable { if (listening) { stop(); start() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        terms = parseTerms(prefs.getString("terms", DEFAULT_TERMS) ?: DEFAULT_TERMS)

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
            text = "언어를 고르고 시작을 누르세요. 번역문을 길게 누르면 선택해서 복사할 수 있어요."
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
        val settingsButton = Button(this).apply {
            text = "설정"
            setOnClickListener { openSettings() }
        }
        micButton = Button(this).apply {
            text = "시작"
            textSize = 18f
            setOnClickListener { if (listening) stop() else start() }
        }
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(speakBox, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(settingsButton, LinearLayout.LayoutParams(WRAP_CONTENT, dp(64)))
            addView(micButton, LinearLayout.LayoutParams(dp(120), dp(64)))
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

    /* ---------- Settings ---------- */

    private fun openSettings() {
        AlertDialog.Builder(this)
            .setTitle("설정")
            .setItems(arrayOf("단어장", "DeepL API 키")) { _, which ->
                if (which == 0) openDictionary() else openDeeplKey()
            }
            .show()
    }

    private fun dialogBox(edit: EditText) = FrameLayout(this).apply {
        setPadding(dp(20), dp(8), dp(20), 0)
        addView(edit)
    }

    private fun openDeeplKey() {
        val edit = EditText(this).apply {
            setText(prefs.getString("deepl", ""))
            setHint("예: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx:fx")
            textSize = 15f
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("DeepL API 키")
            .setMessage("키를 넣으면 인터넷이 될 때 DeepL로 번역하고, 안 될 때는 기기 번역으로 자동 전환해요. 비워두면 기기 번역만 써요.")
            .setView(dialogBox(edit))
            .setPositiveButton("저장") { _, _ ->
                prefs.edit().putString("deepl", edit.text.toString().trim()).apply()
                deeplBlockedUntil = 0L
                if (listening) { stop(); start() }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    /* ---------- Word list ---------- */

    private fun parseTerms(text: String): List<Term> = text.lines().mapNotNull { line ->
        val parts = line.split("|").map { it.trim() }
        val key = parts.getOrNull(0).orEmpty()
        if (key.isEmpty() || key.startsWith("#")) return@mapNotNull null
        val variants = parts.getOrNull(1).orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val names = HashMap<String, String>()
        names[TranslateLanguage.KOREAN] = key
        parts.getOrNull(2).orEmpty().split(",").forEach { pair ->
            val kv = pair.split("=", limit = 2)
            if (kv.size == 2) {
                val label = kv[0].trim()
                val value = kv[1].trim()
                val code = aliases[label]
                    ?: langs.firstOrNull { it.label.equals(label, true) || it.mlkit == label.lowercase() }?.mlkit
                if (code != null && value.isNotEmpty()) names[code] = value
            }
        }
        Term(names, variants)
    }

    // Matches a Korean word even if the recognizer split it with spaces.
    private fun flex(word: String) = Regex(
        word.replace(" ", "").map { Regex.escape(it.toString()) }.joinToString("\\s*"),
        RegexOption.IGNORE_CASE
    )

    // Returns the corrected source to show, and the text to send to the translator.
    private fun applyTerms(src: String): Pair<String, String> {
        var shown = src
        if (from.mlkit == TranslateLanguage.KOREAN) {
            for (t in terms) {
                val key = t.names[TranslateLanguage.KOREAN] ?: continue
                for (w in t.variants + key) shown = flex(w).replace(shown) { key }
            }
        }
        var input = shown
        for (t in terms) {
            val a = t.names[from.mlkit] ?: continue
            val b = t.names[to.mlkit] ?: continue
            input = input.replace(a, b, ignoreCase = true)
        }
        return shown to input
    }

    private fun openDictionary() {
        val edit = EditText(this).apply {
            setText(prefs.getString("terms", DEFAULT_TERMS))
            textSize = 15f
            minLines = 6
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setHorizontallyScrolling(false)
        }
        AlertDialog.Builder(this)
            .setTitle("단어장")
            .setMessage(
                "한 줄에 단어 하나씩 적어요.\n" +
                "한국어 단어 | 잘못 들리는 말 | 언어=번역\n" +
                "잘못 들리는 말은 쉼표로 여러 개 적을 수 있고, 가운데·끝 칸은 비워도 돼요."
            )
            .setView(dialogBox(edit))
            .setPositiveButton("저장") { _, _ ->
                val text = edit.text.toString()
                prefs.edit().putString("terms", text).apply()
                terms = parseTerms(text)
                if (listening) { stop(); start() }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    /* ---------- Language pack check ---------- */

    private fun looksLike(text: String, lang: String): Boolean {
        val letters = text.filter { it.isLetter() }
        if (letters.length < 3) return true
        val hangul = letters.count { it in '\uAC00'..'\uD7A3' }
        val kana = letters.count { it in '\u3040'..'\u30FF' }
        val han = letters.count { it in '\u4E00'..'\u9FFF' }
        val latin = letters.count { it in 'a'..'z' || it in 'A'..'Z' || it in '\u00C0'..'\u024F' }
        return when (lang) {
            TranslateLanguage.KOREAN -> hangul > 0
            TranslateLanguage.JAPANESE -> kana + han > 0
            TranslateLanguage.CHINESE -> han > 0
            else -> latin * 2 > letters.length
        }
    }

    // Deletes and re-downloads the language packs once if output comes back in the wrong language.
    private fun repairLanguagePack() {
        if (!repaired.add(from.mlkit + ">" + to.mlkit)) return
        stop()
        translator?.close()
        translator = null
        status("번역 결과가 이상해서 언어팩을 다시 받는 중이에요…")
        val mm = RemoteModelManager.getInstance()
        val tasks = listOf(from.mlkit, to.mlkit)
            .filter { it != TranslateLanguage.ENGLISH }
            .map { mm.deleteDownloadedModel(TranslateRemoteModel.Builder(it).build()) }
        Tasks.whenAllComplete(tasks).addOnCompleteListener { start() }
    }

    /* ---------- Start / stop ---------- */

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
        recent.clear()
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
                    val engine = if (deeplKey.isNotEmpty()) "DeepL" else "기기 번역"
                    status("듣는 중 · ${from.label} → ${to.label} · $engine")
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

    /* ---------- Speech recognition ---------- */

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
            if (Build.VERSION.SDK_INT >= 33) {
                val words = terms.mapNotNull { it.names[from.mlkit] }
                if (words.isNotEmpty()) putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(words))
            }
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

    /* ---------- Translation ---------- */

    private fun translate(raw: String) {
        val t = translator ?: return
        val id = ++seq
        hint?.let { segments.removeView(it); hint = null }
        val (shown, input) = applyTerms(raw)
        val source = from.mlkit
        val target = to.mlkit

        val trView = TextView(this).apply {
            text = "…"
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
            setTextIsSelectable(true)
        }
        val srcView = TextView(this).apply {
            text = shown
            textSize = 14f
            setTextColor(Color.GRAY)
            setTextIsSelectable(true)
        }
        segments.addView(trView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(14) })
        segments.addView(srcView)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }

        val context = recent.joinToString(" ")
        recent.addLast(shown)
        while (recent.size > 3) recent.removeFirst()

        val key = deeplKey
        if (key.isEmpty() || System.currentTimeMillis() < deeplBlockedUntil) {
            viaMlKit(t, id, trView, input, source, target)
            return
        }
        net.execute {
            val result: Any = try { deepl(input, source, target, key, context) } catch (e: Exception) { e }
            runOnUiThread {
                if (result is String) {
                    showResult(id, trView, result, target)
                } else {
                    deeplFailed(result as Exception)
                    viaMlKit(t, id, trView, input, source, target)
                }
            }
        }
    }

    private fun viaMlKit(t: Translator, id: Int, trView: TextView, input: String, source: String, target: String) {
        t.translate(input)
            .addOnSuccessListener { tr ->
                if (!looksLike(tr, target) && looksLike(input, source)) {
                    trView.text = "$tr\n(언어팩 오류 · 다시 받는 중)"
                    queueSpeech(id, null)
                    repairLanguagePack()
                    return@addOnSuccessListener
                }
                showResult(id, trView, tr, target)
            }
            .addOnFailureListener {
                trView.text = "번역 실패"
                queueSpeech(id, null)
            }
    }

    private fun showResult(id: Int, trView: TextView, tr: String, target: String) {
        trView.text = tr
        Pronouncer.attach(this, segments, trView, tr, target)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        queueSpeech(id, tr)
    }

    /* ---------- DeepL ---------- */

    private fun deeplCode(lang: String, asTarget: Boolean): String =
        if (lang == TranslateLanguage.ENGLISH && asTarget) "EN-US" else lang.uppercase()

    // Runs on a background thread.
    private fun deepl(text: String, source: String, target: String, key: String, context: String): String {
        val host = if (key.endsWith(":fx")) "api-free.deepl.com" else "api.deepl.com"
        val conn = URL("https://$host/v2/translate").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 3000
        conn.readTimeout = 6000
        conn.doOutput = true
        conn.setRequestProperty("Authorization", "DeepL-Auth-Key $key")
        conn.setRequestProperty("Content-Type", "application/json")
        val body = JSONObject()
            .put("text", JSONArray().put(text))
            .put("source_lang", deeplCode(source, false))
            .put("target_lang", deeplCode(target, true))
            .put("formality", "prefer_more")
        if (context.isNotBlank()) body.put("context", context)
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code != 200) throw DeepLException(code)
            val json = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return JSONObject(json).getJSONArray("translations").getJSONObject(0).getString("text")
        } finally {
            conn.disconnect()
        }
    }

    private fun deeplFailed(e: Exception) {
        val code = (e as? DeepLException)?.code
        val (wait, msg) = when (code) {
            401, 403 -> Long.MAX_VALUE to "DeepL 키가 올바르지 않아요. 설정에서 확인하세요. 지금은 기기 번역을 써요."
            456 -> Long.MAX_VALUE to "이번 달 DeepL 무료 한도를 다 썼어요. 기기 번역으로 전환했어요."
            429 -> 10_000L to "DeepL 요청이 많아 잠시 기기 번역을 써요."
            else -> 30_000L to "DeepL에 연결하지 못해 잠시 기기 번역을 써요."
        }
        deeplBlockedUntil = if (wait == Long.MAX_VALUE) Long.MAX_VALUE else System.currentTimeMillis() + wait
        status(msg)
    }

    /* ---------- Speech output ---------- */

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
        net.shutdown()
        super.onDestroy()
    }
}