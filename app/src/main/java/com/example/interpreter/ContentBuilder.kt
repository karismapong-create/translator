package com.example.interpreter

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

// Developer tool: builds the built-in phrasebook one combination at a time
// (one foreign language, meanings in one language) with the engine chosen in settings,
// starting from the content already shipped in the app. Runs in BuildService so it keeps going in the
// background, resumes where it stopped, imports zips from other phones, and exports everything as one zip
// for app/src/main/assets/situations.zip.
object ContentBuilder {

    class Place(val name: String, val desc: String, val role: String)

    private const val GAP_MS = 4000L

    @Volatile var running = false
        private set
    @Volatile private var stopRequested = false
    @Volatile var runningCombo = ""
        private set
    @Volatile var lastMessage = ""
        private set
    var places: List<Place> = emptyList()
        private set
    var listener: ((String) -> Unit)? = null
    var serviceListener: ((String) -> Unit)? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun dir(ctx: Context) = File(ctx.filesDir, "bundle")
    private fun shipped(ctx: Context) = File(ctx.filesDir, "basepack")
    private fun file(ctx: Context, lang: String, place: Int) = File(dir(ctx), "$lang/$place.json")
    private fun english(code: String) = Languages.byCode(code).english
    private fun native(code: String) = Languages.byCode(code).native

    fun report(msg: String) {
        lastMessage = msg
        main.post {
            listener?.invoke(msg)
            serviceListener?.invoke(msg)
        }
    }

    /* ---------- Engine: only the one chosen in settings ---------- */

    fun chosenId(prefs: SharedPreferences): String = prefs.getString("engine", null) ?: "mlkit"

    fun selectedEngine(prefs: SharedPreferences): String? {
        val id = chosenId(prefs)
        return if (Providers.find(id) != null && Providers.ready(prefs, id)) id else null
    }

    private fun engineProblem(prefs: SharedPreferences) =
        "지금 설정된 엔진(${Providers.label(chosenId(prefs))})으로는 만들 수 없어요. " +
            "설정 → 번역 엔진에서 Gemini 같은 AI 엔진을 골라 '저장하고 사용'을 누르세요."

    /* ---------- Files ---------- */

    private fun load(f: File): JSONObject =
        if (f.exists()) try { JSONObject(f.readText()) } catch (_: Exception) { JSONObject() } else JSONObject()

    // Content built or imported on this phone, or else what the app already ships with.
    private fun current(ctx: Context, lang: String, place: Int): JSONObject {
        val f = file(ctx, lang, place)
        if (f.exists()) return load(f)
        BasePack.prepare(ctx)
        return load(File(shipped(ctx), "$lang/$place.json"))
    }

    private fun save(f: File, o: JSONObject) {
        f.parentFile?.mkdirs()
        f.writeText(o.toString())
    }

    private fun has(o: JSONObject?, code: String) = !o?.optString(code).isNullOrBlank()

    private fun child(o: JSONObject, key: String): JSONObject =
        o.optJSONObject(key) ?: JSONObject().also { o.put(key, it) }

    // Text of a multi-language field in any language, preferring English.
    private fun anyText(o: JSONObject?): String {
        if (o == null) return ""
        o.optString("en").takeIf { it.isNotBlank() }?.let { return it }
        val keys = o.keys()
        while (keys.hasNext()) {
            val v = o.optString(keys.next())
            if (v.isNotBlank()) return v
        }
        return ""
    }

    private fun dialogueHas(d: JSONObject, home: String) =
        has(d.optJSONArray("lines")?.optJSONObject(0)?.optJSONObject("m"), home)

    // Cell states for the progress grid.
    const val MISSING = 0
    const val DONE = 1
    const val NEEDS_MEANING = 2
    const val BROKEN = 3
    // A place with fewer than 10 questions has no dialogue for the cells past its last question.
    const val NONE = -1

    // Per place: [question list, dialogue 1..10] states for one foreign + meaning language, read from
    // the same file the phrasebook shows (shipped DB or the one built or imported on this phone).
    fun status(ctx: Context, foreign: String, home: String, count: Int): List<IntArray> =
        (0 until count).map { pi ->
            val row = IntArray(11)
            val o = BasePack.source(ctx, foreign, pi, home) ?: current(ctx, foreign, pi)
            val qs = o.optJSONArray("questions")
            if (qs == null || qs.length() == 0) return@map row
            for (i in qs.length() until 10) row[i + 1] = NONE
            row[0] = when {
                LangCheck.brokenQuestions(o, foreign) > 2 -> BROKEN
                has(qs.optJSONObject(0)?.optJSONObject("title"), home) -> DONE
                else -> NEEDS_MEANING
            }
            val ds = o.optJSONArray("dialogues") ?: return@map row
            for (i in 0 until minOf(qs.length(), 10)) {
                val d = ds.optJSONObject(i) ?: continue
                row[i + 1] = when {
                    !dialogueHas(d, home) -> NEEDS_MEANING
                    LangCheck.fixDialogue(JSONObject(d.toString()), foreign, home) -> DONE
                    else -> BROKEN
                }
            }
            row
        }

