package com.example.interpreter

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
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
import android.window.OnBackInvokedDispatcher
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
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

    // One translated line on screen.
    private class Seg(
        val src: String, val voice: String, val view: TextView, val typed: Boolean,
        val sl: Lang, val tl: Lang
    ) {
        var tr: String = ""
    }

    companion object {
        const val PRIVACY_URL = "https://karismapong-create.github.io/translator/privacy.html"
        const val MENU_SAVE_WORD = 1001
        private val SAMPLES = mapOf(
            "ko" to "안녕하세요. 만나서 반갑습니다.",
            "ja" to "こんにちは。お会いできて嬉しいです。",
            "en" to "Hello, nice to meet you.",
            "fr" to "Bonjour, ravi de vous rencontrer.",
            "es" to "Hola, encantado de conocerte.",
            "de" to "Hallo, schön, Sie kennenzulernen.",
            "zh" to "你好，很高兴认识你。"
        )
    }

    private val langs = Languages.all
    private val latinCodes = setOf("en", "fr", "es", "de")

    private lateinit var home: Lang
    private lateinit var foreign: Lang
    private lateinit var heard: Lang
    private lateinit var settings: EngineSettings

    private lateinit var fromSpinner: Spinner
    private lateinit var toSpinner: Spinner
    private lateinit var segments: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var interimView: TextView
    private lateinit var statusView: TextView
    private lateinit var speakBox: CheckBox
    private lateinit var micButton: Button
    private lateinit var chatInput: EditText
    private lateinit var convPage: ConversationView
    private lateinit var vocabPage: VocabView
    private lateinit var glossaryPage: GlossaryView
    private lateinit var pages: List<View>
    private lateinit var tabViews: List<TextView>
    private var hint: TextView? = null
    private var currentTab = 0

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
    private var terms: List<Glossary.Term> = emptyList()
    private val repaired = HashSet<String>()
    private val prefs by lazy { getSharedPreferences("interpreter", MODE_PRIVATE) }

    private val net = Executors.newSingleThreadExecutor()
    private var remoteBlockedUntil = 0L
    private val recent = ArrayDeque<String>()

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
    private val noSpaces: Boolean get() = from.code == "ja" || from.code == "zh"

    private fun p(key: String) = prefs.getString(key, "").orEmpty().trim()
    private val activeEngine: String get() = settings.active()
    private fun engineLabel(id: String) = settings.label(id)

    private val restartRunnable = Runnable { if (listening) { stop(); start() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Providers.migrate(prefs)
        OnDevice.refresh()
        Pro.init(this)
        // Daily world news for the vocabulary tab is fetched in the background once a day.
        try { NewsStore.schedule(this) } catch (_: Exception) {}
        home = Languages.home(prefs)
        I18n.use(home.code)
        foreign = langs.first { it.code != home.code }
        heard = home
        settings = EngineSettings(this, prefs, net, { id -> useEngine(id) }, { id -> testEngine(id) })

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        terms = Glossary.parse(Glossary.text(prefs, home.code), home.code)

        val labels = langs.map { it.native }
        fromSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, listOf(I18n.t("auto")) + labels)
            setSelection(0)
        }
        toSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, labels)
            setSelection(langs.indexOf(foreign))
        }
        val swap = Button(this).apply {
            text = "⇄"
            textSize = 20f
            setOnClickListener {
                if (auto) {
                    toast(I18n.t("no_swap"))
                    return@setOnClickListener
                }
                val f = fromSpinner.selectedItemPosition - 1
                fromSpinner.setSelection(toSpinner.selectedItemPosition + 1)
                toSpinner.setSelection(f)
            }
        }
        val onChange = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (to.code != home.code) foreign = to
                scroll.removeCallbacks(restartRunnable)
                scroll.postDelayed(restartRunnable, 300)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.card(context)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(fromSpinner, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(swap, LinearLayout.LayoutParams(dp(56), dp(56)))
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
            text = I18n.f("engine_status", engineLabel(activeEngine))
        }

        chatInput = EditText(this).apply {
            setHint(I18n.t("chat_hint"))
            textSize = 16f
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, event ->
                val enter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                if (actionId == EditorInfo.IME_ACTION_SEND || enter) { sendChat(); true } else false
            }
        }
        val sendButton = Button(this).apply {
            text = I18n.t("translate")
            setOnClickListener { sendChat() }
        }
        val chatRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(chatInput, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(sendButton, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }

        speakBox = CheckBox(this).apply { text = I18n.t("speak_aloud") }
        val settingsButton = Button(this).apply {
            text = I18n.t("settings")
            setOnClickListener { openSettings() }
        }
        micButton = Button(this).apply {
            text = I18n.t("start")
            textSize = 17f
            setOnClickListener { if (listening) stop() else start() }
        }
        Ui.primary(micButton)
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(speakBox, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(settingsButton, LinearLayout.LayoutParams(WRAP_CONTENT, dp(60)))
            addView(micButton, LinearLayout.LayoutParams(dp(132), dp(64)))
        }

        val interpPage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(top, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })
            addView(Button(context).apply {
                text = LiveText.t("open")
                setOnClickListener {
                    if (listening) stop()
                    startActivity(Intent(this@MainActivity, LiveTalkActivity::class.java))
                }
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
            addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = dp(4) })
            addView(interimView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(statusView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(chatRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(bottom, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }

        val langInfos = langs.map { LangInfo(it.native, it.code, it.speechTag) }
        convPage = ConversationView(
            this, prefs, langInfos,
            { text, voice -> speakNow(text, voice, null, true) },
            { SpeechOut.stop(tts) },
            { text, voice -> speakNow(text, voice, null, false) }
        )
        vocabPage = VocabView(this, prefs, langInfos) { text, voice -> speakNow(text, voice, null, true) }
        glossaryPage = GlossaryView(this, prefs, langInfos) { text ->
            terms = Glossary.parse(text, home.code)
            if (listening) { stop(); start() }
        }
        pages = listOf(interpPage, convPage, vocabPage, glossaryPage)
        val container = FrameLayout(this).apply {
            pages.forEach { addView(it, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)) }
        }

        tabViews = listOf("tab_interp", "tab_talk", "tab_vocab", "tab_gloss").mapIndexed { i, key ->
            TextView(this).apply {
                text = I18n.t(key)
                textSize = 15f
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(dp(4), dp(10), dp(4), dp(10))
                setOnClickListener { showTab(i) }
            }
        }
        val cameraButton = Button(this).apply {
            text = "📷"
            textSize = 18f
            setPadding(0, 0, 0, 0)
            setOnClickListener { openCamera() }
        }
        // Segmented tabs: a rounded track with the current tab as a soft blue pill.
        val tabTrack = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.card(context)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            tabViews.forEach { addView(it, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)) }
        }
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(tabTrack, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(cameraButton, LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginStart = dp(6) })
        }

        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(tabBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(container, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
        Ui.edgeToEdge(this, root)
        Ui.watch(root)
        showTab(0)
        setupBack()

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

        if (!Languages.hasChosen(prefs)) chooseCountry(firstRun = true)
    }

    /* ---------- Country and language ---------- */

    private fun chooseCountry(firstRun: Boolean) {
        val items = Languages.countries.map { "${it.flag}  ${it.name}" }.toTypedArray()
        val builder = AlertDialog.Builder(this)
            .setTitle("🌐 나라 · 国 · Country · Pays · País · Land · 国家")
            .setItems(items) { _, w ->
                if (listening) stop()
                Languages.setHome(prefs, Languages.countries[w])
                recreate()
            }
        if (firstRun) builder.setCancelable(false) else builder.setNegativeButton("✕", null)
        builder.show().also { Ui.watch(it) }
    }

    /* ---------- Phone back button ---------- */

    // Steps back inside the current tab, then to the interpret tab, then leaves the app.
    private fun handleBack(): Boolean {
        val handled = when (currentTab) {
            1 -> convPage.back()
            2 -> vocabPage.back()
            else -> false
        }
        if (handled) return true
        if (currentTab != 0) {
            showTab(0)
            return true
        }
        return false
    }

    private fun setupBack() {
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                if (!handleBack()) finish()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!handleBack()) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    private fun showTab(i: Int) {
        currentTab = i
        pages.forEachIndexed { k, v -> v.visibility = if (k == i) View.VISIBLE else View.GONE }
        tabViews.forEachIndexed { k, t ->
            t.setTextColor(if (k == i) Ui.PRIMARY_DARK else Ui.INK_SOFT)
            t.typeface = Typeface.create(if (k == i) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
            t.background = if (k == i) Ui.rounded(Ui.PRIMARY_SOFT, 14, this) else null
        }
        if (i != 1) convPage.stopListening()
        if (i == 1) {
            if (listening) stop()
            convPage.onShown()
        }
        if (i == 2) vocabPage.refresh()
        if (i == 3) glossaryPage.refresh()
    }

    private fun showHint() {
        val h = TextView(this).apply {
            text = I18n.t("hint")
            textSize = 16f
            setTextColor(Color.GRAY)
        }
        hint = h
        segments.addView(h)
    }

    private fun openCamera() {
        if (listening) stop()
        convPage.stopListening()
        startActivity(Intent(this, CameraActivity::class.java))
    }

    private fun openMicTest() {
        if (listening) stop()
        convPage.stopListening()
        startActivity(Intent(this, MicTestActivity::class.java))
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
        SpeechOut.speak(engine, text, voice, flush, uid)
    }

    /* ---------- Translators ---------- */

    private fun withTranslator(sl: Lang, tl: Lang, ready: (Translator) -> Unit) {
        val key = sl.code + ">" + tl.code
        val t = translators.getOrPut(key) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(sl.code)
                    .setTargetLanguage(tl.code)
                    .build()
            )
        }
        t.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener { if (translators[key] === t) ready(t) }
            .addOnFailureListener { status(I18n.t("pack_failed")) }
    }

    /* ---------- Language detection ---------- */

    private fun fallbackLatin(): Lang = when {
        foreign.code in latinCodes -> foreign
        home.code in latinCodes -> home
        else -> Languages.byCode("en")
    }

    // Works out which language a piece of text is in.
    private fun detect(text: String, useHint: Boolean = true, done: (Lang) -> Unit) {
        val letters = text.filter { it.isLetter() }
        val hangul = letters.count { it in '\uAC00'..'\uD7A3' }
        val kana = letters.count { it in '\u3040'..'\u30FF' }
        val han = letters.count { it in '\u4E00'..'\u9FFF' }
        val prefersJapanese = listOf(foreign, from, to, home).any { it.code == "ja" }
        when {
            hangul > 0 && hangul * 2 >= letters.length -> done(Languages.byCode("ko"))
            kana > 0 -> done(Languages.byCode("ja"))
            han > 0 -> done(Languages.byCode(if (prefersJapanese) "ja" else "zh"))
            else -> {
                val hinted = if (useHint) {
                    detectedTag?.let { tag -> langs.firstOrNull { tag.startsWith(it.code) } }?.takeIf { it.code in latinCodes }
                } else null
                if (hinted != null) {
                    done(hinted)
                    return
                }
                langId.identifyLanguage(text)
                    .addOnSuccessListener { code ->
                        done(langs.firstOrNull { it.code == code && it.code in latinCodes } ?: fallbackLatin())
                    }
                    .addOnFailureListener { done(fallbackLatin()) }
            }
        }
    }

    private fun autoStatus() =
        status(I18n.f("auto_status", home.native, foreign.native, engineLabel(activeEngine)))

    private fun autoTranslate(raw: String) {
        detect(raw) { lang ->
            heard = lang
            val sl: Lang
            val tl: Lang
            if (lang.code == home.code) {
                sl = home
                tl = foreign
            } else {
                foreign = lang
                sl = lang
                tl = home
            }
            if (listening) autoStatus()
            withTranslator(sl, tl) { t -> translate(raw, sl, tl, t) }
        }
    }

    private fun dispatch(text: String) {
        if (auto) autoTranslate(text) else translate(text)
    }

    /* ---------- Typed chat ---------- */

    // Typed text in my language goes to the foreign language; anything else goes to my language.
    private fun sendChat() {
        val text = chatInput.text.toString().trim()
        if (text.isEmpty()) return
        val target = when {
            auto -> foreign
            to.code != home.code -> to
            else -> from
        }
        chatInput.setText("")
        detect(text, useHint = false) { lang ->
            val sl: Lang
            val tl: Lang
            if (lang.code == home.code) {
                if (target.code == home.code) {
                    status(I18n.t("pick_foreign"))
                    return@detect
                }
                sl = home
                tl = target
            } else {
                if (auto) foreign = lang
                sl = lang
                tl = home
            }
            withTranslator(sl, tl) { t -> translate(text, sl, tl, t, typed = true) }
        }
    }

    /* ---------- Settings ---------- */

    private fun openSettings() {
        val items = arrayOf(
            I18n.f("m_country", Languages.countryLabel(prefs)),
            I18n.t("m_camera"),
            I18n.t("m_mic"),
            I18n.t("m_clear"),
            I18n.t("m_export"),
            I18n.f("m_engine", engineLabel(activeEngine)),
            I18n.f("m_silent", I18n.t(if (silent) "on" else "off")),
            pronLabel() + ": " + I18n.t(if (Pronunciation.enabled(this)) "on" else "off"),
            artGestureLabel(),
            StudyText.t("pack_title"),
            I18n.t("m_privacy"),
            I18n.t("m_licenses"),
            ProText.t("menu")
        )
        AlertDialog.Builder(this)
            .setTitle(I18n.t("settings") + "  ·  " + buildLabel())
            .setItems(items) { _, which ->
                when (which) {
                    0 -> chooseCountry(firstRun = false)
                    1 -> openCamera()
                    2 -> openMicTest()
                    3 -> clearScreen()
                    4 -> exportScreen()
                    5 -> settings.open()
                    6 -> toggleSilent()
                    7 -> {
                        Pronunciation.setEnabled(this, !Pronunciation.enabled(this))
                        toast(pronLabel() + ": " + I18n.t(if (Pronunciation.enabled(this)) "on" else "off"))
                    }
                    8 -> {
                        prefs.edit().putBoolean("art_throw", !prefs.getBoolean("art_throw", false)).apply()
                        toast(artGestureLabel())
                    }
                    9 -> PackUi.menu(this)
                    10 -> startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(PRIVACY_URL)))
                    11 -> showLicenses()
                    else -> Pro.showPaywall(this)
                }
            }
            .show().also { Ui.watch(it) }
    }

    // "Korean pronunciation" for Korean speakers (Hangul), "pronunciation" (romanization) for others.
    private fun pronLabel(): String = when (I18n.lang) {
        "ko" -> "🗣 한글 발음 표시"
        "ja" -> "🗣 発音表記"
        "zh" -> "🗣 发音标注"
        "fr" -> "🗣 Prononciation"
        "es" -> "🗣 Pronunciación"
        "de" -> "🗣 Aussprache"
        else -> "🗣 Pronunciation"
    }

    // What a swipe on a book picture does: skip forward/back (default) or throw the picture away.
    private fun artGestureLabel(): String {
        val t = prefs.getBoolean("art_throw", false)
        return when (I18n.lang) {
            "ko" -> "🖼 그림 위에서 밀기: " + if (t) "그림 던져서 숨기기" else "앞뒤 감기"
            "ja" -> "🖼 絵の上のスワイプ: " + if (t) "投げて隠す" else "早送り・巻き戻し"
            "zh" -> "🖼 在图片上滑动：" + if (t) "甩出隐藏" else "快进/快退"
            "fr" -> "🖼 Glisser sur une image : " + if (t) "la lancer pour masquer" else "avancer/reculer"
            "es" -> "🖼 Deslizar sobre una imagen: " + if (t) "lanzarla para ocultar" else "avanzar/retroceder"
            "de" -> "🖼 Wischen auf Bild: " + if (t) "wegwerfen zum Ausblenden" else "vor/zurück spulen"
            else -> "🖼 Swipe on a picture: " + if (t) "throw it away to hide" else "skip forward/back"
        }
    }

    // Version and build number, so it is clear which build is installed.
    private fun buildLabel(): String = try {
        val info = packageManager.getPackageInfo(packageName, 0)
        "v${info.versionName} (${info.longVersionCode - 1000})"
    } catch (_: Exception) { "" }

    private fun showLicenses() {
        val text = try { assets.open("licenses/NOTICES.txt").bufferedReader().use { it.readText() } } catch (_: Exception) { "" }
        AlertDialog.Builder(this)
            .setTitle(I18n.t("m_licenses"))
            .setView(ScrollView(this).apply {
                addView(TextView(context).apply {
                    this.text = text
                    textSize = 12f
                    setTextIsSelectable(true)
                    setPadding(dp(20), dp(8), dp(20), dp(8))
                })
            })
            .setPositiveButton(android.R.string.ok, null)
            .show().also { Ui.watch(it) }
    }

    private fun toggleSilent() {
        val next = !silent
        prefs.edit().putBoolean("silent", next).apply()
        if (listening) {
            setQuiet(false)
            setQuiet(true)
        }
        toast(I18n.t(if (next) "silent_on" else "silent_off"))
    }

    private fun clearScreen() {
        SpeechOut.stop(tts)
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
        if (lines.isEmpty()) { toast(I18n.t("nothing_export")); return }
        share(lines.joinToString("\n\n") { (if (it.typed) "✍ " else "") + it.src + "\n→ " + it.tr })
    }

    /* ---------- Translation engines ---------- */

    private fun useEngine(id: String) {
        prefs.edit().putString("engine", id).apply()
        remoteBlockedUntil = 0L
        toast(I18n.f("using", engineLabel(activeEngine)))
        if (listening) { stop(); start() } else status(I18n.f("engine_status", engineLabel(activeEngine)))
    }

    // Runs on a background thread for the engine settings "test" button.
    private fun testEngine(id: String): String {
        val target = langs.first { it.code != home.code }
        val result = remote(id, SAMPLES[home.code] ?: SAMPLES.getValue("en"), home, target, "")
        return "${target.native}: $result"
    }

    // Runs on a background thread.
    private fun remote(id: String, text: String, sl: Lang, tl: Lang, context: String): String =
        RemoteTranslate.translate(prefs, id, text, sl, tl, context)

    private fun remoteFailed(id: String, e: Exception?) {
        val name = engineLabel(id)
        val code = (e as? Providers.HttpException)?.code
        val (wait, msg) = when (code) {
            401, 403 -> Long.MAX_VALUE to I18n.f("err_key", name)
            456 -> Long.MAX_VALUE to I18n.t("err_quota")
            400, 404 -> Long.MAX_VALUE to I18n.f("err_config", name)
            429 -> 10_000L to I18n.f("err_rate", name)
            else -> 30_000L to I18n.f("err_net", name)
        }
        remoteBlockedUntil = if (wait == Long.MAX_VALUE) Long.MAX_VALUE else System.currentTimeMillis() + wait
        status(msg)
    }

    /* ---------- Saving to the vocabulary list ---------- */

    // Returns the foreign text, the text in my language, and the foreign language of a translated line.
    private fun sides(seg: Seg): Triple<String, String, Lang> =
        if (seg.tl.code == home.code) Triple(seg.src, seg.tr, seg.sl)
        else Triple(seg.tr, seg.src, seg.tl)

    private fun saveSentence(seg: Seg) {
        val (foreignText, homeText, lang) = sides(seg)
        storeVocab(foreignText, homeText, "", "", lang)
    }

    // Saves a selected word with its meaning, using the line it came from as the example.
    private fun saveWord(word: String, fromTranslation: Boolean, seg: Seg) {
        val (exampleForeign, exampleHome, foreignLang) = sides(seg)
        val wordLang = if (fromTranslation) seg.tl else seg.sl
        if (wordLang.code == home.code) {
            withTranslator(home, foreignLang) { t ->
                t.translate(word)
                    .addOnSuccessListener { fw -> storeVocab(fw, word, exampleForeign, exampleHome, foreignLang) }
                    .addOnFailureListener { toast(I18n.t("word_failed")) }
            }
        } else {
            withTranslator(wordLang, home) { t ->
                t.translate(word)
                    .addOnSuccessListener { m -> storeVocab(word, m, exampleForeign, exampleHome, wordLang) }
                    .addOnFailureListener { storeVocab(word, "", exampleForeign, exampleHome, wordLang) }
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
                .put("l", lang.code)
                .put("d", System.currentTimeMillis())
                .put("c", 0)
                .put("w", 0)
        )
        prefs.edit().putString("saved", arr.toString()).apply()
        toast(I18n.f("saved_word", text + if (meaning.isNotEmpty()) " = $meaning" else ""))
    }

    // Adds "save word" next to copy when part of a line is selected.
    private fun enableWordSave(tv: TextView, seg: Seg, fromTranslation: Boolean) {
        tv.customSelectionActionModeCallback = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.add(0, MENU_SAVE_WORD, 0, I18n.t("save_word"))
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
        startActivity(Intent.createChooser(send, I18n.t("export_title")))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /* ---------- Language pack check ---------- */

    private fun looksLike(text: String, code: String): Boolean {
        val letters = text.filter { it.isLetter() }
        if (letters.length < 3) return true
        val hangul = letters.count { it in '\uAC00'..'\uD7A3' }
        val kana = letters.count { it in '\u3040'..'\u30FF' }
        val han = letters.count { it in '\u4E00'..'\u9FFF' }
        val latin = letters.count { it in 'a'..'z' || it in 'A'..'Z' || it in '\u00C0'..'\u024F' }
        return when (code) {
            "ko" -> hangul > 0
            "ja" -> kana + han > 0
            "zh" -> han > 0
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
        status(I18n.t("repairing"))
        val mm = RemoteModelManager.getInstance()
        val tasks = listOf(src, tgt)
            .filter { it != "en" }
            .map { mm.deleteDownloadedModel(TranslateRemoteModel.Builder(it).build()) }
        Tasks.whenAllComplete(tasks).addOnCompleteListener {
            if (wasListening) start() else status(I18n.t("repaired"))
        }
    }

    /* ---------- Start / stop ---------- */

    private fun start() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status(I18n.t("no_recognizer"))
            return
        }
        if (auto) {
            if (to.code != home.code) foreign = to
            listening = true
            micButton.text = I18n.t("stop")
            recent.clear()
            detectedTag = null
            heard = home
            setQuiet(true)
            withTranslator(home, foreign) { }
            withTranslator(foreign, home) { }
            autoStatus()
            startRecognizer()
            return
        }
        if (from == to) {
            status(I18n.t("same_lang"))
            return
        }
        listening = true
        micButton.text = I18n.t("stop")
        recent.clear()
        setQuiet(true)

        translator?.close()
        val t = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(from.code)
                .setTargetLanguage(to.code)
                .build()
        )
        translator = t
        status(I18n.t("preparing"))
        t.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                if (listening && translator === t) {
                    status(I18n.f("listening", from.native, to.native, engineLabel(activeEngine)))
                    startRecognizer()
                }
            }
            .addOnFailureListener {
                stop()
                status(I18n.t("pack_failed"))
            }
    }

    private fun stop() {
        listening = false
        recognizer?.destroy()
        recognizer = null
        setQuiet(false)
        interimView.text = ""
        micButton.text = I18n.t("start")
        status(I18n.f("engine_status", engineLabel(activeEngine)))
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(rc, perms, res)
        if (rc != 1) return
        if (res.firstOrNull() == PackageManager.PERMISSION_GRANTED) start()
        else status(I18n.t("mic_perm"))
    }

    override fun onResume() {
        super.onResume()
        Pro.refresh()
        if (::vocabPage.isInitialized && currentTab == 2) vocabPage.refresh()
    }

    override fun onStop() {
        if (listening) stop()
        convPage.stopMic()
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
                    status(I18n.t("mic_perm"))
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
                val words = terms.mapNotNull { it.names[from.code] }
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
        val p = Glossary.prepare(raw, sl.code, tl.code, terms, home.code)
        val source = sl.code
        val target = tl.code

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
            text = (if (typed) "✍ " else "") + (if (auto || typed) "[${sl.native}] " else "") + p.shown
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
            setTextColor(Ui.PRIMARY)
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
        // Each translation sits on its own card: the translation big, the original quiet below it.
        val segCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.card(context)
            setPadding(dp(16), dp(12), dp(10), dp(6))
            addView(trView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(srcRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })
        }
        segments.addView(segCard, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })
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

    private fun viaMlKit(t: Translator, id: Int, seg: Seg, p: Glossary.Prepared, source: String, target: String) {
        t.translate(p.input)
            .addOnSuccessListener { raw ->
                val tr = p.restore(raw)
                if (!looksLike(tr, target) && looksLike(p.shown, source)) {
                    seg.view.text = tr + "\n" + I18n.t("pack_error")
                    queueSpeech(id, null)
                    repairLanguagePack(source, target)
                    return@addOnSuccessListener
                }
                showResult(id, seg, tr, target)
            }
            .addOnFailureListener {
                seg.view.text = I18n.t("failed")
                queueSpeech(id, null)
            }
    }

    private fun showResult(id: Int, seg: Seg, tr: String, target: String) {
        seg.tr = tr
        seg.view.text = tr
        Pronunciation.attach(this, segments, seg.view, tr, target, home.code)
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
        convPage.stopListening()
        setQuiet(false)
        translator?.close()
        translators.values.forEach { it.close() }
        tts?.shutdown()
        net.shutdown()
        super.onDestroy()
    }
}