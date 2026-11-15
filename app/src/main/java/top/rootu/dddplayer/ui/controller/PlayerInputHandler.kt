package top.rootu.dddplayer.ui.controller

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import androidx.core.view.isVisible
import top.rootu.dddplayer.R
import top.rootu.dddplayer.data.SettingsRepository
import top.rootu.dddplayer.logic.SeekStepLogic
import top.rootu.dddplayer.viewmodel.PlayerViewModel
import top.rootu.dddplayer.viewmodel.SettingsViewModel

class PlayerInputHandler(
    private val viewModel: PlayerViewModel,
    private val settingsViewModel: SettingsViewModel,
    private val ui: PlayerUiController,
    private val repo: SettingsRepository,
    private val onShowMainMenu: () -> Unit,
    private val onShowControls: () -> Unit,
    private val onHideControls: () -> Unit,
    private val onResetHideTimer: () -> Unit,
    private val onShowPlaylist: () -> Unit
) {

    // Автоповтор нажатия: последняя стрелка и время её события (для пультов без repeatCount)
    private var lastSeekKeyCode = 0
    private var lastSeekEventTime = 0L
    private var holdRepeat = 0

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
        if (settingsViewModel.isSettingsPanelVisible.value == true) {
            val available = viewModel.availableSettings.value ?: emptyList()
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> settingsViewModel.onMenuUp(available)
                KeyEvent.KEYCODE_DPAD_DOWN -> settingsViewModel.onMenuDown(available)
                KeyEvent.KEYCODE_DPAD_LEFT -> viewModel.changeSettingValue(settingsViewModel.currentSettingType.value!!, -1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> viewModel.changeSettingValue(settingsViewModel.currentSettingType.value!!, 1)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    settingsViewModel.closePanel()
                    viewModel.saveCurrentSettings()
                }

                KeyEvent.KEYCODE_BACK -> {
                    settingsViewModel.closePanel()
                    viewModel.restoreSettings()
                }
            }
            return true
        }

        // 2. Навигация в плеере
        if (ui.controlsView.isVisible) onResetHideTimer()

        // Эфирный режим: OK - плейлист, Влево/Вправо - каналы, Вверх - инфопанель
        val isLiveMode = viewModel.isOverallLiveMode()
        val isOkKey = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER
        // OK во время выбора канала - переключаем сразу
        if (isOkKey && viewModel.confirmZap()) return true
        if (event.keyCode != KeyEvent.KEYCODE_DPAD_LEFT && event.keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) {
            viewModel.stopZap()
        }

        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (!ui.controlsView.isVisible && isLiveMode) {
                    onShowPlaylist()
                    return true
                }
                if (!ui.controlsView.isVisible) {
                    when (repo.getOkButtonAction()) {
                        SettingsRepository.OK_ACTION_PAUSE -> viewModel.togglePlayPause()
                        SettingsRepository.OK_ACTION_PANEL -> onShowControls()
                        else -> { // OK_ACTION_PAUSE_PANEL (по умолчанию)
                            viewModel.togglePlayPause()
                            onShowControls()
                        }
                    }
                    return true
                }
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (!ui.controlsView.isVisible) {
                    onShowControls()
                    return true
                }
                // Если фокус на кнопках управления, переходим в плейлист
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
                    val action = if (isLiveMode) SettingsRepository.UP_ACTION_INFO else repo.getUpButtonAction()
                    when (action) {
                        SettingsRepository.UP_ACTION_OSD -> {
                            viewModel.prepareSettingsPanel()
                            settingsViewModel.openPanel(viewModel.availableSettings.value ?: emptyList())
                        }
                        SettingsRepository.UP_ACTION_MENU -> onShowMainMenu() // Боковое меню
                        SettingsRepository.UP_ACTION_INFO -> {
                            if (ui.topInfoPanel.isVisible) {
                                // Если панель уже есть, то показываем боковое меню
                                onShowMainMenu()
                            } else {
                                // Показать только инфопанель
                                ui.showInfoPanelOnly()
                            }
                        }
                    }
                    return true
                }
            }

            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (!ui.controlsView.isVisible && isLiveMode) {
                    viewModel.zapChannel(if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1)
                    return true
                }
                if (!ui.controlsView.isVisible) {
                    // Пульт: сразу перематываем, HUD не показываем.
                    // Если длительность неизвестна (поток), перемотки нет — показываем панель.
                    if (!handleSeekBarSeek(event)) onShowControls()
                    return true
                }

                if (currentFocus?.id == R.id.seek_bar) {
                    handleSeekBarSeek(event)
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

    private fun handleSeekBarSeek(event: KeyEvent): Boolean {
        val duration = viewModel.duration.value ?: 0L
        if (duration <= 0) return false

        val delayMillis = 500L // Серия нажатий копится до этой паузы, затем один seek

        performSeekHandler.removeCallbacks(performSeekRunnable)

        val gap = event.eventTime - lastSeekEventTime
        // Некоторые пульты (в т.ч. Box R) присылают автоповтор как обычное нажатие
        // с repeatCount = 0, поэтому удержание определяем еще и по интервалу между событиями
        val isHold = event.repeatCount > 0 ||
            (event.keyCode == lastSeekKeyCode && gap in 0..HOLD_GAP_MS)
        lastSeekKeyCode = event.keyCode
        lastSeekEventTime = event.eventTime
        holdRepeat = if (isHold) holdRepeat + 1 else 0

        // На удержании держим окно шире интервала автоповтора (~500 мс),
        // иначе первый повтор гоняется с отложенным seek и шаг теряется
        val seekDelay = if (isHold) 700L else delayMillis

        val step = SeekStepLogic.step(duration, holdRepeat, repo.getSeekStepMs())

        // Определяем текущую базу для перемотки.
        // Если мы уже мотаем (pendingSeekPosition != -1), то мотаем оттуда.
        // Иначе берем максимум из сикбара и позиции плеера: после seek позиция плеера
        // обновляется асинхронно и отстает, а сикбар уже стоит на цели.
        val startPos = if (pendingSeekPosition != -1L) pendingSeekPosition else maxOf(
            ui.seekBar.progress.toLong(),
            viewModel.player?.currentPosition ?: 0L
        )

        val target = if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT)
            (startPos - step).coerceAtLeast(0)
        else
            (startPos + step).coerceAtMost(duration)

        // Обновляем состояние
        pendingSeekPosition = target

        // Считаем общую дельту от реальной позиции плеера (для оверлея)
        val realPos = viewModel.player?.currentPosition ?: 0L
        pendingSeekDelta = target - realPos

        val speed = viewModel.playbackSpeed.value ?: 1.0f
        val isLive = viewModel.isLive.value ?: false
        val liveOffset = if (isLive) (duration - target).coerceAtLeast(0)  else 0L

        // Обновляем UI (но не плеер!)
        viewModel.isUserInteracting = true
        ui.seekBar.progress = target.toInt()
        ui.updateTimeLabels(target, duration, speed, isLive, liveOffset)
        ui.showSeekOverlay(pendingSeekDelta, target, duration, isLive, liveOffset)

        // Планируем выполнение seek через seekDelay
        // Если пользователь нажмет еще раз до этого времени, таймер сбросится
        performSeekHandler.postDelayed(performSeekRunnable, seekDelay)
        return true
    }

    fun cleanup() {
        performSeekHandler.removeCallbacksAndMessages(null)
    }

    private companion object {
        // Интервал между событиями короче этого — автоповтор, а не отдельное нажатие
        const val HOLD_GAP_MS = 120L
    }
}