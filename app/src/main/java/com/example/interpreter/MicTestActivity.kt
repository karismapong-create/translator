package com.example.interpreter

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

// Shows connected microphones, live left/right levels, and which speech engines accept app-recorded audio.
class MicTestActivity : Activity() {

    private class EngineChoice(val label: String, val component: ComponentName?, val onDevice: Boolean)

    private val audio by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val prefs by lazy { getSharedPreferences("interpreter", MODE_PRIVATE) }
    private lateinit var home: Lang
    private var devices: List<AudioDeviceInfo> = emptyList()
    private var engines: List<EngineChoice> = emptyList()
    private lateinit var deviceSpinner: Spinner
    private lateinit var deviceInfo: TextView
    private lateinit var routedText: TextView
    private lateinit var leftBar: ProgressBar
    private lateinit var rightBar: ProgressBar
    private lateinit var leftText: TextView
    private lateinit var rightText: TextView
    private lateinit var verdict: TextView
    private lateinit var meterButton: Button
    private lateinit var engineSpinner: Spinner
    private lateinit var sampleInfo: TextView
    private lateinit var recogResult: TextView

    @Volatile private var metering = false
    private var meterThread: Thread? = null
    private var loudFrames = 0
    private var splitFrames = 0
    private var recognizer: SpeechRecognizer? = null
    private var pipe: Array<ParcelFileDescriptor>? = null
    private var lastPcm: ByteArray? = null
    private var player: AudioTrack? = null
    private var testRun = 0

    private val green = Color.rgb(46, 160, 67)
    private val red = Color.rgb(207, 34, 46)
    private val sampleRate = 16000
    private val hidden = setOf(
        AudioDeviceInfo.TYPE_TELEPHONY, AudioDeviceInfo.TYPE_REMOTE_SUBMIX,
        AudioDeviceInfo.TYPE_FM_TUNER, 28
    )
    private val selected: AudioDeviceInfo? get() = devices.getOrNull(deviceSpinner.selectedItemPosition)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        home = Languages.home(prefs)
        I18n.use(home.code)
        val pad = dp(16)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        box.addView(label(MicText.t("title"), 22f, true))
        box.addView(label(MicText.t("intro"), 14f, false, Color.DKGRAY))

        box.addView(section(MicText.t("s1")))
        deviceSpinner = Spinner(this)
        box.addView(deviceSpinner)
        deviceInfo = label("", 14f, false, Color.DKGRAY)
        box.addView(deviceInfo)
        box.addView(Button(this).apply {
            text = MicText.t("refresh")
            isAllCaps = false
            setOnClickListener { loadDevices() }
        })

        box.addView(section(MicText.t("s2")))
        leftText = label(MicText.t("left"), 14f, false)
        leftBar = bar()
        rightText = label(MicText.t("right"), 14f, false)
        rightBar = bar()
        box.addView(leftText)
        box.addView(leftBar)
        box.addView(rightText)
        box.addView(rightBar)
        routedText = label("", 13f, false, Color.GRAY)
        box.addView(routedText)
        verdict = label("", 16f, true)
        box.addView(verdict)
        meterButton = Button(this).apply {
            text = MicText.t("start_meter")
            isAllCaps = false
            setOnClickListener { if (metering) stopMeter() else startMeter() }
        }
        box.addView(meterButton)

        box.addView(section(MicText.t("s3")))
        box.addView(label(MicText.f("s3_help", home.native), 14f, false, Color.DKGRAY))
        box.addView(label(MicText.t("engine_label"), 13f, false, Color.GRAY))
        engineSpinner = Spinner(this)
        box.addView(engineSpinner)
        box.addView(Button(this).apply {
            text = MicText.t("rec_test")
            isAllCaps = false
            setOnClickListener { recordAndTest() }
        })
        sampleInfo = label("", 14f, false, Color.DKGRAY)
        box.addView(sampleInfo)
        val replayRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(this@MicTestActivity).apply {
                text = MicText.t("replay")
                isAllCaps = false
                setOnClickListener { playLast() }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(this@MicTestActivity).apply {
                text = MicText.t("retest")
                isAllCaps = false
                setOnClickListener { retestLast() }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        box.addView(replayRow)
        recogResult = label("", 15f, false)
        recogResult.setTextIsSelectable(true)
        box.addView(recogResult)

        box.addView(Button(this).apply {
            text = I18n.t("close")
            setOnClickListener { finish() }
        })
        setContentView(ScrollView(this).apply { addView(box) })
        Ui.watch(box)

        deviceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                showDeviceInfo()
                if (metering) {
                    stopMeter()
                    startMeter()
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        if (!hasMic()) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 3)
        loadDevices()
        loadEngines()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun label(text: String, size: Float, bold: Boolean, color: Int = Color.BLACK) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(null, Typeface.BOLD)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun section(text: String) = label(text, 17f, true).apply { setPadding(0, dp(20), 0, dp(6)) }

    private fun bar() = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }

    /* ---------- Devices ---------- */

    private fun typeName(t: Int) = when (t) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> MicText.t("t_builtin")
        AudioDeviceInfo.TYPE_USB_DEVICE -> MicText.t("t_usb")
        AudioDeviceInfo.TYPE_USB_HEADSET -> MicText.t("t_usb_headset")
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> MicText.t("t_usb_acc")
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> MicText.t("t_wired")
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> MicText.t("t_bt")
        26 -> MicText.t("t_ble")
        else -> MicText.f("t_other", t)
    }

