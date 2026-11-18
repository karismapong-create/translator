package com.example.interpreter

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Books to study with: classic English works whose copyright has expired (authors who died in 1955
 * or earlier, so they are free in Korea too). Catalog from the Gutendex API, text downloaded once and
 * kept on the phone with the distributor's header, footer and every mention of it removed, so only
 * the bare public-domain text remains.
 *
 * A book is cut into sections (inside chapters, about 250 words each). Each section becomes a lesson
 * in the learner's language and level, like a news lesson, cached per section, language and level.
 */
object BookStore {
    class Book(val id: Int, val title: String, val author: String, val url: String) {
        fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("author", author).put("url", url)
    }

    class Section(val index: Int, val chapter: String, val part: Int, val from: Int, val to: Int, val preview: String)

    private const val UA = "OwlInterpreter/1.0 (Android app; https://karismapong-create.github.io/translator/)"
    private const val MAX_DEATH_YEAR = 1955

    private fun txt(id: Int) = "https://www.gutenberg.org/ebooks/$id.txt.utf-8"

    // Learner favourites that are always offered, even before the catalog loads.
    val starter: List<Book> = listOf(
        Book(11, "Alice's Adventures in Wonderland", "Lewis Carroll", txt(11)),
        Book(55, "The Wonderful Wizard of Oz", "L. Frank Baum", txt(55)),
        Book(16, "Peter Pan", "J. M. Barrie", txt(16)),
        Book(46, "A Christmas Carol", "Charles Dickens", txt(46)),
        Book(1661, "The Adventures of Sherlock Holmes", "Arthur Conan Doyle", txt(1661)),
        Book(113, "The Secret Garden", "Frances Hodgson Burnett", txt(113)),
        Book(45, "Anne of Green Gables", "L. M. Montgomery", txt(45)),
        Book(236, "The Jungle Book", "Rudyard Kipling", txt(236)),
        Book(120, "Treasure Island", "Robert Louis Stevenson", txt(120)),
        Book(215, "The Call of the Wild", "Jack London", txt(215)),
        Book(35, "The Time Machine", "H. G. Wells", txt(35)),
        Book(1342, "Pride and Prejudice", "Jane Austen", txt(1342)),
        Book(84, "Frankenstein", "Mary Wollstonecraft Shelley", txt(84)),
        Book(2591, "Grimms' Fairy Tales", "Jacob Grimm, Wilhelm Grimm", txt(2591)),
        Book(1597, "Andersen's Fairy Tales", "Hans Christian Andersen", txt(1597))
    )

