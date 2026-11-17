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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

// AI conversation partner: role-play in a chosen language and topic, with corrections and hints.
class ConversationView(
    private val activity: Activity,
    private val prefs: SharedPreferences,
    langs: List<LangInfo>,
    private val speak: (String, String) -> Unit,
    private val stopSpeaking: () -> Unit
) : LinearLayout(activity) {

    private class Topic(val label: String, val role: String, val scene: String)

    private val topics = listOf(
        Topic("카페에서 주문하기", "a barista at a café", "ordering drinks and food at a café"),
        Topic("호텔 체크인", "a hotel front desk clerk", "checking in at a hotel"),
        Topic("식당에서 식사", "a restaurant server", "ordering and paying at a restaurant"),
        Topic("약국·병원", "a pharmacist", "describing symptoms and buying medicine at a pharmacy"),
        Topic("길 묻기·교통", "a friendly local", "asking for directions and using public transport"),
        Topic("쇼핑", "a shop clerk", "shopping for clothes and souvenirs"),
        Topic("비즈니스 미팅", "a business partner", "a first business meeting with small talk"),
        Topic("직장 동료와 잡담", "a coworker", "casual chat with a coworker during a break"),
        Topic("뉴스 토론", "a friend who follows the news", "discussing a recent general news topic"),
        Topic("자기소개·취미", "a new friend", "introducing yourselves and talking about hobbies"),
        Topic("직접 입력", "", "")
    )
    private val levels = listOf(
        "초급" to "beginner, using short simple sentences and basic words",
        "중급" to "intermediate, using natural everyday sentences",
        "고급" to "advanced, using natural native-speed speech, idioms and nuance"
    )
    private val englishName = mapOf(
        "ja" to "Japanese", "en" to "English", "fr" to "French", "es" to "Spanish", "zh" to "Simplified Chinese"
    )
    private val foreignLangs = langs.filter { it.code != "ko" }
    private val blue = Color.rgb(47, 91, 234)
    private val green = Color.rgb(46, 160, 67)
    private val density = resources.displayMetrics.density
    private val net = Executors.newSingleThreadExecutor()
    private val audio = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var engine: String? = null
    private var lang: LangInfo = foreignLangs.first()
    private var system = ""
    private var hintSystem = ""
    private val turns = ArrayList<Pair<String, String>>()
    private var busy = false
    private var session = 0
    private var lastUserBox: LinearLayout? = null
    private var recognizer: SpeechRecognizer? = null
    private var muted = false

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

        langSpinner = spinner(foreignLangs.map { it.label })
        topicSpinner = spinner(topics.map { it.label })
        customTopic = EditText(activity).apply {
            setHint("주제를 적어주세요 (예: 일본 친구 집에 초대받았어요)")
            textSize = 15f
            visibility = View.GONE
        }
        topicSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                customTopic.visibility = if (topics[pos].role.isEmpty()) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        levelSpinner = spinner(levels.map { it.first })
        roleInput = EditText(activity).apply {
            setHint("AI 역할 (비워두면 주제에 맞게 정해요)")
            setSingleLine(true)
            textSize = 15f
        }
        engineNote = TextView(activity).apply {
            textSize = 13f
            setPadding(0, dp(12), 0, dp(4))
        }
        val startButton = Button(activity).apply {
            text = "대화 시작"
            textSize = 18f
            setOnClickListener { startSession() }
        }
        val setupBox = LinearLayout(activity).apply {
            orientation = VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            addView(label("언어"))
            addView(langSpinner)
            addView(label("주제"))
            addView(topicSpinner)
            addView(customTopic)
            addView(label("수준"))
            addView(levelSpinner)
            addView(label("AI 역할"))
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
            text = "힌트"
            setOnClickListener { hint() }
        }
        val endButton = Button(activity).apply {
            text = "끝내기"
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
            setHint("말하거나 입력하세요 (한국어도 OK)")
            setSingleLine(true)
            textSize = 16f
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) { submitInput(); true } else false
            }
        }
        val sendButton = Button(activity).apply {
            text = "보내기"
            setOnClickListener { submitInput() }
        }
        val inputRow = LinearLayout(activity).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(input, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(sendButton)
        }
        autoSpeak = CheckBox(activity).apply {
            text = "AI 말 읽어주기"
            isChecked = true
        }
        micButton = Button(activity).apply {
            text = "🎤 말하기"
            textSize = 17f
            setOnClickListener { if (recognizer != null) recognizer?.stopListening() else listen() }
        }
        val micRow = LinearLayout(activity).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(autoSpeak, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(micButton, LayoutParams(dp(170), dp(60)))
        }
        chatPanel = LinearLayout(activity).apply {
            orientation = VERTICAL
            visibility = View.GONE
            addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
            addView(inputRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(micRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }

        addView(setupPanel, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(chatPanel, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    /* ---------- Small helpers ---------- */

    private fun dp(v: Int) = (v * density).toInt()
    private fun toast(msg: String) = Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
    private fun scrollDown() = scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }

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
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setBackgroundColor(bg)
    }

    /* ---------- Setup ---------- */

    fun onShown() {
        engine = WordExplainer.llmEngine(prefs)
        val e = engine
        if (e == null) {
            engineNote.setTextColor(Color.rgb(207, 34, 46))
            engineNote.text = "⚠ 회화에는 LLM이 필요해요. 설정 → 번역 엔진에서 Claude, 맥북 LLM, OpenAI 호환 API 중 하나를 설정하세요."
        } else {
            engineNote.setTextColor(Color.GRAY)
            engineNote.text = "대화 엔진: " + WordExplainer.label(e)
        }
    }

    private fun startSession() {
        val e = WordExplainer.llmEngine(prefs)
        if (e == null) {
            onShown()
            toast("먼저 설정 → 번역 엔진에서 LLM을 설정하세요.")
            return
        }
        engine = e
        lang = foreignLangs[langSpinner.selectedItemPosition]
        val topic = topics[topicSpinner.selectedItemPosition]
        val custom = topic.role.isEmpty()
        val scene = if (custom) customTopic.text.toString().trim().ifEmpty { "free conversation" } else topic.scene
        val role = roleInput.text.toString().trim()
            .ifEmpty { if (custom) "a friendly conversation partner" else topic.role }
        val level = levels[levelSpinner.selectedItemPosition]
        val name = englishName[lang.code] ?: lang.label

        system = "You are role-playing as $role in this situation: $scene. You are talking with a Korean " +
            "learner of $name at ${level.second} level. In \"reply\" speak only natural spoken $name, 1 to 3 " +
            "short sentences, and usually end with a question so the conversation keeps going. Stay in character. " +
            "For every user message, check the learner's $name. If it has mistakes or sounds unnatural, put a " +
            "corrected natural version in \"correction\", its Korean meaning in \"correction_meaning\", and a short " +
            "Korean explanation in \"explain\"; if it is already natural, leave those three empty. If the learner " +
            "writes in Korean, they are asking how to say it: put the $name expression in \"correction\" with " +
            "Korean notes, then continue the role-play. The first user message is [start]: open the conversation " +
            "with a greeting that fits the situation. Reply with JSON only, no code fences: " +
            "{\"reply\":\"...\",\"meaning\":\"Korean translation of reply\",\"correction\":\"\"," +
            "\"correction_meaning\":\"\",\"explain\":\"\"}"
        hintSystem = "This is a role-play between a Korean learner of $name (user) and $role (assistant) in this " +
            "situation: $scene. The last user message is [hint]. Suggest one natural thing the learner could say " +
            "next, in $name at ${level.second} level. Reply with JSON only, no code fences: " +
            "{\"say\":\"...\",\"meaning\":\"Korean translation\"}"

        title.text = "${lang.label} · ${if (custom) scene else topic.label} · ${level.first}"
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

    private fun endSession() {
        stopListening()
        stopSpeaking()
        session++
        busy = false
        chatPanel.visibility = View.GONE
        setupPanel.visibility = View.VISIBLE
        onShown()
    }

    /* ---------- Talking to the AI ---------- */

    private fun submitInput() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        input.setText("")
        sendUser(t)
    }

    private fun sendUser(text: String) {
        if (busy) {
            toast("AI가 답하는 중이에요.")
            return
        }
        lastUserBox = addUser(text)
        turns.add("user" to text)
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
        if (autoSpeak.isChecked) speak(reply, lang.voice)
    }

    private fun showError(e: Exception) {
        val box = bubble(Color.argb(25, 207, 34, 46))
        box.addView(text("응답을 받지 못했어요: ${e.message ?: e}", 14f, Color.DKGRAY))
        box.addView(action("다시 시도", blue) {
            messages.removeView(box)
            ask()
        })
        messages.addView(box, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(10), 0, 0)
        })
        scrollDown()
    }

    private fun addUser(text: String): LinearLayout {
        val box = bubble(Color.argb(18, 0, 0, 0))
        box.addView(text("🙋 $text", 17f))
        messages.addView(box, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(40), dp(10), 0, 0)
        })
        scrollDown()
        return box
    }

    private fun addCorrection(box: LinearLayout, correction: String, meaning: String, explain: String) {
        box.addView(TextView(activity).apply {
            text = "✏ 더 자연스럽게"
            textSize = 13f
            setTextColor(green)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(8), 0, 0)
        })
        box.addView(text(correction, 17f, Color.BLACK, true))
        if (meaning.isNotEmpty()) box.addView(text(meaning, 14f, blue))
        if (explain.isNotEmpty()) box.addView(text(explain, 13f, Color.DKGRAY))
        box.addView(row(
            action("🔊 듣기") { speak(correction, lang.voice) },
            action("☆ 저장", blue) { v -> save(correction, meaning); v.text = "★ 저장됨"; v.isEnabled = false }
        ))
    }

    private fun addAi(reply: String, meaning: String) {
        val box = bubble(Color.argb(20, 47, 91, 234))
        val replyView = text(reply, 20f, Color.BLACK, true)
        box.addView(replyView)
        if (meaning.isNotEmpty()) box.addView(text(meaning, 15f, blue))
        box.addView(row(
            action("🔊 다시 듣기") { speak(reply, lang.voice) },
            action("☆ 저장", blue) { v -> save(reply, meaning); v.text = "★ 저장됨"; v.isEnabled = false }
        ))
        messages.addView(box, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(10), dp(40), 0)
        })
        Pronouncer.attach(activity, box, replyView, reply, lang.code)
        scrollDown()
    }

    private fun hint() {
        val e = engine ?: return
        if (busy || turns.lastOrNull()?.first != "assistant") {
            toast("AI가 말한 다음에 힌트를 볼 수 있어요.")
            return
        }
        busy = true
        val s = session
        val snapshot = ArrayList(turns).apply { add("user" to "[hint]") }
        val waiting = text("💡 힌트 만드는 중…", 14f, Color.GRAY)
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
                    toast("힌트를 받지 못했어요.")
                    return@post
                }
                addHint(say, o?.optString("meaning").orEmpty())
            }
        }
    }

    private fun addHint(say: String, meaning: String) {
        val box = bubble(Color.argb(30, 255, 200, 0))
        box.addView(TextView(activity).apply {
            text = "💡 이렇게 말해보세요"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
        })
        box.addView(text(say, 17f, Color.BLACK, true))
        if (meaning.isNotEmpty()) box.addView(text(meaning, 14f, blue))
        box.addView(row(
            action("🔊 듣기") { speak(say, lang.voice) },
            action("이 문장으로 말하기", blue) { input.setText(say) },
            action("☆", blue) { v -> save(say, meaning); v.text = "★"; v.isEnabled = false }
        ))
        messages.addView(box, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(20), dp(10), dp(20), 0)
        })
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
            toast("AI가 답하는 중이에요.")
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
                val t = firstResult(b).orEmpty().trim()
                finishListen()
                input.setText("")
                if (t.isNotEmpty()) sendUser(t)
            }
            override fun onError(error: Int) {
                finishListen()
                if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    toast("잘 못 들었어요. 다시 말해 주세요.")
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
            putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("ko-KR"))
        }
        setMuted(true)
        r.startListening(intent)
        micButton.text = "⏹ 듣는 중 (누르면 끝)"
    }

    private fun finishListen() {
        recognizer?.destroy()
        recognizer = null
        setMuted(false)
        micButton.text = "🎤 말하기"
    }

    fun stopListening() {
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
        toast("단어장에 저장했어요")
    }
}