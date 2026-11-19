package com.example.interpreter

import android.content.Context

/**
 * Translations the user corrected once ("청첩하다" -> "청혼하다"): applied to every translation shown
 * in the user's language afterwards, whatever engine made it. One rule per line: wrong=>right.
 */
object Corrections {
    private fun key(home: String) = "fixes_$home"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("interpreter", Context.MODE_PRIVATE)

    @Volatile private var cache: Pair<String, List<Pair<String, String>>>? = null

    fun list(ctx: Context, home: String): List<Pair<String, String>> {
        cache?.let { if (it.first == home) return it.second }
        val l = prefs(ctx).getString(key(home), "").orEmpty().lines().mapNotNull { line ->
            val p = line.split("=>", limit = 2)
            if (p.size == 2 && p[0].isNotBlank()) p[0].trim() to p[1].trim() else null
        }
        cache = home to l
        return l
    }

    private fun save(ctx: Context, home: String, l: List<Pair<String, String>>) {
        prefs(ctx).edit().putString(key(home), l.joinToString("\n") { "${it.first}=>${it.second}" }).apply()
        cache = home to l
    }

    fun add(ctx: Context, home: String, wrong: String, right: String) {
        if (wrong.isBlank()) return
        save(ctx, home, list(ctx, home).filter { it.first != wrong.trim() } + (wrong.trim() to right.trim()))
    }

    fun remove(ctx: Context, home: String, wrong: String) = save(ctx, home, list(ctx, home).filter { it.first != wrong })

    /** The text with every correction applied (longest first). */
    fun apply(ctx: Context, home: String, text: String): String {
        val l = list(ctx, home)
        if (l.isEmpty() || text.isEmpty()) return text
        var out = text
        for ((w, r) in l.sortedByDescending { it.first.length }) out = out.replace(w, r)
        return out
    }
}
