package com.example.interpreter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
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
        Book(1597, "Andersen's Fairy Tales", "Hans Christian Andersen", txt(1597)),
        Book(74, "The Adventures of Tom Sawyer", "Mark Twain", txt(74)),
        Book(76, "Adventures of Huckleberry Finn", "Mark Twain", txt(76)),
        Book(514, "Little Women", "Louisa May Alcott", txt(514)),
        Book(271, "Black Beauty", "Anna Sewell", txt(271)),
        Book(174, "The Picture of Dorian Gray", "Oscar Wilde", txt(174)),
        Book(345, "Dracula", "Bram Stoker", txt(345)),
        Book(1260, "Jane Eyre", "Charlotte Brontë", txt(1260)),
        Book(768, "Wuthering Heights", "Emily Brontë", txt(768))
    )

    // Other names people search for (Korean, Japanese, shorthand), by book id.
    private val aliases: Map<Int, List<String>> = mapOf(
        11 to listOf("이상한 나라의 앨리스", "앨리스", "不思議の国のアリス", "アリス", "爱丽丝梦游仙境", "Alice in Wonderland"),
        55 to listOf("오즈의 마법사", "오즈", "オズの魔法使い", "绿野仙踪", "Wizard of Oz"),
        16 to listOf("피터 팬", "피터팬", "ピーター・パン", "彼得潘"),
        46 to listOf("크리스마스 캐럴", "크리스마스 캐롤", "스크루지", "クリスマス・キャロル", "圣诞颂歌"),
        1661 to listOf("셜록 홈즈의 모험", "셜록 홈즈", "셜록 홈스", "셜록홈즈", "シャーロック・ホームズの冒険", "福尔摩斯探案集", "Sherlock Holmes"),
        113 to listOf("비밀의 화원", "秘密の花園", "秘密花园"),
        45 to listOf("빨강 머리 앤", "빨간 머리 앤", "빨간머리앤", "赤毛のアン", "绿山墙的安妮"),
        236 to listOf("정글북", "정글 북", "ジャングル・ブック", "丛林之书"),
        120 to listOf("보물섬", "宝島", "金银岛"),
        215 to listOf("야성의 부름", "야성의 외침", "野性の呼び声", "野性的呼唤"),
        35 to listOf("타임머신", "타임 머신", "タイム・マシン", "时间机器"),
        1342 to listOf("오만과 편견", "高慢と偏見", "傲慢与偏见"),
        84 to listOf("프랑켄슈타인", "フランケンシュタイン", "弗兰肯斯坦"),
        2591 to listOf("그림 동화", "그림형제 동화", "グリム童話", "格林童话", "Grimm"),
        1597 to listOf("안데르센 동화", "アンデルセン童話", "安徒生童话"),
        74 to listOf("톰 소여의 모험", "톰 소여", "トム・ソーヤーの冒険", "汤姆·索亚历险记"),
        76 to listOf("허클베리 핀의 모험", "허클베리 핀", "ハックルベリー・フィンの冒険", "哈克贝利·费恩历险记"),
        514 to listOf("작은 아씨들", "若草物語", "小妇人"),
        271 to listOf("블랙 뷰티", "黒馬物語", "黑骏马"),
        174 to listOf("도리언 그레이의 초상", "도리안 그레이", "ドリアン・グレイの肖像", "道林·格雷的画像"),
        345 to listOf("드라큘라", "ドラキュラ", "德古拉"),
        1260 to listOf("제인 에어", "ジェーン・エア", "简·爱"),
        768 to listOf("폭풍의 언덕", "嵐が丘", "呼啸山庄")
    )

    // Author names as people write them in other languages.
    private val authorAliases: Map<String, List<String>> = mapOf(
        "Lewis Carroll" to listOf("루이스 캐럴", "ルイス・キャロル"),
        "Arthur Conan Doyle" to listOf("코난 도일", "아서 코난 도일", "コナン・ドイル"),
        "Charles Dickens" to listOf("찰스 디킨스", "디킨스", "ディケンズ"),
        "Mark Twain" to listOf("마크 트웨인", "マーク・トウェイン"),
        "Jane Austen" to listOf("제인 오스틴", "ジェーン・オースティン"),
        "Oscar Wilde" to listOf("오스카 와일드", "オスカー・ワイルド"),
        "H. G. Wells" to listOf("허버트 조지 웰스", "웰스", "ウェルズ"),
        "Rudyard Kipling" to listOf("러디어드 키플링", "키플링", "キプリング"),
        "Robert Louis Stevenson" to listOf("스티븐슨", "スティーヴンソン")
    )

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    // Edit distance, for typos ("sherlok" -> "sherlock").
    private fun distance(a: String, b: String): Int {
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]; d[0] = i
            for (j in 1..b.length) {
                val t = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = t
            }
        }
        return d[b.length]
    }

    private fun close(query: String, name: String): Boolean {
        val q = norm(query); val n = norm(name)
        if (q.isEmpty() || n.isEmpty()) return false
        if (n.contains(q) || (q.length >= 4 && q.contains(n) && n.length >= 3)) return true
        if (q.length < 4) return false
        val allowed = if (q.length >= 8) 2 else 1
        // Compare with the whole name and with its words.
        if (distance(q, n) <= allowed) return true
        return name.split(Regex("[\\s,.:;'·・-]+")).any { w -> norm(w).length >= 4 && distance(q, norm(w)) <= allowed }
    }

    /** Books on the phone's lists that match a search by any name (Korean/Japanese/Chinese titles, typos, authors). */
    fun findLocal(ctx: Context, query: String): List<Book> {
        val pool = (shelf(ctx) + starter).distinctBy { it.id }
        return pool.filter { b ->
            close(query, b.title) || close(query, b.author) ||
                aliases[b.id].orEmpty().any { close(query, it) } ||
                authorAliases.entries.any { (en, others) -> b.author.contains(en) && others.any { close(query, it) } }
        }
    }

    /** An English author name for a name written in another language, if known. */
    fun englishAuthor(query: String): String? =
        authorAliases.entries.firstOrNull { (_, others) -> others.any { close(query, it) } }?.key

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

    // The same book file in several places: the main site first, then mirrors (old folder layout:
    // book 1342 lives in 1/3/4/1342/). A busy server (503) is retried after a short wait.
    private fun candidates(id: Int): List<String> {
        val digits = id.toString()
        val folders = if (digits.length == 1) "0/$digits" else digits.dropLast(1).toCharArray().joinToString("/") + "/$digits"
        val out = mutableListOf(
            "https://www.gutenberg.org/cache/epub/$id/pg$id.txt",
            "https://www.gutenberg.org/ebooks/$id.txt.utf-8"
        )
        for (m in listOf("https://gutenberg.pglaf.org", "https://aleph.gutenberg.org", "https://mirrors.xmission.com/gutenberg")) {
            out += "$m/$folders/$id-0.txt"
            out += "$m/$folders/$id.txt"
        }
        return out
    }

    private fun fetchBook(id: Int): String {
        var last: Exception? = null
        for (url in candidates(id)) {
            for (attempt in 0..1) {
                try {
                    val t = get(url)
                    if (t.length > 2000) return t
                    break
                } catch (e: Exception) {
                    last = e
                    val busy = e.message?.contains("503") == true || e.message?.contains("429") == true
                    if (busy && attempt == 0) Thread.sleep(3000) else break
                }
            }
        }
        throw last ?: IllegalStateException("not found")
    }

    /** Downloads and cleans a book. Runs on a background thread. Throws on failure. */
    fun download(ctx: Context, book: Book) {
        val raw = fetchBook(book.id)
        val blocks = clean(raw)
        if (blocks.size < 5) throw IllegalStateException("empty")
        val arr = JSONArray()
        for ((heading, text) in blocks) arr.put(JSONObject().put("h", heading).put("t", text))
        textFile(ctx, book.id).writeText(arr.toString())
        val list = shelf(ctx).filter { it.id != book.id }.toMutableList()
        list.add(0, book)
        shelfFile(ctx).writeText(JSONArray().apply { list.forEach { put(it.toJson()) } }.toString())
    }

    /** Puts a book whose text is on the phone onto the shelf. True when it was not there yet. */
    fun addToShelf(ctx: Context, book: Book): Boolean {
        if (!textFile(ctx, book.id).exists()) return false
        val list = shelf(ctx)
        if (list.any { it.id == book.id }) return false
        shelfFile(ctx).writeText(JSONArray().apply { (list + book).forEach { put(it.toJson()) } }.toString())
        sectionCache.remove(book.id)
        return true
    }

    fun remove(ctx: Context, id: Int) {
        textFile(ctx, id).delete()
        val list = shelf(ctx).filter { it.id != id }
        shelfFile(ctx).writeText(JSONArray().apply { list.forEach { put(it.toJson()) } }.toString())
        File(dir(ctx), "lessons").listFiles()?.filter { it.name.startsWith("${id}_") }?.forEach { it.delete() }
        File(dir(ctx), "art").listFiles()?.filter { it.name.startsWith("${id}_") }?.forEach { it.delete() }
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

    // Progress is kept per language and level ("read_en_1"); an older shared list counts only where a
    // lesson for that language and level exists.
    fun read(ctx: Context, id: Int, lang: String, level: Int): Set<Int> {
        val b = progress(ctx).optJSONObject("$id") ?: return emptySet()
        b.optJSONArray("read_${lang}_$level")?.let { a -> return (0 until a.length()).map { a.optInt(it) }.toSet() }
        val old = b.optJSONArray("read") ?: return emptySet()
        return (0 until old.length()).map { old.optInt(it) }.filter { lessonFile(ctx, id, it, lang, level).exists() }.toSet()
    }

    fun last(ctx: Context, id: Int, lang: String, level: Int): Int =
        progress(ctx).optJSONObject("$id")?.optInt("last_${lang}_$level", -1) ?: -1

    fun markRead(ctx: Context, id: Int, sec: Int, lang: String, level: Int) {
        val p = progress(ctx)
        val b = p.optJSONObject("$id") ?: JSONObject()
        val set = read(ctx, id, lang, level).toMutableSet().apply { add(sec) }
        b.put("read_${lang}_$level", JSONArray(set.sorted())).put("last_${lang}_$level", sec)
        p.put("$id", b)
        progressFile(ctx).writeText(p.toString())
    }

    /** Removes a made part (and its pictures, which belong to its sentences) so it can be made again. */
    fun deleteLesson(ctx: Context, id: Int, sec: Int, lang: String, level: Int, withArt: Boolean) {
        lessonFile(ctx, id, sec, lang, level).delete()
        if (withArt) {
            File(dir(ctx), "art").listFiles()?.filter { it.name.startsWith("${id}_${sec}_${lang}_${level}_") }?.forEach { it.delete() }
            videoFile(ctx, id, sec, lang, level).delete()
            videoTiming(ctx, id, sec, lang, level).delete()
        }
    }

    /* ---------------- Lessons ---------------- */

    fun lesson(ctx: Context, id: Int, sec: Int, lang: String, level: Int): JSONObject? =
        lessonFile(ctx, id, sec, lang, level).takeIf { it.exists() }?.let { try { JSONObject(it.readText()) } catch (_: Exception) { null } }

    /** Picture drawn for one sentence of a part's lesson. */
    fun artFile(ctx: Context, id: Int, sec: Int, lang: String, level: Int, sentence: Int) =
        File(File(dir(ctx), "art").apply { mkdirs() }, "${id}_${sec}_${lang}_${level}_$sentence.jpg")

    /** A part's video (its pictures read aloud), and the time of each sentence and word in it. */
    fun videoFile(ctx: Context, id: Int, sec: Int, lang: String, level: Int) =
        File(File(dir(ctx), "video").apply { mkdirs() }, "${id}_${sec}_${lang}_$level.mp4")

    fun videoTiming(ctx: Context, id: Int, sec: Int, lang: String, level: Int) =
        File(File(dir(ctx), "video").apply { mkdirs() }, "${id}_${sec}_${lang}_$level.json")

    fun saveLesson(ctx: Context, id: Int, sec: Int, lang: String, level: Int, o: JSONObject) =
        lessonFile(ctx, id, sec, lang, level).writeText(o.toString())

    /* ---------------- Background download ---------------- */

    private const val JOB_ID = 7311
    private const val CHANNEL = "books"
    private val main = Handler(Looper.getMainLooper())

    /** Called on the main thread when a book has finished downloading (or failed for good). */
    @Volatile var listener: ((Int, Boolean) -> Unit)? = null

    private fun queueFile(ctx: Context) = File(dir(ctx), "queue.json")

    @Synchronized
    fun queue(ctx: Context): List<Book> = try {
        val a = JSONArray(queueFile(ctx).takeIf { it.exists() }?.readText() ?: "[]")
        (0 until a.length()).mapNotNull { i ->
            a.optJSONObject(i)?.let { Book(it.optInt("id"), it.optString("title"), it.optString("author"), it.optString("url")) }
        }
    } catch (_: Exception) { emptyList() }

    @Synchronized
    private fun saveQueue(ctx: Context, list: List<Book>) {
        queueFile(ctx).writeText(JSONArray().apply { list.forEach { put(it.toJson()) } }.toString())
    }

    fun pending(ctx: Context, id: Int) = queue(ctx).any { it.id == id }

    /** Downloads in the background until it succeeds, even if the app is closed. */
    fun enqueue(ctx: Context, book: Book) {
        if (has(ctx, book.id)) return
        val q = queue(ctx)
        if (q.none { it.id == book.id }) saveQueue(ctx, q + book)
        schedule(ctx)
    }

    private fun schedule(ctx: Context) {
        val js = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        if (js.getPendingJob(JOB_ID) != null) return
        js.schedule(
            JobInfo.Builder(JOB_ID, ComponentName(ctx, BookJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build()
        )
    }

    /** Works through the queue. Returns true when something is left to retry later. */
    fun runQueue(ctx: Context): Boolean {
        var left = false
        for (b in queue(ctx)) {
            val ok = try { download(ctx, b); true } catch (_: Exception) { false }
            if (ok) {
                saveQueue(ctx, queue(ctx).filter { it.id != b.id })
                notifyDone(ctx, b)
            } else {
                left = true
            }
            main.post { listener?.invoke(b.id, ok) }
        }
        return left
    }

    private fun notifyDone(ctx: Context, b: Book) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, StudyText.t("books_btn"), NotificationManager.IMPORTANCE_DEFAULT))
            val open = Intent(ctx, NewsActivity::class.java).putExtra("books", true).putExtra("book", b.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pi = PendingIntent.getActivity(ctx, b.id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(Ui.plain(StudyText.t("books_ready")))
                .setContentText(b.title)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(9000 + b.id % 1000, n)
        } catch (_: Exception) {}
    }
}

// Downloads queued books whenever there is a network, retrying with growing waits until done.
class BookJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            val retry = try { BookStore.runQueue(applicationContext) } catch (_: Exception) { true }
            jobFinished(params, retry)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters) = true
}
