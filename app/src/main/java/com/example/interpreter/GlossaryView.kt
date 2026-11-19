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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

// Glossary tab: fixed translations for names, edited one by one or as plain text.
class GlossaryView(
    context: Context,
    private val prefs: SharedPreferences,
    private val langs: List<LangInfo>,
    private val onChange: (String) -> Unit
) : LinearLayout(context) {

    private val home: Lang = Languages.home(prefs)
    private val foreign = langs.filter { it.code != home.code }
    private val blue = Color.rgb(47, 91, 234)
    private val density = resources.displayMetrics.density
    private val list: LinearLayout

    init {
        orientation = VERTICAL
        val help = TextView(context).apply {
            text = GlossText.t("g_help")
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, dp(8), 0, dp(4))
        }
        val addButton = Button(context).apply {
            text = GlossText.t("g_add")
            setOnClickListener { edit(null) }
        }
        val textButton = Button(context).apply {
            text = GlossText.t("g_edit_text")
            isAllCaps = false
            setOnClickListener { editAsText() }
        }
        val buttons = LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(addButton, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(textButton, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
        list = LinearLayout(context).apply { orientation = VERTICAL }
        addView(help)
        addView(buttons)
        addView(ScrollView(context).apply { addView(list) }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun dp(v: Int) = (v * density).toInt()
    private fun toast(msg: String) = Toast.makeText(context, Ui.plain(msg), Toast.LENGTH_SHORT).show()
    private fun currentText() = Glossary.text(prefs, home.code)

    fun refresh() {
        val terms = Glossary.parse(currentText(), home.code)
        list.removeAllViews()
        if (terms.isEmpty()) {
            list.addView(TextView(context).apply {
                text = GlossText.t("g_empty")
                textSize = 15f
                setTextColor(Color.GRAY)
                setPadding(0, dp(16), 0, 0)
            })
            return
        }
        terms.forEach { list.addView(card(it)) }
    }

    private fun card(t: Glossary.Term): LinearLayout {
        val box = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = Ui.pressable(context)
            setOnClickListener { edit(t) }
        }
        box.addView(TextView(context).apply {
            text = t.names[home.code].orEmpty()
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
        })
        if (t.variants.isNotEmpty()) box.addView(TextView(context).apply {
            text = GlossText.f("g_misheard_label", t.variants.joinToString(", "))
            textSize = 13f
            setTextColor(Color.GRAY)
        })
        val translations = foreign.mapNotNull { l -> t.names[l.code]?.let { "${l.label}: $it" } }
        if (translations.isNotEmpty()) box.addView(TextView(context).apply {
            text = translations.joinToString("  ·  ")
            textSize = 14f
            setTextColor(blue)
        })
        return LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(0, dp(4), 0, dp(4))
            addView(box)
        }
    }

    // Applies a change to the lines of the glossary text, then saves and reloads it.
    private fun change(update: (MutableList<String>) -> Unit) {
        val lines = currentText().lines().toMutableList()
        update(lines)
        val text = lines.joinToString("\n").trim()
        Glossary.saveText(prefs, home.code, text)
        refresh()
        onChange(text)
        toast(GlossText.t("g_saved"))
    }

    private fun field(box: LinearLayout, label: String, value: String): EditText {
        box.addView(TextView(context).apply {
            text = label
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, dp(10), 0, 0)
        })
        val ed = EditText(context).apply {
            setText(value)
            setSingleLine(true)
            textSize = 15f
        }
        box.addView(ed)
        return ed
    }

    private fun edit(t: Glossary.Term?) {
        val box = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val wordEd = field(box, GlossText.f("g_word", home.native), t?.names?.get(home.code).orEmpty())
        val variantsEd = field(box, GlossText.t("g_variants"), t?.variants?.joinToString(", ").orEmpty())
        val transEds = foreign.associate { l ->
            l.code to field(box, GlossText.f("g_trans", l.label), t?.names?.get(l.code).orEmpty())
        }
        val builder = AlertDialog.Builder(context)
            .setTitle(GlossText.t(if (t == null) "g_title_add" else "g_title_edit"))
            .setView(ScrollView(context).apply { addView(box) })
            .setPositiveButton(GlossText.t("g_save"), null)
            .setNegativeButton(I18n.t("cancel"), null)
        if (t != null) {
            builder.setNeutralButton(VocabText.t("delete")) { _, _ ->
                change { lines -> if (t.line < lines.size) lines.removeAt(t.line) }
            }
        }
        val dialog = builder.show().also { Ui.watch(it) }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val word = wordEd.text.toString().trim()
            if (word.isEmpty()) {
                toast(GlossText.t("g_need_word"))
                return@setOnClickListener
            }
            val variants = variantsEd.text.toString().replace("，", ",").split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val names = transEds.mapValues { it.value.text.toString().trim() }
            val line = Glossary.format(word, variants, names, home.code)
            change { lines ->
                if (t != null && t.line < lines.size) lines[t.line] = line else lines.add(line)
            }
            dialog.dismiss()
        }
    }

    private fun editAsText() {
        val ed = EditText(context).apply {
            setText(currentText())
            textSize = 14f
            gravity = Gravity.TOP
            minLines = 8
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        val box = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(TextView(context).apply {
                text = GlossText.t("g_text_help")
                textSize = 13f
                setTextColor(Color.GRAY)
            })
            addView(ed)
        }
        AlertDialog.Builder(context)
            .setTitle(GlossText.t("g_edit_text"))
            .setView(ScrollView(context).apply { addView(box) })
            .setPositiveButton(GlossText.t("g_save")) { _, _ ->
                change { lines ->
                    lines.clear()
                    lines.addAll(ed.text.toString().lines())
                }
            }
            .setNegativeButton(I18n.t("cancel"), null)
            .show().also { Ui.watch(it) }
    }
}

