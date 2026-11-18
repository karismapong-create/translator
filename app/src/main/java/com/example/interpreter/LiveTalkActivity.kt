package com.example.interpreter

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.View
import android.view.WindowManager
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

// Live conversation between two people.
//  - Face to face: the screen is split, the top half turned upside down for the other person.
//  - Quick, then refined: the on-device translation shows at once, the AI engine's replaces it.
//  - Scene and glossary: the chosen place and the user's terms guide the AI translation.
//  - Reply suggestions: after the other person speaks, tap a ready answer to say it.
//  - Split audio: translations for me in my earbuds, my words for them on the phone speaker.
//  - Review: every conversation is saved; lines can go to the vocabulary list.
//  - Two phones: link a nearby phone so each person uses their own screen and language.
class LiveTalkActivity : Activity() {

    private enum class Side { ME, THEM }

    // One half of the screen: whose it is, their lines, the live caption and their mic.
    private inner class Panel(val side: Side) {
        val header = TextView(this@LiveTalkActivity).apply {
            textSize = 13f
            setTextColor(Ui.INK_SOFT)
            setPadding(dp(4), 0, dp(4), dp(6))
        }
        val lines = LinearLayout(this@LiveTalkActivity).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this@LiveTalkActivity).apply { addView(lines) }
        val live = TextView(this@LiveTalkActivity).apply {
            textSize = 15f
            setTextColor(Ui.INK_SOFT)
            setTypeface(null, Typeface.ITALIC)
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        val chips = LinearLayout(this@LiveTalkActivity).apply { orientation = LinearLayout.HORIZONTAL }
        val chipScroll = HorizontalScrollView(this@LiveTalkActivity).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
            visibility = View.GONE
        }
        val mic = Button(this@LiveTalkActivity).apply {
            textSize = 17f
            setOnClickListener { micTapped(side) }
        }
        val root = LinearLayout(this@LiveTalkActivity).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.card(context)
            setPadding(dp(12), dp(10), dp(12), dp(8))
            addView(header)
            addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(live)
            addView(chipScroll)
            addView(mic, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(60)))
        }

        init {
            Ui.primary(mic)
            mic.text = LiveText.t("speak")
            lines.addView(hintView())
        }

        private fun hintView() = TextView(this@LiveTalkActivity).apply {
            text = LiveText.t(if (side == Side.ME && link?.linked == true) "remote_hint" else "panel_hint")
            textSize = 15f
            setTextColor(Ui.INK_SOFT)
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, dp(24))
            tag = "hint"
        }

        fun reset() {
            lines.removeAllViews()
            lines.addView(hintView())
        }

        // A chat bubble: my own words on the right in blue, the other person's (translated) on the left.
        fun add(text: String, own: Boolean): Bubble {
            lines.findViewWithTag<View>("hint")?.let { lines.removeView(it) }
            val b = Bubble(own)
            b.set(text, "")
            lines.addView(b.root, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(if (own) dp(40) else 0, dp(4), if (own) 0 else dp(40), dp(4)) })
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            return b
        }
    }

    private inner class Bubble(own: Boolean) {
        val main = TextView(this@LiveTalkActivity).apply {
            textSize = if (own) 16f else 20f
            setTextColor(Ui.INK)
            setTextIsSelectable(true)
        }
        val note = TextView(this@LiveTalkActivity).apply {
            textSize = 11.5f
            setTextColor(Ui.INK_SOFT)
            visibility = View.GONE
        }
        val root = LinearLayout(this@LiveTalkActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = Ui.rounded(if (own) Ui.PRIMARY_SOFT else Ui.SURFACE, 18, context, if (own) 0 else Ui.LINE)
            addView(main)
            addView(note)
        }

        fun set(text: String, noteText: String) {
            main.text = text
            note.text = noteText
            note.visibility = if (noteText.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private class Reply(val mine: String, val theirs: String?)

    private val prefs by lazy { getSharedPreferences("interpreter", MODE_PRIVATE) }
    private val net = Executors.newSingleThreadExecutor()
    private lateinit var home: Lang
    private lateinit var partner: Lang
    private var scene: Place? = null
    private var terms: List<Glossary.Term> = emptyList()
    private val translators = HashMap<String, Translator>()
    private val turns = ArrayList<LiveStore.Line>()
    private val started = System.currentTimeMillis()

    private lateinit var them: Panel
    private lateinit var me: Panel
    private lateinit var partnerSpinner: Spinner
    private lateinit var splitButton: Button
    private lateinit var linkButton: Button

    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private var listening: Side? = null
    private var lastPartial = 0L
    private var split = false
    private var link: LiveLink? = null
    private var linkDialog: AlertDialog? = null
    private val found = ArrayList<Pair<String, String>>()
    private var foundAdapter: ArrayAdapter<String>? = null
    private var utterances = 0
    // Speech files waiting to play on a chosen output, in order.
    private val routed = ArrayDeque<Pair<String, Side>>()
    private val routeFiles = HashMap<String, Pair<File, Side>>()
    private var routing = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun langOf(side: Side) = if (side == Side.ME) home else partner
    private fun panelOf(side: Side) = if (side == Side.ME) me else them
    private fun other(side: Side) = if (side == Side.ME) Side.THEM else Side.ME

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        home = Languages.home(prefs)
        I18n.use(home.code)
        val others = Languages.all.filter { it.code != home.code }
        partner = others.firstOrNull { it.code == prefs.getString("live_partner", null) } ?: others.first()
        scene = Places.all.getOrNull(prefs.getInt("live_scene", -1))
        terms = Glossary.parse(Glossary.text(prefs, home.code), home.code)
        split = prefs.getBoolean("live_split", false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        them = Panel(Side.THEM)
        me = Panel(Side.ME)
        // The other person sits across the table, so their half faces them.
        them.root.rotation = 180f

        partnerSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, others.map { it.native })
            setSelection(others.indexOf(partner))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    if (others[pos] == partner) return
                    partner = others[pos]
                    prefs.edit().putString("live_partner", partner.code).apply()
                    refreshHeaders()
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        val sceneSpinner = Spinner(this).apply {
            val names = listOf(LiveText.t("scene_none")) + Places.all.map { Places.name(it, home.code) }
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, names)
            setSelection(Places.all.indexOf(scene) + 1)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    scene = Places.all.getOrNull(pos - 1)
                    prefs.edit().putInt("live_scene", pos - 1).apply()
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        splitButton = Button(this).apply { setOnClickListener { toggleSplit() } }
        linkButton = Button(this).apply {
            text = LiveText.t("link")
            setOnClickListener { if (link?.linked == true) unlink() else startLink() }
        }
        val historyButton = Button(this).apply {
            text = LiveText.t("history")
            setOnClickListener { openHistory() }
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(partnerSpinner, LinearLayout.LayoutParams(dp(140), LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(sceneSpinner, LinearLayout.LayoutParams(dp(170), LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
            addView(splitButton)
            addView(linkButton)
            addView(historyButton)
        }
        val bar = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(controls)
            setPadding(0, dp(6), 0, dp(6))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            addView(them.root, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(me.root, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
        Ui.watch(root)
        refreshHeaders()
        updateSplitButton()

        tts = TextToSpeech(this) { }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { runOnUiThread { synthDone(id) } }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { runOnUiThread { synthDone(id) } }
        })
    }

    private fun refreshHeaders() {
        val linkedName = link?.takeIf { it.linked }?.let { linkedTo }
        them.header.text = "${partner.native} · ${LiveText.t("them")}"
        me.header.text = "${home.native} · ${LiveText.t("me")}" +
            (if (linkedName != null) "  ·  ${LiveText.f("link_ok", linkedName)}" else "")
    }

    /* ---------- Listening ---------- */

    private fun micTapped(side: Side) {
        if (listening == side) {
            recognizer?.stopListening()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        listen(side)
    }

    private fun listen(side: Side) {
        cancelListening()
        SpeechOut.stop(tts)
        AudioRoute.stop()
        listening = side
        val panel = panelOf(side)
        panel.mic.text = LiveText.t("listening")
        panel.live.text = ""
        val lang = langOf(side)
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(b: Bundle?) = partial(side, first(b))
            override fun onResults(b: Bundle?) {
                val text = first(b).trim()
                endListening(side)
                if (text.isNotEmpty()) heard(side, text)
            }
            override fun onError(error: Int) {
                endListening(side)
                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) toast(LiveText.t("mic_perm"))
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
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang.speechTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
            if (Build.VERSION.SDK_INT >= 33) {
                val words = terms.mapNotNull { it.names[lang.code] }
                if (words.isNotEmpty()) putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(words))
            }
        }
        r.startListening(intent)
    }

    private fun first(b: Bundle?) = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    private fun endListening(side: Side) {
        if (listening == side) listening = null
        panelOf(side).mic.text = LiveText.t("speak")
        panelOf(side).live.text = ""
        panelOf(other(side)).live.text = ""
        recognizer?.destroy()
        recognizer = null
    }

    private fun cancelListening() {
        listening?.let { endListening(it) }
    }

    // Shows what is being said, and a rough translation on the other half while they talk.
    private fun partial(side: Side, text: String) {
        if (listening != side) return
        panelOf(side).live.text = text
        val now = System.currentTimeMillis()
        val linked = link?.linked == true
        if (linked || text.isBlank() || now - lastPartial < 700) return
        lastPartial = now
        fast(text, langOf(side), langOf(other(side))) { tr ->
            if (listening == side) panelOf(other(side)).live.text = "$tr…"
        }
    }

    /* ---------- Translating a finished sentence ---------- */

    private fun heard(side: Side, text: String) {
        panelOf(side).add(text, own = true)
        val linked = link?.linked == true
        if (side == Side.ME && linked) {
            // The other phone translates and speaks it in its own language.
            link?.send(JSONObject().put("type", "line").put("text", text).put("lang", home.code))
            turns.add(LiveStore.Line(true, text, home.code, "", partner.code))
            hideChips()
            return
        }
        translateFor(side, text, langOf(side), langOf(other(side)), null)
    }

    // Translates quickly on the phone, then refines with the AI engine if there is one, then speaks.
    private fun translateFor(side: Side, text: String, sl: Lang, tl: Lang, known: String?) {
        val listener = other(side)
        val target = panelOf(listener)
        val bubble = target.add("…", own = false)
        val line = LiveStore.Line(side == Side.ME, text, sl.code, "", tl.code)
        turns.add(line)
        if (side == Side.ME) hideChips()

        if (known != null) {
            bubble.set(known, "")
            record(line, known)
            speakFor(listener, known, tl)
            return
        }
        val engine = RemoteTranslate.engine(prefs)
        val p = Glossary.prepare(text, sl.code, tl.code, terms, home.code)
        fast(p.input, sl, tl) { raw ->
            val quick = p.restore(raw)
            bubble.set(quick, if (engine != null) LiveText.t("refining") else "")
            record(line, quick)
            if (engine == null) {
                speakFor(listener, quick, tl)
                afterTurn(side)
                return@fast
            }
            val context = turns.dropLast(1).takeLast(3).joinToString("\n") { it.said }
            val sceneText = scene?.en.orEmpty()
            net.execute {
                val refined = try {
                    RemoteTranslate.translate(prefs, engine, p.input, sl, tl, context, sceneText)
                } catch (e: Exception) { "" }
                runOnUiThread {
                    val best = if (refined.isNotBlank()) p.restore(refined) else quick
                    bubble.set(best, if (refined.isNotBlank()) "" else LiveText.t("fast"))
                    record(line, best)
                    speakFor(listener, best, tl)
                    afterTurn(side)
                }
            }
        }
    }

    private fun record(line: LiveStore.Line, tr: String) {
        val i = turns.indexOf(line)
        if (i >= 0) turns[i] = LiveStore.Line(line.mine, line.said, line.saidLang, tr, line.trLang)
    }

    // On-device translation (ML Kit); downloads the language pack the first time.
    private fun fast(text: String, sl: Lang, tl: Lang, done: (String) -> Unit) {
        if (sl.code == tl.code) { done(text); return }
        val key = sl.code + ">" + tl.code
        val t = translators.getOrPut(key) {
            Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(sl.code).setTargetLanguage(tl.code).build())
        }
        t.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                t.translate(text).addOnSuccessListener { done(it) }.addOnFailureListener { done(text) }
            }
            .addOnFailureListener { done(text) }
    }

    /* ---------- Reply suggestions ---------- */

    private fun afterTurn(side: Side) {
        if (side == Side.THEM) suggestReplies()
    }

    private fun suggestReplies() {
        val engine = RemoteTranslate.engine(prefs)
        if (!RemoteTranslate.canWrite(engine)) {
            showChips(LiveText.quickReplies().map { Reply(it, null) })
            return
        }
        val talk = turns.takeLast(6).joinToString("\n") { (if (it.mine) "Me: " else "Them: ") + it.said }
        val where = scene?.en?.let { " The conversation takes place at $it." }.orEmpty()
        val system = "You help a traveler answer in a live conversation.$where Suggest 3 short, natural replies " +
            "the traveler could say next, each under 12 words, polite and different from each other. " +
            "Return only a JSON array of objects with keys \"mine\" (in ${home.english}) and \"theirs\" " +
            "(the same reply in ${partner.english})."
        showChips(LiveText.quickReplies().take(2).map { Reply(it, null) })
        net.execute {
            val replies = try {
                parseReplies(RemoteTranslate.ask(prefs, engine!!, system, talk, 400))
            } catch (e: Exception) { emptyList() }
            runOnUiThread { if (replies.isNotEmpty()) showChips(replies + LiveText.quickReplies().take(1).map { Reply(it, null) }) }
        }
    }

    private fun parseReplies(text: String): List<Reply> {
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        val arr = JSONArray(text.substring(start, end + 1))
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val mine = o.optString("mine").trim()
            val theirs = o.optString("theirs").trim()
            if (mine.isEmpty()) null else Reply(mine, theirs.ifEmpty { null })
        }.take(3)
    }

    private fun showChips(replies: List<Reply>) {
        me.chips.removeAllViews()
        replies.forEach { r ->
            me.chips.addView(TextView(this).apply {
                text = r.mine
                textSize = 14.5f
                setTextColor(Ui.PRIMARY_DARK)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = Ui.pressable(context, Ui.PRIMARY_SOFT, 20, 0)
                setOnClickListener { sayReply(r) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, dp(4), dp(6), dp(6))
            })
        }
        me.chipScroll.visibility = if (replies.isEmpty()) View.GONE else View.VISIBLE
        me.chipScroll.post { me.chipScroll.scrollTo(0, 0) }
    }

    private fun hideChips() {
        me.chipScroll.visibility = View.GONE
    }

    private fun sayReply(r: Reply) {
        cancelListening()
        if (link?.linked == true) {
            heard(Side.ME, r.mine)
            return
        }
        me.add(r.mine, own = true)
        translateFor(Side.ME, r.mine, home, partner, r.theirs)
    }

    /* ---------- Speaking ---------- */

    // Speaks a translation for one side. With split audio, my translations go to the earbuds and
    // theirs to the phone speaker.
    private fun speakFor(listener: Side, text: String, lang: Lang) {
        val engine = tts ?: return
        val earbuds = AudioRoute.earbuds(this)
        if (!split || earbuds == null) {
            SpeechOut.speak(engine, text, lang.speechTag, false, "live_${++utterances}")
            return
        }
        val id = "syn_${++utterances}"
        val file = File(cacheDir, "$id.wav")
        SpeechOut.useVoice(engine, lang.speechTag)
        routeFiles[id] = file to listener
        engine.synthesizeToFile(text, Bundle(), file, id)
    }

    private fun synthDone(id: String?) {
        if (id == null || !id.startsWith("syn_")) return
        routed.addLast(id to (routeFiles[id]?.second ?: Side.THEM))
        playNextRouted()
    }

    private fun playNextRouted() {
        if (routing) return
        val (id, listener) = routed.removeFirstOrNull() ?: return
        val file = routeFiles.remove(id)?.first ?: return playNextRouted()
        routing = true
        val device = if (listener == Side.THEM) AudioRoute.speaker(this) else AudioRoute.earbuds(this)
        AudioRoute.play(file, device) {
            runOnUiThread {
                routing = false
                playNextRouted()
            }
        }
    }

    private fun toggleSplit() {
        split = !split
        prefs.edit().putBoolean("live_split", split).apply()
        updateSplitButton()
        if (split) {
            AlertDialog.Builder(this)
                .setMessage(LiveText.t("split_help") +
                    if (AudioRoute.earbuds(this) == null) "\n\n" + LiveText.t("no_earbuds") else "")
                .setPositiveButton(LiveText.t("close"), null)
                .show().also { Ui.watch(it) }
        }
    }

    private fun updateSplitButton() {
        splitButton.setCompoundDrawables(null, null, null, null)
        splitButton.text = LiveText.t(if (split) "split_on" else "split_off")
        splitButton.alpha = if (split) 1f else 0.75f
    }

    /* ---------- Two phones ---------- */

    private var linkedTo: String? = null

    private fun nearbyPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 33 -> arrayOf(
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.NEARBY_WIFI_DEVICES
        )
        Build.VERSION.SDK_INT >= 31 -> arrayOf(
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION
        )
        else -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun startLink() {
        val missing = nearbyPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQ_NEARBY)
            return
        }
        found.clear()
        val name = "${Build.MODEL} · ${home.native}"
        val l = LiveLink(this, name, linkListener)
        link?.stop()
        link = l
        l.start()
        val adapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, ArrayList())
        foundAdapter = adapter
        linkDialog = AlertDialog.Builder(this)
            .setTitle(LiveText.t("link_pick"))
            .setMessage(LiveText.t("link_searching"))
            .setAdapter(adapter) { _, which -> found.getOrNull(which)?.let { l.connect(it.first) } }
            .setNegativeButton(LiveText.t("cancel")) { _, _ -> if (!l.linked) { l.stop(); link = null } }
            .show().also { Ui.watch(it) }
    }

    private val linkListener = object : LiveLink.Listener {
        override fun onFound(id: String, name: String) = runOnUiThread {
            if (found.none { it.first == id }) {
                found.add(id to name)
                foundAdapter?.add(name)
            }
        }
        override fun onLost(id: String) = runOnUiThread {
            val i = found.indexOfFirst { it.first == id }
            if (i >= 0) {
                foundAdapter?.remove(found[i].second)
                found.removeAt(i)
            }
        }
        override fun onConfirm(id: String, name: String, code: String) = runOnUiThread {
            AlertDialog.Builder(this@LiveTalkActivity)
                .setTitle(name)
                .setMessage(LiveText.f("link_confirm", code))
                .setPositiveButton(LiveText.t("link_accept")) { _, _ -> link?.accept(id) }
                .setNegativeButton(LiveText.t("cancel")) { _, _ -> link?.reject(id) }
                .setCancelable(false)
                .show().also { Ui.watch(it) }
            Unit
        }
        override fun onLinked(name: String) = runOnUiThread {
            linkDialog?.dismiss()
            linkedTo = name
            link?.send(JSONObject().put("type", "hello").put("lang", home.code))
            enterLinked(true)
            toast(LiveText.f("link_ok", name))
        }
        override fun onUnlinked() = runOnUiThread {
            enterLinked(false)
            toast(LiveText.t("link_lost"))
        }
        override fun onFailed() = runOnUiThread { toast(LiveText.t("link_fail")) }
        override fun onMessage(msg: JSONObject) = runOnUiThread { received(msg) }
    }

    private fun received(msg: JSONObject) {
        when (msg.optString("type")) {
            "hello" -> {
                val l = Languages.byCode(msg.optString("lang"))
                if (l.code != home.code) {
                    partner = l
                    refreshHeaders()
                }
            }
            "line" -> {
                val text = msg.optString("text")
                val lang = Languages.byCode(msg.optString("lang"))
                if (text.isBlank()) return
                if (lang.code != home.code) partner = lang
                translateFor(Side.THEM, text, lang, home, null)
            }
        }
    }

    // Linked: each person has their own phone, so only my half is shown.
    private fun enterLinked(on: Boolean) {
        if (!on) linkedTo = null
        them.root.visibility = if (on) View.GONE else View.VISIBLE
        partnerSpinner.isEnabled = !on
        linkButton.setCompoundDrawables(null, null, null, null)
        linkButton.text = LiveText.t(if (on) "unlink" else "link")
        refreshHeaders()
        me.reset()
    }

    private fun unlink() {
        link?.stop()
        link = null
        enterLinked(false)
    }

    /* ---------- Review ---------- */

    private fun saveSession() {
        if (turns.isEmpty()) return
        LiveStore.delete(prefs, started)
        LiveStore.save(prefs, LiveStore.Session(started, partner.code, scene?.let { Places.name(it, home.code) }.orEmpty(), turns.toList()))
    }

    private fun openHistory() {
        saveSession()
        val sessions = LiveStore.load(prefs)
        if (sessions.isEmpty()) {
            toast(LiveText.t("review_empty"))
            return
        }
        val fmt = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
        val labels = sessions.map { s ->
            listOf(fmt.format(Date(s.time)), Languages.byCode(s.partnerLang).native, s.scene, "${s.lines.size}")
                .filter { it.isNotBlank() }.joinToString(" · ")
        }
        AlertDialog.Builder(this)
            .setTitle(LiveText.t("history"))
            .setItems(labels.toTypedArray()) { _, which -> openSession(sessions[which]) }
            .setNegativeButton(LiveText.t("close"), null)
            .show().also { Ui.watch(it) }
    }

    private fun openSession(s: LiveStore.Session) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        s.lines.forEach { l ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(8), dp(8))
                background = Ui.rounded(if (l.mine) Ui.PRIMARY_SOFT else Ui.SURFACE, 14, context, if (l.mine) 0 else Ui.LINE)
            }
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(this).apply {
                text = (if (l.mine) LiveText.t("me") else LiveText.t("them")) + " · " + l.said
                textSize = 15f
                setTextColor(Ui.INK)
                setTextIsSelectable(true)
            })
            if (l.tr.isNotBlank()) texts.addView(TextView(this).apply {
                text = l.tr
                textSize = 13.5f
                setTextColor(Ui.INK_SOFT)
                setTextIsSelectable(true)
            })
            row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply {
                text = "☆"
                textSize = 20f
                setTextColor(Ui.PRIMARY)
                setPadding(dp(10), dp(4), dp(4), dp(4))
                setOnClickListener {
                    if (LiveStore.toVocab(prefs, l, home.code)) toast(LiveText.t("saved_one"))
                    text = "★"
                }
            })
            box.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            })
        }
        AlertDialog.Builder(this)
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton(LiveText.t("save_all")) { _, _ ->
                val n = s.lines.count { LiveStore.toVocab(prefs, it, home.code) }
                toast(LiveText.f("saved_n", n))
            }
            .setNeutralButton(LiveText.t("delete")) { _, _ -> LiveStore.delete(prefs, s.time) }
            .setNegativeButton(LiveText.t("close"), null)
            .show().also { Ui.watch(it) }
    }

    /* ---------- Lifecycle ---------- */

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        val ok = results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }
        when (requestCode) {
            REQ_MIC -> if (!ok) toast(LiveText.t("mic_perm"))
            REQ_NEARBY -> if (ok) startLink() else toast(LiveText.t("link_perm"))
        }
    }

    override fun onStop() {
        cancelListening()
        saveSession()
        super.onStop()
    }

    override fun onDestroy() {
        link?.stop()
        AudioRoute.stop()
        recognizer?.destroy()
        tts?.shutdown()
        translators.values.forEach { it.close() }
        net.shutdown()
        super.onDestroy()
    }

    private companion object {
        const val REQ_MIC = 41
        const val REQ_NEARBY = 42
    }
}
