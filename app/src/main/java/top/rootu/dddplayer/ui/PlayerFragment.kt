package top.rootu.dddplayer.ui

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.AudioManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.rootu.dddplayer.BuildConfig
import top.rootu.dddplayer.R
import top.rootu.dddplayer.data.SettingsRepository
import top.rootu.dddplayer.logic.AnaglyphLogic
import top.rootu.dddplayer.logic.StereoFormatLogic
import top.rootu.dddplayer.logic.TrackLogic
import top.rootu.dddplayer.model.AspectRatioMode
import top.rootu.dddplayer.model.MenuItem
import top.rootu.dddplayer.model.PlaybackSpeed
import top.rootu.dddplayer.model.ResizeMode
import top.rootu.dddplayer.model.StereoInputType
import top.rootu.dddplayer.model.StereoOutputMode
import top.rootu.dddplayer.renderer.OnSurfaceReadyListener
import top.rootu.dddplayer.renderer.StereoRenderer
import top.rootu.dddplayer.ui.adapter.ChoiceAdapter
import top.rootu.dddplayer.ui.adapter.SideMenuAdapter
import top.rootu.dddplayer.ui.controller.PlayerInputHandler
import top.rootu.dddplayer.ui.controller.PlayerTimerController
import top.rootu.dddplayer.ui.controller.PlayerTouchHandler
import top.rootu.dddplayer.ui.controller.PlayerUiController
import top.rootu.dddplayer.utils.IntentUtils
import top.rootu.dddplayer.utils.afr.AfrFormatItem
import top.rootu.dddplayer.utils.afr.AutoFrameRateHelper
import top.rootu.dddplayer.utils.afr.DisplayHolder
import top.rootu.dddplayer.utils.afr.DisplaySyncHelper
import top.rootu.dddplayer.utils.afr.TvQuickActions
import top.rootu.dddplayer.viewmodel.PlayerViewModel
import top.rootu.dddplayer.viewmodel.SPEED_MENU_CUSTOM
import top.rootu.dddplayer.viewmodel.SessionPrompt
import top.rootu.dddplayer.viewmodel.SUBTITLE_MENU_LOAD_FILE
import top.rootu.dddplayer.viewmodel.PlaylistUiState
import top.rootu.dddplayer.viewmodel.SettingType
import top.rootu.dddplayer.viewmodel.SettingsViewModel
import top.rootu.dddplayer.viewmodel.UpdateViewModel
import kotlin.math.abs
import kotlin.math.roundToInt

class PlayerFragment : Fragment(), OnSurfaceReadyListener {

    private var dimJob: Job? = null

    // Время первого нажатия "Назад" для подтверждения выхода
    private var lastExitBackPressMs = 0L
    private var zoomDialog: Dialog? = null
    private var inPictureInPicture = false
    private val viewModel: PlayerViewModel by activityViewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()
    private val updateViewModel: UpdateViewModel by viewModels()

    private var stereoRenderer: StereoRenderer? = null
    private var sideMenuDialog: Dialog? = null
    private var sideMenuAdapter: SideMenuAdapter? = null
    // Пункты, показанные в боковом меню сейчас (клики по пунктам другого меню игнорируются)
    private var sideMenuItems: List<MenuItem> = emptyList()

    // Контроллеры
    private var _ui: PlayerUiController? = null
    private val ui get() = _ui!!
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
    private var swipeAction = SettingsRepository.SWIPE_NONE
    private var swipeEdgeExclusion = true
    private var verticalGesturesEnabled = true // Жесты громкости и яркости
    private var hiddenControlButtons: Set<String> = emptySet() // Кнопки, скрытые в настройках

    // Масштабирование двумя пальцами (только 2D)
    private lateinit var scaleDetector: ScaleGestureDetector
    private var pinchZoom = 100f
    private var bufferDisplayMode = SettingsRepository.BUFFER_DISPLAY_OFF
    // Кнопка слева в панели: новая версия (если есть), иначе буфер (вывод "в панели") или текущая версия
    private var availableUpdateVersion: String? = null
    private var checkingUpdates = false
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

        _ui = PlayerUiController(view)

        timerController = PlayerTimerController(
            scope = viewLifecycleOwner.lifecycleScope,
            onHideControls = { ui.hideControls() },
            onHideSettings = {
                if (settingsViewModel.isSettingsPanelVisible.value == true) {
                    settingsViewModel.closePanel()
                    viewModel.restoreSettings()
                    // Фокус на корневой контейнер после закрытия OSD
                    view.findViewById<View>(R.id.root_container)?.requestFocus()
                }
            },
            clockViews = listOf(ui.standaloneClock, ui.textClock)
        )

        // Используем Application Context для репозитория, чтобы избежать утечек
        val settingsRepo = SettingsRepository.getInstance(requireContext().applicationContext)

        // Устанавливаем видимость часов на основе настроек
        ui.updateClockTransparency(settingsRepo.getClockTransparency())
        applyBufferDisplayMode(settingsRepo.getBufferDisplayMode())

        inputHandler = PlayerInputHandler(
            viewModel, settingsViewModel, ui,
            settingsRepo,
            onShowMainMenu = { showMainMenu() },
            onShowControls = { ui.showControls(); timerController.resetControlsTimer() },
            onHideControls = { ui.hideControls() },
            onResetHideTimer = { timerController.resetControlsTimer() },
            onShowPlaylist = { showPlaylist() },
            onToggleCustomSpeed = { viewModel.toggleCustomSpeed() }
        )

        swipeAction = settingsRepo.getHorizontalSwipeAction()
        swipeEdgeExclusion = settingsRepo.isSwipeEdgeExclusionEnabled()
        verticalGesturesEnabled = settingsRepo.isVolumeBrightnessGesturesEnabled()
        hiddenControlButtons = settingsRepo.getHiddenControlButtons()
        ui.buttonBack.isVisible = settingsRepo.isBackButtonEnabled()

        // === ИНИЦИАЛИЗАЦИЯ ЖЕСТОВ ===
        val touchArea = view.findViewById<View>(R.id.root_container)

