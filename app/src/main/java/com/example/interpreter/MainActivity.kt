package com.example.interpreter

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import android.text.InputType
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
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
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

    private data class Lang(val label: String, val speechTag: String, val mlkit: String)
    private data class Term(val names: Map<String, String>, val variants: List<String>)
    private class DeepLException(val code: Int) : Exception("HTTP $code")

    // One translated line on screen.
    private class Seg(val src: String, val voice: String, val view: TextView, val typed: Boolean) {
        var tr: String = ""
    }

    // Source text with word-list terms swapped for placeholder codes, and how to put them back.
    private class Prepared(val shown: String, val input: String, val codes: Map<Regex, String>) {
        fun restore(text: String): String {
            var out = text
            for ((re, name) in codes) out = re.replace(out) { name }
            return out
        }
    }

    companion object {
        const val DEFAULT_TERMS =
            "마운자로 | | 일본어=マンジャロ, 영어=Mounjaro, 중국어=穆峰达, 스페인어=Mounjaro, 프랑스어=Mounjaro"
        const val MENU_SAVE_WORD = 1001
    }

    private val langs = listOf(
        Lang("한국어", "ko-KR", TranslateLanguage.KOREAN),
        Lang("日本語", "ja-JP", TranslateLanguage.JAPANESE),
        Lang("English", "en-US", TranslateLanguage.ENGLISH),
        Lang("Français", "fr-FR", TranslateLanguage.FRENCH),
        Lang("Español", "es-ES", TranslateLanguage.SPANISH),
        Lang("中文", "zh-CN", TranslateLanguage.CHINESE)
    )
    private val korean get() = langs[0]
    private val aliases = mapOf(
        "한국어" to TranslateLanguage.KOREAN, "일본어" to TranslateLanguage.JAPANESE,
        "영어" to TranslateLanguage.ENGLISH, "프랑스어" to TranslateLanguage.FRENCH,
        "스페인어" to TranslateLanguage.SPANISH, "중국어" to TranslateLanguage.CHINESE
    )

    private lateinit var fromSpinner: Spinner
    private lateinit var toSpinner: Spinner
    private lateinit var segments: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var interimView: TextView
    private lateinit var statusView: TextView
    private lateinit var speakBox: CheckBox
    private lateinit var micButton: Button
    private lateinit var chatInput: EditText
    private var hint: TextView? = null

    private var recognizer: SpeechRecognizer? = null
    private var translator: Translator? = null
    private var chatTranslator: Translator? = null
    private var chatPair = ""
    private var tts: TextToSpeech? = null
    private var listening = false
    private var committed = 0
    private var seq = 0
    private var nextToSpeak = 1
    private val speechBuf = HashMap<Int, Seg?>()
    private val screenLog = ArrayList<Seg>()
    private val utter = HashMap<String, Seg>()
    private var utterCount = 0
    private var terms: List<Term> = emptyList()
    private val repaired = HashSet<String>()
    private val prefs by lazy { getSharedPreferences("interpreter", MODE_PRIVATE) }

    private val net = Executors.newSingleThreadExecutor()
    private var deeplBlockedUntil = 0L
    private val recent = ArrayDeque<String>()

    private val from: Lang get() = langs[fromSpinner.selectedItemPosition]
    private val to: Lang get() = langs[toSpinner.selectedItemPosition]
    private val noSpaces: Boolean get() = from.speechTag.startsWith("ja") || from.speechTag.startsWith("zh")
    private val deeplKey: String get() = prefs.getString("deepl", "").orEmpty().trim()

    private val restartRunnable = Runnable { if (listening) { stop(); start() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        terms = parseTerms(prefs.getString("terms", DEFAULT_TERMS) ?: DEFAULT_TERMS)

        val labels = langs.map { it.label }
        fromSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, labels)
            setSelection(1)
        }
        toSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, labels)
            setSelection(0)
        }
        val swap = Button(this).apply {
            text = "⇄"
            textSize = 20f
            setOnClickListener {
                val f = fromSpinner.selectedItemPosition
                fromSpinner.setSelection(toSpinner.selectedItemPosition)
                toSpinner.setSelection(f)
            }
        }
        val onChange = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                scroll.removeCallbacks(restartRunnable)
                scroll.postDelayed(restartRunnable, 300)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(fromSpinner, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(swap, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
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
        }

        chatInput = EditText(this).apply {
            setHint("한국어로 입력하면 외국어로 번역해요")
            textSize = 16f
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, event ->
                val enter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                if (actionId == EditorInfo.IME_ACTION_SEND || enter) { sendChat(); true } else false
            }
        }
        val sendButton = Button(this).apply {
            text = "번역"
            setOnClickListener { sendChat() }
        }
        val chatRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(chatInput, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(sendButton, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }

        speakBox = CheckBox(this).apply { text = "음성으로 읽기" }
        val settingsButton = Button(this).apply {
            text = "설정"
            setOnClickListener { openSettings() }
        }
        micButton = Button(this).apply {
            text = "시작"
            textSize = 18f
            setOnClickListener { if (listening) stop() else start() }
        }
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(speakBox, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(settingsButton, LinearLayout.LayoutParams(WRAP_CONTENT, dp(64)))
            addView(micButton, LinearLayout.LayoutParams(dp(120), dp(64)))
        }

        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            addView(top, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(interimView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(statusView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(chatRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(bottom, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        setContentView(root)

        fromSpinner.onItemSelectedListener = onChange
        toSpinner.onItemSelectedListener = onChange

        tts = TextToSpeech(this) { }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = runOnUiThread { highlight(utteranceId, -1, -1) }
            override fun onDone(utteranceId: String?) = runOnUiThread { unhighlight(utteranceId) }
            override fun onError(utteranceId: String?) = runOnUiThread { unhighlight(utteranceId) }
            override fun onStop(utteranceId: String?, interrupted: Boolean) = runOnUiThread { unhighlight(utteranceId) }
            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) =
                runOnUiThread { highlight(utteranceId, start, end) }
        })
    }

    private fun showHint() {
        val h = TextView(this).apply {
            text = "언어를 고르고 시작을 누르세요. 아래 입력창에 한국어를 쓰면 외국어로 번역해요.\n" +
                   "☆ 문장 저장 · 🔊 다시 듣기 · 번역문을 길게 누르면 단어 저장과 복사"
            textSize = 16f
            setTextColor(Color.GRAY)
        }
        hint = h
        segments.addView(h)
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

    private fun unhighlight(id: String?) {
        val seg = utter.remove(id ?: return) ?: return
        seg.view.text = seg.tr
    }

    private fun speakNow(text: String, voice: String, seg: Seg?, flush: Boolean) {
        val engine = tts ?: return
        val uid = "u${++utterCount}"
        if (seg != null) utter[uid] = seg
        engine.language = Locale.forLanguageTag(voice)
        engine.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, uid)
    }

    /* ---------- Typed chat ---------- */

    // Translates typed Korean into whichever foreign language is selected.
    private fun sendChat() {
        val text = chatInput.text.toString().trim()
        if (text.isEmpty()) return
        val target = if (to.mlkit != TranslateLanguage.KOREAN) to else from
        if (target.mlkit == TranslateLanguage.KOREAN) {
            status("위쪽에서 번역할 외국어를 고르세요.")
            return
        }
        chatInput.setText("")
        val src = korean
        val pair = src.mlkit + ">" + target.mlkit
        val existing = chatTranslator
        if (existing != null && chatPair == pair) {
            translate(text, src, target, existing, typed = true)
            return
        }
        chatTranslator?.close()
        val t = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(src.mlkit)
                .setTargetLanguage(target.mlkit)
                .build()
        )
        chatTranslator = t
        chatPair = pair
        if (!listening) status("채팅 번역 준비 중…")
        t.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                if (chatTranslator === t) {
                    if (!listening) status("")
                    translate(text, src, target, t, typed = true)
                }
            }
            .addOnFailureListener { status("언어팩을 받지 못했어요. 인터넷 연결을 확인하세요.") }
    }

    /* ---------- Settings ---------- */

    private fun openSettings() {
        val items = arrayOf("화면 지우기", "저장함", "화면 내용 내보내기", "단어장", "DeepL API 키")
        AlertDialog.Builder(this)
            .setTitle("설정")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> clearScreen()
                    1 -> openSaved()
                    2 -> exportScreen()
                    3 -> openDictionary()
                    else -> openDeeplKey()
                }
            }
            .show()
    }

    private fun clearScreen() {
        tts?.stop()
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
        if (lines.isEmpty()) { toast("화면에 번역된 내용이 없어요."); return }
        share(lines.joinToString("\n\n") { (if (it.typed) "✍ " else "") + it.src + "\n→ " + it.tr })
    }

    private fun dialogBox(edit: EditText) = FrameLayout(this).apply {
        setPadding(dp(20), dp(8), dp(20), 0)
        addView(edit)
    }

    private fun openDeeplKey() {
        val edit = EditText(this).apply {
            setText(prefs.getString("deepl", ""))
            setHint("예: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx:fx")
            textSize = 15f
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("DeepL API 키")
            .setMessage("키를 넣으면 인터넷이 될 때 DeepL로 번역하고, 안 될 때는 기기 번역으로 자동 전환해요. 비워두면 기기 번역만 써요.")
            .setView(dialogBox(edit))
            .setPositiveButton("저장") { _, _ ->
                prefs.edit().putString("deepl", edit.text.toString().trim()).apply()
                deeplBlockedUntil = 0L
                if (listening) { stop(); start() }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    /* ---------- Saved sentences and words ---------- */

    private fun loadSaved(): JSONArray =
        try { JSONArray(prefs.getString("saved", "[]")) } catch (_: Exception) { JSONArray() }

    private fun saveItem(text: String, note: String, voice: String) {
        val arr = loadSaved()
        arr.put(JSONObject().put("t", text).put("n", note).put("v", voice))
        prefs.edit().putString("saved", arr.toString()).apply()
        toast("저장했어요")
    }

    private fun openSaved() {
        val arr = loadSaved()
        if (arr.length() == 0) {
            toast("저장한 문장이 없어요. ☆를 누르거나 단어를 길게 눌러 저장하세요.")
            return
        }
        val items = Array(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            o.optString("t") + "\n   " + o.optString("n")
        }
        AlertDialog.Builder(this)
            .setTitle("저장함")
            .setItems(items) { _, i -> savedActions(i) }
            .setNeutralButton("전체 내보내기") { _, _ -> share(items.joinToString("\n\n")) }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun savedActions(index: Int) {
        val o = loadSaved().optJSONObject(index) ?: return
        val text = o.optString("t")
        AlertDialog.Builder(this)
            .setTitle(text)
            .setItems(arrayOf("소리로 듣기", "복사", "삭제")) { _, which ->
                when (which) {
                    0 -> speakNow(text, o.optString("v", "ja-JP"), null, true)
                    1 -> copy(text)
                    else -> {
                        val arr = loadSaved()
                        arr.remove(index)
                        prefs.edit().putString("saved", arr.toString()).apply()
                        toast("삭제했어요")
                    }
                }
            }
            .show()
    }

    // Adds "단어 저장" next to copy when part of a translation is selected.
    private fun enableWordSave(tv: TextView, seg: Seg) {
        tv.customSelectionActionModeCallback = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.add(0, MENU_SAVE_WORD, 0, "단어 저장")
                return true
            }
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                if (item.itemId != MENU_SAVE_WORD) return false
                val a = minOf(tv.selectionStart, tv.selectionEnd).coerceAtLeast(0)
                val b = maxOf(tv.selectionStart, tv.selectionEnd).coerceAtMost(tv.text.length)
                val word = if (b > a) tv.text.substring(a, b).trim() else ""
                if (word.isNotEmpty()) saveItem(word, seg.tr, seg.voice)
                mode.finish()
                return true
            }
            override fun onDestroyActionMode(mode: ActionMode) {}
        }
    }

    private fun copy(text: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("번역", text))
        toast("복사했어요")
    }

    private fun share(text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, "내보내기"))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /* ---------- Word list ---------- */

    private fun parseTerms(text: String): List<Term> = text.lines().mapNotNull { line ->
        val parts = line.replace("｜", "|").split("|").map { it.trim() }
        val key = parts.getOrNull(0).orEmpty()
        if (key.isEmpty() || key.startsWith("#")) return@mapNotNull null
        val variants = parts.getOrNull(1).orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val names = HashMap<String, String>()
        names[TranslateLanguage.KOREAN] = key
        parts.getOrNull(2).orEmpty().replace("，", ",").split(",").forEach { pair ->
            val kv = pair.replace("＝", "=").split("=", limit = 2)
            if (kv.size == 2) {
                val label = kv[0].trim()
                val value = kv[1].trim()
                val code = aliases[label]
                    ?: langs.firstOrNull { it.label.equals(label, true) || it.mlkit == label.lowercase() }?.mlkit
                if (code != null && value.isNotEmpty()) names[code] = value
            }
        }
        Term(names, variants)
    }

    // Matches a Korean word even if the recognizer split it with spaces.
    private fun flex(word: String) = Regex(
        word.replace(" ", "").map { Regex.escape(it.toString()) }.joinToString("\\s*"),
        RegexOption.IGNORE_CASE
    )

    // Finds placeholder code XZ<n> in translated text, including full-width forms.
    private fun codeRegex(n: Int): Regex {
        val digits = n.toString().map { d -> "[" + d + (d - '0' + 0xFF10).toChar() + "]" }.joinToString("\\s*")
        return Regex("[XxＸｘ]\\s*[ZzＺｚ]\\s*$digits(?![0-9０-９])")
    }

    private fun prepare(src: String, sl: Lang, tl: Lang): Prepared {
        var shown = src
        if (sl.mlkit == TranslateLanguage.KOREAN) {
            for (t in terms) {
                val key = t.names[TranslateLanguage.KOREAN] ?: continue
                for (w in t.variants + key) shown = flex(w).replace(shown) { key }
            }
        }
        var input = shown
        val codes = LinkedHashMap<Regex, String>()
        terms.forEachIndexed { i, t ->
            val a = t.names[sl.mlkit] ?: return@forEachIndexed
            val pattern = if (sl.mlkit == TranslateLanguage.KOREAN) flex(a)
                          else Regex(Regex.escape(a), RegexOption.IGNORE_CASE)
            if (!pattern.containsMatchIn(input)) return@forEachIndexed
            val n = i + 1
            input = pattern.replace(input) { "XZ$n" }
            codes[codeRegex(n)] = t.names[tl.mlkit] ?: t.names[TranslateLanguage.ENGLISH] ?: a
        }
        return Prepared(shown, input, codes)
    }

    private fun openDictionary() {
        val edit = EditText(this).apply {
            setText(prefs.getString("terms", DEFAULT_TERMS))
            textSize = 15f
            minLines = 6
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setHorizontallyScrolling(false)
        }
        AlertDialog.Builder(this)
            .setTitle("단어장")
            .setMessage(
                "한 줄에 단어 하나씩 적어요.\n" +
                "한국어 단어 | 잘못 들리는 말 | 언어=번역\n" +
                "잘못 들리는 말은 쉼표로 여러 개 적을 수 있고, 가운데·끝 칸은 비워도 돼요."
            )
            .setView(dialogBox(edit))
            .setPositiveButton("저장") { _, _ ->
                val text = edit.text.toString()
                prefs.edit().putString("terms", text).apply()
                terms = parseTerms(text)
                if (listening) { stop(); start() }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    /* ---------- Language pack check ---------- */

    private fun looksLike(text: String, lang: String): Boolean {
        val letters = text.filter { it.isLetter() }
        if (letters.length < 3) return true
        val hangul = letters.count { it in '\uAC00'..'\uD7A3' }
        val kana = letters.count { it in '\u3040'..'\u30FF' }
        val han = letters.count { it in '\u4E00'..'\u9FFF' }
        val latin = letters.count { it in 'a'..'z' || it in 'A'..'Z' || it in '\u00C0'..'\u024F' }
        return when (lang) {
            TranslateLanguage.KOREAN -> hangul > 0
            TranslateLanguage.JAPANESE -> kana + han > 0
            TranslateLanguage.CHINESE -> han > 0
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
        chatTranslator?.close()
        chatTranslator = null
        chatPair = ""
        status("번역 결과가 이상해서 언어팩을 다시 받는 중이에요…")
        val mm = RemoteModelManager.getInstance()
        val tasks = listOf(src, tgt)
            .filter { it != TranslateLanguage.ENGLISH }
            .map { mm.deleteDownloadedModel(TranslateRemoteModel.Builder(it).build()) }
        Tasks.whenAllComplete(tasks).addOnCompleteListener {
            if (wasListening) start() else status("언어팩을 정리했어요. 다시 입력하면 새로 받아서 번역해요.")
        }
    }

    /* ---------- Start / stop ---------- */

    private fun start() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status("이 폰에서는 음성 인식을 쓸 수 없어요. Google 앱이 설치되어 있고 켜져 있는지 확인하세요.")
            return
        }
        if (from == to) {
            status("서로 다른 두 언어를 고르세요.")
            return
        }
        listening = true
        micButton.text = "정지"
        recent.clear()

        translator?.close()
        val t = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(from.mlkit)
                .setTargetLanguage(to.mlkit)
                .build()
        )
        translator = t
        status("언어팩 준비 중… 처음 한 번은 인터넷이 필요해요.")
        t.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                if (listening && translator === t) {
                    val engine = if (deeplKey.isNotEmpty()) "DeepL" else "기기 번역"
                    status("듣는 중 · ${from.label} → ${to.label} · $engine")
                    startRecognizer()
                }
            }
            .addOnFailureListener {
                stop()
                status("언어팩을 받지 못했어요. 인터넷 연결을 확인하세요.")
            }
    }

    private fun stop() {
        listening = false
        recognizer?.destroy()
        recognizer = null
        interimView.text = ""
        micButton.text = "시작"
        status("")
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(rc, perms, res)
        if (res.firstOrNull() == PackageManager.PERMISSION_GRANTED) start()
        else status("마이크 권한을 허용해야 통역할 수 있어요.")
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
                handle(firstResult(b), true)
                restartRecognizer()
            }
            override fun onError(error: Int) {
                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    stop()
                    status("마이크 권한이 없어요.")
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
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, from.speechTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            if (Build.VERSION.SDK_INT >= 33) {
                val words = terms.mapNotNull { it.names[from.mlkit] }
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
            if (rest.isNotBlank()) translate(rest.trim())
            committed = 0
            interimView.text = ""
            return
        }
        val size = if (noSpaces) rest.length / 2.5
                   else rest.trim().split(Regex("\\s+")).size.toDouble()
        if (size >= 9) {
            val stable = stablePart(rest)
            if (stable.isNotBlank()) {
                translate(stable.trim())
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
        val p = prepare(raw, sl, tl)
        val source = sl.mlkit
        val target = tl.mlkit

        val trView = TextView(this).apply {
            text = "…"
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
            setTextIsSelectable(true)
        }
        val seg = Seg(p.shown, tl.speechTag, trView, typed)
        screenLog.add(seg)
        enableWordSave(trView, seg)

        val srcView = TextView(this).apply {
            text = if (typed) "✍ " + p.shown else p.shown
            textSize = 14f
            setTextColor(Color.GRAY)
            setTextIsSelectable(true)
        }
        val playView = TextView(this).apply {
            text = "🔊"
            textSize = 18f
            setPadding(dp(12), dp(4), dp(8), dp(4))
            setOnClickListener { if (seg.tr.isNotEmpty()) speakNow(seg.tr, seg.voice, seg, true) }
        }
        val saveView = TextView(this).apply {
            text = "☆"
            textSize = 20f
            setTextColor(Color.rgb(47, 91, 234))
            setPadding(dp(8), dp(4), dp(4), dp(4))
            setOnClickListener {
                if (seg.tr.isNotEmpty()) {
                    saveItem(seg.tr, seg.src, seg.voice)
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
        segments.addView(trView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(14) })
        segments.addView(srcRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }

        val context = recent.joinToString(" ")
        recent.addLast(p.shown)
        while (recent.size > 3) recent.removeFirst()

        val key = deeplKey
        if (key.isEmpty() || System.currentTimeMillis() < deeplBlockedUntil) {
            viaMlKit(t, id, seg, p, source, target)
            return
        }
        net.execute {
            val result: Any = try { deepl(p.input, source, target, key, context) } catch (e: Exception) { e }
            runOnUiThread {
                if (result is String) {
                    showResult(id, seg, p.restore(result), target)
                } else {
                    deeplFailed(result as Exception)
                    viaMlKit(t, id, seg, p, source, target)
                }
            }
        }
    }

    private fun viaMlKit(t: Translator, id: Int, seg: Seg, p: Prepared, source: String, target: String) {
        t.translate(p.input)
            .addOnSuccessListener { raw ->
                val tr = p.restore(raw)
                if (!looksLike(tr, target) && looksLike(p.shown, source)) {
                    seg.view.text = "$tr\n(언어팩 오류 · 다시 받는 중)"
                    queueSpeech(id, null)
                    repairLanguagePack(source, target)
                    return@addOnSuccessListener
                }
                showResult(id, seg, tr, target)
            }
            .addOnFailureListener {
                seg.view.text = "번역 실패"
                queueSpeech(id, null)
            }
    }

    private fun showResult(id: Int, seg: Seg, tr: String, target: String) {
        seg.tr = tr
        seg.view.text = tr
        Pronouncer.attach(this, segments, seg.view, tr, target)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        queueSpeech(id, seg)
    }

    /* ---------- DeepL ---------- */

    private fun deeplCode(lang: String, asTarget: Boolean): String =
        if (lang == TranslateLanguage.ENGLISH && asTarget) "EN-US" else lang.uppercase()

    // Runs on a background thread.
    private fun deepl(text: String, source: String, target: String, key: String, context: String): String {
        val host = if (key.endsWith(":fx")) "api-free.deepl.com" else "api.deepl.com"
        val conn = URL("https://$host/v2/translate").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 3000
        conn.readTimeout = 6000
        conn.doOutput = true
        conn.setRequestProperty("Authorization", "DeepL-Auth-Key $key")
        conn.setRequestProperty("Content-Type", "application/json")
        val body = JSONObject()
            .put("text", JSONArray().put(text))
            .put("source_lang", deeplCode(source, false))
            .put("target_lang", deeplCode(target, true))
            .put("formality", "prefer_more")
        if (context.isNotBlank()) body.put("context", context)
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code != 200) throw DeepLException(code)
            val json = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return JSONObject(json).getJSONArray("translations").getJSONObject(0).getString("text")
        } finally {
            conn.disconnect()
        }
    }

    private fun deeplFailed(e: Exception) {
        val code = (e as? DeepLException)?.code
        val (wait, msg) = when (code) {
            401, 403 -> Long.MAX_VALUE to "DeepL 키가 올바르지 않아요. 설정에서 확인하세요. 지금은 기기 번역을 써요."
            456 -> Long.MAX_VALUE to "이번 달 DeepL 무료 한도를 다 썼어요. 기기 번역으로 전환했어요."
            429 -> 10_000L to "DeepL 요청이 많아 잠시 기기 번역을 써요."
            else -> 30_000L to "DeepL에 연결하지 못해 잠시 기기 번역을 써요."
        }
        deeplBlockedUntil = if (wait == Long.MAX_VALUE) Long.MAX_VALUE else System.currentTimeMillis() + wait
        status(msg)
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
        translator?.close()
        chatTranslator?.close()
        tts?.shutdown()
        net.shutdown()
        super.onDestroy()
    }
}