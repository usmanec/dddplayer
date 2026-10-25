package top.rootu.dddplayer.ui.controller

import android.app.Dialog
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.toColorInt
import androidx.core.view.isVisible
import androidx.media3.common.PlaybackException
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.SubtitleView
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import top.rootu.dddplayer.R
import top.rootu.dddplayer.data.SettingsRepository
import top.rootu.dddplayer.logic.PlaybackErrorLogic
import top.rootu.dddplayer.model.MediaItem
import top.rootu.dddplayer.model.ResizeMode
import top.rootu.dddplayer.model.StereoInputType
import top.rootu.dddplayer.model.StereoOutputMode
import top.rootu.dddplayer.model.SubtitleStyle
import top.rootu.dddplayer.renderer.StereoGLSurfaceView
import top.rootu.dddplayer.ui.PosterCard
import top.rootu.dddplayer.ui.SubtitleStyleOptions
import top.rootu.dddplayer.ui.adapter.OptionsAdapter
import top.rootu.dddplayer.ui.adapter.PlaylistAdapter
import top.rootu.dddplayer.ui.widget.CenterLayoutManager
import top.rootu.dddplayer.ui.widget.OutlineTextView
import top.rootu.dddplayer.viewmodel.SettingType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil

class PlayerUiController(private val rootView: View) {

    // Surfaces & Containers
    val aspectRatioFrame: AspectRatioFrameLayout = rootView.findViewById(R.id.aspect_ratio_frame)
    val standardSurfaceView: SurfaceView = rootView.findViewById(R.id.surface_view_standard)
    val glSurfaceView: StereoGLSurfaceView = rootView.findViewById(R.id.gl_surface_view)

    // Views
    val dimOverlay: View = rootView.findViewById(R.id.dim_overlay)
    val touchZoneTop: View = rootView.findViewById(R.id.touch_zone_top)
    val subtitleView: SubtitleView = rootView.findViewById(R.id.subtitle_view)
    val subtitleSplitContainer: View = rootView.findViewById(R.id.subtitle_split_container)
    val subtitleViewLeft: SubtitleView = rootView.findViewById(R.id.subtitle_view_left)
    val subtitleViewRight: SubtitleView = rootView.findViewById(R.id.subtitle_view_right)
    val standaloneClock: OutlineTextView = rootView.findViewById(R.id.standalone_clock)

    // Buffering
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
    val badgeStereo: TextView = topInfoPanel.findViewById(R.id.badge_stereo)
    val badgeResolution: TextView = topInfoPanel.findViewById(R.id.badge_resolution)
    val badgeAudio: TextView = topInfoPanel.findViewById(R.id.badge_audio)
    val badgeSubtitle: TextView = topInfoPanel.findViewById(R.id.badge_subtitle)
    val buttonBack: ImageButton = topInfoPanel.findViewById(R.id.button_back)

    // Controls
    val playPauseButton: ImageButton = controlsView.findViewById(R.id.button_play_pause)
    val rewindButton: ImageButton = controlsView.findViewById(R.id.button_rewind)
    val ffwdButton: ImageButton = controlsView.findViewById(R.id.button_ffwd)
    val prevButton: ImageButton = controlsView.findViewById(R.id.button_prev)
    val nextButton: ImageButton = controlsView.findViewById(R.id.button_next)
    val seekBar: SeekBar = controlsView.findViewById(R.id.seek_bar)
    val bufferingValueTextView: TextView = controlsView.findViewById(R.id.buffering_value)
    val timeCurrentTextView: TextView = controlsView.findViewById(R.id.time_current)
    val timeDurationTextView: TextView = controlsView.findViewById(R.id.time_duration)
    val buttonQuality: TextView = controlsView.findViewById(R.id.button_quality)
    val buttonSpeed: TextView = controlsView.findViewById(R.id.button_speed)
    val buttonResize: ImageButton = controlsView.findViewById(R.id.button_resize)
    val buttonPip: ImageButton = controlsView.findViewById(R.id.button_pip)
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
    val errorHint: TextView = rootView.findViewById(R.id.error_hint)

    val buttonUpdate: TextView = controlsView.findViewById(R.id.button_update)

    val seekOverlay: View = rootView.findViewById(R.id.seek_overlay)
    val seekDeltaText: TextView = rootView.findViewById(R.id.seek_delta)
    val seekTargetText: TextView = rootView.findViewById(R.id.seek_target_time)

