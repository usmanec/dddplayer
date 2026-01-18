package top.rootu.dddplayer.ui

import android.graphics.Color
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.media3.common.util.UnstableApi
import top.rootu.dddplayer.BuildConfig
import top.rootu.dddplayer.R
import top.rootu.dddplayer.model.StereoInputType
import top.rootu.dddplayer.renderer.OnFpsUpdatedListener
import top.rootu.dddplayer.renderer.OnSurfaceReadyListener
import top.rootu.dddplayer.renderer.StereoGLSurfaceView
import top.rootu.dddplayer.renderer.StereoRenderer
import top.rootu.dddplayer.ui.controller.PlayerInputHandler
import top.rootu.dddplayer.ui.controller.PlayerTimerController
import top.rootu.dddplayer.ui.controller.PlayerUiController
import top.rootu.dddplayer.viewmodel.PlayerViewModel
import top.rootu.dddplayer.viewmodel.SettingType
import java.util.Calendar
import java.util.Locale

@UnstableApi
class PlayerFragment : Fragment(), OnSurfaceReadyListener, OnFpsUpdatedListener {

    private val viewModel: PlayerViewModel by activityViewModels()
    private var stereoRenderer: StereoRenderer? = null

    // Контроллеры
    private lateinit var ui: PlayerUiController
    private lateinit var inputHandler: PlayerInputHandler
    private lateinit var timerController: PlayerTimerController