        touchHandler = PlayerTouchHandler(
            context = requireContext(),
            viewWidth = ::touchAreaWidth,
            // Реальные зоны жеста "Назад" (навигация жестами, Android 10+); при кнопках навигации их нет
            backGestureEdges = {
                // Отключается в настройках, если свайп от края не срабатывает
                val insets = if (swipeEdgeExclusion) {
                    ViewCompat.getRootWindowInsets(touchArea)?.getInsets(WindowInsetsCompat.Type.systemGestures())
                } else null
                (insets?.left ?: 0) to (insets?.right ?: 0)
            },
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
                handleHorizontalSwipe(deltaX, touchAreaWidth())
            },
            onGestureEnd = {
                ui.hideGestureIndicatorDelayed()
                volumeAccumulator = 0f

                // Завершение горизонтального свайпа
                finishHorizontalSwipe()
            },
            isVerticalGesturesEnabled = { verticalGesturesEnabled }
        )

        gestureDetector = GestureDetector(requireContext(), touchHandler)

        // Двумя пальцами: развести/свести - масштаб (режим "Масштаб" 100-200%), двигать - сдвиг картинки
        scaleDetector = ScaleGestureDetector(requireContext(), object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            private var lastFocusX = 0f
            private var lastFocusY = 0f

            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                // Только 2D видео и если не начат свайп одним пальцем
                if (viewModel.inputType.value != StereoInputType.NONE || touchHandler.isScrolling) return false
                pinchZoom = if (viewModel.resizeMode.value == ResizeMode.SCALE) {
                    (viewModel.zoomScale.value ?: 100).toFloat()
                } else {
                    100f
                }
                lastFocusX = detector.focusX
                lastFocusY = detector.focusY
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                pinchZoom = (pinchZoom * detector.scaleFactor).coerceIn(100f, 200f)
                val percent = pinchZoom.roundToInt()
                if (percent != viewModel.zoomScale.value || viewModel.resizeMode.value != ResizeMode.SCALE) {
                    viewModel.setZoomScale(percent)
                }
                ui.panVideo(detector.focusX - lastFocusX, detector.focusY - lastFocusY)
                lastFocusX = detector.focusX
                lastFocusY = detector.focusY
                return true
            }
        }).apply {
            // Двойной тап с протяжкой не масштабирует: двойной тап - перемотка
            isQuickScaleEnabled = false
        }

        // Вешаем слушатель на корневой контейнер
        view.findViewById<View>(R.id.root_container).setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                resetDimTimer() // Осветляем при касании
            }
            if (event.action == MotionEvent.ACTION_UP) {
                touchHandler.onActionUp()
            }
            if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN && !touchHandler.isScrolling) {
                touchHandler.onMultiTouch()
            }
            scaleDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            true // Поглощаем событие
        }

        // ==========================================
        // НАСТРОЙКА OPENGL ES (Детекция GLES 3.0)
        // ==========================================
        val activityManager = requireContext().getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val configurationInfo = activityManager.deviceConfigurationInfo
        // Проверяем поддержку OpenGL ES 3.0 (0x30000)
        val supportsEs3 = configurationInfo.reqGlEsVersion >= 0x30000
        viewModel.setDeviceSupportsEs3(supportsEs3)

        // При принудительном GLES 2.0 создаем и контекст ES 2.0: это обходит и проблемы
        // самого ES 3.0 драйвера, а не только шейдеров
        val preferEs3 = viewModel.renderApiMode.value != SettingsRepository.RENDER_API_GLES2
        val contextIsEs3 = supportsEs3 && preferEs3
        ui.glSurfaceView.setEGLContextClientVersion(if (contextIsEs3) 3 else 2)

        ui.glSurfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)

        stereoRenderer = StereoRenderer(ui.glSurfaceView, this, contextIsEs3, preferEs3).apply {
            onEs3ShadersUnavailable = {
                // Вызывается в GL потоке
                activity?.runOnUiThread { viewModel.setDeviceSupportsEs3(false) }
            }
        }
        ui.glSurfaceView.setRenderer(stereoRenderer)
        ui.glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Сохраняем оригинальный режим ТВ при старте
        val afrHelper = AutoFrameRateHelper.instance(requireContext())
        afrHelper.saveOriginalState(requireActivity())

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

        // Осветляем экран, если пользователь нажал любую кнопку на пульте
        if (event.action == KeyEvent.ACTION_DOWN) {
            resetDimTimer()
        }

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

    /** Ширина области касаний: экран плеера, а не дисплея (вырез и панель навигации в нее не входят). */
    private fun touchAreaWidth(): Int =
        view?.findViewById<View>(R.id.root_container)?.width?.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels

    private fun handleHorizontalSwipe(deltaX: Float, screenWidth: Int) {
        if (swipeAction == SettingsRepository.SWIPE_NONE) return

        if (swipeAction == SettingsRepository.SWIPE_SEEK) {
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
            val isLive = viewModel.isLive.value ?: false
            val liveOffset = if (isLive) ((viewModel.duration.value ?: 0L) - swipeSeekCurrentPosition).coerceAtLeast(0) else 0L
            ui.showSeekOverlay(totalDelta, swipeSeekCurrentPosition, isLive, liveOffset)

            // Визуально двигаем сикбар
            ui.seekBar.progress = swipeSeekCurrentPosition.toInt()
            updateTimeLabelsUI(swipeSeekCurrentPosition)
        }
        else if (swipeAction == SettingsRepository.SWIPE_PLAYLIST || swipeAction == SettingsRepository.SWIPE_EXIT_NEXT) {
            // === PLAYLIST MODE (в режиме "Выход или След" вправо - всегда выход) ===
            swipePlaylistAccumulator += deltaX
            isPlaylistSwipeActive = true
            val showIndexBadge = SettingsRepository.getInstance(requireContext().applicationContext).isShowPlaylistIndexEnabled()

            // В эфирном режиме листаем каналы текущей группы по кругу
            val isLiveMode = viewModel.isOverallLiveMode()

            if (swipePlaylistAccumulator < 0) {
                // Тянем ВЛЕВО -> NEXT
                currentSwipeTargetIsNext = true
                val liveItem = if (isLiveMode) viewModel.getRelativePlaylistItem(1) else null
                val nextTitle = liveItem?.title ?: viewModel.getNextTrackTitle()

                if (nextTitle != null) {
                    currentSwipeIsExit = false
                    // Передаем название и карточку следующего видео
                    val targetItem = liveItem ?: viewModel.getAdjacentPlaylistItem(next = true)
                    ui.updatePlaylistSwipe(swipePlaylistAccumulator, screenWidth, nextTitle, false, targetItem, showIndexBadge)
                } else {
                    currentSwipeIsExit = true
                    ui.updatePlaylistSwipe(swipePlaylistAccumulator, screenWidth, getString(R.string.action_exit), true)
                }
            } else {
                // Тянем ВПРАВО -> PREV
                currentSwipeTargetIsNext = false
                val exitOnly = swipeAction == SettingsRepository.SWIPE_EXIT_NEXT
                val liveItem = if (isLiveMode && !exitOnly) viewModel.getRelativePlaylistItem(-1) else null
                val prevTitle = if (exitOnly) null else liveItem?.title ?: viewModel.getPrevTrackTitle()

                if (prevTitle != null) {
                    currentSwipeIsExit = false
                    // Передаем название и карточку предыдущего видео
                    val targetItem = liveItem ?: viewModel.getAdjacentPlaylistItem(next = false)
                    ui.updatePlaylistSwipe(swipePlaylistAccumulator, screenWidth, prevTitle, false, targetItem, showIndexBadge)
                } else {
                    currentSwipeIsExit = true
                    ui.updatePlaylistSwipe(swipePlaylistAccumulator, screenWidth, getString(R.string.action_exit), true)
                }
            }
        }
    }

    private fun finishHorizontalSwipe() {
        val screenWidth = touchAreaWidth()

        // 1. Завершение перемотки
        if (swipeAction == SettingsRepository.SWIPE_SEEK && isSwipeSeeking) {
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
        if (isPlaylistSwipeActive) {
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
                        val liveTarget = if (viewModel.isOverallLiveMode()) {
                            viewModel.getRelativePlaylistItem(if (isNext) 1 else -1)
                        } else null
                        when {
                            liveTarget != null -> viewModel.playPlaylistItem(liveTarget.absoluteIndex)
                            isNext -> viewModel.nextTrack()
                            else -> viewModel.prevTrack()
                        }
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
        // Долгое нажатие на подробности ошибки копирует их (чтобы приложить к issue)
        ui.errorDetails.setOnLongClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText(getString(R.string.error), ui.errorDetails.text))
            Toast.makeText(requireContext(), R.string.error_copied, Toast.LENGTH_SHORT).show()
            true
        }
        updateControlButtonsVisibility()
        ui.buttonBack.setOnClickListener { requireActivity().finish() }
        ui.buttonPip.setOnClickListener {
            (activity as? PlayerActivity)?.enterPlayerPictureInPicture(showError = true)
        }
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
            val isOsdVisible = settingsViewModel.isSettingsPanelVisible.value == true
            val isSideMenuVisible = sideMenuDialog?.isShowing == true
            if (isOsdVisible || isSideMenuVisible) return@setOnClickListener

            // В эфирном режиме верхняя зона открывает плейлист
            if (viewModel.isOverallLiveMode()) {
                showPlaylist()
                return@setOnClickListener
            }

            // Получаем текущую настройку действия кнопки "Вверх"
            val settingsRepo = SettingsRepository.getInstance(requireContext().applicationContext)

            when (settingsRepo.getUpButtonAction()) {
                SettingsRepository.UP_ACTION_OSD -> { // Быстрые настройки
                    viewModel.prepareSettingsPanel()
                    settingsViewModel.openPanel(viewModel.availableSettings.value ?: emptyList())
                }
                SettingsRepository.UP_ACTION_MENU -> { // Боковое меню
                    ui.hideControls()
                    showMainMenu()
                }
                SettingsRepository.UP_ACTION_INFO -> {
                    if (ui.topInfoPanel.isVisible) {
                        // Если панель уже есть, то показываем боковое меню
                        ui.hideControls()
                        showMainMenu()
                    } else {
                        ui.showInfoPanelOnly()
                    }
                }
                // UP_ACTION_NONE -> Ничего не делать
            }
        }

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
        // Долгое нажатие: сразу своя скорость и ползунок, после него - снова панель управления
        ui.buttonSpeed.setOnLongClickListener {
            timerController.stopControlsTimer()
            viewModel.setPlaybackSpeed(viewModel.getCustomPlaybackSpeed())
            showCustomSpeedDialog(onDismiss = {
                ui.showControls()
                ui.buttonSpeed.requestFocus()
                timerController.resetControlsTimer()
            })
            true
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
            openGlobalSettings()
            true
        }

        ui.buttonPlaylist.setOnClickListener { showPlaylist() }
        ui.buttonQuality.setOnClickListener { showQualityPopup() }

        // SeekBar
        ui.seekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val duration = seekBar?.max?.toLong() ?: 0L
                    val isLive = viewModel.isLive.value ?: false
                    val speed = viewModel.playbackSpeed.value ?: 1.0f

                    val liveOffset = if (isLive) (duration - progress.toLong()).coerceAtLeast(0) else 0L

                    ui.updateTimeLabels(progress.toLong(), duration, speed, isLive, liveOffset)
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
                "aspect" -> showAspectRatioMenu()
                "quick_settings" -> {
                    sideMenuDialog?.dismiss()
                    viewModel.prepareSettingsPanel()
                    settingsViewModel.openPanel(viewModel.availableSettings.value ?: emptyList())
                }
                "global_settings" -> {
                    sideMenuDialog?.dismiss()
                    openGlobalSettings()
                }
            }
        }
    }

    /**
     * Глобальные настройки поверх плеера. Без FLAG_ACTIVITY_NO_USER_ACTION система считает переход уходом
     * пользователя (onUserLeaveHint), и при автовходе в PiP плеер уходил в окно PiP вместо паузы.
     */
    private fun openGlobalSettings(focusItemId: Int? = null) {
        val intent = Intent(requireContext(), GlobalSettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION)
        focusItemId?.let { intent.putExtra("FOCUS_ITEM_ID", it) }
        startActivity(intent)
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
                selected.id.toIntOrNull()?.let { viewModel.selectTrackByIndex(C.TRACK_TYPE_AUDIO, it, fromUser = true) }
                sideMenuDialog?.dismiss()
            }
        }
    }

    private fun showSubtitlesMenu() {
        val menuItems = viewModel.getSubtitleMenuItems(requireContext())
        if (menuItems.isEmpty()) return
        // -1, потому что первый элемент - "Off"
        // -2: пункты "Выкл" и "Загрузить из файла"
        val trackCount = (menuItems.size - 2).coerceAtLeast(0)

        showSideMenu(
            getString(R.string.menu_subtitle_title, trackCount),
            menuItems
        ) { selected ->
            if (selected == null) {
                showMainMenu("subtitles")
            } else if (selected.id == SUBTITLE_MENU_LOAD_FILE) {
                sideMenuDialog?.dismiss()
                openSubtitleFile()
            } else {
                selected.id.toIntOrNull()?.let { viewModel.selectTrackByIndex(C.TRACK_TYPE_TEXT, it, fromUser = true) }
                sideMenuDialog?.dismiss()
            }
        }
    }

    // Выбор файла субтитров через системный диалог (на части ТВ его нет)
    private val pickSubtitleLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = IntentUtils.resolveFileName(requireContext(), uri)
            viewModel.addExternalSubtitle(uri, name)
        }
    }

    private fun openSubtitleFile() {
        try {
            pickSubtitleLauncher.launch(arrayOf("*/*"))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(requireContext(), R.string.subtitle_no_file_picker, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showPlaybackSpeedMenu(initialFocusId: String? = null) {
        val menuItems = viewModel.getPlaybackSpeedMenuItems(requireContext())

        showSideMenu(
            getString(R.string.playback_speed), menuItems, initialFocusId,
            onItemLongClick = { item -> setDefaultSpeedFromMenu(item) }
        ) { selected ->
            when {
                selected == null -> showMainMenu("speed")
                selected.id == SPEED_MENU_CUSTOM -> {
                    // Своя скорость: применяем сохраненную и открываем ползунок
                    viewModel.setPlaybackSpeed(selected.data as? Float ?: viewModel.getCustomPlaybackSpeed())
                    showCustomSpeedDialog()
                }
                else -> {
                    selected.id.toFloatOrNull()?.let { viewModel.setPlaybackSpeed(it) }
                    sideMenuDialog?.dismiss()
                }
            }
        }
    }

    /** Долгое нажатие в меню скорости: пункт становится скоростью по умолчанию. */
    private fun setDefaultSpeedFromMenu(item: MenuItem) {
        val speed = if (item.id == SPEED_MENU_CUSTOM) item.data as? Float else item.id.toFloatOrNull()
        if (speed == null) return
        viewModel.setDefaultPlaybackSpeed(speed)
        Toast.makeText(
            requireContext(), getString(R.string.playback_speed_default_set, PlaybackSpeed.label(speed)), Toast.LENGTH_SHORT
        ).show()
        updateSideMenuItems(viewModel.getPlaybackSpeedMenuItems(requireContext()))
    }

    /**
     * Ползунок своей скорости.
     * @param onDismiss куда вернуться: по умолчанию в меню скорости (боковое меню еще открыто)
     */
    private fun showCustomSpeedDialog(onDismiss: () -> Unit = { showPlaybackSpeedMenu(SPEED_MENU_CUSTOM) }) {
        val steps = ((PlaybackSpeed.CUSTOM_MAX - PlaybackSpeed.CUSTOM_MIN) / PlaybackSpeed.CUSTOM_STEP).roundToInt()
        fun speedAt(progress: Int) = PlaybackSpeed.normalize(PlaybackSpeed.CUSTOM_MIN + progress * PlaybackSpeed.CUSTOM_STEP)
        val current = PlaybackSpeed.normalize(viewModel.playbackSpeed.value ?: 1.0f)
        showLevelDialog(
            max = steps,
            progress = ((current - PlaybackSpeed.CUSTOM_MIN) / PlaybackSpeed.CUSTOM_STEP).roundToInt(),
            title = { getString(R.string.playback_speed_custom_value, PlaybackSpeed.label(speedAt(it))) },
            onChange = { viewModel.setCustomPlaybackSpeed(speedAt(it)) },
            onDismiss = onDismiss
        )
    }

    private fun showAspectRatioMenu() {
        val menuItems = viewModel.getAspectRatioMenuItems(requireContext())

        showSideMenu(getString(R.string.aspect_ratio), menuItems) { selected ->
            if (selected == null) {
                showMainMenu("aspect")
            } else {
                AspectRatioMode.entries.firstOrNull { it.name == selected.id }?.let {
                    viewModel.setAspectRatioMode(it)
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
                updateSideMenuItems(viewModel.getResizeModeMenuItems(requireContext()))
            }
        }
    }

    private fun showZoomLevelDialog() {
        val currentZoom = viewModel.zoomScale.value ?: 115
        showLevelDialog(
            max = 100, // 100% до 200% (100 шагов)
            progress = currentZoom - 100,
            title = { getString(R.string.playback_zoom_percent, it + 100) },
            onChange = { viewModel.setZoomScale(it + 100) },
            // После закрытия диалога возвращаемся в меню масштабирования
            // (sideMenuDialog все еще открыт)
            onDismiss = { showResizeMenu() }
        )
    }

    /**
     * Диалог с ползунком (зум, своя скорость): шаг 1 с пульта, OK закрывает.
     * @param title заголовок для положения ползунка
     * @param onChange новое положение, выбранное пользователем
     */
    private fun showLevelDialog(
        max: Int,
        progress: Int,
        title: (Int) -> String,
        onChange: (Int) -> Unit,
        onDismiss: () -> Unit
    ) {
        zoomDialog?.dismiss()
        zoomDialog = Dialog(requireContext(), R.style.Theme_App_Dialog)
        zoomDialog?.setContentView(R.layout.dialog_zoom_level)

        val titleView = zoomDialog?.findViewById<TextView>(R.id.dialog_title)
        val seekBar = zoomDialog?.findViewById<android.widget.SeekBar>(R.id.seek_zoom)
        var isDpadChange = false

        seekBar?.max = max
        seekBar?.progress = progress.coerceIn(0, max)
        titleView?.text = title(seekBar?.progress ?: progress)

        seekBar?.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                // Обновляем заголовок при изменении
                titleView?.text = title(progress)
                if (fromUser || isDpadChange) onChange(progress)
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        })

        // Перехват DPAD для шага 1 и закрытия по OK
        seekBar?.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        val delta = if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                        isDpadChange = true
                        seekBar.progress = (seekBar.progress + delta).coerceIn(0, seekBar.max)
                        isDpadChange = false
                        return@setOnKeyListener true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        // Нажатие OK/Center на SeekBar закрывает диалог.
                        // Автоповтор пропускаем: диалог мог открыться долгим нажатием OK, которое еще держат
                        if (event.repeatCount == 0) zoomDialog?.dismiss()
                        return@setOnKeyListener true
                    }
                }
            }
            false // Передаем дальше
        }

        zoomDialog?.setOnDismissListener { onDismiss() }
        zoomDialog?.show()
        seekBar?.requestFocus()
    }

    /** Обновляет пункты открытого бокового меню (например, отметку выбранного), не теряя обработку кликов. */
    private fun updateSideMenuItems(items: List<MenuItem>) {
        sideMenuItems = items
        sideMenuAdapter?.submitList(items)
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
        onItemLongClick: ((MenuItem) -> Unit)? = null,
        onItemSelected: (MenuItem?) -> Unit
    ) {
        viewModel.stopZap()
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
        val onItemClick: (MenuItem) -> Unit = { item ->
            // DiffUtil применяет новый список асинхронно, а обработчик меняется сразу.
            // Клик, пришедший в этот промежуток, относится к элементу предыдущего меню
            // (например, "audio" из главного меню) - игнорируем его.
            if (sideMenuItems.any { it === item }) {
                isSelectionMade = true
                onItemSelected(item)
            }
        }
        if (sideMenuAdapter == null) {
            sideMenuAdapter = SideMenuAdapter(onItemClick)
            recycler?.adapter = sideMenuAdapter
        } else {
            sideMenuAdapter?.onItemClick = onItemClick
        }
        // Долгое нажатие - только в меню, которые его поддерживают (скорость)
        sideMenuAdapter?.onItemLongClick = onItemLongClick?.let { handler ->
            { item: MenuItem -> if (sideMenuItems.any { it === item }) handler(item) }
        }

        sideMenuItems = items
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

    private fun applyBufferDisplayMode(mode: Int) {
        bufferDisplayMode = mode
        updateBufferDisplay(viewModel.bufferedPercentage.value ?: 0)
    }

    private fun updateBufferDisplay(percent: Int) {
        // Буфер "в панели" показывается на месте версии, а под полосой перемотки - только когда это место
        // заняла кнопка обновления
        val underSeekBar = bufferDisplayMode == SettingsRepository.BUFFER_DISPLAY_PANEL && availableUpdateVersion != null
        ui.updateBufferDisplay(percent, if (underSeekBar) bufferDisplayMode else SettingsRepository.BUFFER_DISPLAY_OFF)
        renderVersionButton()
        // Рядом с часами: и в одиночных часах, и в часах инфопанели
        timerController.bufferText =
            if (bufferDisplayMode == SettingsRepository.BUFFER_DISPLAY_CLOCK) "$percent%" else ""
    }

    /**
     * Кнопка слева в панели. Есть обновление - иконка и номер новой версии (открывает настройки на обновлении),
     * иначе буфер при выводе "в панели" или текущая версия (нажатие проверяет обновления).
     */
    private fun renderVersionButton() {
        val button = ui.buttonUpdate
        val update = availableUpdateVersion
        val icon = if (update != null && !checkingUpdates) R.drawable.ic_system_update_alt else 0
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
        TextViewCompat.setCompoundDrawableTintList(button, button.textColors)
        button.isEnabled = !checkingUpdates
        when {
            checkingUpdates -> {
                button.text = getString(R.string.update_btn_checking)
                button.alpha = 0.5f
            }
            update != null -> {
                button.text = update
                button.contentDescription = getString(R.string.update_btn_update_fmt, update)
                button.alpha = 1.0f
                button.setOnClickListener { openGlobalSettings(focusItemId = R.id.item_update) }
            }
            else -> {
                button.text = if (bufferDisplayMode == SettingsRepository.BUFFER_DISPLAY_PANEL) {
                    getString(R.string.buffer_percentage, viewModel.bufferedPercentage.value ?: 0)
                } else {
                    getString(R.string.version, BuildConfig.VERSION_NAME)
                }
                button.contentDescription = null
                button.alpha = 0.5f
                button.setOnClickListener { updateViewModel.forceCheckUpdates() }
            }
        }
    }

    private fun resetDimTimer() {
        dimJob?.cancel()
        if (inPictureInPicture) return
        ui.animateDimming(0f) // Сразу осветляем экран при любой активности

        // Затемняем только при паузе пользователем. Во время буферизации,
        // после ошибки или в конце видео playWhenReady остается true.
        val isPausedByUser = viewModel.player?.playWhenReady == false
        if (isPausedByUser) {
            val dimLevel = SettingsRepository.getInstance(requireContext()).getPauseDimLevel()
            if (dimLevel > 0) {
                dimJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(5000) // Ждем 5 секунд бездействия на паузе
                    ui.animateDimming(dimLevel / 100f)
                }
            }
        }
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

            // Сбрасываем таймер затемнения при снятии/постановке на паузу
            resetDimTimer()
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
            availableUpdateVersion = info?.version
            updateBufferDisplay(viewModel.bufferedPercentage.value ?: 0)
        }

        updateViewModel.isCheckingUpdates.observe(viewLifecycleOwner) { checking ->
            checkingUpdates = checking
            renderVersionButton()
        }

        updateViewModel.toastMessage.observe(viewLifecycleOwner) { msg ->
            if (msg != null) {
                Toast.makeText(requireContext().applicationContext, msg, Toast.LENGTH_LONG).show()
                updateViewModel.clearToast()
            }
        }

        viewModel.duration.observe(viewLifecycleOwner) { duration ->
            ui.seekBar.max = duration.toInt()
            // Обновляем метки при изменении длительности
            val current = viewModel.currentPosition.value ?: 0L
            updateTimeLabelsUI(current)
        }
        viewModel.currentPosition.observe(viewLifecycleOwner) { position ->
            if (!viewModel.isUserInteracting) {
                ui.seekBar.progress = position.toInt()
                updateTimeLabelsUI(position)
            }
        }
        viewModel.videoTitle.observe(viewLifecycleOwner) { refreshInfoPanelData() }

        // Инфопанель при смене элемента (по настройке или в эфирном режиме)
        viewModel.showInfoPanelEvent.observe(viewLifecycleOwner) { event ->
            event.getContentIfNotHandled()?.let {
                if (!ui.controlsView.isVisible) ui.showInfoPanelOnly()
            }
        }

        // Выбор канала Влево/Вправо в эфирном режиме
        var hadZapPreview = false
        viewModel.zapPreviewItem.observe(viewLifecycleOwner) { item ->
            if (item != null) {
                hadZapPreview = true
                val showIndex = SettingsRepository.getInstance(requireContext().applicationContext).isShowPlaylistIndexEnabled()
                ui.showInfoPanelWithData(item, viewModel.currentPlaylist.value?.size ?: 0, showIndex)
            } else if (hadZapPreview) {
                // Выбор закончен или отменен: возвращаем данные текущего элемента и скрываем панель
                hadZapPreview = false
                refreshInfoPanelData()
                if (ui.controlsView.isVisible) ui.hideInfoPanel() else ui.showInfoPanelOnly()
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
        // Выбор качества и аудио - только если есть из чего выбирать, субтитры - если они есть
        // (загрузка субтитров из файла остается в боковом меню)
        viewModel.trackCounts.observe(viewLifecycleOwner) { updateControlButtonsVisibility() }

        viewModel.videoResolution.observe(viewLifecycleOwner) { updateVideoBadge() }
        viewModel.tunnelingActive.observe(viewLifecycleOwner) { updateVideoBadge() }
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
            ui.buttonSpeed.text = PlaybackSpeed.label(speed)
            val pos = viewModel.currentPosition.value ?: 0L
            updateTimeLabelsUI(pos)
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
            updateBufferDisplay(percent)
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
            updateStereoBadge()
        }

        // Обновление рендерера
        viewModel.swapEyes.observe(viewLifecycleOwner) { swap ->
            stereoRenderer?.setSwapEyes(swap)
            updateStereoBadge()
        }
        viewModel.outputMode.observe(viewLifecycleOwner) { mode ->
            stereoRenderer?.setOutputMode(mode)
            ui.updateStereoLayout(mode, viewModel.screenSeparation.value ?: 0f)
            updateStereoBadge()
        }
        viewModel.anaglyphType.observe(viewLifecycleOwner) { stereoRenderer?.setAnaglyphType(it) }
        viewModel.renderApiMode.observe(viewLifecycleOwner) { mode ->
            val forceLegacy = (mode == SettingsRepository.RENDER_API_GLES2)
            val applied = stereoRenderer?.setRenderApi(forceLegacy) ?: true
            if (!applied && settingsViewModel.isSettingsPanelVisible.value == true) {
                // Контекст ES 2.0 нельзя переключить на лету
                Toast.makeText(requireContext(), R.string.msg_render_api_restart, Toast.LENGTH_SHORT).show()
            }

            // Обновляем текст в OSD
            if (settingsViewModel.currentSettingType.value == SettingType.RENDER_API) {
                updateSettingsText()
            }
        }
        viewModel.singleFrameSize.observe(viewLifecycleOwner) { (w, h) ->
            stereoRenderer?.setSingleFrameDimensions(w, h)
            // Размер кадра пересчитывается при смене видео - уточняем Half/Full в бейдже
            updateStereoBadge()
        }
        viewModel.depth.observe(viewLifecycleOwner) { stereoRenderer?.setDepth(it) }
        viewModel.screenSeparation.observe(viewLifecycleOwner) {
            stereoRenderer?.setScreenSeparation(it)
            ui.updateStereoLayout(viewModel.outputMode.value, it)
        }

        // VR параметры через делегат
        viewModel.anaglyphDelegate.vrK1.observe(viewLifecycleOwner) { updateVrParams() }
        viewModel.anaglyphDelegate.vrK2.observe(viewLifecycleOwner) { updateVrParams() }
        viewModel.anaglyphDelegate.vrScale.observe(viewLifecycleOwner) { updateVrParams() }

        viewModel.anaglyphDelegate.currentMatrices.observe(viewLifecycleOwner) { (l, r) -> applyAnaglyphMatrices(l, r) }

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
                Toast.makeText(requireContext().applicationContext, msg, Toast.LENGTH_LONG).show()
                viewModel.clearToast()
            }
        }

        // === ИНТЕГРАЦИЯ AFR ===
        viewModel.afrTriggerEvent.observe(viewLifecycleOwner) { format ->
            val settingsRepo = SettingsRepository.getInstance(requireContext().applicationContext)

            if (settingsRepo.isFrameRateMatchingEnabled()) {

                // 1. Проверка на короткие видео (Skip Shorts)
                val durationMs = viewModel.player?.duration ?: C.TIME_UNSET
                if (settingsRepo.isAfrSkipShortsEnabled() && durationMs in 1..60000) {
                    return@observe
                }

                val afrHelper = AutoFrameRateHelper.instance(requireContext())

                // Применяем настройки пользователя
                afrHelper.setResolutionSwitchEnabled(settingsRepo.isAfrResolutionSwitchEnabled())
                afrHelper.setFpsCorrectionEnabled(settingsRepo.isAfrFpsCorrectionEnabled())
                afrHelper.setDoubleRefreshRateEnabled(settingsRepo.isAfrDoubleRefreshRateEnabled())
                afrHelper.setSkip24RateEnabled(settingsRepo.isAfrSkip24RateEnabled())

                // 2. Настраиваем слушатель для паузы
                afrHelper.setListener(object : DisplaySyncHelper.AutoFrameRateListener {
                    override fun onModeStart(newMode: DisplayHolder.Mode?) {
                        val pauseMs = settingsRepo.getAfrPauseMs()
                        if (pauseMs > 0) {
                            viewModel.player?.playWhenReady = false
                            Handler(Looper.getMainLooper()).postDelayed({
                                viewModel.player?.playWhenReady = true
                            }, pauseMs.toLong())
                        }
                    }
                    override fun onModeError(newMode: DisplayHolder.Mode?) {}
                    override fun onModeCancel() {}
                })

                // Обертка для совместимости с AFR
                val afrFormat = object : AfrFormatItem {
                    override fun getWidth() = format.width
                    override fun getHeight() = format.height
                    override fun getFrameRate() = format.frameRate
                }

                // 3. Применяем системный AFR
                afrHelper.apply(requireActivity(), afrFormat)

                // 4. Отправляем команду демону tvQuickActions
                TvQuickActions.sendStartAFR(requireContext(), format.frameRate, format.height)
            }
        }

        viewModel.isLive.observe(viewLifecycleOwner) {
            val pos = viewModel.currentPosition.value ?: 0L
            updateTimeLabelsUI(pos)
        }

        viewModel.playlistUiState.observe(viewLifecycleOwner) { state ->
            if (ui.isPlaylistDialogShowing) {
                updatePlaylistHeader(state)
                ui.updatePlaylistItems(state.items, viewModel.player?.currentMediaItem?.mediaId)
            }
        }

        viewModel.showResumeDialogEvent.observe(viewLifecycleOwner) { data ->
            if (data != null) {
                showResumeDialog(data.first, data.second)
            }
        }

        viewModel.sessionPrompt.observe(viewLifecycleOwner) { prompt ->
            if (prompt != null && sessionPromptDialog?.isShowing != true) showSessionPrompt(prompt)
        }
    }

    private var sessionPromptDialog: Dialog? = null

    /** Выключить проброс (туннелирование) до конца сеанса плеера или вернуть скорость 1x (прежний масштаб). */
    private fun showSessionPrompt(prompt: SessionPrompt) {
        timerController.stopControlsTimer()
        val dialog = Dialog(requireContext(), R.style.Theme_App_Dialog)
        dialog.setContentView(R.layout.dialog_session_prompt)
        dialog.setCanceledOnTouchOutside(false)
        val (title, message) = when (prompt) {
            SessionPrompt.SPEED_BITSTREAM -> R.string.session_speed_title to R.string.session_speed_message
            SessionPrompt.ZOOM_TUNNELING -> R.string.session_zoom_title to R.string.session_zoom_message
        }
        dialog.findViewById<TextView>(R.id.prompt_title).setText(title)
        dialog.findViewById<TextView>(R.id.prompt_message).setText(message)
        val dontAsk = dialog.findViewById<CheckBox>(R.id.prompt_dont_ask)
        val btnOk = dialog.findViewById<Button>(R.id.btn_prompt_ok)
        var answered = false
        fun answer(accept: Boolean) {
            if (answered) return
            answered = true
            viewModel.resolveSessionPrompt(prompt, accept, accept && dontAsk.isChecked)
            dialog.dismiss()
            timerController.resetControlsTimer()
        }
        btnOk.setOnClickListener { answer(true) }
        dialog.findViewById<Button>(R.id.btn_prompt_cancel).setOnClickListener { answer(false) }
        dialog.setOnCancelListener { answer(false) }
        dialog.setOnDismissListener { sessionPromptDialog = null }
        sessionPromptDialog = dialog
        dialog.show()
        btnOk.requestFocus()
    }

    private fun refreshInfoPanelData() {
        ui.videoTitleTextView.text = viewModel.videoTitle.value
        viewModel.player?.let { p ->
            val posterUri = p.mediaMetadata.artworkUri
            val index = p.currentMediaItemIndex
            val size = p.mediaItemCount

            // Получаем настройку
            val settingsRepo = SettingsRepository.getInstance(requireContext().applicationContext)
            val showIndex = settingsRepo.isShowPlaylistIndexEnabled()

            // Передаем в UI
            ui.loadPoster(posterUri, index, size, showIndex)
        }
    }

    private fun updateTimeLabelsUI(currentPos: Long) {
        val player = viewModel.player ?: return
        val duration = viewModel.duration.value ?: 0L
        val speed = viewModel.playbackSpeed.value ?: 1.0f
        val isLive = viewModel.isLive.value ?: false
        val liveOffset = player.currentLiveOffset

        ui.updateTimeLabels(currentPos, duration, speed, isLive, liveOffset)
    }

    private fun showResumeDialog(position: Long, duration: Long) {
        val timeStr = ui.formatTime(position)
        val totalStr = ui.formatTime(duration)

        timerController.stopControlsTimer()

        val dialog = Dialog(requireContext(), R.style.Theme_App_Dialog)
        dialog.setContentView(R.layout.dialog_resume)
        dialog.setCanceledOnTouchOutside(false)

        val messageView = dialog.findViewById<TextView>(R.id.resume_message)
        val btnResume = dialog.findViewById<Button>(R.id.btn_resume)
        val btnStartOver = dialog.findViewById<Button>(R.id.btn_start_over)

        messageView.text = getString(R.string.resume_dialog_msg, timeStr, totalStr)

        btnResume.setOnClickListener {
            viewModel.resumePlayback(position)
            dialog.dismiss()
            timerController.resetControlsTimer()
        }

        btnStartOver.setOnClickListener {
            viewModel.cancelResume()
            dialog.dismiss()
            timerController.resetControlsTimer()
        }

        dialog.setOnCancelListener {
            viewModel.cancelResume()
            timerController.resetControlsTimer()
        }

        dialog.show()

        // Фокус для Android TV
        btnResume.requestFocus()
    }

    // Переставленные линзы очков (например, голубая слева): изображение левого глаза - в цвет линзы B
    private fun applyAnaglyphMatrices(left: FloatArray, right: FloatArray) {
        val swapped = SettingsRepository.getInstance(requireContext().applicationContext).isAnaglyphLensSwapped()
        if (swapped) stereoRenderer?.setAnaglyphMatrices(right, left) else stereoRenderer?.setAnaglyphMatrices(left, right)
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
            SettingType.RENDER_API -> {
                if (viewModel.renderApiMode.value == SettingsRepository.RENDER_API_GLES2) getString(R.string.api_gles2)
                else getString(R.string.api_gles3)
            }
            SettingType.FILTER_MODE -> {
                viewModel.anaglyphType.value?.let { AnaglyphLogic.filterLabel(requireContext(), it) } ?: ""
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

    // "↳" - туннельный режим: видео выводится напрямую аппаратным декодером
    private fun updateVideoBadge() {
        val info = viewModel.videoResolution.value.orEmpty()
        ui.badgeResolution.text = if (viewModel.tunnelingActive.value == true) "$info ↳" else info
    }

    private fun updateStereoBadge() {
        val size = viewModel.videoSize
        ui.updateStereoBadge(
            StereoFormatLogic.badgeLabel(
                viewModel.inputType.value ?: StereoInputType.NONE,
                size?.width?.toFloat() ?: 0f,
                size?.height?.toFloat() ?: 0f,
                viewModel.outputMode.value,
                viewModel.swapEyes.value ?: false
            )
        )
    }

    private fun updateAudioBadge() {
        val track = viewModel.currentAudioTrack.value
        val res = track?.let { TrackLogic.buildTrackLabel(it, requireContext()) } ?: ""
        val audioOut = viewModel.audioOutputInfo.value ?: ""

        val text = if (audioOut.isNotEmpty()) getString(R.string.audio_badge_format, res, audioOut) else res
        ui.badgeAudio.text = text
    }

    private fun showPlaylist() {
        viewModel.stopZap()
        val initialState = viewModel.playlistUiState.value
        if (initialState == null || (viewModel.currentPlaylist.value ?: emptyList()).isEmpty()) {
            Toast.makeText(requireContext().applicationContext, getString(R.string.msg_playlist_empty), Toast.LENGTH_SHORT).show()
            return
        }

        timerController.stopControlsTimer()
        ui.hideControls()

        val settingsRepo = SettingsRepository.getInstance(requireContext().applicationContext)
        val showIndex = settingsRepo.isShowPlaylistIndexEnabled()
        val isM3u = viewModel.isM3uPlaylist.value == true
        var isSelectionMade = false

        ui.showPlaylistDialog(
            items = initialState.items,
            playingUuid = viewModel.player?.currentMediaItem?.mediaId,
            showIndexBadge = showIndex,
            shouldLoop = isM3u || viewModel.isLive.value == true,
            fitPosters = isM3u,
            isSingleVideo = (viewModel.player?.mediaItemCount ?: 0) <= 1,
            onItemSelected = { indexInList ->
                val item = viewModel.playlistUiState.value?.items?.getOrNull(indexInList)
                if (item != null) {
                    isSelectionMade = true
                    viewModel.playPlaylistItem(item.absoluteIndex)
                }
            },
            onDismiss = {
                // Группу сменили, но ничего не выбрали - возвращаем группу текущего элемента
                if (!isSelectionMade) viewModel.revertToPlayingGroup()
                timerController.resetControlsTimer()
            }
        )

        val hasGroups = initialState.availableGroups.isNotEmpty()
        updatePlaylistHeader(initialState)
        // Выбор группы: клик по заголовку или Влево/Вправо в списке
        ui.setPlaylistHeaderClickListener(if (hasGroups) ({ showGroupSelectionDialog() }) else null)
        ui.setPlaylistKeyListener { keyCode, event ->
            if (hasGroups && event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { viewModel.cycleGroup(-1); return@setPlaylistKeyListener true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { viewModel.cycleGroup(1); return@setPlaylistKeyListener true }
                }
            }
            false
        }
    }

    private fun updatePlaylistHeader(state: PlaylistUiState) {
        if (state.availableGroups.isEmpty()) {
            ui.updatePlaylistHeader(getString(R.string.playlist))
        } else {
            val groupName = state.groupName ?: getString(R.string.group_all)
            ui.updatePlaylistHeader("$groupName [${state.items.size}]")
        }
    }

    private fun showGroupSelectionDialog() {
        val state = viewModel.playlistUiState.value ?: return
        if (state.availableGroups.isEmpty()) return

        val groups = listOf<String?>(null) + state.availableGroups // null = Все
        val currentIndex = groups.indexOf(state.groupName).coerceAtLeast(0)
        val displayGroups = groups.map { it ?: getString(R.string.group_all) }

        val dialog = Dialog(requireContext(), R.style.Theme_App_Dialog)
        dialog.setContentView(R.layout.dialog_list_choice)
        dialog.findViewById<TextView>(R.id.dialog_title).text = getString(R.string.select_group)

        val recycler = dialog.findViewById<RecyclerView>(R.id.dialog_recycler_view)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = ChoiceAdapter(displayGroups, currentIndex) { selectedIdx ->
            viewModel.selectGroup(groups[selectedIdx])
            dialog.dismiss()
        }
        dialog.show()

        recycler.post {
            (recycler.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(currentIndex, 0)
            recycler.postDelayed({ recycler.findViewHolderForAdapterPosition(currentIndex)?.itemView?.requestFocus() }, 50)
        }
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

    /** Видимость кнопок нижней панели: доступность функции и выбор пользователя в настройках. */
    private fun updateControlButtonsVisibility() {
        fun shown(key: String) = key !in hiddenControlButtons
        // До подсчета дорожек кнопки выбора остаются видимыми, как в разметке
        val counts = viewModel.trackCounts.value
        ui.buttonSpeed.isVisible = shown(SettingsRepository.BUTTON_SPEED)
        ui.buttonResize.isVisible = shown(SettingsRepository.BUTTON_RESIZE)
        ui.buttonPip.isVisible = shown(SettingsRepository.BUTTON_PIP) &&
            (activity as? PlayerActivity)?.supportsPictureInPicture == true
        ui.buttonQuality.isVisible = shown(SettingsRepository.BUTTON_QUALITY) && (counts == null || counts.video > 1)
        ui.buttonAudio.isVisible = shown(SettingsRepository.BUTTON_AUDIO) && (counts == null || counts.audio > 1)
        ui.buttonSubs.isVisible = shown(SettingsRepository.BUTTON_SUBTITLES) && (counts == null || counts.subtitles > 0)
    }

    private fun shouldAskExitConfirmation(): Boolean {
        val settingsRepo = SettingsRepository.getInstance(requireContext().applicationContext)
        if (!settingsRepo.isConfirmExitEnabled()) return false
        return SystemClock.elapsedRealtime() - lastExitBackPressMs > EXIT_CONFIRM_WINDOW_MS
    }

    private fun setupBackPressedHandler() {
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (settingsViewModel.isSettingsPanelVisible.value == true) {
                    settingsViewModel.closePanel()
                    viewModel.restoreSettings()
                } else if (ui.controlsView.isVisible) {
                    ui.hideControls()
                } else if (shouldAskExitConfirmation()) {
                    // Первое нажатие только предупреждает, выход - повторным нажатием
                    lastExitBackPressMs = SystemClock.elapsedRealtime()
                    Toast.makeText(requireContext().applicationContext, R.string.press_back_exit, Toast.LENGTH_SHORT).show()
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

    override fun onResume() {
        super.onResume()
        // Обновляем настройку свайпа при возврате на экран
        val settingsRepo = SettingsRepository.getInstance(requireContext().applicationContext)
        swipeAction = settingsRepo.getHorizontalSwipeAction()
        swipeEdgeExclusion = settingsRepo.isSwipeEdgeExclusionEnabled()
        verticalGesturesEnabled = settingsRepo.isVolumeBrightnessGesturesEnabled()
        hiddenControlButtons = settingsRepo.getHiddenControlButtons()
        ui.buttonBack.isVisible = settingsRepo.isBackButtonEnabled()
        updateControlButtonsVisibility()

        // Обновляем видимость часов и стиль субтитров (на случай, если изменили в настройках)
        ui.updateClockTransparency(settingsRepo.getClockTransparency())
        ui.applySubtitleStyle(settingsRepo.getSubtitleStyle())
        // Калибровка VR и анаглифа могла измениться в визарде
        viewModel.reloadCalibrationIfChanged()
        applyBufferDisplayMode(settingsRepo.getBufferDisplayMode())

        // Проверяем, нужно ли перезапустить плеер из-за смены настроек
        viewModel.checkSettingsAndRestart()
    }

    override fun onStart() {
        super.onStart()
        // Возобновляем, только если видео играло при уходе с экрана (или это новый плейлист).
        // Пауза снимается в onStop, а не в onPause, чтобы видео продолжало играть в PiP.
        // Плеер остановлен в onStop ради HDMI-выхода: готовим заново с той же позиции
        if (viewModel.stoppedInBackground) {
            viewModel.stoppedInBackground = false
            viewModel.player?.prepare()
        }
        if (viewModel.resumePlayWhenReady) {
            viewModel.player?.play()
        }
        if (viewModel.inputType.value != StereoInputType.NONE) {
            ui.glSurfaceView.onResume()
        }
    }

    override fun onPause() {
        super.onPause()
        viewModel.saveCurrentSettings()
    }

    override fun onStop() {
        viewModel.player?.let { player ->
            viewModel.resumePlayWhenReady = player.playWhenReady
            player.pause()
            // Битстрим на HDMI в системе один: плеер в фоне держит его, и следующий
            // запуск (например, из Lampa) получает ошибку AudioTrack. Освобождаем, позиция сохраняется.
            // При декодированном PCM поведение прежнее: только пауза.
            if (viewModel.isDirectAudioOutput) {
                player.stop()
                viewModel.stoppedInBackground = true
            }
        }
        viewModel.saveCurrentSettings()
        ui.glSurfaceView.onPause()
        super.onStop()
    }

    fun setPictureInPictureMode(enabled: Boolean) {
        inPictureInPicture = enabled
        if (_ui == null) return
        if (enabled) {
            dimJob?.cancel()
            timerController.stopControlsTimer()
            settingsViewModel.closePanel()
            sideMenuDialog?.dismiss()
            zoomDialog?.dismiss()
        }
        ui.setPictureInPictureMode(enabled)
        if (!enabled) {
            resetDimTimer()
            timerController.resetControlsTimer()
        }
    }

    override fun onDestroyView() {
        // Сначала отвязываем поверхность от плеера
        viewModel.player?.setVideoSurface(null)
        // Вопрос о сеансе остается в ViewModel и покажется снова в новом экране
        sessionPromptDialog?.dismiss()

        // Останавливаем таймеры
        timerController.cleanup()
        inputHandler.cleanup()
        doubleTapResetHandler.removeCallbacksAndMessages(null)

        // Освобождаем GL ресурсы
        stereoRenderer?.release()
        stereoRenderer = null
        glSurface = null

        // Восстанавливаем оригинальный режим ТВ при выходе
        activity?.let {
            val afrHelper = AutoFrameRateHelper.instance(requireContext())
            afrHelper.restoreOriginalState(it)
            afrHelper.setListener(null)
            // Останавливаем tvQuickActions
            TvQuickActions.sendStopAFR(it)
        }

        _ui = null
        super.onDestroyView()
    }

    companion object {
        private const val EXIT_CONFIRM_WINDOW_MS = 2000L

        fun newInstance() = PlayerFragment()
    }
}