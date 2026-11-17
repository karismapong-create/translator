package com.example.interpreter

import android.app.Activity
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

// Phrasebook by place: common questions, 2-3 minute example dialogues, and a jump into AI role-play.
class SituationView(
    private val activity: Activity,
    private val prefs: SharedPreferences,
    private val langs: List<LangInfo>,
    private val onRolePlay: (LangInfo, String, String, String) -> Unit
) : LinearLayout(activity) {

    private class Place(val emoji: String, val ko: String, val en: String, val role: String, val staff: String)
    private class Question(val ko: String, val phrase: String, val phraseKo: String)
    private class Line(val who: String, val text: String, val ko: String) {
        val me: Boolean get() = who.equals("A", true)
    }

    private val places = listOf(
        Place("✈️", "공항 체크인", "an airport check-in counter", "an airline check-in agent", "항공사 직원"),
        Place("🛂", "출입국심사대", "immigration passport control", "an immigration officer", "심사관"),
        Place("🧳", "세관", "customs inspection at the airport", "a customs officer", "세관 직원"),
        Place("🛍️", "면세점", "a duty-free shop", "a duty-free shop clerk", "점원"),
        Place("💱", "환전소·은행", "a currency exchange and bank", "a bank teller", "은행 직원"),
        Place("🚇", "지하철", "a subway station", "a station staff member", "역무원"),
        Place("🚌", "버스 정류장", "a bus stop and city bus", "a bus driver", "버스 기사"),
        Place("🚆", "기차역", "a train station ticket office", "a ticket office clerk", "매표 직원"),
        Place("🚕", "택시", "a taxi ride", "a taxi driver", "택시 기사"),
        Place("🚗", "렌터카 업체", "a car rental counter", "a car rental agent", "렌터카 직원"),
        Place("🏨", "호텔", "a hotel front desk", "a hotel front desk clerk", "프런트 직원"),
        Place("🍽️", "음식점", "a restaurant", "a restaurant server", "점원"),
        Place("☕", "카페", "a café", "a barista", "바리스타"),
        Place("🏪", "편의점", "a convenience store", "a convenience store clerk", "점원"),
        Place("🛒", "슈퍼마켓", "a supermarket", "a supermarket staff member", "직원"),
        Place("👕", "옷가게", "a clothing store", "a clothing store clerk", "점원"),
        Place("💊", "약국", "a pharmacy", "a pharmacist", "약사"),
        Place("🏥", "병원", "a hospital or clinic, from reception to the doctor", "a hospital receptionist and doctor", "병원 직원"),
        Place("🚻", "화장실 찾기", "finding and using public restrooms", "a passerby or shop staff member", "현지인"),
        Place("ℹ️", "관광안내소", "a tourist information center", "a tourist information staff member", "안내 직원"),
        Place("🏛️", "박물관", "a museum", "a museum staff member", "직원"),
        Place("🎢", "놀이공원", "an amusement park", "an amusement park staff member", "직원"),
        Place("🏊", "수영장", "a swimming pool", "a pool staff member", "직원"),
        Place("♨️", "온천", "a hot spring bath", "a hot spring staff member", "직원"),
        Place("🏖️", "해변", "a beach with rentals", "a lifeguard or beach rental staff member", "직원"),
        Place("⛰️", "산", "a mountain hiking area", "a mountain hut staff member", "산장 직원"),
        Place("🥾", "트레킹 코스", "a trekking trail", "a trail guide", "가이드"),
        Place("🏞️", "호수", "a lake with boat rentals", "a boat rental staff member", "직원"),
        Place("👮", "경찰서·분실물", "a police station and lost and found office", "a police officer", "경찰관"),
        Place("💇", "미용실", "a hair salon", "a hairdresser", "미용사")
    )
    private val englishName = mapOf(
        "ja" to "Japanese", "en" to "English", "fr" to "French", "es" to "Spanish", "zh" to "Simplified Chinese"
    )
    private val blue = Color.rgb(47, 91, 234)
    private val highlightColor = Color.argb(80, 255, 200, 0)
    private val density = resources.displayMetrics.density
    private val net = Executors.newSingleThreadExecutor()
    private val cacheDir = File(activity.filesDir, "situations").apply { mkdirs() }

    private var place: Place? = null
    private var questions: List<Question> = emptyList()
    private var question: Question? = null
    private var questionIndex = -1
    private var lines: List<Line> = emptyList()
    private var cards: List<View> = emptyList()
    private var screen = 0
    private var loadToken = 0

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var playing = false
    private var playIndex = 0
    private var playButton: Button? = null

    private val langSpinner: Spinner
    private val backButton: Button
    private val titleView: TextView
    private val gridScroll: ScrollView
    private val questionsBox: LinearLayout
    private val questionsScroll: ScrollView
    private val dialogueBox: LinearLayout
    private val dialogueScroll: ScrollView

    private val lang: LangInfo get() = langs[langSpinner.selectedItemPosition.coerceAtLeast(0)]

    init {
        orientation = VERTICAL

        backButton = Button(activity).apply {
            text = "← 뒤로"
            visibility = View.GONE
            setOnClickListener { goBack() }
        }
        titleView = TextView(activity).apply {
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(6), 0, dp(6), 0)
        }
        langSpinner = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, langs.map { it.label })
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = reloadForLanguage()
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        val header = LinearLayout(activity).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(backButton)
            addView(titleView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(langSpinner, LayoutParams(dp(140), LayoutParams.WRAP_CONTENT))
        }

        val grid = LinearLayout(activity).apply { orientation = VERTICAL }
        grid.addView(note(
            "장소를 고르면 자주 하는 질문과 대화 예문을 보여줘요. 처음 열 때 AI가 만들어 저장하고, 다음부터는 바로 열려요."
        ))
        places.chunked(3).forEach { row ->
            val rowLayout = LinearLayout(activity).apply { orientation = HORIZONTAL }
            row.forEach { p ->
                rowLayout.addView(placeButton(p), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                })
            }
            repeat(3 - row.size) { rowLayout.addView(View(activity), LayoutParams(0, 0, 1f)) }
            grid.addView(rowLayout)
        }
        gridScroll = ScrollView(activity).apply { addView(grid) }
        questionsBox = LinearLayout(activity).apply { orientation = VERTICAL }
        questionsScroll = ScrollView(activity).apply { addView(questionsBox) }
        dialogueBox = LinearLayout(activity).apply { orientation = VERTICAL }
        dialogueScroll = ScrollView(activity).apply { addView(dialogueBox) }

        val body = FrameLayout(activity).apply {
            listOf(gridScroll, questionsScroll, dialogueScroll).forEach {
                addView(it, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            }
        }
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        tts = TextToSpeech(activity) { status -> ttsReady = status == TextToSpeech.SUCCESS }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { post { lineFinished(utteranceId) } }
            override fun onError(utteranceId: String?) { post { lineFinished(utteranceId) } }
        })
        show(0)
    }

    /* ---------- Small helpers ---------- */

    private fun dp(v: Int) = (v * density).toInt()
    private fun toast(msg: String) = Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()

    private fun note(text: String, color: Int = Color.GRAY) = TextView(activity).apply {
        this.text = text
        textSize = 14f
        setTextColor(color)
        setPadding(dp(4), dp(8), dp(4), dp(8))
    }

    private fun text(value: String, size: Float, color: Int = Color.BLACK, bold: Boolean = false) =
        TextView(activity).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
            setTextIsSelectable(true)
        }

    private fun button(label: String, onClick: () -> Unit) = Button(activity).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun action(label: String, onClick: (TextView) -> Unit) = TextView(activity).apply {
        text = label
        textSize = 16f
        setPadding(0, dp(6), dp(22), dp(2))
        setOnClickListener { onClick(this) }
    }

    private fun placeButton(p: Place) = TextView(activity).apply {
        text = "${p.emoji}\n${p.ko}"
        textSize = 14f
        gravity = Gravity.CENTER
        setPadding(dp(4), dp(14), dp(4), dp(14))
        setBackgroundColor(Color.argb(18, 47, 91, 234))
        setOnClickListener { openPlace(p) }
    }

    /* ---------- Screens ---------- */

    private fun show(n: Int) {
        if (n != 2) stopPlay()
        screen = n
        gridScroll.visibility = if (n == 0) View.VISIBLE else View.GONE
        questionsScroll.visibility = if (n == 1) View.VISIBLE else View.GONE
        dialogueScroll.visibility = if (n == 2) View.VISIBLE else View.GONE
        backButton.visibility = if (n == 0) View.GONE else View.VISIBLE
        if (n == 0) titleView.text = "📍 어디에 계세요?"
    }

    private fun goBack() {
        loadToken++
        if (screen == 2) show(1) else show(0)
    }

    private fun reloadForLanguage() {
        stopPlay()
        if (screen >= 1 && place != null) {
            show(1)
            loadQuestions(false)
        }
    }

    /* ---------- Cache ---------- */

    private fun readCache(key: String): String? = File(cacheDir, "$key.json").takeIf { it.exists() }?.readText()
    private fun writeCache(key: String, text: String) = File(cacheDir, "$key.json").writeText(text)
    private fun clearDialogues(code: String, placeIdx: Int) {
        cacheDir.listFiles()?.filter { it.name.startsWith("d_${code}_${placeIdx}_") }?.forEach { it.delete() }
    }

    /* ---------- Questions ---------- */

    private fun openPlace(p: Place) {
        place = p
        show(1)
        loadQuestions(false)
    }

    private fun parseQuestions(raw: String): List<Question>? {
        val arr = Tutor.json(raw)?.optJSONArray("questions") ?: return null
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { Question(it.optString("ko"), it.optString("phrase"), it.optString("phrase_ko")) }
        }.filter { it.phrase.isNotBlank() }
    }

    private fun loadQuestions(force: Boolean) {
        val p = place ?: return
        val l = lang
        val placeIdx = places.indexOf(p)
        val key = "q_${l.code}_$placeIdx"
        titleView.text = "${p.emoji} ${p.ko}"
        questionsBox.removeAllViews()
        if (!force) {
            val cached = readCache(key)?.let { parseQuestions(it) }
            if (!cached.isNullOrEmpty()) {
                renderQuestions(cached)
                return
            }
        }
        val e = WordExplainer.llmEngine(prefs)
        if (e == null) {
            questionsBox.addView(note(
                "⚠ 질문 목록을 만들려면 LLM이 필요해요. 설정 → 번역 엔진에서 Claude, 맥북 LLM, OpenAI 호환 API 중 하나를 설정하세요.",
                Color.rgb(207, 34, 46)
            ))
            return
        }
        questionsBox.addView(note("${p.ko}에서 자주 하는 질문을 만드는 중… (${WordExplainer.label(e)})"))
        val name = englishName[l.code] ?: l.label
        val system = "You write travel phrasebooks for Korean speakers. For the given place, list the 10 " +
            "situations travelers most often need to ask or say there, ordered from most common. For each, give a " +
            "short Korean title and the single most useful natural $name sentence. Reply with JSON only, no code " +
            "fences: {\"questions\":[{\"ko\":\"Korean title\",\"phrase\":\"$name sentence\"," +
            "\"phrase_ko\":\"Korean meaning of the sentence\"}]}"
        val user = "Place: ${p.ko} (${p.en}). Language: $name."
        val token = ++loadToken
        net.execute {
            val r: Any = try { Tutor.send(prefs, e, system, listOf("user" to user)) } catch (ex: Exception) { ex }
            post {
                if (token != loadToken) return@post
                val raw = r as? String
                val qs = raw?.let { parseQuestions(it) }
                questionsBox.removeAllViews()
                if (raw == null || qs.isNullOrEmpty()) {
                    questionsBox.addView(note("만들지 못했어요: " + ((r as? Exception)?.message ?: "형식을 읽지 못했어요")))
                    questionsBox.addView(button("다시 시도") { loadQuestions(true) })
                    return@post
                }
                if (force) clearDialogues(l.code, placeIdx)
                writeCache(key, raw)
                renderQuestions(qs)
            }
        }
    }

    private fun renderQuestions(qs: List<Question>) {
        questions = qs
        questionsBox.removeAllViews()
        questionsBox.addView(note("질문을 누르면 2~3분 분량의 대화 예문이 나와요."))
        qs.forEachIndexed { i, q ->
            val row = LinearLayout(activity).apply {
                orientation = VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setBackgroundColor(Color.argb(14, 0, 0, 0))
                setOnClickListener { openDialogue(i) }
                addView(TextView(activity).apply {
                    text = "${i + 1}. ${q.ko}"
                    textSize = 17f
                    setTypeface(null, Typeface.BOLD)
                })
                addView(TextView(activity).apply {
                    text = q.phrase
                    textSize = 15f
                    setTextColor(Color.DKGRAY)
                })
                if (q.phraseKo.isNotEmpty()) addView(TextView(activity).apply {
                    text = q.phraseKo
                    textSize = 13f
                    setTextColor(blue)
                })
            }
            questionsBox.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, dp(4), 0, dp(4))
            })
        }
        questionsBox.addView(button("목록 다시 만들기") { loadQuestions(true) })
    }

    /* ---------- Dialogue ---------- */

    private fun openDialogue(i: Int) {
        question = questions.getOrNull(i) ?: return
        questionIndex = i
        show(2)
        loadDialogue(false)
    }

    private fun parseDialogue(raw: String): Pair<List<Line>, String>? {
        val o = Tutor.json(raw) ?: return null
        val arr = o.optJSONArray("lines") ?: return null
        val ls = (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { Line(it.optString("who", "A"), it.optString("text"), it.optString("ko")) }
        }.filter { it.text.isNotBlank() }
        return if (ls.isEmpty()) null else ls to o.optString("tips")
    }

    private fun loadDialogue(force: Boolean) {
        val p = place ?: return
        val q = question ?: return
        val l = lang
        val placeIdx = places.indexOf(p)
        val key = "d_${l.code}_${placeIdx}_$questionIndex"
        stopPlay()
        titleView.text = "${p.emoji} ${q.ko}"
        dialogueBox.removeAllViews()
        cards = emptyList()
        if (!force) {
            val cached = readCache(key)?.let { parseDialogue(it) }
            if (cached != null) {
                renderDialogue(p, q, cached.first, cached.second)
                return
            }
        }
        val e = WordExplainer.llmEngine(prefs)
        if (e == null) {
            dialogueBox.addView(note("⚠ 대화 예문을 만들려면 LLM이 필요해요.", Color.rgb(207, 34, 46)))
            return
        }
        dialogueBox.addView(note("대화 예문을 만드는 중… 30초~1분 정도 걸릴 수 있어요. (${WordExplainer.label(e)})"))
        val name = englishName[l.code] ?: l.label
        val system = "You write realistic practice dialogues for Korean travelers. Write a natural spoken $name " +
            "dialogue of 20 to 30 short lines, about 2 to 3 minutes when spoken, between the traveler (A) and " +
            "${p.role} (B) at ${p.en}. The traveler is polite; the other person speaks naturally, including the " +
            "questions they typically ask and one small realistic complication. Reply with JSON only, no code " +
            "fences: {\"lines\":[{\"who\":\"A\",\"text\":\"$name line\",\"ko\":\"Korean translation\"}]," +
            "\"tips\":\"one or two short Korean tips about local customs or useful points\"}"
        val user = "Situation: ${q.ko} — ${q.phrase}"
        val token = ++loadToken
        net.execute {
            val r: Any = try { Tutor.send(prefs, e, system, listOf("user" to user)) } catch (ex: Exception) { ex }
            post {
                if (token != loadToken) return@post
                val raw = r as? String
                val parsed = raw?.let { parseDialogue(it) }
                dialogueBox.removeAllViews()
                if (raw == null || parsed == null) {
                    dialogueBox.addView(note("만들지 못했어요: " + ((r as? Exception)?.message ?: "형식을 읽지 못했어요")))
                    dialogueBox.addView(button("다시 시도") { loadDialogue(true) })
                    return@post
                }
                writeCache(key, raw)
                renderDialogue(p, q, parsed.first, parsed.second)
            }
        }
    }

    private fun baseColor(line: Line) = if (line.me) Color.argb(25, 47, 91, 234) else Color.argb(14, 0, 0, 0)

    private fun renderDialogue(p: Place, q: Question, ls: List<Line>, tips: String) {
        lines = ls
        dialogueBox.removeAllViews()
        val play = button("▶ 전체 듣기") { if (playing) stopPlay() else playAll() }
        playButton = play
        val practice = button("🎭 이 상황으로 연습") {
            stopPlay()
            onRolePlay(lang, "${p.en} — ${q.ko} (${q.phrase})", p.role, "${p.emoji} ${p.ko} · ${q.ko}")
        }
        dialogueBox.addView(LinearLayout(activity).apply {
            orientation = HORIZONTAL
            addView(play, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(practice, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        })
        dialogueBox.addView(note("참고용 예문이에요. 실제 절차나 표현은 장소마다 다를 수 있어요."))
        cards = ls.mapIndexed { i, line -> lineCard(i, line, p) }
        cards.forEachIndexed { i, card ->
            val me = ls[i].me
            dialogueBox.addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                setMargins(if (me) dp(30) else 0, dp(6), if (me) 0 else dp(30), 0)
            })
        }
        if (tips.isNotBlank()) {
            dialogueBox.addView(TextView(activity).apply {
                text = "💡 $tips"
                textSize = 14f
                setTextColor(Color.DKGRAY)
                setPadding(dp(4), dp(16), dp(4), dp(8))
            })
        }
        dialogueBox.addView(button("대화 다시 만들기") { loadDialogue(true) })
        dialogueScroll.post { dialogueScroll.scrollTo(0, 0) }
    }

    private fun lineCard(i: Int, line: Line, p: Place): View {
        val card = LinearLayout(activity).apply {
            orientation = VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(baseColor(line))
        }
        card.addView(TextView(activity).apply {
            text = if (line.me) "🙋 나" else "🧑‍💼 ${p.staff}"
            textSize = 12f
            setTextColor(Color.GRAY)
        })
        val tv = text(line.text, 19f, Color.BLACK, true)
        card.addView(tv)
        if (line.ko.isNotEmpty()) card.addView(text(line.ko, 14f, blue))
        card.addView(LinearLayout(activity).apply {
            orientation = HORIZONTAL
            addView(action("🔊") {
                stopPlay()
                speak(line, "one")
            })
            addView(action("☆") { v ->
                save(line.text, line.ko)
                v.text = "★"
                v.isEnabled = false
            })
        })
        Pronouncer.attach(activity, card, tv, line.text, lang.code)
        return card
    }

    /* ---------- Playback ---------- */

    private fun speak(line: Line, id: String) {
        val t = tts ?: return
        if (!ttsReady) {
            toast("음성 엔진을 준비하는 중이에요. 잠시 후 다시 눌러주세요.")
            return
        }
        t.language = Locale.forLanguageTag(lang.voice)
        t.setPitch(if (line.me) 1.15f else 0.85f)
        t.setSpeechRate(0.95f)
        t.speak(line.text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    private fun highlight(i: Int) {
        cards.forEachIndexed { k, c -> c.setBackgroundColor(if (k == i) highlightColor else baseColor(lines[k])) }
        if (i in cards.indices) {
            val top = cards[i].top
            dialogueScroll.post { dialogueScroll.smoothScrollTo(0, (top - dp(60)).coerceAtLeast(0)) }
        }
    }

    private fun playAll() {
        if (lines.isEmpty()) return
        if (!ttsReady) {
            toast("음성 엔진을 준비하는 중이에요. 잠시 후 다시 눌러주세요.")
            return
        }
        playing = true
        playIndex = 0
        playButton?.text = "⏹ 멈추기"
        playCurrent()
    }

    private fun playCurrent() {
        if (!playing) return
        if (playIndex >= lines.size) {
            stopPlay()
            return
        }
        highlight(playIndex)
        speak(lines[playIndex], "line_$playIndex")
    }

    private fun lineFinished(id: String?) {
        if (playing && id == "line_$playIndex") {
            playIndex++
            playCurrent()
        }
    }

    private fun stopPlay() {
        val wasPlaying = playing
        playing = false
        if (wasPlaying) tts?.stop()
        if (cards.isNotEmpty()) highlight(-1)
        playButton?.text = "▶ 전체 듣기"
    }

    fun stopPlayback() {
        stopPlay()
        tts?.stop()
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

    override fun onDetachedFromWindow() {
        tts?.shutdown()
        tts = null
        super.onDetachedFromWindow()
    }
}