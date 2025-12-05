package top.rootu.dddplayer.ui

import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.SubtitleView
import top.rootu.dddplayer.R
import top.rootu.dddplayer.model.StereoOutputMode
import top.rootu.dddplayer.renderer.OnFpsUpdatedListener
import top.rootu.dddplayer.renderer.OnSurfaceReadyListener
import top.rootu.dddplayer.renderer.StereoGLSurfaceView
import top.rootu.dddplayer.renderer.StereoRenderer
import top.rootu.dddplayer.viewmodel.PlayerViewModel
import top.rootu.dddplayer.viewmodel.SettingType
import java.util.Locale
import java.util.Calendar

@UnstableApi
class PlayerFragment : Fragment(), OnSurfaceReadyListener, OnFpsUpdatedListener {

    private val viewModel: PlayerViewModel by activityViewModels()
    private lateinit var glSurfaceView: StereoGLSurfaceView
    private var stereoRenderer: StereoRenderer? = null

    // --- UI State ---
    private var isPanelExpanded = false
    private val hideControlsHandler = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable { hideControls() }
    private var isUserSeeking = false

    private val settingsHideHandler = Handler(Looper.getMainLooper())
    private val settingsHideRunnable = Runnable {
        if (viewModel.isSettingsPanelVisible.value == true) {
            viewModel.closeSettingsPanel(true)
            // Возвращаем фокус на контейнер
            rootContainer.requestFocus()
        }
    }

