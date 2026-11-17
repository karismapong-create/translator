package com.example.interpreter

import android.app.Activity
import android.graphics.Color
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

// Adds the pronunciation line under a translation:
// Hangul for Korean speakers, Latin letters for everyone else reading Korean, Japanese or Chinese.
object Pronunciation {
    private val worker = Executors.newSingleThreadExecutor()

    fun attach(activity: Activity, parent: LinearLayout, anchor: TextView, text: String, lang: String, home: String) {
        if (lang == home) return
        if (home == "ko" && lang != "de") {
            Pronouncer.attach(activity, parent, anchor, text, lang)
            return
        }
        if (home != "ko" && lang !in setOf("ko", "ja", "zh")) return
        val view = TextView(activity).apply {
            this.text = "…"
            textSize = 18f
            setTextColor(Color.rgb(47, 91, 234))
        }
        parent.addView(view, parent.indexOfChild(anchor) + 1)
        worker.execute {
            val result = try {
                if (home == "ko") German.convert(text) else Romanizer.convert(text, lang).orEmpty()
            } catch (_: Throwable) {
                ""
            }
            activity.runOnUiThread {
                if (result.isBlank()) parent.removeView(view) else view.text = "[$result]"
            }
        }
    }
}

// German spelling to Hangul, following common Korean loanword conventions.
internal object German {
    private const val VOWELS = "aeiouyäöüə"

    private fun isV(c: Char?) = c != null && c in VOWELS

    fun convert(text: String): String =
        text.split(Regex("\\s+")).map { word(it) }.filter { it.isNotEmpty() }.joinToString(" ")

    private fun word(w0: String): String {
        var w = w0.lowercase().filter { it.isLetter() }.replace("ß", "ss")
        if (w.isEmpty()) return ""
        if (w.length > 2 && w.endsWith("er")) w = w.dropLast(2) + "ə"
        val ph = mutableListOf<String>()
        var i = 0
        while (i < w.length) {
            val r = w.substring(i)
            val step = when {
                r.startsWith("tsch") -> { ph += "ch"; 4 }
                r.startsWith("sch") -> {
                    ph += "sh"
                    val next = w.getOrNull(i + 3)
                    if (next != null && !isV(next)) ph += "eu"
                    3
                }
                i == 0 && (r.startsWith("sp") || r.startsWith("st")) -> { ph += listOf("sh", "eu"); 1 }
                r.startsWith("chs") -> { ph += listOf("k", "s"); 3 }
                r.startsWith("ch") -> { ph += "h"; 2 }
                r.startsWith("ck") -> { ph += "k"; 2 }
                r.startsWith("ph") -> { ph += "f"; 2 }
                r.startsWith("th") -> { ph += "t"; 2 }
                r.startsWith("qu") -> { ph += listOf("k", "v"); 2 }
                r.startsWith("tz") -> { ph += "ch"; 2 }
                r.startsWith("ei") || r.startsWith("ai") || r.startsWith("ey") -> { ph += listOf("a", "i"); 2 }
                r.startsWith("eu") || r.startsWith("äu") -> { ph += listOf("o", "i"); 2 }
                r.startsWith("ie") -> { ph += "i"; 2 }
                r.startsWith("au") -> { ph += listOf("a", "u"); 2 }
                r.startsWith("ng") -> { ph += "ng"; 2 }
                else -> { single(w, i, ph); 1 }
            }
            i += step
        }
        return Phon.render(ph, useTense = false, english = false)
    }

    private fun single(w: String, i: Int, ph: MutableList<String>) {
        val c = w[i]
        val n = w.getOrNull(i + 1)
        val p = w.getOrNull(i - 1)
        val last = i == w.length - 1
        if (n == c && !isV(c)) return
        when (c) {
            'a' -> ph += "a"
            'e', 'ä' -> ph += "e"
            'i' -> ph += "i"
            'o' -> ph += "o"
            'u' -> ph += "u"
            'ö' -> ph += "oe"
            'ü', 'y' -> ph += listOf("w", "i")
            'ə' -> ph += "eo"
            'h' -> if (!isV(p)) ph += "h"
            'j' -> ph += "y"
            'w' -> ph += "v"
            'v' -> ph += "f"
            'z' -> ph += "ch"
            'x' -> ph += listOf("k", "s")
            'c' -> ph += "k"
            's' -> ph += if (isV(n) && (p == null || isV(p))) "z" else "s"
            'b' -> ph += if (last) "p" else "b"
            'd' -> ph += if (last) "t" else "d"
            'g' -> ph += if (last) "k" else "g"
            else -> ph += c.toString()
        }
    }
}