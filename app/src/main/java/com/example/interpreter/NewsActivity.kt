package com.example.interpreter

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

// Daily world news for vocabulary study: pick a day, language, level and category; each item becomes a
// short article at that level with its key words, ready to listen to and save to the vocabulary list.
class NewsActivity : Activity() {

    private val prefs by lazy { getSharedPreferences("interpreter", MODE_PRIVATE) }
    private val net = Executors.newSingleThreadExecutor()
    private lateinit var home: Lang
    private lateinit var foreignLangs: List<Lang>
    private var lang: Lang = Languages.byCode("en")
    private var level = 1
    private var category = -1
    private var date = ""
    private var dates: List<String> = emptyList()
    // A news item with the day it belongs to (the list can mix days in "last 7 days").
    private class Entry(val date: String, val index: Int, val item: NewsStore.Item)
    private var entries: List<Entry> = emptyList()
    // "" = the last 7 days together.
    private var lessonDate = ""
    private val translators = HashMap<String, Translator>()
    private val glosses = HashMap<String, String>()
    private var tts: TextToSpeech? = null

    private lateinit var dateSpinner: Spinner
    private lateinit var chips: LinearLayout
    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private lateinit var listPage: View
    private lateinit var lessonBox: LinearLayout
    private lateinit var lessonPage: ScrollView
    private var backCallback: Any? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        home = Languages.home(prefs)
        I18n.use(home.code)
        foreignLangs = Languages.all.filter { it.code != home.code }
        lang = foreignLangs.firstOrNull { it.code == prefs.getString("news_lang", null) }
            ?: foreignLangs.firstOrNull { it.code == "en" } ?: foreignLangs.first()
        level = prefs.getInt("news_level", 1)
        tts = TextToSpeech(this) { }
        tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(id: String?) { runOnUiThread { sentenceOf(id)?.let { sentenceStarted(it, id) } } }
            override fun onDone(id: String?) { runOnUiThread { sentenceDone(id) } }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { runOnUiThread { sentenceDone(id) } }
            override fun onStop(id: String?, interrupted: Boolean) { runOnUiThread { sentenceDone(id) } }
            override fun onRangeStart(id: String?, start: Int, end: Int, frame: Int) {
                runOnUiThread {
                    sentenceOf(id)?.let { i ->
                        val base = offsets[id] ?: 0
                        if (playing) wordStart = start + base
                        markWord(i, start + base, end + base, wordColor)
                    }
                }
            }
        })

        dateSpinner = Spinner(this).apply {
            onItemSelectedListener = picked { pos ->
                val d = if (pos == 0) "" else dates.getOrNull(pos - 1) ?: return@picked
                if (d != date || entries.isEmpty()) { date = d; showDay() }
            }
        }
        val refresh = Button(this).apply {
            text = StudyText.t("refresh")
            setOnClickListener { update() }
        }
        val langSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, foreignLangs.map { it.native })
            setSelection(foreignLangs.indexOf(lang))
            onItemSelectedListener = picked { pos ->
                lang = foreignLangs[pos]
                prefs.edit().putString("news_lang", lang.code).apply()
            }
        }
        val levelSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item,
                listOf("level0", "level1", "level2").map { StudyText.t(it) })
            setSelection(level)
            onItemSelectedListener = picked { pos ->
                level = pos
                prefs.edit().putInt("news_level", level).apply()
            }
        }
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(dateSpinner, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(refresh)
        }
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(langSpinner, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(levelSpinner, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        }
        chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        status = TextView(this).apply {
            textSize = 13f
            setTextColor(Ui.INK_SOFT)
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listPage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(row1)
            addView(row2, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
            addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(chips)
                setPadding(0, dp(6), 0, 0)
            })
            addView(status)
            addView(ScrollView(context).apply { addView(list) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        lessonBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), 0, dp(4), dp(24))
        }
        lessonPage = ScrollView(this).apply {
            addView(lessonBox)
            visibility = View.GONE
        }
        val root = android.widget.FrameLayout(this).apply {
            setPadding(dp(16), dp(12), dp(16), dp(12))
            addView(listPage)
            addView(lessonPage)
        }
        setContentView(root)
        Ui.edgeToEdge(this, root)
        Ui.watch(root)

        loadDates()
        update()
    }

    private fun picked(f: (Int) -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = f(pos)
        override fun onNothingSelected(p: AdapterView<*>?) {}
    }

    /* ---------- Days ---------- */

    private fun label(d: String): String {
        val today = NewsStore.recentDates(1).first()
        val (_, mo, da) = d.split("-")
        return "${mo.toInt()}/${da.toInt()}" + if (d == today) " (${StudyText.t("today_tag")})" else ""
    }

    // First option: the last 7 days together (today's page is still short in the Korean evening, as the
    // source is dated in UTC). Then each day with its number of items.
    private fun loadDates() {
        dates = NewsStore.dates(this)
        val counts = dates.associateWith { NewsStore.load(this, it).size }
        val week = dates.take(7).sumOf { counts[it] ?: 0 }
        val labels = listOf(StudyText.f("week", week)) + dates.map { "${label(it)} · ${StudyText.f("count", counts[it] ?: 0)}" }
        dateSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        if (date.isNotEmpty() && date !in dates) date = ""
        dateSpinner.setSelection(if (date.isEmpty()) 0 else dates.indexOf(date) + 1)
        showDay()
    }

    private fun update() {
        status.text = StudyText.t("loading")
        net.execute {
            try { NewsStore.update(this, prefs) } catch (_: Exception) {}
            runOnUiThread { loadDates() }
        }
    }

    private fun showDay() {
        val days = if (date.isEmpty()) dates.take(7) else listOf(date)
        entries = days.flatMap { d -> NewsStore.load(this, d).mapIndexed { i, it -> Entry(d, i, it) } }
        status.text = if (entries.isEmpty()) StudyText.t("no_news") else ""
        renderChips()
        renderList()
    }

    private fun renderChips() {
        chips.removeAllViews()
        val counts = entries.groupingBy { it.item.category }.eachCount()
        fun chip(name: String, id: Int) = TextView(this).apply {
            text = name
            textSize = 13.5f
            val on = category == id
            setTextColor(if (on) Color.WHITE else Ui.PRIMARY_DARK)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = Ui.pressable(context, if (on) Ui.PRIMARY else Ui.PRIMARY_SOFT, 18, 0)
            setOnClickListener {
                category = id
                renderChips()
                renderList()
            }
        }
        chips.addView(chip("${StudyText.t("all")} ${entries.size}", -1), chipParams())
        StudyText.categories.indices.filter { (counts[it] ?: 0) > 0 }.forEach { i ->
            chips.addView(chip("${StudyText.category(i)} ${counts[i]}", i), chipParams())
        }
    }

    private fun chipParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { marginEnd = dp(6) }

    private fun renderList() {
        list.removeAllViews()
        entries.filter { category < 0 || it.item.category == category }.forEach { e ->
            val item = e.item
            val gloss = TextView(this).apply {
                textSize = 15f
                setTextColor(Ui.INK)
            }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = Ui.pressable(context)
                setOnClickListener { openLesson(e) }
                addView(TextView(context).apply {
                    text = (if (item.read) "✔ " else "") + (if (date.isEmpty()) label(e.date) + " · " else "") + StudyText.category(item.category) +
                        if (item.topic.isNotBlank()) " · ${item.topic}" else ""
                    textSize = 12f
                    setTextColor(Ui.PRIMARY)
                })
                addView(gloss)
                addView(TextView(context).apply {
                    text = item.text
                    textSize = 12.5f
                    setTextColor(Ui.INK_SOFT)
                    maxLines = 3
                })
            }
            list.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            })
            // The headline in my language, translated on the phone.
            glosses[item.text]?.let { gloss.text = it } ?: translate(item.text, "en", home.code) {
                glosses[item.text] = it
                gloss.text = it
            }
        }
    }

    private fun translate(text: String, from: String, to: String, done: (String) -> Unit) {
        if (from == to) { done(text); return }
        val key = "$from>$to"
        val t = translators.getOrPut(key) {
            Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(from).setTargetLanguage(to).build())
        }
        t.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener { t.translate(text).addOnSuccessListener { done(it) }.addOnFailureListener { done(text) } }
            .addOnFailureListener { done(text) }
    }

    /* ---------- Lesson ---------- */

    private fun openLesson(e: Entry) {
        val index = e.index
        val item = e.item
        lessonDate = e.date
        listPage.visibility = View.GONE
        lessonPage.visibility = View.VISIBLE
        lessonPage.scrollTo(0, 0)
        registerBack()
        lessonBox.removeAllViews()
        val d = e.date
        val l = lang
        val lv = level
        NewsStore.markRead(this, d, index)
        item.read = true
        val cached = NewsStore.lesson(this, d, index, l.code, lv)
        if (cached != null) {
            renderLesson(item, cached, l)
            return
        }
        val engine = RemoteTranslate.engine(prefs)
        if (!RemoteTranslate.canWrite(engine)) {
            offlineLesson(item, l)
            return
        }
        lessonBox.addView(note(StudyText.f("making", StudyText.t("level$lv"))))
        val spec = when (lv) {
            0 -> "beginner level: 3 short sentences with very common words, present or simple past tense; 5 key words"
            1 -> "intermediate level: 5 sentences with everyday news vocabulary; 7 key words"
            else -> "advanced level: 7 sentences in natural news style; 10 key words including news terms"
        }
        val system = "You are a language teacher writing news lessons for ${home.english} speakers learning ${l.english}. " +
            "Rewrite the news item as a short news article in ${l.english} at $spec. Use only facts in the item; add none. " +
            "Then list the key words from your article. For words in Japanese or Chinese give the reading in kana or " +
            "pinyin, otherwise leave reading empty. Reply with JSON only, no code fences: " +
            "{\"title\":\"${l.english} headline\",\"sentences\":[{\"text\":\"${l.english} sentence\",\"meaning\":\"${home.english} translation\"}]," +
            "\"words\":[{\"word\":\"...\",\"reading\":\"...\",\"meaning\":\"${home.english} meaning\",\"example\":\"sentence from the article\"}]}"
        val user = "Date: $d\nTopic: ${item.topic}\nNews: ${item.text}"
        net.execute {
            val result: Any = try {
                Tutor.json(RemoteTranslate.ask(prefs, engine!!, system, user, 2500)) ?: IllegalStateException("format")
            } catch (e: Exception) { e }
            runOnUiThread {
                if (lessonPage.visibility != View.VISIBLE) return@runOnUiThread
                lessonBox.removeAllViews()
                val o = result as? JSONObject
                if (o == null || (o.optJSONArray("sentences")?.length() ?: 0) == 0) {
                    lessonBox.addView(note(StudyText.f("failed", (result as? Exception)?.message ?: "")))
                    offlineLesson(item, l)
                    return@runOnUiThread
                }
                NewsStore.saveLesson(this, d, index, l.code, lv, o)
                renderLesson(item, o, l)
            }
        }
    }

    // Without an AI engine: the item translated on the phone, no key words.
    private fun offlineLesson(item: NewsStore.Item, l: Lang) {
        lessonBox.addView(note(StudyText.t("need_ai")))
        translate(item.text, "en", l.code) { inLang ->
            translate(item.text, "en", home.code) { mine ->
                if (lessonPage.visibility != View.VISIBLE) return@translate
                val o = JSONObject().put("title", item.topic)
                    .put("sentences", JSONArray().put(JSONObject().put("text", inLang).put("meaning", mine)))
                    .put("words", JSONArray())
                renderLesson(item, o, l)
            }
        }
    }

    private fun note(s: String) = TextView(this).apply {
        text = s
        textSize = 13f
        setTextColor(Ui.INK_SOFT)
        setPadding(0, dp(8), 0, dp(8))
    }

    private fun say(text: String, l: Lang) {
        tts?.let { SpeechOut.speak(it, text, l.speechTag, true, "news") }
    }

    private fun renderLesson(item: NewsStore.Item, o: JSONObject, l: Lang) {
        lessonBox.addView(TextView(this).apply {
            text = StudyText.category(item.category) + " · " + label(lessonDate)
            textSize = 12f
            setTextColor(Ui.PRIMARY)
        })
        o.optString("title").takeIf { it.isNotBlank() }?.let { title ->
            val tv = TextView(this).apply {
                text = title
                textSize = 21f
                setTextColor(Ui.INK)
                Ui.bold(this)
                setTextIsSelectable(true)
                setPadding(0, dp(6), 0, dp(4))
            }
            lessonBox.addView(tv)
        }
        val sentences = o.optJSONArray("sentences") ?: JSONArray()
        sentViews.clear()
        sentTexts.clear()
        lessonWords.clear()
        o.optJSONArray("words")?.let { ws ->
            for (i in 0 until ws.length()) ws.optJSONObject(i)?.let { lessonWords.add(it) }
        }
        playLang = l
        // Play all sits at the top, before the article.
        playButton = Button(this).apply {
            text = TalkText.t("s_play")
            setOnClickListener {
                when {
                    playing -> stopPlay()
                    pausedSentence >= 0 -> resumePlay()
                    else -> playFrom(0)
                }
            }
            Ui.primary(this)
        }
        lessonBox.addView(playButton,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)).apply { bottomMargin = dp(6) })
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = Ui.card(context)
        }
        lessonBox.addView(note(StudyText.t("press_hint")))
        for (i in 0 until sentences.length()) {
            val s = sentences.optJSONObject(i) ?: continue
            val text = s.optString("text")
            val index = sentTexts.size
            sentTexts.add(text)
            val tv = TextView(this).apply {
                this.text = text
                textSize = 18f
                setTextColor(Ui.INK)
                setPadding(dp(6), dp(8), dp(6), dp(2))
            }
            sentViews.add(tv)
            attachInspector(tv, index, l)
            // Each paragraph has its own play button.
            body.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(context).apply {
                    this.text = "🔊"
                    textSize = 17f
                    setTextColor(Ui.PRIMARY)
                    setPadding(dp(8), dp(10), dp(4), dp(4))
                    setOnClickListener {
                        if (playing || pausedSentence >= 0) stopPlay()
                        playFrom(index, 0, index + 1)
                    }
                })
            })
            Pronunciation.attach(this, body, tv, text, l.code, home.code)
            s.optString("meaning").takeIf { it.isNotBlank() }?.let {
                body.addView(TextView(this).apply {
                    this.text = it
                    textSize = 14f
                    setTextColor(Ui.PRIMARY)
                    setPadding(dp(6), 0, dp(6), dp(4))
                })
            }
        }
        lessonBox.addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
        })

        val words = o.optJSONArray("words") ?: JSONArray()
        if (words.length() > 0) {
            lessonBox.addView(TextView(this).apply {
                text = StudyText.t("words")
                textSize = 16f
                setTextColor(Ui.INK)
                Ui.bold(this)
                setPadding(0, dp(16), 0, dp(4))
            })
            for (i in 0 until words.length()) {
                val w = words.optJSONObject(i) ?: continue
                lessonBox.addView(wordRow(w, l), LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) })
            }
            lessonBox.addView(Button(this).apply {
                text = StudyText.t("save_all")
                Ui.primary(this)
                setOnClickListener {
                    var n = 0
                    for (i in 0 until words.length()) {
                        val w = words.optJSONObject(i) ?: continue
                        if (saveWord(w, l)) n++
                    }
                    toast(StudyText.f("saved_n", n))
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(10) })
        }
        lessonBox.addView(note(StudyText.t("source")))
        lessonBox.addView(Button(this).apply {
            text = StudyText.t("open_source")
            setOnClickListener {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(NewsStore.portalUrl(lessonDate)))) } catch (_: Exception) {}
            }
        })
    }

    private fun saveWord(w: JSONObject, l: Lang): Boolean {
        val word = w.optString("word")
        val reading = w.optString("reading")
        val meaning = w.optString("meaning") + if (reading.isNotBlank() && reading != word) " ($reading)" else ""
        return VocabStore.save(prefs, word, meaning, l.code, w.optString("example"))
    }

    private fun wordRow(w: JSONObject, l: Lang): View {
        val word = w.optString("word")
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(8), dp(10))
            background = Ui.rounded(Ui.PRIMARY_SOFT, 14, context)
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val head = TextView(this).apply {
            text = word + w.optString("reading").takeIf { it.isNotBlank() && it != word }?.let { "  $it" }.orEmpty()
            textSize = 17f
            setTextColor(Ui.INK)
            Ui.bold(this)
            setTextIsSelectable(true)
        }
        texts.addView(head)
        texts.addView(TextView(this).apply {
            text = w.optString("meaning")
            textSize = 14f
            setTextColor(Ui.PRIMARY_DARK)
        })
        w.optString("example").takeIf { it.isNotBlank() }?.let {
            texts.addView(TextView(this).apply {
                text = it
                textSize = 13f
                setTextColor(Ui.INK_SOFT)
            })
        }
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(this).apply {
            text = "🔊"
            textSize = 18f
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { say(word, l) }
        })
        row.addView(TextView(this).apply {
            text = "☆"
            textSize = 20f
            setTextColor(Ui.PRIMARY)
            setPadding(dp(8), dp(4), dp(4), dp(4))
            setOnClickListener {
                saveWord(w, l)
                text = "★"
            }
        })
        return row
    }

    /* ---------- Reading along ---------- */

    private val sentViews = ArrayList<TextView>()
    private val sentTexts = ArrayList<String>()
    private val lessonWords = ArrayList<JSONObject>()
    private var playLang: Lang = Languages.byCode("en")
    private var playButton: Button? = null
    private var playing = false
    private val sentenceColor = Color.rgb(255, 241, 204)
    private val wordColor = Color.argb(230, 255, 160, 0)
    private val pickColor = Color.argb(120, 47, 91, 234)

    private fun sentenceOf(id: String?): Int? = id?.takeIf { it.startsWith("ns_") }?.removePrefix("ns_")?.toIntOrNull()

    // Where reading is: the sentence being spoken, the start of the word being spoken, and where an
    // utterance began inside its sentence (a resumed sentence starts mid-way).
    private var current = -1
    private var wordStart = 0
    private var until = 0
    private var pausedSentence = -1
    private var pausedAt = 0
    private val offsets = HashMap<String, Int>()

    // Sentence by sentence, so the one being read can be shown and words lit up as they are spoken.
    // from: start inside the first sentence; end: stop before this sentence.
    private fun playFrom(i: Int, from: Int = 0, end: Int = sentTexts.size) {
        val engine = tts ?: return
        if (i !in sentTexts.indices) return
        playing = true
        pausedSentence = -1
        until = end.coerceAtMost(sentTexts.size)
        playButton?.text = TalkText.t("s_stop")
        for (k in i until until) {
            val start = if (k == i) from.coerceIn(0, sentTexts[k].length) else 0
            val id = "ns_$k"
            offsets[id] = start
            SpeechOut.speak(engine, sentTexts[k].substring(start), playLang.speechTag, k == i, id)
        }
    }

    private fun stopPlay() {
        playing = false
        pausedSentence = -1
        SpeechOut.stop(tts)
        playButton?.text = TalkText.t("s_play")
        markSentence(-1)
    }

    // Stops on the word being spoken and keeps it lit, ready to go on from there.
    private fun pausePlay() {
        if (!playing || current < 0) return
        playing = false
        pausedSentence = current
        pausedAt = wordStart
        SpeechOut.stop(tts)
        playButton?.text = TalkText.t("s_resume")
    }

    private fun resumePlay() {
        val i = pausedSentence
        if (i < 0) playFrom(0) else playFrom(i, pausedAt, until.takeIf { it > i } ?: sentTexts.size)
    }

    // Tapping the sentence being read pauses on its word; tapping it again goes on from that word;
    // tapping another sentence reads from there.
    private fun sentenceTapped(index: Int) {
        when {
            playing && index == current -> pausePlay()
            !playing && index == pausedSentence -> resumePlay()
            else -> {
                if (playing) SpeechOut.stop(tts)
                playFrom(index)
            }
        }
    }

    private fun sentenceStarted(i: Int, id: String?) {
        current = i
        wordStart = offsets[id] ?: 0
        markSentence(i)
    }

    private fun sentenceDone(id: String?) {
        val i = sentenceOf(id) ?: return
        // A pause keeps the sentence and its word lit.
        if (pausedSentence >= 0) return
        sentViews.getOrNull(i)?.let { it.text = sentTexts[i] }
        if (i >= until - 1 || !playing) {
            playing = false
            current = -1
            playButton?.text = TalkText.t("s_play")
            markSentence(-1)
        }
    }

    private fun markSentence(i: Int) {
        sentViews.forEachIndexed { k, v ->
            v.background = if (k == i) Ui.rounded(sentenceColor, 10, this) else null
            if (k != i) v.text = sentTexts[k]
        }
        sentViews.getOrNull(i)?.let { v -> lessonPage.post { followSentence(v) } }
    }

    // Scrolls just enough to keep the sentence being read in view. Its position is added up through
    // its rows and cards (a view's top is only relative to its own parent).
    private fun followSentence(v: View) {
        var y = 0
        var cur: View = v
        while (cur !== lessonBox) {
            y += cur.top
            cur = (cur.parent as? View) ?: return
        }
        val top = lessonPage.scrollY
        val bottom = top + lessonPage.height
        val margin = dp(80)
        if (y - margin < top || y + v.height + margin > bottom) {
            lessonPage.smoothScrollTo(0, (y - margin).coerceAtLeast(0))
        }
    }

    private fun markWord(i: Int, start: Int, end: Int, color: Int) {
        val v = sentViews.getOrNull(i) ?: return
        val text = sentTexts[i]
        if (start < 0 || end <= start || end > text.length) return
        val span = android.text.SpannableString(text)
        span.setSpan(android.text.style.BackgroundColorSpan(color), start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        v.text = span
    }

    // The word around a character position (dictionary-based breaks, so it also works for Japanese and Chinese).
    private fun wordAt(text: String, offset: Int, l: Lang): Pair<Int, Int>? {
        if (text.isEmpty()) return null
        val o = offset.coerceIn(0, text.length - 1)
        val it = java.text.BreakIterator.getWordInstance(java.util.Locale.forLanguageTag(l.speechTag))
        it.setText(text)
        val end = it.following(o).let { e -> if (e == java.text.BreakIterator.DONE) text.length else e }
        val start = it.previous().let { b -> if (b == java.text.BreakIterator.DONE) 0 else b }
        val w = text.substring(start, end)
        return if (w.isBlank() || w.all { c -> !Character.isLetterOrDigit(c) }) null else start to end
    }

    // Long-press a word: its meaning appears. Keep the finger down and slide: each word under the finger
    // lights up and is spoken, and the meaning follows. A short tap reads the sentence from there.
    private fun attachInspector(tv: TextView, index: Int, l: Lang) {
        var inspecting = false
        var last: Pair<Int, Int>? = null
        var downX = 0f
        var downY = 0f
        val longPress = Runnable {
            inspecting = true
            tv.parent?.requestDisallowInterceptTouchEvent(true)
            tv.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            last = null
            inspect(tv, index, l, downX, downY) { last = it }
        }
        tv.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = e.x; downY = e.y
                    inspecting = false
                    tv.postDelayed(longPress, android.view.ViewConfiguration.getLongPressTimeout().toLong())
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (inspecting) {
                        val w = rangeAt(tv, index, l, e.x, e.y)
                        if (w != null && w != last) {
                            last = w
                            inspect(tv, index, l, e.x, e.y) { }
                        }
                    } else if (Math.abs(e.x - downX) > dp(10) || Math.abs(e.y - downY) > dp(10)) {
                        tv.removeCallbacks(longPress)
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    tv.removeCallbacks(longPress)
                    if (!inspecting) {
                        sentenceTapped(index)
                    } else {
                        tv.parent?.requestDisallowInterceptTouchEvent(false)
                        tv.text = sentTexts[index]
                    }
                    inspecting = false
                    true
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    tv.removeCallbacks(longPress)
                    if (inspecting) tv.text = sentTexts[index]
                    inspecting = false
                    true
                }
                else -> false
            }
        }
    }

    private fun rangeAt(tv: TextView, index: Int, l: Lang, x: Float, y: Float): Pair<Int, Int>? {
        val offset = tv.getOffsetForPosition(x, y)
        if (offset < 0) return null
        return wordAt(sentTexts[index], offset, l)
    }

    private fun inspect(tv: TextView, index: Int, l: Lang, x: Float, y: Float, picked: (Pair<Int, Int>) -> Unit) {
        val w = rangeAt(tv, index, l, x, y) ?: return
        picked(w)
        if (playing) stopPlay()
        markWord(index, w.first, w.second, pickColor)
        val word = sentTexts[index].substring(w.first, w.second)
        tts?.let { SpeechOut.speak(it, word, l.speechTag, true, "nw") }
        val loc = IntArray(2)
        tv.getLocationOnScreen(loc)
        showMeaning(word, l, loc[0] + x.toInt(), loc[1] + y.toInt())
    }

    private var popup: android.widget.PopupWindow? = null
    private var popupWord: TextView? = null
    private var popupMeaning: TextView? = null
    private var popupSave: TextView? = null
    private val meanings = HashMap<String, String>()

    // A small card above the finger with the word, its meaning and a save button.
    private fun showMeaning(word: String, l: Lang, x: Int, y: Int) {
        if (popup == null) {
            val w = TextView(this).apply { textSize = 18f; setTextColor(Color.WHITE); Ui.bold(this) }
            val m = TextView(this).apply { textSize = 15f; setTextColor(Color.rgb(220, 228, 255)) }
            val save = TextView(this).apply {
                textSize = 14f
                setTextColor(Ui.ACCENT)
                setPadding(0, dp(6), 0, 0)
            }
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = Ui.rounded(Color.rgb(26, 31, 54), 14, context)
                addView(w); addView(m); addView(save)
            }
            popup = android.widget.PopupWindow(box, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, false).apply {
                isOutsideTouchable = true
                elevation = dp(6).toFloat()
            }
            popupWord = w; popupMeaning = m; popupSave = save
        }
        popupWord?.text = word
        val known = meanings[word] ?: lessonWords.firstOrNull { it.optString("word") == word || word.contains(it.optString("word")) && it.optString("word").length > 1 }
            ?.let { it.optString("meaning") + it.optString("reading").takeIf { r -> r.isNotBlank() }?.let { r -> " ($r)" }.orEmpty() }
        popupMeaning?.text = known ?: "…"
        if (known == null) translate(word, l.code, home.code) { m ->
            meanings[word] = m
            if (popupWord?.text == word) popupMeaning?.text = m
        }
        popupSave?.text = "☆ " + LiveText.t("to_vocab")
        popupSave?.setOnClickListener {
            val m = meanings[word] ?: popupMeaning?.text?.toString().orEmpty()
            if (VocabStore.save(prefs, word, m, l.code)) toast(StudyText.f("saved_n", 1))
            popupSave?.text = "★ " + LiveText.t("to_vocab")
        }
        val px = (x - dp(70)).coerceAtLeast(dp(8))
        val py = (y - dp(120)).coerceAtLeast(dp(40))
        val p = popup!!
        if (p.isShowing) p.update(px, py, -1, -1)
        else p.showAtLocation(window.decorView, Gravity.NO_GRAVITY, px, py)
    }

    /* ---------- Back ---------- */

    private fun closeLesson(): Boolean {
        if (lessonPage.visibility != View.VISIBLE) return false
        stopPlay()
        popup?.dismiss()
        lessonPage.visibility = View.GONE
        listPage.visibility = View.VISIBLE
        unregisterBack()
        renderList()
        return true
    }

    // Android 13+ delivers the back gesture through a callback; older phones call onBackPressed.
    private fun registerBack() {
        if (Build.VERSION.SDK_INT < 33 || backCallback != null) return
        val cb = android.window.OnBackInvokedCallback { closeLesson() }
        onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
        backCallback = cb
    }

    private fun unregisterBack() {
        if (Build.VERSION.SDK_INT < 33) return
        (backCallback as? android.window.OnBackInvokedCallback)?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        backCallback = null
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!closeLesson()) @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun onDestroy() {
        tts?.shutdown()
        translators.values.forEach { it.close() }
        net.shutdown()
        super.onDestroy()
    }
}