    fun done(ctx: Context, foreign: String, home: String, count: Int): Int =
        status(ctx, foreign, home, count).sumOf { row -> row.count { it == DONE } }

    fun stop() {
        stopRequested = true
    }

    // Starts building on a background thread. Called by BuildService; onFinished runs on the main thread.
    fun run(ctx: Context, prefs: SharedPreferences, foreign: String, home: String, onFinished: () -> Unit): Boolean {
        if (running) {
            report("이미 만드는 중이에요: $runningCombo")
            return false
        }
        val engine = selectedEngine(prefs)
        if (engine == null) {
            report(engineProblem(prefs))
            return false
        }
        val list = places
        if (list.isEmpty()) {
            report("장소 목록이 없어요. 상황별 회화 화면에서 생성 창을 다시 열어주세요.")
            return false
        }
        running = true
        stopRequested = false
        runningCombo = "${native(foreign)} + ${native(home)} 뜻 · ${Providers.label(engine)}"
        val app = ctx.applicationContext
        worker.execute {
            val msg = try {
                build(app, prefs, engine, list, foreign, home)
                if (stopRequested) "멈췄어요. '시작 / 이어서'를 누르면 멈춘 곳부터 이어서 만들어요."
                else "✔ ${native(foreign)} + ${native(home)} 뜻 조합을 모두 만들었어요. '내보내기'를 누르세요."
            } catch (e: Exception) {
                "멈췄어요: ${e.message?.take(200)}\n한도에 걸렸다면 나중에 '시작 / 이어서'를 누르면 멈춘 곳부터 이어서 만들어요."
            }
            running = false
            report(msg)
            main.post { onFinished() }
        }
        return true
    }

    /* ---------- Prompts ---------- */

    private fun questionsPrompt(name: String, mine: String) =
        "You write travel phrasebooks for $mine speakers. For the given place, list the 10 situations travelers " +
        "most often need to ask or say there, ordered from most common. For each, give the single most useful " +
        "natural $name sentence, a short $mine title of the situation, and the $mine meaning of the sentence. " +
        "Reply with JSON only, no code fences: {\"questions\":[{\"phrase\":\"$name sentence\"," +
        "\"title\":\"short $mine title\",\"meaning\":\"$mine meaning\"}]}"

    private fun questionsTranslatePrompt(name: String, mine: String) =
        "You translate a travel phrasebook for $mine speakers. The user sends a JSON array of entries, each with " +
        "a $name phrase and a short title in another language. For every entry, in the same order, write a short " +
        "$mine title and the natural $mine meaning of the phrase. Reply with JSON only, no code fences: " +
        "{\"items\":[{\"title\":\"...\",\"meaning\":\"...\"}]}"

    private fun dialoguePrompt(name: String, mine: String, p: Place) =
        "You write realistic practice dialogues for $mine-speaking travelers. Write a natural spoken $name " +
        "dialogue of 20 to 30 short lines, about 2 to 3 minutes when spoken, between the traveler (A) and " +
        "${p.role} (B) at ${p.desc}. The traveler is polite; the other person speaks naturally, including the " +
        "questions they typically ask and one small realistic complication. Reply with JSON only, no code fences: " +
        "{\"lines\":[{\"who\":\"A\",\"text\":\"$name line\",\"m\":\"$mine translation\"}]," +
        "\"tips\":\"one or two short $mine tips about local customs or useful points\"}"

    private fun dialogueTranslatePrompt(name: String, mine: String) =
        "You translate a practice dialogue for $mine-speaking travelers. The user sends JSON with the $name lines " +
        "in order and tips in another language. Translate every line into natural $mine, keeping exactly the same " +
        "order and number of lines, and translate the tips into $mine. Reply with JSON only, no code fences: " +
        "{\"m\":[\"...\"],\"tips\":\"...\"}"

    /* ---------- Building ---------- */

