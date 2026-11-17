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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LangInfo(val label: String, val code: String, val voice: String)

// Saved words and sentences, grouped by date and filtered by language.
class VocabView(
    context: Context,
    private val prefs: SharedPreferences,
    private val langs: List<LangInfo>,
    private val speak: (String, String) -> Unit
) : LinearLayout(context) {

    private class Item(
        val index: Int, val text: String, val note: String,
        val voice: String, val code: String, val time: Long
    )

    private val density = resources.displayMetrics.density
    private val dayFormat = SimpleDateFormat("yyyy년 M월 d일 (E)", Locale.KOREAN)
    private val filter: Spinner
    private val search: EditText
    private val list: LinearLayout
    private var shown: List<Item> = emptyList()

    init {
        orientation = VERTICAL
        filter = Spinner(context).apply {
            adapter = ArrayAdapter(
                context, android.R.layout.simple_spinner_dropdown_item,
                listOf("모든 언어") + langs.map { it.label }
            )
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = refresh()
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        val export = Button(context).apply {
            text = "내보내기"
            setOnClickListener { exportShown() }
        }
        val head = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(filter, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(export, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
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
        list = LinearLayout(context).apply { orientation = VERTICAL }
        val scroll = ScrollView(context).apply { addView(list) }
        addView(head, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(search, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun dp(v: Int) = (v * density).toInt()

    private fun readArray(): JSONArray =
        try { JSONArray(prefs.getString("saved", "[]")) } catch (_: Exception) { JSONArray() }

    private fun load(): List<Item> {
        val arr = readArray()
        val now = System.currentTimeMillis()
        var stamped = false
        val items = ArrayList<Item>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (!o.has("d")) {
                o.put("d", now)
                stamped = true
            }
            val voice = o.optString("v", "ja-JP")
            val code = o.optString("l").ifEmpty { voice.substringBefore("-") }
            items += Item(i, o.optString("t"), o.optString("n"), voice, code, o.optLong("d", now))
        }
        if (stamped) prefs.edit().putString("saved", arr.toString()).apply()
        return items
    }

    fun refresh() {
        val pos = filter.selectedItemPosition
        val code = if (pos <= 0) null else langs[pos - 1].code
        val q = search.text.toString().trim()
        shown = load()
            .filter { code == null || it.code == code }
            .filter { q.isEmpty() || it.text.contains(q, true) || it.note.contains(q, true) }
            .sortedByDescending { it.time }
        list.removeAllViews()
        if (shown.isEmpty()) {
            list.addView(TextView(context).apply {
                text = "저장한 단어나 문장이 없어요.\n통역 화면에서 ☆를 누르거나, 번역문을 길게 눌러 '단어 저장'을 고르세요."
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
                textSize = 19f
                setTypeface(null, Typeface.BOLD)
                setTextIsSelectable(true)
            })
            if (item.note.isNotEmpty()) addView(TextView(context).apply {
                text = item.note
                textSize = 14f
                setTextColor(Color.GRAY)
            })
            addView(TextView(context).apply {
                text = label
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
            setPadding(0, dp(6), 0, dp(6))
            addView(texts, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(play)
            addView(more)
        }
    }

    private fun actions(item: Item) {
        AlertDialog.Builder(context)
            .setTitle(item.text)
            .setItems(arrayOf("복사", "삭제")) { _, which ->
                if (which == 0) copy(item.text) else delete(item)
            }
            .show()
    }

    private fun delete(item: Item) {
        val arr = readArray()
        if (item.index < arr.length()) arr.remove(item.index)
        prefs.edit().putString("saved", arr.toString()).apply()
        Toast.makeText(context, "삭제했어요", Toast.LENGTH_SHORT).show()
        refresh()
    }

    private fun copy(text: String) {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("단어", text))
        Toast.makeText(context, "복사했어요", Toast.LENGTH_SHORT).show()
    }

    private fun exportShown() {
        if (shown.isEmpty()) {
            Toast.makeText(context, "내보낼 항목이 없어요.", Toast.LENGTH_SHORT).show()
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
            if (item.note.isNotEmpty()) sb.append("  —  ").append(item.note)
            sb.append("\n")
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, sb.toString())
        }
        context.startActivity(Intent.createChooser(send, "단어장 내보내기"))
    }
}