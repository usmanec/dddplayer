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
import androidx.media3.common.PlaybackException
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.SubtitleView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import coil.load
import top.rootu.dddplayer.R
import top.rootu.dddplayer.model.MediaItem
import top.rootu.dddplayer.model.StereoInputType
import top.rootu.dddplayer.model.StereoOutputMode
import top.rootu.dddplayer.renderer.StereoGLSurfaceView
import top.rootu.dddplayer.ui.adapter.OptionsAdapter
import top.rootu.dddplayer.ui.adapter.PlaylistAdapter
import top.rootu.dddplayer.viewmodel.SettingType
import java.text.SimpleDateFormat
import java.util.Date
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
    val bufferingContainer: View = rootView.findViewById(R.id.buffering_container)
    val bufferingPercentage: TextView = rootView.findViewById(R.id.buffering_percentage)
    val loaderLeft: ProgressBar = rootView.findViewById(R.id.loader_left)
    val loaderRight: ProgressBar = rootView.findViewById(R.id.loader_right)

    // Panels
    val controlsView: View = rootView.findViewById(R.id.playback_controls)
    val topInfoPanel: View = rootView.findViewById(R.id.top_info_panel)
    val topSettingsPanel: View = rootView.findViewById(R.id.top_settings_panel)

    // Info Panel
    val videoTitleTextView: TextView = topInfoPanel.findViewById(R.id.video_title)
    val videoPoster: ImageView = topInfoPanel.findViewById(R.id.video_poster)
    val posterPlaceholderText: TextView = topInfoPanel.findViewById(R.id.poster_placeholder_text)
    val posterNumberBadge: TextView = topInfoPanel.findViewById(R.id.poster_number_badge)
    val textClock: TextView = topInfoPanel.findViewById(R.id.text_clock)
    val textEndsAt: TextView = topInfoPanel.findViewById(R.id.text_ends_at)
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
    val buttonAudio: ImageButton = controlsView.findViewById(R.id.button_audio)
    val buttonSubs: ImageButton = controlsView.findViewById(R.id.button_subs)
    val buttonSettings: ImageButton = controlsView.findViewById(R.id.button_settings)

    // Settings Panel
    val titleContainer: View = topSettingsPanel.findViewById(R.id.title_container)
    val settingTitle: TextView = topSettingsPanel.findViewById(R.id.setting_title)
    val settingValue: TextView = topSettingsPanel.findViewById(R.id.setting_value)
    val btnSettingsPrev: View = topSettingsPanel.findViewById(R.id.btn_settings_prev)
    val btnSettingsNext: View = topSettingsPanel.findViewById(R.id.btn_settings_next)
    val optionsRecycler: RecyclerView = rootView.findViewById(R.id.options_recycler)
    private val optionsAdapter = OptionsAdapter()

    // Playlist Dialog
    private var playlistDialog: Dialog? = null
    private var playlistAdapter: PlaylistAdapter? = null

    val errorScreen: View = rootView.findViewById(R.id.error_screen)
    val errorText: TextView = rootView.findViewById(R.id.error_text)
    val errorDetails: TextView = rootView.findViewById(R.id.error_details)

    val buttonUpdate: TextView = controlsView.findViewById(R.id.button_update)
    private var updateDialog: Dialog? = null

    val seekOverlay: View = rootView.findViewById(R.id.seek_overlay)
    val seekDeltaText: TextView = rootView.findViewById(R.id.seek_delta)
    val seekTargetText: TextView = rootView.findViewById(R.id.seek_target_time)

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    init {
        // Настройка ленты
        optionsRecycler.adapter = optionsAdapter
        // Отключаем перехват фокуса, чтобы лента не воровала управление у кнопок
        optionsRecycler.isFocusable = false
        optionsRecycler.isFocusableInTouchMode = false
    }

    fun showSeekOverlay(deltaMs: Long, targetTimeMs: Long) {
        seekOverlay.isVisible = true

        val sign = if (deltaMs > 0) "+" else "-"
        val absDelta = Math.abs(deltaMs)

        // Форматируем дельту (если < 1 мин, то просто секунды, иначе мин:сек)
        val deltaStr = if (absDelta < 60000) {
            "${absDelta / 1000}s"
        } else {
            formatTime(absDelta)
        }

        seekDeltaText.text = "$sign $deltaStr"
        seekTargetText.text = formatTime(targetTimeMs)
    }

    fun hideSeekOverlay() {
        seekOverlay.isVisible = false
    }

    fun showFatalError(error: PlaybackException) {
        errorText.text = "Ошибка воспроизведения"
        errorDetails.text = formatErrorDetails(error)

        errorScreen.isVisible = true
        errorScreen.setBackgroundColor(Color.parseColor("#CC000000")) // Темный фон

        bufferingContainer.isVisible = false
        bufferingSplitContainer.isVisible = false
    }

    fun showVideoErrorState(error: PlaybackException) {
        errorText.text = "Ошибка видео декодера.\nВоспроизводится только звук."
        errorDetails.text = formatErrorDetails(error)

        errorScreen.isVisible = true
        // Полупрозрачный фон, чтобы видеть постер (если есть)
        errorScreen.setBackgroundColor(Color.parseColor("#80000000"))

        bufferingContainer.isVisible = false
        bufferingSplitContainer.isVisible = false
    }

    fun hideError() {
        errorScreen.isVisible = false
    }

    private fun formatErrorDetails(error: PlaybackException): String {
        // Формируем строку: "Код ошибки (Число)\nСообщение"
        return "${error.errorCodeName} (${error.errorCode})\n${error.message ?: ""}"
    }

    fun updateSettingsOptions(optionsData: Pair<List<String>, Int>?) {
        if (optionsData != null && optionsData.first.size > 1) {
            val (list, index) = optionsData

            // 1. Устанавливаем паддинг (половина ширины экрана)
            val screenWidth = rootView.resources.displayMetrics.widthPixels
            val padding = screenWidth / 2
            // Проверяем, изменился ли паддинг, чтобы не перерисовывать лишний раз
            if (optionsRecycler.paddingLeft != padding) {
                optionsRecycler.setPadding(padding, 0, padding, 0)
            }

            optionsRecycler.isVisible = true

            // Используем submitData для преобразования строк в уникальные элементы
            optionsAdapter.submitData(list)
            optionsAdapter.setSelection(index)

            // 2. Скроллим
            // Используем scrollToPosition для мгновенного прыжка при первом показе,
            // и smoothScroll для анимации при смене.
            // Но так как мы не знаем, "первый" это показ или нет, используем smoothScroll всегда,
            // но с post, чтобы дать RecyclerView время на layout.
            optionsRecycler.post {
                val smoothScroller = object : LinearSmoothScroller(rootView.context) {
                    override fun getHorizontalSnapPreference(): Int = SNAP_TO_ANY
                    override fun calculateDtToFit(viewStart: Int, viewEnd: Int, boxStart: Int, boxEnd: Int, snapPreference: Int): Int {
                        val boxCenter = boxStart + (boxEnd - boxStart) / 2
                        val viewCenter = viewStart + (viewEnd - viewStart) / 2
                        return boxCenter - viewCenter
                    }
                }
                smoothScroller.targetPosition = index
                optionsRecycler.layoutManager?.startSmoothScroll(smoothScroller)
            }

        } else {
            optionsRecycler.isVisible = false
        }
    }

    /**
     * Обновляет текстовые метки времени.
     * @param currentMs Текущая позиция в мс
     * @param durationMs Общая длительность в мс
     */
    fun updateTimeLabels(currentMs: Long, durationMs: Long) {
        // 1. Левая метка: "00:10:23" (Текущее)
        timeCurrentTextView.text = formatTime(currentMs)

        if (durationMs > 0) {
            val remainingMs = (durationMs - currentMs).coerceAtLeast(0)

            // 2. Правая метка: "- 01:44:37" (Осталось)
            timeDurationTextView.text = "- ${formatTime(remainingMs)}"

            // 3. Верхняя метка: "Конец в 23:15" (Реальное время)
            val endTimeMs = System.currentTimeMillis() + remainingMs
            val endTimeStr = timeFormat.format(Date(endTimeMs))

            textEndsAt.text = rootView.context.getString(R.string.time_ends_at, endTimeStr)
            textEndsAt.isVisible = true
        } else {
            // Если длительность неизвестна (стрим)
            timeDurationTextView.text = "--:--"
            textEndsAt.isVisible = false
        }
    }

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
        optionsRecycler.visibility = View.GONE
        rootView.findViewById<View>(R.id.root_container).requestFocus()
    }

    fun loadPoster(uri: android.net.Uri?, playlistIndex: Int, playlistSize: Int) {
        val isPlaylist = playlistSize > 1
        val numberText = (playlistIndex + 1).toString()

        posterNumberBadge.text = numberText
        posterPlaceholderText.text = numberText

        if (uri != null) {
            // Постер есть -> Грузим
            videoPoster.isVisible = true
            posterPlaceholderText.isVisible = false
            // Если плейлист -> показываем бейдж
            posterNumberBadge.isVisible = isPlaylist

            videoPoster.load(uri) {
                crossfade(true)
                listener(onError = { _, _ -> handleNoPoster(isPlaylist)})
            }
        } else {
            // Постера нет
            handleNoPoster(isPlaylist)
        }
    }

    private fun handleNoPoster(isPlaylist: Boolean) {
        if (isPlaylist) {
            // Плейлист без постера -> Заглушка с номером
            videoPoster.isVisible = false
            posterNumberBadge.isVisible = false
            posterPlaceholderText.isVisible = true
        } else {
            // Одиночное видео без постера -> Дефолтная иконка (старая логика)
            videoPoster.isVisible = true
            videoPoster.setImageResource(R.drawable.tv_banner)

            posterNumberBadge.isVisible = false
            posterPlaceholderText.isVisible = false
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
            playlistDialog?.setCanceledOnTouchOutside(true)
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

        // Скроллим к текущему элементу (ВСЕГДА, даже если диалог переиспользован)
        val recycler = playlistDialog?.findViewById<RecyclerView>(R.id.playlist_recycler)
        // Используем post, чтобы скролл сработал после того, как RecyclerView обновит лейаут
        recycler?.post {
            recycler.scrollToPosition(currentIndex)

            // Ждем, пока скролл завершится и элемент появится, затем фокусируемся
            recycler.postDelayed({
                val holder = recycler.findViewHolderForAdapterPosition(currentIndex)
                if (holder != null) {
                    holder.itemView.requestFocus()
                } else {
                    // Если ViewHolder все еще null (например, список длинный и scrollToPosition не успел),
                    // можно попробовать жесткий скролл или просто оставить как есть.
                    // Но вроде как postDelayed(50-100ms) должно хватать.
                }
            }, 100)
        }

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

    fun updateBufferingState(isBuffering: Boolean, mode: StereoOutputMode?, percent: Int) {
        if (isBuffering) {
            if (mode == StereoOutputMode.CARDBOARD_VR && glSurfaceView.isVisible) {
                bufferingContainer.isVisible = false
                bufferingSplitContainer.isVisible = true
            } else {
                bufferingContainer.isVisible = true
                bufferingSplitContainer.isVisible = false
                bufferingPercentage.text = "$percent%"
            }
        } else {
            bufferingContainer.isVisible = false
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