    private fun build(
        ctx: Context, prefs: SharedPreferences, engine: String,
        list: List<Place>, foreign: String, home: String
    ) {
        val name = english(foreign)
        val mine = english(home)
        for ((pi, p) in list.withIndex()) {
            if (stopRequested) return
            val f = file(ctx, foreign, pi)
            var o = current(ctx, foreign, pi)
            var qs = o.optJSONArray("questions")
            val step = "${pi + 1}/${list.size} ${p.name}"

            if (qs == null || qs.length() == 0) {
                report("$step · 질문 목록 만드는 중…")
                // Small engines sometimes answer in the wrong language; ask again until the list checks out.
                var built = JSONArray()
                for (attempt in 1..3) {
                    val r = ask(prefs, engine, questionsPrompt(name, mine), "Place: ${p.name} (${p.desc}). Language of the phrases: $name.")
                    val arr = r.optJSONArray("questions") ?: continue
                    val good = JSONArray()
                    for (i in 0 until arr.length()) {
                        val q = arr.optJSONObject(i) ?: continue
                        if (q.optString("phrase").isBlank()) continue
                        val entry = JSONObject()
                            .put("phrase", q.optString("phrase"))
                            .put("title", JSONObject().put(home, q.optString("title")))
                            .put("meaning", JSONObject().put(home, q.optString("meaning")))
                        LangCheck.fixQuestion(entry, foreign, home)?.let { good.put(it) }
                    }
                    if (good.length() > built.length()) built = good
                    if (built.length() >= 8) break
                    report("$step · 언어가 섞인 답이 와서 다시 요청 ($attempt/3)…")
                }
                if (built.length() == 0) throw IllegalStateException("질문 목록이 비어 있어요")
                qs = built
                o.put("questions", built)
                o.put("dialogues", JSONArray())
                save(f, o)
            } else if (!has(qs.optJSONObject(0)?.optJSONObject("title"), home)) {
                report("$step · 질문 목록에 ${native(home)} 뜻 추가 중…")
                val items = JSONArray()
                for (i in 0 until qs.length()) {
                    val q = qs.optJSONObject(i) ?: JSONObject()
                    items.put(JSONObject().put("phrase", q.optString("phrase")).put("title", anyText(q.optJSONObject("title"))))
                }
                val r = ask(prefs, engine, questionsTranslatePrompt(name, mine), items.toString())
                val out = r.optJSONArray("items")
                if (out == null || out.length() != qs.length()) throw IllegalStateException("뜻 추가 형식 오류")
                for (i in 0 until qs.length()) {
                    val q = qs.optJSONObject(i) ?: continue
                    val t = out.optJSONObject(i) ?: continue
                    child(q, "title").put(home, t.optString("title"))
                    child(q, "meaning").put(home, t.optString("meaning"))
                }
                save(f, o)
            }

            for (qi in 0 until minOf(qs.length(), 10)) {
                if (stopRequested) return
                // Read the file fresh: the phrasebook screen may have saved a dialogue meanwhile.
                o = current(ctx, foreign, pi)
                val ds = o.optJSONArray("dialogues") ?: JSONArray().also { o.put("dialogues", it) }
                val q = qs.optJSONObject(qi) ?: continue
                val d = ds.optJSONObject(qi)
                if (d == null) {
                    report("$step · 대화 ${qi + 1}/10 만드는 중…")
                    val title = anyText(q.optJSONObject("title"))
                    var made: JSONObject? = null
                    for (attempt in 1..3) {
                        val r = ask(prefs, engine, dialoguePrompt(name, mine, p), "Situation: $title — ${q.optString("phrase")}")
                        val lines = r.optJSONArray("lines")
                        if (lines == null || lines.length() < 8) continue
                        val built = JSONArray()
                        for (i in 0 until lines.length()) {
                            val l = lines.optJSONObject(i) ?: continue
                            val text = l.optString("text")
                            if (text.isBlank()) continue
                            built.put(
                                JSONObject()
                                    .put("who", l.optString("who", "A"))
                                    .put("text", text)
                                    .put("m", JSONObject().put(home, l.optString("m")))
                            )
                        }
                        val d2 = JSONObject().put("lines", built).put("tips", JSONObject().put(home, r.optString("tips")))
                        if (LangCheck.fixDialogue(d2, foreign, home)) { made = d2; break }
                        report("$step · 대화 ${qi + 1}/10: 언어가 섞여서 다시 요청 ($attempt/3)…")
                    }
                    if (made == null) continue
                    ds.put(qi, made)
                    save(f, o)
                } else if (!dialogueHas(d, home)) {
                    report("$step · 대화 ${qi + 1}/10에 ${native(home)} 뜻 추가 중…")
                    val lines = d.optJSONArray("lines") ?: continue
                    val texts = JSONArray()
                    for (i in 0 until lines.length()) texts.put(lines.optJSONObject(i)?.optString("text").orEmpty())
                    val input = JSONObject().put("lines", texts).put("tips", anyText(d.optJSONObject("tips")))
                    val r = ask(prefs, engine, dialogueTranslatePrompt(name, mine), input.toString())
                    val m = r.optJSONArray("m")
                    if (m == null || m.length() != lines.length()) continue
                    val wrong = (0 until m.length()).count { !LangCheck.looks(m.optString(it), home) }
                    if (wrong > maxOf(1, m.length() / 10)) continue
                    for (i in 0 until lines.length()) {
                        val l = lines.optJSONObject(i) ?: continue
                        child(l, "m").put(home, m.optString(i))
                    }
                    child(d, "tips").put(home, r.optString("tips"))
                    save(f, o)
                }
            }
        }
    }

    // Waits between requests, backs off when rate-limited, and retries bad replies.
    private fun ask(prefs: SharedPreferences, engine: String, system: String, user: String): JSONObject {
        var wait = 30_000L
        var lastError: Exception? = null
        for (attempt in 1..6) {
            pause(GAP_MS)
            if (stopRequested) throw IllegalStateException("멈춤")
            try {
                val raw = Providers.complete(prefs, engine, system, listOf("user" to user), 16000, 180000)
                val o = Tutor.json(raw)
                if (o != null) return o
                lastError = IllegalStateException("JSON 형식 오류")
            } catch (e: Providers.HttpException) {
                lastError = e
                when {
                    e.code == 429 -> {
                        report("사용 한도에 걸려 ${wait / 1000}초 기다리는 중…")
                        pause(wait)
                        wait *= 2
                    }
                    e.code >= 500 -> pause(15_000)
                    else -> throw e
                }
            } catch (e: java.io.IOException) {
                lastError = e
                pause(15_000)
            }
        }
        throw lastError ?: IllegalStateException("실패")
    }

