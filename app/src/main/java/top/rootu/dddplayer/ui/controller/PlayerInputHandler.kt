package top.rootu.dddplayer.ui.controller

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
                    return false // Возвращаем false, чтобы система обработала фокус
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
        if (duration > 0) {
            viewModel.isUserInteracting = true

            val step = duration / 200
            val current = ui.seekBar.progress.toLong()
            val target = if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT)
                (current - step).coerceAtLeast(0)
            else
                (current + step).coerceAtMost(duration)

            ui.seekBar.progress = target.toInt()
            ui.timeCurrentTextView.text = ui.formatTime(target)
            viewModel.seekTo(target)

            // Сброс флага взаимодействия происходит во фрагменте через Handler,
            // здесь мы просто обновляем UI
        }
    }
}