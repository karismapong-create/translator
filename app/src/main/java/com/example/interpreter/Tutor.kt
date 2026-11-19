package com.example.interpreter

import android.content.SharedPreferences
import org.json.JSONObject

// Sends a multi-turn conversation to the chosen LLM and returns its text reply.
object Tutor {

    // Runs on a background thread. Turns are (role, content) pairs with roles "user" and "assistant".
    fun send(prefs: SharedPreferences, engine: String, system: String, turns: List<Pair<String, String>>): String =
        Providers.complete(prefs, engine, system, turns, 4096, 40000)

    /**
     * The JSON object in an AI answer. Small models sometimes wrap it in code fences, use curly
     * quotes, leave a trailing comma or stop before the end; those are repaired before giving up.
     */
    fun json(text: String): JSONObject? {
        val t = text.replace("```json", "").replace("```", "")
        val start = t.indexOf('{')
        if (start < 0) return null
        val end = t.lastIndexOf('}')
        if (end > start) try { return JSONObject(t.substring(start, end + 1)) } catch (_: Exception) {}
        var body = t.substring(start).trim()
            .replace('\u201C', '"').replace('\u201D', '"')
            .replace(Regex(",\\s*([}\\]])"), "$1")
        try { return JSONObject(body.substring(0, body.lastIndexOf('}').takeIf { it > 0 }?.plus(1) ?: body.length)) } catch (_: Exception) {}
        // Cut off: close an open string, then the open arrays and objects.
        // Line breaks inside a string are escaped on the way.
        var inString = false
        var escaped = false
        val open = ArrayDeque<Char>()
        val sb = StringBuilder()
        for (c in body) {
            if (inString) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') inString = false
                if (c == '\n') { sb.append("\\n"); continue }
                if (c == '\r') continue
                sb.append(c)
                continue
            }
            sb.append(c)
            when (c) {
                '"' -> inString = true
                '{' -> open.addLast('}')
                '[' -> open.addLast(']')
                '}', ']' -> if (open.isNotEmpty()) open.removeLast()
            }
        }
        body = sb.toString()
        if (inString) body += "\""
        body = body.trimEnd().removeSuffix(",")
        while (open.isNotEmpty()) body += open.removeLast()
        return try { JSONObject(body) } catch (_: Exception) { null }
    }

    /** One text field read straight from an answer whose JSON could not be repaired. */
    fun field(text: String, key: String): String? {
        val m = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)").find(text) ?: return null
        return m.groupValues[1].replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\").trim()
    }
}