package com.example.interpreter

import android.app.Activity
import android.app.AlertDialog
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.ActionMode
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
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

    // names: "ko|ja|en|fr|es|de|zh"
    private class Question(val title: String, val phrase: String, val meaning: String)
    private class Line(val who: String, val text: String, val meaning: String) {
        val me: Boolean get() = who.equals("A", true)
    }

    private val places = Places.all
    private val nameOrder = listOf("ko", "ja", "en", "fr", "es", "de", "zh")

    private companion object {
        const val SAVE_WORD = 1001
    }

    private val home: Lang = Languages.home(prefs)
    private val blue = Ui.PRIMARY
    private val highlightColor = Color.rgb(255, 241, 204)
    private val wordColor = Color.argb(230, 255, 160, 0)
    private val density = resources.displayMetrics.density
    private val net = Executors.newSingleThreadExecutor()

    private var place: Place? = null
    private var questions: List<Question> = emptyList()
    private var question: Question? = null
    private var questionIndex = -1
    private var lines: List<Line> = emptyList()
    private var cards: List<View> = emptyList()
    // The sentence text of each line, for highlighting the word being spoken.
    private val lineViews = ArrayList<TextView>()
    private var screen = 0
    private var loadToken = 0

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var playing = false
    private var playIndex = 0
    // Pause point: the line and the start of the word that was being spoken (-1 = not paused).
    private var pausedLine = -1
    private var pausedAt = 0
    // Start of the word currently being spoken, counted in the full line text.
    private var wordAt = 0
    // Where in the line each utterance began, so word positions map back to the full text.
    private val offsets = HashMap<String, Int>()
    private var playButton: Button? = null

    private val langSpinner: Spinner
    private val titleView: TextView
    private val gridScroll: ScrollView
    private val questionsBox: LinearLayout
    private val questionsScroll: ScrollView
    private val dialogueBox: LinearLayout
    private val dialogueScroll: ScrollView

    private val lang: LangInfo get() = langs[langSpinner.selectedItemPosition.coerceAtLeast(0)]

    init {
        orientation = VERTICAL

        titleView = TextView(activity).apply {
            textSize = 18f
            setTextColor(Ui.INK)
            Ui.bold(this)
            setPadding(dp(6), 0, dp(6), 0)
            setOnLongClickListener {
                if (screen == 0) {
                    ContentBuilder.openDialog(
                        activity, prefs,
                        places.map { ContentBuilder.Place(it.names.split("|")[2], it.en, it.role) }
                    )
                }
                true
            }
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
            addView(titleView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(langSpinner, LayoutParams(dp(140), LayoutParams.WRAP_CONTENT))
        }

        val grid = LinearLayout(activity).apply { orientation = VERTICAL }
        grid.addView(note(TalkText.t("s_help")))
        places.chunked(3).forEach { row ->
            val rowLayout = LinearLayout(activity).apply { orientation = HORIZONTAL }
            row.forEach { p ->
                rowLayout.addView(placeButton(p), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(4), dp(4), dp(4), dp(4))
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
            override fun onStart(utteranceId: String?) { post { lineStarted(utteranceId) } }
            override fun onDone(utteranceId: String?) { post { lineFinished(utteranceId) } }
            override fun onError(utteranceId: String?) { post { lineFinished(utteranceId) } }
            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                post { wordHighlight(utteranceId, start, end) }
            }
        })
        net.execute {
            BasePack.prepare(activity)
            // Content made here by earlier versions moves into the phrasebook DB once.
            try { ContentBuilder.migratePhoneCache(activity) } catch (_: Exception) {}
        }
        show(0)
    }

    /* ---------- Small helpers ---------- */

    private fun dp(v: Int) = (v * density).toInt()
    private fun toast(msg: String) = Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
    private fun englishName(l: LangInfo) = Languages.byCode(l.code).english

    private fun placeName(p: Place): String {
        val parts = p.names.split("|")
        return parts.getOrNull(nameOrder.indexOf(home.code).coerceAtLeast(0)) ?: parts[2]
    }

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

    // A place tile: icon in a soft circle above the name, on a white card.
    private fun placeButton(p: Place) = TextView(activity).apply {
        val res = Ui.iconRes(p.emoji)
        text = if (res != null) placeName(p) else "${p.emoji}\n${placeName(p)}"
        textSize = 13.5f
        setTextColor(Ui.INK)
        gravity = Gravity.CENTER
        maxLines = 2
        setPadding(dp(6), dp(14), dp(6), dp(12))
        if (res != null) {
            setCompoundDrawables(null, Ui.circleIcon(activity, res, 48), null, null)
            compoundDrawablePadding = dp(8)
        }
        background = Ui.pressable(activity)
        setOnClickListener { openPlace(p) }
    }

    /* ---------- Screens ---------- */

    private fun show(n: Int) {
        if (n != 2) stopPlay()
        screen = n
        gridScroll.visibility = if (n == 0) View.VISIBLE else View.GONE
        questionsScroll.visibility = if (n == 1) View.VISIBLE else View.GONE
        dialogueScroll.visibility = if (n == 2) View.VISIBLE else View.GONE
        if (n == 0) titleView.text = TalkText.t("s_where")
    }

    private fun goBack() {
        loadToken++
        if (screen == 2) show(1) else show(0)
    }

    // Phone back button: dialogue → questions → places. Returns false on the places screen.
    fun back(): Boolean {
        if (screen == 0) return false
        goBack()
        return true
    }

    private fun reloadForLanguage() {
        stopPlay()
        if (screen >= 1 && place != null) {
            show(1)
            loadQuestions(false)
        }
    }

    /* ---------- Questions ---------- */

    private fun openPlace(p: Place) {
        place = p
        show(1)
        loadQuestions(false)
    }

    private fun parseQuestions(raw: String): List<Question>? {
        val arr = Tutor.json(raw)?.optJSONArray("questions") ?: return null
        val code = lang.code
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let {
                val phrase = it.optString("phrase")
                val meaning = it.optString("phrase_ko")
                // Sentence and meaning swapped by the AI: put them back.
                if (!LangCheck.looks(phrase, code) && LangCheck.looks(meaning, code) && LangCheck.looks(phrase, home.code))
                    Question(it.optString("ko"), meaning, phrase)
                else Question(it.optString("ko"), phrase, meaning)
            }
        }.filter { it.phrase.isNotBlank() }
    }

    // One store: the phrasebook DB (shipped, built, imported, or made here). Missing → ask the AI and
    // save the answer into the DB, so the builder's progress and export always match this screen.
    private fun loadQuestions(force: Boolean) {
        val p = place ?: return
        val l = lang
        val placeIdx = places.indexOf(p)
        titleView.text = "${p.emoji} ${placeName(p)}"
        questionsBox.removeAllViews()
        val token = ++loadToken
        if (force) {
            generateQuestions(p, l, placeIdx, true, token)
            return
        }
        net.execute {
            val base = BasePack.questions(activity, l.code, placeIdx, home.code)?.let { parseQuestions(it) }
            post {
                if (token != loadToken) return@post
                if (!base.isNullOrEmpty()) renderQuestions(base)
                else generateQuestions(p, l, placeIdx, false, token)
            }
        }
    }

    private fun generateQuestions(p: Place, l: LangInfo, placeIdx: Int, force: Boolean, token: Int) {
        val e = WordExplainer.llmEngine(prefs)
        if (e == null) {
            questionsBox.addView(note(TalkText.t("s_need_llm"), Color.rgb(207, 34, 46)))
            return
        }
        questionsBox.addView(note(TalkText.f("s_making_q", placeName(p), WordExplainer.label(e))))
        val name = englishName(l)
        val mine = home.english
        val system = "You write travel phrasebooks for $mine speakers. For the given place, list the 10 situations " +
            "travelers most often need to ask or say there, ordered from most common. For each, give a short $mine " +
            "title and the single most useful natural $name sentence. Reply with JSON only, no code fences: " +
            "{\"questions\":[{\"ko\":\"short $mine title\",\"phrase\":\"$name sentence\"," +
            "\"phrase_ko\":\"$mine meaning of the sentence\"}]}"
        val user = "Place: ${placeName(p)} (${p.en}). Language: $name."
        net.execute {
            val r: Any = try { Tutor.send(prefs, e, system, listOf("user" to user)) } catch (ex: Exception) { ex }
            post {
                if (token != loadToken) return@post
                val raw = r as? String
                val qs = raw?.let { parseQuestions(it) }
                questionsBox.removeAllViews()
                if (raw == null || qs.isNullOrEmpty()) {
                    questionsBox.addView(note(TalkText.f("s_failed", (r as? Exception)?.message ?: TalkText.t("s_format"))))
                    questionsBox.addView(button(TalkText.t("retry")) { loadQuestions(true) })
                    return@post
                }
                // Saved into the DB (a hand-remade list replaces the old one and its dialogues).
                net.execute { ContentBuilder.setQuestions(activity, l.code, placeIdx, home.code, raw, replace = true) }
                renderQuestions(qs)
            }
        }
    }

    private fun renderQuestions(qs: List<Question>) {
        questions = qs
        questionsBox.removeAllViews()
        questionsBox.addView(note(TalkText.t("s_q_help")))
        qs.forEachIndexed { i, q ->
            val row = LinearLayout(activity).apply {
                orientation = VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background = Ui.pressable(activity)
                setOnClickListener { openDialogue(i) }
                addView(TextView(activity).apply {
                    text = "${i + 1}. ${q.title}"
                    textSize = 16f
                    setTextColor(Ui.INK)
                    Ui.bold(this)
                })
                addView(TextView(activity).apply {
                    text = q.phrase
                    textSize = 15f
                    setTextColor(Ui.INK_SOFT)
                    setPadding(0, dp(2), 0, 0)
                })
                if (q.meaning.isNotEmpty()) addView(TextView(activity).apply {
                    text = q.meaning
                    textSize = 13f
                    setTextColor(blue)
                })
            }
            questionsBox.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, dp(4), 0, dp(4))
            })
        }
        questionsBox.addView(button(TalkText.t("s_regen_list")) { loadQuestions(true) })
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
        val index = questionIndex
        stopPlay()
        titleView.text = "${p.emoji} ${q.title}"
        dialogueBox.removeAllViews()
        cards = emptyList()
        lineViews.clear()
        val token = ++loadToken
        if (force) {
            generateDialogue(p, q, l, token)
            return
        }
        net.execute {
            val base = BasePack.dialogue(activity, l.code, placeIdx, index, home.code)?.let { parseDialogue(it) }
            post {
                if (token != loadToken) return@post
                if (base != null) renderDialogue(p, q, base.first, base.second)
                else generateDialogue(p, q, l, token)
            }
        }
    }

    // Saves the remade dialogue's key sentence and refreshes the question list behind it.
    private fun updatePhrase(p: Place, l: LangInfo, raw: String) {
        val o = Tutor.json(raw) ?: return
        val phrase = o.optString("phrase").trim()
        val i = questionIndex
        val old = questions.getOrNull(i) ?: return
        if (phrase.isEmpty() || phrase == old.phrase) return
        val meaning = o.optString("meaning").trim()
        val placeIdx = places.indexOf(p)
        net.execute { ContentBuilder.setPhrase(activity, l.code, placeIdx, i, home.code, phrase, meaning) }
        val updated = Question(old.title, phrase, meaning)
        question = updated
        renderQuestions(questions.toMutableList().also { it[i] = updated })
    }

    // A made dialogue is usable when its lines are in the foreign language and the meanings in mine;
    // lines with the two swapped are put right.
    private fun checkDialogue(lines: List<Line>, l: LangInfo): List<Line>? {
        var bad = 0
        val fixed = lines.map { x ->
            when {
                LangCheck.looks(x.text, l.code) && (x.meaning.isEmpty() || LangCheck.looks(x.meaning, home.code)) -> x
                LangCheck.looks(x.meaning, l.code) && LangCheck.looks(x.text, home.code) -> Line(x.who, x.meaning, x.text)
                else -> { bad++; x }
            }
        }
        return if (bad <= maxOf(1, lines.size / 10)) fixed else null
    }

    private fun generateDialogue(p: Place, q: Question, l: LangInfo, token: Int, attempt: Int = 1) {
        val qi = questionIndex
        val e = WordExplainer.llmEngine(prefs)
        if (e == null) {
            dialogueBox.addView(note(TalkText.t("s_need_llm"), Color.rgb(207, 34, 46)))
            return
        }
        dialogueBox.addView(note(TalkText.f("s_making_d", WordExplainer.label(e))))
        val name = englishName(l)
        val mine = home.english
        val system = "You write realistic practice dialogues for $mine-speaking travelers. Write a natural spoken " +
            "$name dialogue of 20 to 30 short lines, about 2 to 3 minutes when spoken, between the traveler (A) and " +
            "${p.role} (B) at ${p.en}. The traveler is polite; the other person speaks naturally, including the " +
            "questions they typically ask and one small realistic complication. Reply with JSON only, no code " +
            "fences: {\"phrase\":\"the traveler's single most useful $name sentence in this dialogue, copied from " +
            "one of A's lines\",\"meaning\":\"$mine meaning of that sentence\"," +
            "\"lines\":[{\"who\":\"A\",\"text\":\"$name line\",\"ko\":\"$mine translation\"}]," +
            "\"tips\":\"one or two short $mine tips about local customs or useful points\"}"
        val user = "Situation: ${q.title} — ${q.phrase}"
        net.execute {
            val r: Any = try { Tutor.send(prefs, e, system, listOf("user" to user)) } catch (ex: Exception) { ex }
            post {
                if (token != loadToken) return@post
                val raw = r as? String
                val parsed = raw?.let { parseDialogue(it) }
                val checked = parsed?.let { checkDialogue(it.first, l) }
                if (parsed != null && checked == null && attempt < 2) {
                    // Mixed-up languages: ask once more before giving up.
                    generateDialogue(p, q, l, token, attempt + 1)
                    return@post
                }
                dialogueBox.removeAllViews()
                if (raw == null || parsed == null || checked == null) {
                    dialogueBox.addView(note(TalkText.f("s_failed", (r as? Exception)?.message ?: TalkText.t("s_format"))))
                    dialogueBox.addView(button(TalkText.t("retry")) { loadDialogue(true) })
                    return@post
                }
                // Saved into the DB for this question (replacing an older dialogue), before the key
                // sentence update below, which runs after it on the same thread.
                val placeIdx = places.indexOf(p)
                val phrase = q.phrase
                net.execute { ContentBuilder.adoptDialogue(activity, l.code, placeIdx, qi, home.code, raw, phrase) }
                updatePhrase(p, l, raw)
                renderDialogue(p, question ?: q, checked, parsed.second)
            }
        }
    }

    private fun baseColor(line: Line) = if (line.me) Ui.PRIMARY_SOFT else Ui.SURFACE

    // Chat-style bubble: my lines soft blue, the other side white with a hairline border.
    private fun bubble(fill: Int, line: Line) =
        Ui.rounded(fill, 18, activity, if (line.me || fill != Ui.SURFACE) 0 else Ui.LINE)

    private fun renderDialogue(p: Place, q: Question, ls: List<Line>, tips: String) {
        lines = ls
        dialogueBox.removeAllViews()
        val play = button(TalkText.t("s_play")) {
            when {
                playing -> pausePlay()
                pausedLine >= 0 -> resumePlay()
                else -> playAll()
            }
        }
        playButton = play
        Ui.primary(play)
        val practice = button(TalkText.t("s_practice")) {
            stopPlay()
            onRolePlay(lang, "${p.en} — ${q.title} (${q.phrase})", p.role, "${p.emoji} ${placeName(p)} · ${q.title}")
        }
        dialogueBox.addView(LinearLayout(activity).apply {
            orientation = HORIZONTAL
            addView(play, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(practice, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        })
        dialogueBox.addView(note(TalkText.t("s_tap_hint")))
        dialogueBox.addView(note(TalkText.t("s_disclaimer")))
        lineViews.clear()
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
                setTextColor(Color.rgb(122, 82, 0))
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = Ui.rounded(Color.rgb(255, 247, 228), 16, activity)
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        }
        dialogueBox.addView(button(TalkText.t("s_regen_d")) { loadDialogue(true) })
        dialogueScroll.post { dialogueScroll.scrollTo(0, 0) }
    }

    private fun lineCard(i: Int, line: Line, p: Place): View {
        val card = LinearLayout(activity).apply {
            orientation = VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = bubble(baseColor(line), line)
            setOnClickListener { lineTapped(i) }
            setOnLongClickListener {
                openWordPicker(line)
                true
            }
        }
        card.addView(TextView(activity).apply {
            text = when {
                line.me -> TalkText.t("s_me")
                home.code == "ko" -> "🧑‍💼 ${p.staff}"
                else -> TalkText.t("s_other")
            }
            textSize = 12f
            setTextColor(Color.GRAY)
        })
        // Not selectable, so a tap anywhere on the line reaches the card.
        val tv = text(line.text, 19f, Color.BLACK, true).apply { setTextIsSelectable(false) }
        card.addView(tv)
        lineViews.add(tv)
        if (line.meaning.isNotEmpty()) card.addView(text(line.meaning, 14f, blue).apply { setTextIsSelectable(false) })
        card.addView(LinearLayout(activity).apply {
            orientation = HORIZONTAL
            addView(action("🔊") {
                stopPlay()
                speak(line, "one_$i")
            })
            addView(action("☆") { v ->
                save(line.text, line.meaning)
                v.text = "★"
                v.isEnabled = false
            })
        })
        Pronunciation.attach(activity, card, tv, line.text, lang.code, home.code)
        return card
    }

    /* ---------- Playback ---------- */

    private fun speak(line: Line, id: String, from: Int = 0) {
        val t = tts ?: return
        if (!ttsReady) {
            toast(TalkText.t("tts_wait"))
            return
        }
        val start = from.coerceIn(0, line.text.length)
        offsets[id] = start
        SpeechOut.speak(t, line.text.substring(start), lang.voice, true, id, alt = !line.me)
    }

    private fun highlight(i: Int) {
        cards.forEachIndexed { k, c -> c.background = bubble(if (k == i) highlightColor else baseColor(lines[k]), lines[k]) }
        if (i in cards.indices) {
            val top = cards[i].top
            dialogueScroll.post { dialogueScroll.smoothScrollTo(0, (top - dp(60)).coerceAtLeast(0)) }
        }
    }

    private fun playAll() = playFrom(0)

    private fun playFrom(i: Int, from: Int = 0) {
        if (i !in lines.indices) return
        if (!ttsReady) {
            toast(TalkText.t("tts_wait"))
            return
        }
        if (pausedLine >= 0 && pausedLine != i) lines.getOrNull(pausedLine)?.let { lineViews.getOrNull(pausedLine)?.text = it.text }
        pausedLine = -1
        playing = true
        playIndex = i
        playButton?.text = TalkText.t("s_stop")
        // Keeps playing with the screen off; the notification can stop it.
        PlaybackService.onStopRequested = { post { stopPlay() } }
        place?.let { p -> PlaybackService.start(activity, "${placeName(p)} · ${question?.title.orEmpty()}") }
        playCurrent(from)
    }

    // Tapping a line while playing pauses on the word being spoken. Tapping the paused line resumes
    // from that word; tapping another line plays from the start of that line.
    private fun lineTapped(i: Int) {
        when {
            playing -> pausePlay()
            i == pausedLine -> resumePlay()
            else -> playFrom(i)
        }
    }

    // Stops the voice but keeps the line and the word highlighted, remembering where to continue.
    private fun pausePlay() {
        if (!playing) return
        playing = false
        pausedLine = playIndex
        pausedAt = wordAt
        PlaybackService.stop(activity)
        SpeechOut.stop(tts)
        highlight(pausedLine)
        playButton?.text = TalkText.t("s_resume")
    }

    private fun resumePlay() {
        val i = pausedLine
        if (i !in lines.indices) {
            playAll()
            return
        }
        playFrom(i, pausedAt)
    }

    private fun playCurrent(from: Int = 0) {
        if (!playing) return
        if (playIndex >= lines.size) {
            stopPlay()
            return
        }
        // The line lights up when its sound actually starts (lineStarted), not now: the start may
        // wait for the audio output to wake, and the picture must not run ahead of the voice.
        wordAt = from
        speak(lines[playIndex], "line_$playIndex", from)
    }

    // Line index from an utterance id such as "line_3" or "one_3".
    private fun lineOf(id: String?): Int = id?.substringAfter('_', "")?.toIntOrNull() ?: -1

    // Moves a highlight over the word the voice is saying. Needs a voice engine that reports word positions.
    private fun wordHighlight(id: String?, s0: Int, e0: Int) {
        val i = lineOf(id)
        val base = offsets[id] ?: 0
        val start = s0 + base
        val end = e0 + base
        // A late position report after a pause must not move the paused word.
        if (id == "line_$playIndex") {
            if (!playing) return
            wordAt = start
        }
        val tv = lineViews.getOrNull(i) ?: return
        val text = lines.getOrNull(i)?.text ?: return
        if (start < 0 || end <= start || end > text.length) return
        val span = SpannableString(text)
        span.setSpan(BackgroundColorSpan(wordColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        tv.text = span
    }

    private fun clearWords() {
        lineViews.forEachIndexed { k, v -> lines.getOrNull(k)?.let { v.text = it.text } }
    }

    // Called when the voice really begins a line, so highlight, scroll and sound stay together.
    private fun lineStarted(id: String?) {
        if (playing && id == "line_$playIndex") highlight(playIndex)
    }

    private fun lineFinished(id: String?) {
        val done = lineOf(id)
        lines.getOrNull(done)?.let { lineViews.getOrNull(done)?.text = it.text }
        if (playing && id == "line_$playIndex") {
            playIndex++
            playCurrent()
        }
    }

    private fun stopPlay() {
        val wasPlaying = playing
        playing = false
        pausedLine = -1
        pausedAt = 0
        PlaybackService.stop(activity)
        if (wasPlaying) SpeechOut.stop(tts)
        if (cards.isNotEmpty()) highlight(-1)
        clearWords()
        playButton?.text = TalkText.t("s_play")
    }

    fun stopPlayback() {
        stopPlay()
        SpeechOut.stop(tts)
    }

    /* ---------- Saving words from a line ---------- */

    // Long-press on a line: tap a word to save it, or select a phrase in the sentence.
    private fun openWordPicker(line: Line) {
        if (playing) stopPlay()
        val l = lang
        val box = LinearLayout(activity).apply {
            orientation = VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val sentence = TextView(activity).apply {
            text = line.text
            textSize = 20f
            setTextColor(Color.BLACK)
            setTypeface(null, Typeface.BOLD)
            setTextIsSelectable(true)
        }
        sentence.customSelectionActionModeCallback = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.add(0, SAVE_WORD, 0, I18n.t("save_word"))
                return true
            }
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                if (item.itemId != SAVE_WORD) return false
                val a = minOf(sentence.selectionStart, sentence.selectionEnd).coerceAtLeast(0)
                val b = maxOf(sentence.selectionStart, sentence.selectionEnd).coerceAtMost(sentence.text.length)
                val w = if (b > a) sentence.text.substring(a, b).trim() else ""
                if (w.isNotEmpty()) saveWord(w, line, l)
                mode.finish()
                return true
            }
            override fun onDestroyActionMode(mode: ActionMode) {}
        }
        box.addView(sentence)
        if (line.meaning.isNotEmpty()) box.addView(text(line.meaning, 14f, blue))
        val chipsLabel = note(TalkText.t("w_words"))
        val chips = LinearLayout(activity).apply { orientation = HORIZONTAL }
        box.addView(chipsLabel)
        box.addView(HorizontalScrollView(activity).apply { addView(chips) })
        box.addView(note(TalkText.t("w_phrase")))
        net.execute {
            val words = WordExplainer.candidates(line.text, l.code)
            post {
                if (words.isEmpty()) {
                    chipsLabel.visibility = View.GONE
                    return@post
                }
                words.forEach { w ->
                    chips.addView(TextView(activity).apply {
                        text = w
                        textSize = 16f
                        setTextColor(Ui.INK)
                        setPadding(dp(14), dp(8), dp(14), dp(8))
                        background = Ui.pressable(activity, Ui.PRIMARY_SOFT, 20, 0)
                        setOnClickListener {
                            saveWord(w, line, l)
                            text = "✔ $w"
                            isEnabled = false
                        }
                    }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                        setMargins(0, 0, dp(6), 0)
                    })
                }
            }
        }
        AlertDialog.Builder(activity)
            .setTitle(TalkText.t("w_title"))
            .setView(ScrollView(activity).apply { addView(box) })
            .setPositiveButton(I18n.t("close"), null)
            .show().also { Ui.watch(it) }
    }

    // Saves a word with its meaning in my language, using the line as the example.
    private fun saveWord(word: String, line: Line, l: LangInfo) {
        if (l.code == home.code) {
            storeWord(word, "", line, l)
            return
        }
        val t = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(l.code)
                .setTargetLanguage(home.code)
                .build()
        )
        fun done(meaning: String) {
            storeWord(word, meaning, line, l)
            t.close()
        }
        t.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                t.translate(word)
                    .addOnSuccessListener { done(it) }
                    .addOnFailureListener { done("") }
            }
            .addOnFailureListener { done("") }
    }

    private fun storeWord(word: String, meaning: String, line: Line, l: LangInfo) {
        val arr = try { JSONArray(prefs.getString("saved", "[]")) } catch (_: Exception) { JSONArray() }
        arr.put(
            JSONObject()
                .put("t", word)
                .put("m", meaning)
                .put("e", line.text)
                .put("em", line.meaning)
                .put("v", l.voice)
                .put("l", l.code)
                .put("d", System.currentTimeMillis())
                .put("c", 0)
                .put("w", 0)
        )
        prefs.edit().putString("saved", arr.toString()).apply()
        toast(I18n.f("saved_word", word + if (meaning.isNotEmpty()) " = $meaning" else ""))
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

    override fun onDetachedFromWindow() {
        tts?.shutdown()
        tts = null
        super.onDetachedFromWindow()
    }
}