    private fun pause(ms: Long) {
        var left = ms
        while (left > 0 && !stopRequested) {
            Thread.sleep(minOf(left, 1000L))
            left -= 1000L
        }
    }

    /* ---------- Content made in the phrasebook screen ---------- */

    // A dialogue made or remade in the phrasebook screen for a question of the DB list joins the DB,
    // so it counts in progress and goes out with "export". Runs on a background thread.
    // raw: {"lines":[{"who","text","ko"}],"tips"} as the phrasebook screen stores it.
    @Synchronized
    // phrase: the question's sentence the dialogue was made for; it must be the DB's sentence at that index.
    fun adoptDialogue(ctx: Context, lang: String, place: Int, index: Int, home: String, raw: String, phrase: String? = null): Boolean {
        val src = Tutor.json(raw) ?: return false
        val lines = src.optJSONArray("lines") ?: return false
        val o = current(ctx, lang, place)
        val qs = o.optJSONArray("questions") ?: return false
        if (index !in 0 until qs.length()) return false
        if (phrase != null && qs.optJSONObject(index)?.optString("phrase")?.trim() != phrase.trim()) return false
        val built = JSONArray()
        for (i in 0 until lines.length()) {
            val l = lines.optJSONObject(i) ?: continue
            if (l.optString("text").isBlank()) continue
            built.put(
                JSONObject().put("who", l.optString("who", "A")).put("text", l.optString("text"))
                    .put("m", JSONObject().put(home, l.optString("ko")))
            )
        }
        val d = JSONObject().put("lines", built).put("tips", JSONObject().put(home, src.optString("tips")))
        if (!LangCheck.fixDialogue(d, lang, home)) return false
        val ds = o.optJSONArray("dialogues") ?: JSONArray().also { o.put("dialogues", it) }
        val old = ds.optJSONObject(index)
        if (old != null && lineTexts(old) == lineTexts(d)) {
            val ol = old.optJSONArray("lines") ?: return false
            for (k in 0 until ol.length()) {
                val x = ol.optJSONObject(k) ?: continue
                x.put("m", mergeMap(x.optJSONObject("m"), built.optJSONObject(k)?.optJSONObject("m")))
            }
            old.put("tips", mergeMap(old.optJSONObject("tips"), d.optJSONObject("tips")))
        } else {
            ds.put(index, d)
        }
        save(file(ctx, lang, place), o)
        return true
    }

    // A question list made in the phrasebook screen. replace = the user remade it by hand: it becomes
    // the DB's list for this language and place. Otherwise it is used only where the DB has no usable
    // list. Every question keeps its position so dialogues stay with their question.
    // raw: {"questions":[{"ko" (title),"phrase","phrase_ko" (meaning)}]}.
    @Synchronized
    fun setQuestions(ctx: Context, lang: String, place: Int, home: String, raw: String, replace: Boolean): Boolean {
        val arr = Tutor.json(raw)?.optJSONArray("questions") ?: return false
        val o = current(ctx, lang, place)
        val qs = o.optJSONArray("questions")
        val usable = qs != null && qs.length() > 0 && LangCheck.brokenQuestions(o, lang) <= 2 &&
            has(qs.optJSONObject(0)?.optJSONObject("title"), home)
        if (usable && !replace) return false
        val built = JSONArray()
        for (i in 0 until arr.length()) {
            val q = arr.optJSONObject(i) ?: continue
            if (q.optString("phrase").isBlank()) continue
            val entry = JSONObject().put("phrase", q.optString("phrase"))
                .put("title", JSONObject().put(home, q.optString("ko")))
                .put("meaning", JSONObject().put(home, q.optString("phrase_ko")))
            built.put(LangCheck.fixQuestion(entry, lang, home) ?: entry)
        }
        if (built.length() == 0) return false
        o.put("questions", built)
        o.put("dialogues", JSONArray())
        save(file(ctx, lang, place), o)
        return true
    }

    // The file the phrasebook screen shows for this language and place, as an editable copy, so a
    // change lands in exactly what the screen and the progress grid read.
    private fun shown(ctx: Context, lang: String, place: Int, home: String): JSONObject =
        BasePack.source(ctx, lang, place, home)?.let { JSONObject(it.toString()) } ?: current(ctx, lang, place)

    // Saves a dialogue made in the phrasebook screen for the question at this position of the list it
    // shows. lines: (who, text, meaning in my language). Returns null when saved, or why it couldn't be.
    @Synchronized
    fun putDialogue(
        ctx: Context, lang: String, place: Int, index: Int, home: String,
        lines: List<Triple<String, String, String>>, tips: String
    ): String? {
        val o = shown(ctx, lang, place, home)
        val qs = o.optJSONArray("questions") ?: return "no question list"
        if (index !in 0 until qs.length()) return "question ${index + 1} not in list (${qs.length()})"
        val built = JSONArray()
        lines.filter { it.second.isNotBlank() }.forEach { (who, text, meaning) ->
            built.put(JSONObject().put("who", who).put("text", text).put("m", JSONObject().put(home, meaning)))
        }
        if (built.length() == 0) return "empty dialogue"
        val ds = o.optJSONArray("dialogues") ?: JSONArray().also { o.put("dialogues", it) }
        ds.put(index, JSONObject().put("lines", built).put("tips", JSONObject().put(home, tips)))
        save(file(ctx, lang, place), o)
        return null
    }

