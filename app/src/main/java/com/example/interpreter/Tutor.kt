package com.example.interpreter

import android.content.SharedPreferences
import org.json.JSONObject

// Sends a multi-turn conversation to the chosen LLM and returns its text reply.
object Tutor {

    // Runs on a background thread. Turns are (role, content) pairs with roles "user" and "assistant".
    fun send(prefs: SharedPreferences, engine: String, system: String, turns: List<Pair<String, String>>): String =
        Providers.complete(prefs, engine, system, turns, 4096, 40000)

    fun json(text: String): JSONObject? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try { JSONObject(text.substring(start, end + 1)) } catch (_: Exception) { null }
    }
}