    private val gestureContainer: View = rootView.findViewById(R.id.gesture_indicator_container)
    private val gestureIcon: ImageView = rootView.findViewById(R.id.gesture_icon)
    private val gestureProgress: ProgressBar = rootView.findViewById(R.id.gesture_progress)
    private val gestureText: TextView = rootView.findViewById(R.id.gesture_text)

    private val rewindOverlay: View = rootView.findViewById(R.id.rewind_overlay)
    private val ffwdOverlay: View = rootView.findViewById(R.id.ffwd_overlay)
    private val rewindText: TextView = rootView.findViewById(R.id.rewind_text)
    private val ffwdText: TextView = rootView.findViewById(R.id.ffwd_text)

    private val overlayPrev: View = rootView.findViewById(R.id.overlay_swipe_prev)
    private val iconPrev: ImageView = rootView.findViewById(R.id.icon_swipe_prev)
    private val textPrevTitle: TextView = rootView.findViewById(R.id.text_swipe_prev_title)
    private val textPrevHint: TextView = rootView.findViewById(R.id.text_swipe_prev_hint)
    private val cardPrev: View = rootView.findViewById(R.id.card_swipe_prev)

    private val overlayNext: View = rootView.findViewById(R.id.overlay_swipe_next)
    private val iconNext: ImageView = rootView.findViewById(R.id.icon_swipe_next)
    private val textNextTitle: TextView = rootView.findViewById(R.id.text_swipe_next_title)
    private val textNextHint: TextView = rootView.findViewById(R.id.text_swipe_next_hint)
    private val cardNext: View = rootView.findViewById(R.id.card_swipe_next)
    // Видео, уже показанное в карточке шторки (свайп обновляет шторку на каждом движении пальца)
    private val boundSwipeCards = mutableMapOf<View, String?>()
    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideGestureRunnable = Runnable {
        gestureContainer.isVisible = false
        rewindOverlay.isVisible = false
        ffwdOverlay.isVisible = false
    }

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val timeFormatSeconds = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // Прозрачность одиночных часов из настроек (CLOCK_OFF = часы выключены)
    private var clockTransparency: Int = SettingsRepository.CLOCK_OFF

    // Автоскрытие инфопанели, показанной без панели управления
    private val hideInfoPanelRunnable = Runnable { hideInfoPanel() }

    private var inPictureInPicture = false

    fun setPictureInPictureMode(enabled: Boolean) {
        inPictureInPicture = enabled
        if (enabled) {
            playlistDialog?.dismiss()
            hideControls()
            topSettingsPanel.isVisible = false
            hideHandler.removeCallbacks(hideGestureRunnable)
            hideHandler.removeCallbacks(hideInfoPanelRunnable)
            topInfoPanel.isVisible = false
            listOf(seekOverlay, gestureContainer, rewindOverlay, ffwdOverlay,
                overlayPrev, overlayNext, dimOverlay).forEach {
                it.animate().cancel()
                it.isVisible = false
            }
        } else {
            showControls()
        }
        updateClockVisibility()
    }

    init {
        // Настройка ленты
        optionsRecycler.adapter = optionsAdapter
        // Отключаем перехват фокуса, чтобы лента не воровала управление у кнопок
        optionsRecycler.isFocusable = false
        optionsRecycler.isFocusableInTouchMode = false

        // ВАЖНО: Отключаем анимации изменений.
        // Это убирает "дерганье" и эффект морфинга при смене списков (например, Аудио -> Субтитры).
        optionsRecycler.itemAnimator = null
    }

    fun updateClockTransparency(transparency: Int) {
        clockTransparency = transparency
        if (transparency != SettingsRepository.CLOCK_OFF) {
            standaloneClock.alpha = (100 - transparency) / 100f
        }
        updateClockVisibility()
    }

    private fun updateClockVisibility() {
        standaloneClock.isVisible = !inPictureInPicture &&
            clockTransparency != SettingsRepository.CLOCK_OFF && !topInfoPanel.isVisible
    }

