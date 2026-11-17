package com.example.interpreter

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LangInfo(val label: String, val code: String, val voice: String)

// Saved words and sentences: filter by language and date, and test yourself.
class VocabView(
    context: Context,
    private val prefs: SharedPreferences,
    private val langs: List<LangInfo>,
    private val speak: (String, String) -> Unit
) : LinearLayout(context) {

    private class Item(
        val index: Int, val text: String, val meaning: String,
        val example: String, val exampleMeaning: String,
        val voice: String, val code: String, val time: Long,
        val right: Int, val wrong: Int
    )

    private val density = resources.displayMetrics.density
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd (E)", Locale.KOREAN)
    private var dayOptions: List<String> = emptyList()
    private var selectedDay: String? = null
    private var shown: List<Item> = emptyList()

    private val langFilter: Spinner
    private val dateFilter: Spinner
    private val search: EditText
    private val list: LinearLayout
    private val listPanel: LinearLayout
    private val quizHolder: FrameLayout

    init {
        orientation = VERTICAL
        langFilter = Spinner(context).apply {
            adapter = ArrayAdapter(
                context, android.R.layout.simple_spinner_dropdown_item,
                listOf("모든 언어") + langs.map { it.label }
            )
            onItemSelectedListener = onSelect { refresh() }
        }
        dateFilter = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, listOf("모든 날짜"))
            onItemSelectedListener = onSelect { pos ->
                val day = if (pos <= 0) null else dayOptions.getOrNull(pos - 1)
                if (day != selectedDay) {
                    selectedDay = day
                    refresh()
                }
            }
        }
        val filters = LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(langFilter, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(dateFilter, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
        search = EditText(context).apply {
            setHint("검색")
            setSingleLine(true)
            textSize = 15f
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { refresh() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        val quizButton = Button(context).apply {
            text = "시험"
            setOnClickListener { startQuiz() }
        }
        val exportButton = Button(context).apply {
            text = "내보내기"
            setOnClickListener { exportShown() }
        }
        val actions = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(search, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(quizButton)
            addView(exportButton)
        }
        list = LinearLayout(context).apply { orientation = VERTICAL }
        val scroll = ScrollView(context).apply { addView(list) }
        listPanel = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(filters, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(actions, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        }
        quizHolder = FrameLayout(context).apply { visibility = View.GONE }
        addView(listPanel, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(quizHolder, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun onSelect(f: (Int) -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = f(pos)
        override fun onNothingSelected(p: AdapterView<*>?) {}
    }

    private fun dp(v: Int) = (v * density).toInt()
    private fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    /* ---------- Storage ---------- */

    private fun readArray(): JSONArray =
        try { JSONArray(prefs.getString("saved", "[]")) } catch (_: Exception) { JSONArray() }

    private fun writeArray(arr: JSONArray) = prefs.edit().putString("saved", arr.toString()).apply()

    private fun isKorean(s: String): Boolean {
        val letters = s.filter { it.isLetter() }
        val hangul = letters.count { it in '\uAC00'..'\uD7A3' }
        return hangul > 0 && hangul * 2 >= letters.length
    }

    private fun voiceFor(s: String): String = when {
        s.any { it in '\u3040'..'\u30FF' } -> "ja-JP"
        s.any { it in '\u4E00'..'\u9FFF' } -> "zh-CN"
        isKorean(s) -> "ko-KR"
        else -> "en-US"
    }

    // Converts items saved by older versions into foreign text + Korean meaning.
    private fun migrate(o: JSONObject) {
        val t = o.optString("t")
        val n = o.optString("n")
        when {
            isKorean(t) && n.isNotEmpty() && !isKorean(n) -> {
                val v = voiceFor(n)
                o.put("t", n).put("m", t).put("v", v).put("l", v.substringBefore("-"))
            }
            !isKorean(t) && isKorean(n) -> o.put("m", n)
            else -> {
                o.put("m", "")
                if (n.isNotEmpty() && n != t) o.put("e", n)
            }
        }
    }

    private fun load(): List<Item> {
        val arr = readArray()
        val now = System.currentTimeMillis()
        var changed = false
        val items = ArrayList<Item>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (!o.has("d")) { o.put("d", now); changed = true }
            if (!o.has("m")) { migrate(o); changed = true }
            val voice = o.optString("v", "ja-JP")
            val code = o.optString("l").ifEmpty { voice.substringBefore("-") }
            items += Item(
                i, o.optString("t"), o.optString("m"), o.optString("e"), o.optString("em"),
                voice, code, o.optLong("d", now), o.optInt("c", 0), o.optInt("w", 0)
            )
        }
        if (changed) writeArray(arr)
        return items
    }

    /* ---------- List ---------- */

    fun refresh() {
        val pos = langFilter.selectedItemPosition
        val code = if (pos <= 0) null else langs[pos - 1].code
        val q = search.text.toString().trim()
        val base = load()
            .filter { code == null || it.code == code }
            .filter {
                q.isEmpty() || it.text.contains(q, true) || it.meaning.contains(q, true) || it.example.contains(q, true)
            }
            .sortedByDescending { it.time }

        val days = base.map { dayFormat.format(Date(it.time)) }.distinct()
        if (days != dayOptions) {
            dayOptions = days
            if (selectedDay != null && selectedDay !in days) selectedDay = null
            dateFilter.adapter = ArrayAdapter(
                context, android.R.layout.simple_spinner_dropdown_item, listOf("모든 날짜") + days
            )
            dateFilter.setSelection(selectedDay?.let { days.indexOf(it) + 1 } ?: 0)
        }
        shown = base.filter { selectedDay == null || dayFormat.format(Date(it.time)) == selectedDay }

        list.removeAllViews()
        if (shown.isEmpty()) {
            list.addView(TextView(context).apply {
                text = "저장한 단어나 문장이 없어요.\n통역 화면에서 ☆를 누르거나, 글자를 길게 눌러 '단어 저장'을 고르세요."
                textSize = 15f
                setTextColor(Color.GRAY)
                setPadding(0, dp(16), 0, 0)
            })
            return
        }
        var lastDay = ""
        for (item in shown) {
            val day = dayFormat.format(Date(item.time))
            if (day != lastDay) {
                lastDay = day
                list.addView(header(day))
            }
            list.addView(row(item))
        }
    }

    private fun header(day: String) = TextView(context).apply {
        text = day
        textSize = 13f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Color.rgb(47, 91, 234))
        setPadding(0, dp(18), 0, dp(4))
    }

    private fun row(item: Item): View {
        val label = langs.firstOrNull { it.code == item.code }?.label ?: item.code
        val texts = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(TextView(context).apply {
                text = item.text
                textSize = 20f
                setTypeface(null, Typeface.BOLD)
                setTextIsSelectable(true)
            })
            addView(TextView(context).apply {
                text = item.meaning.ifEmpty { "뜻 없음 · 시험을 시작하면 자동으로 채워요" }
                textSize = 16f
                setTextColor(if (item.meaning.isEmpty()) Color.LTGRAY else Color.rgb(47, 91, 234))
            })
            if (item.example.isNotEmpty()) addView(TextView(context).apply {
                text = "예) " + item.example + if (item.exampleMeaning.isNotEmpty()) "\n     " + item.exampleMeaning else ""
                textSize = 13f
                setTextColor(Color.GRAY)
            })
            addView(TextView(context).apply {
                text = "$label  ·  ✔${item.right}  ✘${item.wrong}"
                textSize = 11f
                setTextColor(Color.LTGRAY)
            })
        }
        val play = TextView(context).apply {
            text = "🔊"
            textSize = 18f
            setPadding(dp(10), dp(4), dp(6), dp(4))
            setOnClickListener { speak(item.text, item.voice) }
        }
        val more = TextView(context).apply {
            text = "⋮"
            textSize = 22f
            setPadding(dp(10), dp(4), dp(4), dp(4))
            setOnClickListener { actions(item) }
        }
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            addView(texts, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(play)
            addView(more)
        }
    }

    private fun actions(item: Item) {
        AlertDialog.Builder(context)
            .setTitle(item.text)
            .setItems(arrayOf("복사", "삭제")) { _, which ->
                if (which == 0) copy(item.text + if (item.meaning.isNotEmpty()) " = " + item.meaning else "")
                else delete(item)
            }
            .show()
    }

    private fun delete(item: Item) {
        val arr = readArray()
        if (item.index < arr.length()) arr.remove(item.index)
        writeArray(arr)
        toast("삭제했어요")
        refresh()
    }

    private fun copy(text: String) {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("단어", text))
        toast("복사했어요")
    }

    private fun exportShown() {
        if (shown.isEmpty()) {
            toast("내보낼 항목이 없어요.")
            return
        }
        var lastDay = ""
        val sb = StringBuilder()
        for (item in shown) {
            val day = dayFormat.format(Date(item.time))
            if (day != lastDay) {
                lastDay = day
                if (sb.isNotEmpty()) sb.append("\n")
                sb.append("■ ").append(day).append("\n")
            }
            sb.append(item.text)
            if (item.meaning.isNotEmpty()) sb.append("  =  ").append(item.meaning)
            if (item.example.isNotEmpty()) sb.append("\n    예) ").append(item.example)
            sb.append("\n")
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, sb.toString())
        }
        context.startActivity(Intent.createChooser(send, "단어장 내보내기"))
    }

    /* ---------- Quiz ---------- */

    private fun startQuiz() {
        if (shown.size < 2) {
            toast("시험을 보려면 단어가 2개 이상 있어야 해요.")
            return
        }
        val missing = shown.filter { it.meaning.isEmpty() && it.code != TranslateLanguage.KOREAN }
        if (missing.isEmpty()) {
            chooseMode()
            return
        }
        toast("뜻이 없는 단어 ${missing.size}개의 뜻을 채우는 중…")
        fillMeanings(missing) {
            refresh()
            chooseMode()
        }
    }

    // Translates words that have no meaning yet into Korean, then saves them.
    private fun fillMeanings(items: List<Item>, done: () -> Unit) {
        var pending = items.size
        val results = HashMap<Int, String>()
        fun finishOne() {
            pending--
            if (pending > 0) return
            val arr = readArray()
            results.forEach { (i, m) -> arr.optJSONObject(i)?.put("m", m) }
            writeArray(arr)
            done()
        }
        items.groupBy { it.code }.forEach { (code, group) ->
            try {
                val t = Translation.getClient(
                    TranslatorOptions.Builder()
                        .setSourceLanguage(code)
                        .setTargetLanguage(TranslateLanguage.KOREAN)
                        .build()
                )
                t.downloadModelIfNeeded(DownloadConditions.Builder().build())
                    .addOnSuccessListener {
                        group.forEach { item ->
                            t.translate(item.text)
                                .addOnSuccessListener { m -> results[item.index] = m }
                                .addOnCompleteListener { finishOne() }
                        }
                    }
                    .addOnFailureListener { group.forEach { _ -> finishOne() } }
            } catch (_: Exception) {
                group.forEach { _ -> finishOne() }
            }
        }
    }

    private fun chooseMode() {
        val retry = shown.filter { it.wrong > 0 }
        val options = arrayListOf("외국어 보고 뜻 고르기", "뜻 보고 외국어 고르기", "카드 넘기며 외우기")
        if (retry.size >= 2) options += "틀렸던 것만 (뜻 고르기, ${retry.size}개)"
        AlertDialog.Builder(context)
            .setTitle("시험 · ${shown.size}개")
            .setItems(options.toTypedArray()) { _, which ->
                if (which == 3) launchQuiz(retry, QuizView.PICK_MEANING) else launchQuiz(shown, which)
            }
            .show()
    }

    private fun launchQuiz(pool: List<Item>, mode: Int) {
        val cards = pool.filter { it.meaning.isNotEmpty() }.map {
            QuizView.Card(it.index, it.text, it.meaning, it.example, it.exampleMeaning, it.voice)
        }
        if (cards.size < 2) {
            toast("뜻이 있는 단어가 2개 이상 필요해요.")
            return
        }
        val quiz = QuizView(context, cards, mode, speak, { card, ok -> record(card.key, ok) }, { closeQuiz() })
        quizHolder.removeAllViews()
        quizHolder.addView(quiz, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        listPanel.visibility = View.GONE
        quizHolder.visibility = View.VISIBLE
    }

    private fun record(index: Int, ok: Boolean) {
        val arr = readArray()
        val o = arr.optJSONObject(index) ?: return
        val key = if (ok) "c" else "w"
        o.put(key, o.optInt(key, 0) + 1)
        writeArray(arr)
    }

    private fun closeQuiz() {
        quizHolder.removeAllViews()
        quizHolder.visibility = View.GONE
        listPanel.visibility = View.VISIBLE
        refresh()
    }
}