    private fun loadDevices() {
        devices = audio.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { it.type !in hidden }
        val labels = devices.map { d -> typeName(d.type) + " · " + d.productName }
        deviceSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            labels.ifEmpty { listOf(MicText.t("no_mic")) }
        )
        val usb = devices.indexOfFirst {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
        if (usb >= 0) deviceSpinner.setSelection(usb)
        showDeviceInfo()
    }

    private fun showDeviceInfo() {
        val d = selected
        if (d == null) {
            deviceInfo.text = ""
            return
        }
        val any = MicText.t("unlimited")
        val channels = d.channelCounts.takeIf { it.isNotEmpty() }?.joinToString("/") ?: any
        val rates = d.sampleRates.takeIf { it.isNotEmpty() }?.joinToString("/") ?: any
        val stereo = d.channelCounts.isEmpty() || d.channelCounts.any { it >= 2 }
        deviceInfo.text = MicText.f("info", channels, rates) + "\n" +
            MicText.t(if (stereo) "stereo_yes" else "stereo_no")
    }

    /* ---------- Speech engines on this phone ---------- */

    private fun loadEngines() {
        val list = ArrayList<EngineChoice>()
        list += EngineChoice(MicText.t("sys_engine"), null, false)
        if (Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            list += EngineChoice(MicText.t("ondevice"), null, true)
        }
        val found = packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
        for (info in found) {
            val si = info.serviceInfo
            val name = info.loadLabel(packageManager).toString()
            list += EngineChoice("$name · ${si.packageName}", ComponentName(si.packageName, si.name), false)
        }
        engines = list
        engineSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, list.map { it.label })
    }

    /* ---------- Left / right levels ---------- */

    private fun db(rms: Double) = if (rms < 1.0) -90.0 else 20 * log10(rms / 32768.0)
    private fun pct(db: Double) = ((db + 60) / 60 * 100).toInt().coerceIn(0, 100)

    private fun startMeter() {
        if (!hasMic()) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 3)
            return
        }
        val rate = 48000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 19200)
            )
        } catch (_: Exception) {
            null
        }
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            rec?.release()
            verdict.setTextColor(red)
            verdict.text = MicText.t("stereo_fail")
            return
        }
        selected?.let { rec.setPreferredDevice(it) }
        loudFrames = 0
        splitFrames = 0
        verdict.text = MicText.t("tap")
        verdict.setTextColor(Color.DKGRAY)
        rec.startRecording()
        metering = true
        meterButton.text = MicText.t("stop_meter")
        meterThread = Thread {
            val buf = ShortArray(4800)
            while (metering) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 1) continue
                var l = 0.0
                var r = 0.0
                var i = 0
                while (i + 1 < n) {
                    l += (buf[i] * buf[i]).toDouble()
                    r += (buf[i + 1] * buf[i + 1]).toDouble()
                    i += 2
                }
                val half = (n / 2).coerceAtLeast(1)
                val ld = db(sqrt(l / half))
                val rd = db(sqrt(r / half))
                val routed = rec.routedDevice
                runOnUiThread { showLevels(ld, rd, routed) }
            }
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
        }.also { it.start() }
    }

    private fun showLevels(l: Double, r: Double, routed: AudioDeviceInfo?) {
        if (!metering) return
        leftBar.progress = pct(l)
        rightBar.progress = pct(r)
        leftText.text = MicText.t("left") + "   %.0f dB".format(l)
        rightText.text = MicText.t("right") + "   %.0f dB".format(r)
        routedText.text = MicText.f(
            "routed",
            routed?.let { typeName(it.type) + " · " + it.productName } ?: MicText.t("checking")
        )
        if (maxOf(l, r) > -40) {
            loudFrames++
            if (abs(l - r) > 8) splitFrames++
        }
        if (loudFrames >= 15) {
            val split = splitFrames.toDouble() / loudFrames > 0.3
            verdict.setTextColor(if (split) green else red)
            verdict.text = MicText.t(if (split) "split_ok" else "split_no")
        }
    }

    private fun stopMeter() {
        metering = false
        meterThread?.join(500)
        meterThread = null
        meterButton.text = MicText.t("start_meter")
    }

    /* ---------- Recording a sample ---------- */

    private fun recordAndTest() {
        if (!hasMic()) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 3)
            return
        }
        if (metering) stopMeter()
        stopPlayback()
        cancelRecognizer()
        recogResult.setTextColor(Color.BLACK)
        recogResult.text = ""
        sampleInfo.setTextColor(Color.BLACK)
        sampleInfo.text = MicText.f("speak5", home.native)
        val device = selected
        Thread {
            val pcm = recordMono(5, device)
            runOnUiThread {
                if (pcm == null) {
                    sampleInfo.setTextColor(red)
                    sampleInfo.text = MicText.t("rec_fail")
                } else {
                    lastPcm = pcm
                    describeSample(pcm)
                    runTest(pcm)
                }
            }
        }.start()
    }

    private fun recordMono(seconds: Int, device: AudioDeviceInfo?): ByteArray? {
        val min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, sampleRate)
            )
        } catch (_: Exception) {
            return null
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return null
        }
        device?.let { rec.setPreferredDevice(it) }
        val out = ByteArrayOutputStream()
        val buf = ByteArray(3200)
        val total = sampleRate * 2 * seconds
        rec.startRecording()
        while (out.size() < total) {
            val n = rec.read(buf, 0, buf.size)
            if (n > 0) out.write(buf, 0, n) else break
        }
        try { rec.stop() } catch (_: Exception) {}
        rec.release()
        return out.toByteArray()
    }

    // Shows how loud the recorded sample is, so silent recordings are easy to spot.
    private fun describeSample(pcm: ByteArray) {
        var sum = 0.0
        var peak = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val s = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
            val v = s.toShort().toInt()
            sum += (v * v).toDouble()
            peak = maxOf(peak, abs(v))
            i += 2
        }
        val samples = (pcm.size / 2).coerceAtLeast(1)
        val rmsDb = db(sqrt(sum / samples))
        val peakDb = db(peak.toDouble())
        val heard = peakDb > -30
        sampleInfo.setTextColor(if (heard) green else red)
        sampleInfo.text = MicText.f("sample", rmsDb, peakDb, samples / sampleRate.toDouble()) + "\n" +
            MicText.t(if (heard) "heard" else "silent")
    }

    private fun playLast() {
        val pcm = lastPcm ?: run {
            sampleInfo.text = MicText.t("first")
            return
        }
        stopPlayback()
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size)
            .build()
        track.write(pcm, 0, pcm.size)
        track.play()
        player = track
    }

    private fun stopPlayback() {
        player?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        player = null
    }

    /* ---------- Feeding the sample to a speech engine ---------- */

    private fun retestLast() {
        val pcm = lastPcm ?: run {
            recogResult.text = MicText.t("first")
            return
        }
        runTest(pcm)
    }

    private fun errorText(code: Int) = MicText.t(
        when (code) {
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "e_network"
            SpeechRecognizer.ERROR_AUDIO -> "e_audio"
            SpeechRecognizer.ERROR_SERVER -> "e_server"
            SpeechRecognizer.ERROR_CLIENT -> "e_client"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "e_timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "e_nomatch"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "e_busy"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "e_perm"
            12 -> "e_lang"
            13 -> "e_pack"
            else -> "e_unknown"
        }
    )

    private fun cancelRecognizer() {
        recognizer?.destroy()
        recognizer = null
        pipe?.forEach { try { it.close() } catch (_: Exception) {} }
        pipe = null
    }

    private fun runTest(pcm: ByteArray) {
        if (Build.VERSION.SDK_INT < 33) {
            recogResult.text = MicText.t("need13")
            return
        }
        val choice = engines.getOrNull(engineSpinner.selectedItemPosition) ?: return
        cancelRecognizer()
        val run = ++testRun
        recogResult.setTextColor(Color.BLACK)
        recogResult.text = MicText.f("recognizing", choice.label)
        val r = try {
            when {
                choice.onDevice -> SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
                choice.component != null -> SpeechRecognizer.createSpeechRecognizer(this, choice.component)
                else -> SpeechRecognizer.createSpeechRecognizer(this)
            }
        } catch (e: Exception) {
            recogResult.setTextColor(red)
            recogResult.text = MicText.f("open_fail", e.message ?: "")
            return
        }
        recognizer = r
        val p = ParcelFileDescriptor.createPipe()
        pipe = p
        var finished = false
        var lastPartial = ""

        fun done(color: Int, msg: String) {
            if (run != testRun || finished) return
            finished = true
            recogResult.setTextColor(color)
            recogResult.text = msg
        }
        // Some engines only report text as partial results and send an empty final result.
        fun finishWith(finalText: String?, failure: String) {
            when {
                !finalText.isNullOrBlank() -> done(green, MicText.f("ok", choice.label, finalText))
                lastPartial.isNotBlank() -> done(green, MicText.f("ok_partial", choice.label, lastPartial))
                else -> done(red, "✘ ${choice.label}\n$failure")
            }
        }

        r.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(b: Bundle?) {
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (run == testRun && !finished && !t.isNullOrBlank()) {
                    lastPartial = t
                    recogResult.text = MicText.f("recognizing", "\"$t\"")
                }
            }
            override fun onResults(b: Bundle?) {
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                finishWith(t, MicText.t("empty"))
            }
            override fun onError(error: Int) {
                finishWith(null, MicText.f("code", errorText(error), error))
            }
            override fun onReadyForSpeech(b: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(type: Int, b: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, home.speechTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, p[0])
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, sampleRate)
        }
        try {
            r.startListening(intent)
        } catch (e: Exception) {
            done(red, "✘ ${choice.label}\n" + MicText.f("start_fail", e.message ?: ""))
            return
        }
        Thread {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(p[1]).use { out ->
                    var i = 0
                    while (i < pcm.size) {
                        val n = minOf(3200, pcm.size - i)
                        out.write(pcm, i, n)
                        i += n
                        Thread.sleep(50)
                    }
                }
            } catch (_: Exception) {}
        }.start()
        recogResult.postDelayed({ finishWith(null, MicText.t("no_response")) }, 20000)
    }

    override fun onPause() {
        if (metering) stopMeter()
        stopPlayback()
        super.onPause()
    }

    override fun onDestroy() {
        metering = false
        cancelRecognizer()
        stopPlayback()
        super.onDestroy()
    }
}

