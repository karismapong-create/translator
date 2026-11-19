package com.example.interpreter

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * One package (a zip) for the app's content, so it can be backed up, moved to another phone, and
 * later handed out to subscribers from a server:
 *
 *   manifest.json                       format, version, date, what is inside
 *   books/shelf.json                    the books (id, title, author, source)
 *   books/text/<id>.json                the cleaned public-domain text
 *   books/lessons/<id>_<part>_<lang>_<level>.json
 *   books/art/<id>_<part>_<lang>_<level>_<sentence>.jpg
 *   units/<lang>.json                   idioms found per sentence
 *
 * The situation dialogues DB is a separate DB with its own export/import (situations.zip) and ships
 * as assets/situations.zip; a book package never touches it.
 *
 * Import adds what is missing and never deletes.
 */
object ContentPack {
    const val FORMAT = "owl-content-pack"
    const val VERSION = 1

    class Summary(var books: Int = 0, var lessons: Int = 0, var pictures: Int = 0, var idioms: Int = 0, var dialogueFiles: Int = 0) {
        fun isEmpty() = books + lessons + pictures + idioms == 0
        /** The file was a situation dialogues DB, not a book package. */
        fun wrongKind() = isEmpty() && dialogueFiles > 0
    }

    private fun booksDir(ctx: Context) = File(ctx.filesDir, "books")
    private fun unitsDir(ctx: Context) = File(ctx.filesDir, "units")

    /* ---------------- Export ---------------- */

