package top.rootu.dddplayer.ui.controller

import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale

/**
 * Контроллер таймеров плеера на Coroutines.
 */
class PlayerTimerController(
    private val scope: CoroutineScope,
    private val onHideControls: () -> Unit,
    private val onHideSettings: () -> Unit,
    private val clockViews: List<TextView>
) {
    private var controlsTimerJob: Job? = null
    private var settingsTimerJob: Job? = null
    private var clockJob: Job? = null

    private var lastTimeStr: String = ""

    // Текст буфера, который будет добавлен к часам (например "95%")
    var bufferText: String = ""
        set(value) {
            field = value
            updateClockViews()
        }

    fun startClock() {
        clockJob?.cancel()
        clockJob = scope.launch {
            while (isActive) {
                val calendar = Calendar.getInstance()
                lastTimeStr = String.format(
                    Locale.getDefault(), "%02d:%02d",
                    calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE)
                )
                updateClockViews()
                delay(10000) // Обновляем каждые 10 секунд
            }
        }
    }

    private fun updateClockViews() {
        if (lastTimeStr.isEmpty()) return

        val finalContent = if (bufferText.isNotEmpty()) {
            "$bufferText • $lastTimeStr"
        } else {
            lastTimeStr
        }

        // Обновляем все подписанные вьюхи
        clockViews.forEach { it.text = finalContent }
    }

    fun resetControlsTimer() {
        controlsTimerJob?.cancel()
        controlsTimerJob = scope.launch {
            delay(5000) // 5 sec auto-hide
            onHideControls()
        }
    }

    fun stopControlsTimer() {
        controlsTimerJob?.cancel()
    }

    fun resetSettingsTimer() {
        settingsTimerJob?.cancel()
        settingsTimerJob = scope.launch {
            delay(60000) // 60 sec auto-hide
            onHideSettings()
        }
    }

    fun stopSettingsTimer() {
        settingsTimerJob?.cancel()
    }

    fun cleanup() {
        controlsTimerJob?.cancel()
        settingsTimerJob?.cancel()
        clockJob?.cancel()
    }
}