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
import com.google.mlkit.nl.translate.TranslateLanguage
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

    private class Lang(val label: String, val code: String, val voice: String)
    private class Block(val box: Rect, val text: String) {
        var tr: String = ""
    }

    private val langs = listOf(
        Lang("日本語", TranslateLanguage.JAPANESE, "ja-JP"),
        Lang("中文", TranslateLanguage.CHINESE, "zh-CN"),
        Lang("English", TranslateLanguage.ENGLISH, "en-US"),
        Lang("Français", TranslateLanguage.FRENCH, "fr-FR"),
        Lang("Español", TranslateLanguage.SPANISH, "es-ES"),
        Lang("한국어", TranslateLanguage.KOREAN, "ko-KR")
    )

    private lateinit var previewView: PreviewView
    private lateinit var frozenImage: ImageView
    private lateinit var overlay: Overlay
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
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        previewView = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        frozenImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            visibility = View.GONE
        }
        overlay = Overlay(this)

        val labels = langs.map { it.label }
        srcSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, labels)
            setSelection(0)
        }
        tgtSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, labels)
            setSelection(5)
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
            text = "기기 번역: Google 번역 제공"
            textSize = 11f
            setTextColor(Color.GRAY)
        }
        freezeButton = Button(this).apply {
            text = "멈추고 정확히 번역"
            setOnClickListener { if (frozen) unfreeze() else freeze() }
        }
        val closeButton = Button(this).apply {
            text = "닫기"
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
        val root = FrameLayout(this).apply {
            addView(previewView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(frozenImage, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(overlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(panel, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        }
        setContentView(root)

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
        else status("카메라 권한을 허용해야 쓸 수 있어요.")
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
        TranslateLanguage.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        TranslateLanguage.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        TranslateLanguage.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private fun readyText() = "글자를 비추세요 · ${src.label} → ${tgt.label}"

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
        overlay.invalidate()
        if (src.code == tgt.code) {
            status("서로 다른 두 언어를 고르세요.")
            return
        }
        val t = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(src.code)
                .setTargetLanguage(tgt.code)
                .build()
        )
        translator = t
        status("언어팩 준비 중… 처음 한 번은 인터넷이 필요해요.")
        t.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                if (translator === t) {
                    translatorReady = true
                    status(readyText())
                }
            }
            .addOnFailureListener { if (translator === t) status("언어팩을 받지 못했어요. 인터넷 연결을 확인하세요.") }
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
        val sep = if (src.code == TranslateLanguage.JAPANESE || src.code == TranslateLanguage.CHINESE) "" else " "
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
                        overlay.invalidate()
                    }
                }
                .addOnFailureListener { pending.remove(text) }
        }
        blocks = found
        overlay.invalidate()
    }

    /* ---------- Freeze and translate with the chosen engine ---------- */

    private fun freeze() {
        val bmp: Bitmap = previewView.bitmap ?: run { toast("화면을 가져오지 못했어요."); return }
        val r = recognizer ?: return
        frozen = true
        val gen = ++generation
        frozenImage.setImageBitmap(bmp)
        frozenImage.visibility = View.VISIBLE
        blocks = emptyList()
        overlay.invalidate()
        freezeButton.text = "다시 실시간"
        status("글자 인식 중…")
        r.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { result ->
                if (gen != generation) return@addOnSuccessListener
                imgW = bmp.width
                imgH = bmp.height
                val found = toBlocks(result)
                blocks = found
                overlay.invalidate()
                if (found.isEmpty()) status("글자를 찾지 못했어요. 다시 실시간으로 비춰 보세요.")
                else translateFrozen(found, gen)
            }
            .addOnFailureListener { if (gen == generation) status("글자를 인식하지 못했어요.") }
    }

    private fun translateFrozen(found: List<Block>, gen: Int) {
        val engine = Engines.active(prefs)
        if (engine == "mlkit") {
            found.forEach { mlkitOne(it, gen) }
            status("완료 · 기기 번역 · 글자를 누르면 원문 보기·복사·저장")
            return
        }
        status("번역 중… · " + Engines.label(engine))
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
                            status(Engines.label(engine) + " 연결 실패 · 기기 번역으로 표시해요")
                            rest.forEach { mlkitOne(it, gen) }
                        }
                    }
                    return@execute
                }
                runOnUiThread {
                    if (gen == generation) {
                        b.tr = tr
                        overlay.invalidate()
                    }
                }
            }
            runOnUiThread {
                if (gen == generation) status("완료 · ${Engines.label(engine)} · 글자를 누르면 원문 보기·복사·저장")
            }
        }
    }

    private fun mlkitOne(b: Block, gen: Int) {
        val hit = cache[b.text]
        if (hit != null) {
            b.tr = hit
            overlay.invalidate()
            return
        }
        val t = translator ?: return
        t.translate(b.text).addOnSuccessListener { tr ->
            if (gen == generation) {
                b.tr = tr
                overlay.invalidate()
            }
        }
    }

    private fun unfreeze() {
        frozen = false
        generation++
        frozenImage.visibility = View.GONE
        frozenImage.setImageBitmap(null)
        blocks = emptyList()
        overlay.invalidate()
        freezeButton.text = "멈추고 정확히 번역"
        status(readyText())
    }

    /* ---------- Tap a block ---------- */

    private fun showDetail(b: Block) {
        AlertDialog.Builder(this)
            .setTitle(b.tr)
            .setMessage("원문\n" + b.text)
            .setPositiveButton("저장") { _, _ -> save(b) }
            .setNeutralButton("복사") { _, _ -> copy(b.tr) }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun save(b: Block) {
        val arr = try { JSONArray(prefs.getString("saved", "[]")) } catch (_: Exception) { JSONArray() }
        arr.put(JSONObject().put("t", b.tr).put("n", b.text).put("v", tgt.voice))
        prefs.edit().putString("saved", arr.toString()).apply()
        toast("저장함에 저장했어요")
    }

    private fun copy(text: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("번역", text))
        toast("복사했어요")
    }

    private fun status(msg: String) { statusView.text = msg }
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        recognizer?.close()
        translator?.close()
        analysisExecutor.shutdown()
        net.shutdown()
        super.onDestroy()
    }

    /* ---------- Drawing ---------- */

    private inner class Overlay(ctx: Context) : View(ctx) {
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