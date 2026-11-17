package com.example.interpreter

import android.content.SharedPreferences

// Names that must always be translated a fixed way.
// Each line: "word in my language | misheard forms | language=translation, language=translation".
object Glossary {

    const val DEFAULT_KO =
        "마운자로 | | 일본어=マンジャロ, 영어=Mounjaro, 중국어=穆峰达, 스페인어=Mounjaro, 프랑스어=Mounjaro, 독일어=Mounjaro"

    class Term(val line: Int, val names: Map<String, String>, val variants: List<String>)

    // Source text with glossary terms swapped for placeholder codes, and how to put them back.
    class Prepared(val shown: String, val input: String, private val codes: Map<Regex, String>) {
        fun restore(text: String): String {
            var out = text
            for ((re, name) in codes) out = re.replace(out) { name }
            return out
        }
    }

    private val koreanLabels = mapOf(
        "ko" to "한국어", "ja" to "일본어", "en" to "영어", "fr" to "프랑스어",
        "es" to "스페인어", "de" to "독일어", "zh" to "중국어"
    )

    // Korean speakers keep the glossary saved by earlier versions; other languages get their own.
    private fun prefKey(home: String) = if (home == "ko") "terms" else "terms_$home"

    fun text(prefs: SharedPreferences, home: String): String =
        prefs.getString(prefKey(home), null) ?: if (home == "ko") DEFAULT_KO else ""

    fun saveText(prefs: SharedPreferences, home: String, text: String) {
        prefs.edit().putString(prefKey(home), text).apply()
    }

    fun label(code: String, home: String): String =
        if (home == "ko") koreanLabels[code] ?: code else Languages.byCode(code).native

    private fun codeOf(label: String): String? {
        val l = label.trim()
        koreanLabels.entries.firstOrNull { it.value == l }?.let { return it.key }
        return Languages.all.firstOrNull {
            it.native.equals(l, true) || it.english.equals(l, true) || it.code == l.lowercase()
        }?.code
    }

    fun parse(text: String, home: String): List<Term> = text.lines().mapIndexedNotNull { i, line ->
        val parts = line.replace("｜", "|").split("|").map { it.trim() }
        val key = parts.getOrNull(0).orEmpty()
        if (key.isEmpty() || key.startsWith("#")) return@mapIndexedNotNull null
        val variants = parts.getOrNull(1).orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val names = HashMap<String, String>()
        names[home] = key
        parts.getOrNull(2).orEmpty().replace("，", ",").split(",").forEach { pair ->
            val kv = pair.replace("＝", "=").split("=", limit = 2)
            if (kv.size == 2) {
                val code = codeOf(kv[0])
                val value = kv[1].trim()
                if (code != null && code != home && value.isNotEmpty()) names[code] = value
            }
        }
        Term(i, names, variants)
    }

    fun format(word: String, variants: List<String>, names: Map<String, String>, home: String): String {
        val translations = names.filter { it.key != home && it.value.isNotBlank() }
            .entries.joinToString(", ") { "${label(it.key, home)}=${it.value.trim()}" }
        return listOf(word.trim(), variants.joinToString(", "), translations).joinToString(" | ")
    }

    /* ---------- Matching ---------- */

    // Matches a Korean word even if the recognizer split it with spaces.
    private fun flex(word: String) = Regex(
        word.replace(" ", "").map { Regex.escape(it.toString()) }.joinToString("\\s*"),
        RegexOption.IGNORE_CASE
    )

    private fun pattern(word: String, code: String): Regex = when (code) {
        "ko" -> flex(word)
        "ja", "zh" -> Regex(Regex.escape(word))
        else -> Regex("(?<!\\p{L})" + Regex.escape(word) + "(?!\\p{L})", RegexOption.IGNORE_CASE)
    }

    // Finds placeholder code XZ<n> in translated text, including full-width forms.
    private fun codeRegex(n: Int): Regex {
        val digits = n.toString().map { d -> "[" + d + (d - '0' + 0xFF10).toChar() + "]" }.joinToString("\\s*")
        return Regex("[XxＸｘ]\\s*[ZzＺｚ]\\s*$digits(?![0-9０-９])")
    }

    fun prepare(src: String, sl: String, tl: String, terms: List<Term>, home: String): Prepared {
        var shown = src
        if (sl == home) {
            for (t in terms) {
                val key = t.names[home] ?: continue
                for (w in t.variants + key) shown = pattern(w, sl).replace(shown) { key }
            }
        }
        var input = shown
        val codes = LinkedHashMap<Regex, String>()
        terms.forEachIndexed { i, t ->
            val a = t.names[sl] ?: return@forEachIndexed
            val p = pattern(a, sl)
            if (!p.containsMatchIn(input)) return@forEachIndexed
            val n = i + 1
            input = p.replace(input) { "XZ$n" }
            codes[codeRegex(n)] = t.names[tl] ?: t.names["en"] ?: a
        }
        return Prepared(shown, input, codes)
    }
}