    // Храним ссылку на GL Surface
    private var glSurface: Surface? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.player_fragment, container, false)

        ui = PlayerUiController(view)

        timerController = PlayerTimerController(
            onHideControls = { ui.hideControls() },
            onHideSettings = {
                if (viewModel.isSettingsPanelVisible.value == true) {
                    viewModel.closeSettingsPanel(true)
                    view.findViewById<View>(R.id.root_container)?.requestFocus()
                }
            },
            clockView = ui.textClock
        )

        inputHandler = PlayerInputHandler(
            viewModel, ui,
            onShowControls = { ui.showControls(); timerController.resetControlsTimer() },
            onHideControls = { ui.hideControls() },
            onResetHideTimer = { timerController.resetControlsTimer() },
            onShowPlaylist = { showPlaylist() }
        )

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
    }

    fun handleKeyEvent(event: KeyEvent): Boolean {
        if (viewModel.isSettingsPanelVisible.value == true) timerController.resetSettingsTimer()
        val handled = inputHandler.handleKeyEvent(event, activity?.currentFocus)
        if (handled && viewModel.isUserInteracting) {
            view?.postDelayed({ viewModel.isUserInteracting = false }, 500)
        }
        return handled
    }

    private fun setupControls() {
        // Навигация в настройках
        ui.btnSettingsPrev.setOnClickListener { viewModel.onMenuLeft(); timerController.resetSettingsTimer() }
        ui.btnSettingsNext.setOnClickListener { viewModel.onMenuRight(); timerController.resetSettingsTimer() }
        ui.titleContainer.setOnClickListener { viewModel.onMenuDown(); timerController.resetSettingsTimer() }
        ui.topSettingsPanel.setOnClickListener { timerController.resetSettingsTimer() }

        // Основные клики
        ui.touchZoneTop.setOnClickListener { if (viewModel.isSettingsPanelVisible.value != true) viewModel.openSettingsPanel() }
        view?.findViewById<View>(R.id.root_container)?.setOnClickListener {
            if (viewModel.isSettingsPanelVisible.value == true) {
                viewModel.closeSettingsPanel(save = true)
                it.requestFocus()
            } else if (ui.controlsView.isVisible) ui.hideControls() else ui.showControls()
        }
        ui.controlsView.setOnClickListener { timerController.resetControlsTimer() }

        // Кнопки плеера
        ui.playPauseButton.setOnClickListener { viewModel.togglePlayPause() }
        ui.rewindButton.setOnClickListener { viewModel.seekBack() }
        ui.ffwdButton.setOnClickListener { viewModel.seekForward() }
        ui.prevButton.setOnClickListener { viewModel.prevTrack() }
        ui.nextButton.setOnClickListener { viewModel.nextTrack() }

        ui.buttonSettings.setOnClickListener { ui.hideControls(); viewModel.openSettingsPanel() }
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

        viewModel.updateInfo.observe(viewLifecycleOwner) { info ->
            if (info != null) {
                ui.buttonUpdate.text = "Обновить до ${info.version}"
                ui.buttonUpdate.alpha = 1.0f
                ui.buttonUpdate.setOnClickListener {
                    ui.showUpdateDialog(info, { viewModel.startUpdate() }, {})
                }
            } else {
                ui.buttonUpdate.text = "v${BuildConfig.VERSION_NAME}"
                ui.buttonUpdate.alpha = 0.5f // Тусклая
                ui.buttonUpdate.setOnClickListener {
                    viewModel.forceCheckUpdates()
                }
            }
            ui.buttonUpdate.isVisible = true // Всегда видна
        }

        viewModel.isCheckingUpdates.observe(viewLifecycleOwner) { checking ->
            if (checking) {
                ui.buttonUpdate.text = "Проверка..."
                ui.buttonUpdate.isEnabled = false
            } else {
                ui.buttonUpdate.isEnabled = true
                // Текст обновится через updateInfo observer
            }
        }

        viewModel.downloadProgress.observe(viewLifecycleOwner) { progress ->
            ui.updateDownloadProgress(progress)
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
            // Обновляем постер из метаданных плеера
            val posterUri = viewModel.player.mediaMetadata.artworkUri
            ui.loadPoster(posterUri)
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
        viewModel.currentAudioName.observe(viewLifecycleOwner) { ui.badgeAudio.text = it }
        viewModel.currentSubtitleName.observe(viewLifecycleOwner) { ui.badgeSubtitle.text = it }

        viewModel.isBuffering.observe(viewLifecycleOwner) {
            ui.updateBufferingState(it, viewModel.outputMode.value)
        }

        // Переключение поверхностей (HDR Fix)
        viewModel.inputType.observe(viewLifecycleOwner) { type ->
            val isStereo = type != StereoInputType.NONE
            ui.setSurfaceMode(isStereo)
            if (isStereo) {
                ui.glSurfaceView.onResume()
                stereoRenderer?.setInputType(type)
                if (glSurface != null) viewModel.player.setVideoSurface(glSurface)
            } else {
                ui.glSurfaceView.onPause()
                viewModel.player.setVideoSurfaceView(ui.standardSurfaceView)
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

        // VR параметры
        viewModel.vrK1.observe(viewLifecycleOwner) { updateVrParams() }
        viewModel.vrK2.observe(viewLifecycleOwner) { updateVrParams() }
        viewModel.vrScale.observe(viewLifecycleOwner) { updateVrParams() }

        viewModel.currentMatrices.observe(viewLifecycleOwner) { (l, r) -> stereoRenderer?.setAnaglyphMatrices(l, r) }

        // Панель настроек
        viewModel.isSettingsPanelVisible.observe(viewLifecycleOwner) { isVisible ->
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
        viewModel.currentSettingType.observe(viewLifecycleOwner, updateTextObserver)
        viewModel.inputType.observe(viewLifecycleOwner, updateTextObserver)
        viewModel.outputMode.observe(viewLifecycleOwner, updateTextObserver)
        viewModel.anaglyphType.observe(viewLifecycleOwner, updateTextObserver)
        viewModel.swapEyes.observe(viewLifecycleOwner, updateTextObserver)
        viewModel.depth.observe(viewLifecycleOwner, updateTextObserver)
        viewModel.screenSeparation.observe(viewLifecycleOwner, updateTextObserver)
        viewModel.currentAudioName.observe(viewLifecycleOwner, updateTextObserver)
        viewModel.currentSubtitleName.observe(viewLifecycleOwner, updateTextObserver)
        viewModel.customHueOffsetL.observe(viewLifecycleOwner, updateTextObserver)
        viewModel.customHueOffsetR.observe(viewLifecycleOwner, updateTextObserver)
        viewModel.customLeakL.observe(viewLifecycleOwner, updateTextObserver)
        viewModel.customLeakR.observe(viewLifecycleOwner, updateTextObserver)
        viewModel.customSpaceLms.observe(viewLifecycleOwner, updateTextObserver)
        viewModel.isMatrixValid.observe(viewLifecycleOwner, updateTextObserver)

        viewModel.cues.observe(viewLifecycleOwner) { cues ->
            ui.subtitleView.setCues(cues)
            ui.subtitleViewLeft.setCues(cues)
            ui.subtitleViewRight.setCues(cues)
        }

        viewModel.toastMessage.observe(viewLifecycleOwner) { msg ->
            if (msg != null) {
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                viewModel.clearToastMessage()
            }
        }
    }

    private fun updateVrParams() {
        stereoRenderer?.setDistortion(
            viewModel.vrK1.value ?: 0.34f,
            viewModel.vrK2.value ?: 0.10f,
            viewModel.vrScale.value ?: 1.2f
        )
        updateSettingsText()
    }

    private fun updateSettingsText() {
        if (viewModel.isSettingsPanelVisible.value != true) return
        val type = viewModel.currentSettingType.value ?: return

        val valueStr = when(type) {
            SettingType.VIDEO_TYPE -> viewModel.inputType.value?.name?.replace("_", " ") ?: ""
            SettingType.OUTPUT_FORMAT -> viewModel.outputMode.value?.name?.replace("_", " ") ?: ""
            SettingType.GLASSES_TYPE -> getGlassesGroupName(viewModel.anaglyphType.value!!)
            SettingType.FILTER_MODE -> {
                val name = viewModel.anaglyphType.value?.name ?: ""
                if (name.endsWith("_CUSTOM")) "Custom" else name
            }
            SettingType.CUSTOM_HUE_L -> {
                val offset = viewModel.customHueOffsetL.value ?: 0
                val color = viewModel.calculatedColorL.value ?: Color.WHITE
                "$offset (${String.format("#%06X", (0xFFFFFF and color))})"
            }
            SettingType.CUSTOM_HUE_R -> {
                val offset = viewModel.customHueOffsetR.value ?: 0
                val color = viewModel.calculatedColorR.value ?: Color.WHITE
                "$offset (${String.format("#%06X", (0xFFFFFF and color))})"
            }
            SettingType.CUSTOM_LEAK_L -> "${(viewModel.customLeakL.value!! * 100).toInt()}%"
            SettingType.CUSTOM_LEAK_R -> "${(viewModel.customLeakR.value!! * 100).toInt()}%"
            SettingType.CUSTOM_SPACE -> if (viewModel.customSpaceLms.value == true) "LMS" else "XYZ"
            SettingType.SWAP_EYES -> if (viewModel.swapEyes.value == true) "R - L" else "L - R"
            SettingType.DEPTH_3D -> viewModel.depth.value.toString()
            SettingType.SCREEN_SEPARATION -> String.format("%.1f", (viewModel.screenSeparation.value ?: 0f) * 100)
            SettingType.VR_DISTORTION -> String.format("%.2f", viewModel.vrK1.value)
            SettingType.VR_ZOOM -> String.format("%.2f", viewModel.vrScale.value)
            SettingType.AUDIO_TRACK -> viewModel.currentAudioName.value ?: ""
            SettingType.SUBTITLES -> viewModel.currentSubtitleName.value ?: ""
        }

        val color = when(type) {
            SettingType.CUSTOM_HUE_L -> viewModel.calculatedColorL.value ?: Color.WHITE
            SettingType.CUSTOM_HUE_R -> viewModel.calculatedColorR.value ?: Color.WHITE
            else -> Color.WHITE
        }

        ui.updateSettingsText(type, valueStr, viewModel.isMatrixValid.value ?: true, color)

        // список опций
        val optionsData = viewModel.getOptionsForCurrentSetting()
        ui.updateSettingsOptions(optionsData)
    }

    private fun getGlassesGroupName(type: StereoRenderer.AnaglyphType): String {
        return when {
            type.name.startsWith("RC_") -> "Red - Cyan"
            type.name.startsWith("YB_") -> "Yellow - Blue"
            type.name.startsWith("GM_") -> "Green - Magenta"
            type.name.startsWith("RB_") -> "Red - Blue"
            else -> "Unknown"
        }
    }

    private fun showPlaylist() {
        val playlist = viewModel.currentPlaylist.value ?: emptyList()
        if (playlist.isEmpty()) {
            Toast.makeText(context, "Плейлист пуст", Toast.LENGTH_SHORT).show()
            return
        }

        timerController.stopControlsTimer()
        ui.hideControls()

        ui.showPlaylistDialog(
            items = playlist,
            currentIndex = viewModel.player.currentMediaItemIndex,
            onItemSelected = { index ->
                viewModel.seekTo(0)
                viewModel.player.seekToDefaultPosition(index)
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
                if (viewModel.isSettingsPanelVisible.value == true) {
                    viewModel.closeSettingsPanel(save = false)
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
        if (viewModel.inputType.value != StereoInputType.NONE) {
            activity?.runOnUiThread { viewModel.player.setVideoSurface(surface) }
        }
    }

    override fun onFpsUpdated(fps: Int) {
        activity?.runOnUiThread { ui.fpsCounterTextView.text = "FPS: $fps" }
    }

    override fun onResume() {
        super.onResume()
        viewModel.player.playWhenReady = true
        if (viewModel.inputType.value != StereoInputType.NONE) {
            ui.glSurfaceView.onResume()
        }
    }

    override fun onPause() {
        super.onPause()
        viewModel.player.playWhenReady = false
        ui.glSurfaceView.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        timerController.cleanup()
        inputHandler.cleanup()
        viewModel.player.setVideoSurface(null)
        stereoRenderer?.release()
        stereoRenderer = null
        glSurface = null
    }

    companion object {
        fun newInstance() = PlayerFragment()
    }
}