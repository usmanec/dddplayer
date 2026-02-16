package top.rootu.dddplayer.ui

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.recyclerview.widget.RecyclerView
import top.rootu.dddplayer.BuildConfig
import top.rootu.dddplayer.R
import top.rootu.dddplayer.data.SettingsRepository
import top.rootu.dddplayer.logic.AnaglyphLogic
import top.rootu.dddplayer.logic.TrackLogic
import top.rootu.dddplayer.model.MenuItem
import top.rootu.dddplayer.model.PlaybackSpeed
import top.rootu.dddplayer.model.ResizeMode
import top.rootu.dddplayer.model.StereoInputType
import top.rootu.dddplayer.model.StereoOutputMode
import top.rootu.dddplayer.renderer.OnFpsUpdatedListener
import top.rootu.dddplayer.renderer.OnSurfaceReadyListener
import top.rootu.dddplayer.renderer.StereoRenderer
import top.rootu.dddplayer.ui.adapter.SideMenuAdapter
import top.rootu.dddplayer.ui.controller.PlayerInputHandler
import top.rootu.dddplayer.ui.controller.PlayerTimerController
import top.rootu.dddplayer.ui.controller.PlayerTouchHandler
import top.rootu.dddplayer.ui.controller.PlayerUiController
import top.rootu.dddplayer.viewmodel.PlayerViewModel
import top.rootu.dddplayer.viewmodel.SettingType
import top.rootu.dddplayer.viewmodel.SettingsViewModel
import top.rootu.dddplayer.viewmodel.UpdateViewModel
import kotlin.math.abs

class PlayerFragment : Fragment(), OnSurfaceReadyListener, OnFpsUpdatedListener {

    private var zoomDialog: Dialog? = null
    private val viewModel: PlayerViewModel by activityViewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()
    private val updateViewModel: UpdateViewModel by viewModels()

    private var stereoRenderer: StereoRenderer? = null
    private var sideMenuDialog: Dialog? = null
    private var sideMenuAdapter: SideMenuAdapter? = null

    // Контроллеры
    private lateinit var ui: PlayerUiController
    private lateinit var inputHandler: PlayerInputHandler
    private lateinit var timerController: PlayerTimerController

    // Жесты
    private lateinit var gestureDetector: GestureDetector
    private lateinit var touchHandler: PlayerTouchHandler

    // Накопление перемотки по двойному тапу
    private var doubleTapSeekSeconds = 0
    private val doubleTapResetHandler = Handler(Looper.getMainLooper())
    private val performDoubleTapSeekRunnable = Runnable {
        if (doubleTapSeekSeconds != 0) {
            val current = viewModel.currentPosition.value ?: 0L
            val target = current + (doubleTapSeekSeconds * 1000L)
            viewModel.seekTo(target.coerceAtLeast(0))
            doubleTapSeekSeconds = 0
        }
    }

    // Накопитель для плавного изменения громкости
    private var volumeAccumulator = 0f

    // Переменные для горизонтального свайпа
    private var swipeAction = 0 // 0=None, 1=Seek, 2=Playlist
    private var isSwipeSeeking = false
    private var swipeSeekStartPosition = 0L
    private var swipeSeekCurrentPosition = 0L

    // Состояние для плейлиста
    private var swipePlaylistAccumulator = 0f
    private var isPlaylistSwipeActive = false

    // Храним, что именно мы сейчас тянем (для finish)
    private var currentSwipeTargetIsNext: Boolean? = null
    private var currentSwipeIsExit: Boolean = false

