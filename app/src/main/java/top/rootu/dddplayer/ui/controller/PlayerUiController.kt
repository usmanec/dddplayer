package top.rootu.dddplayer.ui.controller

import android.app.Dialog
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.SubtitleView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import top.rootu.dddplayer.R
import top.rootu.dddplayer.model.MediaItem
import top.rootu.dddplayer.model.StereoInputType
import top.rootu.dddplayer.model.StereoOutputMode
import top.rootu.dddplayer.renderer.StereoGLSurfaceView
import top.rootu.dddplayer.ui.adapter.PlaylistAdapter
import top.rootu.dddplayer.viewmodel.SettingType
import java.util.Locale

class PlayerUiController(private val rootView: View) {

    // Surfaces & Containers
    val aspectRatioFrame: AspectRatioFrameLayout = rootView.findViewById(R.id.aspect_ratio_frame)
    val standardSurfaceView: SurfaceView = rootView.findViewById(R.id.surface_view_standard)
    val glSurfaceView: StereoGLSurfaceView = rootView.findViewById(R.id.gl_surface_view)

    // Views
    val touchZoneTop: View = rootView.findViewById(R.id.touch_zone_top)
    val subtitleView: SubtitleView = rootView.findViewById(R.id.subtitle_view)
    val subtitleSplitContainer: View = rootView.findViewById(R.id.subtitle_split_container)
    val subtitleViewLeft: SubtitleView = rootView.findViewById(R.id.subtitle_view_left)
    val subtitleViewRight: SubtitleView = rootView.findViewById(R.id.subtitle_view_right)
    val fpsCounterTextView: TextView = rootView.findViewById(R.id.fps_counter)

    // Buffering
    val bufferingIndicator: ProgressBar = rootView.findViewById(R.id.buffering_indicator)
    val bufferingSplitContainer: View = rootView.findViewById(R.id.buffering_split_container)
    val loaderLeft: ProgressBar = rootView.findViewById(R.id.loader_left)
    val loaderRight: ProgressBar = rootView.findViewById(R.id.loader_right)

    // Panels
    val controlsView: View = rootView.findViewById(R.id.playback_controls)
    val topInfoPanel: View = rootView.findViewById(R.id.top_info_panel)
    val topSettingsPanel: View = rootView.findViewById(R.id.top_settings_panel)

    // Info Panel
    val videoTitleTextView: TextView = topInfoPanel.findViewById(R.id.video_title)
    val videoPoster: ImageView = topInfoPanel.findViewById(R.id.video_poster)
    val textClock: TextView = topInfoPanel.findViewById(R.id.text_clock)
    val iconInputMode: ImageView = topInfoPanel.findViewById(R.id.icon_input_mode)
    val iconSwapEyes: android.widget.ImageView = topInfoPanel.findViewById(R.id.icon_swap_eyes)
    val badgeResolution: TextView = topInfoPanel.findViewById(R.id.badge_resolution)
    val badgeAudio: TextView = topInfoPanel.findViewById(R.id.badge_audio)
    val badgeSubtitle: TextView = topInfoPanel.findViewById(R.id.badge_subtitle)

    // Controls
    val playPauseButton: ImageButton = controlsView.findViewById(R.id.button_play_pause)
    val rewindButton: ImageButton = controlsView.findViewById(R.id.button_rewind)
    val ffwdButton: ImageButton = controlsView.findViewById(R.id.button_ffwd)
    val prevButton: ImageButton = controlsView.findViewById(R.id.button_prev)
    val nextButton: ImageButton = controlsView.findViewById(R.id.button_next)
    val seekBar: SeekBar = controlsView.findViewById(R.id.seek_bar)
    val timeCurrentTextView: TextView = controlsView.findViewById(R.id.time_current)
    val timeDurationTextView: TextView = controlsView.findViewById(R.id.time_duration)
    val buttonQuality: TextView = controlsView.findViewById(R.id.button_quality)
    val buttonPlaylist: ImageButton = controlsView.findViewById(R.id.button_playlist)
    val buttonSettings: ImageButton = controlsView.findViewById(R.id.button_settings)

    // Settings Panel
    val titleContainer: View = topSettingsPanel.findViewById(R.id.title_container)
    val settingTitle: TextView = topSettingsPanel.findViewById(R.id.setting_title)
    val settingValue: TextView = topSettingsPanel.findViewById(R.id.setting_value)
    val btnSettingsPrev: View = topSettingsPanel.findViewById(R.id.btn_settings_prev)
    val btnSettingsNext: View = topSettingsPanel.findViewById(R.id.btn_settings_next)

    // Playlist Dialog
    private var playlistDialog: Dialog? = null
    private var playlistAdapter: PlaylistAdapter? = null

    fun setSurfaceMode(isStereo: Boolean) {
        if (isStereo) {
            aspectRatioFrame.visibility = View.GONE
            glSurfaceView.visibility = View.VISIBLE
            fpsCounterTextView.visibility = View.VISIBLE
        } else {
            aspectRatioFrame.visibility = View.VISIBLE
            glSurfaceView.visibility = View.GONE
            fpsCounterTextView.visibility = View.GONE
        }
    }

    fun setAspectRatio(ratio: Float) {
        aspectRatioFrame.setAspectRatio(ratio)
    }

    fun showControls(focusOnSeekBar: Boolean = false) {
        controlsView.visibility = View.VISIBLE
        topInfoPanel.visibility = View.VISIBLE
        if (focusOnSeekBar) seekBar.requestFocus() else playPauseButton.requestFocus()
    }

