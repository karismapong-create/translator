package com.example.interpreter

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

// Saved live conversations for review, and saving their lines to the vocabulary list.
object LiveStore {
    private const val KEY = "live_sessions"
    private const val KEEP = 30

    // One spoken line: who said it, in their words, and the translation.
    class Line(val mine: Boolean, val said: String, val saidLang: String, val tr: String, val trLang: String)

    class Session(val time: Long, val partnerLang: String, val scene: String, val lines: List<Line>)

    fun load(prefs: SharedPreferences): List<Session> {
        val arr = try { JSONArray(prefs.getString(KEY, "[]")) } catch (_: Exception) { JSONArray() }
        val out = ArrayList<Session>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val ls = o.optJSONArray("lines") ?: JSONArray()
            val lines = (0 until ls.length()).mapNotNull { k ->
                ls.optJSONObject(k)?.let {
                    Line(it.optBoolean("m"), it.optString("s"), it.optString("sl"), it.optString("t"), it.optString("tl"))
                }
            }
            out.add(Session(o.optLong("time"), o.optString("partner"), o.optString("scene"), lines))
        }
        return out.sortedByDescending { it.time }
    }

    fun save(prefs: SharedPreferences, s: Session) {
        if (s.lines.isEmpty()) return
        val arr = try { JSONArray(prefs.getString(KEY, "[]")) } catch (_: Exception) { JSONArray() }
        val lines = JSONArray()
        s.lines.forEach {
            lines.put(JSONObject().put("m", it.mine).put("s", it.said).put("sl", it.saidLang).put("t", it.tr).put("tl", it.trLang))
        }
        arr.put(JSONObject().put("time", s.time).put("partner", s.partnerLang).put("scene", s.scene).put("lines", lines))
        val keep = JSONArray()
        val start = (arr.length() - KEEP).coerceAtLeast(0)
        for (i in start until arr.length()) keep.put(arr.get(i))
        prefs.edit().putString(KEY, keep.toString()).apply()
    }

    fun delete(prefs: SharedPreferences, time: Long) {
        val arr = try { JSONArray(prefs.getString(KEY, "[]")) } catch (_: Exception) { JSONArray() }
        val keep = JSONArray()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { if (it.optLong("time") != time) keep.put(it) }
        prefs.edit().putString(KEY, keep.toString()).apply()
    }

    // Saves a line to the vocabulary list: the foreign sentence with its meaning in my language.
    fun toVocab(prefs: SharedPreferences, l: Line, home: String): Boolean {
        val (foreign, fLang, meaning) =
            if (l.saidLang != home) Triple(l.said, l.saidLang, l.tr) else Triple(l.tr, l.trLang, l.said)
        if (foreign.isBlank() || fLang == home) return false
        val lang = Languages.byCode(fLang)
        val arr = try { JSONArray(prefs.getString("saved", "[]")) } catch (_: Exception) { JSONArray() }
        for (i in 0 until arr.length()) if (arr.optJSONObject(i)?.optString("t") == foreign) return false
        arr.put(
            JSONObject()
                .put("t", foreign).put("m", meaning).put("e", "").put("em", "")
                .put("v", lang.speechTag).put("l", lang.code)
                .put("d", System.currentTimeMillis()).put("c", 0).put("w", 0)
        )
        prefs.edit().putString("saved", arr.toString()).apply()
        return true
    }
}
