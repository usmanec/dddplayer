package top.rootu.dddplayer.player

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Аудиорежим телефона и аудиоустройства во время просмотра.
 *
 * Звонок, мессенджер или голосовой ассистент переводят Bluetooth-наушники в гарнитурный режим (HFP/SCO: моно,
 * звук "как через далекий микрофон" или тишина). Часть телефонов после этого не возвращает уже открытый
 * AudioTrack на A2DP, и помогало только переключить дорожку. Когда аудиорежим возвращается из звонка или связи
 * в обычный, вызывается [onVoiceModeEnded]: плеер пересоздает аудиовыход сам. Смены режима и устройств
 * пишутся в лог для диагностики.
 */
internal class AudioRouteWatcher(context: Context, private val onVoiceModeEnded: () -> Unit) {

    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private var lastMode = AudioManager.MODE_NORMAL
    private var running = false

    // Слушателя режима до Android 12 нет, поэтому опрашиваем: getMode() дешевый и без разрешений
    private val poll = object : Runnable {
        override fun run() {
            checkMode()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = logDevices("added", addedDevices)

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = logDevices("removed", removedDevices)
    }

    fun start() {
        if (running) return
        running = true
        lastMode = audioManager.mode
        handler.postDelayed(poll, POLL_INTERVAL_MS)
        runCatching { audioManager.registerAudioDeviceCallback(deviceCallback, handler) }
    }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(poll)
        runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
    }

    private fun checkMode() {
        val mode = audioManager.mode
        if (mode == lastMode) return
        Log.i(TAG, "Audio mode ${modeName(lastMode)} -> ${modeName(mode)}")
        val wasVoice = isVoiceMode(lastMode)
        lastMode = mode
        if (mode == AudioManager.MODE_NORMAL && wasVoice) onVoiceModeEnded()
    }

    private fun logDevices(action: String, devices: Array<out AudioDeviceInfo>) {
        val outputs = devices.filter { it.isSink }
        if (outputs.isEmpty()) return
        Log.i(TAG, "Output devices $action: " + outputs.joinToString { "${typeName(it.type)}(${it.productName})" })
    }

    private companion object {
        const val TAG = "AudioRoute"
        const val POLL_INTERVAL_MS = 2_000L

        // Звонок, VoIP и их варианты (MODE_CALL_SCREENING и т.п. есть только в новых API, сравниваем числами)
        fun isVoiceMode(mode: Int) = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION ||
            mode == 4 || mode == 5 || mode == 6

        fun modeName(mode: Int) = when (mode) {
            AudioManager.MODE_NORMAL -> "NORMAL"
            AudioManager.MODE_RINGTONE -> "RINGTONE"
            AudioManager.MODE_IN_CALL -> "IN_CALL"
            AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
            4 -> "CALL_SCREENING"
            5 -> "CALL_REDIRECT"
            6 -> "COMMUNICATION_REDIRECT"
            else -> mode.toString()
        }

        fun typeName(type: Int) = when (type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BT_A2DP"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BT_SCO"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "SPEAKER"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "EARPIECE"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
            AudioDeviceInfo.TYPE_HDMI -> "HDMI"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB"
            26 -> "BLE_HEADSET" // AudioDeviceInfo.TYPE_BLE_HEADSET (API 31)
            else -> "type$type"
        }
    }
}
