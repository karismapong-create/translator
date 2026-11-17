package com.example.interpreter

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

// Word-matching database: fixes misheard words and pins how names are translated per language.
class GlossaryView(
    context: Context,
    private val prefs: SharedPreferences,
    private val langs: List<LangInfo>,
    private val defaultTerms: String,
    private val onChanged: (String) -> Unit
) : LinearLayout(context) {

    private class Entry(val key: String, val variants: String, val names: Map<String, String>)

    private val density = resources.displayMetrics.density
    private val koreanName = mapOf(
        "ko" to "한국어", "ja" to "일본어", "en" to "영어",
        "fr" to "프랑스어", "es" to "스페인어", "zh" to "중국어"
    )
    private val foreignLangs = langs.filter { it.code != "ko" }
    private val list: LinearLayout

    init {
        orientation = VERTICAL
        val add = Button(context).apply {
            text = "+ 새 용어"
            setOnClickListener { edit(null) }
        }
        val raw = Button(context).apply {
            text = "텍스트로 편집"
            setOnClickListener { editText() }
        }
        val head = LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(add, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(raw, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
        val help = TextView(context).apply {
            text = "음성 인식이 잘못 알아듣는 말을 고치고, 약 이름·지명 같은 고유명사를 언어별로 정해 둔 이름으로 번역해요. 눌러서 수정할 수 있어요."
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, dp(6), 0, dp(6))
        }
        list = LinearLayout(context).apply { orientation = VERTICAL }
        val scroll = ScrollView(context).apply { addView(list) }
        addView(head, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(help, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun dp(v: Int) = (v * density).toInt()

    private fun currentText() = prefs.getString("terms", defaultTerms) ?: defaultTerms

    private fun codeOf(label: String): String? =
        koreanName.entries.firstOrNull { it.value == label }?.key
            ?: langs.firstOrNull { it.label.equals(label, true) || it.code == label.lowercase() }?.code

    private fun parse(): MutableList<Entry> = currentText().lines().mapNotNull { line ->
        val parts = line.replace("｜", "|").split("|").map { it.trim() }
        val key = parts.getOrNull(0).orEmpty()
        if (key.isEmpty() || key.startsWith("#")) return@mapNotNull null
        val names = LinkedHashMap<String, String>()
        parts.getOrNull(2).orEmpty().replace("，", ",").split(",").forEach { pair ->
            val kv = pair.replace("＝", "=").split("=", limit = 2)
            if (kv.size == 2) {
                val code = codeOf(kv[0].trim())
                val value = kv[1].trim()
                if (code != null && value.isNotEmpty()) names[code] = value
            }
        }
        Entry(key, parts.getOrNull(1).orEmpty(), names)
    }.toMutableList()

    private fun serialize(entries: List<Entry>) = entries.joinToString("\n") { e ->
        e.key + " | " + e.variants + " | " +
            e.names.entries.joinToString(", ") { (code, value) -> (koreanName[code] ?: code) + "=" + value }
    }

    private fun store(entries: List<Entry>) = storeText(serialize(entries))

    private fun storeText(text: String) {
        prefs.edit().putString("terms", text).apply()
        onChanged(text)
        refresh()
    }

    fun refresh() {
        list.removeAllViews()
        val entries = parse()
        if (entries.isEmpty()) {
            list.addView(TextView(context).apply {
                text = "등록된 용어가 없어요. '+ 새 용어'로 추가하세요."
                textSize = 15f
                setTextColor(Color.GRAY)
                setPadding(0, dp(16), 0, 0)
            })
            return
        }
        entries.forEachIndexed { i, e ->
            val row = LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(0, dp(10), 0, dp(10))
                setOnClickListener { edit(i) }
                addView(TextView(context).apply {
                    text = e.key
                    textSize = 18f
                    setTypeface(null, Typeface.BOLD)
                })
                if (e.variants.isNotEmpty()) addView(TextView(context).apply {
                    text = "잘못 들림: " + e.variants
                    textSize = 13f
                    setTextColor(Color.GRAY)
                })
                val names = foreignLangs.mapNotNull { l -> e.names[l.code]?.let { "${l.label} $it" } }
                addView(TextView(context).apply {
                    text = if (names.isEmpty()) "언어별 이름 없음" else names.joinToString("  ·  ")
                    textSize = 14f
                    setTextColor(Color.rgb(47, 91, 234))
                })
            }
            list.addView(row)
        }
    }

    private fun edit(index: Int?) {
        val all = parse()
        val e = index?.let { all.getOrNull(it) }
        val box = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        fun field(label: String, value: String, hint: String): EditText {
            box.addView(TextView(context).apply {
                text = label
                textSize = 13f
                setTextColor(Color.GRAY)
                setPadding(0, dp(8), 0, 0)
            })
            val ed = EditText(context).apply {
                setText(value)
                setHint(hint)
                setSingleLine(true)
                textSize = 15f
            }
            box.addView(ed)
            return ed
        }
        val keyEd = field("한국어 단어", e?.key ?: "", "예: 마운자로")
        val varEd = field("잘못 들리는 말 (쉼표로 구분, 없으면 비우기)", e?.variants ?: "", "예: 마운 잘로, 마운틴 자로")
        val nameEds = foreignLangs.map { l -> l.code to field(l.label, e?.names?.get(l.code) ?: "", "") }
        val scroll = ScrollView(context).apply { addView(box) }

        val builder = AlertDialog.Builder(context)
            .setTitle(if (e == null) "새 용어" else "용어 수정")
            .setView(scroll)
            .setPositiveButton("저장") { _, _ ->
                val key = keyEd.text.toString().trim()
                if (key.isEmpty()) return@setPositiveButton
                val names = LinkedHashMap<String, String>()
                nameEds.forEach { (code, ed) ->
                    val v = ed.text.toString().trim()
                    if (v.isNotEmpty()) names[code] = v
                }
                val entry = Entry(key, varEd.text.toString().trim(), names)
                if (index != null && index < all.size) all[index] = entry else all.add(entry)
                store(all)
            }
            .setNegativeButton("취소", null)
        if (e != null && index != null) {
            builder.setNeutralButton("삭제") { _, _ ->
                all.removeAt(index)
                store(all)
            }
        }
        builder.show()
    }

    private fun editText() {
        val edit = EditText(context).apply {
            setText(currentText())
            textSize = 15f
            minLines = 6
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setHorizontallyScrolling(false)
        }
        val box = FrameLayout(context).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(edit)
        }
        AlertDialog.Builder(context)
            .setTitle("텍스트로 편집")
            .setMessage("한 줄에 용어 하나씩: 한국어 단어 | 잘못 들리는 말 | 일본어=…, 영어=…")
            .setView(box)
            .setPositiveButton("저장") { _, _ -> storeText(edit.text.toString()) }
            .setNegativeButton("취소", null)
            .show()
    }
}