// Screen text for the microphone test. Order in m(): ko, ja, en, fr, es, de, zh.
private object MicText {

    fun t(key: String): String = table[key]?.let { it[I18n.lang] ?: it["en"] ?: it["ko"] } ?: key

    fun f(key: String, vararg args: Any): String = String.format(t(key), *args)

    private fun m(ko: String, ja: String, en: String, fr: String, es: String, de: String, zh: String) =
        mapOf("ko" to ko, "ja" to ja, "en" to en, "fr" to fr, "es" to es, "de" to de, "zh" to zh)

    private val table: Map<String, Map<String, String>> = mapOf(
        "title" to m("🎙 마이크 진단", "🎙 マイク診断", "🎙 Microphone test", "🎙 Test du micro", "🎙 Prueba de micrófono", "🎙 Mikrofontest", "🎙 麦克风诊断"),
        "intro" to m(
            "2인용 무선 마이크로 '왼쪽 = 나, 오른쪽 = 상대'를 나눌 수 있는지 확인해요.\n1) 마이크 앱에서 출력 모드를 스테레오로 바꾸고, 수신기를 폰에 꽂으세요.\n2) 아래에서 마이크를 고르고 '소리 측정 시작'을 누르세요.\n3) 송신기 1을 두드리면 왼쪽 막대만, 송신기 2는 오른쪽 막대만 움직이면 성공이에요.",
            "2人用ワイヤレスマイクで「左 = 自分、右 = 相手」を分けられるか確認します。\n1) マイクのアプリで出力をステレオにし、受信機をスマホに挿します。\n2) 下でマイクを選び「測定開始」を押します。\n3) 送信機1を叩くと左だけ、送信機2は右だけ動けば成功です。",
            "Checks whether a two-person wireless mic can split “left = me, right = the other person”.\n1) Set the mic app to stereo output and plug the receiver into the phone.\n2) Pick the mic below and tap “Start measuring”.\n3) Tapping transmitter 1 should move only the left bar, and transmitter 2 only the right bar.",
            "Vérifie si un micro sans fil pour deux personnes sépare « gauche = moi, droite = l'autre ».\n1) Réglez l'appli du micro en stéréo et branchez le récepteur.\n2) Choisissez le micro ci-dessous et touchez « Mesurer ».\n3) L'émetteur 1 doit faire bouger seulement la barre gauche, l'émetteur 2 seulement la droite.",
            "Comprueba si un micrófono inalámbrico para dos separa «izquierda = yo, derecha = la otra persona».\n1) Pon la app del micrófono en estéreo y conecta el receptor.\n2) Elige el micrófono abajo y toca «Medir».\n3) El transmisor 1 debe mover solo la barra izquierda y el 2 solo la derecha.",
            "Prüft, ob ein Funkmikrofon für zwei Personen „links = ich, rechts = Gegenüber“ trennen kann.\n1) In der Mikrofon-App Stereo einstellen und den Empfänger einstecken.\n2) Unten das Mikrofon wählen und „Messen“ tippen.\n3) Sender 1 sollte nur den linken Balken bewegen, Sender 2 nur den rechten.",
            "检查双人无线麦克风能否区分“左 = 我，右 = 对方”。\n1) 在麦克风应用中设为立体声输出，并把接收器插入手机。\n2) 在下方选择麦克风并点“开始测量”。\n3) 敲击发射器 1 只有左侧条动、发射器 2 只有右侧条动即为成功。"
        ),
        "s1" to m("1. 연결된 마이크", "1. 接続中のマイク", "1. Connected microphones", "1. Micros connectés", "1. Micrófonos conectados", "1. Verbundene Mikrofone", "1. 已连接的麦克风"),
        "refresh" to m("목록 새로고침", "一覧を更新", "Refresh list", "Actualiser", "Actualizar lista", "Liste aktualisieren", "刷新列表"),
        "s2" to m("2. 왼쪽·오른쪽 소리 크기", "2. 左右の音量", "2. Left / right levels", "2. Niveaux gauche / droite", "2. Niveles izquierda / derecha", "2. Pegel links / rechts", "2. 左右音量"),
        "left" to m("왼쪽 (송신기 1)", "左 (送信機1)", "Left (transmitter 1)", "Gauche (émetteur 1)", "Izquierda (transmisor 1)", "Links (Sender 1)", "左（发射器 1）"),
        "right" to m("오른쪽 (송신기 2)", "右 (送信機2)", "Right (transmitter 2)", "Droite (émetteur 2)", "Derecha (transmisor 2)", "Rechts (Sender 2)", "右（发射器 2）"),
        "start_meter" to m("소리 측정 시작", "測定開始", "Start measuring", "Mesurer", "Medir", "Messen", "开始测量"),
        "stop_meter" to m("측정 멈추기", "測定停止", "Stop measuring", "Arrêter", "Detener", "Stoppen", "停止测量"),
        "s3" to m("3. 음성 인식 연결 시험", "3. 音声認識の接続テスト", "3. Speech recognition test", "3. Test de reconnaissance vocale", "3. Prueba de reconocimiento de voz", "3. Spracherkennungstest", "3. 语音识别连接测试"),
        "s3_help" to m(
            "앱이 녹음한 소리를 음성 인식 엔진에 넘길 수 있는지 시험해요.\n① '5초 녹음 후 시험'을 누르고 %s로 말하세요.\n② 녹음된 소리 크기를 확인하고, '녹음 다시 듣기'로 직접 들어보세요.\n③ 엔진을 바꿔가며 '같은 녹음으로 시험'을 눌러 어느 엔진이 되는지 찾으세요.",
            "アプリが録音した音を音声認識エンジンに渡せるか試します。\n① 「5秒録音してテスト」を押し、%sで話してください。\n② 録音の音量を確認し、「録音を再生」で聞いてみてください。\n③ エンジンを変えながら「同じ録音でテスト」を押し、使えるエンジンを探します。",
            "Tests whether app-recorded audio can be passed to a speech engine.\n① Tap “Record 5 s and test” and speak in %s.\n② Check the recorded level and listen with “Play recording”.\n③ Switch engines and tap “Test same recording” to find one that works.",
            "Vérifie si l'audio enregistré par l'appli peut être transmis à un moteur vocal.\n① Touchez « Enregistrer 5 s et tester » et parlez en %s.\n② Vérifiez le niveau et écoutez avec « Réécouter ».\n③ Changez de moteur et touchez « Tester le même enregistrement ».",
            "Comprueba si el audio grabado por la app puede pasarse a un motor de voz.\n① Toca «Grabar 5 s y probar» y habla en %s.\n② Revisa el nivel y escúchalo con «Reproducir grabación».\n③ Cambia de motor y toca «Probar la misma grabación».",
            "Prüft, ob von der App aufgenommener Ton an eine Spracherkennung übergeben werden kann.\n① „5 s aufnehmen und testen“ tippen und auf %s sprechen.\n② Pegel prüfen und mit „Aufnahme abspielen“ anhören.\n③ Engine wechseln und „Gleiche Aufnahme testen“ tippen.",
            "测试应用录制的声音能否交给语音识别引擎。\n① 点“录音 5 秒并测试”，用%s说话。\n② 查看录音音量，并用“播放录音”试听。\n③ 切换引擎并点“用同一录音测试”，找出可用的引擎。"
        ),
        "engine_label" to m("음성 인식 엔진", "音声認識エンジン", "Speech engine", "Moteur vocal", "Motor de voz", "Spracherkennung", "语音识别引擎"),
        "rec_test" to m("5초 녹음 후 시험", "5秒録音してテスト", "Record 5 s and test", "Enregistrer 5 s et tester", "Grabar 5 s y probar", "5 s aufnehmen und testen", "录音 5 秒并测试"),
        "replay" to m("녹음 다시 듣기", "録音を再生", "Play recording", "Réécouter", "Reproducir grabación", "Aufnahme abspielen", "播放录音"),
        "retest" to m("같은 녹음으로 시험", "同じ録音でテスト", "Test same recording", "Tester le même enregistrement", "Probar la misma grabación", "Gleiche Aufnahme testen", "用同一录音测试"),
        "t_builtin" to m("내장 마이크", "内蔵マイク", "Built-in mic", "Micro intégré", "Micrófono integrado", "Eingebautes Mikrofon", "内置麦克风"),
        "t_usb" to m("USB 마이크", "USBマイク", "USB mic", "Micro USB", "Micrófono USB", "USB-Mikrofon", "USB 麦克风"),
        "t_usb_headset" to m("USB 헤드셋", "USBヘッドセット", "USB headset", "Casque USB", "Auriculares USB", "USB-Headset", "USB 耳机"),
        "t_usb_acc" to m("USB 액세서리", "USBアクセサリ", "USB accessory", "Accessoire USB", "Accesorio USB", "USB-Zubehör", "USB 配件"),
        "t_wired" to m("유선 이어폰 마이크", "有線イヤホンマイク", "Wired headset mic", "Micro filaire", "Micrófono con cable", "Kabel-Headset", "有线耳机麦克风"),
        "t_bt" to m("블루투스 (통화용 음질)", "Bluetooth (通話品質)", "Bluetooth (call quality)", "Bluetooth (qualité appel)", "Bluetooth (calidad de llamada)", "Bluetooth (Anrufqualität)", "蓝牙（通话音质）"),
        "t_ble" to m("블루투스 LE", "Bluetooth LE", "Bluetooth LE", "Bluetooth LE", "Bluetooth LE", "Bluetooth LE", "蓝牙 LE"),
        "t_other" to m("기타 (%d)", "その他 (%d)", "Other (%d)", "Autre (%d)", "Otro (%d)", "Sonstige (%d)", "其他（%d）"),
        "no_mic" to m("마이크를 찾지 못했어요", "マイクが見つかりません", "No microphone found", "Aucun micro trouvé", "No se encontró micrófono", "Kein Mikrofon gefunden", "未找到麦克风"),
        "unlimited" to m("제한 없음", "制限なし", "any", "tous", "cualquiera", "beliebig", "不限"),
        "info" to m("지원 채널 수: %1\$s\n샘플레이트: %2\$s", "チャンネル数: %1\$s\nサンプルレート: %2\$s", "Channels: %1\$s\nSample rates: %2\$s", "Canaux : %1\$s\nFréquences : %2\$s", "Canales: %1\$s\nFrecuencias: %2\$s", "Kanäle: %1\$s\nAbtastraten: %2\$s", "声道数：%1\$s\n采样率：%2\$s"),
        "stereo_yes" to m("스테레오 입력 가능성 있음 (마이크가 하나여도 2로 나올 수 있어요)", "ステレオ入力の可能性あり (マイク1本でも2と表示されることがあります)", "May support stereo (a single mic can still show 2)", "Stéréo possible (un seul micro peut aussi afficher 2)", "Puede admitir estéreo (un solo micrófono también puede mostrar 2)", "Stereo möglich (auch ein einzelnes Mikro kann 2 anzeigen)", "可能支持立体声（单个麦克风也可能显示 2）"),
        "stereo_no" to m("모노만 지원 · 두 사람 목소리를 나눌 수 없어요", "モノラルのみ · 2人の声を分けられません", "Mono only · can't split two voices", "Mono uniquement · impossible de séparer deux voix", "Solo mono · no puede separar dos voces", "Nur Mono · zwei Stimmen nicht trennbar", "仅单声道 · 无法区分两人的声音"),
        "sys_engine" to m("시스템 기본 음성 인식", "システム標準の音声認識", "System default", "Moteur par défaut", "Predeterminado del sistema", "Systemstandard", "系统默认语音识别"),
        "ondevice" to m("기기 내 음성 인식 (오프라인)", "端末内音声認識 (オフライン)", "On-device (offline)", "Sur l'appareil (hors ligne)", "En el dispositivo (sin conexión)", "Auf dem Gerät (offline)", "设备端语音识别（离线）"),
        "stereo_fail" to m("스테레오로 녹음을 시작하지 못했어요.", "ステレオ録音を開始できませんでした。", "Couldn't start stereo recording.", "Impossible de lancer l'enregistrement stéréo.", "No se pudo iniciar la grabación estéreo.", "Stereo-Aufnahme konnte nicht starten.", "无法开始立体声录音。"),
        "tap" to m("송신기를 하나씩 두드리거나 말해 보세요…", "送信機を1つずつ叩くか話してみてください…", "Tap or speak into each transmitter in turn…", "Tapotez ou parlez dans chaque émetteur à tour de rôle…", "Toca o habla en cada transmisor por turnos…", "Nacheinander auf jeden Sender tippen oder sprechen…", "依次敲击或对着每个发射器说话…"),
        "routed" to m("실제로 소리가 들어오는 마이크: %s", "実際に音が入っているマイク: %s", "Mic actually in use: %s", "Micro réellement utilisé : %s", "Micrófono en uso: %s", "Tatsächlich genutztes Mikro: %s", "实际使用的麦克风：%s"),
        "checking" to m("확인 중", "確認中", "checking", "vérification", "comprobando", "wird geprüft", "检查中"),
        "split_ok" to m("✔ 왼쪽·오른쪽이 따로 들어와요. 두 사람 구분이 가능해요!", "✔ 左右が別々に入っています。2人を区別できます！", "✔ Left and right come in separately. Two speakers can be told apart!", "✔ Gauche et droite arrivent séparément. Deux voix peuvent être distinguées !", "✔ Izquierda y derecha llegan por separado. ¡Se pueden distinguir dos voces!", "✔ Links und rechts kommen getrennt an. Zwei Sprecher sind unterscheidbar!", "✔ 左右分别输入，可以区分两个人！"),
        "split_no" to m(
            "✘ 두 채널 소리가 거의 같아요. 마이크가 하나이거나, 마이크 앱이 모노 모드이거나, 폰이 모노로 합쳐서 받는 거예요.",
            "✘ 2チャンネルの音がほぼ同じです。マイクが1本か、アプリがモノラルか、スマホがモノラルにまとめています。",
            "✘ Both channels sound almost the same. There's one mic, the mic app is in mono, or the phone merges to mono.",
            "✘ Les deux canaux sont presque identiques : un seul micro, appli en mono, ou téléphone qui fusionne en mono.",
            "✘ Los dos canales suenan casi igual: hay un solo micrófono, la app está en mono o el teléfono lo une en mono.",
            "✘ Beide Kanäle klingen fast gleich: nur ein Mikro, App im Mono-Modus oder das Handy mischt zu Mono.",
            "✘ 两个声道几乎相同：只有一个麦克风、麦克风应用为单声道，或手机合并为单声道。"
        ),
        "speak5" to m("🔴 5초 동안 말하세요 (%s)…", "🔴 5秒間話してください (%s)…", "🔴 Speak for 5 seconds (%s)…", "🔴 Parlez pendant 5 secondes (%s)…", "🔴 Habla durante 5 segundos (%s)…", "🔴 5 Sekunden sprechen (%s)…", "🔴 请说 5 秒（%s）…"),
        "rec_fail" to m("녹음을 시작하지 못했어요.", "録音を開始できませんでした。", "Couldn't start recording.", "Impossible de lancer l'enregistrement.", "No se pudo iniciar la grabación.", "Aufnahme konnte nicht starten.", "无法开始录音。"),
        "sample" to m("녹음된 소리: 평균 %1\$.0f dB · 최대 %2\$.0f dB · %3\$.1f초", "録音: 平均 %1\$.0f dB · 最大 %2\$.0f dB · %3\$.1f秒", "Recording: average %1\$.0f dB · peak %2\$.0f dB · %3\$.1f s", "Enregistrement : moyenne %1\$.0f dB · crête %2\$.0f dB · %3\$.1f s", "Grabación: media %1\$.0f dB · pico %2\$.0f dB · %3\$.1f s", "Aufnahme: Mittel %1\$.0f dB · Spitze %2\$.0f dB · %3\$.1f s", "录音：平均 %1\$.0f dB · 峰值 %2\$.0f dB · %3\$.1f 秒"),
        "heard" to m("말소리가 녹음됐어요. 녹음 자체는 문제없어요.", "声が録音されました。録音自体は問題ありません。", "Speech was recorded. The recording itself is fine.", "La voix a été enregistrée. L'enregistrement est correct.", "Se grabó la voz. La grabación está bien.", "Sprache wurde aufgenommen. Die Aufnahme ist in Ordnung.", "已录到说话声，录音本身没有问题。"),
        "silent" to m("거의 무음이에요. 마이크 선택이나 연결을 확인하세요.", "ほぼ無音です。マイクの選択や接続を確認してください。", "Almost silent. Check the mic selection or connection.", "Presque silencieux. Vérifiez le micro choisi ou la connexion.", "Casi en silencio. Revisa el micrófono elegido o la conexión.", "Fast stumm. Mikrofonauswahl oder Verbindung prüfen.", "几乎无声，请检查麦克风选择或连接。"),
        "first" to m("먼저 '5초 녹음 후 시험'을 눌러주세요.", "先に「5秒録音してテスト」を押してください。", "Tap “Record 5 s and test” first.", "Touchez d'abord « Enregistrer 5 s et tester ».", "Primero toca «Grabar 5 s y probar».", "Zuerst „5 s aufnehmen und testen“ tippen.", "请先点“录音 5 秒并测试”。"),
        "e_network" to m("네트워크 오류", "ネットワークエラー", "Network error", "Erreur réseau", "Error de red", "Netzwerkfehler", "网络错误"),
        "e_audio" to m("오디오를 받지 못했어요 (외부 소리 입력을 지원하지 않을 수 있어요)", "音声を受け取れませんでした (外部音声入力に非対応の可能性)", "No audio received (may not support external audio input)", "Aucun audio reçu (entrée audio externe peut-être non prise en charge)", "No se recibió audio (puede que no admita entrada externa)", "Kein Ton empfangen (externe Eingabe evtl. nicht unterstützt)", "未收到音频（可能不支持外部音频输入）"),
        "e_server" to m("서버 오류", "サーバーエラー", "Server error", "Erreur serveur", "Error del servidor", "Serverfehler", "服务器错误"),
        "e_client" to m("요청을 받아주지 않았어요 (외부 소리 입력을 지원하지 않을 수 있어요)", "リクエストが受け付けられませんでした (外部音声入力に非対応の可能性)", "Request rejected (may not support external audio input)", "Requête refusée (entrée audio externe peut-être non prise en charge)", "Solicitud rechazada (puede que no admita entrada externa)", "Anfrage abgelehnt (externe Eingabe evtl. nicht unterstützt)", "请求被拒绝（可能不支持外部音频输入）"),
        "e_timeout" to m("말소리가 들리지 않았어요", "声が聞こえませんでした", "No speech heard", "Aucune voix entendue", "No se oyó voz", "Keine Sprache gehört", "没有听到说话声"),
        "e_nomatch" to m("말소리를 알아듣지 못했어요", "聞き取れませんでした", "Speech not understood", "Voix non comprise", "No se entendió la voz", "Sprache nicht verstanden", "未能识别说话内容"),
        "e_busy" to m("음성 인식이 다른 곳에서 사용 중이에요", "音声認識が他で使用中です", "Speech recognition is busy", "Reconnaissance vocale occupée", "El reconocimiento de voz está ocupado", "Spracherkennung ist belegt", "语音识别正被占用"),
        "e_perm" to m("권한이 없어요", "権限がありません", "No permission", "Autorisation manquante", "Sin permiso", "Keine Berechtigung", "没有权限"),
        "e_lang" to m("이 언어를 지원하지 않아요", "この言語に対応していません", "Language not supported", "Langue non prise en charge", "Idioma no compatible", "Sprache nicht unterstützt", "不支持此语言"),
        "e_pack" to m("언어팩이 아직 없어요", "言語パックがまだありません", "Language pack not downloaded yet", "Pack de langue pas encore téléchargé", "Paquete de idioma aún no descargado", "Sprachpaket noch nicht geladen", "尚未下载语言包"),
        "e_unknown" to m("알 수 없는 오류", "不明なエラー", "Unknown error", "Erreur inconnue", "Error desconocido", "Unbekannter Fehler", "未知错误"),
        "need13" to m("안드로이드 13 이상에서만 시험할 수 있어요.", "Android 13以上でのみテストできます。", "Requires Android 13 or later.", "Nécessite Android 13 ou plus.", "Requiere Android 13 o posterior.", "Erfordert Android 13 oder neuer.", "需要 Android 13 或更高版本。"),
        "recognizing" to m("인식 중… %s", "認識中… %s", "Recognizing… %s", "Reconnaissance… %s", "Reconociendo… %s", "Erkennung… %s", "识别中… %s"),
        "open_fail" to m("✘ 이 엔진을 열지 못했어요: %s", "✘ このエンジンを開けませんでした: %s", "✘ Couldn't open this engine: %s", "✘ Impossible d'ouvrir ce moteur : %s", "✘ No se pudo abrir este motor: %s", "✘ Engine konnte nicht geöffnet werden: %s", "✘ 无法打开此引擎：%s"),
        "ok" to m("✔ %1\$s 연결 성공\n\"%2\$s\"", "✔ %1\$s 接続成功\n\"%2\$s\"", "✔ %1\$s works\n\"%2\$s\"", "✔ %1\$s fonctionne\n\"%2\$s\"", "✔ %1\$s funciona\n\"%2\$s\"", "✔ %1\$s funktioniert\n\"%2\$s\"", "✔ %1\$s 连接成功\n\"%2\$s\""),
        "ok_partial" to m(
            "✔ %1\$s 연결 성공\n\"%2\$s\"\n(최종 결과는 비어 있었지만 중간 결과로 인식이 확인됐어요)",
            "✔ %1\$s 接続成功\n\"%2\$s\"\n(最終結果は空でしたが、途中結果で認識を確認しました)",
            "✔ %1\$s works\n\"%2\$s\"\n(the final result was empty, but partial results confirmed recognition)",
            "✔ %1\$s fonctionne\n\"%2\$s\"\n(résultat final vide, mais les résultats partiels confirment la reconnaissance)",
            "✔ %1\$s funciona\n\"%2\$s\"\n(el resultado final estaba vacío, pero los parciales confirman el reconocimiento)",
            "✔ %1\$s funktioniert\n\"%2\$s\"\n(Endergebnis leer, aber Zwischenergebnisse bestätigen die Erkennung)",
            "✔ %1\$s 连接成功\n\"%2\$s\"\n（最终结果为空，但中间结果确认已识别）"
        ),
        "empty" to m("결과가 비어 있어요. 이 엔진은 앱이 넘긴 소리를 듣지 않는 것 같아요.", "結果が空です。このエンジンはアプリの音声を受け付けないようです。", "Empty result. This engine doesn't seem to accept app audio.", "Résultat vide. Ce moteur ne semble pas accepter l'audio de l'appli.", "Resultado vacío. Este motor no parece aceptar el audio de la app.", "Leeres Ergebnis. Diese Engine nimmt anscheinend keinen App-Ton an.", "结果为空。此引擎似乎不接受应用传入的声音。"),
        "code" to m("%1\$s (코드 %2\$d)", "%1\$s (コード %2\$d)", "%1\$s (code %2\$d)", "%1\$s (code %2\$d)", "%1\$s (código %2\$d)", "%1\$s (Code %2\$d)", "%1\$s（代码 %2\$d）"),
        "start_fail" to m("시작하지 못했어요: %s", "開始できませんでした: %s", "Couldn't start: %s", "Démarrage impossible : %s", "No se pudo iniciar: %s", "Start fehlgeschlagen: %s", "无法启动：%s"),
        "no_response" to m("20초 동안 응답이 없어요. 이 엔진은 앱이 넘긴 소리를 받지 않는 것 같아요.", "20秒間応答がありません。このエンジンはアプリの音声を受け付けないようです。", "No response for 20 seconds. This engine doesn't seem to accept app audio.", "Aucune réponse en 20 secondes. Ce moteur ne semble pas accepter l'audio de l'appli.", "Sin respuesta en 20 segundos. Este motor no parece aceptar el audio de la app.", "20 Sekunden keine Antwort. Diese Engine nimmt anscheinend keinen App-Ton an.", "20 秒无响应。此引擎似乎不接受应用传入的声音。")
    )
}