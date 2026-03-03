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
 * @param scope CoroutineScope, привязанный к жизненному циклу (например, viewLifecycleOwner.lifecycleScope)
 */
class PlayerTimerController(
    private val scope: CoroutineScope,
    private val onHideControls: () -> Unit,
    private val onHideSettings: () -> Unit,
    private val clockView: TextView
) {
    private var controlsTimerJob: Job? = null
    private var settingsTimerJob: Job? = null
    private var clockJob: Job? = null

    fun startClock() {
        clockJob?.cancel()
        clockJob = scope.launch {
            while (isActive) {
                val calendar = Calendar.getInstance()
                clockView.text = String.format(
                    Locale.getDefault(), "%02d:%02d",
                    calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE)
                )
                delay(10000) // Обновляем каждые 10 секунд
            }
        }
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