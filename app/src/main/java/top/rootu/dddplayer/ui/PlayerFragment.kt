package top.rootu.dddplayer.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import top.rootu.dddplayer.R
import top.rootu.dddplayer.model.StereoType
import top.rootu.dddplayer.renderer.AnaglyphShader
import top.rootu.dddplayer.renderer.OnFpsUpdatedListener
import top.rootu.dddplayer.renderer.OnSurfaceReadyListener
import top.rootu.dddplayer.viewmodel.PlayerViewModel
import android.opengl.GLSurfaceView
import android.view.Surface
import java.util.Locale
import androidx.core.view.isVisible
import androidx.core.net.toUri

class PlayerFragment : Fragment(), OnSurfaceReadyListener, OnFpsUpdatedListener {

    private val viewModel: PlayerViewModel by viewModels()
    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var anaglyphShader: AnaglyphShader

    // UI элементы
    private lateinit var rootContainer: ViewGroup
    private lateinit var controlsView: View
    private lateinit var fpsCounterTextView: TextView
    private lateinit var playPauseButton: ImageButton
    private lateinit var rewindButton: ImageButton
    private lateinit var ffwdButton: ImageButton
    private lateinit var seekBar: SeekBar
    private lateinit var timeCurrentTextView: TextView
    private lateinit var timeDurationTextView: TextView
    private lateinit var stereoModeButton: Button
    private lateinit var anaglyphTypeButton: Button