    fun hideControls() {
        controlsView.visibility = View.GONE
        topInfoPanel.visibility = View.GONE
        rootView.findViewById<View>(R.id.root_container).requestFocus()
    }

    fun loadPoster(uri: android.net.Uri?) {
        if (uri != null) {
            videoPoster.load(uri) {
                crossfade(true)
                error(R.drawable.ic_input_mode_auto)
                placeholder(R.drawable.ic_input_mode_auto)
            }
        } else {
            videoPoster.setImageResource(R.drawable.ic_input_mode_auto)
        }
    }

    fun showPlaylistDialog(
        items: List<MediaItem>,
        currentIndex: Int,
        onItemSelected: (Int) -> Unit,
        onDismiss: () -> Unit
    ) {
        val context = rootView.context
        if (playlistDialog == null) {
            playlistDialog = Dialog(context, android.R.style.Theme_Translucent_NoTitleBar)
            playlistDialog?.setContentView(R.layout.dialog_playlist)
            playlistDialog?.window?.apply {
                setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setGravity(Gravity.END)
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            }

            val recycler = playlistDialog?.findViewById<RecyclerView>(R.id.playlist_recycler)
            recycler?.layoutManager = LinearLayoutManager(context)
            playlistAdapter = PlaylistAdapter { index ->
                onItemSelected(index)
                playlistDialog?.dismiss()
            }
            recycler?.adapter = playlistAdapter
            playlistDialog?.setOnDismissListener { onDismiss() }
        }

        playlistAdapter?.submitList(items)
        playlistAdapter?.setCurrentIndex(currentIndex)

        val recycler = playlistDialog?.findViewById<RecyclerView>(R.id.playlist_recycler)
        recycler?.scrollToPosition(currentIndex)

        playlistDialog?.show()
    }

    fun updateSettingsText(
        type: SettingType,
        valueStr: String,
        isMatrixValid: Boolean = true,
        color: Int = Color.WHITE
    ) {
        settingValue.setTextColor(color)
        settingValue.paintFlags = if (!isMatrixValid) {
            settingValue.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        } else {
            settingValue.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        }

        val context = rootView.context
        settingValue.text = valueStr

        settingTitle.text = when (type) {
            SettingType.VIDEO_TYPE -> context.getString(R.string.setting_video_type)
            SettingType.OUTPUT_FORMAT -> context.getString(R.string.setting_output_format)
            SettingType.GLASSES_TYPE -> context.getString(R.string.setting_glasses_type)
            SettingType.FILTER_MODE -> context.getString(R.string.setting_filter)
            SettingType.CUSTOM_HUE_L -> "Оттенок (Левый)"
            SettingType.CUSTOM_HUE_R -> "Оттенок (Правый)"
            SettingType.CUSTOM_LEAK_L -> "Утечка (Левый)"
            SettingType.CUSTOM_LEAK_R -> "Утечка (Правый)"
            SettingType.CUSTOM_SPACE -> "Пространство"
            SettingType.SWAP_EYES -> context.getString(R.string.setting_swap_eyes)
            SettingType.DEPTH_3D -> context.getString(R.string.setting_depth)
            SettingType.SCREEN_SEPARATION -> context.getString(R.string.setting_screen_separation)
            SettingType.VR_DISTORTION -> context.getString(R.string.setting_vr_distortion)
            SettingType.VR_ZOOM -> context.getString(R.string.setting_vr_zoom)
            SettingType.AUDIO_TRACK -> context.getString(R.string.setting_audio_track)
            SettingType.SUBTITLES -> context.getString(R.string.setting_subtitles)
        }
    }

    fun updateBufferingState(isBuffering: Boolean, mode: StereoOutputMode?) {
        if (isBuffering) {
            if (mode == StereoOutputMode.CARDBOARD_VR && glSurfaceView.isVisible) {
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

    fun updateStereoLayout(mode: StereoOutputMode?, separation: Float) {
        if (!glSurfaceView.isVisible) {
            subtitleView.isVisible = true
            subtitleSplitContainer.isVisible = false
            return
        }

        if (mode == StereoOutputMode.CARDBOARD_VR) {
            subtitleView.isVisible = false
            subtitleSplitContainer.isVisible = true
        } else {
            subtitleView.isVisible = true
            subtitleSplitContainer.isVisible = false
        }

        val screenWidth = rootView.resources.displayMetrics.widthPixels
        val shiftPx = separation * screenWidth
        subtitleViewLeft.translationX = -shiftPx
        subtitleViewRight.translationX = shiftPx
        loaderLeft.translationX = -shiftPx
        loaderRight.translationX = shiftPx
    }

    fun updateInputModeIcon(type: StereoInputType, swapEyes: Boolean) {
        val iconRes = when (type) {
            StereoInputType.NONE -> R.drawable.ic_input_mode_mono

            StereoInputType.SIDE_BY_SIDE -> {
                if (swapEyes) R.drawable.ic_input_mode_ss_rl
                else R.drawable.ic_input_mode_ss_lr
            }

            StereoInputType.TOP_BOTTOM -> {
                if (swapEyes) R.drawable.ic_input_mode_ou_rl
                else R.drawable.ic_input_mode_ou_lr
            }

            StereoInputType.INTERLACED -> R.drawable.ic_input_mode_interlaced

            StereoInputType.TILED_1080P -> R.drawable.ic_input_mode_3dz
        }
        iconInputMode.setImageResource(iconRes)
    }

    fun formatTime(millis: Long): String {
        val seconds = (millis / 1000) % 60
        val minutes = (millis / (1000 * 60)) % 60
        val hours = (millis / (1000 * 60 * 60))
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }
}