// Screen text for the glossary tab. Order in m(): ko, ja, en, fr, es, de, zh.
private object GlossText {

    fun t(key: String): String = table[key]?.let { it[I18n.lang] ?: it["en"] ?: it["ko"] } ?: key

    fun f(key: String, vararg args: Any): String = String.format(t(key), *args)

    private fun m(ko: String, ja: String, en: String, fr: String, es: String, de: String, zh: String) =
        mapOf("ko" to ko, "ja" to ja, "en" to en, "fr" to fr, "es" to es, "de" to de, "zh" to zh)

    private val table: Map<String, Map<String, String>> = mapOf(
        "g_help" to m(
            "통역할 때 꼭 정해진 대로 옮길 이름(약 이름, 회사·제품명 등)을 등록해요. '잘못 들리는 말'에 음성 인식이 틀리게 적는 형태를 넣으면 바로잡아요.",
            "通訳で必ず決まった訳にしたい名前(薬名、会社・製品名など)を登録します。「聞き間違い」に音声認識の誤記を入れると自動で直します。",
            "Register names that must always be translated a fixed way (medicines, company or product names). Add common misrecognitions under “Misheard as” to fix them automatically.",
            "Enregistrez les noms à traduire toujours de la même façon (médicaments, sociétés, produits). Ajoutez les erreurs de reconnaissance fréquentes pour les corriger.",
            "Registra nombres que deben traducirse siempre igual (medicamentos, empresas, productos). Añade los errores de reconocimiento frecuentes para corregirlos.",
            "Lege Namen fest, die immer gleich übersetzt werden sollen (Medikamente, Firmen, Produkte). Häufige Erkennungsfehler werden automatisch korrigiert.",
            "登记必须按固定方式翻译的名称（药名、公司或产品名）。在“误听为”中填写识别错误的写法即可自动纠正。"
        ),
        "g_add" to m("+ 추가", "+ 追加", "+ Add", "+ Ajouter", "+ Añadir", "+ Hinzufügen", "+ 添加"),
        "g_edit_text" to m("텍스트로 편집", "テキストで編集", "Edit as text", "Modifier en texte", "Editar como texto", "Als Text bearbeiten", "以文本编辑"),
        "g_word" to m("내 언어 단어 (%s)", "自分の言語の単語 (%s)", "Word in your language (%s)", "Mot dans votre langue (%s)", "Palabra en tu idioma (%s)", "Wort in deiner Sprache (%s)", "你的语言中的词（%s）"),
        "g_variants" to m("잘못 들리는 말 (쉼표로 구분, 선택)", "聞き間違い (カンマ区切り・任意)", "Misheard as (comma-separated, optional)", "Mal reconnu comme (virgules, facultatif)", "Mal reconocido como (comas, opcional)", "Falsch erkannt als (kommagetrennt, optional)", "误听为（逗号分隔，可选）"),
        "g_trans" to m("%s 번역", "%s 訳", "%s translation", "Traduction (%s)", "Traducción (%s)", "Übersetzung (%s)", "%s 译名"),
        "g_misheard_label" to m("잘못 들림: %s", "聞き間違い: %s", "Misheard: %s", "Mal reconnu : %s", "Mal reconocido: %s", "Falsch erkannt: %s", "误听：%s"),
        "g_empty" to m("등록된 용어가 없어요.", "登録された用語はありません。", "No terms yet.", "Aucun terme.", "No hay términos.", "Noch keine Begriffe.", "还没有术语。"),
        "g_need_word" to m("단어를 입력하세요.", "単語を入力してください。", "Enter a word.", "Saisissez un mot.", "Escribe una palabra.", "Gib ein Wort ein.", "请输入词语。"),
        "g_saved" to m("저장했어요", "保存しました", "Saved", "Enregistré", "Guardado", "Gespeichert", "已保存"),
        "g_save" to m("저장", "保存", "Save", "Enregistrer", "Guardar", "Speichern", "保存"),
        "g_title_add" to m("용어 추가", "用語を追加", "Add term", "Ajouter un terme", "Añadir término", "Begriff hinzufügen", "添加术语"),
        "g_title_edit" to m("용어 수정", "用語を編集", "Edit term", "Modifier le terme", "Editar término", "Begriff bearbeiten", "编辑术语"),
        "g_text_help" to m(
            "한 줄에 하나씩: 단어 | 잘못 들리는 말 | 언어=번역, 언어=번역",
            "1行に1つ: 単語 | 聞き間違い | 言語=訳, 言語=訳",
            "One per line: word | misheard forms | language=translation, ...",
            "Un par ligne : mot | erreurs | langue=traduction, ...",
            "Uno por línea: palabra | errores | idioma=traducción, ...",
            "Eins pro Zeile: Wort | Fehler | Sprache=Übersetzung, ...",
            "每行一个：词 | 误听 | 语言=译名, ..."
        )
    )
}