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

    private fun sources(ctx: Context, lang: String, place: Int): List<JSONObject> {
        prepare(ctx)
        return listOfNotNull(
            parse(File(dir(ctx), "$lang/$place.json")),
            parse(File(ContentBuilder.dir(ctx), "$lang/$place.json"))
        )
    }

    private fun text(o: JSONObject?, home: String): String = o?.optString(home).orEmpty()

    // Questions in the format SituationView reads, only if they have meanings in my language.
    fun questions(ctx: Context, lang: String, place: Int, home: String): String? {
        for (src in sources(ctx, lang, place)) {
            val arr = src.optJSONArray("questions") ?: continue
            if (text(arr.optJSONObject(0)?.optJSONObject("title"), home).isBlank()) continue
            val out = JSONArray()
            for (i in 0 until arr.length()) {
                val q = arr.optJSONObject(i) ?: continue
                out.put(
                    JSONObject()
                        .put("ko", text(q.optJSONObject("title"), home))
                        .put("phrase", q.optString("phrase"))
                        .put("phrase_ko", text(q.optJSONObject("meaning"), home))
                )
            }
            if (out.length() > 0) return JSONObject().put("questions", out).toString()
        }
        return null
    }

    // A dialogue in the format SituationView reads, only if it has meanings in my language.
    fun dialogue(ctx: Context, lang: String, place: Int, index: Int, home: String): String? {
        for (src in sources(ctx, lang, place)) {
            val d = src.optJSONArray("dialogues")?.optJSONObject(index) ?: continue
            val lines = d.optJSONArray("lines") ?: continue
            if (text(lines.optJSONObject(0)?.optJSONObject("m"), home).isBlank()) continue
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
            if (out.length() > 0) {
                return JSONObject().put("lines", out).put("tips", text(d.optJSONObject("tips"), home)).toString()
            }
        }
        return null
    }
}