    // Clock Handler
    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClock()
            clockHandler.postDelayed(this, 10000) // Update every 10 sec
        }
    }

    // --- Views ---
    private lateinit var rootContainer: View
    private lateinit var touchZoneTop: View

    // Субтитры
    private lateinit var subtitleView: SubtitleView
    private lateinit var subtitleSplitContainer: View
    private lateinit var subtitleViewLeft: SubtitleView
    private lateinit var subtitleViewRight: SubtitleView

    // Panels
    private lateinit var controlsView: View
    private lateinit var topInfoPanel: View
    private lateinit var topSettingsPanel: View

    // Info Panel Views
    private lateinit var videoTitleTextView: TextView
    private lateinit var textClock: TextView
    private lateinit var iconInputMode: android.widget.ImageView
    private lateinit var iconOutputMode: android.widget.ImageView
    private lateinit var iconSwapEyes: android.widget.ImageView
    private lateinit var badgeResolution: TextView
    private lateinit var badgeAudio: TextView
    private lateinit var badgeSubtitle: TextView

    // Controls Views
    private lateinit var playPauseButton: ImageButton
    private lateinit var rewindButton: ImageButton
    private lateinit var ffwdButton: ImageButton
    private lateinit var prevButton: ImageButton
    private lateinit var nextButton: ImageButton
    private lateinit var seekBar: SeekBar
    private lateinit var timeCurrentTextView: TextView
    private lateinit var timeDurationTextView: TextView

    private lateinit var buttonQuality: TextView
    private lateinit var buttonPlaylist: ImageButton
    private lateinit var buttonSettings: ImageButton

    // Other
    private lateinit var fpsCounterTextView: TextView
    private lateinit var bufferingIndicator: ProgressBar
    private lateinit var bufferingSplitContainer: View
    private lateinit var loaderLeft: ProgressBar
    private lateinit var loaderRight: ProgressBar

    // Settings Panel Views
    private lateinit var titleContainer: View
    private lateinit var settingTitle: TextView
    private lateinit var settingValue: TextView
    private lateinit var btnSettingsPrev: View
    private lateinit var btnSettingsNext: View

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.player_fragment, container, false)
        bindViews(view)
        glSurfaceView.setEGLContextClientVersion(2)
        stereoRenderer = StereoRenderer(glSurfaceView, this, this)
        glSurfaceView.setRenderer(stereoRenderer)
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        return view
    }

    private fun bindViews(view: View) {
        rootContainer = view.findViewById(R.id.root_container)
        touchZoneTop = view.findViewById(R.id.touch_zone_top)

        subtitleView = view.findViewById(R.id.subtitle_view)
        subtitleSplitContainer = view.findViewById(R.id.subtitle_split_container)
        subtitleViewLeft = view.findViewById(R.id.subtitle_view_left)
        subtitleViewRight = view.findViewById(R.id.subtitle_view_right)

        glSurfaceView = view.findViewById(R.id.gl_surface_view)
        fpsCounterTextView = view.findViewById(R.id.fps_counter)

        bufferingIndicator = view.findViewById(R.id.buffering_indicator)
        bufferingSplitContainer = view.findViewById(R.id.buffering_split_container)
        loaderLeft = view.findViewById(R.id.loader_left)
        loaderRight = view.findViewById(R.id.loader_right)

        // Panels
        controlsView = view.findViewById(R.id.playback_controls)
        topInfoPanel = view.findViewById(R.id.top_info_panel)
        topSettingsPanel = view.findViewById(R.id.top_settings_panel)

        // Info Panel
        videoTitleTextView = topInfoPanel.findViewById(R.id.video_title)
        textClock = topInfoPanel.findViewById(R.id.text_clock)
        iconInputMode = topInfoPanel.findViewById(R.id.icon_input_mode)
        iconOutputMode = topInfoPanel.findViewById(R.id.icon_output_mode)
        iconSwapEyes = topInfoPanel.findViewById(R.id.icon_swap_eyes)
        badgeResolution = topInfoPanel.findViewById(R.id.badge_resolution)
        badgeAudio = topInfoPanel.findViewById(R.id.badge_audio)
        badgeSubtitle = topInfoPanel.findViewById(R.id.badge_subtitle)

        // Controls
        playPauseButton = controlsView.findViewById(R.id.button_play_pause)
        rewindButton = controlsView.findViewById(R.id.button_rewind)
        ffwdButton = controlsView.findViewById(R.id.button_ffwd)
        prevButton = controlsView.findViewById(R.id.button_prev)
        nextButton = controlsView.findViewById(R.id.button_next)
        seekBar = controlsView.findViewById(R.id.seek_bar)
        timeCurrentTextView = controlsView.findViewById(R.id.time_current)
        timeDurationTextView = controlsView.findViewById(R.id.time_duration)

        buttonQuality = controlsView.findViewById(R.id.button_quality)
        buttonPlaylist = controlsView.findViewById(R.id.button_playlist)
        buttonSettings = controlsView.findViewById(R.id.button_settings)

        // Settings Panel
        titleContainer = topSettingsPanel.findViewById(R.id.title_container)
        settingTitle = topSettingsPanel.findViewById(R.id.setting_title)
        settingValue = topSettingsPanel.findViewById(R.id.setting_value)
        btnSettingsPrev = topSettingsPanel.findViewById(R.id.btn_settings_prev)
        btnSettingsNext = topSettingsPanel.findViewById(R.id.btn_settings_next)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.requestFocus()
        setupControls()
        observeViewModel()
        setupBackPressedHandler()

        clockHandler.post(clockRunnable)
    }

    fun handleKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false

        // 1. Навигация в настройках
        if (viewModel.isSettingsPanelVisible.value == true) {
            resetSettingsHideTimer()
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    viewModel.onMenuUp()
                    return true
                }

                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    viewModel.onMenuDown()
                    return true
                }

                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    viewModel.onMenuLeft()
                    return true
                }

                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    viewModel.onMenuRight()
                    return true
                }

                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    viewModel.closeSettingsPanel(save = true)
                    rootContainer.requestFocus()
                    return true
                }

                KeyEvent.KEYCODE_BACK -> {
                    viewModel.closeSettingsPanel(save = false)
                    rootContainer.requestFocus()
                    return true
                }
            }
            return true
        }

        // 2. Навигация в плеере
        if (controlsView.isVisible) resetHideTimer()
        val currentFocus = activity?.currentFocus

        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (!controlsView.isVisible) {
                    showControls()
                    return true
                }
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (!controlsView.isVisible) {
                    showControls()
                    return true
                }
                // Если фокус на кнопках управления, нажатие вниз открывает плейлист
                if (currentFocus?.id in listOf(
                        R.id.button_play_pause,
                        R.id.button_rewind,
                        R.id.button_ffwd,
                        R.id.button_prev,
                        R.id.button_next
                    )
                ) {
                    showPlaylist()
                    return true
                }
            }

            KeyEvent.KEYCODE_DPAD_UP -> {
                if (controlsView.isVisible && currentFocus?.id == R.id.seek_bar) {
                    hideControls()
                    return true
                } else if (!controlsView.isVisible) {
                    // Если панели скрыты, вверх открывает настройки
                    viewModel.openSettingsPanel()
                    return true
                }
            }

            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (!controlsView.isVisible) {
                    showControls(focusOnSeekBar = true)
                    return false
                }

                // Если фокус на SeekBar, делаем быструю перемотку (0.5% от длины)
                if (currentFocus?.id == R.id.seek_bar) {
                    val duration = viewModel.duration.value ?: 0L
                    if (duration > 0) {
                        val step = duration / 200 // 0.5% шаг
                        val current = seekBar.progress.toLong()
                        val target = if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT)
                            (current - step).coerceAtLeast(0)
                        else
                            (current + step).coerceAtMost(duration)

                        // Мгновенно обновляем UI и плеер
                        viewModel.isUserInteracting = true
                        seekBar.progress = target.toInt()
                        timeCurrentTextView.text = formatTime(target)
                        viewModel.seekTo(target)

                        // Сбрасываем флаг через небольшую задержку
                        hideControlsHandler.postDelayed(
                            { viewModel.isUserInteracting = false },
                            500
                        )
                    }
                    resetHideTimer()
                    return true
                }

                // Если фокус на кнопках, возвращаем false (стандартная навигация)
                return false
            }

            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                viewModel.togglePlayPause()
                showControls()
                return true
            }

            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                viewModel.nextTrack()
                showControls()
                return true
            }

            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                viewModel.prevTrack()
                showControls()
                return true
            }
        }
        return false
    }

    private fun setupControls() {
        // Settings Panel
        btnSettingsPrev.setOnClickListener { viewModel.onMenuLeft(); resetSettingsHideTimer() }
        btnSettingsNext.setOnClickListener { viewModel.onMenuRight(); resetSettingsHideTimer() }
        titleContainer.setOnClickListener { viewModel.onMenuDown(); resetSettingsHideTimer() }
        topSettingsPanel.setOnClickListener { resetSettingsHideTimer() }

        // Touch Zones
        touchZoneTop.setOnClickListener { if (viewModel.isSettingsPanelVisible.value != true) viewModel.openSettingsPanel() }
        rootContainer.setOnClickListener {
            if (viewModel.isSettingsPanelVisible.value == true) {
                viewModel.closeSettingsPanel(save = true)
                rootContainer.requestFocus()
            } else if (controlsView.isVisible) hideControls() else showControls()
        }
        controlsView.setOnClickListener { resetHideTimer() }

        // Playback Controls
        playPauseButton.setOnClickListener { viewModel.togglePlayPause() }
        rewindButton.setOnClickListener { viewModel.seekBack() }
        ffwdButton.setOnClickListener { viewModel.seekForward() }
        prevButton.setOnClickListener { viewModel.prevTrack() }
        nextButton.setOnClickListener { viewModel.nextTrack() }

        // Right Controls
        buttonSettings.setOnClickListener {
            hideControls()
            viewModel.openSettingsPanel()
        }
        buttonPlaylist.setOnClickListener { showPlaylistToast() }
        buttonQuality.setOnClickListener { showQualityPopup() }

        // SeekBar
        seekBar.setOnKeyListener { _, keyCode, event ->
            // Этот код может не вызываться на TV, если handleKeyEvent перехватит раньше
            return@setOnKeyListener false
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) timeCurrentTextView.text = formatTime(progress.toLong())
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = true
                viewModel.isUserInteracting = true
                hideControlsHandler.removeCallbacks(hideControlsRunnable)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.progress?.let { viewModel.player.seekTo(it.toLong()) }
                isUserSeeking = false
                viewModel.isUserInteracting = false
                resetHideTimer()
            }
        })
    }

    private fun observeViewModel() {
        viewModel.isPlaying.observe(viewLifecycleOwner) { isPlaying ->
            val iconRes = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
            playPauseButton.setImageResource(iconRes)
        }
        viewModel.duration.observe(viewLifecycleOwner) { duration ->
            seekBar.max = duration.toInt()
            timeDurationTextView.text = formatTime(duration)
        }
        viewModel.currentPosition.observe(viewLifecycleOwner) { position ->
            if (!isUserSeeking && !viewModel.isUserInteracting) {
                seekBar.progress = position.toInt()
                timeCurrentTextView.text = formatTime(position)
            }
        }
        viewModel.videoTitle.observe(viewLifecycleOwner) { title ->
            videoTitleTextView.text = title
        }

        viewModel.hasPrevious.observe(viewLifecycleOwner) { hasPrev ->
            prevButton.alpha = if (hasPrev) 1.0f else 0.3f
            prevButton.isEnabled = hasPrev
        }
        viewModel.hasNext.observe(viewLifecycleOwner) { hasNext ->
            nextButton.alpha = if (hasNext) 1.0f else 0.3f
            nextButton.isEnabled = hasNext
        }

        // Playlist Button Visibility
        viewModel.playlistSize.observe(viewLifecycleOwner) { size ->
            buttonPlaylist.isVisible = size > 1
        }

        // Quality Button Text
        viewModel.currentQualityName.observe(viewLifecycleOwner) { name ->
            buttonQuality.text = name
        }

        // Info Panel Badges
        viewModel.videoResolution.observe(viewLifecycleOwner) { res -> badgeResolution.text = res }
        viewModel.currentAudioName.observe(viewLifecycleOwner) { name -> badgeAudio.text = name }
        viewModel.currentSubtitleName.observe(viewLifecycleOwner) { name ->
            badgeSubtitle.text = name
        }

        viewModel.isBuffering.observe(viewLifecycleOwner) { isBuffering ->
            val mode = viewModel.outputMode.value
            if (isBuffering) {
                if (mode == StereoOutputMode.CARDBOARD_VR) {
                    bufferingIndicator.isVisible = false
                    bufferingSplitContainer.isVisible = true
                } else {
                    bufferingIndicator.isVisible = true
                    bufferingSplitContainer.isVisible = false
                }
            } else {
                bufferingIndicator.isVisible = false
                bufferingSplitContainer.isVisible = false
            }
        }

        viewModel.swapEyes.observe(viewLifecycleOwner) { swap ->
            stereoRenderer?.setSwapEyes(swap)
            iconSwapEyes.alpha = if (swap) 1.0f else 0.3f
        }
        viewModel.inputType.observe(viewLifecycleOwner) { type ->
            stereoRenderer?.setInputType(type)
            // todo Update icon based on type (simplified logic)
            // iconInputMode.setImageResource(...)
        }
        viewModel.outputMode.observe(viewLifecycleOwner) { mode ->
            stereoRenderer?.setOutputMode(mode)
            if (mode == StereoOutputMode.CARDBOARD_VR) {
                subtitleView.isVisible = false
                subtitleSplitContainer.isVisible = true
                if (viewModel.isBuffering.value == true) {
                    bufferingIndicator.isVisible = false
                    bufferingSplitContainer.isVisible = true
                }
            } else {
                subtitleView.isVisible = true
                subtitleSplitContainer.isVisible = false
                if (viewModel.isBuffering.value == true) {
                    bufferingIndicator.isVisible = true
                    bufferingSplitContainer.isVisible = false
                }
            }
        }
        viewModel.anaglyphType.observe(viewLifecycleOwner) { type ->
            stereoRenderer?.setAnaglyphType(type)
        }
        viewModel.singleFrameSize.observe(viewLifecycleOwner) { (width, height) ->
            stereoRenderer?.setSingleFrameDimensions(width, height)
        }
        viewModel.depth.observe(viewLifecycleOwner) { d -> stereoRenderer?.setDepth(d) }

        viewModel.screenSeparation.observe(viewLifecycleOwner) { sep ->
            stereoRenderer?.setScreenSeparation(sep)

            // sep - это доля от ширины экрана (например 0.05 = 5%)
            val screenWidth = resources.displayMetrics.widthPixels
            val shiftPx = sep * screenWidth

            // Сдвигаем UI элементы
            subtitleViewLeft.translationX = -shiftPx
            subtitleViewRight.translationX = shiftPx
            loaderLeft.translationX = -shiftPx
            loaderRight.translationX = shiftPx
        }

        // VR Observers
        viewModel.vrK1.observe(viewLifecycleOwner) { updateVrParams(); updateSettingsText() }
        viewModel.vrK2.observe(viewLifecycleOwner) { updateVrParams() }
        viewModel.vrScale.observe(viewLifecycleOwner) { updateVrParams(); updateSettingsText() }

        viewModel.currentMatrices.observe(viewLifecycleOwner) { (l, r) ->
            stereoRenderer?.setAnaglyphMatrices(l, r)
        }
        viewModel.calculatedColorL.observe(viewLifecycleOwner) { updateSettingsText() }
        viewModel.calculatedColorR.observe(viewLifecycleOwner) { updateSettingsText() }
        viewModel.customHueOffsetL.observe(viewLifecycleOwner) { updateSettingsText() }
        viewModel.customHueOffsetR.observe(viewLifecycleOwner) { updateSettingsText() }
        viewModel.customLeakL.observe(viewLifecycleOwner) { updateSettingsText() }
        viewModel.customLeakR.observe(viewLifecycleOwner) { updateSettingsText() }
        viewModel.customSpaceLms.observe(viewLifecycleOwner) { updateSettingsText() }
        viewModel.isMatrixValid.observe(viewLifecycleOwner) { updateSettingsText() }

        viewModel.isSettingsPanelVisible.observe(viewLifecycleOwner) { isVisible ->
            topSettingsPanel.isVisible = isVisible
            if (isVisible) {
                hideControls()
                resetSettingsHideTimer()
                updateSettingsText()
            } else {
                settingsHideHandler.removeCallbacks(settingsHideRunnable)
            }
        }

        viewModel.currentSettingType.observe(viewLifecycleOwner) { updateSettingsText() }
        viewModel.inputType.observe(viewLifecycleOwner) { updateSettingsText() }
        viewModel.outputMode.observe(viewLifecycleOwner) { updateSettingsText() }
        viewModel.anaglyphType.observe(viewLifecycleOwner) { updateSettingsText() }
        viewModel.swapEyes.observe(viewLifecycleOwner) { updateSettingsText() }
        viewModel.depth.observe(viewLifecycleOwner) { updateSettingsText() }
        viewModel.screenSeparation.observe(viewLifecycleOwner) { updateSettingsText() }
        viewModel.currentAudioName.observe(viewLifecycleOwner) { updateSettingsText() }
        viewModel.currentSubtitleName.observe(viewLifecycleOwner) { updateSettingsText() }

        viewModel.cues.observe(viewLifecycleOwner) { cues ->
            subtitleView.setCues(cues)
            subtitleViewLeft.setCues(cues)
            subtitleViewRight.setCues(cues)
        }

        viewModel.toastMessage.observe(viewLifecycleOwner) { msg ->
            if (msg != null) {
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                viewModel.clearToastMessage()
            }
        }
    }

    private fun updateVrParams() {
        val k1 = viewModel.vrK1.value ?: 0.34f
        val k2 = viewModel.vrK2.value ?: 0.10f
        val scale = viewModel.vrScale.value ?: 1.2f
        stereoRenderer?.setDistortion(k1, k2, scale)
    }

    private fun updateSettingsText() {
        if (viewModel.isSettingsPanelVisible.value != true) return
        val type = viewModel.currentSettingType.value ?: return
        resetSettingsHideTimer()

        settingValue.setTextColor(Color.WHITE)
        settingValue.paintFlags = settingValue.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()

        val isMatrixInvalid = viewModel.isMatrixValid.value == false

        when (type) {
            SettingType.VIDEO_TYPE -> {
                settingTitle.text = getString(R.string.setting_video_type)
                settingValue.text = viewModel.inputType.value?.name?.replace("_", " ")
            }

            SettingType.OUTPUT_FORMAT -> {
                settingTitle.text = getString(R.string.setting_output_format)
                settingValue.text = viewModel.outputMode.value?.name?.replace("_", " ")
            }

            SettingType.GLASSES_TYPE -> {
                settingTitle.text = getString(R.string.setting_glasses_type)
                settingValue.text = getGlassesGroupName(viewModel.anaglyphType.value!!)
            }

            SettingType.FILTER_MODE -> {
                settingTitle.text = getString(R.string.setting_filter)
                val name = viewModel.anaglyphType.value?.name ?: ""
                settingValue.text = if (name.endsWith("_CUSTOM")) "Custom" else name
            }

            SettingType.CUSTOM_HUE_L -> {
                settingTitle.text = "Оттенок (Левый)"
                val offset = viewModel.customHueOffsetL.value ?: 0
                val color = viewModel.calculatedColorL.value ?: Color.WHITE
                settingValue.text = "$offset (${String.format("#%06X", (0xFFFFFF and color))})"
                settingValue.setTextColor(color)
                if (isMatrixInvalid) settingValue.paintFlags =
                    settingValue.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            }

            SettingType.CUSTOM_HUE_R -> {
                settingTitle.text = "Оттенок (Правый)"
                val offset = viewModel.customHueOffsetR.value ?: 0
                val color = viewModel.calculatedColorR.value ?: Color.WHITE
                settingValue.text = "$offset (${String.format("#%06X", (0xFFFFFF and color))})"
                settingValue.setTextColor(color)
                if (isMatrixInvalid) settingValue.paintFlags =
                    settingValue.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            }

            SettingType.CUSTOM_LEAK_L -> {
                settingTitle.text = "Утечка (Левый)"
                settingValue.text = "${(viewModel.customLeakL.value!! * 100).toInt()}%"
            }

            SettingType.CUSTOM_LEAK_R -> {
                settingTitle.text = "Утечка (Правый)"
                settingValue.text = "${(viewModel.customLeakR.value!! * 100).toInt()}%"
            }

            SettingType.CUSTOM_SPACE -> {
                settingTitle.text = "Пространство"
                settingValue.text = if (viewModel.customSpaceLms.value == true) "LMS" else "XYZ"
            }

            SettingType.SWAP_EYES -> {
                settingTitle.text = getString(R.string.setting_swap_eyes)
                settingValue.text = if (viewModel.swapEyes.value == true) "R - L" else "L - R"
            }

            SettingType.DEPTH_3D -> {
                settingTitle.text = getString(R.string.setting_depth)
                settingValue.text = viewModel.depth.value.toString()
            }

            SettingType.SCREEN_SEPARATION -> {
                settingTitle.text = getString(R.string.setting_screen_separation)
                // Показываем в условных единицах (x100 для красоты)
                settingValue.text =
                    String.format("%.1f", (viewModel.screenSeparation.value ?: 0f) * 100)
            }

            SettingType.VR_DISTORTION -> {
                settingTitle.text = getString(R.string.setting_vr_distortion)
                settingValue.text = String.format("%.2f", viewModel.vrK1.value)
            }

            SettingType.VR_ZOOM -> {
                settingTitle.text = getString(R.string.setting_vr_zoom)
                settingValue.text = String.format("%.2f", viewModel.vrScale.value)
            }

            SettingType.AUDIO_TRACK -> {
                settingTitle.text = getString(R.string.setting_audio_track)
                settingValue.text = viewModel.currentAudioName.value
            }

            SettingType.SUBTITLES -> {
                settingTitle.text = getString(R.string.setting_subtitles)
                settingValue.text = viewModel.currentSubtitleName.value
            }
        }
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

    private fun showControls(focusOnSeekBar: Boolean = false) {
        if (controlsView.isVisible) {
            if (focusOnSeekBar) seekBar.requestFocus() else {
                // Если фокус потерян или на контейнере, возвращаем на Play
                if (activity?.currentFocus == null || activity?.currentFocus == rootContainer) {
                    playPauseButton.requestFocus()
                }
            }
            resetHideTimer()
            return
        }
        controlsView.visibility = View.VISIBLE
        topInfoPanel.visibility = View.VISIBLE

        if (focusOnSeekBar) seekBar.requestFocus() else playPauseButton.requestFocus()
        resetHideTimer()
    }

    private fun hideControls() {
        if (!controlsView.isVisible) return
        collapseControls()
        controlsView.visibility = View.GONE
        topInfoPanel.visibility = View.GONE
        rootContainer.requestFocus()
        hideControlsHandler.removeCallbacks(hideControlsRunnable)
    }

    private fun expandControls() {
        if (isPanelExpanded) return
        isPanelExpanded = true
        // bottomControlsRow.visibility = View.VISIBLE // У нас теперь нет нижнего ряда в новом дизайне
    }

    private fun collapseControls() {
        if (!isPanelExpanded) return
        isPanelExpanded = false
        // bottomControlsRow.visibility = View.GONE
    }

    private fun resetHideTimer() {
        hideControlsHandler.removeCallbacks(hideControlsRunnable)
        hideControlsHandler.postDelayed(hideControlsRunnable, 10000)
    }

    private fun resetSettingsHideTimer() {
        settingsHideHandler.removeCallbacks(settingsHideRunnable)
        settingsHideHandler.postDelayed(settingsHideRunnable, 60000)
    }

    private fun setupBackPressedHandler() {
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (viewModel.isSettingsPanelVisible.value == true) {
                        viewModel.closeSettingsPanel(save = false)
                        rootContainer.requestFocus()
                    } else if (controlsView.isVisible) {
                        hideControls()
                    } else {
                        if (isEnabled) {
                            isEnabled = false
                            requireActivity().onBackPressedDispatcher.onBackPressed()
                        }
                    }
                }
            })
    }

    private fun formatTime(millis: Long): String {
        val seconds = (millis / 1000) % 60
        val minutes = (millis / (1000 * 60)) % 60
        val hours = (millis / (1000 * 60 * 60))
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }

    private fun updateClock() {
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)
        textClock.text = String.format(Locale.getDefault(), "%02d:%02d", hour, minute)
    }

    private fun showPlaylist() {
        // todo Заменить тост на плейлист
        Toast.makeText(context, "Playlist (Coming Soon)", Toast.LENGTH_SHORT).show()
    }

    private fun showQualityPopup() {
        val options = viewModel.videoQualityOptions.value ?: return
        if (options.isEmpty()) return

        // Останавливаем таймер скрытия панелей
        hideControlsHandler.removeCallbacks(hideControlsRunnable)

        // Используем ContextThemeWrapper для применения темного стиля
        val wrapper = ContextThemeWrapper(context, R.style.DarkPopupContext)
        val popup = PopupMenu(wrapper, buttonQuality)

        options.forEachIndexed { index, option ->
            popup.menu.add(0, index, 0, option.name)
        }

        popup.setOnMenuItemClickListener { item ->
            val selectedOption = options[item.itemId]
            viewModel.setVideoQuality(selectedOption)
            true
        }

        // Когда меню закрывается, перезапускаем таймер скрытия
        popup.setOnDismissListener {
            resetHideTimer()
        }

        popup.show()
    }

    override fun onSurfaceReady(surface: Surface) {
        activity?.runOnUiThread { viewModel.player.setVideoSurface(surface) }
    }

    override fun onFpsUpdated(fps: Int) {
        activity?.runOnUiThread { fpsCounterTextView.text = "FPS: $fps" }
    }

    override fun onResume() {
        super.onResume()
        viewModel.player.playWhenReady = true
        glSurfaceView.onResume()
        rootContainer.requestFocus()
    }

    override fun onPause() {
        super.onPause()
        viewModel.player.playWhenReady = false
        glSurfaceView.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Очищаем колбэки, чтобы избежать утечек и крашей
        hideControlsHandler.removeCallbacksAndMessages(null)
        settingsHideHandler.removeCallbacksAndMessages(null)
        clockHandler.removeCallbacksAndMessages(null)

        // Отвязываем поверхность от плеера
        viewModel.player.setVideoSurface(null)

        // Освобождаем ресурсы рендерера
        stereoRenderer?.release()
        stereoRenderer = null
    }

    companion object {
        fun newInstance(): PlayerFragment {
            return PlayerFragment()
        }
    }
}