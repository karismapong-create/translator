package com.example.interpreter

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.View
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
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

// Conversation tab: phrasebook by place, and an AI partner for free role-play with corrections and hints.
class ConversationView(
    private val activity: Activity,
    private val prefs: SharedPreferences,
    langs: List<LangInfo>,
    private val speak: (String, String) -> Unit,
    private val stopSpeaking: () -> Unit,
    // Speaks after whatever is playing, instead of cutting it off.
    private val speakAfter: ((String, String) -> Unit)? = null
) : LinearLayout(activity) {

    private class Topic(val key: String, val role: String, val scene: String)

    private val topics = listOf(
        Topic("tp_cafe", "a barista at a café", "ordering drinks and food at a café"),
        Topic("tp_hotel", "a hotel front desk clerk", "checking in at a hotel"),
        Topic("tp_rest", "a restaurant server", "ordering and paying at a restaurant"),
        Topic("tp_pharm", "a pharmacist", "describing symptoms and buying medicine at a pharmacy"),
        Topic("tp_way", "a friendly local", "asking for directions and using public transport"),
        Topic("tp_shop", "a shop clerk", "shopping for clothes and souvenirs"),
        Topic("tp_biz", "a business partner", "a first business meeting with small talk"),
        Topic("tp_cowork", "a coworker", "casual chat with a coworker during a break"),
        Topic("tp_news", "a friend who follows the news", "discussing a recent general news topic"),
        Topic("tp_intro", "a new friend", "introducing yourselves and talking about hobbies"),
        Topic("tp_custom", "", "")
    )
    private val levels = listOf(
        "lv_1" to "beginner, using short simple sentences and basic words",
        "lv_2" to "intermediate, using natural everyday sentences",
        "lv_3" to "advanced, using natural native-speed speech, idioms and nuance"
    )
    private val home: Lang = Languages.home(prefs)
    private val foreignLangs = langs.filter { it.code != home.code }
    private val blue = Color.rgb(47, 91, 234)
    private val green = Color.rgb(46, 160, 67)
    private val density = resources.displayMetrics.density
    private val net = Executors.newSingleThreadExecutor()
    private val audio = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var engine: String? = null
    private var lang: LangInfo = foreignLangs.first()
    private var system = ""
    private var hintSystem = ""
    // The last hint shown, and whether my own line is being read out this turn.
    private var lastHint = ""
    private var spokeMine = false
    private val turns = ArrayList<Pair<String, String>>()
    private var busy = false
    private var session = 0
    private var lastUserBox: LinearLayout? = null
    private var recognizer: SpeechRecognizer? = null
    private var muted = false
    private var fromSituation = false

    private val modeTabs: List<TextView>
    private val situationView: SituationView
    private val aiPanel: LinearLayout
    private val setupPanel: ScrollView
    private val langSpinner: Spinner
    private val topicSpinner: Spinner
    private val customTopic: EditText
    private val levelSpinner: Spinner
    private val roleInput: EditText
    private val engineNote: TextView
    private val chatPanel: LinearLayout
    private val title: TextView
    private val messages: LinearLayout
    private val scroll: ScrollView
    private val input: EditText
    private val micButton: Button
    private val autoSpeak: CheckBox

    init {
        orientation = VERTICAL

        situationView = SituationView(activity, prefs, foreignLangs) { l, scene, role, t ->
            startFromSituation(l, scene, role, t)
        }

        langSpinner = spinner(foreignLangs.map { it.label })
        topicSpinner = spinner(topics.map { TalkText.t(it.key) })
        customTopic = EditText(activity).apply {
            setHint(TalkText.t("custom_hint"))
            textSize = 15f
            visibility = View.GONE
        }
        topicSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                customTopic.visibility = if (topics[pos].role.isEmpty()) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        levelSpinner = spinner(levels.map { TalkText.t(it.first) })
        roleInput = EditText(activity).apply {
            setHint(TalkText.t("role_hint"))
            setSingleLine(true)
            textSize = 15f
        }
        engineNote = TextView(activity).apply {
            textSize = 13f
            setPadding(0, dp(12), 0, dp(4))
        }
        val startButton = Button(activity).apply {
            text = TalkText.t("start_chat")
            textSize = 18f
            setOnClickListener { startSession() }
        }
        val setupBox = LinearLayout(activity).apply {
            orientation = VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            addView(label(TalkText.t("l_lang")))
            addView(langSpinner)
            addView(label(TalkText.t("l_topic")))
            addView(topicSpinner)
            addView(customTopic)
            addView(label(TalkText.t("l_level")))
            addView(levelSpinner)
            addView(label(TalkText.t("l_role")))
            addView(roleInput)
            addView(engineNote)
            addView(startButton)
        }
        setupPanel = ScrollView(activity).apply { addView(setupBox) }

        title = TextView(activity).apply {
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
        }
        val hintButton = Button(activity).apply {
            text = TalkText.t("hint_btn")
            setOnClickListener { hint() }
        }
        val endButton = Button(activity).apply {
            text = TalkText.t("end_btn")
            setOnClickListener { endSession() }
        }
        val header = LinearLayout(activity).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(hintButton)
            addView(endButton)
        }
        messages = LinearLayout(activity).apply { orientation = VERTICAL }
        scroll = ScrollView(activity).apply { addView(messages) }
        input = EditText(activity).apply {
            setHint(TalkText.t("input_hint"))
            setSingleLine(true)
            textSize = 16f
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) { submitInput(); true } else false
            }
        }
        val sendButton = Button(activity).apply {
            text = TalkText.t("send")
            setOnClickListener { submitInput() }
        }
        val inputRow = LinearLayout(activity).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(input, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(sendButton)
        }
        autoSpeak = CheckBox(activity).apply {
            text = TalkText.t("auto_speak")
            isChecked = true
        }
        micButton = Button(activity).apply {
            text = TalkText.t("mic")
            textSize = 17f
            setOnClickListener { if (recognizer != null) recognizer?.stopListening() else listen() }
        }
        val micRow = LinearLayout(activity).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(autoSpeak, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(micButton, LayoutParams(dp(180), dp(60)))
        }
        chatPanel = LinearLayout(activity).apply {
            orientation = VERTICAL
            visibility = View.GONE
            addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
            addView(inputRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(micRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        aiPanel = LinearLayout(activity).apply {
            orientation = VERTICAL
            addView(setupPanel, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
            addView(chatPanel, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        }

        modeTabs = listOf("mode_situation", "mode_ai").mapIndexed { i, key ->
            TextView(activity).apply {
                text = TalkText.t(key)
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(8))
                setOnClickListener { showMode(i) }
            }
        }
        val modeBar = LinearLayout(activity).apply {
            orientation = HORIZONTAL
            modeTabs.forEach { addView(it, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)) }
        }
        val body = FrameLayout(activity).apply {
            addView(situationView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(aiPanel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        addView(modeBar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        showMode(0)
    }

    /* ---------- Small helpers ---------- */

    private fun dp(v: Int) = (v * density).toInt()
    private fun toast(msg: String) = Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
    private fun scrollDown() = scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    private fun englishName(l: LangInfo) = Languages.byCode(l.code).english

    private fun spinner(items: List<String>) = Spinner(activity).apply {
        adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, items)
    }

    private fun label(text: String) = TextView(activity).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.GRAY)
        setPadding(0, dp(12), 0, 0)
    }

    private fun text(value: String, size: Float, color: Int = Color.BLACK, bold: Boolean = false) =
        TextView(activity).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
            setTextIsSelectable(true)
        }

    private fun action(label: String, color: Int = Color.BLACK, onClick: (TextView) -> Unit) =
        TextView(activity).apply {
            text = label
            textSize = 15f
            setTextColor(color)
            setPadding(0, dp(6), dp(20), dp(2))
            setOnClickListener { onClick(this) }
        }

    private fun row(vararg views: View) = LinearLayout(activity).apply {
        orientation = HORIZONTAL
        views.forEach { addView(it) }
    }

    private fun bubble(bg: Int) = LinearLayout(activity).apply {
        orientation = VERTICAL
        setPadding(dp(14), dp(10), dp(14), dp(10))
        background = Ui.rounded(bg, 18, activity, if (bg == Ui.SURFACE) Ui.LINE else 0)
    }

    /* ---------- Modes ---------- */

    private fun showMode(i: Int) {
        situationView.visibility = if (i == 0) View.VISIBLE else View.GONE
        aiPanel.visibility = if (i == 1) View.VISIBLE else View.GONE
        modeTabs.forEachIndexed { k, t ->
            t.setTextColor(if (k == i) blue else Color.GRAY)
            t.setTypeface(null, if (k == i) Typeface.BOLD else Typeface.NORMAL)
        }
        if (i != 0) situationView.stopPlayback()
        if (i != 1) stopListening()
    }

    fun onShown() {
        engine = WordExplainer.llmEngine(prefs)
        val e = engine
        if (e == null) {
            engineNote.setTextColor(Color.rgb(207, 34, 46))
            engineNote.text = TalkText.t("need_llm_note")
        } else {
            engineNote.setTextColor(Color.GRAY)
            engineNote.text = TalkText.f("engine_note", WordExplainer.label(e))
        }
    }

    /* ---------- Starting a session ---------- */

    private fun startSession() {
        val e = WordExplainer.llmEngine(prefs)
        if (e == null) {
            onShown()
            toast(TalkText.t("need_llm_toast"))
            return
        }
        engine = e
        fromSituation = false
        val l = foreignLangs[langSpinner.selectedItemPosition]
        val topic = topics[topicSpinner.selectedItemPosition]
        val custom = topic.role.isEmpty()
        val scene = if (custom) customTopic.text.toString().trim().ifEmpty { "free conversation" } else topic.scene
        val role = roleInput.text.toString().trim()
            .ifEmpty { if (custom) "a friendly conversation partner" else topic.role }
        val level = levels[levelSpinner.selectedItemPosition]
        val topicLabel = if (custom) scene else TalkText.t(topic.key)
        startWith(l, scene, role, level, "${l.label} · $topicLabel · ${TalkText.t(level.first)}")
    }

    private fun startFromSituation(l: LangInfo, scene: String, role: String, titleText: String) {
        val e = WordExplainer.llmEngine(prefs)
        if (e == null) {
            toast(TalkText.t("need_llm_toast"))
            return
        }
        engine = e
        fromSituation = true
        showMode(1)
        val level = levels[levelSpinner.selectedItemPosition]
        startWith(l, scene, role, level, "${l.label} · $titleText · ${TalkText.t(level.first)}")
    }

    private fun startWith(l: LangInfo, scene: String, role: String, level: Pair<String, String>, titleText: String) {
        lang = l
        val name = englishName(l)
        val mine = home.english
        system = "You are role-playing as $role in this situation: $scene. You are talking with a $mine-speaking " +
            "learner of $name at ${level.second} level. In \"reply\" speak only natural spoken $name, 1 to 3 " +
            "short sentences, and usually end with a question so the conversation keeps going. Stay in character. " +
            "For every user message, check the learner's $name. If it has mistakes or sounds unnatural, put a " +
            "corrected natural version in \"correction\", its $mine meaning in \"correction_meaning\", and a short " +
            "$mine explanation in \"explain\"; if it is already natural, leave those three empty. If the learner " +
            "writes in $mine, they are asking how to say it: put the $name expression in \"correction\" with " +
            "$mine notes, then continue the role-play. The first user message is [start]: open the conversation " +
            "with a greeting that fits the situation. Reply with JSON only, no code fences: " +
            "{\"reply\":\"...\",\"meaning\":\"$mine translation of reply\",\"correction\":\"\"," +
            "\"correction_meaning\":\"\",\"explain\":\"\"}"
        hintSystem = "This is a role-play between a $mine-speaking learner of $name (user) and $role (assistant) in " +
            "this situation: $scene. The last user message is [hint]. Suggest one natural thing the learner could " +
            "say next, in $name at ${level.second} level. Reply with JSON only, no code fences: " +
            "{\"say\":\"...\",\"meaning\":\"$mine translation\"}"

        title.text = titleText
        turns.clear()
        messages.removeAllViews()
        lastUserBox = null
        session++
        busy = false
        setupPanel.visibility = View.GONE
        chatPanel.visibility = View.VISIBLE
        turns.add("user" to "[start]")
        ask()
    }

    // Phone back button: leaves a chat, or steps back inside the phrasebook. Returns false when nothing is left.
    fun back(): Boolean {
        if (situationView.visibility == View.VISIBLE) return situationView.back()
        if (chatPanel.visibility == View.VISIBLE) {
            endSession()
            return true
        }
        return false
    }

    private fun endSession() {
        stopListening()
        stopSpeaking()
        session++
        busy = false
        chatPanel.visibility = View.GONE
        setupPanel.visibility = View.VISIBLE
        onShown()
        if (fromSituation) {
            fromSituation = false
            showMode(0)
        }
    }

    /* ---------- Talking to the AI ---------- */

    private fun submitInput() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        input.setText("")
        sendUser(t, typed = true)
    }

    private fun sendUser(text: String, typed: Boolean = false) {
        if (busy) {
            toast(TalkText.t("busy"))
            return
        }
        lastUserBox = addUser(text)
        turns.add("user" to text)
        // A sent hint (or any line in the language I practise) is read out, so I hear how it sounds.
        spokeMine = autoSpeak.isChecked && (text == lastHint || (typed && LangCheck.looks(text, lang.code)))
        if (spokeMine) speak(text, lang.voice)
        ask()
    }

    private fun ask() {
        val e = engine ?: return
        val s = session
        busy = true
        val waiting = text("…", 18f, Color.GRAY)
        messages.addView(waiting)
        scrollDown()
        val snapshot = ArrayList(turns)
        net.execute {
            val r: Any = try { Tutor.send(prefs, e, system, snapshot) } catch (ex: Exception) { ex }
            post {
                if (s != session) return@post
                messages.removeView(waiting)
                busy = false
                if (r is String) handleReply(r) else showError(r as Exception)
            }
        }
    }

    private fun handleReply(raw: String) {
        turns.add("assistant" to raw)
        val o = Tutor.json(raw)
        val reply = o?.optString("reply").orEmpty().ifEmpty { raw }
        val meaning = o?.optString("meaning").orEmpty()
        val correction = o?.optString("correction").orEmpty()
        if (correction.isNotEmpty()) {
            lastUserBox?.let {
                addCorrection(it, correction, o?.optString("correction_meaning").orEmpty(), o?.optString("explain").orEmpty())
            }
        }
        addAi(reply, meaning)
        if (autoSpeak.isChecked) {
            val after = speakAfter
            if (spokeMine && after != null) after(reply, lang.voice) else speak(reply, lang.voice)
        }
        spokeMine = false
    }

    private fun showError(e: Exception) {
        val box = bubble(Color.rgb(253, 236, 236))
        box.addView(text(TalkText.f("no_reply", e.message ?: e.toString()), 14f, Color.DKGRAY))
        box.addView(action(TalkText.t("retry"), blue) {
            messages.removeView(box)
            ask()
        })
        messages.addView(box, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(10), 0, 0)
        })
        scrollDown()
    }

    private fun addUser(text: String): LinearLayout {
        val box = bubble(Ui.SURFACE)
        box.addView(text("🙋 $text", 17f))
        if (LangCheck.looks(text, lang.code)) box.addView(row(action(TalkText.t("listen")) { speak(text, lang.voice) }))
        messages.addView(box, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(40), dp(10), 0, 0)
        })
        scrollDown()
        return box
    }

    private fun addCorrection(box: LinearLayout, correction: String, meaning: String, explain: String) {
        box.addView(TextView(activity).apply {
            text = TalkText.t("better")
            textSize = 13f
            setTextColor(green)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(8), 0, 0)
        })
        box.addView(text(correction, 17f, Color.BLACK, true))
        if (meaning.isNotEmpty()) box.addView(text(meaning, 14f, blue))
        if (explain.isNotEmpty()) box.addView(text(explain, 13f, Color.DKGRAY))
        box.addView(row(
            action(TalkText.t("listen")) { speak(correction, lang.voice) },
            action(TalkText.t("save"), blue) { v -> save(correction, meaning); v.text = TalkText.t("saved_mark"); v.isEnabled = false }
        ))
    }

    private fun addAi(reply: String, meaning: String) {
        val box = bubble(Ui.PRIMARY_SOFT)
        val replyView = text(reply, 20f, Color.BLACK, true)
        box.addView(replyView)
        if (meaning.isNotEmpty()) box.addView(text(meaning, 15f, blue))
        box.addView(row(
            action(TalkText.t("replay")) { speak(reply, lang.voice) },
            action(TalkText.t("save"), blue) { v -> save(reply, meaning); v.text = TalkText.t("saved_mark"); v.isEnabled = false }
        ))
        messages.addView(box, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(10), dp(40), 0)
        })
        Pronunciation.attach(activity, box, replyView, reply, lang.code, home.code)
        scrollDown()
    }

    private fun hint() {
        val e = engine ?: return
        if (busy || turns.lastOrNull()?.first != "assistant") {
            toast(TalkText.t("hint_after"))
            return
        }
        busy = true
        val s = session
        val snapshot = ArrayList(turns).apply { add("user" to "[hint]") }
        val waiting = text(TalkText.t("hint_wait"), 14f, Color.GRAY)
        messages.addView(waiting)
        scrollDown()
        net.execute {
            val r: Any = try { Tutor.send(prefs, e, hintSystem, snapshot) } catch (ex: Exception) { ex }
            post {
                if (s != session) return@post
                messages.removeView(waiting)
                busy = false
                val o = (r as? String)?.let { Tutor.json(it) }
                val say = o?.optString("say").orEmpty()
                if (say.isEmpty()) {
                    toast(TalkText.t("hint_fail"))
                    return@post
                }
                addHint(say, o?.optString("meaning").orEmpty())
            }
        }
    }

    private fun addHint(say: String, meaning: String) {
        val box = bubble(Color.rgb(255, 247, 228))
        box.addView(TextView(activity).apply {
            text = TalkText.t("hint_title")
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
        })
        lastHint = say
        val sayView = text(say, 17f, Color.BLACK, true)
        box.addView(sayView)
        if (meaning.isNotEmpty()) box.addView(text(meaning, 14f, blue))
        box.addView(row(
            action(TalkText.t("listen")) { speak(say, lang.voice) },
            action(TalkText.t("use_this"), blue) { input.setText(say) },
            action("☆", blue) { v -> save(say, meaning); v.text = "★"; v.isEnabled = false }
        ))
        messages.addView(box, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(20), dp(10), dp(20), 0)
        })
        // Reading in my alphabet under the hint, the same as under the AI's replies.
        Pronunciation.attach(activity, box, sayView, say, lang.code, home.code)
        scrollDown()
    }

    /* ---------- Speaking ---------- */

    private fun setMuted(on: Boolean) {
        if (on && !prefs.getBoolean("silent", true)) return
        if (muted == on) return
        muted = on
        for (stream in listOf(AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM, AudioManager.STREAM_MUSIC)) {
            try {
                audio.adjustStreamVolume(stream, if (on) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)
            } catch (_: Exception) {}
        }
    }

    private fun firstResult(b: Bundle?) = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun listen() {
        if (busy) {
            toast(TalkText.t("busy"))
            return
        }
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 4)
            return
        }
        stopSpeaking()
        val r = SpeechRecognizer.createSpeechRecognizer(activity)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(b: Bundle?) {
                firstResult(b)?.let { input.setText(it) }
            }
            override fun onResults(b: Bundle?) {
                val t = firstResult(b).orEmpty().trim().ifEmpty { input.text.toString().trim() }
                finishListen()
                input.setText("")
                if (t.isNotEmpty()) sendUser(t)
            }
            override fun onError(error: Int) {
                finishListen()
                if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    toast(TalkText.t("not_heard"))
                }
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
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang.voice)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf(home.speechTag))
        }
        setMuted(true)
        r.startListening(intent)
        micButton.text = TalkText.t("mic_on")
    }

    private fun finishListen() {
        recognizer?.destroy()
        recognizer = null
        setMuted(false)
        micButton.text = TalkText.t("mic")
    }

    fun stopListening() {
        situationView.stopPlayback()
        stopMic()
    }

    // Only the microphone; situation playback may go on with the screen off.
    fun stopMic() {
        if (recognizer != null) {
            recognizer?.cancel()
            finishListen()
        }
    }

    /* ---------- Saving ---------- */

    private fun save(text: String, meaning: String) {
        val arr = try { JSONArray(prefs.getString("saved", "[]")) } catch (_: Exception) { JSONArray() }
        arr.put(
            JSONObject()
                .put("t", text)
                .put("m", meaning)
                .put("e", "")
                .put("em", "")
                .put("v", lang.voice)
                .put("l", lang.code)
                .put("d", System.currentTimeMillis())
                .put("c", 0)
                .put("w", 0)
        )
        prefs.edit().putString("saved", arr.toString()).apply()
        toast(TalkText.t("saved_toast"))
    }
}