    /** Карточка соседнего видео вместо иконки; без [target] (выход) - иконка. */
    private fun setSwipeTarget(icon: ImageView, card: View, target: MediaItem?, iconRes: Int, showIndexBadge: Boolean) {
        icon.isVisible = target == null
        card.isVisible = target != null
        if (target == null) {
            icon.setImageResource(iconRes)
            return
        }
        val key = "${target.uuid}/$showIndexBadge"
        if (boundSwipeCards[card] == key) return
        boundSwipeCards[card] = key
        PosterCard.bind(
            card.findViewById(R.id.swipe_poster_image),
            card.findViewById(R.id.swipe_poster_placeholder),
            card.findViewById(R.id.swipe_poster_badge),
            target.posterUri, target.absoluteIndex, isSingleVideo = false, showIndexBadge = showIndexBadge
        )
    }

    /**
     * Инфопанель с данными выбранного (еще не включенного) канала. Не скрывается сама,
     * пока идет выбор канала.
     */
    fun showInfoPanelWithData(item: MediaItem, playlistSize: Int, showIndex: Boolean) {
        if (inPictureInPicture) return
        hideHandler.removeCallbacks(hideInfoPanelRunnable)
        topInfoPanel.isVisible = true
        updateClockVisibility()
        videoTitleTextView.text = item.title ?: item.filename ?: item.uri.lastPathSegment
        loadPoster(item.posterUri, item.absoluteIndex, playlistSize, showIndex)
    }

    /**
     * Показывает только верхнюю инфопанель (без панели управления) и скрывает ее через 3 секунды.
     */
    fun showInfoPanelOnly() {
        if (inPictureInPicture) return
        topInfoPanel.isVisible = true
        updateClockVisibility()
        hideHandler.removeCallbacks(hideInfoPanelRunnable)
        if (!controlsView.isVisible) {
            hideHandler.postDelayed(hideInfoPanelRunnable, 3000)
        }
    }

    fun hideInfoPanel() {
        hideHandler.removeCallbacks(hideInfoPanelRunnable)
        if (!controlsView.isVisible) {
            topInfoPanel.isVisible = false
            updateClockVisibility()
        }
    }

    fun showVolumeIndicator(percent: Int) {
        showGestureIndicator(R.drawable.ic_volume_up, percent)
    }

    fun showBrightnessIndicator(percent: Int) {
        showGestureIndicator(R.drawable.ic_brightness_medium, percent)
    }

    private fun showGestureIndicator(iconRes: Int, percent: Int) {
        hideHandler.removeCallbacks(hideGestureRunnable)
        gestureContainer.isVisible = true
        gestureProgress.isVisible = true
        gestureIcon.setImageResource(iconRes)
        gestureProgress.progress = percent
        gestureText.text = rootView.context.getString(R.string.percentage_int_format, percent)
    }

    fun hideGestureIndicatorDelayed() {
        hideHandler.removeCallbacks(hideGestureRunnable)
        hideHandler.postDelayed(hideGestureRunnable, 1000) // Скрыть через 1 сек
    }

    fun showDoubleTapOverlay(forward: Boolean, seconds: Int) {
        hideHandler.removeCallbacks(hideGestureRunnable)

        if (forward) {
            ffwdOverlay.isVisible = true
            rewindOverlay.isVisible = false
            ffwdText.text = "+${seconds}"
        } else {
            ffwdOverlay.isVisible = false
            rewindOverlay.isVisible = true
            rewindText.text = "-${seconds}"
        }

        hideHandler.postDelayed(hideGestureRunnable, 600) // Быстрое скрытие
    }

