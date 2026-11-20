package com.example.interpreter

import android.content.SharedPreferences

// A second check of lesson translations. A translation that fails a quick check (another script
// mixed in, empty, or the sentence copied back) is reviewed again: first by Gemini Nano on the
// phone (free, offline), then by the AI engine chosen in settings. A result is only used when it
// passes the same check, so a bad review never replaces a translation with something worse.
object MeaningReview {

    fun suspicious(text: String, meaning: String, home: String): Boolean {
        val m = meaning.trim()
        if (m.isEmpty()) return true
        if (!LangCheck.looks(m, home)) return true
        return m.equals(text.trim(), ignoreCase = true)
    }

    private fun system(src: Lang, home: Lang) =
        "You are a careful translation editor. The user gives a ${src.english} sentence and a draft " +
        "${home.english} translation. If the draft is wrong, unnatural, incomplete, or mixes in other " +
        "languages or scripts (for example Chinese characters in Korean), rewrite it as a faithful, natural " +
        "${home.english} translation. If it is already good, repeat it unchanged. Reply with the final " +
        "${home.english} translation only, with no notes, quotes or romanization."

    private fun user(text: String, meaning: String) = "Sentence:\n$text\n\nDraft translation:\n$meaning"

    // Background thread. Returns a checked translation, or null when no engine could give a good one.
    fun review(prefs: SharedPreferences, text: String, meaning: String, src: Lang, home: Lang): String? {
        val tries = mutableListOf<() -> String>()
        if (OnDevice.ready) tries.add { OnDevice.review(text, meaning, src.code, home.code) }
        val e = RemoteTranslate.engine(prefs)
        if (e != null && e != OnDevice.ID) {
            if (RemoteTranslate.canWrite(e)) tries.add { RemoteTranslate.ask(prefs, e, system(src, home), user(text, meaning), 600) }
            else tries.add { RemoteTranslate.translate(prefs, e, text, src, home, "") }
        }
        for (t in tries) {
            try {
                val out = RemoteTranslate.clean(t()).lines().first { it.isNotBlank() }.trim()
                if (!suspicious(text, out, home.code)) return out
            } catch (_: Exception) {}
        }
        return null
    }

    fun available(prefs: SharedPreferences): Boolean =
        OnDevice.ready || RemoteTranslate.engine(prefs).let { it != null && it != OnDevice.ID }
}
