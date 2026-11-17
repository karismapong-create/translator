package com.example.interpreter

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.ActionMode
import android.view.Gravity
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
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
import android.widget.Toast
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
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

    private data class Lang(val label: String, val speechTag: String, val mlkit: String, val english: String)
    private data class Term(val names: Map<String, String>, val variants: List<String>)
    private data class Engine(val id: String, val label: String)
    private class Field(val key: String, val label: String, val hint: String, val default: String = "")
    private class HttpException(val code: Int, val body: String) : Exception("HTTP $code")

    // One translated line on screen.
    private class Seg(
        val src: String, val voice: String, val view: TextView, val typed: Boolean,
        val sl: Lang, val tl: Lang
    ) {
        var tr: String = ""
    }

    // Source text with glossary terms swapped for placeholder codes, and how to put them back.
    private class Prepared(val shown: String, val input: String, val codes: Map<Regex, String>) {
        fun restore(text: String): String {
            var out = text
            for ((re, name) in codes) out = re.replace(out) { name }
            return out
        }
    }

    companion object {
        const val DEFAULT_TERMS =
            "마운자로 | | 일본어=マンジャロ, 영어=Mounjaro, 중국어=穆峰达, 스페인어=Mounjaro, 프랑스어=Mounjaro"
        const val MENU_SAVE_WORD = 1001
        const val DEFAULT_CLAUDE_MODEL = "claude-haiku-5-5"
    }

    private val langs = listOf(
        Lang("한국어", "ko-KR", TranslateLanguage.KOREAN, "Korean"),
        Lang("日本語", "ja-JP", TranslateLanguage.JAPANESE, "Japanese"),
        Lang("English", "en-US", TranslateLanguage.ENGLISH, "English"),
        Lang("Français", "fr-FR", TranslateLanguage.FRENCH, "French"),
        Lang("Español", "es-ES", TranslateLanguage.SPANISH, "Spanish"),
        Lang("中文", "zh-CN", TranslateLanguage.CHINESE, "Simplified Chinese")
    )
    private val korean get() = langs[0]
    private val latinCodes = setOf(TranslateLanguage.ENGLISH, TranslateLanguage.FRENCH, TranslateLanguage.SPANISH)
    private val aliases = mapOf(
        "한국어" to TranslateLanguage.KOREAN, "일본어" to TranslateLanguage.JAPANESE,
        "영어" to TranslateLanguage.ENGLISH, "프랑스어" to TranslateLanguage.FRENCH,
        "스페인어" to TranslateLanguage.SPANISH, "중국어" to TranslateLanguage.CHINESE
    )
    private val engines = listOf(
        Engine("mlkit", "기기 번역만"),
        Engine("deepl", "DeepL"),
        Engine("claude", "Claude"),
        Engine("openai", "OpenAI 호환 API"),
        Engine("local", "로컬 LLM (맥북)")
    )

    private lateinit var fromSpinner: Spinner
    private lateinit var toSpinner: Spinner
    private lateinit var segments: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var interimView: TextView
    private lateinit var statusView: TextView
    private lateinit var speakBox: CheckBox
    private lateinit var micButton: Button
    private lateinit var chatInput: EditText
    private lateinit var vocabPage: VocabView
    private lateinit var glossaryPage: GlossaryView
    private lateinit var pages: List<View>
    private lateinit var tabViews: List<TextView>
    private var hint: TextView? = null

    private var recognizer: SpeechRecognizer? = null
    private var translator: Translator? = null
    private val translators = HashMap<String, Translator>()
    private var tts: TextToSpeech? = null
    private var listening = false
    private var committed = 0
    private var seq = 0
    private var nextToSpeak = 1
    private val speechBuf = HashMap<Int, Seg?>()
    private val screenLog = ArrayList<Seg>()
    private val utter = HashMap<String, Seg>()
    private var utterCount = 0
    private var terms: List<Term> = emptyList()
    private val repaired = HashSet<String>()
    private val prefs by lazy { getSharedPreferences("interpreter", MODE_PRIVATE) }

    private val net = Executors.newSingleThreadExecutor()
    private var remoteBlockedUntil = 0L
    private val recent = ArrayDeque<String>()

    // Automatic language detection
    private var heard: Lang = langs[0]
    private var foreign: Lang = langs[1]
    private var detectedTag: String? = null
    private val langId by lazy { LanguageIdentification.getClient() }

    // Silent mode
    private val audio by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val silent: Boolean get() = prefs.getBoolean("silent", true)
    private var quiet = false
    private var musicMuted = false

    private val auto: Boolean get() = fromSpinner.selectedItemPosition == 0
    private val from: Lang get() = if (auto) heard else langs[fromSpinner.selectedItemPosition - 1]
    private val to: Lang get() = langs[toSpinner.selectedItemPosition]
    private val noSpaces: Boolean get() = from.speechTag.startsWith("ja") || from.speechTag.startsWith("zh")

    private fun p(key: String) = prefs.getString(key, "").orEmpty().trim()
    private val engineId: String
        get() = prefs.getString("engine", null) ?: if (p("deepl").isNotEmpty()) "deepl" else "mlkit"
    private fun engineLabel(id: String) = engines.firstOrNull { it.id == id }?.label ?: id
    private fun engineReady(id: String): Boolean = when (id) {
        "deepl" -> p("deepl").isNotEmpty()
        "claude" -> p("claude_key").isNotEmpty()
        "openai" -> p("openai_url").isNotEmpty() && p("openai_key").isNotEmpty() && p("openai_model").isNotEmpty()
        "local" -> p("local_url").isNotEmpty() && p("local_model").isNotEmpty()
        else -> false
    }
    private val activeEngine: String
        get() = engineId.let { if (it != "mlkit" && engineReady(it)) it else "mlkit" }

    private val restartRunnable = Runnable { if (listening) { stop(); start() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        terms = parseTerms(prefs.getString("terms", DEFAULT_TERMS) ?: DEFAULT_TERMS)

        val labels = langs.map { it.label }
        fromSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, listOf("🔍 자동 감지") + labels)
            setSelection(0)
        }
        toSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, labels)
            setSelection(1)
        }
        val swap = Button(this).apply {
            text = "⇄"
            textSize = 20f
            setOnClickListener {
                if (auto) {
                    toast("자동 감지 중에는 바꿀 필요가 없어요")
                    return@setOnClickListener
                }
                val f = fromSpinner.selectedItemPosition - 1
                fromSpinner.setSelection(toSpinner.selectedItemPosition + 1)
                toSpinner.setSelection(f)
            }
        }
        val onChange = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (to.mlkit != TranslateLanguage.KOREAN) foreign = to
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
        scroll = ScrollView(this).apply { addView(segments) }
        showHint()

        interimView = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.GRAY)
            setTypeface(null, Typeface.ITALIC)
        }
        statusView = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.GRAY)
            text = "번역 엔진: " + engineLabel(activeEngine)
        }

        chatInput = EditText(this).apply {
            setHint("입력하면 한국어↔외국어로 번역해요")
            textSize = 16f
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, event ->
                val enter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                if (actionId == EditorInfo.IME_ACTION_SEND || enter) { sendChat(); true } else false
            }
        }
        val sendButton = Button(this).apply {
            text = "번역"
            setOnClickListener { sendChat() }
        }
        val chatRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(chatInput, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(sendButton, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
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

        val interpPage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(top, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(interimView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(statusView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(chatRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(bottom, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }

        val langInfos = langs.map { LangInfo(it.label, it.mlkit, it.speechTag) }
        vocabPage = VocabView(this, prefs, langInfos) { text, voice -> speakNow(text, voice, null, true) }
        glossaryPage = GlossaryView(this, prefs, langInfos, DEFAULT_TERMS) { text ->
            terms = parseTerms(text)
            if (listening) { stop(); start() }
        }
        pages = listOf(interpPage, vocabPage, glossaryPage)
        val container = FrameLayout(this).apply {
            pages.forEach { addView(it, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)) }
        }

        tabViews = listOf("통역", "단어장", "용어집").mapIndexed { i, name ->
            TextView(this).apply {
                text = name
                textSize = 17f
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, dp(10))
                setOnClickListener { showTab(i) }
            }
        }
        val cameraButton = Button(this).apply {
            text = "📷"
            textSize = 18f
            setOnClickListener { openCamera() }
        }
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tabViews.forEach { addView(it, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)) }
            addView(cameraButton, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }

        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            addView(tabBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(container, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
        showTab(0)

        fromSpinner.onItemSelectedListener = onChange
        toSpinner.onItemSelectedListener = onChange

        tts = TextToSpeech(this) { }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = runOnUiThread { highlight(utteranceId, -1, -1) }
            override fun onDone(utteranceId: String?) = runOnUiThread { speechEnded(utteranceId) }
            override fun onError(utteranceId: String?) = runOnUiThread { speechEnded(utteranceId) }
            override fun onStop(utteranceId: String?, interrupted: Boolean) = runOnUiThread { speechEnded(utteranceId) }
            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) =
                runOnUiThread { highlight(utteranceId, start, end) }
        })
    }

    private fun showTab(i: Int) {
        pages.forEachIndexed { k, v -> v.visibility = if (k == i) View.VISIBLE else View.GONE }
        tabViews.forEachIndexed { k, t ->
            t.setTextColor(if (k == i) Color.rgb(47, 91, 234) else Color.GRAY)
            t.setTypeface(null, if (k == i) Typeface.BOLD else Typeface.NORMAL)
        }
        if (i == 1) vocabPage.refresh()
        if (i == 2) glossaryPage.refresh()
    }

    private fun showHint() {
        val h = TextView(this).apply {
            text = "시작을 누르고 말하세요. 🔍 자동 감지는 한국어와 오른쪽 언어를 알아서 구분해요.\n" +
                   "☆ 문장 저장 · 🔊 다시 듣기 · 글자를 길게 눌러 '단어 저장'"
            textSize = 16f
            setTextColor(Color.GRAY)
        }
        hint = h
        segments.addView(h)
    }

    private fun openCamera() {
        if (listening) stop()
        startActivity(Intent(this, CameraActivity::class.java))
    }

    /* ---------- Silent mode ---------- */

    private fun adjust(stream: Int, mute: Boolean) {
        try {
            audio.adjustStreamVolume(stream, if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)
        } catch (_: Exception) {}
    }

    // Silences the recognizer's start and stop beeps while listening.
    private fun setQuiet(on: Boolean) {
        val want = on && silent
        if (want == quiet) return
        quiet = want
        adjust(AudioManager.STREAM_NOTIFICATION, want)
        adjust(AudioManager.STREAM_SYSTEM, want)
        setMusicMuted(want && tts?.isSpeaking != true)
    }

    private fun setMusicMuted(mute: Boolean) {
        if (mute == musicMuted) return
        musicMuted = mute
        adjust(AudioManager.STREAM_MUSIC, mute)
    }

    /* ---------- Highlight while speaking ---------- */

    private fun highlight(id: String?, start: Int, end: Int) {
        val seg = utter[id ?: return] ?: return
        val text = seg.tr
        if (text.isEmpty()) return
        val s = SpannableString(text)
        s.setSpan(BackgroundColorSpan(Color.argb(55, 255, 200, 0)), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (start >= 0 && end > start && end <= text.length) {
            s.setSpan(BackgroundColorSpan(Color.argb(190, 255, 200, 0)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        seg.view.text = s
        if (start < 0) scroll.post { scroll.smoothScrollTo(0, seg.view.top) }
    }

    private fun speechEnded(id: String?) {
        if (id != null) utter.remove(id)?.let { it.view.text = it.tr }
        if (quiet && tts?.isSpeaking != true) setMusicMuted(true)
    }

    private fun speakNow(text: String, voice: String, seg: Seg?, flush: Boolean) {
        val engine = tts ?: return
        setMusicMuted(false)
        val uid = "u${++utterCount}"
        if (seg != null) utter[uid] = seg
        engine.language = Locale.forLanguageTag(voice)
        engine.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, uid)
    }

    /* ---------- Translators ---------- */

    private fun withTranslator(sl: Lang, tl: Lang, ready: (Translator) -> Unit) {
        val key = sl.mlkit + ">" + tl.mlkit
        val t = translators.getOrPut(key) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(sl.mlkit)
                    .setTargetLanguage(tl.mlkit)
                    .build()
            )
        }
        t.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener { if (translators[key] === t) ready(t) }
            .addOnFailureListener { status("언어팩을 받지 못했어요. 인터넷 연결을 확인하세요.") }
    }

    /* ---------- Language detection ---------- */

    private fun byCode(code: String) = langs.first { it.mlkit == code }
    private fun langFromTag(tag: String?): Lang? = tag?.let { t -> langs.firstOrNull { t.startsWith(it.mlkit) } }
    private fun fallbackLatin() = if (foreign.mlkit in latinCodes) foreign else byCode(TranslateLanguage.ENGLISH)

    // Works out which language a piece of text is in.
    private fun detect(text: String, useHint: Boolean = true, done: (Lang) -> Unit) {
        val letters = text.filter { it.isLetter() }
        val hangul = letters.count { it in '\uAC00'..'\uD7A3' }
        val kana = letters.count { it in '\u3040'..'\u30FF' }
        val han = letters.count { it in '\u4E00'..'\u9FFF' }
        val prefersJapanese = listOf(foreign, from, to).any { it.mlkit == TranslateLanguage.JAPANESE }
        when {
            hangul > 0 && hangul * 2 >= letters.length -> done(korean)
            kana > 0 -> done(byCode(TranslateLanguage.JAPANESE))
            han > 0 -> done(byCode(if (prefersJapanese) TranslateLanguage.JAPANESE else TranslateLanguage.CHINESE))
            else -> {
                val hinted = if (useHint) langFromTag(detectedTag)?.takeIf { it.mlkit in latinCodes } else null
                if (hinted != null) {
                    done(hinted)
                    return
                }
                langId.identifyLanguage(text)
                    .addOnSuccessListener { code ->
                        done(langs.firstOrNull { it.mlkit == code && it.mlkit in latinCodes } ?: fallbackLatin())
                    }
                    .addOnFailureListener { done(fallbackLatin()) }
            }
        }
    }

    private fun autoStatus() =
        status("듣는 중 · 자동 감지 (한국어 ↔ ${foreign.label}) · ${engineLabel(activeEngine)}")

    private fun autoTranslate(raw: String) {
        detect(raw) { lang ->
            heard = lang
            val sl: Lang
            val tl: Lang
            if (lang.mlkit == TranslateLanguage.KOREAN) {
                sl = korean
                tl = foreign
            } else {
                foreign = lang
                sl = lang
                tl = korean
            }
            if (listening) autoStatus()
            withTranslator(sl, tl) { t -> translate(raw, sl, tl, t) }
        }
    }

    private fun dispatch(text: String) {
        if (auto) autoTranslate(text) else translate(text)
    }

    /* ---------- Typed chat ---------- */

    // Typed Korean goes to the foreign language; typed foreign text goes to Korean.
    private fun sendChat() {
        val text = chatInput.text.toString().trim()
        if (text.isEmpty()) return
        val target = when {
            auto -> foreign
            to.mlkit != TranslateLanguage.KOREAN -> to
            else -> from
        }
        chatInput.setText("")
        detect(text, useHint = false) { lang ->
            val sl: Lang
            val tl: Lang
            if (lang.mlkit == TranslateLanguage.KOREAN) {
                if (target.mlkit == TranslateLanguage.KOREAN) {
                    status("위쪽에서 번역할 외국어를 고르세요.")
                    return@detect
                }
                sl = korean
                tl = target
            } else {
                if (auto) foreign = lang
                sl = lang
                tl = korean
            }
            withTranslator(sl, tl) { t -> translate(text, sl, tl, t, typed = true) }
        }
    }

    /* ---------- Settings ---------- */

    private fun openSettings() {
        val items = arrayOf(
            "📷 카메라 번역", "화면 지우기", "화면 내용 내보내기",
            "번역 엔진 (지금: ${engineLabel(activeEngine)})",
            "무음 모드 (지금: ${if (silent) "켜짐" else "꺼짐"})"
        )
        AlertDialog.Builder(this)
            .setTitle("설정")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> openCamera()
                    1 -> clearScreen()
                    2 -> exportScreen()
                    3 -> openEngines()
                    else -> toggleSilent()
                }
            }
            .show()
    }

    private fun toggleSilent() {
        val next = !silent
        prefs.edit().putBoolean("silent", next).apply()
        if (listening) {
            setQuiet(false)
            setQuiet(true)
        }
        toast(if (next) "무음 모드를 켰어요" else "무음 모드를 껐어요")
    }

    private fun clearScreen() {
        tts?.stop()
        segments.removeAllViews()
        screenLog.clear()
        utter.clear()
        speechBuf.clear()
        nextToSpeak = seq + 1
        recent.clear()
        showHint()
    }

    private fun exportScreen() {
        val lines = screenLog.filter { it.tr.isNotEmpty() }
        if (lines.isEmpty()) { toast("화면에 번역된 내용이 없어요."); return }
        share(lines.joinToString("\n\n") { (if (it.typed) "✍ " else "") + it.src + "\n→ " + it.tr })
    }

    /* ---------- Translation engines ---------- */

    private fun openEngines() {
        val current = engines.indexOfFirst { it.id == engineId }.coerceAtLeast(0)
        val labels = engines.map { e ->
            e.label + if (e.id != "mlkit" && !engineReady(e.id)) "  (설정 필요)" else ""
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("번역 엔진")
            .setSingleChoiceItems(labels, current) { d, which ->
                d.dismiss()
                val e = engines[which]
                if (e.id == "mlkit") useEngine("mlkit") else openEngineConfig(e)
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun useEngine(id: String) {
        prefs.edit().putString("engine", id).apply()
        remoteBlockedUntil = 0L
        toast(engineLabel(activeEngine) + " 사용")
        if (listening) { stop(); start() } else status("번역 엔진: " + engineLabel(activeEngine))
    }

    private fun fieldsFor(id: String): List<Field> = when (id) {
        "deepl" -> listOf(Field("deepl", "API 키", "xxxxxxxx-…:fx"))
        "claude" -> listOf(
            Field("claude_key", "API 키", "sk-ant-…"),
            Field("claude_model", "모델", DEFAULT_CLAUDE_MODEL, DEFAULT_CLAUDE_MODEL)
        )
        "openai" -> listOf(
            Field("openai_url", "API 주소", "https://api.openai.com/v1", "https://api.openai.com/v1"),
            Field("openai_key", "API 키", "sk-…"),
            Field("openai_model", "모델", "사용할 모델 이름")
        )
        else -> listOf(
            Field("local_url", "맥북 주소", "http://192.168.0.10:11434/v1"),
            Field("local_key", "API 키 (없으면 비워두기)", ""),
            Field("local_model", "모델", "ollama list에 나오는 이름")
        )
    }

    private fun engineHelp(id: String): String = when (id) {
        "deepl" -> "deepl.com/pro-api에서 받은 키를 넣으세요. 무료 키는 :fx로 끝나요."
        "claude" -> "console.anthropic.com에서 받은 API 키를 넣으세요. 사용한 만큼 요금이 나가요."
        "openai" -> "OpenAI나 같은 형식을 쓰는 서비스의 주소, 키, 모델 이름을 넣으세요."
        else -> "맥북에서 Ollama나 LM Studio 서버를 켜고, 폰과 같은 와이파이에서 맥 주소를 넣으세요."
    }

    private fun openEngineConfig(e: Engine) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val edits = fieldsFor(e.id).map { f ->
            box.addView(TextView(this).apply {
                text = f.label
                textSize = 13f
                setTextColor(Color.GRAY)
                setPadding(0, dp(8), 0, 0)
            })
            val ed = EditText(this).apply {
                setText(prefs.getString(f.key, null) ?: f.default)
                setHint(f.hint)
                textSize = 15f
                setSingleLine(true)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            }
            box.addView(ed)
            f to ed
        }
        fun store() {
            val editor = prefs.edit()
            edits.forEach { (f, ed) -> editor.putString(f.key, ed.text.toString().trim()) }
            editor.apply()
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(e.label)
            .setMessage(engineHelp(e.id))
            .setView(box)
            .setPositiveButton("저장하고 사용") { _, _ -> store(); useEngine(e.id) }
            .setNeutralButton("연결 테스트", null)
            .setNegativeButton("취소", null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            store()
            dialog.setMessage("테스트 중… (로컬 LLM은 처음에 오래 걸릴 수 있어요)")
            net.execute {
                val r: Any = try {
                    remote(e.id, "안녕하세요. 만나서 반갑습니다.", korean, langs[1], "")
                } catch (ex: Exception) { ex }
                runOnUiThread {
                    dialog.setMessage(
                        if (r is String) "성공 ✔\n일본어 번역: $r"
                        else "실패: " + describe(r as Exception)
                    )
                }
            }
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is HttpException -> "HTTP ${e.code}\n${e.body}"
        is java.net.SocketTimeoutException -> "응답 시간 초과. 모델이 너무 크거나 주소가 틀렸을 수 있어요."
        is java.net.ConnectException, is java.net.UnknownHostException, is java.net.NoRouteToHostException ->
            "연결 실패. 주소, 같은 와이파이인지, 맥의 서버와 방화벽 설정을 확인하세요."
        else -> e.toString()
    }

    // Runs on a background thread.
    private fun remote(id: String, text: String, sl: Lang, tl: Lang, context: String): String = when (id) {
        "deepl" -> deepl(text, sl.mlkit, tl.mlkit, p("deepl"), context)
        "claude" -> claude(text, sl, tl, context)
        "openai" -> chat(p("openai_url"), p("openai_key"), p("openai_model"), text, sl, tl, context, 20000)
        "local" -> chat(p("local_url"), p("local_key"), p("local_model"), text, sl, tl, context, 45000)
        else -> throw IllegalStateException("no engine")
    }

    private fun systemPrompt(sl: Lang, tl: Lang) =
        "You are a professional simultaneous interpreter. Translate the user's text from ${sl.english} " +
        "to ${tl.english}. The text may be an unfinished fragment of ongoing speech; translate it as it is " +
        "without completing or guessing the rest. Lines marked as context are earlier conversation, given " +
        "only so you understand the flow; never translate or repeat them. Keep placeholder codes such as " +
        "XZ1 or XZ2 exactly as written. Use natural, polite spoken ${tl.english}. Reply with the translation " +
        "only, with no quotes, notes, romanization or explanations."

    private fun userPrompt(text: String, context: String) =
        if (context.isBlank()) text
        else "Context (do not translate):\n$context\n\nTranslate:\n$text"

    private fun clean(s: String) =
        s.replace(Regex("(?s)<think>.*?</think>"), "").trim().trim('"', '“', '”').trim()

    private fun postJson(url: String, headers: Map<String, String>, body: JSONObject, readTimeout: Int): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 4000
        conn.readTimeout = readTimeout
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                throw HttpException(code, err.take(300))
            }
            return JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    private fun deeplCode(lang: String, asTarget: Boolean): String =
        if (lang == TranslateLanguage.ENGLISH && asTarget) "EN-US" else lang.uppercase()

    private fun deepl(text: String, source: String, target: String, key: String, context: String): String {
        val host = if (key.endsWith(":fx")) "api-free.deepl.com" else "api.deepl.com"
        val body = JSONObject()
            .put("text", JSONArray().put(text))
            .put("source_lang", deeplCode(source, false))
            .put("target_lang", deeplCode(target, true))
            .put("formality", "prefer_more")
        if (context.isNotBlank()) body.put("context", context)
        val json = postJson("https://$host/v2/translate", mapOf("Authorization" to "DeepL-Auth-Key $key"), body, 6000)
        return json.getJSONArray("translations").getJSONObject(0).getString("text")
    }

    private fun claude(text: String, sl: Lang, tl: Lang, context: String): String {
        val body = JSONObject()
            .put("model", p("claude_model").ifEmpty { DEFAULT_CLAUDE_MODEL })
            .put("max_tokens", 1024)
            .put("system", systemPrompt(sl, tl))
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", userPrompt(text, context))))
        val json = postJson(
            "https://api.anthropic.com/v1/messages",
            mapOf("x-api-key" to p("claude_key"), "anthropic-version" to "2023-06-01"),
            body, 20000
        )
        val content = json.getJSONArray("content")
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val block = content.getJSONObject(i)
            if (block.optString("type") == "text") sb.append(block.optString("text"))
        }
        return clean(sb.toString())
    }

    // OpenAI-style chat completions: OpenAI, compatible services, Ollama and LM Studio.
    private fun chat(
        base0: String, key: String, model: String,
        text: String, sl: Lang, tl: Lang, context: String, readTimeout: Int
    ): String {
        var base = base0.trim().trimEnd('/')
        if (!base.startsWith("http://") && !base.startsWith("https://")) base = "http://$base"
        if (!base.contains("/v1")) base += "/v1"
        val body = JSONObject()
            .put("model", model)
            .put("stream", false)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", systemPrompt(sl, tl)))
                .put(JSONObject().put("role", "user").put("content", userPrompt(text, context))))
        val headers = if (key.isNotEmpty()) mapOf("Authorization" to "Bearer $key") else emptyMap()
        val json = postJson("$base/chat/completions", headers, body, readTimeout)
        val msg = json.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        return clean(msg.optString("content"))
    }

    private fun remoteFailed(id: String, e: Exception?) {
        val name = engineLabel(id)
        val code = (e as? HttpException)?.code
        val (wait, msg) = when (code) {
            401, 403 -> Long.MAX_VALUE to "$name 키가 올바르지 않아요. 설정에서 확인하세요. 지금은 기기 번역을 써요."
            456 -> Long.MAX_VALUE to "이번 달 DeepL 무료 한도를 다 썼어요. 기기 번역으로 전환했어요."
            400, 404 -> Long.MAX_VALUE to "$name 설정(주소나 모델 이름)을 확인하세요. 지금은 기기 번역을 써요."
            429 -> 10_000L to "$name 요청이 많아 잠시 기기 번역을 써요."
            else -> 30_000L to "$name 에 연결하지 못해 잠시 기기 번역을 써요."
        }
        remoteBlockedUntil = if (wait == Long.MAX_VALUE) Long.MAX_VALUE else System.currentTimeMillis() + wait
        status(msg)
    }

    /* ---------- Saving to the vocabulary list ---------- */

    // Returns the foreign text, the Korean text and the foreign language of a translated line.
    private fun sides(seg: Seg): Triple<String, String, Lang> =
        if (seg.tl.mlkit == TranslateLanguage.KOREAN) Triple(seg.src, seg.tr, seg.sl)
        else Triple(seg.tr, seg.src, seg.tl)

    private fun saveSentence(seg: Seg) {
        val (foreignText, koreanText, lang) = sides(seg)
        storeVocab(foreignText, koreanText, "", "", lang)
    }

    // Saves a selected word with its meaning, using the line it came from as the example.
    private fun saveWord(word: String, fromTranslation: Boolean, seg: Seg) {
        val (exampleForeign, exampleKorean, foreignLang) = sides(seg)
        val wordLang = if (fromTranslation) seg.tl else seg.sl
        if (wordLang.mlkit == TranslateLanguage.KOREAN) {
            withTranslator(korean, foreignLang) { t ->
                t.translate(word)
                    .addOnSuccessListener { fw -> storeVocab(fw, word, exampleForeign, exampleKorean, foreignLang) }
                    .addOnFailureListener { toast("단어 번역에 실패했어요.") }
            }
        } else {
            withTranslator(wordLang, korean) { t ->
                t.translate(word)
                    .addOnSuccessListener { m -> storeVocab(word, m, exampleForeign, exampleKorean, wordLang) }
                    .addOnFailureListener { storeVocab(word, "", exampleForeign, exampleKorean, wordLang) }
            }
        }
    }

    private fun storeVocab(text: String, meaning: String, example: String, exampleMeaning: String, lang: Lang) {
        val arr = try { JSONArray(prefs.getString("saved", "[]")) } catch (_: Exception) { JSONArray() }
        val sameAsExample = example == text
        arr.put(
            JSONObject()
                .put("t", text)
                .put("m", meaning)
                .put("e", if (sameAsExample) "" else example)
                .put("em", if (sameAsExample) "" else exampleMeaning)
                .put("v", lang.speechTag)
                .put("l", lang.mlkit)
                .put("d", System.currentTimeMillis())
                .put("c", 0)
                .put("w", 0)
        )
        prefs.edit().putString("saved", arr.toString()).apply()
        toast("단어장에 저장: $text" + (if (meaning.isNotEmpty()) " = $meaning" else ""))
    }

    // Adds "단어 저장" next to copy when part of a line is selected.
    private fun enableWordSave(tv: TextView, seg: Seg, fromTranslation: Boolean) {
        tv.customSelectionActionModeCallback = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.add(0, MENU_SAVE_WORD, 0, "단어 저장")
                return true
            }
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                if (item.itemId != MENU_SAVE_WORD) return false
                val a = minOf(tv.selectionStart, tv.selectionEnd).coerceAtLeast(0)
                val b = maxOf(tv.selectionStart, tv.selectionEnd).coerceAtMost(tv.text.length)
                val word = if (b > a) tv.text.substring(a, b).trim() else ""
                if (word.isNotEmpty() && seg.tr.isNotEmpty()) saveWord(word, fromTranslation, seg)
                mode.finish()
                return true
            }
            override fun onDestroyActionMode(mode: ActionMode) {}
        }
    }

    private fun share(text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, "내보내기"))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /* ---------- Glossary matching ---------- */

    private fun parseTerms(text: String): List<Term> = text.lines().mapNotNull { line ->
        val parts = line.replace("｜", "|").split("|").map { it.trim() }
        val key = parts.getOrNull(0).orEmpty()
        if (key.isEmpty() || key.startsWith("#")) return@mapNotNull null
        val variants = parts.getOrNull(1).orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val names = HashMap<String, String>()
        names[TranslateLanguage.KOREAN] = key
        parts.getOrNull(2).orEmpty().replace("，", ",").split(",").forEach { pair ->
            val kv = pair.replace("＝", "=").split("=", limit = 2)
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

    // Finds placeholder code XZ<n> in translated text, including full-width forms.
    private fun codeRegex(n: Int): Regex {
        val digits = n.toString().map { d -> "[" + d + (d - '0' + 0xFF10).toChar() + "]" }.joinToString("\\s*")
        return Regex("[XxＸｘ]\\s*[ZzＺｚ]\\s*$digits(?![0-9０-９])")
    }

    private fun prepare(src: String, sl: Lang, tl: Lang): Prepared {
        var shown = src
        if (sl.mlkit == TranslateLanguage.KOREAN) {
            for (t in terms) {
                val key = t.names[TranslateLanguage.KOREAN] ?: continue
                for (w in t.variants + key) shown = flex(w).replace(shown) { key }
            }
        }
        var input = shown
        val codes = LinkedHashMap<Regex, String>()
        terms.forEachIndexed { i, t ->
            val a = t.names[sl.mlkit] ?: return@forEachIndexed
            val pattern = if (sl.mlkit == TranslateLanguage.KOREAN) flex(a)
                          else Regex(Regex.escape(a), RegexOption.IGNORE_CASE)
            if (!pattern.containsMatchIn(input)) return@forEachIndexed
            val n = i + 1
            input = pattern.replace(input) { "XZ$n" }
            codes[codeRegex(n)] = t.names[tl.mlkit] ?: t.names[TranslateLanguage.ENGLISH] ?: a
        }
        return Prepared(shown, input, codes)
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
    private fun repairLanguagePack(src: String, tgt: String) {
        if (!repaired.add("$src>$tgt")) return
        val wasListening = listening
        stop()
        translator?.close()
        translator = null
        translators.values.forEach { it.close() }
        translators.clear()
        status("번역 결과가 이상해서 언어팩을 다시 받는 중이에요…")
        val mm = RemoteModelManager.getInstance()
        val tasks = listOf(src, tgt)
            .filter { it != TranslateLanguage.ENGLISH }
            .map { mm.deleteDownloadedModel(TranslateRemoteModel.Builder(it).build()) }
        Tasks.whenAllComplete(tasks).addOnCompleteListener {
            if (wasListening) start() else status("언어팩을 정리했어요. 다시 입력하면 새로 받아서 번역해요.")
        }
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
        if (auto) {
            if (to.mlkit != TranslateLanguage.KOREAN) foreign = to
            listening = true
            micButton.text = "정지"
            recent.clear()
            detectedTag = null
            setQuiet(true)
            withTranslator(korean, foreign) { }
            withTranslator(foreign, korean) { }
            autoStatus()
            startRecognizer()
            return
        }
        if (from == to) {
            status("서로 다른 두 언어를 고르세요.")
            return
        }
        listening = true
        micButton.text = "정지"
        recent.clear()
        setQuiet(true)

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
                    status("듣는 중 · ${from.label} → ${to.label} · ${engineLabel(activeEngine)}")
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
        setQuiet(false)
        interimView.text = ""
        micButton.text = "시작"
        status("번역 엔진: " + engineLabel(activeEngine))
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(rc, perms, res)
        if (res.firstOrNull() == PackageManager.PERMISSION_GRANTED) start()
        else status("마이크 권한을 허용해야 통역할 수 있어요.")
    }

    override fun onStop() {
        if (listening) stop()
        super.onStop()
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
                b?.getString(SpeechRecognizer.DETECTED_LANGUAGE)?.let { detectedTag = it }
                handle(firstResult(b), true)
                restartRecognizer()
            }
            override fun onLanguageDetection(results: Bundle) {
                results.getString(SpeechRecognizer.DETECTED_LANGUAGE)?.let { detectedTag = it }
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
        val primary = from.speechTag
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, primary)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            if (auto) {
                val others = langs.map { it.speechTag }.filter { it != primary }
                putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", others.toTypedArray())
                if (Build.VERSION.SDK_INT >= 34) {
                    val all = ArrayList(langs.map { it.speechTag })
                    putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                    putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, all)
                    putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED)
                    putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, all)
                }
            }
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
            if (rest.isNotBlank()) dispatch(rest.trim())
            committed = 0
            interimView.text = ""
            return
        }
        val size = if (noSpaces) rest.length / 2.5
                   else rest.trim().split(Regex("\\s+")).size.toDouble()
        if (size >= 9) {
            val stable = stablePart(rest)
            if (stable.isNotBlank()) {
                dispatch(stable.trim())
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

    private fun translate(
        raw: String,
        sl: Lang = from,
        tl: Lang = to,
        mt: Translator? = translator,
        typed: Boolean = false
    ) {
        val t = mt ?: return
        val id = ++seq
        hint?.let { segments.removeView(it); hint = null }
        val p = prepare(raw, sl, tl)
        val source = sl.mlkit
        val target = tl.mlkit

        val trView = TextView(this).apply {
            text = "…"
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
            setTextIsSelectable(true)
        }
        val seg = Seg(p.shown, tl.speechTag, trView, typed, sl, tl)
        screenLog.add(seg)
        enableWordSave(trView, seg, true)

        val srcView = TextView(this).apply {
            text = (if (typed) "✍ " else "") + (if (auto || typed) "[${sl.label}] " else "") + p.shown
            textSize = 14f
            setTextColor(Color.GRAY)
            setTextIsSelectable(true)
        }
        enableWordSave(srcView, seg, false)
        val playView = TextView(this).apply {
            text = "🔊"
            textSize = 18f
            setPadding(dp(12), dp(4), dp(8), dp(4))
            setOnClickListener { if (seg.tr.isNotEmpty()) speakNow(seg.tr, seg.voice, seg, true) }
        }
        val saveView = TextView(this).apply {
            text = "☆"
            textSize = 20f
            setTextColor(Color.rgb(47, 91, 234))
            setPadding(dp(8), dp(4), dp(4), dp(4))
            setOnClickListener {
                if (seg.tr.isNotEmpty()) {
                    saveSentence(seg)
                    text = "★"
                }
            }
        }
        val srcRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(srcView, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(playView, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(saveView, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
        segments.addView(trView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(14) })
        segments.addView(srcRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }

        val context = recent.joinToString("\n")
        recent.addLast(p.shown)
        while (recent.size > 3) recent.removeFirst()

        val engine = activeEngine
        if (engine == "mlkit" || System.currentTimeMillis() < remoteBlockedUntil) {
            viaMlKit(t, id, seg, p, source, target)
            return
        }
        net.execute {
            val result: Any = try { remote(engine, p.input, sl, tl, context) } catch (e: Exception) { e }
            runOnUiThread {
                if (result is String && result.isNotBlank()) {
                    showResult(id, seg, p.restore(result), target)
                } else {
                    remoteFailed(engine, result as? Exception)
                    viaMlKit(t, id, seg, p, source, target)
                }
            }
        }
    }

    private fun viaMlKit(t: Translator, id: Int, seg: Seg, p: Prepared, source: String, target: String) {
        t.translate(p.input)
            .addOnSuccessListener { raw ->
                val tr = p.restore(raw)
                if (!looksLike(tr, target) && looksLike(p.shown, source)) {
                    seg.view.text = "$tr\n(언어팩 오류 · 다시 받는 중)"
                    queueSpeech(id, null)
                    repairLanguagePack(source, target)
                    return@addOnSuccessListener
                }
                showResult(id, seg, tr, target)
            }
            .addOnFailureListener {
                seg.view.text = "번역 실패"
                queueSpeech(id, null)
            }
    }

    private fun showResult(id: Int, seg: Seg, tr: String, target: String) {
        seg.tr = tr
        seg.view.text = tr
        Pronouncer.attach(this, segments, seg.view, tr, target)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        queueSpeech(id, seg)
    }

    /* ---------- Speech output ---------- */

    // Speak translations in the order they were requested.
    private fun queueSpeech(id: Int, seg: Seg?) {
        if (id < nextToSpeak) return
        speechBuf[id] = seg
        while (speechBuf.containsKey(nextToSpeak)) {
            val s = speechBuf.remove(nextToSpeak)
            if (s != null && speakBox.isChecked) speakNow(s.tr, s.voice, s, false)
            nextToSpeak++
        }
    }

    private fun status(msg: String) { statusView.text = msg }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        listening = false
        recognizer?.destroy()
        setQuiet(false)
        translator?.close()
        translators.values.forEach { it.close() }
        tts?.shutdown()
        net.shutdown()
        super.onDestroy()
    }
}