    /**
     * Обновляет положение шторки плейлиста.
     * @param offsetPx Смещение в пикселях (положительное = тянем вправо, отрицательное = влево)
     * @param screenWidth Ширина экрана
     * @param title Заголовок следующего/предыдущего видео (или "Выход")
     * @param isExitMode Если true, показываем иконку выхода
     * @param target Видео, на которое переключит свайп: карточка с постером или номером вместо иконки
     */
    fun updatePlaylistSwipe(offsetPx: Float, screenWidth: Int, title: String, isExitMode: Boolean, target: MediaItem? = null, showIndexBadge: Boolean = true) {
        gestureContainer.isVisible = false

        // Порог срабатывания (50% экрана)
        val thresholdPassed = abs(offsetPx) > (screenWidth / 2)

        // Прозрачность подсказки: ярче, когда готовы переключить
        val hintAlpha = if (thresholdPassed) 1.0f else 0.7f
        val context = rootView.context

        if (offsetPx > 0) {
            // === PREV (Слева) ===
            overlayNext.isVisible = false
            overlayPrev.isVisible = true

            // Двигаем шторку
            overlayPrev.translationX = -screenWidth.toFloat() + offsetPx

            textPrevTitle.text = title
            textPrevHint.alpha = hintAlpha

            setSwipeTarget(iconPrev, cardPrev, target, if (isExitMode) R.drawable.ic_exit else R.drawable.ic_skip_previous, showIndexBadge)
            if (isExitMode) {
                textPrevHint.text = if (thresholdPassed)
                    context.getString(R.string.swipe_release_exit)
                else
                    context.getString(R.string.swipe_pull_exit)
            } else {
                textPrevHint.text = if (thresholdPassed)
                    context.getString(R.string.swipe_release_next)
                else
                    context.getString(R.string.swipe_pull_next)
            }

        } else {
            // === NEXT (Справа) ===
            overlayPrev.isVisible = false
            overlayNext.isVisible = true

            // Двигаем шторку
            overlayNext.translationX = screenWidth.toFloat() + offsetPx

            textNextTitle.text = title
            textNextHint.alpha = hintAlpha

            setSwipeTarget(iconNext, cardNext, target, if (isExitMode) R.drawable.ic_exit else R.drawable.ic_skip_next, showIndexBadge)
            if (isExitMode) {
                textNextHint.text = if (thresholdPassed)
                    context.getString(R.string.swipe_release_exit)
                else
                    context.getString(R.string.swipe_pull_exit)
            } else {
                textNextHint.text = if (thresholdPassed)
                    context.getString(R.string.swipe_release_next)
                else
                    context.getString(R.string.swipe_pull_next)
            }
        }
    }

    /**
     * Анимация завершения (растягивание на весь экран и действие)
     */
    fun animatePlaylistSwipeConfirm(isNext: Boolean, onAnimationEnd: () -> Unit) {
        val targetView = if (isNext) overlayNext else overlayPrev

        targetView.animate()
            .translationX(0f) // Доводим до центра (0)
            .setDuration(200)
            .withEndAction {
                onAnimationEnd()
                // Скрываем после действия (с задержкой или сразу, зависит от логики переключения)
                targetView.isVisible = false
            }
            .start()
    }

    /**
     * Анимация отмены (схлопывание обратно)
     */
    fun animatePlaylistSwipeCancel(screenWidth: Int) {
        if (overlayPrev.isVisible) {
            overlayPrev.animate()
                .translationX(-screenWidth.toFloat())
                .setDuration(300)
                .withEndAction { overlayPrev.isVisible = false }
                .start()
        }
        if (overlayNext.isVisible) {
            overlayNext.animate()
                .translationX(screenWidth.toFloat())
                .setDuration(300)
                .withEndAction { overlayNext.isVisible = false }
                .start()
        }
    }

    fun showSeekOverlay(deltaMs: Long, targetTimeMs: Long, isLive: Boolean = false, liveOffsetMs: Long = 0L) {
        seekOverlay.isVisible = true
        val sign = if (deltaMs > 0) "+" else "-"
        val absDelta = abs(deltaMs)
        val deltaStr = if (absDelta < 60000) "${absDelta / 1000}s" else formatTime(absDelta)
        seekDeltaText.text = rootView.context.getString(R.string.seek_delta_format, sign, deltaStr)
        if (isLive) {
            // В эфире показываем реальное время, на которое целимся
            val wallClockTarget = System.currentTimeMillis() - abs(liveOffsetMs)
            seekTargetText.text = timeFormatSeconds.format(Date(wallClockTarget))
        } else {
            seekTargetText.text = formatTime(targetTimeMs)
        }
    }

    fun hideSeekOverlay() {
        seekOverlay.isVisible = false
    }

    fun showFatalError(error: PlaybackException) {
        errorText.text = rootView.context.getString(R.string.error_playback_title)
        errorDetails.text = formatErrorDetails(error)
        showErrorHint(error)
        errorScreen.isVisible = true
        errorScreen.setBackgroundColor("#CC000000".toColorInt())
        bufferingContainer.isVisible = false
        bufferingSplitContainer.isVisible = false
    }