    /**
     * Writes the package to Download and returns its name. Background thread.
     * [bookIds]: books to include (with all their lessons and pictures). [situations]: the dialogue DB.
     */
    fun export(ctx: Context, bookIds: Set<Int>, progress: (String) -> Unit = {}): String {
        if (Build.VERSION.SDK_INT < 29) throw IllegalStateException("Android 10 or newer is needed")
        val name = "owl-books-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".zip"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("can't create the file")
        val s = Summary()
        ctx.contentResolver.openOutputStream(uri)?.use { os ->
            ZipOutputStream(os).use { zip ->
                fun put(path: String, bytes: ByteArray) {
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(bytes)
                    zip.closeEntry()
                }
                val books = BookStore.shelf(ctx).filter { it.id in bookIds }
                if (books.isNotEmpty()) {
                    put("books/shelf.json", JSONArray().apply { books.forEach { put(it.toJson()) } }.toString().toByteArray())
                    val prefixes = books.map { "${it.id}_" }
                    for (b in books) {
                        File(booksDir(ctx), "${b.id}.json").takeIf { it.exists() }?.let { put("books/text/${b.id}.json", it.readBytes()) }
                        s.books++
                    }
                    File(booksDir(ctx), "lessons").listFiles()?.filter { f -> prefixes.any { f.name.startsWith(it) } }?.forEach {
                        put("books/lessons/${it.name}", it.readBytes()); s.lessons++
                    }
                    val art = File(booksDir(ctx), "art").listFiles()?.filter { f -> prefixes.any { f.name.startsWith(it) } }.orEmpty()
                    art.forEachIndexed { i, f ->
                        put("books/art/${f.name}", f.readBytes()); s.pictures++
                        if (i % 20 == 0) progress("🎨 ${i + 1}/${art.size}")
                    }
                }
                unitsDir(ctx).listFiles()?.filter { it.extension == "json" }?.forEach {
                    put("units/${it.name}", it.readBytes()); s.idioms++
                }
                val manifest = JSONObject()
                    .put("format", FORMAT).put("kind", "books").put("version", VERSION)
                    .put("created", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date()))
                    .put("books", JSONArray().apply { books.forEach { put(it.id) } })
                    .put("lessons", s.lessons).put("pictures", s.pictures)
                put("manifest.json", manifest.toString(2).toByteArray())
            }
        } ?: throw IllegalStateException("can't write the file")
        return "Download/$name"
    }

    /* ---------------- Import ---------------- */

    /** Adds the package's content to this phone. Works for a file now and a server download later. */
    fun import(ctx: Context, input: InputStream, progress: (String) -> Unit = {}): Summary {
        val s = Summary()
        val codes = Languages.all.map { it.code }.toSet()
        val placeFile = Regex("^(\\d{1,2})\\.json$")
        val safe = Regex("^[A-Za-z0-9_.-]+$")
        var shelf: JSONArray? = null
        // Lessons this phone already has with different sentences: their pictures in the package belong
        // to other sentences, so they are skipped (lessons come before pictures in a package).
        val keptOwn = HashSet<String>()
        ZipInputStream(input).use { zip ->
            var e = zip.nextEntry
            var n = 0
            while (e != null) {
                if (!e.isDirectory) {
                    val parts = e.name.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != ".." }
                    val last = parts.lastOrNull().orEmpty()
                    if (++n % 25 == 0) progress("📥 $n")
                    when {
                        parts.size == 2 && parts[0] == "books" && last == "shelf.json" ->
                            shelf = try { JSONArray(zip.readBytes().toString(Charsets.UTF_8)) } catch (_: Exception) { null }
                        parts.size == 3 && parts[0] == "books" && parts[1] in setOf("text", "lessons", "art") && safe.matches(last) -> {
                            val dir = if (parts[1] == "text") booksDir(ctx) else File(booksDir(ctx), parts[1])
                            dir.mkdirs()
                            val f = File(dir, last)
                            val bytes = zip.readBytes()
                            if (parts[1] == "art" && last.substringBeforeLast('_') in keptOwn) {
                                // skip: picture for a lesson this phone made differently
                            } else if (!f.exists()) {
                                f.writeBytes(bytes)
                                when (parts[1]) { "lessons" -> s.lessons++; "art" -> s.pictures++ }
                            } else if (parts[1] == "lessons" && !f.readBytes().contentEquals(bytes)) {
                                keptOwn += last.removeSuffix(".json")
                            }
                        }
                        parts.size == 2 && parts[0] == "units" && safe.matches(last) ->
                            if (mergeUnits(ctx, last, zip.readBytes())) s.idioms++
                        // A dialogue DB file (<lang>/<place>.json): not for this DB, only counted for the hint.
                        parts.size >= 2 && parts[parts.size - 2] in codes && placeFile.matches(last) -> s.dialogueFiles++
                    }
                }
                e = zip.nextEntry
            }
        }
        shelf?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val b = BookStore.Book(o.optInt("id"), o.optString("title"), o.optString("author"), o.optString("url"))
                if (BookStore.addToShelf(ctx, b)) s.books++
            }
        }
        return s
    }

    // Adds idioms for sentences this phone hasn't checked yet.
    private fun mergeUnits(ctx: Context, name: String, bytes: ByteArray): Boolean {
        val incoming = try { JSONObject(bytes.toString(Charsets.UTF_8)) } catch (_: Exception) { return false }
        val f = File(unitsDir(ctx), name)
        val mine = try { if (f.exists()) JSONObject(f.readText()) else JSONObject() } catch (_: Exception) { JSONObject() }
        var added = 0
        for (k in incoming.keys()) if (!mine.has(k)) { mine.put(k, incoming.get(k)); added++ }
        if (added == 0) return false
        f.parentFile?.mkdirs()
        f.writeText(mine.toString())
        StudyUnits.forget()
        return true
    }

    /* ---------------- Packages shipped with the app ---------------- */

    /**
     * Imports every package in assets/packs/ once per app version (an import only adds what is
     * missing, so running it again after an update is safe). Background thread.
     */
    fun installBundled(ctx: Context) {
        val prefs = ctx.getSharedPreferences("interpreter", Context.MODE_PRIVATE)
        val version = try {
            val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        } catch (_: Exception) { 0L }
        if (prefs.getLong("packs_installed_version", -1) == version) return
        val names = try { ctx.assets.list("packs")?.filter { it.endsWith(".zip") }.orEmpty() } catch (_: Exception) { emptyList() }
        for (name in names.sorted()) {
            try { ctx.assets.open("packs/$name").use { import(ctx, it) } } catch (_: Exception) {}
        }
        prefs.edit().putLong("packs_installed_version", version).apply()
    }
}