    // A remade dialogue can bring a better key sentence for its question.
    @Synchronized
    fun setPhrase(ctx: Context, lang: String, place: Int, index: Int, home: String, phrase: String, meaning: String) {
        val o = shown(ctx, lang, place, home)
        val q = o.optJSONArray("questions")?.optJSONObject(index) ?: return
        q.put("phrase", phrase)
        child(q, "meaning").put(home, meaning)
        save(file(ctx, lang, place), o)
    }

    // Newer shipped content (after an app update) fills what this phone's DB lacks, without
    // replacing anything made on the phone.
    fun absorbShipped(ctx: Context) {
        val root = shipped(ctx)
        if (!root.exists()) return
        root.walkTopDown().filter { it.isFile && it.extension == "json" }.forEach { f ->
            val path = f.relativeTo(root).path.replace('\\', '/')
            val mine = File(dir(ctx), path)
            if (!mine.exists()) return@forEach
            val lang = path.substringBefore('/')
            try { save(mine, merge(load(mine), load(f), lang, keepBase = true)) } catch (_: Exception) {}
        }
    }

    // Earlier versions kept content made in the phrasebook screen in a separate cache. This moves it
    // into the DB, as the screen showed it, then removes the cache: from now on there is one store.
    @Synchronized
    fun migratePhoneCache(ctx: Context) {
        val dir = File(ctx.filesDir, "situations")
        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".json") } ?: return
        if (files.isEmpty()) return
        val listName = Regex("^(?:([a-z]{2})_)?(f_)?q_([a-z]{2})_(\\d+)\\.json$")
        val dlgName = Regex("^(?:([a-z]{2})_)?(b_|f_)?d_([a-z]{2})_(\\d+)_(\\d+)\\.json$")
        val phraseName = Regex("^(?:([a-z]{2})_)?qo_(b_|f_)?([a-z]{2})_(\\d+)_(\\d+)\\.json$")
        fun home(m: MatchResult) = m.groupValues[1].ifEmpty { "ko" }
        fun prefix(home: String) = if (home == "ko") "" else "${home}_"
        fun listText(home: String, kind: String, lang: String, place: Int) =
            File(dir, "${prefix(home)}${kind}q_${lang}_$place.json").takeIf { it.exists() }?.readText()
        fun dialogues(kind: String) = files.mapNotNull { f -> dlgName.find(f.name)?.takeIf { it.groupValues[2] == kind }?.let { f to it } }
        fun adoptAll(kind: String) {
            for ((f, m) in dialogues(kind)) {
                val h = home(m)
                val lang = m.groupValues[3]
                val place = m.groupValues[4].toInt()
                val index = m.groupValues[5].toInt()
                val phrase: String? = if (kind == "b_") null else (listText(h, kind, lang, place)
                    ?.let { Tutor.json(it)?.optJSONArray("questions")?.optJSONObject(index)?.optString("phrase") } ?: continue)
                try { adoptDialogue(ctx, lang, place, index, h, f.readText(), phrase) } catch (_: Exception) {}
            }
        }
        try {
            // 1. Dialogues made for the DB's list, while it is still that list.
            adoptAll("b_")
            // 2. Automatic lists where the DB had none, with their dialogues.
            files.mapNotNull { f -> listName.find(f.name)?.takeIf { it.groupValues[2].isEmpty() }?.let { f to it } }.forEach { (f, m) ->
                setQuestions(ctx, m.groupValues[3], m.groupValues[4].toInt(), home(m), f.readText(), replace = false)
            }
            adoptAll("")
            // 3. Lists the user remade by hand replace the DB's list, with their dialogues.
            files.mapNotNull { f -> listName.find(f.name)?.takeIf { it.groupValues[2] == "f_" }?.let { f to it } }.forEach { (f, m) ->
                setQuestions(ctx, m.groupValues[3], m.groupValues[4].toInt(), home(m), f.readText(), replace = true)
            }
            adoptAll("f_")
            // 4. Key sentences updated by remade dialogues.
            files.mapNotNull { f -> phraseName.find(f.name)?.let { f to it } }.forEach { (f, m) ->
                val o = Tutor.json(f.readText()) ?: return@forEach
                val phrase = o.optString("phrase")
                if (phrase.isNotBlank()) setPhrase(ctx, m.groupValues[3], m.groupValues[4].toInt(), m.groupValues[5].toInt(), home(m), phrase, o.optString("meaning"))
            }
        } finally {
            files.forEach { it.delete() }
        }
    }

    /* ---------- Import ---------- */

    private fun mergeMap(base: JSONObject?, add: JSONObject?): JSONObject {
        val out = JSONObject()
        for (src in listOf(base, add)) {
            if (src == null) continue
            val keys = src.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = src.optString(k)
                if (v.isNotBlank()) out.put(k, v)
            }
        }
        return out
    }

    private fun phrases(qs: JSONArray) = (0 until qs.length()).map { qs.optJSONObject(it)?.optString("phrase").orEmpty() }

    private fun lineTexts(d: JSONObject): List<String> {
        val ls = d.optJSONArray("lines") ?: return emptyList()
        return (0 until ls.length()).map { ls.optJSONObject(it)?.optString("text").orEmpty() }
    }

    // Total completeness of a place file over every meaning language.
    private fun completeness(o: JSONObject): Int =
        Languages.all.sumOf { LangCheck.score(o, it.code).coerceAtLeast(0) }

    // Same sentences: meanings of every language are combined, and a broken dialogue is replaced by a
    // good one. Different sentences: the more complete file is kept whole (the imported one on a tie),
    // so questions and dialogues never get mixed from two lists. Both are cleaned first.
    // keepBase: base is this phone's DB and add is newer shipped content. Then a different list or a
    // different dialogue in base is kept (the user made it), and add only fills what base lacks.
    private fun merge(base: JSONObject, add: JSONObject, lang: String, keepBase: Boolean = false): JSONObject {
        LangCheck.repair(base, lang)
        LangCheck.repair(add, lang)
        val bq = base.optJSONArray("questions")
        val aq = add.optJSONArray("questions")
        if (aq == null || aq.length() == 0) return base
        if (bq == null || bq.length() == 0) return add
        if (phrases(bq) != phrases(aq)) {
            if (keepBase) return base
            return if (completeness(base) > completeness(add)) base else add
        }
        for (i in 0 until bq.length()) {
            val b = bq.optJSONObject(i) ?: continue
            val a = aq.optJSONObject(i) ?: continue
            b.put("title", mergeMap(b.optJSONObject("title"), a.optJSONObject("title")))
            b.put("meaning", mergeMap(b.optJSONObject("meaning"), a.optJSONObject("meaning")))
        }
        val bd = base.optJSONArray("dialogues") ?: JSONArray().also { base.put("dialogues", it) }
        val ad = add.optJSONArray("dialogues") ?: JSONArray()
        for (i in 0 until ad.length()) {
            val a = ad.optJSONObject(i) ?: continue
            val b = bd.optJSONObject(i)
            if (b == null) {
                bd.put(i, a)
                continue
            }
            if (lineTexts(b) != lineTexts(a)) {
                if (!keepBase) bd.put(i, a)
                continue
            }
            val bl = b.optJSONArray("lines") ?: continue
            val al = a.optJSONArray("lines") ?: continue
            for (k in 0 until bl.length()) {
                val x = bl.optJSONObject(k) ?: continue
                x.put("m", mergeMap(x.optJSONObject("m"), al.optJSONObject(k)?.optJSONObject("m")))
            }
            b.put("tips", mergeMap(b.optJSONObject("tips"), a.optJSONObject("tips")))
        }
        return base
    }

    // Saved dialogues and sentence updates on this phone were made for the old list; after an import
    // they could show under the wrong question. Clears them for one language and place, keeping only
    // what the user remade by hand.
    private fun clearPhoneCache(ctx: Context, lang: String, place: Int) {
        val mid = "_${lang}_${place}_"
        val end = "_${lang}_${place}.json"
        File(ctx.filesDir, "situations").listFiles()
            ?.filter { (it.name.contains(mid) || it.name.endsWith(end)) && !it.name.contains("f_") }
            ?.forEach { it.delete() }
    }

    // Cleans every file built or imported on this phone, and the shipped files it differs from.
    // Returns how many entries were fixed or dropped (dropped dialogues are made again on the next run).
    fun repairAll(ctx: Context): Int {
        BasePack.prepare(ctx)
        var total = 0
        for (lang in Languages.all.map { it.code }) {
            for (place in 0 until 64) {
                val f = file(ctx, lang, place)
                val shippedFile = File(shipped(ctx), "$lang/$place.json")
                if (!f.exists() && !shippedFile.exists()) continue
                val o = current(ctx, lang, place)
                val n = LangCheck.repair(o, lang)
                if (n > 0) {
                    save(f, o)
                    clearPhoneCache(ctx, lang, place)
                    total += n
                }
            }
        }
        return total
    }

    // Merges a zip made by "export" into this phone's content. Returns how many files were merged.
    // Runs on a background thread.
    fun importZip(ctx: Context, uri: Uri): Int {
        if (running) throw IllegalStateException("만드는 중에는 가져올 수 없어요. 먼저 멈추세요.")
        val codes = Languages.all.map { it.code }.toSet()
        val fileName = Regex("^(\\d{1,2})\\.json$")
        var count = 0
        val input = ctx.contentResolver.openInputStream(uri) ?: throw IllegalStateException("파일을 열지 못했어요")
        input.use { stream ->
            ZipInputStream(stream).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    if (!e.isDirectory) {
                        val parts = e.name.replace('\\', '/').split('/').filter { it.isNotEmpty() }
                        val lang = parts.getOrNull(parts.size - 2)
                        val match = parts.lastOrNull()?.let { fileName.find(it) }
                        if (lang != null && lang in codes && match != null) {
                            val place = match.groupValues[1].toInt()
                            val incoming = try {
                                JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                            } catch (_: Exception) {
                                null
                            }
                            if (incoming != null) {
                                save(file(ctx, lang, place), merge(current(ctx, lang, place), incoming, lang))
                                clearPhoneCache(ctx, lang, place)
                                count++
                            }
                        }
                    }
                    e = zip.nextEntry
                }
            }
        }
        return count
    }

    /* ---------- Export ---------- */

    // Zips the shipped content plus everything built or imported on this phone into Download/situations.zip.
    // Files from this phone replace shipped files with the same name. Runs on a background thread.
    fun export(ctx: Context): String? {
        if (Build.VERSION.SDK_INT < 29) return null
        BasePack.prepare(ctx)
        val paths = LinkedHashSet<String>()
        for (root in listOf(shipped(ctx), dir(ctx))) {
            if (!root.exists()) continue
            root.walkTopDown()
                .filter { it.isFile && it.extension == "json" }
                .forEach { paths.add(it.relativeTo(root).path.replace('\\', '/')) }
        }
        if (paths.isEmpty()) return null
        // Each file: shipped and this phone's version merged the same way as an import, then cleaned.
        val entries = LinkedHashMap<String, ByteArray>()
        for (path in paths) {
            val lang = path.substringBefore('/')
            val a = load(File(shipped(ctx), path))
            val b = load(File(dir(ctx), path))
            // The same as the app shows: this phone's DB, filled in from the shipped file.
            val o = when {
                a.length() == 0 -> b.also { LangCheck.repair(it, lang) }
                b.length() == 0 -> a.also { LangCheck.repair(it, lang) }
                else -> merge(b, a, lang, keepBase = true)
            }
            entries[path] = o.toString().toByteArray(Charsets.UTF_8)
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "situations.zip")
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        ctx.contentResolver.openOutputStream(uri)?.use { os ->
            ZipOutputStream(os).use { zip ->
                for ((path, bytes) in entries) {
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        } ?: return null
        return "Download/situations.zip"
    }

    /* ---------- Developer window ---------- */

    fun openDialog(activity: Activity, prefs: SharedPreferences, list: List<Place>) {
        places = list
        val langs = Languages.all
        val home = Languages.home(prefs).code
        val density = activity.resources.displayMetrics.density
        fun px(v: Int) = (v * density).toInt()
        fun spinner() = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, langs.map { it.native })
        }
        fun label(text: String) = TextView(activity).apply {
            this.text = text
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, px(10), 0, 0)
        }
        fun button(text: String, onClick: () -> Unit) = Button(activity).apply {
            this.text = text
            isAllCaps = false
            setOnClickListener { onClick() }
        }

        val foreignSpinner = spinner()
        val meaningSpinner = spinner()
        val savedForeign = prefs.getString("builder_foreign", null) ?: langs.first { it.code != home }.code
        val savedMeaning = prefs.getString("builder_meaning", null) ?: home
        foreignSpinner.setSelection(langs.indexOfFirst { it.code == savedForeign }.coerceAtLeast(0))
        meaningSpinner.setSelection(langs.indexOfFirst { it.code == savedMeaning }.coerceAtLeast(0))
        fun foreign() = langs[foreignSpinner.selectedItemPosition].code
        fun meaning() = langs[meaningSpinner.selectedItemPosition].code

        val engineView = TextView(activity).apply {
            textSize = 13f
            setPadding(0, px(8), 0, 0)
        }
        fun showEngine() {
            val ok = selectedEngine(prefs) != null
            engineView.setTextColor(if (ok) Color.DKGRAY else Color.rgb(207, 34, 46))
            engineView.text = "생성 엔진 (설정에서 고른 엔진): ${Providers.label(chosenId(prefs))}" +
                if (ok) "" else "\n⚠ " + engineProblem(prefs)
        }
        val progress = TextView(activity).apply {
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, px(12), 0, 0)
        }
        val status = TextView(activity).apply {
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, px(6), 0, px(6))
            if (running) text = lastMessage
        }
        val grid = ProgressGrid(activity)
        val legend = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, px(6), 0, px(4))
            listOf(DONE to "완료", NEEDS_MEANING to "뜻 없음", BROKEN to "언어 섞임", MISSING to "없음").forEach { (st, name) ->
                addView(TextView(activity).apply {
                    text = "■ $name"
                    textSize = 12f
                    setTextColor(ProgressGrid.color(st))
                    setPadding(0, 0, px(12), 0)
                })
            }
        }
        val gridNote = TextView(activity).apply {
            text = "줄마다 장소 · ● 질문 목록, ■ 대화 1~10"
            textSize = 12f
            setTextColor(Color.GRAY)
        }
        fun refresh(msg: String?) {
            val f = foreign()
            val m = meaning()
            Thread {
                try { migratePhoneCache(activity) } catch (_: Exception) {}
                val rows = status(activity, f, m, list.size)
                val count = rows.sumOf { row -> row.count { it == DONE } }
                // Cells that can exist: a list and one dialogue per question (empty places count as 11).
                val total = rows.sumOf { row -> row.count { it != NONE } }.coerceAtLeast(1)
                val names = Places.all.take(list.size).map { Places.name(it, home) }
                activity.runOnUiThread {
                    progress.text = "${native(f)} + ${native(m)} 뜻 · 완료 $count / $total (${count * 100 / total}%)" +
                        if (running) "\n만드는 중: $runningCombo" else ""
                    grid.set(names, rows)
                    gridNote.text = "위에서 고른 ${native(f)} 대화 + ${native(m)} 뜻 기준 · 줄마다 장소 · 맨 앞 ●는 질문 목록, 숫자 칸은 그 번호 질문의 대화"
                    if (msg != null) status.text = msg
                }
            }.start()
        }
        val onPick = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = refresh(null)
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        foreignSpinner.onItemSelectedListener = onPick
        meaningSpinner.onItemSelectedListener = onPick

        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(8), px(20), 0)
            addView(TextView(activity).apply {
                text = "고른 조합만 만들어요: 30개 장소의 질문 목록과 대화 예문(장소당 10개). " +
                    "앱에 이미 들어 있는 외국어라면 그 내용에 뜻만 덧붙여요. " +
                    "백그라운드에서 만들어서 앱을 닫거나 화면을 꺼도 계속돼요. 충전기에 꽂아두는 걸 권해요."
                textSize = 13f
                setTextColor(Color.GRAY)
            })
            addView(engineView)
            addView(label("외국어 (대화 언어)"))
            addView(foreignSpinner)
            addView(label("뜻을 달 언어"))
            addView(meaningSpinner)
            addView(progress)
            addView(legend)
            addView(gridNote)
            addView(grid)
            addView(status)
            addView(button("▶ 시작 / 이어서 (백그라운드)") {
                showEngine()
                val f = foreign()
                val m = meaning()
                when {
                    f == m -> status.text = "외국어와 뜻 언어를 서로 다르게 고르세요."
                    running -> status.text = "이미 만드는 중이에요: $runningCombo"
                    selectedEngine(prefs) == null -> status.text = engineProblem(prefs)
                    else -> {
                        if (Build.VERSION.SDK_INT >= 33 &&
                            activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        ) {
                            activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 5)
                        }
                        prefs.edit().putString("builder_foreign", f).putString("builder_meaning", m).apply()
                        BuildService.start(activity, f, m)
                        refresh("시작했어요… 진행 상황은 알림창에서도 볼 수 있어요.")
                    }
                }
            })
            addView(button("⏸ 멈추기") {
                stop()
                refresh("멈추는 중… (지금 요청이 끝나면 멈춰요)")
            })
            addView(button("🔍 검사·정리 (언어 섞임 고치기)") {
                if (running) {
                    status.text = "만드는 중에는 정리할 수 없어요. 먼저 멈추세요."
                } else {
                    status.text = "검사하는 중…"
                    Thread {
                        val n = try { repairAll(activity) } catch (_: Exception) { -1 }
                        activity.runOnUiThread {
                            refresh(
                                when {
                                    n < 0 -> "검사하지 못했어요."
                                    n == 0 -> "✔ 문제를 찾지 못했어요."
                                    else -> "✔ ${n}곳을 고치거나 지웠어요. 지운 대화는 '시작 / 이어서'로 다시 만들 수 있어요."
                                }
                            )
                        }
                    }.start()
                }
            })
            addView(button("📥 가져오기 (zip 합치기)") {
                if (running) {
                    status.text = "만드는 중에는 가져올 수 없어요. 먼저 멈추세요."
                } else {
                    activity.startActivity(Intent(activity, ImportActivity::class.java))
                }
            })
            addView(button("📦 내보내기 (다운로드 폴더)") {
                status.text = "압축하는 중…"
                Thread {
                    val path = try { export(activity) } catch (_: Exception) { null }
                    activity.runOnUiThread {
                        status.text = if (path != null) {
                            "✔ $path 에 저장했어요. 앱에 이미 들어 있던 콘텐츠와 이 폰에서 만들거나 가져온 콘텐츠가 모두 들어 있어요. " +
                                "GitHub의 app/src/main/assets/ 폴더에 situations.zip 이름으로 올려 기존 파일을 바꾸세요. " +
                                "이름이 'situations (1).zip'처럼 바뀌었다면 situations.zip으로 바꿔서 올리세요."
                        } else {
                            "내보낼 콘텐츠가 없거나 저장하지 못했어요."
                        }
                    }
                }.start()
            })
        }
        listener = { msg -> refresh(msg) }
        showEngine()
        refresh(null)
        AlertDialog.Builder(activity)
            .setTitle("🛠 콘텐츠 일괄 생성")
            .setView(ScrollView(activity).apply { addView(box) })
            .setPositiveButton(I18n.t("close"), null)
            .setOnDismissListener { listener = null }
            .show().also { Ui.watch(it) }
    }
}
