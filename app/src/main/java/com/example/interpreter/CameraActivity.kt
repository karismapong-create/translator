package com.example.interpreter

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.text.StaticLayout
import android.text.TextPaint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

// Points the camera at text and draws the translation on top of it.
class CameraActivity : ComponentActivity() {

    private class Block(val box: Rect, val text: String) {
        var tr: String = ""
    }

    private val langs = Languages.all
    private lateinit var home: Lang

    private lateinit var previewView: PreviewView
    private lateinit var frozenImage: ImageView
    private lateinit var textOverlay: TextOverlay
    private lateinit var srcSpinner: Spinner
    private lateinit var tgtSpinner: Spinner
    private lateinit var statusView: TextView
    private lateinit var freezeButton: Button

    private var recognizer: TextRecognizer? = null
    private var translator: Translator? = null
    private var translatorReady = false
    private val cache = HashMap<String, String>()
    private val pending = HashSet<String>()
    private var blocks: List<Block> = emptyList()
    private var imgW = 0
    private var imgH = 0
    @Volatile private var busy = false
    @Volatile private var frozen = false
    private var lastRun = 0L
    private var generation = 0
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val net = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("interpreter", MODE_PRIVATE) }

    private val src: Lang get() = langs[srcSpinner.selectedItemPosition]
    private val tgt: Lang get() = langs[tgtSpinner.selectedItemPosition]

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        home = Languages.home(prefs)
        I18n.use(home.code)
        OnDevice.refresh()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        previewView = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        frozenImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            visibility = View.GONE
        }
        textOverlay = TextOverlay(this)

        val labels = langs.map { it.native }
        srcSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, labels)
            setSelection(langs.indexOfFirst { it.code != home.code })
        }
        tgtSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, labels)
            setSelection(langs.indexOf(home))
        }
        val arrow = TextView(this).apply {
            text = "→"
            textSize = 20f
            setPadding(dp(8), 0, dp(8), 0)
        }
        val langRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(srcSpinner, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(arrow)
            addView(tgtSpinner, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        statusView = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.DKGRAY)
        }
        val credit = TextView(this).apply {
            text = CamText.t("c_credit")
            textSize = 11f
            setTextColor(Color.GRAY)
        }
        freezeButton = Button(this).apply {
            text = CamText.t("c_freeze")
            isAllCaps = false
            setOnClickListener { if (frozen) unfreeze() else freeze() }
        }
        val closeButton = Button(this).apply {
            text = I18n.t("close")
            setOnClickListener { finish() }
        }
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(freezeButton, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(closeButton, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(235, 255, 255, 255))
            setPadding(dp(16), dp(8), dp(16), dp(16))
            addView(langRow)
            addView(statusView)
            addView(credit)
            addView(buttonRow)
        }
        val overlayView: View = textOverlay
        val root = FrameLayout(this).apply {
            addView(previewView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(frozenImage, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(overlayView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(panel, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        }
        setContentView(root)
        Ui.edgeToEdge(this, root)
        Ui.watch(root)

        val onChange = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = setupLanguages()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        srcSpinner.onItemSelectedListener = onChange
        tgtSpinner.onItemSelectedListener = onChange

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else requestPermissions(arrayOf(Manifest.permission.CAMERA), 2)
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startCamera()
        else status(CamText.t("c_perm"))
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analysisExecutor) { analyze(it) }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    /* ---------- Languages ---------- */

    private fun makeRecognizer(code: String): TextRecognizer = when (code) {
        "ja" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        "zh" -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        "ko" -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private fun readyText() = CamText.f("c_ready", src.native, tgt.native)

    private fun setupLanguages() {
        if (frozen) unfreeze()
        recognizer?.close()
        recognizer = makeRecognizer(src.code)
        translator?.close()
        translator = null
        translatorReady = false
        cache.clear()
        pending.clear()
        blocks = emptyList()
        textOverlay.invalidate()
        if (src.code == tgt.code) {
            status(I18n.t("same_lang"))
            return
        }
        val t = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(src.code)
                .setTargetLanguage(tgt.code)
                .build()
        )
        translator = t
        status(I18n.t("preparing"))
        t.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                if (translator === t) {
                    translatorReady = true
                    status(readyText())
                }
            }
            .addOnFailureListener { if (translator === t) status(I18n.t("pack_failed")) }
    }

    /* ---------- Live mode ---------- */

    // Runs on the camera thread. Reads text a few times per second.
    private fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        val r = recognizer
        val now = System.currentTimeMillis()
        if (media == null || r == null || frozen || busy || now - lastRun < 700) {
            proxy.close()
            return
        }
        busy = true
        lastRun = now
        val rot = proxy.imageInfo.rotationDegrees
        val w = if (rot % 180 == 0) proxy.width else proxy.height
        val h = if (rot % 180 == 0) proxy.height else proxy.width
        r.process(InputImage.fromMediaImage(media, rot))
            .addOnSuccessListener { result -> showLive(toBlocks(result), w, h) }
            .addOnCompleteListener {
                proxy.close()
                busy = false
            }
    }

    private fun toBlocks(result: Text): List<Block> {
        val sep = if (src.code == "ja" || src.code == "zh") "" else " "
        return result.textBlocks.mapNotNull { b ->
            val box = b.boundingBox ?: return@mapNotNull null
            val text = b.lines.joinToString(sep) { it.text }.trim()
            if (text.length < 2) null else Block(box, text)
        }
    }

    private fun showLive(found: List<Block>, w: Int, h: Int) {
        if (frozen) return
        imgW = w
        imgH = h
        if (cache.size > 300) cache.clear()
        val t = translator
        for (b in found) {
            val hit = cache[b.text]
            if (hit != null) { b.tr = hit; continue }
            if (!translatorReady || t == null || !pending.add(b.text)) continue
            val text = b.text
            t.translate(text)
                .addOnSuccessListener { tr ->
                    pending.remove(text)
                    if (translator !== t) return@addOnSuccessListener
                    cache[text] = tr
                    if (!frozen) {
                        blocks.filter { it.text == text }.forEach { it.tr = tr }
                        textOverlay.invalidate()
                    }
                }
                .addOnFailureListener { pending.remove(text) }
        }
        blocks = found
        textOverlay.invalidate()
    }

    /* ---------- Freeze and translate with the chosen engine ---------- */

    private fun freeze() {
        val bmp: Bitmap = previewView.bitmap ?: run { toast(CamText.t("c_no_frame")); return }
        val r = recognizer ?: return
        frozen = true
        val gen = ++generation
        frozenImage.setImageBitmap(bmp)
        frozenImage.visibility = View.VISIBLE
        blocks = emptyList()
        textOverlay.invalidate()
        freezeButton.text = CamText.t("c_live")
        status(CamText.t("c_reading"))
        r.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { result ->
                if (gen != generation) return@addOnSuccessListener
                imgW = bmp.width
                imgH = bmp.height
                val found = toBlocks(result)
                blocks = found
                textOverlay.invalidate()
                if (found.isEmpty()) status(CamText.t("c_none"))
                else translateFrozen(found, gen)
            }
            .addOnFailureListener { if (gen == generation) status(CamText.t("c_fail")) }
    }

    private fun translateFrozen(found: List<Block>, gen: Int) {
        val engine = Engines.active(prefs)
        if (engine == "mlkit") {
            found.forEach { mlkitOne(it, gen) }
            status(CamText.f("c_done", Engines.label(engine)))
            return
        }
        status(CamText.f("c_translating", Engines.label(engine)))
        val s = src.code
        val g = tgt.code
        net.execute {
            for ((i, b) in found.withIndex()) {
                if (gen != generation) return@execute
                val tr: String? = try { Engines.translate(prefs, engine, b.text, s, g) } catch (e: Exception) { null }
                if (tr.isNullOrBlank()) {
                    val rest = found.drop(i)
                    runOnUiThread {
                        if (gen == generation) {
                            status(CamText.f("c_engine_fail", Engines.label(engine)))
                            rest.forEach { mlkitOne(it, gen) }
                        }
                    }
                    return@execute
                }
                runOnUiThread {
                    if (gen == generation) {
                        b.tr = tr
                        textOverlay.invalidate()
                    }
                }
            }
            runOnUiThread {
                if (gen == generation) status(CamText.f("c_done", Engines.label(engine)))
            }
        }
    }

    private fun mlkitOne(b: Block, gen: Int) {
        val hit = cache[b.text]
        if (hit != null) {
            b.tr = hit
            textOverlay.invalidate()
            return
        }
        val t = translator ?: return
        t.translate(b.text).addOnSuccessListener { tr ->
            if (gen == generation) {
                b.tr = tr
                textOverlay.invalidate()
            }
        }
    }

    private fun unfreeze() {
        frozen = false
        generation++
        frozenImage.visibility = View.GONE
        frozenImage.setImageBitmap(null)
        blocks = emptyList()
        textOverlay.invalidate()
        freezeButton.text = CamText.t("c_freeze")
        status(readyText())
    }

    /* ---------- Tap a block ---------- */

    private fun showDetail(b: Block) {
        AlertDialog.Builder(this)
            .setTitle(b.tr)
            .setMessage(CamText.t("c_original") + "\n" + b.text)
            .setPositiveButton(CamText.t("c_save")) { _, _ -> save(b) }
            .setNeutralButton(VocabText.t("copy")) { _, _ -> copy(b.tr) }
            .setNegativeButton(I18n.t("close"), null)
            .show().also { Ui.watch(it) }
    }

    // Saves the foreign text with its meaning in my language, in the word list's format.
    private fun save(b: Block) {
        val (text, meaning, lang) =
            if (tgt.code == home.code) Triple(b.text, b.tr, src)
            else Triple(b.tr, if (src.code == home.code) b.text else "", tgt)
        val arr = try { JSONArray(prefs.getString("saved", "[]")) } catch (_: Exception) { JSONArray() }
        arr.put(
            JSONObject()
                .put("t", text)
                .put("m", meaning)
                .put("e", "")
                .put("em", "")
                .put("v", lang.speechTag)
                .put("l", lang.code)
                .put("d", System.currentTimeMillis())
                .put("c", 0)
                .put("w", 0)
        )
        prefs.edit().putString("saved", arr.toString()).apply()
        toast(TalkText.t("saved_toast"))
    }

    private fun copy(text: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("text", text))
        toast(VocabText.t("copied"))
    }

    private fun status(msg: String) { statusView.text = msg }
    private fun toast(msg: String) = Toast.makeText(this, Ui.plain(msg), Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        recognizer?.close()
        translator?.close()
        analysisExecutor.shutdown()
        net.shutdown()
        super.onDestroy()
    }

    /* ---------- Drawing ---------- */

    private inner class TextOverlay(ctx: Context) : View(ctx) {
        private val density = resources.displayMetrics.density
        private val minSize = 11 * density
        private val bg = Paint().apply { color = Color.argb(230, 255, 255, 255) }
        private val border = Paint().apply {
            color = Color.rgb(47, 91, 234)
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }

        // Converts a box from camera-image coordinates to screen coordinates.
        fun map(r: Rect): RectF {
            val scale = maxOf(width.toFloat() / imgW, height.toFloat() / imgH)
            val ox = (width - imgW * scale) / 2f
            val oy = (height - imgH * scale) / 2f
            return RectF(r.left * scale + ox, r.top * scale + oy, r.right * scale + ox, r.bottom * scale + oy)
        }

        private fun make(text: String, size: Float, w: Int): StaticLayout {
            tp.textSize = size
            return StaticLayout.Builder.obtain(text, 0, text.length, tp, w).build()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (imgW == 0 || imgH == 0) return
            for (b in blocks) {
                if (b.tr.isEmpty()) continue
                val r = map(b.box)
                val w = r.width().toInt().coerceAtLeast((40 * density).toInt())
                var size = (r.height() * 0.8f).coerceIn(minSize, 48 * density)
                var layout = make(b.tr, size, w)
                while (layout.height > r.height() && size > minSize) {
                    size = maxOf(minSize, size * 0.88f)
                    layout = make(b.tr, size, w)
                }
                val area = RectF(r.left, r.top, r.left + w, r.top + maxOf(r.height(), layout.height.toFloat()))
                canvas.drawRect(area, bg)
                canvas.drawRect(area, border)
                canvas.save()
                canvas.translate(r.left, r.top)
                layout.draw(canvas)
                canvas.restore()
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (blocks.isEmpty()) return false
            if (e.action == MotionEvent.ACTION_UP) {
                val hit = blocks.lastOrNull { it.tr.isNotEmpty() && map(it.box).contains(e.x, e.y) }
                if (hit != null) showDetail(hit)
            }
            return true
        }
    }
}

