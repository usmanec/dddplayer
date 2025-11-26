package top.rootu.dddplayer.ui

import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
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

@UnstableApi
class PlayerFragment : Fragment(), OnSurfaceReadyListener, OnFpsUpdatedListener {

    private val viewModel: PlayerViewModel by viewModels()
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

    private lateinit var controlsView: View
    private lateinit var bottomControlsRow: View
    private lateinit var fpsCounterTextView: TextView

    // Лоадеры
    private lateinit var bufferingIndicator: ProgressBar
    private lateinit var bufferingSplitContainer: View
    private lateinit var loaderLeft: ProgressBar
    private lateinit var loaderRight: ProgressBar

    private lateinit var playPauseButton: ImageButton
    private lateinit var rewindButton: ImageButton
    private lateinit var ffwdButton: ImageButton
    private lateinit var seekBar: SeekBar
    private lateinit var timeCurrentTextView: TextView
    private lateinit var timeDurationTextView: TextView
    private lateinit var videoTitleTextView: TextView
    private lateinit var inputModeButton: ImageButton
    private lateinit var outputModeButton: ImageButton
    private lateinit var swapEyesButton: ImageButton
    private lateinit var tracksButton: ImageButton
    private lateinit var playlistButton: ImageButton

    // --- OSD Views ---
    private lateinit var topSettingsPanel: View
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

        controlsView = view.findViewById(R.id.playback_controls)
        bottomControlsRow = controlsView.findViewById(R.id.bottom_controls_row)
        glSurfaceView = view.findViewById(R.id.gl_surface_view)
        fpsCounterTextView = view.findViewById(R.id.fps_counter)

        bufferingIndicator = view.findViewById(R.id.buffering_indicator)
        bufferingSplitContainer = view.findViewById(R.id.buffering_split_container)
        loaderLeft = view.findViewById(R.id.loader_left)
        loaderRight = view.findViewById(R.id.loader_right)

        playPauseButton = controlsView.findViewById(R.id.button_play_pause)
        rewindButton = controlsView.findViewById(R.id.button_rewind)
        ffwdButton = controlsView.findViewById(R.id.button_ffwd)
        seekBar = controlsView.findViewById(R.id.seek_bar)
        timeCurrentTextView = controlsView.findViewById(R.id.time_current)
        timeDurationTextView = controlsView.findViewById(R.id.time_duration)
        videoTitleTextView = controlsView.findViewById(R.id.video_title)
        inputModeButton = controlsView.findViewById(R.id.button_input_mode)
        outputModeButton = controlsView.findViewById(R.id.button_output_mode)
        swapEyesButton = controlsView.findViewById(R.id.button_swap_eyes)
        tracksButton = controlsView.findViewById(R.id.button_tracks)
        playlistButton = controlsView.findViewById(R.id.button_playlist)

        topSettingsPanel = view.findViewById(R.id.top_settings_panel)
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