    private val hideControlsHandler = Handler(Looper.getMainLooper())
    private var isSeeking = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.player_fragment, container, false)
        bindViews(view)

        glSurfaceView.setEGLContextClientVersion(2)
        anaglyphShader = AnaglyphShader(glSurfaceView, this, this)
        glSurfaceView.setRenderer(anaglyphShader)
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY

        return view
    }

    private fun bindViews(view: View) {
        rootContainer = view.findViewById(R.id.root_container)
        controlsView = view.findViewById(R.id.playback_controls)
        glSurfaceView = view.findViewById(R.id.gl_surface_view)
        fpsCounterTextView = view.findViewById(R.id.fps_counter)
        playPauseButton = controlsView.findViewById(R.id.button_play_pause)
        rewindButton = controlsView.findViewById(R.id.button_rewind)
        ffwdButton = controlsView.findViewById(R.id.button_ffwd)
        seekBar = controlsView.findViewById(R.id.seek_bar)
        timeCurrentTextView = controlsView.findViewById(R.id.time_current)
        timeDurationTextView = controlsView.findViewById(R.id.time_duration)
        stereoModeButton = controlsView.findViewById(R.id.button_stereo_mode)
        anaglyphTypeButton = controlsView.findViewById(R.id.button_anaglyph_type)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        rootContainer.requestFocus()
        setupControls()
        observeViewModel()

//        val videoUri = "http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"
//        val videoUri = "http://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8"
        val videoUri = "http://epg.rootu.top/tmp/3D/bbb_sunflower_1080p_30fps_stereo_abl.mp4"
        viewModel.loadMedia(top.rootu.dddplayer.model.MediaItem(videoUri.toUri()))
    }

    private fun setupControls() {
        rootContainer.setOnClickListener { toggleControls() }

        // ИСПРАВЛЕНО: Улучшенный обработчик нажатий для пульта
        rootContainer.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                // Если контролы видны, любое нажатие на D-pad сбрасывает таймер
                if (controlsView.isVisible) {
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP,
                        KeyEvent.KEYCODE_DPAD_DOWN,
                        KeyEvent.KEYCODE_DPAD_LEFT,
                        KeyEvent.KEYCODE_DPAD_RIGHT,
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER -> {
                            hideControlsWithDelay() // Сбрасываем таймер
                        }
                    }
                }

                // Центральная кнопка всегда показывает/скрывает контролы
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    // Если контролы скрыты, нажатие их покажет.
                    // Если видны, то нажатие на кнопку (например, Play) выполнит действие,
                    // а toggleControls() вызывать не нужно, так как это сделает сама кнопка.
                    // Поэтому вызываем toggleControls только если фокус НЕ на кнопках.
                    if (!controlsView.hasFocus()) {
                        toggleControls()
                        return@setOnKeyListener true
                    }
                }
            }
            return@setOnKeyListener false
        }

        playPauseButton.setOnClickListener { viewModel.togglePlayPause() }
        rewindButton.setOnClickListener { viewModel.seekBack() }
        ffwdButton.setOnClickListener { viewModel.seekForward() }
        stereoModeButton.setOnClickListener { showStereoModeSelector() }
        anaglyphTypeButton.setOnClickListener { showAnaglyphTypeSelector() }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    timeCurrentTextView.text = formatTime(progress.toLong())
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isSeeking = true
                // Пока пользователь двигает SeekBar, не прячем контролы
                hideControlsHandler.removeCallbacksAndMessages(null)
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.progress?.let { viewModel.player.seekTo(it.toLong()) }
                isSeeking = false
                // Когда пользователь отпустил SeekBar, снова запускаем таймер
                hideControlsWithDelay()
            }
        })
    }

    private fun observeViewModel() {
        viewModel.isPlaying.observe(viewLifecycleOwner) { isPlaying ->
            val iconRes = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            playPauseButton.setImageResource(iconRes)
        }

        viewModel.duration.observe(viewLifecycleOwner) { duration ->
            seekBar.max = duration.toInt()
            timeDurationTextView.text = formatTime(duration)
        }

        viewModel.currentPosition.observe(viewLifecycleOwner) { position ->
            if (!isSeeking) {
                seekBar.progress = position.toInt()
                timeCurrentTextView.text = formatTime(position)
            }
        }

        viewModel.stereoMode.observe(viewLifecycleOwner) { mode ->
            anaglyphShader.setStereoMode(mode)
            glSurfaceView.requestRender()
        }

        viewModel.anaglyphType.observe(viewLifecycleOwner) { type ->
            anaglyphShader.setAnaglyphType(type)
            glSurfaceView.requestRender()
        }
    }

    private fun toggleControls() {
        if (controlsView.isVisible) {
            hideControls()
        } else {
            showControls()
        }
    }

    private fun showControls() {
        controlsView.animate().alpha(1.0f).withStartAction {
            controlsView.visibility = View.VISIBLE
            playPauseButton.requestFocus()
        }.start()
        hideControlsWithDelay()
    }

    private fun hideControls() {
        // Отменяем все запланированные скрытия
        hideControlsHandler.removeCallbacksAndMessages(null)
        controlsView.animate().alpha(0.0f).withEndAction {
            controlsView.visibility = View.GONE
            rootContainer.requestFocus()
        }.start()
    }

    // Эта функция теперь наш главный "сбрасыватель таймера"
    private fun hideControlsWithDelay() {
        hideControlsHandler.removeCallbacksAndMessages(null)
        hideControlsHandler.postDelayed({ hideControls() }, 3000)
    }

    private fun showStereoModeSelector() {
        AlertDialog.Builder(requireContext(), androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle(R.string.action_select_stereo_mode)
            .setItems(arrayOf(
                getString(R.string.stereo_mode_none),
                getString(R.string.stereo_mode_side_by_side),
                getString(R.string.stereo_mode_top_bottom)
            )) { dialog, which ->
                val type = when (which) {
                    0 -> StereoType.NONE
                    1 -> StereoType.SIDE_BY_SIDE
                    2 -> StereoType.TOP_BOTTOM
                    else -> StereoType.NONE
                }
                viewModel.setStereoType(type)
                dialog.dismiss()
            }
            .show()
    }

    private fun showAnaglyphTypeSelector() {
        val anaglyphTypes = AnaglyphShader.AnaglyphType.entries.toTypedArray()
        val anaglyphTypeNames = anaglyphTypes.map { it.name.replace("_", " ") }.toTypedArray()
        AlertDialog.Builder(requireContext(), androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle(R.string.action_select_anaglyph_type)
            .setItems(anaglyphTypeNames) { dialog, which ->
                val type = anaglyphTypes[which]
                viewModel.setAnaglyphType(type)
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

    override fun onSurfaceReady(surface: Surface) {
        activity?.runOnUiThread { viewModel.player.setVideoSurface(surface) }
    }

    @SuppressLint("SetTextI18n")
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
        viewModel.player.setVideoSurface(null)
        glSurfaceView.onPause()
    }

    companion object {
        fun newInstance() = PlayerFragment()
    }
}
