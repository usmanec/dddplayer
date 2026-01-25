package top.rootu.dddplayer.ui.controller

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import androidx.core.view.isVisible
import top.rootu.dddplayer.R
import top.rootu.dddplayer.viewmodel.PlayerViewModel

class PlayerInputHandler(
    private val viewModel: PlayerViewModel,
    private val ui: PlayerUiController,
    private val onShowControls: () -> Unit,
    private val onHideControls: () -> Unit,
    private val onResetHideTimer: () -> Unit,
    private val onShowPlaylist: () -> Unit
) {

    private var seekAccelerationCount = 0
    private var lastSeekTime = 0L
    private var lastKeyCode = 0
    private val seekResetHandler = Handler(Looper.getMainLooper())
    private val seekResetRunnable = Runnable {
        seekAccelerationCount = 0
        lastKeyCode = 0
    }

    private var pendingSeekPosition: Long = -1L
    private var pendingSeekDelta: Long = 0L
    private val performSeekHandler = Handler(Looper.getMainLooper())
    private val performSeekRunnable = Runnable {
        if (pendingSeekPosition != -1L) {
            viewModel.seekTo(pendingSeekPosition)
            ui.hideSeekOverlay()

            // Сбрасываем состояние
            pendingSeekPosition = -1L
            pendingSeekDelta = 0L

            performSeekHandler.removeCallbacksAndMessages(null) // Чистим старые отложенные сбросы

            // Через небольшую задержку разрешаем обновление UI от плеера
            performSeekHandler.postDelayed({ viewModel.isUserInteracting = false }, 500)
        }
    }
    fun handleKeyEvent(event: KeyEvent, currentFocus: View?): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false

        // 1. Навигация в настройках
        if (viewModel.isSettingsPanelVisible.value == true) {
            // Сброс таймера скрытия настроек должен быть во фрагменте,
            // но здесь мы возвращаем true, что сигнал обработан
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> viewModel.onMenuUp()
                KeyEvent.KEYCODE_DPAD_DOWN -> viewModel.onMenuDown()
                KeyEvent.KEYCODE_DPAD_LEFT -> viewModel.onMenuLeft()
                KeyEvent.KEYCODE_DPAD_RIGHT -> viewModel.onMenuRight()
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    viewModel.closeSettingsPanel(save = true)
                }

                KeyEvent.KEYCODE_BACK -> {
                    viewModel.closeSettingsPanel(save = false)
                }
            }
            return true
        }

        // 2. Навигация в плеере
        if (ui.controlsView.isVisible) onResetHideTimer()

        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (!ui.controlsView.isVisible) {
                    onShowControls()
                    return true
                }
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (!ui.controlsView.isVisible) {
                    onShowControls()
                    return true
                }
                if (currentFocus?.id in listOf(
                        R.id.button_play_pause, R.id.button_rewind,
                        R.id.button_ffwd, R.id.button_prev, R.id.button_next
                    )
                ) {
                    onShowPlaylist()
                    return true
                }
            }

            KeyEvent.KEYCODE_DPAD_UP -> {
                if (ui.controlsView.isVisible && currentFocus?.id == R.id.seek_bar) {
                    onHideControls()
                    return true
                } else if (!ui.controlsView.isVisible) {
                    viewModel.openSettingsPanel()
                    return true
                }
            }

            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (!ui.controlsView.isVisible) {
                    // Показываем контролы и фокусируемся на сикбаре
                    ui.showControls(focusOnSeekBar = true)
                    onResetHideTimer()
                    // Поглощаем событие, чтобы не было прыжка при открытии панели
                    return true
                }

                if (currentFocus?.id == R.id.seek_bar) {
                    handleSeekBarSeek(event.keyCode)
                    onResetHideTimer()
                    return true
                }
                return false
            }

            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                viewModel.togglePlayPause()
                onShowControls()
                return true
            }

            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                viewModel.nextTrack()
                onShowControls()
                return true
            }

            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                viewModel.prevTrack()
                onShowControls()
                return true
            }
        }
        return false
    }

    private fun handleSeekBarSeek(keyCode: Int) {
        val duration = viewModel.duration.value ?: 0L
        if (duration <= 0) return

        val delayMillis = 500L // Считаем серию нажатий в пределах этого времени

        performSeekHandler.removeCallbacks(performSeekRunnable)

        val currentTime = System.currentTimeMillis()

        if (lastKeyCode == keyCode && currentTime - lastSeekTime < delayMillis) {
            seekAccelerationCount++
        } else {
            seekAccelerationCount = 0
        }
        lastSeekTime = currentTime
        lastKeyCode = keyCode

        // Сброс счетчика при бездействии
        seekResetHandler.removeCallbacks(seekResetRunnable)
        seekResetHandler.postDelayed(seekResetRunnable, delayMillis)

        // Базовый шаг: очень маленький, зависит от длины видео
        // Для 2-часового фильма: (7200000 / 200 / 8) = 4500 мс (4.5 сек)
        // Для 5-минутного ролика: (300000 / 200 / 8) = 187 мс -> coerce -> 250 мс
        val baseStep = (duration / 200 / 8).coerceIn(250, 10000)

        // Множитель ускорения
        val multiplier = when {
            seekAccelerationCount < 5 -> 1
            seekAccelerationCount < 15 -> 2
            seekAccelerationCount < 30 -> 4
            seekAccelerationCount < 50 -> 8
            else -> 16
        }

        // Итоговый шаг (Минимум 0.5 сек, максимум 60 сек)
        val step = (baseStep * multiplier).coerceIn(500, 60000)

        // Определяем текущую базу для перемотки
        // Если мы уже мотаем (pendingSeekPosition != -1), то мотаем оттуда.
        // Если это первое нажатие, мотаем от текущей позиции плеера.
        val startPos = if (pendingSeekPosition != -1L) pendingSeekPosition else ui.seekBar.progress.toLong()

        val target = if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT)
            (startPos - step).coerceAtLeast(0)
        else
            (startPos + step).coerceAtMost(duration)

        // Обновляем состояние
        pendingSeekPosition = target

        // Считаем общую дельту от реальной позиции плеера (для оверлея)
        val realPos = viewModel.player!!.currentPosition
        pendingSeekDelta = target - realPos

        // Обновляем UI (но не плеер!)
        viewModel.isUserInteracting = true
        ui.seekBar.progress = target.toInt()
        ui.updateTimeLabels(target, duration)
        ui.showSeekOverlay(pendingSeekDelta, target)

        // Планируем выполнение seek через 500мс (как сброс ускорения)
        // Если пользователь нажмет еще раз до этого времени, таймер сбросится
        performSeekHandler.postDelayed(performSeekRunnable, delayMillis)

        // Сброс флага взаимодействия происходит во фрагменте через Handler,
        // здесь мы просто обновляем UI
    }

    fun cleanup() {
        seekResetHandler.removeCallbacksAndMessages(null)
        performSeekHandler.removeCallbacksAndMessages(null)
    }
}