    private fun dir(ctx: Context) = File(ctx.filesDir, "books").apply { mkdirs() }
    private fun textFile(ctx: Context, id: Int) = File(dir(ctx), "$id.json")
    private fun shelfFile(ctx: Context) = File(dir(ctx), "shelf.json")
    private fun progressFile(ctx: Context) = File(dir(ctx), "progress.json")
    private fun lessonFile(ctx: Context, id: Int, sec: Int, lang: String, level: Int) =
        File(File(dir(ctx), "lessons").apply { mkdirs() }, "${id}_${sec}_${lang}_$level.json")

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", UA)
        try {
            var c = conn
            // http -> https redirects are not followed automatically.
            var hops = 0
            while (c.responseCode in 300..399 && hops < 5) {
                val next = c.getHeaderField("Location") ?: break
                c.disconnect()
                c = URL(URL(c.url, next).toString()).openConnection() as HttpURLConnection
                c.setRequestProperty("User-Agent", UA)
                c.connectTimeout = 15000
                c.readTimeout = 30000
                hops++
            }
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /* ---------------- Catalog ---------------- */

    /** Popular English books, or a search. Runs on a background thread. Throws on network failure. */
    fun catalog(search: String?): List<Book> {
        val q = StringBuilder("https://gutendex.com/books/?languages=en&copyright=false&mime_type=text%2Fplain")
        if (!search.isNullOrBlank()) q.append("&search=").append(URLEncoder.encode(search.trim(), "UTF-8"))
        val o = JSONObject(get(q.toString()))
        val results = o.optJSONArray("results") ?: return emptyList()
        val out = ArrayList<Book>()
        for (i in 0 until results.length()) {
            val b = results.optJSONObject(i) ?: continue
            val authors = b.optJSONArray("authors") ?: JSONArray()
            // Free in Korea too: every author died in 1955 or earlier.
            var ok = authors.length() > 0
            val names = ArrayList<String>()
            for (k in 0 until authors.length()) {
                val a = authors.optJSONObject(k) ?: continue
                val death = if (a.isNull("death_year")) null else a.optInt("death_year")
                if (death == null || death > MAX_DEATH_YEAR) ok = false
                names += a.optString("name").let { n ->
                    // "Carroll, Lewis" -> "Lewis Carroll"
                    val p = n.split(", ")
                    if (p.size == 2) "${p[1]} ${p[0]}" else n
                }
            }
            if (!ok) continue
            val id = b.optInt("id")
            out += Book(id, b.optString("title").substringBefore(";").trim(), names.joinToString(", "), txt(id))
        }
        return out
    }

    /* ---------------- Shelf ---------------- */

    fun shelf(ctx: Context): List<Book> = try {
        val a = JSONArray(shelfFile(ctx).takeIf { it.exists() }?.readText() ?: "[]")
        (0 until a.length()).mapNotNull { i ->
            a.optJSONObject(i)?.let { Book(it.optInt("id"), it.optString("title"), it.optString("author"), it.optString("url")) }
        }.filter { textFile(ctx, it.id).exists() }
    } catch (_: Exception) { emptyList() }

    fun has(ctx: Context, id: Int) = textFile(ctx, id).exists()

    /** Downloads and cleans a book. Runs on a background thread. Throws on failure. */
    fun download(ctx: Context, book: Book) {
        val raw = get(book.url)
        val blocks = clean(raw)
        if (blocks.size < 5) throw IllegalStateException("empty")
        val arr = JSONArray()
        for ((heading, text) in blocks) arr.put(JSONObject().put("h", heading).put("t", text))
        textFile(ctx, book.id).writeText(arr.toString())
        val list = shelf(ctx).filter { it.id != book.id }.toMutableList()
        list.add(0, book)
        shelfFile(ctx).writeText(JSONArray().apply { list.forEach { put(it.toJson()) } }.toString())
    }

    fun remove(ctx: Context, id: Int) {
        textFile(ctx, id).delete()
        val list = shelf(ctx).filter { it.id != id }
        shelfFile(ctx).writeText(JSONArray().apply { list.forEach { put(it.toJson()) } }.toString())
        File(dir(ctx), "lessons").listFiles()?.filter { it.name.startsWith("${id}_") }?.forEach { it.delete() }
    }

    private val headingRe = Regex(
        "^(CHAPTER|Chapter|BOOK|Book|PART|Part|STAVE|Stave|ADVENTURE|Adventure|SCENE|LETTER|Letter)\\b.*|^[IVXLC]+\\.?(\\s.*)?$"
    )

    // Keeps only the work itself: paragraphs (with lines joined) and chapter headings.
    private fun clean(raw: String): List<Pair<Boolean, String>> {
        var text = raw.replace("\r\n", "\n").replace('\r', '\n')
        val start = Regex("\\*\\*\\* ?START OF (THE|THIS) PROJECT GUTENBERG[^\n]*", RegexOption.IGNORE_CASE).find(text)
        if (start != null) text = text.substring(start.range.last + 1)
        val end = Regex("\\*\\*\\* ?END OF (THE|THIS) PROJECT GUTENBERG", RegexOption.IGNORE_CASE).find(text)
        if (end != null) text = text.substring(0, end.range.first)
        val out = ArrayList<Pair<Boolean, String>>()
        for (block in text.split(Regex("\n\\s*\n"))) {
            val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue
            val joined = lines.joinToString(" ").replace(Regex("\\s+"), " ").replace("_", "").trim()
            if (joined.isEmpty()) continue
            if (joined.contains("gutenberg", ignoreCase = true)) continue
            if (joined.startsWith("[Illustration", ignoreCase = true)) continue
            if (joined.none { it.isLetter() }) continue
            val heading = lines.size <= 2 && joined.length <= 70 && headingRe.matches(lines[0])
            out += heading to joined
        }
        // Drops the front matter (title page, contents) when the first chapter comes early.
        val first = out.indexOfFirst { it.first }
        if (first > 0 && first < out.size / 5) {
            // A contents list repeats the headings; start at the last heading that equals the first one.
            val firstText = out[first].second
            val again = out.withIndex().filter { it.index > first && it.value.first && it.value.second == firstText }.map { it.index }
            val begin = again.firstOrNull() ?: first
            return out.subList(begin, out.size).toList()
        }
        return out
    }

    fun blocks(ctx: Context, id: Int): List<Pair<Boolean, String>> = try {
        val a = JSONArray(textFile(ctx, id).readText())
        (0 until a.length()).map { i -> a.getJSONObject(i).let { it.optBoolean("h") to it.optString("t") } }
    } catch (_: Exception) { emptyList() }

    private val sectionCache = HashMap<Int, List<Section>>()

    /** Sections of about 250 words that never cross a chapter. */
    fun sections(ctx: Context, id: Int): List<Section> = sectionCache.getOrPut(id) {
        val bs = blocks(ctx, id)
        val out = ArrayList<Section>()
        var chapter = ""
        var part = 0
        var from = -1
        var words = 0
        fun close(to: Int) {
            if (from < 0 || to <= from) return
            part++
            val preview = bs.subList(from, to).firstOrNull { !it.first }?.second?.take(90) ?: ""
            out += Section(out.size, chapter, part, from, to, preview)
            from = -1
            words = 0
        }
        bs.forEachIndexed { i, (h, t) ->
            if (h) {
                close(i)
                chapter = t
                part = 0
                return@forEachIndexed
            }
            if (from < 0) from = i
            words += t.split(' ').size
            if (words >= 250) close(i + 1)
        }
        close(bs.size)
        out
    }

    fun sectionText(ctx: Context, id: Int, s: Section): String =
        blocks(ctx, id).subList(s.from, s.to).filter { !it.first }.joinToString("\n\n") { it.second }

    /* ---------------- Progress ---------------- */

    private fun progress(ctx: Context): JSONObject = try {
        JSONObject(progressFile(ctx).takeIf { it.exists() }?.readText() ?: "{}")
    } catch (_: Exception) { JSONObject() }

    fun read(ctx: Context, id: Int): Set<Int> {
        val a = progress(ctx).optJSONObject("$id")?.optJSONArray("read") ?: return emptySet()
        return (0 until a.length()).map { a.optInt(it) }.toSet()
    }

    fun last(ctx: Context, id: Int): Int = progress(ctx).optJSONObject("$id")?.optInt("last", 0) ?: 0

    fun markRead(ctx: Context, id: Int, sec: Int) {
        val p = progress(ctx)
        val b = p.optJSONObject("$id") ?: JSONObject()
        val set = read(ctx, id).toMutableSet().apply { add(sec) }
        b.put("read", JSONArray(set.sorted())).put("last", sec)
        p.put("$id", b)
        progressFile(ctx).writeText(p.toString())
    }

    /* ---------------- Lessons ---------------- */

    fun lesson(ctx: Context, id: Int, sec: Int, lang: String, level: Int): JSONObject? =
        lessonFile(ctx, id, sec, lang, level).takeIf { it.exists() }?.let { try { JSONObject(it.readText()) } catch (_: Exception) { null } }

    fun saveLesson(ctx: Context, id: Int, sec: Int, lang: String, level: Int, o: JSONObject) =
        lessonFile(ctx, id, sec, lang, level).writeText(o.toString())
}
