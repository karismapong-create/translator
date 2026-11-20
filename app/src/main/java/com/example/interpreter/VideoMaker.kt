package com.example.interpreter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Makes a part's video on the phone: the narration of its sentences (with the time of every word),
 * and its pictures with a slow camera move and a fade between pictures, encoded as an MP4 (H.264 +
 * AAC). The subtitles are not burned in: the timing goes alongside as JSON, so the app shows them
 * live on top of the video (long-press a word, vocabulary, seek by word). Runs on a background thread.
 */
object VideoMaker {
    class Cancelled : Exception("cancelled")

    private const val W = 768
    private const val H = 576
    private const val FPS = 15
    private const val LEAD_MS = 300
    private const val GAP_MS = 350

    /** One sentence spoken: its 16-bit mono samples and the words' [charStart, charEnd, sampleOffset]. */
    class Spoken(val pcm: ShortArray, val rate: Int, val words: List<IntArray>)

    /* ---------------- Narration ---------------- */

    fun narrate(ctx: Context, sentences: List<String>, tag: String, dir: File, cancelled: () -> Boolean, progress: (Int) -> Unit): List<Spoken> {
        val ready = CountDownLatch(1)
        var ok = false
        val tts = TextToSpeech(ctx.applicationContext) { status -> ok = status == TextToSpeech.SUCCESS; ready.countDown() }
        try {
            if (!ready.await(15, TimeUnit.SECONDS) || !ok) throw IllegalStateException("voice engine")
            SpeechOut.useVoice(tts, tag)
            tts.setSpeechRate(1.0f)
            tts.setPitch(1.0f)
            val ranges = HashMap<String, MutableList<IntArray>>()
            val done = HashMap<String, CountDownLatch>()
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { done[id]?.countDown() }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) { done[id]?.countDown() }
                override fun onRangeStart(id: String?, start: Int, end: Int, frame: Int) {
                    synchronized(ranges) { ranges.getOrPut(id ?: return) { ArrayList() }.add(intArrayOf(start, end, frame)) }
                }
            })
            dir.mkdirs()
            val out = ArrayList<Spoken>()
            sentences.forEachIndexed { i, text ->
                if (cancelled()) throw Cancelled()
                progress(i)
                val id = "vm_$i"
                val f = File(dir, "s$i.wav")
                f.delete()
                val latch = CountDownLatch(1)
                done[id] = latch
                tts.synthesizeToFile(text, Bundle(), f, id)
                latch.await(60, TimeUnit.SECONDS)
                val wav = readWav(f)
                val words = synchronized(ranges) { ranges[id]?.toList() }.orEmpty()
                out += if (wav != null) Spoken(wav.first, wav.second, words) else Spoken(ShortArray(0), 22050, emptyList())
                f.delete()
            }
            return out
        } finally {
            tts.shutdown()
        }
    }

    // A WAV file's samples as 16-bit mono, and its sample rate.
    private fun readWav(f: File): Pair<ShortArray, Int>? {
        if (!f.exists() || f.length() < 44) return null
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        var rate = 22050
        var channels = 1
        var bits = 16
        var pos = 12
        while (pos + 8 <= b.limit()) {
            val id = String(ByteArray(4) { b.get(pos + it) }, Charsets.US_ASCII)
            val size = b.getInt(pos + 4)
            val body = pos + 8
            if (id == "fmt ") {
                channels = b.getShort(body + 2).toInt().coerceAtLeast(1)
                rate = b.getInt(body + 4)
                bits = b.getShort(body + 14).toInt()
            } else if (id == "data") {
                if (bits != 16) return null
                val end = minOf(b.limit(), body + (if (size <= 0) b.limit() else size))
                val frames = (end - body) / (2 * channels)
                val out = ShortArray(frames)
                for (k in 0 until frames) out[k] = b.getShort(body + k * 2 * channels)
                return out to rate
            }
            pos = body + size + (size and 1)
        }
        return null
    }

    /* ---------------- Rendering ---------------- */

    /**
     * Writes the MP4 to [out] and returns the timing: {"sentences":[{"start":ms,"end":ms,
     * "words":[[charStart,charEnd,ms],...]}]}. [pictures] has one entry per sentence (null: none of
     * its own; the last picture before it continues).
     */
    fun render(spoken: List<Spoken>, pictures: List<File?>, out: File, cancelled: () -> Boolean, progress: (Int) -> Unit): JSONObject {
        val rate = spoken.firstOrNull { it.pcm.isNotEmpty() }?.rate ?: 22050
        // The timeline: a moment of quiet, then each sentence followed by a short pause.
        val timing = JSONArray()
        val starts = LongArray(spoken.size)
        val ends = LongArray(spoken.size)
        var cursor = rate.toLong() * LEAD_MS / 1000
        val pcmParts = ArrayList<ShortArray>()
        pcmParts += ShortArray(cursor.toInt())
        spoken.forEachIndexed { i, s ->
            val pcm = if (s.rate == rate || s.pcm.isEmpty()) s.pcm else resample(s.pcm, s.rate, rate)
            val k = if (s.rate == 0) 1.0 else rate.toDouble() / s.rate
            starts[i] = cursor * 1000 / rate
            val words = JSONArray()
            for (w in s.words) words.put(JSONArray().put(w[0]).put(w[1]).put(starts[i] + (w[2] * k).toLong() * 1000 / rate))
            pcmParts += pcm
            cursor += pcm.size
            ends[i] = cursor * 1000 / rate
            val gap = rate * GAP_MS / 1000
            pcmParts += ShortArray(gap)
            cursor += gap
            timing.put(JSONObject().put("start", starts[i]).put("end", ends[i]).put("words", words))
        }
        val totalMs = cursor * 1000 / rate
        val pcm = ShortArray(cursor.toInt()).also { all ->
            var p = 0
            for (part in pcmParts) { System.arraycopy(part, 0, all, p, part.size); p += part.size }
        }

        val audio = encodeAudio(pcm, rate, cancelled)
        out.delete()
        val mux = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            encodeVideo(pictures, starts, totalMs, mux, audio, cancelled, progress)
        } finally {
            try { mux.stop() } catch (_: Exception) {}
            try { mux.release() } catch (_: Exception) {}
        }
        return JSONObject().put("sentences", timing).put("duration", totalMs)
    }

    private fun resample(src: ShortArray, from: Int, to: Int): ShortArray {
        val n = (src.size.toLong() * to / from).toInt()
        return ShortArray(n) { i ->
            val x = i.toDouble() * from / to
            val a = x.toInt().coerceAtMost(src.size - 1)
            val b = (a + 1).coerceAtMost(src.size - 1)
            val f = x - a
            (src[a] * (1 - f) + src[b] * f).toInt().toShort()
        }
    }

    private class Encoded(val format: MediaFormat, val samples: List<Pair<ByteArray, MediaCodec.BufferInfo>>)

    // The whole narration as AAC, kept in memory (about 1 MB for two minutes).
    private fun encodeAudio(pcm: ShortArray, rate: Int, cancelled: () -> Boolean): Encoded {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 64000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()
        val samples = ArrayList<Pair<ByteArray, MediaCodec.BufferInfo>>()
        var outFormat: MediaFormat? = null
        var fed = 0
        var inputDone = false
        val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                if (cancelled()) throw Cancelled()
                if (!inputDone) {
                    val idx = enc.dequeueInputBuffer(10000)
                    if (idx >= 0) {
                        val buf = enc.getInputBuffer(idx)!!
                        buf.clear()
                        val n = minOf(pcm.size - fed, buf.capacity() / 2, 2048)
                        val pts = fed.toLong() * 1_000_000 / rate
                        if (n <= 0) {
                            enc.queueInputBuffer(idx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val bb = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
                            for (k in 0 until n) bb.putShort(pcm[fed + k])
                            buf.put(bb.array())
                            enc.queueInputBuffer(idx, 0, n * 2, pts, 0)
                            fed += n
                        }
                    }
                }
                val o = enc.dequeueOutputBuffer(info, 10000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outFormat = enc.outputFormat
                    o >= 0 -> {
                        val buf = enc.getOutputBuffer(o)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                            val bytes = ByteArray(info.size)
                            buf.position(info.offset); buf.get(bytes, 0, info.size)
                            samples += bytes to MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) }
                        }
                        enc.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } finally {
            try { enc.stop() } catch (_: Exception) {}
            enc.release()
        }
        return Encoded(outFormat ?: throw IllegalStateException("audio format"), samples)
    }

    private fun encodeVideo(pictures: List<File?>, starts: LongArray, totalMs: Long, mux: MediaMuxer, audio: Encoded,
                            cancelled: () -> Boolean, progress: (Int) -> Unit) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, W, H).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 1_500_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()

        // Each sentence shows its own picture or the last one before it.
        val shown = arrayOfNulls<File>(pictures.size)
        var last: File? = pictures.firstOrNull { it != null }
        for (i in pictures.indices) { pictures[i]?.let { last = it }; shown[i] = last }
        val bitmaps = HashMap<String, Bitmap?>()
        fun bitmap(f: File?): Bitmap? = f?.let { bitmaps.getOrPut(it.path) { decode(it) } }

        val frame = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(frame)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val m = Matrix()
        val argb = IntArray(W * H)
        val frames = (totalMs * FPS / 1000).toInt() + 1
        var vTrack = -1
        var aTrack: Int
        var started = false
        val info = MediaCodec.BufferInfo()
        var k = 0
        var inputDone = false
        try {
            while (true) {
                if (cancelled()) throw Cancelled()
                if (!inputDone) {
                    val idx = enc.dequeueInputBuffer(10000)
                    if (idx >= 0) {
                        if (k >= frames) {
                            enc.queueInputBuffer(idx, 0, 0, k * 1_000_000L / FPS, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val t = k * 1000L / FPS
                            var s = starts.indexOfLast { it <= t }.coerceAtLeast(0)
                            if (starts.isEmpty()) s = 0
                            val segStart = if (starts.isEmpty()) 0L else if (t < starts[0]) 0L else starts[s]
                            val segEnd = if (s + 1 < starts.size) starts[s + 1] else totalMs
                            val p = ((t - segStart).toFloat() / (segEnd - segStart).coerceAtLeast(1)).coerceIn(0f, 1f)
                            canvas.drawColor(Color.rgb(16, 18, 28))
                            val pic = bitmap(shown.getOrNull(s))
                            if (pic != null) {
                                val changed = s == 0 || shown.getOrNull(s - 1) != shown.getOrNull(s)
                                val fadeIn = if (changed) ((t - segStart) / 400f).coerceIn(0f, 1f) else 1f
                                val base = maxOf(W.toFloat() / pic.width, H.toFloat() / pic.height)
                                val zoom = 1.04f + ((if (s % 2 == 0) 1.16f else 1.10f) - 1.04f) * p
                                val dir = when (s % 4) { 0 -> -1f to -1f; 1 -> 1f to -1f; 2 -> 1f to 1f; else -> -1f to 1f }
                                m.reset()
                                m.postTranslate(-pic.width / 2f, -pic.height / 2f)
                                m.postScale(base * zoom, base * zoom)
                                m.postTranslate(W / 2f + dir.first * W * 0.04f * p, H / 2f + dir.second * H * 0.04f * p)
                                paint.alpha = (255 * fadeIn).toInt()
                                canvas.drawBitmap(pic, m, paint)
                            }
                            frame.getPixels(argb, 0, W, 0, 0, W, H)
                            val image = enc.getInputImage(idx)!!
                            fillYuv(image, argb)
                            enc.queueInputBuffer(idx, 0, W * H * 3 / 2, t * 1000, 0)
                            k++
                            if (k % FPS == 0) progress((k * 100 / frames).coerceAtMost(99))
                        }
                    }
                }
                val o = enc.dequeueOutputBuffer(info, 10000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        vTrack = mux.addTrack(enc.outputFormat)
                        aTrack = mux.addTrack(audio.format)
                        mux.start()
                        started = true
                        for ((bytes, ai) in audio.samples) mux.writeSampleData(aTrack, ByteBuffer.wrap(bytes), ai)
                    }
                    o >= 0 -> {
                        val buf = enc.getOutputBuffer(o)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && started) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            mux.writeSampleData(vTrack, buf, info)
                        }
                        enc.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } finally {
            try { enc.stop() } catch (_: Exception) {}
            enc.release()
            bitmaps.values.forEach { it?.recycle() }
            frame.recycle()
        }
        progress(100)
    }

    // ARGB pixels into the encoder's YUV 4:2:0 picture, whatever its plane layout.
    private fun fillYuv(image: android.media.Image, argb: IntArray) {
        val planes = image.planes
        val y = planes[0].buffer; val yRow = planes[0].rowStride; val yPix = planes[0].pixelStride
        val u = planes[1].buffer; val uRow = planes[1].rowStride; val uPix = planes[1].pixelStride
        val v = planes[2].buffer; val vRow = planes[2].rowStride; val vPix = planes[2].pixelStride
        for (row in 0 until H) {
            val o = row * W
            val yo = row * yRow
            for (col in 0 until W) {
                val c = argb[o + col]
                val r = (c shr 16) and 0xff; val g = (c shr 8) and 0xff; val b = c and 0xff
                y.put(yo + col * yPix, (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte())
            }
        }
        for (row in 0 until H / 2) {
            for (col in 0 until W / 2) {
                val c = argb[(row * 2) * W + col * 2]
                val r = (c shr 16) and 0xff; val g = (c shr 8) and 0xff; val b = c and 0xff
                u.put(row * uRow + col * uPix, (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).toByte())
                v.put(row * vRow + col * vPix, (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).toByte())
            }
        }
    }

    private fun decode(f: File): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var sample = 1
        while (o.outWidth / (sample * 2) >= W * 1.3) sample *= 2
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (_: Exception) { null }
}
