package com.example.interpreter

import org.json.JSONArray
import org.json.JSONObject

/**
 * Splits book text into sentences without breaking at a full stop that doesn't end one: titles
 * ("Mrs. Rachel Lynde", "Dr.", "St."), initials ("J. M. Barrie"), "e.g.", an ellipsis or a "!" or
 * "?" that the sentence goes on after ("Oh!" she said), and full stops inside quotes.
 * Also mends a lesson the AI split at such a place, by joining the pieces back together.
 */
object Sentences {

    // Words that end in a full stop without ending the sentence (compared in lower case, no dot).
    private val abbreviations = setOf(
        "mr", "mrs", "ms", "messrs", "mmes", "mme", "mlle", "dr", "drs", "st", "ste", "mt", "ft",
        "jr", "sr", "prof", "rev", "revd", "hon", "capt", "cpt", "col", "gen", "lt", "maj", "sgt",
        "cmdr", "adm", "gov", "pres", "supt", "insp", "esq", "fr", "sen", "rep", "sec", "bros",
        "co", "corp", "inc", "ltd", "dept", "univ", "assn", "ave", "rd", "blvd", "sq",
        "vs", "viz", "cf", "ca", "approx", "fig", "figs", "vol", "vols", "ch", "chap", "pp", "pg",
        "ed", "eds", "al", "op", "cit", "ibid",
        "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "sept", "oct", "nov", "dec",
    )

    // Only abbreviations when a number follows ("No. 7", "p. 12").
    private val beforeNumber = setOf("no", "nos", "p", "art", "nr")

    private const val closers = "\"'”’»)]}"
    private const val openers = "\"'“‘«([{"

    private fun isEnd(c: Char) = c == '.' || c == '!' || c == '?' || c == '…'

    /** The text's sentences, trimmed; a blank line always ends one. */
    fun split(text: String): List<String> {
        val out = ArrayList<String>()
        for (para in text.split(Regex("\n\\s*\n"))) {
            val p = para.replace(Regex("\\s+"), " ").trim()
            if (p.isEmpty()) continue
            var from = 0
            var i = 0
            while (i < p.length) {
                if (!isEnd(p[i])) { i++; continue }
                val stop = i
                var j = i
                while (j < p.length && (isEnd(p[j]) || p[j] in closers)) j++
                // A break needs a space after the stop and its closing quotes.
                if (j >= p.length || p[j] != ' ') { i = j; continue }
                if (breaksAt(p, stop, j)) {
                    p.substring(from, j).trim().takeIf { it.isNotEmpty() }?.let { out += it }
                    from = j + 1
                }
                i = j
            }
            p.substring(from).trim().takeIf { it.isNotEmpty() }?.let { out += it }
        }
        return out
    }

    // p[stop] is the first stop mark, p[after] is the space after the marks and closing quotes.
    private fun breaksAt(p: String, stop: Int, after: Int): Boolean {
        var k = after
        while (k < p.length && (p[k] == ' ' || p[k] in openers)) k++
        val next = p.getOrNull(k) ?: return true
        // The sentence goes on ("Mrs. rachel", "Oh!" she said, "Well... perhaps"): not an end.
        if (next.isLowerCase()) return false
        // Punctuation that can't start a sentence (a dash, a comma) keeps it going too.
        if (next == ',' || next == ';' || next == ':' || next == '—' || next == '–' || next == '-') return false
        val marks = p.substring(stop, after).filter { isEnd(it) }
        if (marks != ".") return true
        return !abbreviationBefore(p, stop, next)
    }

    // The word right before a single full stop is a title, an initial or another abbreviation.
    private fun abbreviationBefore(p: String, dot: Int, next: Char): Boolean {
        var s = dot
        while (s > 0 && (p[s - 1].isLetter() || p[s - 1] == '.')) s--
        val word = p.substring(s, dot)
        if (word.isEmpty()) return false
        // "J." or "U.S." or "e.g.": letters with dots between them ("said I." still ends one).
        if (word.length == 1 && word[0].isUpperCase()) return word != "I"
        if (word.contains('.') && word.split('.').all { it.length == 1 }) return true
        val w = word.lowercase()
        if (w in abbreviations) return true
        if (w in beforeNumber && next.isDigit()) return true
        return false
    }

    /** A piece ends where no sentence can end (after "Mrs.", an initial, a comma...). */
    fun unfinished(s: String): Boolean {
        val t = s.trimEnd()
        if (t.isEmpty()) return false
        val last = t.last()
        if (last == ',' || last == ';' || last == ':' || last == '—' || last == '–') return true
        if (last != '.') return false
        // Only the abbreviation rule applies here: what follows is in the next piece.
        return abbreviationBefore(t, t.length - 1, 'A') && !t.endsWith("..")
    }

    /** A piece that can't start a sentence: it continues the one before. */
    private fun continues(s: String): Boolean {
        val c = s.trimStart().trimStart { it in openers }.firstOrNull() ?: return false
        return c.isLowerCase()
    }

    /**
     * Groups of a lesson's sentence indexes that belong together (each group becomes one sentence),
     * or null when every sentence is whole. Only for languages that mark sentence starts with
     * capitals (the lower-case rule) and English (the abbreviation rule).
     */
    fun groups(texts: List<String>, lang: String): List<IntRange>? {
        if (texts.size < 2) return null
        val latin = lang in setOf("en", "fr", "de", "es", "it", "pt", "nl", "vi", "id", "tr", "pl")
        if (!latin) return null
        val out = ArrayList<IntRange>()
        var start = 0
        for (i in 1..texts.size) {
            val join = i < texts.size && (
                (lang == "en" && unfinished(texts[i - 1])) || continues(texts[i])
            )
            if (!join) { out += start until i; start = i }
        }
        return if (out.size == texts.size) null else out
    }

    /**
     * Joins the lesson's sentences in each group (texts and meanings). Returns the groups used, or
     * null when nothing changed.
     */
    fun mend(o: JSONObject, lang: String): List<IntRange>? {
        val arr = o.optJSONArray("sentences") ?: return null
        val items = (0 until arr.length()).map { arr.optJSONObject(it) ?: JSONObject() }
        val groups = groups(items.map { it.optString("text") }, lang) ?: return null
        val joined = JSONArray()
        for (g in groups) {
            if (g.first == g.last) { joined.put(items[g.first]); continue }
            val first = JSONObject(items[g.first].toString())
            first.put("text", g.joinToString(" ") { items[it].optString("text").trim() })
            first.put("meaning", g.map { items[it].optString("meaning").trim() }.filter { it.isNotEmpty() }.joinToString(" "))
            joined.put(first)
        }
        o.put("sentences", joined)
        return groups
    }
}