        val videoUri = arguments?.getParcelable<Uri>(ARG_VIDEO_URI)
        if (videoUri != null) {
            viewModel.loadMedia(
                top.rootu.dddplayer.model.MediaItem(
                    videoUri,
                    videoUri.lastPathSegment
                )
            )
        } else {
            // Дефолтное видео для теста
//            val defaultUri = "http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"
//            val defaultUri = "http://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8"
            val defaultUri = "http://epg.rootu.top/tmp/3D/bbb_sunflower_1080p_30fps_stereo_abl.mp4"
            viewModel.loadMedia(top.rootu.dddplayer.model.MediaItem(defaultUri.toUri()))
        }
    }

    fun handleKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
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
                    return true
                }

                KeyEvent.KEYCODE_BACK -> {
                    viewModel.closeSettingsPanel(save = false)
                    return true
                }
            }
            return true
        }
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
                if (!isPanelExpanded && currentFocus?.id in listOf(
                        R.id.button_play_pause,
                        R.id.button_rewind,
                        R.id.button_ffwd,
                        R.id.seek_bar
                    )
                ) {
                    expandControls()
                    return true
                }
            }

            KeyEvent.KEYCODE_DPAD_UP -> {
                if (controlsView.isVisible && currentFocus?.id == R.id.seek_bar) {
                    hideControls()
                    return true
                } else if (!controlsView.isVisible) {
                    viewModel.openSettingsPanel()
                    return true
                }
            }

            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (!controlsView.isVisible) {
                    showControls(focusOnSeekBar = true)
                    return false
                }
                if (currentFocus?.id == R.id.seek_bar) {
                    isUserSeeking = true
                }
            }

            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                viewModel.togglePlayPause()
                showControls()
                return true
            }
        }
        return false
    }

    private fun setupControls() {
        btnSettingsPrev.setOnClickListener { viewModel.onMenuLeft(); resetSettingsHideTimer() }
        btnSettingsNext.setOnClickListener { viewModel.onMenuRight(); resetSettingsHideTimer() }
        titleContainer.setOnClickListener { viewModel.onMenuDown(); resetSettingsHideTimer() }
        topSettingsPanel.setOnClickListener { resetSettingsHideTimer() }
        touchZoneTop.setOnClickListener { if (viewModel.isSettingsPanelVisible.value != true) viewModel.openSettingsPanel() }
        rootContainer.setOnClickListener {
            if (viewModel.isSettingsPanelVisible.value == true) viewModel.closeSettingsPanel(
                save = true
            ) else if (controlsView.isVisible) hideControls() else showControls()
        }
        controlsView.setOnClickListener { resetHideTimer() }
        playPauseButton.setOnClickListener { viewModel.togglePlayPause() }
        rewindButton.setOnClickListener { viewModel.seekBack() }
        ffwdButton.setOnClickListener { viewModel.seekForward() }
        swapEyesButton.setOnClickListener { viewModel.toggleSwapEyes() }
        inputModeButton.setOnClickListener { viewModel.openSettingsPanel() }
        outputModeButton.setOnClickListener { viewModel.openSettingsPanel() }
        tracksButton.setOnClickListener { viewModel.openSettingsPanel() }
        playlistButton.setOnClickListener { viewModel.openSettingsPanel() }
        seekBar.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    isUserSeeking = true
                } else if (event.action == KeyEvent.ACTION_UP) {
                    viewModel.player.seekTo(seekBar.progress.toLong())
                    isUserSeeking = false
                }
            }
            return@setOnKeyListener false
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) timeCurrentTextView.text = formatTime(progress.toLong())
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = true
                hideControlsHandler.removeCallbacks(hideControlsRunnable)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.progress?.let { viewModel.player.seekTo(it.toLong()) }
                isUserSeeking = false
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
            if (!isUserSeeking) {
                seekBar.progress = position.toInt()
                timeCurrentTextView.text = formatTime(position)
            }
        }
        viewModel.videoTitle.observe(viewLifecycleOwner) { title ->
            videoTitleTextView.text = title
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
            swapEyesButton.alpha = if (swap) 1.0f else 0.5f
        }
        viewModel.inputType.observe(viewLifecycleOwner) { type -> stereoRenderer?.setInputType(type) }

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

        viewModel.anaglyphType.observe(viewLifecycleOwner) { type -> stereoRenderer?.setAnaglyphType(type) }
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

        // OSD Observers
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
                settingValue.text = viewModel.anaglyphType.value?.name
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
                settingValue.text = String.format("%.1f", (viewModel.screenSeparation.value ?: 0f) * 100)
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
        if (controlsView.isVisible) return
        controlsView.visibility = View.VISIBLE
        if (focusOnSeekBar) seekBar.requestFocus() else playPauseButton.requestFocus()
        resetHideTimer()
    }

    private fun hideControls() {
        if (!controlsView.isVisible) return
        collapseControls()
        controlsView.visibility = View.GONE
        view?.requestFocus()
        hideControlsHandler.removeCallbacks(hideControlsRunnable)
    }

    private fun expandControls() {
        if (isPanelExpanded) return
        isPanelExpanded = true
        bottomControlsRow.visibility = View.VISIBLE
        outputModeButton.requestFocus()
    }

    private fun collapseControls() {
        if (!isPanelExpanded) return
        isPanelExpanded = false
        bottomControlsRow.visibility = View.GONE
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
                    } else if (controlsView.isVisible) {
                        if (isPanelExpanded) collapseControls()
                        else hideControls()
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

        // Отвязываем поверхность от плеера
        viewModel.player.setVideoSurface(null)

        // Освобождаем ресурсы рендерера
        stereoRenderer?.release()
        stereoRenderer = null
    }

    companion object {
        private const val ARG_VIDEO_URI = "video_uri"
        fun newInstance(videoUri: Uri? = null): PlayerFragment {
            return PlayerFragment().apply {
                arguments = Bundle().apply { putParcelable(ARG_VIDEO_URI, videoUri) }
            }
        }
    }
}