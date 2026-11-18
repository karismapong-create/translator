package com.example.interpreter

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import java.io.File

// Plays a speech file on a chosen output: the phone speaker for the other person, the earbuds
// for the user. Android decides in the end; on phones that refuse, it plays on the usual output.
object AudioRoute {

    private val earbudTypes = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        26 // TYPE_BLE_HEADSET (Android 12+)
    )

    private fun outputs(ctx: Context): Array<AudioDeviceInfo> =
        (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).getDevices(AudioManager.GET_DEVICES_OUTPUTS)

    fun speaker(ctx: Context): AudioDeviceInfo? =
        outputs(ctx).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }

    fun earbuds(ctx: Context): AudioDeviceInfo? =
        outputs(ctx).firstOrNull { it.type in earbudTypes }

    private var player: MediaPlayer? = null

    fun play(file: File, device: AudioDeviceInfo?, done: () -> Unit) {
        stop()
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(file.absolutePath)
            if (device != null && Build.VERSION.SDK_INT >= 28) mp.setPreferredDevice(device)
            mp.setOnCompletionListener {
                it.release()
                if (player === it) player = null
                file.delete()
                done()
            }
            mp.prepare()
            mp.start()
        } catch (e: Exception) {
            mp.release()
            if (player === mp) player = null
            done()
        }
    }

    fun stop() {
        player?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        player = null
    }
}
