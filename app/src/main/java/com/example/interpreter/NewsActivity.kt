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
    // Opened as "study with books" instead of news.
    private var books = false
    private var currentBook: BookStore.Book? = null

    // Set while a book part is on screen: sentences get a 🎨 button that draws the scene.
    private class Art(val book: BookStore.Book, val section: BookStore.Section, val lang: String, val level: Int)
    private var art: Art? = null
    private val artNet = Executors.newFixedThreadPool(2)
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
    private fun toast(msg: String) = Toast.makeText(this, Ui.plain(msg), Toast.LENGTH_SHORT).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        home = Languages.home(prefs)
        I18n.use(home.code)
        foreignLangs = Languages.all.filter { it.code != home.code }
        lang = foreignLangs.firstOrNull { it.code == prefs.getString("news_lang", null) }
            ?: foreignLangs.firstOrNull { it.code == "en" } ?: foreignLangs.first()
        level = prefs.getInt("news_level", 1)
        books = intent.getBooleanExtra("books", false)
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
                        if (playing) { current = i; wordStart = start + base }
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
                if (books) visibility = View.GONE
            })
            addView(status)
            addView(ScrollView(context).apply { addView(list) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        lessonBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), 0, dp(4), dp(24))
        }
        lessonPage = SeekScroll().apply {
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

        if (books) {
            row1.visibility = View.GONE
            showShelf()
            val fromNote = intent.getIntExtra("book", -1)
            BookStore.shelf(this).firstOrNull { it.id == fromNote }?.let { openBook(it) }
        } else {
            loadDates()
            update()
        }
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
                    text = (if (newsRead(e.date, e.index)) "✔ " else "") + (if (date.isEmpty()) label(e.date) + " · " else "") + StudyText.category(item.category) +
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
        StudyLog.read(this, (item.topic.ifBlank { StudyText.category(item.category) }).take(60))
        item.read = true
        markNewsRead(d, index, l.code, lv)
        lessonToken++
        art = null
        lessonSaver = { o -> NewsStore.saveLesson(this, d, index, l.code, lv, o) }
        val cached = NewsStore.lesson(this, d, index, l.code, lv)
        if (cached != null) {
            renderLesson(cached, l, newsHeader(item)) { newsFooter() }
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
            "Then list the key words and idioms from your article (an idiom or set phrase counts as one item). For words in Japanese or Chinese give the reading in kana or " +
            "pinyin, otherwise leave reading empty. Reply with JSON only, no code fences: " +
"Every \"meaning\" is a faithful, natural translation in standard adult ${home.english}, whatever the level " +
            "(never simplified for the level); translate idioms and phrasal verbs by their sense, not word by word. " +
            politeRule() +
            "{\"title\":\"${l.english} headline\",\"sentences\":[{\"text\":\"${l.english} sentence\",\"meaning\":\"${home.english} translation\"}]," +
            "\"words\":[{\"word\":\"...\",\"reading\":\"...\",\"meaning\":\"${home.english} meaning\",\"example\":\"sentence from the article\"}],"+
            "\"idioms\":[{\"text\":\"idiom, set phrase or phrasal verb exactly as written in one of your sentences\",\"meaning\":\"${home.english} meaning\",\"reading\":\"...\"}]}"
        val user = "Date: $d\nTopic: ${item.topic}\nNews: ${item.text}"
        val token = ++lessonToken
        net.execute {
            val result: Any = try {
                Tutor.json(RemoteTranslate.ask(prefs, engine!!, system, user, 2500))?.also { o ->
                    if ((o.optJSONArray("sentences")?.length() ?: 0) > 0) NewsStore.saveLesson(this, d, index, l.code, lv, o)
                } ?: IllegalStateException("format")
            } catch (e: Exception) { e }
            runOnUiThread {
                if (lessonPage.visibility != View.VISIBLE || token != lessonToken) return@runOnUiThread
                lessonBox.removeAllViews()
                val o = result as? JSONObject
                if (o == null || (o.optJSONArray("sentences")?.length() ?: 0) == 0) {
                    lessonBox.addView(note(StudyText.f("failed", (result as? Exception)?.message ?: "")))
                    offlineLesson(item, l)
                    return@runOnUiThread
                }
                renderLesson(o, l, newsHeader(item)) { newsFooter() }
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
                renderLesson(o, l, newsHeader(item)) { newsFooter() }
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

    // Read marks belong to a language and level: an article read at one level is still new at another.
    private fun newsReadKey(code: String, lv: Int) = "news_read_${code}_$lv"

    private fun newsRead(date: String, index: Int) =
        prefs.getStringSet(newsReadKey(lang.code, level), emptySet())?.contains("$date#$index") == true

    private fun markNewsRead(date: String, index: Int, code: String, lv: Int) {
        val set = HashSet(prefs.getStringSet(newsReadKey(code, lv), emptySet()) ?: emptySet())
        set.add("$date#$index")
        // Keeps the list from growing without end (two months of news).
        if (set.size > 3000) set.retainAll(set.sorted().takeLast(2500).toSet())
        prefs.edit().putStringSet(newsReadKey(code, lv), set).apply()
    }

    private fun newsHeader(item: NewsStore.Item) = StudyText.category(item.category) + " · " + label(lessonDate)

    private fun newsFooter() {
        lessonBox.addView(note(StudyText.t("source")))
        lessonBox.addView(Button(this).apply {
            text = StudyText.t("open_source")
            setOnClickListener {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(NewsStore.portalUrl(lessonDate)))) } catch (_: Exception) {}
            }
        })
    }

    // The lesson on screen and how to keep a corrected meaning in it.
    private var shownLesson: JSONObject? = null
    private val meaningViews = HashMap<Int, TextView>()
    private val reviewNet = Executors.newSingleThreadExecutor()
    private var lessonSaver: ((JSONObject) -> Unit)? = null

    // Long-press on a meaning: correct it here, and optionally fix the same mistake everywhere.
    private fun editMeaning(i: Int, view: TextView) {
        val o = shownLesson ?: return
        val s = o.optJSONArray("sentences")?.optJSONObject(i) ?: return
        val full = android.widget.EditText(this).apply { setText(view.text) }
        val wrong = android.widget.EditText(this).apply { hint = StudyText.t("fix_wrong") }
        val right = android.widget.EditText(this).apply { hint = StudyText.t("fix_right") }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
            addView(full)
            addView(note(StudyText.t("fix_everywhere")))
            addView(wrong); addView(right)
        }
        val src = s.optString("text")
        val reviewLang = playLang
        val status = note("")
        box.addView(status, 0)
        status.visibility = View.GONE
        if (MeaningReview.available(prefs)) box.addView(Button(this).apply {
            text = StudyText.t("review_ai")
            setOnClickListener {
                isEnabled = false
                status.visibility = View.VISIBLE
                status.text = StudyText.t("review_checking")
                val draft = full.text.toString()
                reviewNet.execute {
                    val r = MeaningReview.review(prefs, src, draft, reviewLang, home)
                    runOnUiThread {
                        isEnabled = true
                        if (r != null) { full.setText(r); status.text = StudyText.t("review_done") }
                        else status.text = StudyText.t("review_failed")
                    }
                }
            }
        }, 1)
        android.app.AlertDialog.Builder(this)
            .setTitle(StudyText.t("fix_title"))
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton(StudyText.t("fix_save")) { _, _ ->
                val w = wrong.text.toString().trim()
                val r = right.text.toString().trim()
                if (w.isNotEmpty()) Corrections.add(this, home.code, w, r)
                val text = Corrections.apply(this, home.code, full.text.toString().trim())
                s.put("meaning", text)
                view.text = text
                lessonSaver?.invoke(o)
            }
            .setNegativeButton(I18n.t("close"), null)
            .show().also { Ui.watch(it) }
    }

    // Second check: translations that look wrong (other scripts mixed in, empty, copied) are reviewed
    // again in the background by the phone's AI or the chosen engine, then saved and shown.
    private fun autoReview(o: JSONObject, l: Lang) {
        val sentences = o.optJSONArray("sentences") ?: return
        val h = home
        val bad = (0 until sentences.length()).filter { i ->
            val st = sentences.optJSONObject(i) ?: return@filter false
            meaningViews.containsKey(i) && MeaningReview.suspicious(st.optString("text"), st.optString("meaning"), h.code)
        }
        if (bad.isEmpty()) return
        val canReview = MeaningReview.available(prefs)
        bad.forEach { i ->
            meaningViews[i]?.apply {
                alpha = 0.55f
                if (!canReview) text = StudyText.f("review_flag", text)
            }
        }
        if (!canReview) return
        val saver = lessonSaver
        reviewNet.execute {
            var changed = false
            for (i in bad) {
                val st = sentences.optJSONObject(i) ?: continue
                val fixed = MeaningReview.review(prefs, st.optString("text"), st.optString("meaning"), l, h)
                if (fixed != null) { st.put("meaning", fixed); changed = true }
                runOnUiThread {
                    if (shownLesson !== o) return@runOnUiThread
                    meaningViews[i]?.apply {
                        alpha = 1f
                        text = if (fixed != null) Corrections.apply(context, h.code, fixed)
                        else StudyText.f("review_flag", text)
                    }
                }
            }
            if (changed) saver?.invoke(o)
        }
    }

    // The reader: sentences with every reading tool, then key words, then the footer.
    private fun renderLesson(o: JSONObject, l: Lang, header: String, footer: () -> Unit) {
        shownLesson = o
        lessonBox.addView(TextView(this).apply {
            text = header
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
        meaningViews.clear()
        sentViews.clear()
        artHolders.clear()
        sentTexts.clear()
        wordStarts.clear()
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
        lessonBox.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(playButton, LinearLayout.LayoutParams(0, dp(56), 1f))
            // Speed beside play; a change applies at once from the word being read.
            addView(Ui.speedChip(context) { if (playing) { pausePlay(); resumePlay() } },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) })
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = Ui.card(context)
        }
        lessonBox.addView(note(StudyText.t("press_hint")))
        if (art != null) lessonBox.addView(TextView(this).apply {
            textSize = 14f
            setTextColor(Ui.PRIMARY)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = Ui.pressable(context)
            setOnClickListener { toggleAllArt() }
        }.also { artToggle = it; updateArtToggle() })
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
                if (art != null) addView(TextView(context).apply {
                    this.text = "🎨"
                    textSize = 17f
                    setTextColor(Ui.PRIMARY)
                    setPadding(dp(6), dp(10), dp(4), dp(4))
                    setOnClickListener { artButton(index) }
                })
            })
            Pronunciation.attach(this, body, tv, text, l.code, home.code)
            s.optString("meaning").takeIf { it.isNotBlank() }?.let {
                body.addView(TextView(this).apply {
                    this.text = Corrections.apply(context, home.code, it)
                    textSize = 14f
                    setTextColor(Ui.PRIMARY)
                    setPadding(dp(6), 0, dp(6), dp(4))
                    // A tap pauses or goes on, as on the white space; a long press corrects the meaning.
                    setOnClickListener { togglePlay() }
                    setOnLongClickListener { editMeaning(index, this); true }
                }.also { meaningViews[index] = it })
            }
            // Room for the scene's picture; one drawn before shows again at once.
            if (art != null) {
                val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                body.addView(holder)
                while (artHolders.size < index) artHolders.add(null)
                artHolders.add(holder)
                showSavedArt(index, holder)
            }
        }
        lessonBox.addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
        })
        loadSentUnits(l, o)
        autoReview(o, l)

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
        footer()
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
            text = if (VocabStore.has(prefs, word)) "★" else "☆"
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

    // Utterance ids carry the run they belong to ("ns_<run>_<sentence>"). Reports from a stopped or
    // paused run arrive late; they are ignored so they can't move the position or end the new run.
    private var run = 0
    private fun sentenceOf(id: String?): Int? {
        val parts = id?.takeIf { it.startsWith("ns_") }?.split("_") ?: return null
        if (parts.size != 3 || parts[1].toIntOrNull() != run) return null
        return parts[2].toIntOrNull()
    }

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
        run++
        playing = true
        pausedSentence = -1
        current = i
        wordStart = from.coerceIn(0, sentTexts[i].length)
        until = end.coerceAtMost(sentTexts.size)
        playButton?.text = TalkText.t("s_stop")
        offsets.clear()
        for (k in i until until) {
            val start = if (k == i) from.coerceIn(0, sentTexts[k].length) else 0
            val id = "ns_${run}_$k"
            offsets[id] = start
            SpeechOut.speak(engine, sentTexts[k].substring(start), playLang.speechTag, k == i, id)
        }
    }

    private fun stopPlay() {
        run++
        playing = false
        pausedSentence = -1
        SpeechOut.stop(tts)
        playButton?.text = TalkText.t("s_play")
        markSentence(-1)
    }

    // Stops on the word being spoken and keeps it lit, ready to go on from there.
    private fun pausePlay() {
        if (!playing || current < 0) return
        run++
        playing = false
        pausedSentence = current
        pausedAt = wordStart
        SpeechOut.stop(tts)
        markSentence(current)
        markWordAt(current, pausedAt)
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
        StudyLog.listened(this)
        current = i
        wordStart = offsets[id] ?: 0
        markSentence(i)
    }

    private fun sentenceDone(id: String?) {
        val i = sentenceOf(id) ?: return
        // A pause keeps the sentence and its word lit.
        if (pausedSentence >= 0) return
        sentViews.getOrNull(i)?.let { it.text = baseText(i) }
        if (i >= until - 1 || !playing) {
            playing = false
            current = -1
            playButton?.text = TalkText.t("s_play")
            markSentence(-1)
        }
    }

    /* ---------- Seeking by dragging sideways ---------- */

    // Drag right or left anywhere except on the words of the article's sentences (translations, pictures
    // and white space included): reading jumps forward or back
    // word by word, and starts again from there when the finger lifts.
    private inner class SeekScroll : ScrollView(this@NewsActivity) {
        private var downX = 0f
        private var downY = 0f
        private var onSentence = false
        private var seeking = false
        private var decided = false
        private val slop = android.view.ViewConfiguration.get(this@NewsActivity).scaledTouchSlop

        // Only the sentence's own words count; the white space around and beside them seeks and pauses.
        private fun hitsSentence(ev: android.view.MotionEvent): Boolean {
            val at = IntArray(2)
            return sentViews.any {
                if (!it.isShown) return@any false
                it.getLocationOnScreen(at)
                onText(it, ev.rawX - at[0], ev.rawY - at[1])
            }
        }

        // Returns true once the gesture is a sideways seek.
        private fun watch(ev: android.view.MotionEvent): Boolean {
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = ev.x; downY = ev.y
                    onSentence = hitsSentence(ev)
                    seeking = false
                    decided = false
                }
                android.view.MotionEvent.ACTION_MOVE -> if (!decided && !onSentence && sentTexts.isNotEmpty()) {
                    val dx = Math.abs(ev.x - downX)
                    val dy = Math.abs(ev.y - downY)
                    if (dx > slop * 2 && dx > dy * 1.5f) {
                        decided = true
                        seeking = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                        seekStart()
                    } else if (dy > slop) {
                        decided = true
                    }
                }
            }
            return seeking
        }

        override fun onInterceptTouchEvent(ev: android.view.MotionEvent): Boolean {
            if (watch(ev)) return true
            return super.onInterceptTouchEvent(ev)
        }

        override fun onTouchEvent(ev: android.view.MotionEvent): Boolean {
            if (!seeking) {
                if (watch(ev)) return true
                // A tap outside the sentences pauses where it is reading, or goes on from there.
                if (ev.actionMasked == android.view.MotionEvent.ACTION_UP && !onSentence && !decided &&
                    ev.eventTime - ev.downTime < android.view.ViewConfiguration.getLongPressTimeout() &&
                    Math.abs(ev.x - downX) < slop && Math.abs(ev.y - downY) < slop) {
                    togglePlay()
                }
                return super.onTouchEvent(ev)
            }
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_MOVE -> seekMove(ev.x - downX)
                android.view.MotionEvent.ACTION_UP -> { seeking = false; seekEnd(true) }
                android.view.MotionEvent.ACTION_CANCEL -> { seeking = false; seekEnd(true) }
            }
            return true
        }
    }

    private fun togglePlay() {
        if (sentTexts.isEmpty()) return
        when {
            playing -> { pausePlay(); flash("⏸") }
            pausedSentence >= 0 -> { resumePlay(); flash("▶") }
            else -> { playFrom(0); flash("▶") }
        }
    }

    private var flashView: TextView? = null

    // A big symbol in the middle of the screen for a moment.
    private fun flash(symbol: String) {
        val root = lessonPage.parent as? android.widget.FrameLayout ?: return
        val v = flashView ?: TextView(this).apply {
            textSize = 44f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = Ui.rounded(Color.argb(150, 20, 24, 40), 40, context)
            root.addView(this, android.widget.FrameLayout.LayoutParams(dp(96), dp(96), Gravity.CENTER))
        }.also { flashView = it }
        v.text = symbol
        v.animate().cancel()
        v.alpha = 1f
        v.visibility = View.VISIBLE
        v.animate().alpha(0f).setStartDelay(450).setDuration(350).withEndAction { v.visibility = View.GONE }.start()
    }

    private var seekFrom = 0 to 0
    private var seekAt = 0 to 0
    private val wordStarts = HashMap<Int, List<Int>>()

    // Where each word of a sentence begins.
    private fun starts(i: Int): List<Int> = wordStarts.getOrPut(i) {
        val text = sentTexts[i]
        val it = java.text.BreakIterator.getWordInstance(java.util.Locale.forLanguageTag(playLang.speechTag))
        it.setText(text)
        val out = ArrayList<Int>()
        var a = it.first()
        var b = it.next()
        while (b != java.text.BreakIterator.DONE) {
            if (text.substring(a, b).any { c -> Character.isLetterOrDigit(c) }) out += a
            a = b
            b = it.next()
        }
        if (out.isEmpty()) out += 0
        out
    }

    private fun seekStart() {
        val from = when {
            playing && current >= 0 -> current to wordStart
            pausedSentence >= 0 -> pausedSentence to pausedAt
            current >= 0 -> current to 0
            else -> 0 to 0
        }
        if (playing) {
            run++
            playing = false
            SpeechOut.stop(tts)
        }
        seekFrom = from
        seekAt = from
        lessonPage.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
    }

    // One word per step of finger travel; crosses into the next or previous sentence.
    private fun seekMove(dx: Float) {
        var steps = (dx / dp(14)).toInt()
        var s = seekFrom.first.coerceIn(0, sentTexts.size - 1)
        var list = starts(s)
        var w = list.indexOfLast { it <= seekFrom.second }.coerceAtLeast(0)
        while (steps > 0) {
            if (w < list.size - 1) w++
            else if (s < sentTexts.size - 1) { s++; list = starts(s); w = 0 }
            else break
            steps--
        }
        while (steps < 0) {
            if (w > 0) w--
            else if (s > 0) { s--; list = starts(s); w = list.size - 1 }
            else break
            steps++
        }
        val at = s to list[w]
        if (at == seekAt) return
        seekAt = at
        lessonPage.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        if (current != s) { current = s; markSentence(s) }
        markWordAt(s, list[w])
    }

    private fun seekEnd(play: Boolean) {
        val (s, o) = seekAt
        pausedSentence = s
        pausedAt = o
        if (play) playFrom(s, o, sentTexts.size)
    }

    // Lights the word that begins at a position.
    private fun markWordAt(i: Int, start: Int) {
        val text = sentTexts.getOrNull(i) ?: return
        val w = wordAt(text, start, playLang) ?: return
        markWord(i, w.first, w.second, wordColor)
    }

    private fun markSentence(i: Int) {
        sentViews.forEachIndexed { k, v ->
            v.background = if (k == i) Ui.rounded(sentenceColor, 10, this) else null
            if (k != i) v.text = baseText(k)
        }
        sentViews.getOrNull(i)?.let { v -> lessonPage.post { followSentence(v) } }
    }

    // Scrolls just enough to keep the sentence being read in view. Its position is added up through
    // its rows and cards (a view's top is only relative to its own parent).
    // Position of a view inside the lesson (sum of tops up to the lesson box).
    private fun yInLesson(v: View): Int? {
        var y = 0
        var cur: View = v
        while (cur !== lessonBox) {
            y += cur.top
            cur = (cur.parent as? View) ?: return null
        }
        return y
    }

    // Keeps the sentence being read in view, and its picture with it when there is one: the
    // sentence and its picture are placed together on the screen when they fit.
    private fun followSentence(v: View) {
        val y = yInLesson(v) ?: return
        val top = lessonPage.scrollY
        val height = lessonPage.height
        val margin = dp(16)
        val i = sentViews.indexOf(v)
        val holder = artHolders.getOrNull(i)?.takeIf { it.childCount > 0 && it.getChildAt(0) is android.widget.ImageView }
        val blockEnd = holder?.let { h -> yInLesson(h)?.let { it + h.height } } ?: (y + v.height)
        val block = blockEnd - y
        val target = when {
            // Sentence and picture together: if the whole block isn't on screen, show it from the sentence,
            // or centred when there is room to spare.
            holder != null && block + margin * 2 <= height -> {
                if (y >= top + margin && blockEnd <= top + height - margin) return
                (y - ((height - block) / 2).coerceAtMost(dp(80))).coerceAtLeast(0)
            }
            // Taller than the screen: sentence at the top, picture below it as far as it goes.
            holder != null -> (y - margin).coerceAtLeast(0)
            // No picture: the sentence being read stays in the middle of the screen.
            else -> {
                val t = (y + v.height / 2 - height / 2).coerceAtLeast(0)
                if (Math.abs(t - top) < dp(12)) return
                t
            }
        }
        lessonPage.smoothScrollTo(0, target)
    }

    // Words and idioms of each sentence (by index), found by the app; idioms are tinted green.
    private val sentUnits = HashMap<Int, List<StudyUnits.Unit>>()
    private var unitsGen = 0
    private val unitsNet = Executors.newSingleThreadExecutor()
    private val idiomColor = Color.argb(60, 46, 160, 90)

    private fun baseText(i: Int): CharSequence {
        val text = sentTexts.getOrNull(i) ?: return ""
        val idioms = sentUnits[i]?.filter { it.idiom && it.end <= text.length } ?: return text
        if (idioms.isEmpty()) return text
        val span = android.text.SpannableString(text)
        for (u in idioms) span.setSpan(android.text.style.BackgroundColorSpan(idiomColor), u.start, u.end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return span
    }

    // Idioms that came with the lesson show at once; sentences of older lessons are asked separately.
    private fun loadSentUnits(l: Lang, lesson: JSONObject) {
        val gen = ++unitsGen
        sentUnits.clear()
        val texts = sentTexts.toList()
        val ctx = applicationContext
        val given = lesson.optJSONArray("idioms")
        unitsNet.execute {
            if (given != null) StudyUnits.seed(ctx, l.code, texts, given)
            val first = HashMap<Int, List<StudyUnits.Unit>>()
            texts.forEachIndexed { i, t -> first[i] = StudyUnits.full(ctx, l.code, t, l.speechTag) }
            runOnUiThread { if (gen == unitsGen) { sentUnits.putAll(first); refreshSentences() } }
            val got = try { StudyUnits.fetch(ctx, prefs, l.code, texts) } catch (_: Exception) { false }
            if (!got) return@execute
            val all = HashMap<Int, List<StudyUnits.Unit>>()
            texts.forEachIndexed { i, t -> all[i] = StudyUnits.full(ctx, l.code, t, l.speechTag) }
            runOnUiThread { if (gen == unitsGen) { sentUnits.putAll(all); refreshSentences() } }
        }
    }

    // Redraws sentences that show no reading or pause highlight.
    private fun refreshSentences() {
        sentViews.forEachIndexed { k, v ->
            if (!(playing && k == current) && k != pausedSentence) v.text = baseText(k)
        }
    }

    private fun markWord(i: Int, start: Int, end: Int, color: Int) {
        val v = sentViews.getOrNull(i) ?: return
        val text = sentTexts[i]
        if (start < 0 || end <= start || end > text.length) return
        val span = android.text.SpannableString(baseText(i))
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

    // Whether a point (in the view's own coordinates) is on the text's glyphs, not the empty part of a line.
    private fun onText(tv: TextView, x: Float, y: Float): Boolean {
        val layout = tv.layout ?: return false
        val ly = y - tv.totalPaddingTop
        if (ly < -dp(4) || ly > layout.height + dp(4)) return false
        val line = layout.getLineForVertical(ly.toInt().coerceIn(0, layout.height))
        val lx = x - tv.totalPaddingLeft
        return lx >= layout.getLineLeft(line) - dp(8) && lx <= layout.getLineRight(line) + dp(8)
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
                    // Beside the words: the reader's seek and pause gestures handle it.
                    if (!onText(tv, e.x, e.y)) return@setOnTouchListener false
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
                        tv.text = baseText(index)
                    }
                    inspecting = false
                    true
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    tv.removeCallbacks(longPress)
                    if (inspecting) tv.text = baseText(index)
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
        // An idiom (or a word the app found) is picked as one piece.
        sentUnits[index]?.let { us -> StudyUnits.at(us, offset)?.let { u -> return u.start to u.end } }
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
        val unit = sentUnits[index]?.firstOrNull { it.start == w.first && it.end == w.second }
        showMeaning(word, l, loc[0] + x.toInt(), loc[1] + y.toInt(), unit)
    }

    private var popup: android.widget.PopupWindow? = null
    private var popupWord: TextView? = null
    private var popupMeaning: TextView? = null
    private var popupSave: TextView? = null
    private val meanings = HashMap<String, String>()

    // A small card above the finger with the word, its meaning and a save button.
    private fun showMeaning(word: String, l: Lang, x: Int, y: Int, unit: StudyUnits.Unit? = null) {
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
        popupWord?.text = if (unit?.idiom == true) "${TalkText.t("u_idiom")} · $word" else word
        val fromUnit = unit?.meaning?.takeIf { it.isNotBlank() }?.let { m ->
            m + (unit.reading.takeIf { r -> r.isNotBlank() && r != word }?.let { r -> " ($r)" } ?: "")
        }
        if (fromUnit != null) meanings[word] = fromUnit
        val known = fromUnit ?: meanings[word] ?: lessonWords.firstOrNull { it.optString("word") == word || word.contains(it.optString("word")) && it.optString("word").length > 1 }
            ?.let { it.optString("meaning") + it.optString("reading").takeIf { r -> r.isNotBlank() }?.let { r -> " ($r)" }.orEmpty() }
        popupMeaning?.text = known ?: "…"
        if (known == null) translate(word, l.code, home.code) { m ->
            meanings[word] = m
            if (popupWord?.text?.endsWith(word) == true) popupMeaning?.text = m
        }
        popupSave?.text = (if (VocabStore.has(prefs, word)) "★ " else "☆ ") + LiveText.t("to_vocab")
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

    /* ---------- Books ---------- */

    private fun heading(t: String) = TextView(this).apply {
        text = t
        textSize = 16f
        setTextColor(Ui.INK)
        Ui.bold(this)
        setPadding(dp(4), dp(14), dp(4), dp(6))
    }

    private fun bookRow(b: BookStore.Book, sub: String, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = Ui.pressable(context)
        isClickable = true
        setOnClickListener { onClick() }
        addView(TextView(context).apply {
            text = b.title
            textSize = 16f
            setTextColor(Ui.INK)
            Ui.bold(this)
        })
        addView(TextView(context).apply {
            text = sub
            textSize = 13f
            setTextColor(Ui.INK_SOFT)
        })
    }

    private fun addRow(v: View) {
        list.addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
    }

    // The shelf: my books with progress, a search box, and books to start.
    private fun showShelf(search: String? = null) {
        list.removeAllViews()
        status.text = StudyText.t("books_intro")
        val box = android.widget.EditText(this).apply {
            hint = StudyText.t("books_search")
            setSingleLine(true)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setText(search.orEmpty())
        }
        val go = Button(this).apply { text = "🔍" }
        val doSearch = { showShelf(box.text.toString().trim().ifEmpty { null }) }
        go.setOnClickListener { doSearch() }
        box.setOnEditorActionListener { _, _, _ -> doSearch(); true }
        list.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(box, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(go)
        })
        if (search == null) list.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(context).apply {
                text = StudyText.t("pack_export")
                setOnClickListener { exportPack() }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(context).apply {
                text = StudyText.t("pack_import")
                setOnClickListener { pickPack() }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })
        val mine = BookStore.shelf(this)
        if (search == null && mine.isNotEmpty()) {
            list.addView(heading(StudyText.t("books_mine")))
            for (b in mine) {
                val total = BookStore.sections(this, b.id).size
                val done = BookStore.read(this, b.id, lang.code, level).size
                addRow(bookRow(b, "${b.author} · ${StudyText.f("books_progress", done, total)}") { openBook(b) })
            }
        }
        val waiting = BookStore.queue(this)
        if (search == null && waiting.isNotEmpty()) {
            list.addView(heading(StudyText.t("books_queue")))
            for (b in waiting) addRow(bookRow(b, StudyText.t("books_downloading")) { openBook(b) })
        }
        list.addView(heading(StudyText.t(if (search == null) "books_pick" else "books_found")))
        val shown = HashSet<Int>(mine.map { it.id } + waiting.map { it.id })
        if (search == null) {
            for (b in BookStore.starter) if (shown.add(b.id)) addRow(bookRow(b, b.author) { openBook(b) })
        } else {
            // Close matches first: titles in my language, typos, authors (books on the shelf included).
            val local = BookStore.findLocal(this, search)
            val localShown = HashSet<Int>()
            for (b in local) if (localShown.add(b.id)) addRow(bookRow(b, b.author) { openBook(b) })
            shown.addAll(localShown)
        }
        val more = note(StudyText.t("books_loading"))
        list.addView(more)
        val token = ++shelfToken
        fun searchCatalog(queries: List<String>) {
            net.execute {
                var failed = false
                val found = LinkedHashMap<Int, BookStore.Book>()
                for (q in queries.distinct()) {
                    try { BookStore.catalog(q).forEach { found.putIfAbsent(it.id, it) } } catch (_: Exception) { failed = true }
                    // Loosen a search with no result: its longest word alone.
                    if (q.isNotEmpty() && found.isEmpty() && q.contains(' ')) {
                        val word = q.split(' ').maxByOrNull { it.length }.orEmpty()
                        if (word.length >= 4) try { BookStore.catalog(word).forEach { found.putIfAbsent(it.id, it) } } catch (_: Exception) {}
                    }
                }
                runOnUiThread {
                    if (token != shelfToken || currentBook != null || lessonPage.visibility == View.VISIBLE) return@runOnUiThread
                    if (found.isEmpty() && failed) { more.text = StudyText.t("books_offline"); return@runOnUiThread }
                    list.removeView(more)
                    var n = 0
                    for (b in found.values) if (shown.add(b.id)) { addRow(bookRow(b, b.author) { openBook(b) }); n++ }
                    if (n == 0 && search != null && shown.isEmpty()) list.addView(note(StudyText.t("books_none")))
                    list.addView(note(StudyText.t("books_source")))
                }
            }
        }
        if (search == null) { searchCatalog(listOf("")); return }
        // A search in another script is also put into English (on the phone) for the catalog.
        val latin = search.none { Character.UnicodeScript.of(it.code).let { sc -> sc != Character.UnicodeScript.LATIN && sc != Character.UnicodeScript.COMMON } }
        val author = BookStore.englishAuthor(search)
        if (latin) { searchCatalog(listOfNotNull(search, author)); return }
        val from = when {
            search.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HANGUL } -> "ko"
            search.any { Character.UnicodeScript.of(it.code).let { sc -> sc == Character.UnicodeScript.HIRAGANA || sc == Character.UnicodeScript.KATAKANA } } -> "ja"
            search.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN } -> "zh"
            else -> null
        }
        if (from == null) { searchCatalog(listOfNotNull(author)); return }
        translate(search, from, "en") { en -> searchCatalog(listOfNotNull(en.trim().takeIf { it.isNotEmpty() && it != search }, author)) }
    }

    private var shelfToken = 0

    // Not on the phone yet: downloaded in the background (keeps trying, even with the app closed),
    // and opened by itself when it arrives while I'm still looking.
    private fun openBook(b: BookStore.Book) {
        currentBook = b
        registerBack()
        if (BookStore.has(this, b.id)) { showBook(b); return }
        list.removeAllViews()
        status.text = ""
        list.addView(heading(b.title))
        list.addView(note(StudyText.t("books_downloading")))
        val wait = note("")
        list.addView(wait)
        BookStore.listener = { id, ok ->
            if (currentBook?.id == id && lessonPage.visibility != View.VISIBLE) {
                if (ok) showBook(b) else wait.text = StudyText.t("books_retrying")
            }
        }
        BookStore.enqueue(this, b)
    }

    // A book's sections by chapter, with what I've read; "continue" opens the next one.
    private fun showBook(b: BookStore.Book) {
        list.removeAllViews()
        status.text = ""
        val secs = BookStore.sections(this, b.id)
        val read = BookStore.read(this, b.id, lang.code, level)
        list.addView(TextView(this).apply {
            text = b.title
            textSize = 20f
            setTextColor(Ui.INK)
            Ui.bold(this)
            setPadding(dp(4), dp(4), dp(4), 0)
        })
        list.addView(note(b.author + " · " + StudyText.f("books_progress", read.size, secs.size)))
        if (secs.isEmpty()) {
            list.addView(note(StudyText.t("books_empty")))
            return
        }
        val next = if (read.isEmpty()) 0 else (secs.indices.firstOrNull { it > BookStore.last(this, b.id, lang.code, level) && it !in read }
            ?: secs.indices.firstOrNull { it !in read } ?: 0)
        list.addView(Button(this).apply {
            text = StudyText.f(if (read.isEmpty()) "books_start" else "books_continue", next + 1)
            Ui.primary(this)
            setOnClickListener { openSection(b, next) }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)))
        var lastChapter: String? = null
        for (s in secs) {
            if (s.chapter != lastChapter) {
                lastChapter = s.chapter
                list.addView(heading(s.chapter.ifBlank { "—" }))
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = Ui.pressable(context)
                isClickable = true
                setOnClickListener { openSection(b, s.index) }
                addView(TextView(context).apply {
                    text = (if (s.index in read) "✔ " else "") + StudyText.f("books_part", s.index + 1)
                    textSize = 14f
                    setTextColor(if (s.index in read) Ui.GOOD else Ui.PRIMARY)
                    Ui.bold(this)
                })
                addView(TextView(context).apply {
                    text = s.preview + "…"
                    textSize = 13f
                    setTextColor(Ui.INK_SOFT)
                    maxLines = 2
                })
            }
            addRow(row)
        }
        val del = Button(this).apply {
            text = StudyText.t("books_remove")
            setOnClickListener {
                BookStore.remove(this@NewsActivity, b.id)
                closeBook()
            }
        }
        list.addView(del)
    }

    private fun confirmDeletePart(b: BookStore.Book, s: BookStore.Section) {
        val withArt = android.widget.CheckBox(this).apply {
            text = StudyText.t("books_delete_art")
            isChecked = true
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(StudyText.t("books_delete_part"))
            .setMessage(StudyText.t("books_delete_msg"))
            .setView(LinearLayout(this).apply { setPadding(dp(20), 0, dp(20), 0); addView(withArt) })
            .setPositiveButton(StudyText.t("books_delete_go")) { _, _ ->
                BookStore.deleteLesson(this, b.id, s.index, lang.code, level, withArt.isChecked)
                openSection(b, s.index)
            }
            .setNegativeButton(I18n.t("close"), null)
            .show().also { Ui.watch(it) }
    }

    private fun bookHeader(b: BookStore.Book, s: BookStore.Section) =
        "📚 ${b.title} · ${s.chapter.ifBlank { "" }} · " + StudyText.f("books_part", s.index + 1)

    private fun bookFooter(b: BookStore.Book, s: BookStore.Section) {
        val secs = BookStore.sections(this, b.id)
        // A made part can be removed, to make it again (for example with another model).
        if (BookStore.lesson(this, b.id, s.index, lang.code, level) != null) lessonBox.addView(Button(this).apply {
            text = StudyText.t("books_delete_part")
            setOnClickListener { confirmDeletePart(b, s) }
        })
        // Shown with the phone's translation: the AI lesson can be tried again.
        if (BookStore.lesson(this, b.id, s.index, lang.code, level) == null && RemoteTranslate.canWrite(RemoteTranslate.engine(prefs))) {
            lessonBox.addView(Button(this).apply {
                text = StudyText.t("books_retry")
                setOnClickListener { openSection(b, s.index) }
            })
        }
        lessonBox.addView(note(StudyText.t("books_source")))
        lessonBox.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            if (s.index > 0) addView(Button(context).apply {
                text = StudyText.t("books_prev")
                setOnClickListener { openSection(b, s.index - 1) }
            }, LinearLayout.LayoutParams(0, dp(56), 1f))
            if (s.index < secs.size - 1) addView(Button(context).apply {
                text = StudyText.t("books_next")
                Ui.primary(this)
                setOnClickListener { openSection(b, s.index + 1) }
            }, LinearLayout.LayoutParams(0, dp(56), 1f))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
    }

    // Which lesson is on screen; a slow answer for another one must not replace it.
    private var lessonToken = 0
    private val making = java.util.Collections.synchronizedSet(HashSet<String>())

    // Sentence meanings in the polite register (Korean 존댓말, Japanese です・ます), narration and dialogue alike;
    // without this the engines fall back to the plain written style (한다체, 반말).
    private fun politeRule(): String = when (home.code) {
        "ko" -> "Write every Korean sentence meaning in polite 존댓말 (해요체 or 합니다체, e.g. \"그는 집으로 돌아갔습니다\", " +
            "\"고마워요\"), for narration and dialogue alike; never use 반말 or the plain written 한다체 (\"돌아갔다\", \"고마워\"). "
        "ja" -> "Write every Japanese sentence meaning in polite です・ます form, for narration and dialogue alike; never plain form. "
        else -> "Use a polite, neutral register in the ${home.english} meanings. "
    }

    private fun bookSystem(b: BookStore.Book, l: Lang, lv: Int): String {
        val spec = when {
            lv == 0 -> "beginner graded reader: retell the passage in 6 to 8 short, simple sentences with very common words"
            lv == 1 -> "intermediate graded reader: retell the passage in 10 to 12 sentences with everyday words, keeping the important dialogue"
            l.code == "en" -> "advanced: keep the original English wording exactly, only split it into sentences"
            else -> "advanced: translate the passage faithfully into natural ${l.english}, sentence by sentence"
        }
        val keyWords = when (lv) { 0 -> 5; 1 -> 7; else -> 10 }
        return "You are a language teacher making reading lessons for ${home.english} speakers learning ${l.english}. " +
            "The text is part of the public-domain book \"${b.title}\" by ${b.author}. Write it in ${l.english} as a $spec. " +
            "Keep the story's events and add nothing. Then list $keyWords key words or idioms from your text (an idiom or set phrase counts as one item). " +
            "For Japanese or Chinese words give the reading in kana or pinyin, otherwise leave reading empty. " +
"Every \"meaning\" is a faithful, natural translation in standard adult ${home.english}, whatever the level " +
            "(never simplified for the level); translate idioms and phrasal verbs by their sense, not word by word. " +
            politeRule() +
            "Reply with JSON only, no code fences: {\"title\":\"short ${l.english} title for this part\"," +
            "\"sentences\":[{\"text\":\"${l.english} sentence\",\"meaning\":\"${home.english} translation\"}]," +
            "\"words\":[{\"word\":\"...\",\"reading\":\"...\",\"meaning\":\"${home.english} meaning\",\"example\":\"sentence from your text\"}],"+
            "\"idioms\":[{\"text\":\"idiom, set phrase or phrasal verb exactly as written in one of your sentences\",\"meaning\":\"${home.english} meaning\",\"reading\":\"...\"}]}"
    }

    // Makes and saves a part's lesson. Runs on a background thread; the result is kept even if I left
    // the screen meanwhile, so a part is only ever made once per language and level.
    private fun makeBookLesson(b: BookStore.Book, index: Int, l: Lang, lv: Int): Any {
        BookStore.lesson(this, b.id, index, l.code, lv)?.let { return it }
        val engine = RemoteTranslate.engine(prefs) ?: return IllegalStateException("engine")
        val s = BookStore.sections(this, b.id).getOrNull(index) ?: return IllegalStateException("section")
        val passage = BookStore.sectionText(this, b.id, s)
        // A busy or rate-limited engine (for example right after drawing pictures with the same key)
        // gets two more tries with waits before falling back to the phone's translation.
        var last: Exception = IllegalStateException("format")
        for (wait in listOf(0L, 4000L, 10000L)) {
            if (wait > 0) Thread.sleep(wait)
            try {
                val o = Tutor.json(RemoteTranslate.ask(prefs, engine, bookSystem(b, l, lv), passage, 4000))
                if (o != null && (o.optJSONArray("sentences")?.length() ?: 0) > 0) {
                    BookStore.saveLesson(this, b.id, index, l.code, lv, o)
                    return o
                }
                last = IllegalStateException("format")
            } catch (e: Exception) { last = e }
        }
        return last
    }

    // Gets the next part ready in the background, so "Next" opens at once.
    private fun prefetch(b: BookStore.Book, index: Int, l: Lang, lv: Int) {
        if (index >= BookStore.sections(this, b.id).size) return
        if (!RemoteTranslate.canWrite(RemoteTranslate.engine(prefs))) return
        if (BookStore.lesson(this, b.id, index, l.code, lv) != null) return
        val key = "${b.id}_${index}_${l.code}_$lv"
        if (!making.add(key)) return
        prefetchNet.execute {
            try { makeBookLesson(b, index, l, lv) } finally { making.remove(key) }
        }
    }

    private val prefetchNet = Executors.newSingleThreadExecutor()

    // A section as a lesson in my language and level (AI), or the text with phone translation (offline).
    private fun openSection(b: BookStore.Book, index: Int) {
        val s = BookStore.sections(this, b.id).getOrNull(index) ?: return
        stopPlay()
        popup?.dismiss()
        listPage.visibility = View.GONE
        lessonPage.visibility = View.VISIBLE
        lessonPage.scrollTo(0, 0)
        registerBack()
        lessonBox.removeAllViews()
        BookStore.markRead(this, b.id, index, lang.code, level)
        StudyLog.read(this, b.title + " · " + StudyText.f("books_part", index + 1))
        val token = ++lessonToken
        val l = lang
        val lv = level
        art = Art(b, s, l.code, lv)
        lessonSaver = { o -> BookStore.saveLesson(this, b.id, index, l.code, lv, o) }
        val cached = BookStore.lesson(this, b.id, index, l.code, lv)
        if (cached != null) {
            renderLesson(cached, l, bookHeader(b, s)) { bookFooter(b, s) }
            prefetch(b, index + 1, l, lv)
            return
        }
        val passage = BookStore.sectionText(this, b.id, s)
        if (!RemoteTranslate.canWrite(RemoteTranslate.engine(prefs))) {
            offlineBookLesson(b, s, passage, l)
            return
        }
        lessonBox.addView(note(StudyText.f("books_making", StudyText.t("level$lv"))))
        val key = "${b.id}_${index}_${l.code}_$lv"
        making.add(key)
        net.execute {
            val result = try { makeBookLesson(b, index, l, lv) } finally { making.remove(key) }
            runOnUiThread {
                if (token != lessonToken || lessonPage.visibility != View.VISIBLE) return@runOnUiThread
                lessonBox.removeAllViews()
                val o = result as? JSONObject
                if (o == null) {
                    lessonBox.addView(note(StudyText.f("failed", (result as? Exception)?.message ?: "")))
                    offlineBookLesson(b, s, passage, l)
                    return@runOnUiThread
                }
                renderLesson(o, l, bookHeader(b, s)) { bookFooter(b, s) }
                prefetch(b, index + 1, l, lv)
            }
        }
    }

    // Without an AI engine: the original sentences (translated on the phone for other languages).
    private fun offlineBookLesson(b: BookStore.Book, s: BookStore.Section, passage: String, l: Lang) {
        val token = lessonToken
        lessonBox.addView(note(StudyText.t("need_ai")))
        if (RemoteTranslate.canWrite(RemoteTranslate.engine(prefs))) lessonBox.addView(Button(this).apply {
            text = StudyText.t("books_retry")
            setOnClickListener { openSection(b, s.index) }
        })
        val it = java.text.BreakIterator.getSentenceInstance(java.util.Locale.ENGLISH)
        it.setText(passage)
        val sents = ArrayList<String>()
        var a = it.first()
        var e = it.next()
        while (e != java.text.BreakIterator.DONE) {
            passage.substring(a, e).trim().takeIf { t -> t.isNotEmpty() }?.let { t -> sents += t }
            a = e
            e = it.next()
        }
        val items = sents.take(30)
        val out = arrayOfNulls<JSONObject>(items.size)
        var left = items.size
        if (left == 0) return
        items.forEachIndexed { i, en ->
            translate(en, "en", l.code) { inLang ->
                translate(en, "en", home.code) { mine ->
                    out[i] = JSONObject().put("text", inLang).put("meaning", if (mine == inLang) "" else mine)
                    left--
                    if (left == 0 && lessonPage.visibility == View.VISIBLE && currentBook === b && token == lessonToken) {
                        val o = JSONObject().put("title", s.chapter)
                            .put("sentences", JSONArray().apply { out.forEach { x -> if (x != null) put(x) } })
                            .put("words", JSONArray())
                        lessonBox.removeAllViews()
                        renderLesson(o, l, bookHeader(b, s)) { bookFooter(b, s) }
                    }
                }
            }
        }
    }

    /* ---------- Pictures for book scenes ---------- */

    private val artHolders = ArrayList<LinearLayout?>()
    private val artBusy = java.util.Collections.synchronizedSet(HashSet<String>())

    private fun artFile(a: Art, i: Int) = BookStore.artFile(this, a.book.id, a.section.index, a.lang, a.level, i)

    // Pictures thrown away stay hidden (the file is kept) until the button brings them back.
    private fun hiddenArt(): MutableSet<String> = HashSet(prefs.getStringSet("art_hidden", emptySet()) ?: emptySet())

    private fun setHidden(name: String, hidden: Boolean) {
        val set = hiddenArt()
        if (hidden) set.add(name) else set.remove(name)
        prefs.edit().putStringSet("art_hidden", set).apply()
    }

    private var artToggle: TextView? = null

    private fun visiblePictures() = artHolders.withIndex().filter { (_, h) ->
        h != null && h.childCount > 0 && h.getChildAt(0) is android.widget.ImageView
    }

    // "Hide all pictures" while any is showing, otherwise "show all pictures" (drawn ones of this part).
    private fun updateArtToggle() {
        val t = artToggle ?: return
        t.text = StudyText.t(if (visiblePictures().isNotEmpty()) "art_hide_all" else "art_show_all")
    }

    private fun toggleAllArt() {
        val a = art ?: return
        val shown = visiblePictures()
        if (shown.isNotEmpty()) {
            for ((i, h) in shown) throwAway(i, h!!, h.getChildAt(0), 0f, -1f)
        } else {
            for ((i, h) in artHolders.withIndex()) {
                if (h == null) continue
                val f = artFile(a, i)
                if (f.exists()) { setHidden(f.name, false); showSavedArt(i, h) }
            }
        }
        artToggle?.postDelayed({ updateArtToggle() }, 450)
    }

    // The picture button: draws the scene, hides the picture on screen, or brings a hidden one back.
    private fun artButton(i: Int) {
        val a = art ?: return
        val holder = artHolders.getOrNull(i) ?: return
        val f = artFile(a, i)
        when {
            holder.childCount > 0 && holder.getChildAt(0) is android.widget.ImageView -> throwAway(i, holder, holder.getChildAt(0), 0f, -1f)
            f.exists() -> { setHidden(f.name, false); showSavedArt(i, holder) }
            else -> paint(i, holder, false)
        }
    }

    // Flies the picture off in a direction, then removes it and remembers it as hidden.
    private fun throwAway(i: Int, holder: LinearLayout, v: View, vx: Float, vy: Float) {
        val a = art ?: return
        val len = Math.max(1f, Math.hypot(vx.toDouble(), vy.toDouble()).toFloat())
        val dist = resources.displayMetrics.widthPixels * 1.3f
        v.animate().cancel()
        v.animate()
            .translationXBy(vx / len * dist)
            .translationYBy(vy / len * dist)
            .rotationBy(if (vx >= 0) 25f else -25f)
            .alpha(0f)
            .setDuration(280)
            .withEndAction {
                holder.removeAllViews()
                setHidden(artFile(a, i).name, true)
                updateArtToggle()
            }
            .start()
    }

    private fun showSavedArt(i: Int, holder: LinearLayout) {
        val a = art ?: return
        val f = artFile(a, i)
        if (f.name in hiddenArt()) return
        if (f.exists()) artNet.execute {
            val bmp = android.graphics.BitmapFactory.decodeFile(f.path)
            runOnUiThread { if (bmp != null && art === a) putPicture(i, holder, bmp) }
        }
    }

    private fun putPicture(i: Int, holder: LinearLayout, bmp: android.graphics.Bitmap) {
        holder.removeAllViews()
        holder.post { updateArtToggle() }
        holder.addView(android.widget.ImageView(this).apply {
            setImageBitmap(bmp)
            adjustViewBounds = true
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(14).toFloat())
                }
            }
            // Press and throw it anywhere to hide it; long-press (without moving) draws it again.
            val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
            var downX = 0f
            var downY = 0f
            var dragging = false
            var tracker: android.view.VelocityTracker? = null
            val redraw = Runnable {
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                android.app.AlertDialog.Builder(this@NewsActivity)
                    .setMessage(StudyText.t("art_redraw"))
                    .setPositiveButton(StudyText.t("art_redraw_yes")) { _, _ -> paint(i, holder, true) }
                    .setNegativeButton(I18n.t("close"), null)
                    .show().also { Ui.watch(it) }
            }
            // Option (settings): swiping on a picture skips forward/back like elsewhere (default), or
            // throws the picture away. In skip mode the picture lets the swipe through.
            setOnLongClickListener { redraw.run(); true }
            // A tap on a picture pauses or goes on, like a tap on any empty spot.
            setOnClickListener { togglePlay() }
            setOnTouchListener { v, e ->
                if (!prefs.getBoolean("art_throw", false)) return@setOnTouchListener false
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX; downY = e.rawY
                        dragging = false
                        tracker?.recycle()
                        tracker = android.view.VelocityTracker.obtain().also { it.addMovement(e) }
                        v.postDelayed(redraw, android.view.ViewConfiguration.getLongPressTimeout().toLong())
                        true
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        tracker?.addMovement(e)
                        val dx = e.rawX - downX
                        val dy = e.rawY - downY
                        if (!dragging && Math.hypot(dx.toDouble(), dy.toDouble()) > slop) {
                            dragging = true
                            v.removeCallbacks(redraw)
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                        }
                        if (dragging) {
                            v.translationX = dx
                            v.translationY = dy
                            v.rotation = dx / 40f
                            v.alpha = (1f - Math.hypot(dx.toDouble(), dy.toDouble()).toFloat() / (v.width * 1.5f)).coerceIn(0.3f, 1f)
                        }
                        true
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        v.removeCallbacks(redraw)
                        val t = tracker
                        t?.addMovement(e)
                        t?.computeCurrentVelocity(1000)
                        val vx = t?.xVelocity ?: 0f
                        val vy = t?.yVelocity ?: 0f
                        t?.recycle()
                        tracker = null
                        if (!dragging && e.actionMasked == android.view.MotionEvent.ACTION_UP &&
                            e.eventTime - e.downTime < android.view.ViewConfiguration.getLongPressTimeout()) {
                            togglePlay()
                        }
                        if (dragging) {
                            val dx = e.rawX - downX
                            val dy = e.rawY - downY
                            val fast = Math.hypot(vx.toDouble(), vy.toDouble()) > dp(900)
                            val far = Math.hypot(dx.toDouble(), dy.toDouble()) > v.width * 0.35f
                            if (e.actionMasked == android.view.MotionEvent.ACTION_UP && (fast || far)) {
                                throwAway(i, holder, v, if (fast) vx else dx, if (fast) vy else dy)
                            } else {
                                v.animate().translationX(0f).translationY(0f).rotation(0f).alpha(1f).setDuration(200).start()
                            }
                        }
                        dragging = false
                        true
                    }
                    else -> false
                }
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(6), dp(6), dp(6), dp(10))
        })
    }

    // Draws the scene of one sentence (with the sentences around it for context) and keeps it.
    private fun paint(i: Int, holder: LinearLayout?, again: Boolean) {
        val a = art ?: return
        holder ?: return
        if (!ImageGen.available(prefs)) {
            android.app.AlertDialog.Builder(this).setMessage(StudyText.t("art_need_key")).setPositiveButton(I18n.t("close"), null)
                .show().also { Ui.watch(it) }
            return
        }
        if (!prefs.getBoolean("art_info_seen", false)) {
            android.app.AlertDialog.Builder(this)
                .setMessage(StudyText.t("art_info"))
                .setPositiveButton(StudyText.t("art_go")) { _, _ ->
                    prefs.edit().putBoolean("art_info_seen", true).apply()
                    paint(i, holder, again)
                }
                .setNegativeButton(I18n.t("close"), null)
                .show().also { Ui.watch(it) }
            return
        }
        val f = artFile(a, i)
        if (f.exists() && !again) { showSavedArt(i, holder); return }
        val key = f.name
        if (!artBusy.add(key)) return
        holder.removeAllViews()
        holder.addView(note(StudyText.t("art_drawing")))
        val scene = sentTexts.getOrNull(i).orEmpty()
        val before = sentTexts.getOrNull(i - 1).orEmpty()
        val after = sentTexts.getOrNull(i + 1).orEmpty()
        val prompt = "A single illustration for a scene in the classic public-domain book \"${a.book.title}\" by ${a.book.author}" +
            (a.section.chapter.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") + ". " +
            "Scene to draw: $scene\nContext before: $before\nContext after: $after\n" +
            "Style: gentle hand-painted watercolor storybook illustration, warm soft colors, period-appropriate costumes and " +
            "settings, consistent with the book's era. Absolutely no text, letters, captions, signatures or speech bubbles."
        artNet.execute {
            val result: Any = try { ImageGen.draw(prefs, prompt) } catch (e: Exception) { e }
            val bytes = result as? ByteArray
            if (bytes != null) try { f.writeBytes(bytes); setHidden(f.name, false) } catch (_: Exception) {}
            val bmp = bytes?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
            artBusy.remove(key)
            runOnUiThread {
                if (art !== a) return@runOnUiThread
                if (bmp != null) putPicture(i, holder, bmp)
                else {
                    holder.removeAllViews()
                    // The failure note shows for 5 seconds, then fades away (a tap removes it at once).
                    val msg = note(StudyText.f("art_failed", (result as? Exception)?.message ?: "")).apply {
                        setOnClickListener { holder.removeView(this) }
                    }
                    holder.addView(msg)
                    msg.postDelayed({
                        msg.animate().alpha(0f).setDuration(400).withEndAction { holder.removeView(msg) }.start()
                    }, 5000)
                }
            }
        }
    }

    /* ---------- Content packages ---------- */

    private fun exportPack() = PackUi.export(this) { msg -> status.text = msg }

    private fun pickPack() = startActivity(Intent(this, PackImportActivity::class.java))

    private var studySince = 0L

    override fun onPause() {
        if (studySince > 0) StudyLog.studied(this, (System.currentTimeMillis() - studySince) / 1000)
        studySince = 0L
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        studySince = System.currentTimeMillis()
        // Back from importing a package: show what arrived.
        if (books && currentBook == null && lessonPage.visibility != View.VISIBLE && PackUi.changed) {
            PackUi.changed = false
            showShelf()
        }
    }

    /* ---------- Back ---------- */

    private fun closeLesson(): Boolean {
        if (lessonPage.visibility != View.VISIBLE) return false
        stopPlay()
        popup?.dismiss()
        lessonPage.visibility = View.GONE
        listPage.visibility = View.VISIBLE
        val b = currentBook
        if (books && b != null) showBook(b) else { unregisterBack(); renderList() }
        return true
    }

    private fun closeBook(): Boolean {
        if (!books || currentBook == null) return false
        currentBook = null
        unregisterBack()
        showShelf()
        return true
    }

    private fun goBack(): Boolean = closeLesson() || closeBook()

    // Android 13+ delivers the back gesture through a callback; older phones call onBackPressed.
    private fun registerBack() {
        if (Build.VERSION.SDK_INT < 33 || backCallback != null) return
        val cb = android.window.OnBackInvokedCallback { goBack() }
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
        if (!goBack()) @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun onDestroy() {
        BookStore.listener = null
        tts?.shutdown()
        translators.values.forEach { it.close() }
        net.shutdown()
        super.onDestroy()
    }
}