// A place in the phrasebook. names: "ko|ja|en|fr|es|de|zh".
class Place(val emoji: String, val names: String, val en: String, val role: String, val staff: String)

// The phrasebook places, also used as scenes for live conversation.
object Places {
    val all = listOf(
        Place("✈️", "공항 체크인|空港チェックイン|Airport check-in|Enregistrement à l'aéroport|Facturación en el aeropuerto|Flughafen-Check-in|机场值机", "an airport check-in counter", "an airline check-in agent", "항공사 직원"),
        Place("🛂", "출입국심사대|入国審査|Immigration|Contrôle des passeports|Control de inmigración|Passkontrolle|出入境检查", "immigration passport control", "an immigration officer", "심사관"),
        Place("🧳", "세관|税関|Customs|Douane|Aduana|Zoll|海关", "customs inspection at the airport", "a customs officer", "세관 직원"),
        Place("🛍️", "면세점|免税店|Duty-free shop|Boutique hors taxes|Tienda libre de impuestos|Duty-free-Shop|免税店", "a duty-free shop", "a duty-free shop clerk", "점원"),
        Place("💱", "환전소·은행|両替所・銀行|Exchange & bank|Change et banque|Cambio y banco|Wechselstube & Bank|换汇处·银行", "a currency exchange and bank", "a bank teller", "은행 직원"),
        Place("🚇", "지하철|地下鉄|Subway|Métro|Metro|U-Bahn|地铁", "a subway station", "a station staff member", "역무원"),
        Place("🚌", "버스 정류장|バス停|Bus stop|Arrêt de bus|Parada de autobús|Bushaltestelle|公交车站", "a bus stop and city bus", "a bus driver", "버스 기사"),
        Place("🚆", "기차역|駅|Train station|Gare|Estación de tren|Bahnhof|火车站", "a train station ticket office", "a ticket office clerk", "매표 직원"),
        Place("🚕", "택시|タクシー|Taxi|Taxi|Taxi|Taxi|出租车", "a taxi ride", "a taxi driver", "택시 기사"),
        Place("🚗", "렌터카 업체|レンタカー|Car rental|Location de voiture|Alquiler de coches|Autovermietung|租车公司", "a car rental counter", "a car rental agent", "렌터카 직원"),
        Place("🏨", "호텔|ホテル|Hotel|Hôtel|Hotel|Hotel|酒店", "a hotel front desk", "a hotel front desk clerk", "프런트 직원"),
        Place("🍽️", "음식점|レストラン|Restaurant|Restaurant|Restaurante|Restaurant|餐厅", "a restaurant", "a restaurant server", "점원"),
        Place("☕", "카페|カフェ|Café|Café|Cafetería|Café|咖啡馆", "a café", "a barista", "바리스타"),
        Place("🏪", "편의점|コンビニ|Convenience store|Supérette|Tienda de conveniencia|Kiosk|便利店", "a convenience store", "a convenience store clerk", "점원"),
        Place("🛒", "슈퍼마켓|スーパー|Supermarket|Supermarché|Supermercado|Supermarkt|超市", "a supermarket", "a supermarket staff member", "직원"),
        Place("👕", "옷가게|洋服店|Clothing store|Magasin de vêtements|Tienda de ropa|Bekleidungsgeschäft|服装店", "a clothing store", "a clothing store clerk", "점원"),
        Place("💊", "약국|薬局|Pharmacy|Pharmacie|Farmacia|Apotheke|药店", "a pharmacy", "a pharmacist", "약사"),
        Place("🏥", "병원|病院|Hospital|Hôpital|Hospital|Krankenhaus|医院", "a hospital or clinic, from reception to the doctor", "a hospital receptionist and doctor", "병원 직원"),
        Place("🚻", "화장실 찾기|トイレを探す|Finding a restroom|Trouver des toilettes|Buscar un baño|Toilette finden|找洗手间", "finding and using public restrooms", "a passerby or shop staff member", "현지인"),
        Place("ℹ️", "관광안내소|観光案内所|Tourist information|Office de tourisme|Oficina de turismo|Touristeninformation|游客服务中心", "a tourist information center", "a tourist information staff member", "안내 직원"),
        Place("🏛️", "박물관|博物館|Museum|Musée|Museo|Museum|博物馆", "a museum", "a museum staff member", "직원"),
        Place("🎢", "놀이공원|遊園地|Amusement park|Parc d'attractions|Parque de atracciones|Freizeitpark|游乐园", "an amusement park", "an amusement park staff member", "직원"),
        Place("🏊", "수영장|プール|Swimming pool|Piscine|Piscina|Schwimmbad|游泳池", "a swimming pool", "a pool staff member", "직원"),
        Place("♨️", "온천|温泉|Hot spring|Source chaude|Aguas termales|Therme|温泉", "a hot spring bath", "a hot spring staff member", "직원"),
        Place("🏖️", "해변|ビーチ|Beach|Plage|Playa|Strand|海滩", "a beach with rentals", "a lifeguard or beach rental staff member", "직원"),
        Place("⛰️", "산|山|Mountain|Montagne|Montaña|Berg|山", "a mountain hiking area", "a mountain hut staff member", "산장 직원"),
        Place("🥾", "트레킹 코스|トレッキングコース|Trekking trail|Sentier de randonnée|Ruta de senderismo|Wanderweg|徒步路线", "a trekking trail", "a trail guide", "가이드"),
        Place("🏞️", "호수|湖|Lake|Lac|Lago|See|湖", "a lake with boat rentals", "a boat rental staff member", "직원"),
        Place("👮", "경찰서·분실물|警察・遺失物|Police & lost and found|Police et objets trouvés|Policía y objetos perdidos|Polizei & Fundbüro|警察局·失物招领", "a police station and lost and found office", "a police officer", "경찰관"),
        Place("💇", "미용실|美容院|Hair salon|Salon de coiffure|Peluquería|Friseur|理发店", "a hair salon", "a hairdresser", "미용사")
    )

    private val order = listOf("ko", "ja", "en", "fr", "es", "de", "zh")

    fun name(p: Place, home: String): String {
        val parts = p.names.split("|")
        return parts.getOrNull(order.indexOf(home).coerceAtLeast(0)) ?: parts[2]
    }
}
