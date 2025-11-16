package top.rootu.dddplayer.ui

import android.net.Uri
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
import top.rootu.dddplayer.model.StereoInputType
import top.rootu.dddplayer.model.StereoOutputMode
import top.rootu.dddplayer.renderer.OnFpsUpdatedListener
import top.rootu.dddplayer.renderer.OnSurfaceReadyListener
import top.rootu.dddplayer.renderer.StereoRenderer
import top.rootu.dddplayer.viewmodel.PlayerViewModel
import android.opengl.GLSurfaceView
import android.view.Surface
import androidx.core.net.toUri
import androidx.core.view.isVisible
import java.util.Locale

class PlayerFragment : Fragment(), OnSurfaceReadyListener, OnFpsUpdatedListener {

    private val viewModel: PlayerViewModel by viewModels()
    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var stereoRenderer: StereoRenderer

    private lateinit var rootContainer: ViewGroup
    private lateinit var controlsView: View
    private lateinit var fpsCounterTextView: TextView
    private lateinit var playPauseButton: ImageButton
    private lateinit var rewindButton: ImageButton
    private lateinit var ffwdButton: ImageButton
    private lateinit var seekBar: SeekBar
    private lateinit var timeCurrentTextView: TextView
    private lateinit var timeDurationTextView: TextView
    private lateinit var inputFormatButton: Button
    private lateinit var outputModeButton: Button
    private lateinit var swapEyesButton: Button

    private val hideControlsHandler = Handler(Looper.getMainLooper())
    private var isSeeking = false

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
        inputFormatButton = controlsView.findViewById(R.id.button_input_format)
        outputModeButton = controlsView.findViewById(R.id.button_output_mode)
        swapEyesButton = controlsView.findViewById(R.id.button_swap_eyes)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        rootContainer.requestFocus()
        setupControls()
        observeViewModel()

        // Получаем URI из аргументов фрагмента
        val videoUri = arguments?.getParcelable<Uri>(ARG_VIDEO_URI)

        if (videoUri != null) {
            // Если URI есть, загружаем его
            viewModel.loadMedia(top.rootu.dddplayer.model.MediaItem(videoUri))
        } else {
            // Если приложение запущено из лаунчера без файла,
            // можно показать заглушку или загрузить видео по умолчанию.
//            val videoUri = "http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"
//            val videoUri = "http://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8"
            val videoUri = "http://epg.rootu.top/tmp/3D/bbb_sunflower_1080p_30fps_stereo_abl.mp4"
            viewModel.loadMedia(top.rootu.dddplayer.model.MediaItem(videoUri.toUri()))
        }
    }
    private fun setupControls() {
        // Показываем/скрываем контролы по клику мыши/тачскрина
        rootContainer.setOnClickListener { toggleControls() }

        rootContainer.setOnKeyListener { v, keyCode, event ->
            // Этот слушатель должен срабатывать, только если фокус находится на самом rootContainer,
            // а не на его дочерних элементах (кнопках).
            if (!v.hasFocus()) {
                return@setOnKeyListener false
            }

            if (event.action == KeyEvent.ACTION_DOWN) {
                // Если контролы скрыты, любое нажатие на D-pad их покажет
                if (controlsView.visibility == View.GONE) {
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP,
                        KeyEvent.KEYCODE_DPAD_DOWN,
                        KeyEvent.KEYCODE_DPAD_LEFT,
                        KeyEvent.KEYCODE_DPAD_RIGHT,
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER -> {
                            showControls()
                            return@setOnKeyListener true // Событие обработано
                        }
                    }
                }
            }
            return@setOnKeyListener false // Событие не обработано
        }

        // Устанавливаем слушатель на саму панель, чтобы сбрасывать таймер при навигации по ней
        controlsView.setOnKeyListener { _, _, _ ->
            hideControlsWithDelay() // Сбрасываем таймер при любом действии на панели
            return@setOnKeyListener false // Не перехватываем событие, чтобы кнопки работали
        }

        playPauseButton.setOnClickListener { viewModel.togglePlayPause() }
        rewindButton.setOnClickListener { viewModel.seekBack() }
        ffwdButton.setOnClickListener { viewModel.seekForward() }
        swapEyesButton.setOnClickListener { viewModel.toggleSwapEyes() }
        inputFormatButton.setOnClickListener { showInputFormatSelector() }
        outputModeButton.setOnClickListener { showOutputModeSelector() }

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
            playPauseButton.setImageResource(if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
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
        viewModel.videoSize.observe(viewLifecycleOwner) { videoSize ->
            stereoRenderer.setVideoDimensions(videoSize.width, videoSize.height)
        }
        viewModel.inputType.observe(viewLifecycleOwner) { type -> stereoRenderer.setInputType(type) }
        viewModel.outputMode.observe(viewLifecycleOwner) { mode -> stereoRenderer.setOutputMode(mode) }
        viewModel.anaglyphType.observe(viewLifecycleOwner) { type -> stereoRenderer.setAnaglyphType(type) }
        viewModel.swapEyes.observe(viewLifecycleOwner) { swap ->
            stereoRenderer.setSwapEyes(swap)
            swapEyesButton.alpha = if (swap) 1.0f else 0.5f
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
                if (selectedMode == StereoOutputMode.ANAGLYPH) {
                    showAnaglyphTypeSelector()
                }
                dialog.dismiss()
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
        return if (hours > 0) String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        else String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }

    override fun onSurfaceReady(surface: Surface) { activity?.runOnUiThread { viewModel.player.setVideoSurface(surface) } }
    override fun onFpsUpdated(fps: Int) { activity?.runOnUiThread { fpsCounterTextView.text = "FPS: $fps" } }
    override fun onResume() { super.onResume(); viewModel.player.playWhenReady = true; glSurfaceView.onResume() }
    override fun onPause() { super.onPause(); viewModel.player.playWhenReady = false; glSurfaceView.onPause() }
    override fun onDestroyView() { super.onDestroyView(); viewModel.player.setVideoSurface(null); glSurfaceView.onPause() }

    companion object {
        private const val ARG_VIDEO_URI = "video_uri"

        fun newInstance(videoUri: Uri? = null): PlayerFragment {
            val fragment = PlayerFragment()
            // Передаем URI через Bundle, это правильный способ
            // передавать данные во фрагменты.
            val args = Bundle().apply {
                putParcelable(ARG_VIDEO_URI, videoUri)
            }
            fragment.arguments = args
            return fragment
        }
    }
}