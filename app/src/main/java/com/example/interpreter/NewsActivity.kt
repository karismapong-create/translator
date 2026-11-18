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
    private var items: List<NewsStore.Item> = emptyList()
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

        dateSpinner = Spinner(this).apply {
            onItemSelectedListener = picked { pos -> dates.getOrNull(pos)?.let { if (it != date) { date = it; showDay() } } }
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

    private fun loadDates() {
        dates = NewsStore.dates(this)
        dateSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, dates.map { label(it) })
        if (date.isEmpty() || date !in dates) date = dates.firstOrNull().orEmpty()
        if (date.isNotEmpty()) dateSpinner.setSelection(dates.indexOf(date))
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
        items = if (date.isEmpty()) emptyList() else NewsStore.load(this, date)
        status.text = if (items.isEmpty()) StudyText.t("no_news") else ""
        renderChips()
        renderList()
    }

    private fun renderChips() {
        chips.removeAllViews()
        val counts = items.groupingBy { it.category }.eachCount()
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
        chips.addView(chip("${StudyText.t("all")} ${items.size}", -1), chipParams())
        StudyText.categories.indices.filter { (counts[it] ?: 0) > 0 }.forEach { i ->
            chips.addView(chip("${StudyText.category(i)} ${counts[i]}", i), chipParams())
        }
    }

    private fun chipParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { marginEnd = dp(6) }

    private fun renderList() {
        list.removeAllViews()
        items.withIndex().filter { category < 0 || it.value.category == category }.forEach { (i, item) ->
            val gloss = TextView(this).apply {
                textSize = 15f
                setTextColor(Ui.INK)
            }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = Ui.pressable(context)
                setOnClickListener { openLesson(i, item) }
                addView(TextView(context).apply {
                    text = (if (item.read) "✔ " else "") + StudyText.category(item.category) +
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

    private fun openLesson(index: Int, item: NewsStore.Item) {
        listPage.visibility = View.GONE
        lessonPage.visibility = View.VISIBLE
        lessonPage.scrollTo(0, 0)
        registerBack()
        lessonBox.removeAllViews()
        val d = date
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
            text = StudyText.category(item.category) + " · " + label(date)
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
        val all = StringBuilder()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = Ui.card(context)
        }
        for (i in 0 until sentences.length()) {
            val s = sentences.optJSONObject(i) ?: continue
            val text = s.optString("text")
            all.append(text).append(' ')
            val tv = TextView(this).apply {
                this.text = text
                textSize = 18f
                setTextColor(Ui.INK)
                setTextIsSelectable(true)
                setPadding(0, dp(8), 0, 0)
                setOnClickListener { say(text, l) }
            }
            body.addView(tv)
            Pronunciation.attach(this, body, tv, text, l.code, home.code)
            s.optString("meaning").takeIf { it.isNotBlank() }?.let {
                body.addView(TextView(this).apply {
                    this.text = it
                    textSize = 14f
                    setTextColor(Ui.PRIMARY)
                })
            }
        }
        lessonBox.addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
        lessonBox.addView(Button(this).apply {
            text = "🔊 " + StudyText.t("all")
            setOnClickListener { say(all.toString().trim(), l) }
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
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(NewsStore.portalUrl(date)))) } catch (_: Exception) {}
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

    /* ---------- Back ---------- */

    private fun closeLesson(): Boolean {
        if (lessonPage.visibility != View.VISIBLE) return false
        SpeechOut.stop(tts)
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