// Screen text for the camera screen. Order in m(): ko, ja, en, fr, es, de, zh.
private object CamText {

    fun t(key: String): String = table[key]?.let { it[I18n.lang] ?: it["en"] ?: it["ko"] } ?: key

    fun f(key: String, vararg args: Any): String = String.format(t(key), *args)

    private fun m(ko: String, ja: String, en: String, fr: String, es: String, de: String, zh: String) =
        mapOf("ko" to ko, "ja" to ja, "en" to en, "fr" to fr, "es" to es, "de" to de, "zh" to zh)

    private val table: Map<String, Map<String, String>> = mapOf(
        "c_credit" to m("기기 번역: Google 번역 제공", "端末内翻訳: Google 翻訳提供", "On-device translation by Google Translate", "Traduction sur l'appareil par Google Traduction", "Traducción en el dispositivo con Google Traductor", "Übersetzung auf dem Gerät von Google Übersetzer", "设备翻译：由 Google 翻译提供"),
        "c_freeze" to m("멈추고 정확히 번역", "止めて正確に翻訳", "Freeze & translate precisely", "Figer et traduire précisément", "Congelar y traducir con precisión", "Anhalten & genau übersetzen", "定格并精确翻译"),
        "c_live" to m("다시 실시간", "ライブに戻る", "Back to live", "Retour au direct", "Volver a en vivo", "Zurück zu live", "返回实时"),
        "c_perm" to m("카메라 권한을 허용해야 쓸 수 있어요.", "カメラの許可が必要です。", "Allow camera access to use this.", "Autorisez l'appareil photo pour continuer.", "Permite el acceso a la cámara.", "Erlaube den Kamerazugriff.", "需要相机权限。"),
        "c_ready" to m("글자를 비추세요 · %1\$s → %2\$s", "文字を映してください · %1\$s → %2\$s", "Point at text · %1\$s → %2\$s", "Visez le texte · %1\$s → %2\$s", "Apunta al texto · %1\$s → %2\$s", "Auf Text richten · %1\$s → %2\$s", "对准文字 · %1\$s → %2\$s"),
        "c_no_frame" to m("화면을 가져오지 못했어요.", "画面を取得できませんでした。", "Couldn't capture the screen.", "Impossible de capturer l'écran.", "No se pudo capturar la pantalla.", "Bild konnte nicht erfasst werden.", "无法获取画面。"),
        "c_reading" to m("글자 인식 중…", "文字を認識中…", "Reading text…", "Lecture du texte…", "Leyendo texto…", "Text wird erkannt…", "正在识别文字…"),
        "c_none" to m("글자를 찾지 못했어요. 다시 실시간으로 비춰 보세요.", "文字が見つかりませんでした。ライブでもう一度映してください。", "No text found. Go back to live and try again.", "Aucun texte trouvé. Revenez au direct et réessayez.", "No se encontró texto. Vuelve a en vivo e inténtalo.", "Kein Text gefunden. Zurück zu live und erneut versuchen.", "未找到文字，请返回实时重试。"),
        "c_fail" to m("글자를 인식하지 못했어요.", "文字を認識できませんでした。", "Couldn't read the text.", "Impossible de lire le texte.", "No se pudo leer el texto.", "Text konnte nicht erkannt werden.", "无法识别文字。"),
        "c_done" to m("완료 · %s · 글자를 누르면 원문 보기·복사·저장", "完了 · %s · タップで原文・コピー・保存", "Done · %s · tap text to see the original, copy or save", "Terminé · %s · touchez pour voir l'original, copier ou enregistrer", "Listo · %s · toca para ver el original, copiar o guardar", "Fertig · %s · tippen für Original, Kopieren, Speichern", "完成 · %s · 点按文字查看原文、复制或保存"),
        "c_translating" to m("번역 중… · %s", "翻訳中… · %s", "Translating… · %s", "Traduction… · %s", "Traduciendo… · %s", "Übersetzung… · %s", "翻译中… · %s"),
        "c_engine_fail" to m("%s 연결 실패 · 기기 번역으로 표시해요", "%s に接続できません · 端末内翻訳で表示します", "%s failed · showing on-device translation", "Échec de %s · traduction locale affichée", "%s falló · se muestra la traducción local", "%s fehlgeschlagen · Geräteübersetzung wird gezeigt", "%s 连接失败 · 显示设备翻译"),
        "c_original" to m("원문", "原文", "Original", "Original", "Original", "Original", "原文"),
        "c_save" to m("저장", "保存", "Save", "Enregistrer", "Guardar", "Speichern", "保存")
    )
}