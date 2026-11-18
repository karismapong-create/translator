package com.example.interpreter

import org.json.JSONArray
import org.json.JSONObject

// Checks that a sentence is written in the language it should be, by its script. AI engines,
// especially small ones, sometimes swap the sentence and its meaning or mix languages; this
// catches that before it reaches the phrasebook.
object LangCheck {
    private fun count(t: String, test: (Int) -> Boolean) = t.codePoints().filter(test).count().toInt()
    private fun hangul(c: Int) = c in 0xAC00..0xD7A3 || c in 0x1100..0x11FF || c in 0x3130..0x318F
    private fun kana(c: Int) = c in 0x3040..0x30FF || c in 0x31F0..0x31FF
    private fun han(c: Int) = c in 0x4E00..0x9FFF || c in 0x3400..0x4DBF
    private fun latin(c: Int) = (c in 'a'.code..'z'.code) || (c in 'A'.code..'Z'.code) || c in 0x00C0..0x024F

    fun looks(text: String, code: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        val h = count(t, ::hangul)
        val k = count(t, ::kana)
        val c = count(t, ::han)
        val l = count(t, ::latin)
        return when (code) {
            // Korean: Hangul, and no Japanese or Chinese characters mixed in.
            "ko" -> h > 0 && k == 0 && c == 0
            "ja" -> (k > 0 || c > 0) && h == 0
            "zh" -> c > 0 && k == 0 && h == 0
            // Latin languages: mostly Latin letters, no Asian scripts.
            else -> l > 0 && h == 0 && k == 0 && c == 0
        }
    }

    // A question is good when its phrase is in the foreign language and its meaning in mine.
    // Returns the fixed question (phrase and meaning swapped back), or null when it can't be saved.
    fun fixQuestion(q: JSONObject, foreign: String, home: String): JSONObject? {
        val phrase = q.optString("phrase")
        val meaning = q.optJSONObject("meaning")?.optString(home).orEmpty()
        if (looks(phrase, foreign) && (meaning.isEmpty() || looks(meaning, home))) return q
        if (looks(meaning, foreign) && looks(phrase, home)) {
            q.put("phrase", meaning)
            (q.optJSONObject("meaning") ?: JSONObject().also { q.put("meaning", it) }).put(home, phrase)
            return q
        }
        return null
    }

    // True when every line is in the foreign language and every meaning in mine (after swapping back
    // lines whose text and meaning were reversed).
    fun fixDialogue(d: JSONObject, foreign: String, home: String): Boolean {
        val lines = d.optJSONArray("lines") ?: return false
        if (lines.length() == 0) return false
        var bad = 0
        for (i in 0 until lines.length()) {
            val l = lines.optJSONObject(i) ?: continue
            val text = l.optString("text")
            val m = l.optJSONObject("m")?.optString(home).orEmpty()
            when {
                looks(text, foreign) && (m.isEmpty() || looks(m, home)) -> {}
                looks(m, foreign) && looks(text, home) -> {
                    l.put("text", m)
                    l.optJSONObject("m")?.put(home, text)
                }
                else -> bad++
            }
        }
        // Allow a stray line or two (a name, a number); more means the dialogue is broken.
        return bad <= maxOf(1, lines.length() / 10)
    }

    // Questions that stay wrong even after swapping back (for example a Korean "Japanese" sentence).
    fun brokenQuestions(o: JSONObject, foreign: String): Int {
        val qs = o.optJSONArray("questions") ?: return 0
        val homes = Languages.all.map { it.code }.filter { it != foreign }
        return (0 until qs.length()).count { i ->
            val q = qs.optJSONObject(i)?.let { JSONObject(it.toString()) } ?: return@count false
            val withMeaning = homes.filter { q.optJSONObject("meaning")?.optString(it)?.isNotBlank() == true }
            if (withMeaning.isEmpty()) !looks(q.optString("phrase"), foreign)
            else withMeaning.any { fixQuestion(JSONObject(q.toString()), foreign, it) == null }
        }
    }

    // Cleans a whole place file in place: swaps reversed entries back and drops broken dialogues so
    // they are made again. A question list with more than two broken sentences is dropped whole, with
    // its dialogues, so the place is made again from scratch. Returns how many entries changed.
    fun repair(o: JSONObject, foreign: String): Int {
        var changes = 0
        val homes = Languages.all.map { it.code }.filter { it != foreign }
        val qs = o.optJSONArray("questions") ?: return 0
        if (brokenQuestions(o, foreign) > 2) {
            val n = qs.length() + (o.optJSONArray("dialogues")?.length() ?: 0)
            o.put("questions", JSONArray())
            o.put("dialogues", JSONArray())
            return n
        }
        for (i in 0 until qs.length()) {
            val q = qs.optJSONObject(i) ?: continue
            val before = q.toString()
            for (h in homes) if (q.optJSONObject("meaning")?.has(h) == true) fixQuestion(q, foreign, h)
            if (q.toString() != before) changes++
        }
        val ds = o.optJSONArray("dialogues") ?: return changes
        for (i in 0 until ds.length()) {
            val d = ds.optJSONObject(i) ?: continue
            val before = d.toString()
            val home = homes.firstOrNull { h -> d.optJSONArray("lines")?.optJSONObject(0)?.optJSONObject("m")?.has(h) == true }
            if (home == null || !fixDialogue(d, foreign, home)) {
                ds.put(i, JSONObject.NULL)
                changes++
            } else if (d.toString() != before) changes++
        }
        return changes
    }

    // How complete a place file is for a meaning language: questions plus dialogues with that meaning.
    fun score(o: JSONObject?, home: String, foreign: String? = null): Int {
        if (o == null) return -1
        val qs = o.optJSONArray("questions") ?: return -1
        if (foreign != null && brokenQuestions(o, foreign) > 2) return -1
        if (qs.optJSONObject(0)?.optJSONObject("title")?.optString(home).isNullOrBlank()) return -1
        val ds = o.optJSONArray("dialogues") ?: JSONArray()
        var n = 1
        for (i in 0 until minOf(ds.length(), qs.length())) {
            if (ds.optJSONObject(i)?.optJSONArray("lines")?.optJSONObject(0)?.optJSONObject("m")?.optString(home)?.isNotBlank() == true) n++
        }
        return n
    }
}
