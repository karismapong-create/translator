package com.example.interpreter

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

// Built-in phrasebook shipped in assets/situations.zip, plus content built on this phone with ContentBuilder.
// Each file holds one foreign language and one place; meanings are stored per home language.
object BasePack {
    private const val ASSET = "situations.zip"
    private const val DIR = "basepack"

    @Volatile private var ready = false

    private fun dir(ctx: Context) = File(ctx.filesDir, DIR)

    // Unzips the shipped pack once after each install or update. Runs on a background thread.
    fun prepare(ctx: Context) {
        if (ready) return
        synchronized(this) {
            if (ready) return
            val prefs = ctx.getSharedPreferences("interpreter", Context.MODE_PRIVATE)
            val stamp = try {
                ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime
            } catch (_: Exception) {
                0L
            }
            if (prefs.getLong("basepack_stamp", -1L) != stamp) {
                val out = dir(ctx)
                out.deleteRecursively()
                out.mkdirs()
                try {
                    ctx.assets.open(ASSET).use { input ->
                        ZipInputStream(input).use { zip ->
                            var e = zip.nextEntry
                            while (e != null) {
                                if (!e.isDirectory) {
                                    val f = File(out, e.name)
                                    if (f.canonicalPath.startsWith(out.canonicalPath)) {
                                        f.parentFile?.mkdirs()
                                        f.outputStream().use { zip.copyTo(it) }
                                    }
                                }
                                e = zip.nextEntry
                            }
                        }
                    }
                } catch (_: Exception) {
                    // This build has no pack yet.
                }
                prefs.edit().putLong("basepack_stamp", stamp).apply()
            }
            ready = true
        }
    }

    private fun parse(f: File): JSONObject? =
        if (f.exists()) try { JSONObject(f.readText()) } catch (_: Exception) { null } else null

    // One file per foreign language and place, never a mix: the questions and their dialogues must come
    // from the same file, or a dialogue can belong to a different question. The more complete of the
    // shipped file and the one built or imported on this phone wins (this phone's on a tie).
    private fun source(ctx: Context, lang: String, place: Int, home: String): JSONObject? {
        prepare(ctx)
        val shipped = parse(File(dir(ctx), "$lang/$place.json"))
        val mine = parse(File(ContentBuilder.dir(ctx), "$lang/$place.json"))
        val a = LangCheck.score(mine, home, lang)
        val b = LangCheck.score(shipped, home, lang)
        return when {
            a < 0 && b < 0 -> null
            a >= b -> mine
            else -> shipped
        }
    }

    private fun text(o: JSONObject?, home: String): String = o?.optString(home).orEmpty()

    // Questions in the format SituationView reads, only if they have meanings in my language.
    // Entries whose sentence and meaning were swapped are put right.
    fun questions(ctx: Context, lang: String, place: Int, home: String): String? {
        val src = source(ctx, lang, place, home) ?: return null
        val arr = src.optJSONArray("questions") ?: return null
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val raw = arr.optJSONObject(i) ?: continue
            // Kept even when it can't be fixed, so every question stays at the index of its dialogue.
            val q = LangCheck.fixQuestion(JSONObject(raw.toString()), lang, home) ?: raw
            out.put(
                JSONObject()
                    .put("ko", text(q.optJSONObject("title"), home))
                    .put("phrase", q.optString("phrase"))
                    .put("phrase_ko", text(q.optJSONObject("meaning"), home))
            )
        }
        return if (out.length() > 0) JSONObject().put("questions", out).toString() else null
    }

    // A dialogue in the format SituationView reads, from the same file as the questions, only if it has
    // meanings in my language and its lines are in the right languages.
    fun dialogue(ctx: Context, lang: String, place: Int, index: Int, home: String): String? {
        val src = source(ctx, lang, place, home) ?: return null
        val d = src.optJSONArray("dialogues")?.optJSONObject(index)?.let { JSONObject(it.toString()) } ?: return null
        val lines = d.optJSONArray("lines") ?: return null
        if (text(lines.optJSONObject(0)?.optJSONObject("m"), home).isBlank()) return null
        if (!LangCheck.fixDialogue(d, lang, home)) return null
        val out = JSONArray()
        for (i in 0 until lines.length()) {
            val l = lines.optJSONObject(i) ?: continue
            out.put(
                JSONObject()
                    .put("who", l.optString("who", "A"))
                    .put("text", l.optString("text"))
                    .put("ko", text(l.optJSONObject("m"), home))
            )
        }
        if (out.length() == 0) return null
        return JSONObject().put("lines", out).put("tips", text(d.optJSONObject("tips"), home)).toString()
    }
}
