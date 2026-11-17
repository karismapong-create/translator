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
        val pad = dp(16)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        box.addView(label("🎙 마이크 진단", 22f, true))
        box.addView(label(
            "2인용 무선 마이크로 '왼쪽 = 나, 오른쪽 = 상대'를 나눌 수 있는지 확인해요.\n" +
            "1) 마이크 앱에서 출력 모드를 스테레오로 바꾸고, 수신기를 폰에 꽂으세요.\n" +
            "2) 아래에서 마이크를 고르고 '소리 측정 시작'을 누르세요.\n" +
            "3) 송신기 1을 두드리면 왼쪽 막대만, 송신기 2는 오른쪽 막대만 움직이면 성공이에요.",
            14f, false, Color.DKGRAY
        ))

        box.addView(section("1. 연결된 마이크"))
        deviceSpinner = Spinner(this)
        box.addView(deviceSpinner)
        deviceInfo = label("", 14f, false, Color.DKGRAY)
        box.addView(deviceInfo)
        box.addView(Button(this).apply {
            text = "목록 새로고침"
            setOnClickListener { loadDevices() }
        })

        box.addView(section("2. 왼쪽·오른쪽 소리 크기"))
        leftText = label("왼쪽 (송신기 1)", 14f, false)
        leftBar = bar()
        rightText = label("오른쪽 (송신기 2)", 14f, false)
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
            text = "소리 측정 시작"
            setOnClickListener { if (metering) stopMeter() else startMeter() }
        }
        box.addView(meterButton)

        box.addView(section("3. 음성 인식 연결 시험"))
        box.addView(label(
            "앱이 녹음한 소리를 음성 인식 엔진에 넘길 수 있는지 시험해요.\n" +
            "① '5초 녹음 후 시험'을 누르고 한국어로 말하세요.\n" +
            "② 녹음된 소리 크기를 확인하고, '녹음 다시 듣기'로 직접 들어보세요.\n" +
            "③ 엔진을 바꿔가며 '같은 녹음으로 시험'을 눌러 어느 엔진이 되는지 찾으세요.",
            14f, false, Color.DKGRAY
        ))
        box.addView(label("음성 인식 엔진", 13f, false, Color.GRAY))
        engineSpinner = Spinner(this)
        box.addView(engineSpinner)
        box.addView(Button(this).apply {
            text = "5초 녹음 후 시험"
            setOnClickListener { recordAndTest() }
        })
        sampleInfo = label("", 14f, false, Color.DKGRAY)
        box.addView(sampleInfo)
        val replayRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(this@MicTestActivity).apply {
                text = "녹음 다시 듣기"
                setOnClickListener { playLast() }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(this@MicTestActivity).apply {
                text = "같은 녹음으로 시험"
                setOnClickListener { retestLast() }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        box.addView(replayRow)
        recogResult = label("", 15f, false)
        recogResult.setTextIsSelectable(true)
        box.addView(recogResult)

        box.addView(Button(this).apply {
            text = "닫기"
            setOnClickListener { finish() }
        })
        setContentView(ScrollView(this).apply { addView(box) })

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
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "내장 마이크"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB 마이크"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB 헤드셋"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB 액세서리"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "유선 이어폰 마이크"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "블루투스 (통화용 음질)"
        26 -> "블루투스 LE"
        else -> "기타 ($t)"
    }

    private fun loadDevices() {
        devices = audio.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { it.type !in hidden }
        val labels = devices.map { d -> typeName(d.type) + " · " + d.productName }
        deviceSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            labels.ifEmpty { listOf("마이크를 찾지 못했어요") }
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
        val channels = d.channelCounts.takeIf { it.isNotEmpty() }?.joinToString("/") ?: "제한 없음"
        val rates = d.sampleRates.takeIf { it.isNotEmpty() }?.joinToString("/") ?: "제한 없음"
        val stereo = d.channelCounts.isEmpty() || d.channelCounts.any { it >= 2 }
        deviceInfo.text = "지원 채널 수: $channels\n샘플레이트: $rates\n" +
            if (stereo) "스테레오 입력 가능성 있음" else "모노만 지원 · 두 사람 목소리를 나눌 수 없어요"
    }

    /* ---------- Speech engines on this phone ---------- */

    private fun loadEngines() {
        val list = ArrayList<EngineChoice>()
        list += EngineChoice("시스템 기본 음성 인식", null, false)
        if (Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            list += EngineChoice("기기 내 음성 인식 (오프라인)", null, true)
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
            verdict.text = "스테레오로 녹음을 시작하지 못했어요."
            return
        }
        selected?.let { rec.setPreferredDevice(it) }
        loudFrames = 0
        splitFrames = 0
        verdict.text = "송신기를 하나씩 두드리거나 말해 보세요…"
        verdict.setTextColor(Color.DKGRAY)
        rec.startRecording()
        metering = true
        meterButton.text = "측정 멈추기"
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
        leftText.text = "왼쪽 (송신기 1)   %.0f dB".format(l)
        rightText.text = "오른쪽 (송신기 2)   %.0f dB".format(r)
        routedText.text = "실제로 소리가 들어오는 마이크: " +
            (routed?.let { typeName(it.type) + " · " + it.productName } ?: "확인 중")
        if (maxOf(l, r) > -40) {
            loudFrames++
            if (abs(l - r) > 8) splitFrames++
        }
        if (loudFrames >= 15) {
            val split = splitFrames.toDouble() / loudFrames > 0.3
            verdict.setTextColor(if (split) green else red)
            verdict.text = if (split) "✔ 왼쪽·오른쪽이 따로 들어와요. 두 사람 구분이 가능해요!"
                           else "✘ 두 채널 소리가 거의 같아요. 마이크 앱에서 스테레오 모드인지 확인하세요. " +
                                "그래도 같으면 폰이 모노로 합쳐서 받는 거예요."
        }
    }

    private fun stopMeter() {
        metering = false
        meterThread?.join(500)
        meterThread = null
        meterButton.text = "소리 측정 시작"
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
        sampleInfo.text = "🔴 5초 동안 한국어로 말하세요…"
        val device = selected
        Thread {
            val pcm = recordMono(5, device)
            runOnUiThread {
                if (pcm == null) {
                    sampleInfo.setTextColor(red)
                    sampleInfo.text = "녹음을 시작하지 못했어요."
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
        sampleInfo.text = "녹음된 소리: 평균 %.0f dB · 최대 %.0f dB · %.1f초\n".format(rmsDb, peakDb, samples / sampleRate.toDouble()) +
            if (heard) "말소리가 녹음됐어요. 녹음 자체는 문제없어요."
            else "거의 무음이에요. 마이크 선택이나 연결을 확인하세요."
    }

    private fun playLast() {
        val pcm = lastPcm ?: run {
            sampleInfo.text = "먼저 '5초 녹음 후 시험'을 눌러주세요."
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
            recogResult.text = "먼저 '5초 녹음 후 시험'을 눌러주세요."
            return
        }
        runTest(pcm)
    }

    private fun errorText(code: Int) = when (code) {
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "네트워크 오류"
        SpeechRecognizer.ERROR_AUDIO -> "오디오를 받지 못했어요 (외부 소리 입력을 지원하지 않을 수 있어요)"
        SpeechRecognizer.ERROR_SERVER -> "서버 오류"
        SpeechRecognizer.ERROR_CLIENT -> "요청을 받아주지 않았어요 (외부 소리 입력을 지원하지 않을 수 있어요)"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "말소리가 들리지 않았어요"
        SpeechRecognizer.ERROR_NO_MATCH -> "말소리를 알아듣지 못했어요"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "음성 인식이 다른 곳에서 사용 중이에요"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "권한이 없어요"
        12 -> "이 언어를 지원하지 않아요"
        13 -> "한국어 언어팩이 아직 없어요"
        else -> "알 수 없는 오류"
    }

    private fun cancelRecognizer() {
        recognizer?.destroy()
        recognizer = null
        pipe?.forEach { try { it.close() } catch (_: Exception) {} }
        pipe = null
    }

    private fun runTest(pcm: ByteArray) {
        if (Build.VERSION.SDK_INT < 33) {
            recogResult.text = "안드로이드 13 이상에서만 시험할 수 있어요."
            return
        }
        val choice = engines.getOrNull(engineSpinner.selectedItemPosition) ?: return
        cancelRecognizer()
        val run = ++testRun
        recogResult.setTextColor(Color.BLACK)
        recogResult.text = "인식 중… (${choice.label})"
        val r = try {
            when {
                choice.onDevice -> SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
                choice.component != null -> SpeechRecognizer.createSpeechRecognizer(this, choice.component)
                else -> SpeechRecognizer.createSpeechRecognizer(this)
            }
        } catch (e: Exception) {
            recogResult.setTextColor(red)
            recogResult.text = "✘ 이 엔진을 열지 못했어요: ${e.message}"
            return
        }
        recognizer = r
        val p = ParcelFileDescriptor.createPipe()
        pipe = p
        var finished = false
        fun done(color: Int, msg: String) {
            if (run != testRun || finished) return
            finished = true
            recogResult.setTextColor(color)
            recogResult.text = msg
        }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(b: Bundle?) {
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (run == testRun && !finished && !t.isNullOrBlank()) recogResult.text = "인식 중… \"$t\""
            }
            override fun onResults(b: Bundle?) {
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (t.isNullOrBlank()) {
                    done(red, "✘ ${choice.label}\n결과가 비어 있어요. 이 엔진은 앱이 넘긴 소리를 듣지 않는 것 같아요.")
                } else {
                    done(green, "✔ ${choice.label} 연결 성공\n\"$t\"")
                }
            }
            override fun onError(error: Int) {
                done(red, "✘ ${choice.label}\n${errorText(error)} (코드 $error)")
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
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, p[0])
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, sampleRate)
        }
        try {
            r.startListening(intent)
        } catch (e: Exception) {
            done(red, "✘ ${choice.label}\n시작하지 못했어요: ${e.message}")
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
        recogResult.postDelayed({
            done(red, "✘ ${choice.label}\n20초 동안 응답이 없어요. 이 엔진은 앱이 넘긴 소리를 받지 않는 것 같아요.")
        }, 20000)
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