    // Храним ссылку на GL Surface
    private var glSurface: Surface? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.player_fragment, container, false)

        ui = PlayerUiController(view)

        timerController = PlayerTimerController(
            onHideControls = { ui.hideControls() },
            onHideSettings = {
                if (settingsViewModel.isSettingsPanelVisible.value == true) {
                    settingsViewModel.closePanel()
                    viewModel.restoreSettings()
                    // Фокус на корневой контейнер после закрытия OSD
                    view.findViewById<View>(R.id.root_container)?.requestFocus()
                }
            },
            clockView = ui.textClock
        )

        // Используем Application Context для репозитория, чтобы избежать утечек
        val settingsRepo = SettingsRepository(requireContext().applicationContext)

        inputHandler = PlayerInputHandler(
            viewModel, settingsViewModel, ui,
            settingsRepo,
            onShowMainMenu = { showMainMenu() },
            onShowControls = { ui.showControls(); timerController.resetControlsTimer() },
            onHideControls = { ui.hideControls() },
            onResetHideTimer = { timerController.resetControlsTimer() },
            onShowPlaylist = { showPlaylist() }
        )

        swipeAction = settingsRepo.getHorizontalSwipeAction()

        // === ИНИЦИАЛИЗАЦИЯ ЖЕСТОВ ===
        val displayMetrics = resources.displayMetrics

        touchHandler = PlayerTouchHandler(
            context = requireContext(),
            screenWidth = displayMetrics.widthPixels,
            onSingleTap = {
                // Логика одиночного тапа (показать/скрыть контролы)
                if (settingsViewModel.isSettingsPanelVisible.value == true) {
                    settingsViewModel.closePanel()
                    viewModel.saveCurrentSettings()
                } else if (ui.controlsView.isVisible) {
                    ui.hideControls()
                } else {
                    ui.showControls()
                    timerController.resetControlsTimer()
                }
            },
            onDoubleTapSeek = { forward ->
                handleDoubleTapSeek(forward)
            },
            onVolumeChange = { deltaPercent ->
                changeVolume(deltaPercent)
            },
            onBrightnessChange = { deltaPercent ->
                changeBrightness(deltaPercent)
            },
            onHorizontalScroll = { deltaX ->
                handleHorizontalSwipe(deltaX, displayMetrics.widthPixels)
            },
            onGestureEnd = {
                ui.hideGestureIndicatorDelayed()
                volumeAccumulator = 0f

                // Завершение горизонтального свайпа
                finishHorizontalSwipe()
            }
        )

        gestureDetector = GestureDetector(requireContext(), touchHandler)

        // Вешаем слушатель на корневой контейнер
        view.findViewById<View>(R.id.root_container).setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                touchHandler.onActionUp()
            }
            gestureDetector.onTouchEvent(event)
            true // Поглощаем событие
        }

        // Настройка GL Surface
        ui.glSurfaceView.setEGLContextClientVersion(2)
        ui.glSurfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)

        stereoRenderer = StereoRenderer(ui.glSurfaceView, this, this)
        ui.glSurfaceView.setRenderer(stereoRenderer)
        ui.glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.requestFocus()
        setupControls()
        observeViewModel()
        setupBackPressedHandler()
        timerController.startClock()

        // Подписываемся на событие пересоздания плеера
        viewModel.playerRecreatedEvent.observe(viewLifecycleOwner) { newPlayer ->
            // Плеер обновился. Нужно привязать Surface.
            attachSurfaceToPlayer(newPlayer)
        }
    }

    private fun attachSurfaceToPlayer(player: Player) {
        // Проверяем текущий режим, чтобы привязать нужную поверхность
        if (viewModel.inputType.value != StereoInputType.NONE) {
            // 3D режим -> GL Surface
            if (glSurface != null) {
                player.setVideoSurface(glSurface)
            }
        } else {
            // 2D режим -> Standard SurfaceView
            player.setVideoSurfaceView(ui.standardSurfaceView)
        }
    }

    fun handleKeyEvent(event: KeyEvent): Boolean {
        if (settingsViewModel.isSettingsPanelVisible.value == true) timerController.resetSettingsTimer()
        val handled = inputHandler.handleKeyEvent(event, activity?.currentFocus)
        return handled
    }

    // === ЛОГИКА ЖЕСТОВ ===

    private fun handleDoubleTapSeek(forward: Boolean) {
        doubleTapResetHandler.removeCallbacks(performDoubleTapSeekRunnable)

        // Если направление сменилось, сбрасываем накопление
        if ((forward && doubleTapSeekSeconds < 0) || (!forward && doubleTapSeekSeconds > 0)) {
            doubleTapSeekSeconds = 0
        }

        val step = 15
        if (forward) doubleTapSeekSeconds += step else doubleTapSeekSeconds -= step

        ui.showDoubleTapOverlay(doubleTapSeekSeconds > 0, abs(doubleTapSeekSeconds))

        // Выполняем перемотку через небольшую задержку, чтобы пользователь мог тапнуть еще раз
        doubleTapResetHandler.postDelayed(performDoubleTapSeekRunnable, 600)
    }

    private fun changeVolume(deltaPercent: Float) {
        val audioManager = requireContext().getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        val stepsDelta = deltaPercent * maxVolume * 1.2f

        volumeAccumulator += stepsDelta

        // Применяем изменения к системе, если накопился целый шаг
        if (abs(volumeAccumulator) >= 1.0f) {
            val stepsToApply = volumeAccumulator.toInt()
            volumeAccumulator -= stepsToApply // Оставляем дробную часть для плавности

            val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val newSystemVolume = (currentVolume + stepsToApply).coerceIn(0, maxVolume)

            if (newSystemVolume != currentVolume) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newSystemVolume, 0)
            }
        }

        // Обновляем UI всегда, используя (Текущая громкость + Остаток аккумулятора)
        val finalSystemVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val visualVolume = (finalSystemVolume + volumeAccumulator).coerceIn(0f, maxVolume.toFloat())
        val percent = (visualVolume * 100) / maxVolume
        ui.showVolumeIndicator(percent.toInt())
    }

    private fun changeBrightness(deltaPercent: Float) {
        val window = requireActivity().window
        val lp = window.attributes

        // Если яркость еще не задана вручную, берем системную (примерно 0.5 как fallback)
        var currentBrightness = if (lp.screenBrightness < 0) 0.5f else lp.screenBrightness

        // deltaPercent приходит как доля экрана (например 0.05)
        currentBrightness = (currentBrightness + deltaPercent).coerceIn(0.01f, 1.0f)

        lp.screenBrightness = currentBrightness
        window.attributes = lp

        ui.showBrightnessIndicator((currentBrightness * 100).toInt())
    }

    private fun handleHorizontalSwipe(deltaX: Float, screenWidth: Int) {
        if (swipeAction == 0) return // None

        if (swipeAction == 1) {
            // === SEEK MODE (Перемотка) ===
            val duration = viewModel.duration.value ?: 0L
            if (duration <= 0) return

            if (!isSwipeSeeking) {
                isSwipeSeeking = true
                swipeSeekStartPosition = viewModel.currentPosition.value ?: 0L
                swipeSeekCurrentPosition = swipeSeekStartPosition
                timerController.stopControlsTimer()
            }

            // Чувствительность: Полный экран = 300 секунд
            val seekSensitivity = 300_000L
            val timeDelta = ((deltaX / screenWidth) * seekSensitivity).toLong()

            swipeSeekCurrentPosition = (swipeSeekCurrentPosition + timeDelta).coerceIn(0, duration)

            // Используем центральный оверлей (тот же, что и для кнопок)
            val totalDelta = swipeSeekCurrentPosition - (viewModel.player?.currentPosition ?: 0L)
            ui.showSeekOverlay(totalDelta, swipeSeekCurrentPosition)

            // Визуально двигаем сикбар
            ui.seekBar.progress = swipeSeekCurrentPosition.toInt()
            ui.updateTimeLabels(swipeSeekCurrentPosition, duration)
        }
        else if (swipeAction == 2) {
            // === PLAYLIST MODE ===
            swipePlaylistAccumulator += deltaX
            isPlaylistSwipeActive = true

            if (swipePlaylistAccumulator < 0) {
                // Тянем ВЛЕВО -> NEXT
                currentSwipeTargetIsNext = true
                val nextTitle = viewModel.getNextTrackTitle()

                if (nextTitle != null) {
                    currentSwipeIsExit = false
                    // Передаем название следующего видео
                    ui.updatePlaylistSwipe(swipePlaylistAccumulator, screenWidth, nextTitle, false)
                } else {
                    currentSwipeIsExit = true
                    ui.updatePlaylistSwipe(swipePlaylistAccumulator, screenWidth, getString(R.string.action_exit), true)
                }
            } else {
                // Тянем ВПРАВО -> PREV
                currentSwipeTargetIsNext = false
                val prevTitle = viewModel.getPrevTrackTitle()

                if (prevTitle != null) {
                    currentSwipeIsExit = false
                    // Передаем название предыдущего видео
                    ui.updatePlaylistSwipe(swipePlaylistAccumulator, screenWidth, prevTitle, false)
                } else {
                    currentSwipeIsExit = true
                    ui.updatePlaylistSwipe(swipePlaylistAccumulator, screenWidth, getString(R.string.action_exit), true)
                }
            }
        }
    }

    private fun finishHorizontalSwipe() {
        val screenWidth = resources.displayMetrics.widthPixels

        // 1. Завершение перемотки
        if (swipeAction == 1 && isSwipeSeeking) {
            viewModel.seekTo(swipeSeekCurrentPosition)
            ui.hideSeekOverlay()
            isSwipeSeeking = false
            timerController.resetControlsTimer()

            // Блокируем обновление UI от плеера на полсекунды, чтобы не дергалось
            viewModel.isUserInteracting = true
            Handler(Looper.getMainLooper()).postDelayed({
                viewModel.isUserInteracting = false
            }, 500)
        }

        // 2. Завершение свайпа плейлиста
        if (swipeAction == 2 && isPlaylistSwipeActive) {
            val threshold = screenWidth / 2 // Половина экрана
            val draggedDistance = abs(swipePlaylistAccumulator)

            if (draggedDistance > threshold) {
                // CONFIRM
                val isNext = currentSwipeTargetIsNext ?: true // fallback

                ui.animatePlaylistSwipeConfirm(isNext) {
                    if (currentSwipeIsExit) {
                        // Логика выхода
                        requireActivity().finish()
                    } else {
                        // Переключение трека
                        if (isNext) viewModel.nextTrack() else viewModel.prevTrack()
                    }
                }
            } else {
                // CANCEL
                ui.animatePlaylistSwipeCancel(screenWidth)
            }

            // Сброс
            swipePlaylistAccumulator = 0f
            isPlaylistSwipeActive = false
            currentSwipeTargetIsNext = null
        }
    }
    private fun setupControls() {
        // Навигация в настройках
        ui.btnSettingsPrev.setOnClickListener {
            viewModel.changeSettingValue(settingsViewModel.currentSettingType.value!!, -1)
            timerController.resetSettingsTimer()
        }
        ui.btnSettingsNext.setOnClickListener {
            viewModel.changeSettingValue(settingsViewModel.currentSettingType.value!!, 1)
            timerController.resetSettingsTimer()
        }
        ui.titleContainer.setOnClickListener {
            settingsViewModel.onMenuDown(viewModel.availableSettings.value ?: emptyList())
            timerController.resetSettingsTimer()
        }
        ui.topSettingsPanel.setOnClickListener { timerController.resetSettingsTimer() }

        // Основные клики
        ui.touchZoneTop.setOnClickListener {
            if (settingsViewModel.isSettingsPanelVisible.value != true) {
                viewModel.prepareSettingsPanel()
                settingsViewModel.openPanel(viewModel.availableSettings.value ?: emptyList())
            }
        }

        // ВАЖНО: Убрали onClickListener для root_container, так как теперь работает onTouchListener
        // view?.findViewById<View>(R.id.root_container)?.setOnClickListener { ... }

        ui.controlsView.setOnClickListener { timerController.resetControlsTimer() }

        // Кнопки плеера
        ui.playPauseButton.setOnClickListener { viewModel.togglePlayPause() }
        ui.rewindButton.setOnClickListener { viewModel.seekBack() }
        ui.ffwdButton.setOnClickListener { viewModel.seekForward() }
        ui.prevButton.setOnClickListener { viewModel.prevTrack() }
        ui.nextButton.setOnClickListener { viewModel.nextTrack() }

        ui.buttonSpeed.setOnClickListener {
            ui.hideControls()
            showPlaybackSpeedMenu()
        }

        ui.buttonResize.setOnClickListener {
            ui.hideControls()
            showResizeMenu()
        }

        ui.buttonAudio.setOnClickListener {
            ui.hideControls()
            showAudioTrackMenu()
        }

        ui.buttonSubs.setOnClickListener {
            ui.hideControls()
            showSubtitlesMenu()
        }

        ui.buttonSettings.setOnClickListener {
            ui.hideControls()
            showMainMenu()
        }
        ui.buttonSettings.setOnLongClickListener {
            // Останавливаем таймер скрытия интерфейса
            timerController.stopControlsTimer()
            // Скрываем контролы плеера, чтобы при возврате был чистый экран
            ui.hideControls()
            // Запускаем глобальные настройки
            val intent = Intent(requireContext(), GlobalSettingsActivity::class.java)
            startActivity(intent)
            true
        }

        ui.buttonPlaylist.setOnClickListener { showPlaylist() }
        ui.buttonQuality.setOnClickListener { showQualityPopup() }

        // SeekBar
        ui.seekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val duration = seekBar?.max?.toLong() ?: 0L
                    ui.updateTimeLabels(progress.toLong(), duration)
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {
                viewModel.isUserInteracting = true
                timerController.stopControlsTimer()
            }
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                seekBar?.progress?.let { viewModel.seekTo(it.toLong()) }
                viewModel.isUserInteracting = false
                timerController.resetControlsTimer()
            }
        })
    }

    /**
     * @param focusId ID элемента, на который нужно установить фокус.
     */
    private fun showMainMenu(focusId: String? = null) {
        val menuItems = viewModel.getMainMenuItems(requireContext())

        showSideMenu(getString(R.string.menu_main_title), menuItems, focusId) { selected ->
            if (selected == null) return@showSideMenu

            when (selected.id) {
                "audio" -> showAudioTrackMenu()
                "subtitles" -> showSubtitlesMenu()
                "speed" -> showPlaybackSpeedMenu()
                "resize" -> showResizeMenu()
                "quick_settings" -> {
                    sideMenuDialog?.dismiss()
                    viewModel.prepareSettingsPanel()
                    settingsViewModel.openPanel(viewModel.availableSettings.value ?: emptyList())
                }
                "global_settings" -> {
                    sideMenuDialog?.dismiss()
                    startActivity(Intent(requireContext(), GlobalSettingsActivity::class.java))
                }
            }
        }
    }

    private fun showAudioTrackMenu() {
        val menuItems = viewModel.getAudioTrackMenuItems(requireContext())
        if (menuItems.isEmpty()) return
        // -1, потому что первый элемент - "Off"
        val trackCount = (menuItems.size - 1).coerceAtLeast(0)

        showSideMenu(
            getString(R.string.menu_audio_title, trackCount),
            menuItems
        ) { selected ->
            if (selected == null) {
                showMainMenu("audio")
            } else {
                viewModel.selectTrackByIndex(C.TRACK_TYPE_AUDIO, selected.id.toInt())
                sideMenuDialog?.dismiss()
            }
        }
    }

    private fun showSubtitlesMenu() {
        val menuItems = viewModel.getSubtitleMenuItems(requireContext())
        if (menuItems.isEmpty()) return
        // -1, потому что первый элемент - "Off"
        val trackCount = (menuItems.size - 1).coerceAtLeast(0)

        showSideMenu(
            getString(R.string.menu_subtitle_title, trackCount),
            menuItems
        ) { selected ->
            if (selected == null) {
                showMainMenu("subtitles")
            } else {
                viewModel.selectTrackByIndex(C.TRACK_TYPE_TEXT, selected.id.toInt())
                sideMenuDialog?.dismiss()
            }
        }
    }

    private fun showPlaybackSpeedMenu() {
        val menuItems = viewModel.getPlaybackSpeedMenuItems()

        showSideMenu(getString(R.string.playback_speed), menuItems) { selected ->
            if (selected == null) {
                showMainMenu("speed")
            } else {
                val speedValue = selected.id.toFloatOrNull()
                if (speedValue != null) {
                    viewModel.setPlaybackSpeed(PlaybackSpeed.fromValue(speedValue))
                }
                sideMenuDialog?.dismiss()
            }
        }
    }

    private fun showResizeMenu() {
        val menuItems = viewModel.getResizeModeMenuItems(requireContext())
        var changed = false

        showSideMenu(getString(R.string.playback_zoom), menuItems) { selected ->
            if (selected == null) {
                if (changed) sideMenuDialog?.dismiss() else showMainMenu("resize")
            } else {
                val mode = ResizeMode.entries.firstOrNull { it.name == selected.id }
                if (mode != null) {
                    changed = true
                    if (mode == ResizeMode.SCALE && mode == viewModel.resizeMode.value) {
                        // Если SCALE уже выбран, или мы его только что выбрали, открываем диалог
                        showZoomLevelDialog()
                    } else {
                        // При повторном выборе закрываем диалог
                        if (mode == viewModel.resizeMode.value) sideMenuDialog?.dismiss()
                        else viewModel.setResizeMode(mode)
                    }
                }
                // Принудительно обновляем список в адаптере, чтобы выделить новый пункт.
                sideMenuAdapter?.submitList(viewModel.getResizeModeMenuItems(requireContext()))
            }
        }
    }

    private fun showZoomLevelDialog() {
        val currentZoom = viewModel.zoomScale.value ?: 115
        val context = requireContext()

        zoomDialog?.dismiss()
        zoomDialog = Dialog(context, R.style.Theme_App_Dialog)
        zoomDialog?.setContentView(R.layout.dialog_zoom_level)

        val title = zoomDialog?.findViewById<TextView>(R.id.dialog_title)
        val seekBar = zoomDialog?.findViewById<android.widget.SeekBar>(R.id.seek_zoom)
        var isDpadChange = false

        title?.text = getString(R.string.playback_zoom_percent, currentZoom)
        seekBar?.max = 100 // 100% до 200% (100 шагов)
        seekBar?.progress = currentZoom - 100

        seekBar?.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                val newZoom = progress + 100
                // Обновляем заголовок при изменении
                title?.text = getString(R.string.playback_zoom_percent, newZoom)

                if (fromUser || isDpadChange) {
                    viewModel.setZoomScale(newZoom)
                }
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        })

        // Перехват DPAD для шага 1 и закрытия по OK
        seekBar?.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        val newProgress = (seekBar.progress - 1).coerceAtLeast(0)
                        isDpadChange = true
                        seekBar.progress = newProgress
                        isDpadChange = false
                        return@setOnKeyListener true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        val newProgress = (seekBar.progress + 1).coerceAtMost(seekBar.max)
                        isDpadChange = true
                        seekBar.progress = newProgress
                        isDpadChange = false
                        return@setOnKeyListener true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        // Нажатие OK/Center на SeekBar закрывает диалог
                        zoomDialog?.dismiss()
                        return@setOnKeyListener true
                    }
                }
            }
            false // Передаем дальше
        }

        zoomDialog?.setOnDismissListener {
            // После закрытия диалога возвращаемся в меню масштабирования
            // (sideMenuDialog все еще открыт)
            showResizeMenu()
        }

        zoomDialog?.show()
        seekBar?.requestFocus()
    }

    /**
     * @param initialFocusId ID элемента для начального фокуса.
     * Если null, фокус ставится на элемент с isSelected=true, либо на первый.
     * @param onItemSelected Колбек выбора.
     * Если передан MenuItem - пользователь выбрал пункт.
     * Если передан null - пользователь закрыл меню (назад/тап мимо).
     */
    private fun showSideMenu(
        title: String,
        items: List<MenuItem>,
        initialFocusId: String? = null,
        onItemSelected: (MenuItem?) -> Unit
    ) {
        timerController.stopControlsTimer()
        ui.hideControls()

        if (sideMenuDialog == null) {
            // Используем кастомный стиль для бокового меню
            sideMenuDialog = Dialog(requireContext(), R.style.Theme_App_SideMenuDialog)
            sideMenuDialog?.setContentView(R.layout.dialog_side_menu)
            sideMenuDialog?.setCanceledOnTouchOutside(true)
            sideMenuDialog?.window?.apply {
                setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setGravity(Gravity.END)
            }
        }

        var isSelectionMade = false

        sideMenuDialog?.setOnDismissListener {
            timerController.resetControlsTimer()
            if (!isSelectionMade) {
                onItemSelected(null)
            } else {
                // Очистка списка, чтобы избежать утечек и проблем с DiffUtil при следующем открытии
                sideMenuAdapter?.submitList(emptyList())
            }
        }

        val header = sideMenuDialog?.findViewById<TextView>(R.id.menu_header)
        header?.text = title

        val recycler = sideMenuDialog?.findViewById<RecyclerView>(R.id.menu_recycler)
        if (sideMenuAdapter == null) {
            sideMenuAdapter = SideMenuAdapter { item ->
                isSelectionMade = true
                onItemSelected(item)
            }
            recycler?.adapter = sideMenuAdapter
        } else {
            sideMenuAdapter?.onItemClick = { item ->
                isSelectionMade = true
                onItemSelected(item)
            }
        }

        sideMenuAdapter?.submitList(items) {
            recycler?.post {
                var targetIndex = -1
                if (initialFocusId != null) {
                    targetIndex = items.indexOfFirst { it.id == initialFocusId }
                }

                if (targetIndex == -1) {
                    targetIndex = items.indexOfFirst { it.isSelected }
                }

                if (targetIndex == -1) targetIndex = 0

                // Скроллим и фокусируемся
                recycler.scrollToPosition(targetIndex)
                recycler.postDelayed({
                    val vh = recycler.findViewHolderForAdapterPosition(targetIndex)
                    vh?.itemView?.requestFocus()
                }, 50)
            }
        }

        sideMenuDialog?.show()
    }

    private fun observeViewModel() {
        viewModel.isPlaying.observe(viewLifecycleOwner) { isPlaying ->
            if (isPlaying) {
                // Если мы играем, значит фатальной ошибки нет.
                // Но ошибка видео может быть (тогда videoDisabledError не null).
                if (viewModel.videoDisabledError.value == null) {
                    ui.hideError()
                }
            }
            ui.playPauseButton.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow)
        }
        // Фатальная ошибка (плеер стоп)
        viewModel.fatalError.observe(viewLifecycleOwner) { error ->
            if (error != null) {
                ui.showFatalError(error)
            }
        }

        // Ошибка видео (звук идет)
        viewModel.videoDisabledError.observe(viewLifecycleOwner) { error ->
            if (error != null) {
                ui.showVideoErrorState(error)
            } else {
                // Если ошибка ушла (например, сменили видео), скрываем экран
                // Но только если нет фатальной ошибки
                if (viewModel.fatalError.value == null) {
                    ui.hideError()
                }
            }
        }

        // UpdateViewModel Observers
        updateViewModel.updateInfo.observe(viewLifecycleOwner) { info ->
            if (info != null) {
                ui.buttonUpdate.text = getString(R.string.update_btn_update_fmt, info.version)
                ui.buttonUpdate.alpha = 1.0f
                ui.buttonUpdate.setOnClickListener {
                    val intent = Intent(requireContext(), GlobalSettingsActivity::class.java).apply {
                        putExtra("FOCUS_ITEM_ID", R.id.item_update)
                    }
                    startActivity(intent)
                }
            } else {
                ui.buttonUpdate.text = getString(R.string.version, BuildConfig.VERSION_NAME)
                ui.buttonUpdate.alpha = 0.5f
                ui.buttonUpdate.setOnClickListener {
                    updateViewModel.forceCheckUpdates()
                }
            }
        }

        updateViewModel.isCheckingUpdates.observe(viewLifecycleOwner) { checking ->
            if (checking) {
                ui.buttonUpdate.text = getString(R.string.update_btn_checking)
                ui.buttonUpdate.isEnabled = false
            } else {
                ui.buttonUpdate.isEnabled = true
            }
        }

        updateViewModel.toastMessage.observe(viewLifecycleOwner) { msg ->
            if (msg != null) {
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                updateViewModel.clearToast()
            }
        }

        viewModel.duration.observe(viewLifecycleOwner) { duration ->
            ui.seekBar.max = duration.toInt()
            // Обновляем метки при изменении длительности
            val current = viewModel.currentPosition.value ?: 0L
            ui.updateTimeLabels(current, duration)
        }
        viewModel.currentPosition.observe(viewLifecycleOwner) { position ->
            if (!viewModel.isUserInteracting) {
                ui.seekBar.progress = position.toInt()
                // Обновляем метки при ходе воспроизведения
                val duration = viewModel.duration.value ?: 0L
                ui.updateTimeLabels(position, duration)
            }
        }
        viewModel.videoTitle.observe(viewLifecycleOwner) { title ->
            ui.videoTitleTextView.text = title
            viewModel.player?.let { p ->
                val posterUri = p.mediaMetadata.artworkUri
                val index = p.currentMediaItemIndex
                val size = p.mediaItemCount

                // Получаем настройку
                val settingsRepo = SettingsRepository(requireContext().applicationContext)
                val showIndex = settingsRepo.isShowPlaylistIndexEnabled()

                // Передаем в UI
                ui.loadPoster(posterUri, index, size, showIndex)
            }
        }

        viewModel.hasPrevious.observe(viewLifecycleOwner) {
            ui.prevButton.alpha = if (it) 1.0f else 0.3f; ui.prevButton.isEnabled = it
        }
        viewModel.hasNext.observe(viewLifecycleOwner) {
            ui.nextButton.alpha = if (it) 1.0f else 0.3f; ui.nextButton.isEnabled = it
        }
        viewModel.playlistSize.observe(viewLifecycleOwner) { ui.buttonPlaylist.isVisible = it > 1 }
        viewModel.currentQualityName.observe(viewLifecycleOwner) { ui.buttonQuality.text = it }

        viewModel.videoResolution.observe(viewLifecycleOwner) { ui.badgeResolution.text = it }
        viewModel.videoAspectRatio.observe(viewLifecycleOwner) { ui.setAspectRatio(it) }
        viewModel.audioOutputInfo.observe(viewLifecycleOwner) { updateAudioBadge() }
        viewModel.currentAudioTrack.observe(viewLifecycleOwner) { trackOption ->
            updateAudioBadge()
            updateSettingsText()
        }
        viewModel.currentSubtitleTrack.observe(viewLifecycleOwner) { trackOption ->
            val name = trackOption?.let { TrackLogic.buildTrackLabel(it, requireContext()) } ?: ""
            ui.badgeSubtitle.text = name
            updateSettingsText()
        }
        viewModel.playbackSpeed.observe(viewLifecycleOwner) { speed ->
            ui.buttonSpeed.text = speed.label
        }

        viewModel.resizeMode.observe(viewLifecycleOwner) { mode ->
            val zoom = viewModel.zoomScale.value ?: 115
            ui.setResizeMode(mode, zoom)
        }

        viewModel.zoomScale.observe(viewLifecycleOwner) { zoom ->
            // Обновляем UI, если мы в режиме SCALE
            if (viewModel.resizeMode.value == ResizeMode.SCALE) {
                ui.setResizeMode(ResizeMode.SCALE, zoom)
            }
        }

        viewModel.isBuffering.observe(viewLifecycleOwner) { isBuffering ->
            val percent = viewModel.bufferedPercentage.value ?: 0
            ui.updateBufferingState(isBuffering, viewModel.outputMode.value, percent)
        }

        viewModel.bufferedPercentage.observe(viewLifecycleOwner) { percent ->
            if (viewModel.isBuffering.value == true) {
                ui.updateBufferingState(true, viewModel.outputMode.value, percent)
            }
        }

        viewModel.bufferedPosition.observe(viewLifecycleOwner) { bufferedPos ->
            ui.seekBar.secondaryProgress = bufferedPos.toInt()
        }

        // Переключение поверхностей (HDR Fix)
        viewModel.inputType.observe(viewLifecycleOwner) { type ->
            val isStereo = type != StereoInputType.NONE
            ui.setSurfaceMode(isStereo)
            viewModel.player?.let { player ->
                if (isStereo) {
                    ui.glSurfaceView.onResume()
                    stereoRenderer?.setInputType(type)
                    if (glSurface != null) player.setVideoSurface(glSurface)
                } else {
                    ui.glSurfaceView.onPause()
                    player.setVideoSurfaceView(ui.standardSurfaceView)
                }
            }
            ui.updateStereoLayout(viewModel.outputMode.value, viewModel.screenSeparation.value ?: 0f)
            ui.updateInputModeIcon(type, viewModel.swapEyes.value ?: false)
        }

        // Обновление рендерера
        viewModel.swapEyes.observe(viewLifecycleOwner) { swap ->
            stereoRenderer?.setSwapEyes(swap)
            ui.iconSwapEyes.alpha = if (swap) 1.0f else 0.3f
            ui.updateInputModeIcon(viewModel.inputType.value ?: StereoInputType.NONE, swap)
        }
        viewModel.outputMode.observe(viewLifecycleOwner) { mode ->
            stereoRenderer?.setOutputMode(mode)
            ui.updateStereoLayout(mode, viewModel.screenSeparation.value ?: 0f)
        }
        viewModel.anaglyphType.observe(viewLifecycleOwner) { stereoRenderer?.setAnaglyphType(it) }
        viewModel.singleFrameSize.observe(viewLifecycleOwner) { (w, h) -> stereoRenderer?.setSingleFrameDimensions(w, h) }
        viewModel.depth.observe(viewLifecycleOwner) { stereoRenderer?.setDepth(it) }
        viewModel.screenSeparation.observe(viewLifecycleOwner) {
            stereoRenderer?.setScreenSeparation(it)
            ui.updateStereoLayout(viewModel.outputMode.value, it)
        }

        // VR параметры через делегат
        viewModel.anaglyphDelegate.vrK1.observe(viewLifecycleOwner) { updateVrParams() }
        viewModel.anaglyphDelegate.vrK2.observe(viewLifecycleOwner) { updateVrParams() }
        viewModel.anaglyphDelegate.vrScale.observe(viewLifecycleOwner) { updateVrParams() }

        viewModel.anaglyphDelegate.currentMatrices.observe(viewLifecycleOwner) { (l, r) -> stereoRenderer?.setAnaglyphMatrices(l, r) }

        // Панель настроек (SettingsViewModel)
        settingsViewModel.isSettingsPanelVisible.observe(viewLifecycleOwner) { isVisible ->
            ui.topSettingsPanel.isVisible = isVisible
            if (isVisible) {
                ui.hideControls()
                timerController.resetSettingsTimer()
                updateSettingsText()
            } else {
                timerController.stopSettingsTimer()
                ui.optionsRecycler.isVisible = false
            }
        }

        // Обновление текста настроек
        val updateTextObserver = { _: Any -> updateSettingsText() }
        settingsViewModel.currentSettingType.observe(viewLifecycleOwner, updateTextObserver)

        // Наблюдаем за всеми параметрами, которые могут изменить текст в OSD
        viewModel.inputType.observe(viewLifecycleOwner, updateTextObserver)
        viewModel.outputMode.observe(viewLifecycleOwner, updateTextObserver)
        viewModel.anaglyphType.observe(viewLifecycleOwner, updateTextObserver)
        viewModel.swapEyes.observe(viewLifecycleOwner, updateTextObserver)
        viewModel.depth.observe(viewLifecycleOwner, updateTextObserver)
        viewModel.screenSeparation.observe(viewLifecycleOwner, updateTextObserver)

        viewModel.anaglyphDelegate.customHueOffsetL.observe(viewLifecycleOwner, updateTextObserver)
        viewModel.anaglyphDelegate.customHueOffsetR.observe(viewLifecycleOwner, updateTextObserver)
        viewModel.anaglyphDelegate.customLeakL.observe(viewLifecycleOwner, updateTextObserver)
        viewModel.anaglyphDelegate.customLeakR.observe(viewLifecycleOwner, updateTextObserver)
        viewModel.anaglyphDelegate.customSpaceLms.observe(viewLifecycleOwner, updateTextObserver)
        viewModel.anaglyphDelegate.isMatrixValid.observe(viewLifecycleOwner, updateTextObserver)

        viewModel.cues.observe(viewLifecycleOwner) { cues ->
            ui.subtitleView.setCues(cues)
            ui.subtitleViewLeft.setCues(cues)
            ui.subtitleViewRight.setCues(cues)
        }

        viewModel.toastMessage.observe(viewLifecycleOwner) { msg ->
            if (msg != null) {
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                updateViewModel.clearToast()
            }
        }
    }

    private fun updateVrParams() {
        stereoRenderer?.setDistortion(
            viewModel.anaglyphDelegate.vrK1.value ?: 0.34f,
            viewModel.anaglyphDelegate.vrK2.value ?: 0.10f,
            viewModel.anaglyphDelegate.vrScale.value ?: 1.2f
        )
        updateSettingsText()
    }

    private fun updateSettingsText() {
        if (settingsViewModel.isSettingsPanelVisible.value != true) return
        val type = settingsViewModel.currentSettingType.value ?: return
        val context = requireContext()

        val valueStr = when(type) {
            SettingType.VIDEO_TYPE -> {
                val inputType = viewModel.inputType.value ?: StereoInputType.NONE
                // Получаем список локализованных строк из ViewModel (которая теперь использует контекст Fragment)
                viewModel.getOptionsForSetting(type, context)?.first?.getOrNull(inputType.ordinal) ?: inputType.name.replace("_", " ")
            }
            SettingType.OUTPUT_FORMAT -> {
                val outputMode = viewModel.outputMode.value ?: StereoOutputMode.ANAGLYPH
                viewModel.getOptionsForSetting(type, context)?.first?.getOrNull(outputMode.ordinal) ?: outputMode.name.replace("_", " ")
            }
            SettingType.GLASSES_TYPE -> {
                val group = AnaglyphLogic.getGlassesGroup(viewModel.anaglyphType.value!!)
                viewModel.getOptionsForSetting(type, context)?.first?.getOrNull(group.ordinal) ?: group.name.replace("_", " ")
            }
            SettingType.FILTER_MODE -> {
                val name = viewModel.anaglyphType.value?.name ?: ""
                if (name.endsWith("_CUSTOM")) getString(R.string.val_custom) else name
            }
            SettingType.CUSTOM_HUE_L -> {
                val offset = viewModel.anaglyphDelegate.customHueOffsetL.value ?: 0
                val color = viewModel.anaglyphDelegate.calculatedColorL.value ?: android.graphics.Color.WHITE
                getString(R.string.custom_hue_format, offset, String.format("#%06X", (0xFFFFFF and color)))
            }
            SettingType.CUSTOM_HUE_R -> {
                val offset = viewModel.anaglyphDelegate.customHueOffsetR.value ?: 0
                val color = viewModel.anaglyphDelegate.calculatedColorR.value ?: android.graphics.Color.WHITE
                getString(R.string.custom_hue_format, offset, String.format("#%06X", (0xFFFFFF and color)))
            }
            SettingType.CUSTOM_LEAK_L -> getString(R.string.custom_leak_format, (viewModel.anaglyphDelegate.customLeakL.value!! * 100).toInt())
            SettingType.CUSTOM_LEAK_R -> getString(R.string.custom_leak_format, (viewModel.anaglyphDelegate.customLeakR.value!! * 100).toInt())
            SettingType.CUSTOM_SPACE -> if (viewModel.anaglyphDelegate.customSpaceLms.value == true) "LMS" else "XYZ"
            SettingType.SWAP_EYES -> if (viewModel.swapEyes.value == true) getString(R.string.val_swap_rl) else getString(R.string.val_swap_lr)
            SettingType.DEPTH_3D -> viewModel.depth.value.toString()
            SettingType.SCREEN_SEPARATION -> getString(R.string.screen_separation_format, (viewModel.screenSeparation.value ?: 0f) * 100)
            SettingType.VR_DISTORTION -> getString(R.string.vr_distortion_format, viewModel.anaglyphDelegate.vrK1.value)
            SettingType.VR_ZOOM -> getString(R.string.vr_zoom_format, viewModel.anaglyphDelegate.vrScale.value)
            SettingType.AUDIO_TRACK -> {
                val track = viewModel.currentAudioTrack.value
                track?.let { TrackLogic.buildTrackLabel(it, context) } ?: ""
            }
            SettingType.SUBTITLES -> {
                val track = viewModel.currentSubtitleTrack.value
                track?.let { TrackLogic.buildTrackLabel(it, context) } ?: ""
            }
        }

        val color = when(type) {
            SettingType.CUSTOM_HUE_L -> viewModel.anaglyphDelegate.calculatedColorL.value ?: android.graphics.Color.WHITE
            SettingType.CUSTOM_HUE_R -> viewModel.anaglyphDelegate.calculatedColorR.value ?: android.graphics.Color.WHITE
            else -> android.graphics.Color.WHITE
        }

        ui.updateSettingsText(type, valueStr, viewModel.anaglyphDelegate.isMatrixValid.value ?: true, color)

        // список опций
        val optionsData = viewModel.getOptionsForSetting(type, context)
        ui.updateSettingsOptions(optionsData)
    }

    private fun updateAudioBadge() {
        val track = viewModel.currentAudioTrack.value
        val res = track?.let { TrackLogic.buildTrackLabel(it, requireContext()) } ?: ""
        val audioOut = viewModel.audioOutputInfo.value ?: ""

        val text = if (audioOut.isNotEmpty()) getString(R.string.audio_badge_format, res, audioOut) else res
        ui.badgeAudio.text = text
    }

    private fun showPlaylist() {
        val playlist = viewModel.currentPlaylist.value ?: emptyList()
        if (playlist.isEmpty()) {
            Toast.makeText(context, getString(R.string.msg_playlist_empty), Toast.LENGTH_SHORT).show()
            return
        }

        timerController.stopControlsTimer()
        ui.hideControls()

        val settingsRepo = SettingsRepository(requireContext().applicationContext)
        val showIndex = settingsRepo.isShowPlaylistIndexEnabled()

        ui.showPlaylistDialog(
            items = playlist,
            currentIndex = viewModel.player?.currentMediaItemIndex ?: 0,
            showIndexBadge = showIndex,
            onItemSelected = { index ->
                viewModel.playPlaylistItem(index)
            },
            onDismiss = {
                timerController.resetControlsTimer()
            }
        )
    }

    private fun showQualityPopup() {
        val options = viewModel.videoQualityOptions.value ?: return
        if (options.isEmpty()) return
        timerController.stopControlsTimer()

        val wrapper = ContextThemeWrapper(context, R.style.DarkPopupContext)
        val popup = PopupMenu(wrapper, ui.buttonQuality)
        options.forEachIndexed { index, option -> popup.menu.add(0, index, 0, option.name) }
        popup.setOnMenuItemClickListener { item ->
            viewModel.setVideoQuality(options[item.itemId])
            true
        }
        popup.setOnDismissListener { timerController.resetControlsTimer() }
        popup.show()
    }

    private fun setupBackPressedHandler() {
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (settingsViewModel.isSettingsPanelVisible.value == true) {
                    settingsViewModel.closePanel()
                    viewModel.restoreSettings()
                } else if (ui.controlsView.isVisible) {
                    ui.hideControls()
                } else {
                    isEnabled = false
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onSurfaceReady(surface: Surface) {
        this.glSurface = surface
        viewModel.player?.let { player ->
            if (viewModel.inputType.value != StereoInputType.NONE) {
                activity?.runOnUiThread { player.setVideoSurface(surface) }
            }
        }
    }

    override fun onFpsUpdated(fps: Int) {
        activity?.runOnUiThread {
            ui.fpsCounterTextView.text = getString(R.string.fps_counter_format, fps)
        }
    }

    override fun onResume() {
        super.onResume()
        // Обновляем настройку свайпа при возврате на экран
        val settingsRepo = SettingsRepository(requireContext().applicationContext)
        swipeAction = settingsRepo.getHorizontalSwipeAction()
        // Проверяем, нужно ли перезапустить плеер из-за смены настроек
        viewModel.checkSettingsAndRestart()
        viewModel.player?.playWhenReady = true
        if (viewModel.inputType.value != StereoInputType.NONE) {
            ui.glSurfaceView.onResume()
        }
    }

    override fun onPause() {
        super.onPause()
        viewModel.player?.playWhenReady = false
        ui.glSurfaceView.onPause()
    }

    override fun onDestroyView() {
        // Сначала отвязываем поверхность от плеера
        viewModel.player?.setVideoSurface(null)

        // Останавливаем таймеры
        timerController.cleanup()
        inputHandler.cleanup()
        doubleTapResetHandler.removeCallbacksAndMessages(null)

        // Освобождаем GL ресурсы
        stereoRenderer?.release()
        stereoRenderer = null
        glSurface = null
        super.onDestroyView()
    }

    companion object {
        fun newInstance() = PlayerFragment()
    }
}