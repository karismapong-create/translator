package com.example.interpreter

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// The vocabulary list ("saved" in preferences), its spaced-repetition schedule and the study streak.
//
// Spaced repetition: every word sits in a box 0..6. Knowing it moves it up a box and pushes the next
// review further away (1, 3, 7, 14, 30, 60 days); forgetting it sends it back to box 0, to be asked
// again in the same session. Fields per word: b = box, n = next review time (0 = never studied).
object VocabStore {
    private const val KEY = "saved"
    private val days = longArrayOf(0, 1, 3, 7, 14, 30, 60)
    private const val DAY = 24L * 60 * 60 * 1000

    fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    fun read(prefs: SharedPreferences): JSONArray =
        try { JSONArray(prefs.getString(KEY, "[]")) } catch (_: Exception) { JSONArray() }

    fun write(prefs: SharedPreferences, arr: JSONArray) = prefs.edit().putString(KEY, arr.toString()).apply()

    // Adds a word or phrase unless it is already there. Returns false for a duplicate.
    fun save(
        prefs: SharedPreferences, text: String, meaning: String, langCode: String,
        example: String = "", exampleMeaning: String = ""
    ): Boolean {
        if (text.isBlank()) return false
        val arr = read(prefs)
        for (i in 0 until arr.length()) if (arr.optJSONObject(i)?.optString("t") == text) return false
        val lang = Languages.byCode(langCode)
        arr.put(
            JSONObject()
                .put("t", text).put("m", meaning).put("e", example).put("em", exampleMeaning)
                .put("v", lang.speechTag).put("l", lang.code)
                .put("d", System.currentTimeMillis()).put("c", 0).put("w", 0)
        )
        write(prefs, arr)
        StudyLog.app?.let { StudyLog.saved(it, text) }
        return true
    }

    // Words due for review now (studied before), then never-studied words, for the user's foreign languages.
    fun dueCount(prefs: SharedPreferences, home: String): Int {
        val arr = read(prefs)
        val now = System.currentTimeMillis()
        var n = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("l") == home || o.optString("m").isBlank()) continue
            val next = o.optLong("n", 0)
            if (next in 1..now) n++
        }
        return n + minOf(newLeft(prefs), newCount(prefs, home))
    }

    private fun newCount(prefs: SharedPreferences, home: String): Int {
        val arr = read(prefs)
        return (0 until arr.length()).count {
            val o = arr.optJSONObject(it)
            o != null && o.optLong("n", 0) == 0L && o.optString("l") != home && o.optString("m").isNotBlank()
        }
    }

    // New words to introduce per day, so reviews never pile up.
    fun newPerDay(prefs: SharedPreferences) = prefs.getInt("study_new_per_day", 10)
    fun newLeft(prefs: SharedPreferences) = (newPerDay(prefs) - prefs.getInt("study_new_" + today(), 0)).coerceAtLeast(0)

    // knew: 2 = knew it, 1 = unsure, 0 = forgot. Returns the next review time.
    fun answer(prefs: SharedPreferences, index: Int, knew: Int): Long {
        val arr = read(prefs)
        val o = arr.optJSONObject(index) ?: return 0
        val wasNew = o.optLong("n", 0) == 0L
        val now = System.currentTimeMillis()
        var box = o.optInt("b", 0)
        val next = when (knew) {
            2 -> { box = minOf(6, box + 1); now + days[box] * DAY }
            1 -> { box = maxOf(1, box); now + maxOf(DAY, days[box] * DAY / 2) }
            else -> { box = 0; now + 10 * 60 * 1000L }
        }
        o.put("b", box).put("n", next)
        val counter = if (knew == 2) "c" else "w"
        if (knew != 1) o.put(counter, o.optInt(counter, 0) + 1)
        write(prefs, arr)
        val t = today()
        val e = prefs.edit().putInt("study_count_$t", prefs.getInt("study_count_$t", 0) + 1)
        if (wasNew) e.putInt("study_new_$t", prefs.getInt("study_new_$t", 0) + 1)
        val studied = studyDays(prefs).apply { add(t) }
        e.putString("study_days", JSONArray(studied.toList()).toString()).apply()
        return next
    }

    private fun studyDays(prefs: SharedPreferences): MutableSet<String> {
        val arr = try { JSONArray(prefs.getString("study_days", "[]")) } catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).map { arr.optString(it) }.toMutableSet()
    }

    // Days in a row with at least one review, ending today (or yesterday, if not studied yet today).
    fun streak(prefs: SharedPreferences): Int {
        val set = studyDays(prefs)
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        var t = System.currentTimeMillis()
        if (fmt.format(Date(t)) !in set) t -= DAY
        var n = 0
        while (fmt.format(Date(t)) in set) { n++; t -= DAY }
        return n
    }

    fun doneToday(prefs: SharedPreferences) = prefs.getInt("study_count_" + today(), 0)
    fun goal(prefs: SharedPreferences) = prefs.getInt("study_goal", 20)

    fun setHook(prefs: SharedPreferences, index: Int, hook: String) {
        val arr = read(prefs)
        arr.optJSONObject(index)?.put("img", hook) ?: return
        write(prefs, arr)
    }
}
