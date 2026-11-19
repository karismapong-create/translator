package com.example.interpreter

import android.app.Activity
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

// Daily review with spaced repetition and active recall: the word first (or only its sound in
// listening mode), the user tries to remember, then sees the meaning, its reading, an example and a
// memory hook, and says how well they knew it. Forgotten words come back at the end of the session.
class StudyActivity : Activity() {

    private class Card(
        val index: Int, val text: String, val meaning: String, val example: String,
        val exampleMeaning: String, val voice: String, val code: String, var hook: String
    )

    private val prefs by lazy { getSharedPreferences("interpreter", MODE_PRIVATE) }
    private val net = Executors.newSingleThreadExecutor()
    private lateinit var home: Lang
    private val queue = ArrayDeque<Card>()
    private var card: Card? = null
    private var listening = false
    private var tts: TextToSpeech? = null

    private lateinit var stats: TextView
    private lateinit var modeButton: Button
    private lateinit var body: LinearLayout

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        home = Languages.home(prefs)
        I18n.use(home.code)
        listening = prefs.getBoolean("study_listen", false)
        tts = TextToSpeech(this) { }

        stats = TextView(this).apply {
            textSize = 15f
            setTextColor(Ui.INK)
            Ui.bold(this)
        }
        modeButton = Button(this).apply {
            setOnClickListener {
                listening = !listening
                prefs.edit().putBoolean("study_listen", listening).apply()
                updateMode()
                card?.let { showFront(it) }
            }
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(stats, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(modeButton)
        }
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = Ui.card(context)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(top)
            addView(ScrollView(context).apply { addView(body) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(12) })
        }
        setContentView(root)
        Ui.edgeToEdge(this, root)
        Ui.watch(root)
        updateMode()
        load()
        next()
    }

    private fun updateMode() {
        modeButton.setCompoundDrawables(null, null, null, null)
        modeButton.text = StudyText.t("listen_mode")
        modeButton.alpha = if (listening) 1f else 0.6f
    }

    // Due reviews first (oldest first), then today's share of new words.
    private fun load() {
        val arr = VocabStore.read(prefs)
        val now = System.currentTimeMillis()
        val due = ArrayList<Pair<Long, Card>>()
        val fresh = ArrayList<Card>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val code = o.optString("l").ifEmpty { o.optString("v").substringBefore("-") }
            if (code == home.code || o.optString("m").isBlank()) continue
            val c = Card(i, o.optString("t"), o.optString("m"), o.optString("e"), o.optString("em"),
                o.optString("v", Languages.byCode(code).speechTag), code, o.optString("img"))
            val n = o.optLong("n", 0)
            when {
                n == 0L -> fresh.add(c)
                n <= now -> due.add(n to c)
            }
        }
        due.sortedBy { it.first }.forEach { queue.addLast(it.second) }
        fresh.take(VocabStore.newLeft(prefs)).forEach { queue.addLast(it) }
    }

    private fun updateStats() {
        stats.text = listOf(
            StudyText.f("streak", VocabStore.streak(prefs)),
            StudyText.f("today", VocabStore.doneToday(prefs), VocabStore.goal(prefs)),
            StudyText.f("left", queue.size + if (card != null) 1 else 0)
        ).joinToString("  ·  ")
    }

    private fun next() {
        card = queue.removeFirstOrNull()
        updateStats()
        val c = card
        if (c == null) {
            body.removeAllViews()
            body.addView(text(
                StudyText.t(if (VocabStore.doneToday(prefs) > 0) "done" else "empty"),
                18f, Ui.INK
            ).apply { gravity = Gravity.CENTER; setPadding(0, dp(40), 0, dp(40)) })
            return
        }
        showFront(c)
    }

    private fun text(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        setTextIsSelectable(true)
    }

    private fun say(c: Card) {
        tts?.let { SpeechOut.speak(it, c.text, c.voice, true, "study") }
    }

    private fun showFront(c: Card) {
        body.removeAllViews()
        body.addView(text(StudyText.t(if (listening) "listen_hint" else "recall"), 13f, Ui.INK_SOFT))
        val word = text(c.text, 32f, Ui.INK).apply {
            Ui.bold(this)
            setPadding(0, dp(18), 0, dp(6))
            visibility = if (listening) View.INVISIBLE else View.VISIBLE
        }
        body.addView(word)
        body.addView(Button(this).apply {
            text = "🔊"
            setOnClickListener { say(c) }
        }, LinearLayout.LayoutParams(dp(64), dp(56)))
        body.addView(Button(this).apply {
            text = StudyText.t("show")
            setOnClickListener { showBack(c) }
            Ui.primary(this)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(60)).apply { topMargin = dp(24) })
        if (listening) say(c)
    }

    private fun showBack(c: Card) {
        body.removeAllViews()
        val word = text(c.text, 30f, Ui.INK).apply { Ui.bold(this) }
        body.addView(word)
        Pronunciation.attach(this, body, word, c.text, c.code, home.code)
        body.addView(text(c.meaning, 20f, Ui.PRIMARY).apply { setPadding(0, dp(8), 0, dp(8)) })
        if (c.example.isNotBlank()) {
            body.addView(text(c.example, 15f, Ui.INK))
            if (c.exampleMeaning.isNotBlank()) body.addView(text(c.exampleMeaning, 13.5f, Ui.INK_SOFT))
        }
        body.addView(Button(this).apply {
            text = "🔊"
            setOnClickListener { say(c) }
        }, LinearLayout.LayoutParams(dp(64), dp(56)))
        body.addView(hookView(c))

        val answers = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf(0 to "forgot", 1 to "hard", 2 to "knew").forEach { (k, key) ->
                addView(Button(context).apply {
                    text = StudyText.t(key)
                    if (k == 2) Ui.primary(this)
                    setOnClickListener { answer(c, k) }
                }, LinearLayout.LayoutParams(0, dp(60), 1f))
            }
        }
        body.addView(answers, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(20)
        })
        say(c)
    }

    // A vivid sound-alike scene in my language that links the word to its meaning, made once by the AI.
    private fun hookView(c: Card): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = Ui.rounded(android.graphics.Color.rgb(255, 247, 228), 16, context)
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(14), 0, 0)
            addView(box)
        }
        fun showHook(h: String) {
            box.removeAllViews()
            box.addView(text("✨ $h", 15f, android.graphics.Color.rgb(122, 82, 0)))
        }
        if (c.hook.isNotBlank()) {
            showHook(c.hook)
            return wrap
        }
        val engine = RemoteTranslate.engine(prefs)
        if (!RemoteTranslate.canWrite(engine)) {
            box.addView(text(StudyText.t("hook_need"), 13f, Ui.INK_SOFT))
            return wrap
        }
        box.addView(Button(this).apply {
            text = StudyText.t("hook_btn")
            setOnClickListener {
                isEnabled = false
                text = StudyText.t("hook_wait")
                val system = "You help ${home.english} speakers memorise ${Languages.byCode(c.code).english} words. " +
                    "Write one short, vivid, funny memory hook in ${home.english}: link the sound of the word to a " +
                    "${home.english} word or phrase that sounds similar, and picture a scene that shows the meaning. " +
                    "At most two sentences. Reply with the hook only."
                net.execute {
                    val h = try {
                        RemoteTranslate.ask(prefs, engine!!, system, "Word: ${c.text}\nMeaning: ${c.meaning}", 200)
                    } catch (e: Exception) { "" }
                    runOnUiThread {
                        if (h.isBlank()) {
                            isEnabled = true
                            text = StudyText.t("hook_btn")
                        } else {
                            c.hook = h
                            VocabStore.setHook(prefs, c.index, h)
                            showHook(h)
                        }
                    }
                }
            }
        })
        return wrap
    }

    private fun answer(c: Card, knew: Int) {
        VocabStore.answer(prefs, c.index, knew)
        StudyLog.reviewed(this, c.text, knew > 0)
        // Forgotten words are asked again at the end of this session.
        if (knew == 0) queue.addLast(c)
        next()
    }

    private var studySince = 0L

    override fun onResume() {
        super.onResume()
        studySince = System.currentTimeMillis()
    }

    override fun onPause() {
        if (studySince > 0) StudyLog.studied(this, (System.currentTimeMillis() - studySince) / 1000)
        studySince = 0L
        super.onPause()
    }

    override fun onDestroy() {
        tts?.shutdown()
        net.shutdown()
        super.onDestroy()
    }
}
