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

    fun done(ctx: Context, foreign: String, home: String, count: Int): Int {
        var n = 0
        for (pi in 0 until count) {
            val o = current(ctx, foreign, pi)
            val qs = o.optJSONArray("questions") ?: continue
            if (has(qs.optJSONObject(0)?.optJSONObject("title"), home)) n++
            val ds = o.optJSONArray("dialogues") ?: continue
            for (i in 0 until minOf(qs.length(), 10)) {
                val d = ds.optJSONObject(i) ?: continue
                if (dialogueHas(d, home)) n++
            }
        }
        return n
    }

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
            val o = current(ctx, foreign, pi)
            var qs = o.optJSONArray("questions")
            val step = "${pi + 1}/${list.size} ${p.name}"

            if (qs == null || qs.length() == 0) {
                report("$step · 질문 목록 만드는 중…")
                val r = ask(prefs, engine, questionsPrompt(name, mine), "Place: ${p.name} (${p.desc}). Language of the phrases: $name.")
                val arr = r.optJSONArray("questions") ?: throw IllegalStateException("질문 목록 형식 오류")
                val built = JSONArray()
                for (i in 0 until arr.length()) {
                    val q = arr.optJSONObject(i) ?: continue
                    val phrase = q.optString("phrase")
                    if (phrase.isBlank()) continue
                    built.put(
                        JSONObject()
                            .put("phrase", phrase)
                            .put("title", JSONObject().put(home, q.optString("title")))
                            .put("meaning", JSONObject().put(home, q.optString("meaning")))
                    )
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

            val ds = o.optJSONArray("dialogues") ?: JSONArray().also { o.put("dialogues", it) }
            for (qi in 0 until minOf(qs.length(), 10)) {
                if (stopRequested) return
                val q = qs.optJSONObject(qi) ?: continue
                val d = ds.optJSONObject(qi)
                if (d == null) {
                    report("$step · 대화 ${qi + 1}/10 만드는 중…")
                    val title = anyText(q.optJSONObject("title"))
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
                    ds.put(qi, JSONObject().put("lines", built).put("tips", JSONObject().put(home, r.optString("tips"))))
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

    // Same sentences: meanings of every language are combined. Different sentences: the imported file wins.
    private fun merge(base: JSONObject, add: JSONObject): JSONObject {
        val bq = base.optJSONArray("questions")
        val aq = add.optJSONArray("questions")
        if (aq == null || aq.length() == 0) return base
        if (bq == null || bq.length() == 0 || phrases(bq) != phrases(aq)) return add
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
            if (b == null || lineTexts(b) != lineTexts(a)) {
                bd.put(i, a)
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
                                save(file(ctx, lang, place), merge(current(ctx, lang, place), incoming))
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
        val entries = LinkedHashMap<String, File>()
        for (root in listOf(shipped(ctx), dir(ctx))) {
            if (!root.exists()) continue
            root.walkTopDown()
                .filter { it.isFile && it.extension == "json" }
                .forEach { entries[it.relativeTo(root).path] = it }
        }
        if (entries.isEmpty()) return null
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "situations.zip")
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        ctx.contentResolver.openOutputStream(uri)?.use { os ->
            ZipOutputStream(os).use { zip ->
                for ((path, f) in entries) {
                    zip.putNextEntry(ZipEntry(path))
                    f.inputStream().use { it.copyTo(zip) }
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

        val total = list.size * 11
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
        fun refresh(msg: String?) {
            val f = foreign()
            val m = meaning()
            Thread {
                val count = done(activity, f, m, list.size)
                activity.runOnUiThread {
                    progress.text = "${native(f)} + ${native(m)} 뜻 · 완료 $count / $total (${count * 100 / total}%)" +
                        if (running) "\n만드는 중: $runningCombo" else ""
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
