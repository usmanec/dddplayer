package top.rootu.dddplayer.ui.controller

import android.os.Handler
import android.os.Looper
import android.widget.TextView
import java.util.Calendar
import java.util.Locale

class PlayerTimerController(
    private val onHideControls: () -> Unit,
    private val onHideSettings: () -> Unit,
    private val clockView: TextView
) {
    private val handler = Handler(Looper.getMainLooper())

    private val hideControlsRunnable = Runnable { onHideControls() }
    private val hideSettingsRunnable = Runnable { onHideSettings() }

    private val clockRunnable = object : Runnable {
        override fun run() {
            val calendar = Calendar.getInstance()
            clockView.text = String.format(Locale.getDefault(), "%02d:%02d",
                calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE))
            handler.postDelayed(this, 10000) // 10 sec
        }
    }

    fun startClock() {
        clockRunnable.run()
    }

    fun resetControlsTimer() {
        handler.removeCallbacks(hideControlsRunnable)
        handler.postDelayed(hideControlsRunnable, 5000) // 5 sec auto-hide
    }

    fun stopControlsTimer() {
        handler.removeCallbacks(hideControlsRunnable)
    }

    fun resetSettingsTimer() {
        handler.removeCallbacks(hideSettingsRunnable)
        handler.postDelayed(hideSettingsRunnable, 60000) // 60 sec
    }

    fun stopSettingsTimer() {
        handler.removeCallbacks(hideSettingsRunnable)
    }

    fun cleanup() {
        handler.removeCallbacksAndMessages(null)
    }
}