    fun showVideoErrorState(error: PlaybackException) {
        errorText.text = rootView.context.getString(R.string.error_video_decoder)
        errorDetails.text = formatErrorDetails(error)
        showErrorHint(error)
        errorScreen.isVisible = true
        errorScreen.setBackgroundColor("#80000000".toColorInt())
        bufferingContainer.isVisible = false
        bufferingSplitContainer.isVisible = false
    }

    fun hideError() {
        errorScreen.isVisible = false
    }

    // Подсказка для известных причин: файл не отдало приложение-источник (content://)
    private fun showErrorHint(error: PlaybackException) {
        val isIoError = error.errorCode in 2000..2999
        val showHint = isIoError && PlaybackErrorLogic.isContentSourceError(error)
        errorHint.isVisible = showHint
        if (showHint) errorHint.setText(R.string.error_hint_content_source)
    }

    private fun formatErrorDetails(error: PlaybackException): String {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                val responseCode = cause.responseCode
                val responseMessage = cause.responseMessage ?: ""

                // Получаем URI и обрезаем, если он слишком длинный
                val fullUri = cause.dataSpec.uri.toString()
                val displayUri = if (fullUri.length > 120) {
                    // Оставляем первые 80 символов и последние 30
                    "${fullUri.take(80)}...${fullUri.takeLast(30)}"
                } else {
                    fullUri
                }

                return "HTTP $responseCode $responseMessage\n$displayUri"
            }
            cause = cause.cause
        }

        // ограничиваем длину сообщения
        val msg = error.message ?: ""
        val displayMsg = if (msg.length > 303) msg.take(300) + "..." else msg

        // Причины ниже кода ошибки: там видно, что именно не удалось (например, ответ файлового менеджера)
        val causes = PlaybackErrorLogic.causeLines(error).joinToString("") { "\n→ $it" }
        return "${error.errorCodeName} (${error.errorCode})\n$displayMsg$causes"
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

            // Используем callback, чтобы скроллить только ПОСЛЕ обновления списка
            optionsAdapter.submitData(list) {
                optionsAdapter.setSelection(index)

                // Используем post, чтобы дать RecyclerView время на layout (измерение ширины элементов)
                optionsRecycler.post {
                    // Используем кастомный SmoothScroller для точного центрирования
                    val smoothScroller = object : LinearSmoothScroller(rootView.context) {
                        override fun getHorizontalSnapPreference(): Int = SNAP_TO_ANY

                        // Вычисляем смещение так, чтобы центр элемента совпал с центром контейнера
                        override fun calculateDtToFit(viewStart: Int, viewEnd: Int, boxStart: Int, boxEnd: Int, snapPreference: Int): Int {
                            val boxCenter = boxStart + (boxEnd - boxStart) / 2
                            val viewCenter = viewStart + (viewEnd - viewStart) / 2
                            return boxCenter - viewCenter
                        }

                        // Делаем скролл быстрым, чтобы это выглядело отзывчиво
                        override fun calculateTimeForScrolling(dx: Int): Int {
                            return 100.coerceAtMost(super.calculateTimeForScrolling(dx))
                        }
                    }
                    smoothScroller.targetPosition = index
                    optionsRecycler.layoutManager?.startSmoothScroll(smoothScroller)
                }
            }

        } else {
            optionsRecycler.isVisible = false
        }
    }

    /**
     * Обновляет текстовые метки времени.
     * @param currentMs Текущая позиция
     * @param durationMs Общая длительность
     * @param speed Текущая скорость (для расчета времени окончания)
     * @param isLive Флаг прямого эфира
     * @param liveOffsetMs Смещение прямого эфира
     */
    fun updateTimeLabels(
        currentMs: Long,
        durationMs: Long,
        speed: Float,
        isLive: Boolean,
        liveOffsetMs: Long
    ) {
        val colorWhite = ContextCompat.getColor(rootView.context, R.color.tv_white)
        val colorRed = ContextCompat.getColor(rootView.context, R.color.error_red)

        if (isLive) {
            // Вычисляем реальное время: Сейчас + Смещение (оно отрицательное)
            // Если liveOffsetMs = TIME_UNSET, считаем что это 0 (прямой эфир)
            val safeOffset = if (liveOffsetMs != androidx.media3.common.C.TIME_UNSET) liveOffsetMs else 0L
            val wallClockTime = System.currentTimeMillis() - safeOffset

            timeCurrentTextView.text = timeFormatSeconds.format(Date(wallClockTime))

            val absOffset = abs(safeOffset)
            val textDuration = StringBuilder()
            textDuration.append(rootView.context.getString(R.string.state_live))
            if (absOffset < 60000) {
                timeDurationTextView.setTextColor(colorRed)
                textEndsAt.setTextColor(colorRed)
            } else {
                textDuration.append(" ")
                timeDurationTextView.text = textDuration.append(
                    rootView.context.getString(R.string.seek_delta_format, "-", formatTime(absOffset))
                )
                timeDurationTextView.setTextColor(colorWhite)
                textEndsAt.setTextColor(colorWhite)
            }

            timeDurationTextView.text = textDuration.toString()
            // Ширина под самый длинный вариант: "Эфир -ч:мм:сс" (не меньше минут), чтобы полоса не дергалась
            fitTimeWidth(timeCurrentTextView, timeCurrentTextView.text)
            fitTimeWidth(
                timeDurationTextView,
                rootView.context.getString(R.string.state_live) + " " +
                    rootView.context.getString(R.string.seek_delta_format, "-", formatTime(absOffset.coerceAtLeast(60_000)))
            )

            textEndsAt.text = rootView.context.getString(R.string.state_live)
            textEndsAt.isVisible = true
            return
        }

        timeCurrentTextView.text = formatTime(currentMs)
        // Текущее и оставшееся время - в формате длительности (с часами, если видео длиннее часа)
        val longest = formatTime(maxOf(durationMs, currentMs))
        fitTimeWidth(timeCurrentTextView, longest)
        fitTimeWidth(timeDurationTextView, rootView.context.getString(R.string.seek_delta_format, "-", longest))
        timeDurationTextView.setTextColor(colorWhite)
        textEndsAt.setTextColor(colorWhite)
        if (durationMs > 0) {
            val remainingMs = (durationMs - currentMs).coerceAtLeast(0)
            timeDurationTextView.text = rootView.context.getString(R.string.seek_delta_format, "-", formatTime(remainingMs))

            // Если скорость 2.0x, то 10 минут видео пройдут за 5 минут реального времени
            val realTimeLeftMs = (remainingMs / speed).toLong()
            val endTimeMs = System.currentTimeMillis() + realTimeLeftMs

            val endTimeStr = timeFormat.format(Date(endTimeMs))
            textEndsAt.text = rootView.context.getString(R.string.time_ends_at, endTimeStr)
            textEndsAt.isVisible = true
        } else {
            // Если длительность неизвестна (стрим?)
            timeDurationTextView.text = "--:--"
            textEndsAt.isVisible = false
        }
    }

    fun setSurfaceMode(isStereo: Boolean) {
        if (isStereo) {
            aspectRatioFrame.visibility = View.GONE
            glSurfaceView.visibility = View.VISIBLE
        } else {
            aspectRatioFrame.visibility = View.VISIBLE
            glSurfaceView.visibility = View.GONE
        }
    }

    fun setAspectRatio(ratio: Float) {
        aspectRatioFrame.setAspectRatio(ratio)
    }

    /**
     * Устанавливает режим масштабирования для AspectRatioFrameLayout.
     * В режиме ZOOM использует RESIZE_MODE_FIT и применяет ручное масштабирование.
     */
    fun setResizeMode(mode: ResizeMode, scalePercent: Int) {
        val resizeMode = when (mode) {
            ResizeMode.FIT, ResizeMode.SCALE -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            ResizeMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            ResizeMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        }
        aspectRatioFrame.resizeMode = resizeMode

        // Применяем ручное масштабирование только в режиме SCALE
        if (mode == ResizeMode.SCALE) {
            // Масштаб: 1.0 (100%) до 2.0 (200%)
            val scale = scalePercent / 100f
            aspectRatioFrame.scaleX = scale
            aspectRatioFrame.scaleY = scale
        } else {
            // Сбрасываем масштабирование для всех остальных режимов
            aspectRatioFrame.scaleX = 1.0f
            aspectRatioFrame.scaleY = 1.0f
        }
        // Сдвиг картинки возможен только в пределах увеличения (без увеличения - сброс)
        panVideo(0f, 0f)
    }

    /** Сдвигает увеличенную картинку (жест двумя пальцами), не выходя за края увеличения. */
    fun panVideo(dx: Float, dy: Float) {
        val scale = aspectRatioFrame.scaleX
        val maxX = (aspectRatioFrame.width * (scale - 1f) / 2f).coerceAtLeast(0f)
        val maxY = (aspectRatioFrame.height * (scale - 1f) / 2f).coerceAtLeast(0f)
        aspectRatioFrame.translationX = (aspectRatioFrame.translationX + dx).coerceIn(-maxX, maxX)
        aspectRatioFrame.translationY = (aspectRatioFrame.translationY + dy).coerceIn(-maxY, maxY)
    }

    fun showControls(focusOnSeekBar: Boolean = false) {
        if (inPictureInPicture) return
        controlsView.visibility = View.VISIBLE
        topInfoPanel.visibility = View.VISIBLE
        updateClockVisibility() // Скрываем одиночные часы, т.к. показана панель

        if (focusOnSeekBar) seekBar.requestFocus() else playPauseButton.requestFocus()
    }

    fun hideControls() {
        controlsView.visibility = View.GONE
        topInfoPanel.visibility = View.GONE
        optionsRecycler.visibility = View.GONE
        updateClockVisibility() // Показываем одиночные часы, если они разрешены

        rootView.findViewById<View>(R.id.root_container).requestFocus()
    }

    fun loadPoster(uri: android.net.Uri?, playlistIndex: Int, playlistSize: Int, showIndex: Boolean) {
        PosterCard.bind(videoPoster, posterPlaceholderText, posterNumberBadge, uri, playlistIndex, playlistSize <= 1, showIndex)
    }

    // Колбэки текущего показа плейлиста (диалог переиспользуется между показами)
    private var playlistOnItemSelected: ((Int) -> Unit)? = null
    private var playlistOnDismiss: (() -> Unit)? = null

    /**
     * @param items элементы для показа (может быть отфильтрованный по группе список)
     * @param onItemSelected позиция в [items]
     */
    fun showPlaylistDialog(
        items: List<MediaItem>,
        playingUuid: String?,
        showIndexBadge: Boolean,
        shouldLoop: Boolean,
        fitPosters: Boolean,
        isSingleVideo: Boolean,
        onItemSelected: (Int) -> Unit,
        onDismiss: () -> Unit
    ) {
        val context = rootView.context
        playlistOnItemSelected = onItemSelected
        playlistOnDismiss = onDismiss
        if (playlistDialog == null) {
            playlistDialog = Dialog(context, android.R.style.Theme_Translucent_NoTitleBar)
            playlistDialog?.setContentView(R.layout.dialog_playlist)
            playlistDialog?.setCanceledOnTouchOutside(true)
            playlistDialog?.window?.apply {
                setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setGravity(Gravity.END)
                setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            }

            val recycler = playlistDialog?.findViewById<RecyclerView>(R.id.playlist_recycler)
            recycler?.layoutManager = CenterLayoutManager(context)
            playlistAdapter = PlaylistAdapter(
                onLoopRequest = { targetPos ->
                    recycler?.scrollToPosition(targetPos)
                    recycler?.postDelayed({
                        recycler.findViewHolderForAdapterPosition(targetPos)?.itemView?.requestFocus()
                    }, 50)
                },
                onItemClick = { index ->
                    playlistOnItemSelected?.invoke(index)
                    playlistDialog?.dismiss()
                }
            )
            recycler?.adapter = playlistAdapter
            playlistDialog?.setOnDismissListener { playlistOnDismiss?.invoke() }
        }

        playlistAdapter?.shouldLoop = shouldLoop
        playlistAdapter?.fitPosters = fitPosters
        playlistAdapter?.isSingleVideo = isSingleVideo
        playlistAdapter?.setShowIndexBadge(showIndexBadge)
        updatePlaylistItems(items, playingUuid)
        playlistDialog?.show()
    }

    val isPlaylistDialogShowing: Boolean
        get() = playlistDialog?.isShowing == true

    fun setPlaylistKeyListener(listener: (keyCode: Int, event: android.view.KeyEvent) -> Boolean) {
        playlistDialog?.setOnKeyListener { _, keyCode, event -> listener(keyCode, event) }
    }

    fun setPlaylistHeaderClickListener(listener: (() -> Unit)?) {
        playlistDialog?.findViewById<View>(R.id.playlist_header)?.apply {
            setOnClickListener(listener?.let { l -> View.OnClickListener { l() } })
            isClickable = listener != null
        }
    }

    fun updatePlaylistHeader(text: String) {
        playlistDialog?.findViewById<TextView>(R.id.playlist_header)?.text = text
    }

    fun updatePlaylistItems(items: List<MediaItem>, playingUuid: String?) {
        playlistAdapter?.setPlayingItemUuid(playingUuid)
        playlistAdapter?.submitList(items) {
            val recycler = playlistDialog?.findViewById<RecyclerView>(R.id.playlist_recycler) ?: return@submitList
            val targetIdx = items.indexOfFirst { it.uuid == playingUuid }.coerceAtLeast(0)

            // Используем post, чтобы скролл сработал после того, как RecyclerView обновит лейаут
            recycler.post {
                recycler.scrollToPosition(targetIdx)
                // Ждем, пока скролл завершится и элемент появится, затем фокусируемся
                recycler.postDelayed({
                    recycler.findViewHolderForAdapterPosition(targetIdx)?.itemView?.requestFocus()
                }, 50)
            }
        }
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
            SettingType.CUSTOM_HUE_L -> context.getString(R.string.setting_hue_l)
            SettingType.CUSTOM_HUE_R -> context.getString(R.string.setting_hue_r)
            SettingType.CUSTOM_LEAK_L -> context.getString(R.string.setting_leak_l)
            SettingType.CUSTOM_LEAK_R -> context.getString(R.string.setting_leak_r)
            SettingType.CUSTOM_SPACE -> context.getString(R.string.setting_space)
            SettingType.RENDER_API -> context.getString(R.string.setting_render_api)
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
                bufferingPercentage.text = rootView.context.getString(R.string.percentage_int_format, percent)
            }
        } else {
            bufferingContainer.isVisible = false
            bufferingSplitContainer.isVisible = false
        }
    }

    /**
     * Процент буфера под полосой перемотки (режим BUFFER_DISPLAY_PANEL).
     */
    fun updateBufferDisplay(percent: Int, displayMode: Int) {
        if (displayMode == SettingsRepository.BUFFER_DISPLAY_PANEL) {
            bufferingValueTextView.isVisible = true
            bufferingValueTextView.text = rootView.context.getString(R.string.buffer_percentage, percent)
        } else {
            bufferingValueTextView.isVisible = false
        }
    }

    /** Стиль субтитров из настроек - для обычного слоя и двойного (Cardboard VR). */
    fun applySubtitleStyle(style: SubtitleStyle) {
        listOf(subtitleView, subtitleViewLeft, subtitleViewRight).forEach { SubtitleStyleOptions.apply(it, style) }
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

    /** Бейдж стереоформата ("3D (HSBS) RL", "2D (OU) L"); null - видео не стерео, бейдж скрыт */
    fun updateStereoBadge(label: String?) {
        badgeStereo.text = label.orEmpty()
        badgeStereo.isVisible = label != null
    }

    // Шаблоны, под которые сейчас подогнана ширина подписей времени
    private val timeWidthTemplates = HashMap<TextView, String>()

    /**
     * Ширина подписи времени по шаблону: цифры моноширинные (tnum), поэтому шаблон - образец с нулями.
     * Подписи по краям полосы перемотки не меняют ширину каждую секунду, и полоса стоит на месте.
     */
    private fun fitTimeWidth(view: TextView, sample: CharSequence) {
        val template = sample.toString().replace(DIGIT_REGEX, "0")
        if (timeWidthTemplates[view] == template) return
        timeWidthTemplates[view] = template
        view.minWidth = ceil(view.paint.measureText(template)).toInt() + view.paddingLeft + view.paddingRight
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

    fun animateDimming(targetAlpha: Float) {
        if (targetAlpha > 0f) {
            dimOverlay.isVisible = true
            dimOverlay.animate()
                .alpha(targetAlpha)
                .setDuration(800) // Плавное затемнение (800мс)
                .start()
        } else {
            dimOverlay.animate()
                .alpha(0f)
                .setDuration(300) // Быстрое осветление (300мс)
                .withEndAction { dimOverlay.isVisible = false }
                .start()
        }
    }

    private companion object {
        val DIGIT_REGEX = Regex("\\d")
    }
}
