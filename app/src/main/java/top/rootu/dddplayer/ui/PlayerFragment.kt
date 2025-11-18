package top.rootu.dddplayer.ui

import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import top.rootu.dddplayer.R
import top.rootu.dddplayer.model.StereoInputType
import top.rootu.dddplayer.model.StereoOutputMode
import top.rootu.dddplayer.renderer.OnFpsUpdatedListener
import top.rootu.dddplayer.renderer.OnSurfaceReadyListener
import top.rootu.dddplayer.renderer.StereoGLSurfaceView
import top.rootu.dddplayer.renderer.StereoRenderer
import top.rootu.dddplayer.viewmodel.PlayerViewModel
import android.view.Surface
import androidx.core.net.toUri
import java.util.Locale

class PlayerFragment : Fragment(), OnSurfaceReadyListener, OnFpsUpdatedListener {

    private val viewModel: PlayerViewModel by viewModels()
    private lateinit var glSurfaceView: StereoGLSurfaceView
    private lateinit var stereoRenderer: StereoRenderer

    // --- UI State ---
    private var isPanelExpanded = false
    private val hideControlsHandler = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable { hideControls() }
    private var isUserSeeking = false

    // --- Views ---
    private lateinit var controlsView: View
    private lateinit var bottomControlsRow: View
    private lateinit var fpsCounterTextView: TextView
    private lateinit var bufferingIndicator: ProgressBar
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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.player_fragment, container, false)
        bindViews(view)

        glSurfaceView.setEGLContextClientVersion(2)
        stereoRenderer = StereoRenderer(glSurfaceView, this, this)
        glSurfaceView.setRenderer(stereoRenderer)
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY

        return view
    }

    private fun bindViews(view: View) {
        controlsView = view.findViewById(R.id.playback_controls)
        bottomControlsRow = controlsView.findViewById(R.id.bottom_controls_row)
        glSurfaceView = view.findViewById(R.id.gl_surface_view)
        fpsCounterTextView = view.findViewById(R.id.fps_counter)
        bufferingIndicator = view.findViewById(R.id.buffering_indicator)
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
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.requestFocus()
        setupControls()
        observeViewModel()
        setupBackPressedHandler()

        val videoUri = arguments?.getParcelable<Uri>(ARG_VIDEO_URI)
        if (videoUri != null) {
            viewModel.loadMedia(top.rootu.dddplayer.model.MediaItem(videoUri, videoUri.lastPathSegment))
        } else {
            // Если приложение запущено из лаунчера без файла,
            // можно показать заглушку или загрузить видео по умолчанию.
//            val videoUri = "http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"
//            val videoUri = "http://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8"
            val videoUri = "http://epg.rootu.top/tmp/3D/bbb_sunflower_1080p_30fps_stereo_abl.mp4"
            viewModel.loadMedia(top.rootu.dddplayer.model.MediaItem(videoUri.toUri()))
        }
    }

    fun handleKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false

        if (controlsView.isVisible) {
            resetHideTimer()
        }

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
                if (!isPanelExpanded) {
                    val topRowIds = listOf(R.id.button_play_pause, R.id.button_rewind, R.id.button_ffwd, R.id.seek_bar)
                    if (currentFocus?.id in topRowIds) {
                        expandControls()
                        return true
                    }
                }
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (controlsView.isVisible && currentFocus?.id == R.id.seek_bar) {
                    hideControls()
                    return true
                } else if (!controlsView.isVisible) {
                    showPlaylist()
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
        }
        return false
    }

    private fun setupControls() {
        playPauseButton.setOnClickListener { viewModel.togglePlayPause() }
        rewindButton.setOnClickListener { viewModel.seekBack() }
        ffwdButton.setOnClickListener { viewModel.seekForward() }
        swapEyesButton.setOnClickListener { viewModel.toggleSwapEyes() }
        inputModeButton.setOnClickListener { showInputFormatSelector() }
        outputModeButton.setOnClickListener { showOutputModeSelector() }
        tracksButton.setOnClickListener { showTracksSelector() }
        playlistButton.setOnClickListener { showPlaylist() }

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
            bufferingIndicator.isVisible = isBuffering
        }
        viewModel.swapEyes.observe(viewLifecycleOwner) { swap ->
            stereoRenderer.setSwapEyes(swap)
            swapEyesButton.alpha = if (swap) 1.0f else 0.5f
        }
        viewModel.inputType.observe(viewLifecycleOwner) { type -> stereoRenderer.setInputType(type) }
        viewModel.outputMode.observe(viewLifecycleOwner) { mode -> stereoRenderer.setOutputMode(mode) }
        viewModel.anaglyphType.observe(viewLifecycleOwner) { type -> stereoRenderer.setAnaglyphType(type) }
        viewModel.singleFrameSize.observe(viewLifecycleOwner) { (width, height) ->
            stereoRenderer.setSingleFrameDimensions(width, height)
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
        hideControlsHandler.postDelayed(hideControlsRunnable, 5000)
    }

    private fun setupBackPressedHandler() {
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (controlsView.isVisible) {
                    if (isPanelExpanded) collapseControls() else hideControls()
                } else {
                    if (isEnabled) {
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    private fun showPlaylist() { Toast.makeText(context, "Playlist coming soon!", Toast.LENGTH_SHORT).show() }
    private fun showTracksSelector() { Toast.makeText(context, "Tracks/Subs coming soon!", Toast.LENGTH_SHORT).show() }

    private fun showInputFormatSelector() {
        val items = StereoInputType.values().map { it.name.replace("_", " ") }.toTypedArray()
        AlertDialog.Builder(requireContext(), androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle(R.string.action_select_input_format)
            .setItems(items) { dialog, which ->
                viewModel.setInputType(StereoInputType.values()[which])
                dialog.dismiss()
            }
            .show()
    }

    private fun showOutputModeSelector() {
        val items = StereoOutputMode.values().map { it.name.replace("_", " ") }.toTypedArray()
        AlertDialog.Builder(requireContext(), androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle(R.string.action_select_output_mode)
            .setItems(items) { dialog, which ->
                val selectedMode = StereoOutputMode.values()[which]
                viewModel.setOutputMode(selectedMode)
                dialog.dismiss()
                if (selectedMode == StereoOutputMode.ANAGLYPH) {
                    showAnaglyphTypeSelector()
                }
            }
            .show()
    }

    private fun showAnaglyphTypeSelector() {
        val items = StereoRenderer.AnaglyphType.values().map { it.name.replace("_", " ") }.toTypedArray()
        AlertDialog.Builder(requireContext(), androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle(R.string.action_select_anaglyph_type)
            .setItems(items) { dialog, which ->
                viewModel.setAnaglyphType(StereoRenderer.AnaglyphType.values()[which])
                dialog.dismiss()
            }
            .show()
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

    override fun onSurfaceReady(surface: Surface) { activity?.runOnUiThread { viewModel.player.setVideoSurface(surface) } }
    override fun onFpsUpdated(fps: Int) { activity?.runOnUiThread { fpsCounterTextView.text = "FPS: $fps" } }

    override fun onResume() { super.onResume(); viewModel.player.playWhenReady = true; glSurfaceView.onResume() }
    override fun onPause() { super.onPause(); viewModel.player.playWhenReady = false; glSurfaceView.onPause() }
    override fun onDestroyView() { super.onDestroyView(); viewModel.player.setVideoSurface(null); glSurfaceView.onPause() }

    companion object {
        private const val ARG_VIDEO_URI = "video_uri"
        fun newInstance(videoUri: Uri? = null): PlayerFragment {
            return PlayerFragment().apply {
                arguments = Bundle().apply { putParcelable(ARG_VIDEO_URI, videoUri) }
            }
        }
    }
}