package top.rootu.dddplayer.viewmodel

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.launch
import top.rootu.dddplayer.R
import top.rootu.dddplayer.data.SettingsRepository
import top.rootu.dddplayer.data.VideoSettings
import top.rootu.dddplayer.logic.AnaglyphLogic
import top.rootu.dddplayer.logic.SettingsMutator
import top.rootu.dddplayer.logic.TrackLogic
import top.rootu.dddplayer.model.MediaItem
import top.rootu.dddplayer.model.MenuItem
import top.rootu.dddplayer.model.PlaybackSpeed
import top.rootu.dddplayer.model.ResizeMode
import top.rootu.dddplayer.model.StereoInputType
import top.rootu.dddplayer.model.StereoOutputMode
import top.rootu.dddplayer.player.PlayerManager
import top.rootu.dddplayer.renderer.StereoRenderer
import top.rootu.dddplayer.utils.StereoTypeDetector
import top.rootu.dddplayer.utils.getString
import androidx.media3.common.MediaItem as Media3MediaItem

// --- Enums & Data Classes ---
enum class SettingType {
    VIDEO_TYPE, OUTPUT_FORMAT, GLASSES_TYPE, FILTER_MODE,
    CUSTOM_HUE_L, CUSTOM_HUE_R, CUSTOM_LEAK_L, CUSTOM_LEAK_R, CUSTOM_SPACE,
    SWAP_EYES, DEPTH_3D, SCREEN_SEPARATION, VR_DISTORTION, VR_ZOOM, AUDIO_TRACK, SUBTITLES
}

enum class GlassesGroup { RED_CYAN, YELLOW_BLUE, GREEN_MAGENTA, RED_BLUE }

data class TrackOption(
    val format: Format?, // null для пункта "Off"
    val nameFromMeta: String?,
    val index: Int, // Порядковый номер (для генерации "Track 1")
    val group: Tracks.Group?,
    val trackIndex: Int,
    val isOff: Boolean = false
)

data class VideoQualityOption(
    val name: String,
    val width: Int,
    val height: Int,
    val bitrate: Int,
    val group: Tracks.Group?,
    val trackIndex: Int,
    val isAuto: Boolean = false
)

@UnstableApi
class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SettingsRepository(application)

    // Делегат для 3D/VR настроек
    val anaglyphDelegate = AnaglyphDelegate(repository)

    // --- LiveData ---
    private val _isPlaying = MutableLiveData<Boolean>()
    val isPlaying: LiveData<Boolean> = _isPlaying
    private val _currentPosition = MutableLiveData<Long>()
    val currentPosition: LiveData<Long> = _currentPosition
    private val _duration = MutableLiveData<Long>()
    val duration: LiveData<Long> = _duration
    private val _videoTitle = MutableLiveData<String?>()
    val videoTitle: LiveData<String?> = _videoTitle
    private val _isBuffering = MutableLiveData<Boolean>()
    val isBuffering: LiveData<Boolean> = _isBuffering
    private val _toastMessage = MutableLiveData<String?>()
    val toastMessage: LiveData<String?> = _toastMessage
    private val _cues = MutableLiveData<List<Cue>>()
    val cues: LiveData<List<Cue>> = _cues

    // Playlist State
    private val _currentPlaylist = MutableLiveData<List<MediaItem>>()
    val currentPlaylist: LiveData<List<MediaItem>> = _currentPlaylist
    private val _currentWindowIndex = MutableLiveData(0)
    val currentWindowIndex: LiveData<Int> = _currentWindowIndex
    private val _playlistSize = MutableLiveData(0)
    val playlistSize: LiveData<Int> = _playlistSize
    private val _hasPrevious = MutableLiveData(false)
    val hasPrevious: LiveData<Boolean> = _hasPrevious
    private val _hasNext = MutableLiveData(false)
    val hasNext: LiveData<Boolean> = _hasNext

    // Quality & Info
    private val _videoResolution = MutableLiveData<String>()
    val videoResolution: LiveData<String> = _videoResolution
    private val _videoAspectRatio = MutableLiveData(1.777f)
    val videoAspectRatio: LiveData<Float> = _videoAspectRatio
    private val _videoQualityOptions = MutableLiveData<List<VideoQualityOption>>()
    val videoQualityOptions: LiveData<List<VideoQualityOption>> = _videoQualityOptions
    private val _currentQualityName = MutableLiveData("Auto")
    val currentQualityName: LiveData<String> = _currentQualityName

    // Renderer Settings (Main)
    private val _inputType = MutableLiveData(StereoInputType.NONE)
    val inputType: LiveData<StereoInputType> = _inputType
    private val _outputMode = MutableLiveData(StereoOutputMode.ANAGLYPH)
    val outputMode: LiveData<StereoOutputMode> = _outputMode
    private val _anaglyphType = MutableLiveData(StereoRenderer.AnaglyphType.RC_DUBOIS)
    val anaglyphType: LiveData<StereoRenderer.AnaglyphType> = _anaglyphType
    private val _swapEyes = MutableLiveData(false)
    val swapEyes: LiveData<Boolean> = _swapEyes

    // Параллакс (для конкретного видео)
    private val _depth = MutableLiveData(0)
    val depth: LiveData<Int> = _depth

    // Разделение экрана (Глобальная настройка IPD)
    private val _screenSeparation = MutableLiveData(repository.getGlobalFloat("global_screen_separation_pct", 0f))
    val screenSeparation: LiveData<Float> = _screenSeparation

    // UI State
    private val _singleFrameSize = MutableLiveData<Pair<Float, Float>>()
    val singleFrameSize: LiveData<Pair<Float, Float>> = _singleFrameSize

    // Список доступных настроек для OSD (вычисляется здесь, передается в SettingsViewModel)
    private val _availableSettings = MutableLiveData<List<SettingType>>()
    val availableSettings: LiveData<List<SettingType>> = _availableSettings

    // Tracks & Nav
    private val _audioOutputInfo = MutableLiveData<String>()
    val audioOutputInfo: LiveData<String> = _audioOutputInfo
    private val _currentAudioTrack = MutableLiveData<TrackOption?>()
    val currentAudioTrack: LiveData<TrackOption?> = _currentAudioTrack

    private val _currentSubtitleTrack = MutableLiveData<TrackOption?>()
    val currentSubtitleTrack: LiveData<TrackOption?> = _currentSubtitleTrack
    private val _videoDisabledError = MutableLiveData<PlaybackException?>()
    val videoDisabledError: LiveData<PlaybackException?> = _videoDisabledError
    private val _fatalError = MutableLiveData<PlaybackException?>()
    val fatalError: LiveData<PlaybackException?> = _fatalError
    private val _bufferedPercentage = MutableLiveData(0)
    val bufferedPercentage: LiveData<Int> = _bufferedPercentage
    private val _bufferedPosition = MutableLiveData(0L)
    val bufferedPosition: LiveData<Long> = _bufferedPosition
    private val _playbackSpeed = MutableLiveData(PlaybackSpeed.X1_00)
    val playbackSpeed: LiveData<PlaybackSpeed> = _playbackSpeed

    private val _resizeMode = MutableLiveData(ResizeMode.FIT)
    val resizeMode: LiveData<ResizeMode> = _resizeMode

    private val _zoomScale = MutableLiveData(repository.getZoomScalePercent())
    val zoomScale: LiveData<Int> = _zoomScale

    // Internal
    private var audioOptions = listOf<TrackOption>()
    private var subtitleOptions = listOf<TrackOption>()
    private var currentAudioIndex = 0
    private var currentSubtitleIndex = 0
    private var backupSettings: VideoSettings? = null
    private var isSettingsLoadedFromDb = false
    private var currentUri: String? = null
    private var lastVideoSize: VideoSize? = null
    var isUserInteracting = false
    private val _playbackEnded = MutableLiveData<Boolean>()
    val playbackEnded: LiveData<Boolean> = _playbackEnded

    private val handler = Handler(Looper.getMainLooper())

    // Player Manager
    private val playerManager: PlayerManager

    // Безопасный доступ к плееру (может быть null, если не инициализирован)
    val player: ExoPlayer? get() = playerManager.exoPlayer

    // Событие: Плеер был пересоздан (нужно привязать Surface)
    private val _playerRecreatedEvent = MutableLiveData<ExoPlayer>()
    val playerRecreatedEvent: LiveData<ExoPlayer> = _playerRecreatedEvent

    // Храним хэш настроек при старте
    private var lastSettingsHash = repository.getHardSettingsSignature()

    private val progressUpdater = object : Runnable {
        override fun run() {
            val p = playerManager.exoPlayer ?: return
            if (p.isPlaying || p.isLoading) {
                if (!isUserInteracting) {
                    _currentPosition.value = p.currentPosition
                }
                _bufferedPosition.value = p.bufferedPosition

                // Логика расчета процента буферизации вперед
                // (ExoPlayer.bufferedPercentage не подходит,
                // т.к. он показывает % буфера на прогрессе, а не заполненность буфера)
                val bufferedDuration = p.bufferedPosition - p.currentPosition
                val targetBuffer = if (bufferedDuration > 6_000L) 50_000L else 5_000L
                val maxPercent = if (targetBuffer == 5_000L) 99 else 100
                val percent = ((bufferedDuration * 101) / targetBuffer).toInt().coerceIn(0, maxPercent)
                _bufferedPercentage.value = percent
            }
            handler.postDelayed(this, 200)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            updateProgressUpdaterState()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _isBuffering.value = (playbackState == Player.STATE_BUFFERING)
            if (playbackState == Player.STATE_READY) _duration.value = player?.duration
            if (playbackState == Player.STATE_ENDED) _playbackEnded.value = true
            updateProgressUpdaterState()
        }

        override fun onPlayerError(error: PlaybackException) {
            if (tryRecoverFromError(error)) {
                return
            }
            _fatalError.postValue(error)
            _isPlaying.postValue(false)
        }

        override fun onMediaItemTransition(mediaItem: Media3MediaItem?, reason: Int) {
            handleMediaItemTransition(mediaItem)
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            handleVideoSizeChanged(videoSize)
        }

        override fun onTracksChanged(tracks: Tracks) {
            updateTracksInfo(tracks)
        }

        override fun onCues(cueGroup: CueGroup) {
            _cues.value = cueGroup.cues
        }
    }

    init {
        // Загружаем кастомные настройки через делегат
        anaglyphDelegate.loadCustomSettings(_anaglyphType.value!!)

        playerManager = PlayerManager(application, playerListener)

        // Подписываемся на создание нового плеера
        playerManager.onPlayerCreated = { newPlayer ->
            _playerRecreatedEvent.postValue(newPlayer)
            // Принудительно обновляем UI состояние
            _isPlaying.postValue(newPlayer.isPlaying)
            _duration.postValue(newPlayer.duration)
        }

        playerManager.onMetadataAvailable = {
            // Метаданные из файла (MKV/MP4) готовы. Обновляем треки.
            handler.post {
                player?.currentTracks?.let { updateTracksInfo(it) }
            }
        }

        playerManager.onAudioOutputFormatChanged = { info ->
            _audioOutputInfo.postValue(info)
        }

        // Инициализируем плеер сразу
        playerManager.initializePlayer()

        if (repository.isRememberZoomEnabled()) {
            val savedModeOrd = repository.getGlobalResizeMode()
            val savedMode = ResizeMode.entries.getOrNull(savedModeOrd) ?: ResizeMode.FIT
            _resizeMode.value = savedMode
            // ZoomScale уже загружается в поле _zoomScale при инициализации
        }
    }

    private fun updateProgressUpdaterState() {
        val p = playerManager.exoPlayer ?: return
        val shouldRun = p.isPlaying || p.playbackState == Player.STATE_BUFFERING

        if (shouldRun) {
            handler.removeCallbacks(progressUpdater)
            handler.post(progressUpdater)
        } else {
            handler.removeCallbacks(progressUpdater)
        }
    }

    private fun tryRecoverFromError(error: PlaybackException): Boolean {
        if (error !is ExoPlaybackException || error.type != ExoPlaybackException.TYPE_RENDERER) {
            return false
        }

        val rendererIndex = error.rendererIndex
        if (rendererIndex == C.INDEX_UNSET) return false

        player?.let { p ->
            val trackType = p.getRendererType(rendererIndex)

            if (trackType == C.TRACK_TYPE_VIDEO) {
                _videoDisabledError.postValue(error)
                _toastMessage.postValue(getString(R.string.error_video_decoder))

                // Отключаем проблемный трек
                val parameters = p.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(trackType, true)
                    .build()
                p.trackSelectionParameters = parameters

            } else if (trackType == C.TRACK_TYPE_AUDIO) {
                val hint = if (audioOptions.size > 2) getString(R.string.error_audio_disabled_hint, "${error.errorCodeName}: ${error.message}")
                else getString(R.string.error_audio_disabled, "${error.errorCodeName}: ${error.message}")
                _toastMessage.postValue(hint)
                selectTrackByIndex(C.TRACK_TYPE_AUDIO, 0) // Выкл
            } else {
                return false
            }


            // Перезапускаем воспроизведение
            p.seekTo(p.currentMediaItemIndex, p.currentPosition)
            p.prepare()
            p.play()
        }
        return true
    }

    private fun handleMediaItemTransition(mediaItem: Media3MediaItem?) {
        player?.let { p ->
            val title = mediaItem?.mediaMetadata?.title?.toString()
            currentUri = mediaItem?.localConfiguration?.uri?.toString()
            _videoTitle.value = title ?: mediaItem?.localConfiguration?.uri?.lastPathSegment

            _hasPrevious.value = p.hasPreviousMediaItem()
            _hasNext.value = p.hasNextMediaItem()
            _playlistSize.value = p.mediaItemCount
            _currentWindowIndex.value = p.currentMediaItemIndex

            isSettingsLoadedFromDb = false
            _inputType.value = StereoInputType.NONE

            // Сбрасываем ошибку видео при ЛЮБОМ переходе
            _videoDisabledError.value = null

            // ВАЖНО: Принудительно включаем видео обратно, если оно было отключено из-за ошибки
            // Это нужно делать здесь, так как автоматический переход на следующий трек
            // не вызывает playPlaylistItem, но вызывает этот колбэк.
            val params = p.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
                .build()
            p.trackSelectionParameters = params

            if (currentUri != null) {
                viewModelScope.launch {
                    val saved = repository.getVideoSettings(currentUri!!)
                    if (saved != null) {
                        applySettings(saved)
                        isSettingsLoadedFromDb = true
                    } else {
                        loadGlobalDefaults()
                    }
                }
            }
        }
    }

    private fun handleVideoSizeChanged(videoSize: VideoSize) {
        lastVideoSize = videoSize
        _videoResolution.value = "${videoSize.width}x${videoSize.height}"

        if (videoSize.height > 0) {
            val ratio = (videoSize.width * videoSize.pixelWidthHeightRatio) / videoSize.height
            _videoAspectRatio.postValue(ratio)
        }

        if (videoSize.width == 0 || videoSize.height == 0) return

        if (isSettingsLoadedFromDb) {
            calculateFrameSize(_inputType.value!!, videoSize)
            return
        }

        // Логика автоопределения
        if (_inputType.value == StereoInputType.NONE) {
            val detectedType = StereoTypeDetector.detect(
                player?.videoFormat,
                player?.currentMediaItem?.localConfiguration?.uri
            )
            if (detectedType != StereoInputType.NONE) {
                setInputType(detectedType)
                showAutoDetectToast(detectedType)
            } else {
                // Эвристика по соотношению сторон
                val ar = videoSize.width.toFloat() / videoSize.height.toFloat()
                val heuristic = when {
                    ar > 3.0 -> StereoInputType.SIDE_BY_SIDE
                    ar > 0.8 && ar < 1.2 && videoSize.height > videoSize.width * 1.1 -> StereoInputType.TOP_BOTTOM
                    else -> StereoInputType.NONE
                }
                if (heuristic != StereoInputType.NONE) {
                    setInputType(heuristic)
                    showAutoDetectToast(heuristic)
                } else {
                    calculateFrameSize(StereoInputType.NONE, videoSize)
                }
            }
        } else {
            calculateFrameSize(_inputType.value!!, videoSize)
        }
    }

    private fun updateTracksInfo(tracks: Tracks) {
        val metadata = playerManager.getTrackMetadata()

        // 1. Аудио
        val (audio, audioIdx) = TrackLogic.extractAudioTracks(tracks, metadata)
        audioOptions = audio
        currentAudioIndex = audioIdx
        _currentAudioTrack.postValue(audioOptions.getOrNull(currentAudioIndex))

        // 2. Субтитры
        val (subs, subIdx) = TrackLogic.extractSubtitleTracks(tracks, metadata)
        subtitleOptions = subs
        currentSubtitleIndex = subIdx
        _currentSubtitleTrack.postValue(subtitleOptions.getOrNull(currentSubtitleIndex))

        // 3. Видео (Quality)
        _videoQualityOptions.postValue(TrackLogic.extractVideoTracks(tracks))

        updateAvailableSettings()
    }

    // --- Player Controls ---
    fun seekForward() = playerManager.seekForward()
    fun seekBack() = playerManager.seekBack()
    fun seekTo(pos: Long) {
        player?.seekTo(pos)
        _currentPosition.value = pos
    }
    fun togglePlayPause() = playerManager.togglePlayPause()
    fun nextTrack() { if (player?.hasNextMediaItem() == true) player!!.seekToNextMediaItem() }
    fun prevTrack() { if (player?.hasPreviousMediaItem() == true) player!!.seekToPreviousMediaItem() }

    fun setPlaybackSpeed(speed: PlaybackSpeed) {
        _playbackSpeed.value = speed
        player?.setPlaybackSpeed(speed.value)
    }

    fun setResizeMode(mode: ResizeMode) {
        _resizeMode.value = mode
        // Если включено запоминание - сохраняем
        if (repository.isRememberZoomEnabled()) {
            repository.setGlobalResizeMode(mode.ordinal)
        }
    }

    fun setZoomScale(percent: Int) {
        val newScale = percent.coerceIn(100, 200) // Ограничиваем от 100% до 200%
        repository.setZoomScalePercent(newScale)
        _zoomScale.value = newScale
        _resizeMode.value = ResizeMode.SCALE
        // Если включено запоминание - сохраняем режим SCALE
        if (repository.isRememberZoomEnabled()) {
            repository.setGlobalResizeMode(ResizeMode.SCALE.ordinal)
        }
    }

    fun loadPlaylist(items: List<MediaItem>, startIndex: Int) {
        _currentPlaylist.value = items
        val startPos = items.getOrNull(startIndex)?.startPositionMs ?: 0L
        playerManager.loadPlaylist(items, startIndex, startPos)
        viewModelScope.launch { repository.cleanupOldSettings() }
    }

    // --- Settings Logic ---

    private fun loadGlobalDefaults() {
        val outModeOrd = repository.getGlobalInt("def_output_mode", StereoOutputMode.ANAGLYPH.ordinal)
        val outputMode = StereoOutputMode.entries.getOrNull(outModeOrd) ?: StereoOutputMode.ANAGLYPH
        _outputMode.postValue(outputMode)

        val anaTypeOrd = repository.getGlobalInt("def_anaglyph_type", StereoRenderer.AnaglyphType.RC_DUBOIS.ordinal)
        val anaglyphType = StereoRenderer.AnaglyphType.entries.getOrNull(anaTypeOrd) ?: StereoRenderer.AnaglyphType.RC_DUBOIS
        _anaglyphType.postValue(anaglyphType)

        _screenSeparation.postValue(repository.getGlobalFloat("global_screen_separation_pct", 0f))
        anaglyphDelegate.loadGlobalVrParams()

        if (AnaglyphLogic.isCustomType(anaglyphType)) anaglyphDelegate.loadCustomSettings(anaglyphType)
        updateAvailableSettings()
        anaglyphDelegate.updateAnaglyphMatrix(anaglyphType)
    }

    fun saveCurrentSettings() {
        val uri = currentUri ?: return
        val settings = VideoSettings(
            uri, System.currentTimeMillis(),
            _inputType.value!!, _outputMode.value!!, _anaglyphType.value!!,
            _swapEyes.value!!, _depth.value!!
        )
        viewModelScope.launch { repository.saveVideoSettings(settings) }

        repository.saveGlobalDefaults(settings.outputMode, settings.anaglyphType)
        repository.putGlobalFloat("global_screen_separation_pct", _screenSeparation.value!!)
        anaglyphDelegate.saveGlobalVrParams()

        isSettingsLoadedFromDb = true
    }

    fun prepareSettingsPanel() {
        // Создаем копию текущих настроек для отката
        backupSettings = VideoSettings(
            "", 0, _inputType.value!!, _outputMode.value!!,
            _anaglyphType.value!!, _swapEyes.value!!, _depth.value!!
        )
        updateAvailableSettings()
    }

    fun restoreSettings() {
        backupSettings?.let { applySettings(it) }
        anaglyphDelegate.loadGlobalVrParams()
    }

    private fun applySettings(s: VideoSettings) {
        _inputType.postValue(s.inputType)
        _outputMode.postValue(s.outputMode)
        _anaglyphType.postValue(s.anaglyphType)
        _swapEyes.postValue(s.swapEyes)
        _depth.postValue(s.depth)

        anaglyphDelegate.loadGlobalVrParams()

        handler.post { lastVideoSize?.let { calculateFrameSize(s.inputType, it) } }
        if (AnaglyphLogic.isCustomType(s.anaglyphType)) anaglyphDelegate.loadCustomSettings(s.anaglyphType)
        updateAvailableSettings()
        anaglyphDelegate.updateAnaglyphMatrix(s.anaglyphType)
    }

    // --- Menu Generation ---

    fun getPlaybackSpeedMenuItems(): List<MenuItem> {
        val currentSpeed = _playbackSpeed.value ?: PlaybackSpeed.X1_00
        return PlaybackSpeed.entries.map { speed ->
            MenuItem(
                id = speed.value.toString(),
                title = speed.label,
                isSelected = speed == currentSpeed
            )
        }
    }

    private fun getResizeModeIconResId(mode: ResizeMode?): Int? {
        return when (mode) {
            ResizeMode.FIT -> R.drawable.ic_fit_screen
            ResizeMode.ZOOM -> R.drawable.ic_fit_zoom
            ResizeMode.SCALE -> R.drawable.ic_fit_scale
            ResizeMode.FILL -> R.drawable.ic_fullscreen
            else -> null
        }
    }

    fun getResizeModeMenuItems(context: Context): List<MenuItem> {
        val currentMode = _resizeMode.value ?: ResizeMode.FIT
        val currentZoom = _zoomScale.value ?: 115

        return ResizeMode.entries.map { mode ->
            val titleResId = when (mode) {
                ResizeMode.FIT -> R.string.resize_mode_fit
                ResizeMode.ZOOM -> R.string.resize_mode_zoom
                ResizeMode.SCALE -> R.string.resize_mode_scale
                ResizeMode.FILL -> R.string.resize_mode_fill
            }
            val iconResId = getResizeModeIconResId(mode)

            val description = when (mode) {
                ResizeMode.FIT -> context.getString(R.string.resize_mode_fit_desc)
                ResizeMode.ZOOM -> context.getString(R.string.resize_mode_zoom_desc)
                ResizeMode.SCALE -> context.getString(R.string.playback_zoom_percent, currentZoom)
                ResizeMode.FILL -> context.getString(R.string.resize_mode_fill_desc)
            }

            MenuItem(
                id = mode.name,
                title = context.getString(titleResId),
                description = description,
                iconRes = iconResId,
                isSelected = mode == currentMode
            )
        }
    }

    fun getMainMenuItems(context: Context): List<MenuItem> {
        val currentAudioName = currentAudioTrack.value?.let { TrackLogic.buildTrackLabel(it, context) } ?: ""
        val currentSubtitleName = currentSubtitleTrack.value?.let { TrackLogic.buildTrackLabel(it, context) } ?: ""
        val currentSpeed = _playbackSpeed.value?.label ?: PlaybackSpeed.X1_00.label

        return listOf(
            MenuItem(
                "audio",
                context.getString(R.string.menu_audio_title, (audioOptions.size - 1).coerceAtLeast(0)),
                currentAudioName,
                R.drawable.ic_audio_track
            ),
            MenuItem(
                "subtitles",
                context.getString(R.string.menu_subtitle_title, (subtitleOptions.size - 1).coerceAtLeast(0)),
                currentSubtitleName,
                R.drawable.ic_subtitles
            ),
            MenuItem(
                "speed",
                context.getString(R.string.playback_speed),
                currentSpeed,
                R.drawable.ic_speed
            ),
            MenuItem(
                "resize",
                context.getString(R.string.playback_zoom),
                getResizeModeLabel(context),
                getResizeModeIconResId(_resizeMode.value)
            ),
            MenuItem(
                "quick_settings",
                context.getString(R.string.menu_quick_settings_title),
                context.getString(R.string.menu_quick_settings_desc),
                R.drawable.ic_settings_3d
            ),
            MenuItem(
                "global_settings",
                context.getString(R.string.menu_global_settings_title),
                context.getString(R.string.menu_global_settings_desc),
                R.drawable.ic_build
            )
        )
    }

    /**
     * Метод для выбора элемента плейлиста из UI.
     * Гарантирует сброс ошибок и включение видео.
     */
    fun playPlaylistItem(index: Int) {
        player?.let { p ->
            val isVideoError = _videoDisabledError.value == null
            // Сбрасываем ошибку в UI немедленно
            _videoDisabledError.value = null

            // Включаем видео обратно (на случай, если оно было отключено из-за ошибки)
            val params = p.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
                .build()
            p.trackSelectionParameters = params

            if (p.currentMediaItemIndex == index) {
                // Если мы уже на этом треке и была ошибка перезапускаем его
                if (isVideoError) p.seekTo(index, 0)
            } else {
                // Переключение на другой трек
                seekTo(0)
                p.seekToDefaultPosition(index)
            }
            p.prepare() // На всякий случай
            p.play()
        }
    }

    fun getNextTrackTitle(): String? {
        val p = player ?: return null
        if (p.hasNextMediaItem()) {
            val nextIndex = p.nextMediaItemIndex
            val item = p.getMediaItemAt(nextIndex)
            return item.mediaMetadata.title?.toString() ?: "Video ${nextIndex + 1}"
        }
        return null
    }

    fun getPrevTrackTitle(): String? {
        val p = player ?: return null
        if (p.hasPreviousMediaItem()) {
            val prevIndex = p.previousMediaItemIndex
            val item = p.getMediaItemAt(prevIndex)
            return item.mediaMetadata.title?.toString() ?: "Video ${prevIndex + 1}"
        }
        return null
    }
    fun getAudioTrackMenuItems(context: Context): List<MenuItem> {
        return audioOptions.mapIndexed { index, option ->
            val name = TrackLogic.buildTrackLabel(option, context)
            MenuItem(index.toString(), name, isSelected = index == currentAudioIndex)
        }
    }

    fun getSubtitleMenuItems(context: Context): List<MenuItem> {
        return subtitleOptions.mapIndexed { index, option ->
            val name = TrackLogic.buildTrackLabel(option, context)
            MenuItem(index.toString(), name, isSelected = index == currentSubtitleIndex)
        }
    }

    private fun getResizeModeLabel(context: Context): String {
        val mode = _resizeMode.value ?: ResizeMode.FIT
        return when (mode) {
            ResizeMode.FIT -> context.getString(R.string.resize_mode_fit)
            ResizeMode.ZOOM -> context.getString(R.string.resize_mode_zoom)
            ResizeMode.SCALE -> context.getString(R.string.playback_zoom_scale, _zoomScale.value ?: 115)
            ResizeMode.FILL -> context.getString(R.string.resize_mode_fill)
        }
    }

    fun selectTrackByIndex(trackType: Int, index: Int) {
        val options = if (trackType == C.TRACK_TYPE_AUDIO) audioOptions else subtitleOptions

        if (index in options.indices) {
            val option = options[index]

            if (trackType == C.TRACK_TYPE_AUDIO) {
                currentAudioIndex = index
                _currentAudioTrack.value = option
            } else {
                currentSubtitleIndex = index
                _currentSubtitleTrack.value = option
            }

            // Общая логика применения
            player?.let { p ->
                val builder = p.trackSelectionParameters.buildUpon()
                if (option.isOff) {
                    builder.setTrackTypeDisabled(trackType, true)
                } else {
                    builder.setTrackTypeDisabled(trackType, false)
                    option.group?.let {
                        builder.setOverrideForType(
                            TrackSelectionOverride(
                                it.mediaTrackGroup,
                                option.trackIndex
                            )
                        )
                    }
                }
                p.trackSelectionParameters = builder.build()
            }
        }
    }

    // Логика изменения настроек (вызывается из Fragment по команде SettingsViewModel)
    fun changeSettingValue(settingType: SettingType, direction: Int) {
        when (settingType) {
            SettingType.VIDEO_TYPE -> setInputType(SettingsMutator.cycleEnum(_inputType.value!!, direction))
            SettingType.OUTPUT_FORMAT -> {
                _outputMode.value = SettingsMutator.cycleEnum(_outputMode.value!!, direction)
                updateAvailableSettings()
            }
            SettingType.GLASSES_TYPE -> {
                val currentGroup = AnaglyphLogic.getGlassesGroup(_anaglyphType.value!!)
                val nextGroup = SettingsMutator.cycleEnum(currentGroup, direction)
                val nextFilter = getPreferredFilter(nextGroup)
                _anaglyphType.value = nextFilter
                if (AnaglyphLogic.isCustomType(nextFilter)) anaglyphDelegate.loadCustomSettings(nextFilter)
                updateAvailableSettings()
                anaglyphDelegate.updateAnaglyphMatrix(nextFilter)
            }
            SettingType.FILTER_MODE -> {
                val currentGroup = AnaglyphLogic.getGlassesGroup(_anaglyphType.value!!)
                val filters = AnaglyphLogic.getFiltersForGroup(currentGroup)
                val nextFilter = SettingsMutator.cycleList(_anaglyphType.value!!, filters, direction)
                _anaglyphType.value = nextFilter
                savePreferredFilter(nextFilter)
                if (AnaglyphLogic.isCustomType(nextFilter)) anaglyphDelegate.loadCustomSettings(nextFilter)
                updateAvailableSettings()
                anaglyphDelegate.updateAnaglyphMatrix(nextFilter)
            }
            SettingType.CUSTOM_HUE_L -> {
                anaglyphDelegate.setCustomHueL(SettingsMutator.modifyInt(anaglyphDelegate.customHueOffsetL.value!!, direction, 1, -100, 100))
                anaglyphDelegate.saveCustomSettings(_anaglyphType.value!!)
            }
            SettingType.CUSTOM_HUE_R -> {
                anaglyphDelegate.setCustomHueR(SettingsMutator.modifyInt(anaglyphDelegate.customHueOffsetR.value!!, direction, 1, -100, 100))
                anaglyphDelegate.saveCustomSettings(_anaglyphType.value!!)
            }
            SettingType.CUSTOM_LEAK_L -> {
                anaglyphDelegate.setCustomLeakL(SettingsMutator.modifyFloat(anaglyphDelegate.customLeakL.value!!, direction, 0.01f, 0f, 0.5f))
                anaglyphDelegate.saveCustomSettings(_anaglyphType.value!!)
            }
            SettingType.CUSTOM_LEAK_R -> {
                anaglyphDelegate.setCustomLeakR(SettingsMutator.modifyFloat(anaglyphDelegate.customLeakR.value!!, direction, 0.01f, 0f, 0.5f))
                anaglyphDelegate.saveCustomSettings(_anaglyphType.value!!)
            }
            SettingType.CUSTOM_SPACE -> {
                anaglyphDelegate.setCustomSpaceLms(!anaglyphDelegate.customSpaceLms.value!!)
                anaglyphDelegate.saveCustomSettings(_anaglyphType.value!!)
            }
            SettingType.SWAP_EYES -> _swapEyes.value = !_swapEyes.value!!
            SettingType.DEPTH_3D -> _depth.value = SettingsMutator.modifyInt(_depth.value!!, direction, 1, -50, 50)
            SettingType.SCREEN_SEPARATION -> _screenSeparation.value = SettingsMutator.modifyFloat(_screenSeparation.value!!, direction, 0.005f, -0.15f, 0.15f)
            SettingType.VR_DISTORTION -> anaglyphDelegate.setVrK1(SettingsMutator.modifyFloat(anaglyphDelegate.vrK1.value!!, direction, 0.02f, 0.0f, 2.0f))
            SettingType.VR_ZOOM -> anaglyphDelegate.setVrScale(SettingsMutator.modifyFloat(anaglyphDelegate.vrScale.value!!, direction, 0.05f, 0.5f, 3.0f))
            SettingType.AUDIO_TRACK -> {
                val options = audioOptions
                if (options.isNotEmpty()) {
                    val nextIndex = (currentAudioIndex + direction + options.size) % options.size
                    selectTrackByIndex(C.TRACK_TYPE_AUDIO, nextIndex)
                }
            }
            SettingType.SUBTITLES -> {
                val options = subtitleOptions
                if (options.isNotEmpty()) {
                    val nextIndex = (currentSubtitleIndex + direction + options.size) % options.size
                    selectTrackByIndex(C.TRACK_TYPE_TEXT, nextIndex)
                }
            }
        }
    }

    /**
     * Возвращает список ID ресурсов (Int) или нелокализованные строки.
     * Локализация должна происходить во Fragment.
     */
    fun getOptionsForSetting(type: SettingType, context: Context): Pair<List<String>, Int>? {
        return when (type) {
            SettingType.VIDEO_TYPE -> {
                val values = StereoInputType.entries.toTypedArray()
                val list = values.map {
                    val resId = when (it) {
                        StereoInputType.NONE -> R.string.stereo_mode_mono
                        StereoInputType.SIDE_BY_SIDE -> R.string.stereo_mode_sbs
                        StereoInputType.TOP_BOTTOM -> R.string.stereo_mode_tb
                        StereoInputType.INTERLACED -> R.string.stereo_mode_interlaced
                        StereoInputType.TILED_1080P -> R.string.stereo_mode_tiled
                    }
                    context.getString(resId) // Используем контекст, переданный из Fragment
                }
                Pair(list, _inputType.value!!.ordinal)
            }
            SettingType.OUTPUT_FORMAT -> {
                val values = StereoOutputMode.entries.toTypedArray()
                val list = values.map {
                    val resId = when (it) {
                        StereoOutputMode.ANAGLYPH -> R.string.output_mode_anaglyph
                        StereoOutputMode.LEFT_ONLY -> R.string.output_mode_left
                        StereoOutputMode.RIGHT_ONLY -> R.string.output_mode_right
                        StereoOutputMode.CARDBOARD_VR -> R.string.output_mode_vr
                    }
                    context.getString(resId) // Используем контекст, переданный из Fragment
                }
                Pair(list, _outputMode.value!!.ordinal)
            }
            SettingType.GLASSES_TYPE -> {
                val currentGroup = AnaglyphLogic.getGlassesGroup(_anaglyphType.value!!)
                val groups = GlassesGroup.entries.toTypedArray()
                val list = groups.map {
                    val resId = when(it) {
                        GlassesGroup.RED_CYAN -> R.string.glasses_rc
                        GlassesGroup.YELLOW_BLUE -> R.string.glasses_yb
                        GlassesGroup.GREEN_MAGENTA -> R.string.glasses_gm
                        GlassesGroup.RED_BLUE -> R.string.glasses_rb
                    }
                    context.getString(resId) // Используем контекст, переданный из Fragment
                }
                Pair(list, currentGroup.ordinal)
            }
            SettingType.FILTER_MODE -> {
                val currentGroup = AnaglyphLogic.getGlassesGroup(_anaglyphType.value!!)
                val filters = AnaglyphLogic.getFiltersForGroup(currentGroup)
                val list = filters.map {
                    if (it.name.endsWith("_CUSTOM"))
                        context.getString(R.string.val_custom) // Используем контекст, переданный из Fragment
                    else it.name
                }
                val index = filters.indexOf(_anaglyphType.value!!)
                Pair(list, index)
            }
            SettingType.AUDIO_TRACK -> {
                if (audioOptions.isNotEmpty()) {
                    // Генерируем строки "здесь и сейчас" используя актуальный контекст
                    val list = audioOptions.map { TrackLogic.buildTrackLabel(it, context) }
                    Pair(list, currentAudioIndex)
                } else null
            }
            SettingType.SUBTITLES -> {
                if (subtitleOptions.isNotEmpty()) {
                    // Генерируем строки "здесь и сейчас"
                    val list = subtitleOptions.map { TrackLogic.buildTrackLabel(it, context) }
                    Pair(list, currentSubtitleIndex)
                } else null
            }
            else -> null
        }
    }

    fun setInputType(inputType: StereoInputType) {
        if (_inputType.value == inputType) return
        _inputType.value = inputType
        lastVideoSize?.let { calculateFrameSize(inputType, it) }
        updateAvailableSettings()
    }

    fun setVideoQuality(option: VideoQualityOption) {
        player?.let { p ->
            val builder = p.trackSelectionParameters.buildUpon()
            if (option.isAuto) {
                builder.setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
                    .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            } else {
                builder.setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
                option.group?.let {
                    builder.setOverrideForType(TrackSelectionOverride(it.mediaTrackGroup, option.trackIndex))
                }
            }
            p.trackSelectionParameters = builder.build()
            _currentQualityName.value = option.name
        }
    }

    private fun showAutoDetectToast(type: StereoInputType) {
        val typeName = type.name.replace("_", " ")
        // Используем Application Context для тоста, так как он не зависит от локали Activity
        // и не вызывает проблем с утечками, но здесь мы должны использовать getString из Application
        // для корректной локализации, если язык был изменен.
        // В данном случае, мы оставляем Application Context, но для тостов это допустимо.
        _toastMessage.value = getApplication<Application>().getString(R.string.msg_auto_detect, typeName)
    }

    private fun updateAvailableSettings() {
        val list = mutableListOf<SettingType>()
        list.add(SettingType.VIDEO_TYPE)
        if (_inputType.value != StereoInputType.NONE) {
            list.add(SettingType.OUTPUT_FORMAT)
            if (_outputMode.value == StereoOutputMode.ANAGLYPH) {
                list.add(SettingType.GLASSES_TYPE)
                list.add(SettingType.FILTER_MODE)
                if (AnaglyphLogic.isCustomType(_anaglyphType.value!!)) {
                    list.add(SettingType.CUSTOM_HUE_L); list.add(SettingType.CUSTOM_LEAK_L)
                    list.add(SettingType.CUSTOM_HUE_R); list.add(SettingType.CUSTOM_LEAK_R)
                    list.add(SettingType.CUSTOM_SPACE)
                }
            }
            list.add(SettingType.SWAP_EYES)
            list.add(SettingType.DEPTH_3D)
            if (_outputMode.value == StereoOutputMode.CARDBOARD_VR) {
                list.add(SettingType.SCREEN_SEPARATION); list.add(SettingType.VR_DISTORTION); list.add(SettingType.VR_ZOOM)
            }
        }
        if (audioOptions.size > 1) list.add(SettingType.AUDIO_TRACK)
        if (subtitleOptions.size > 1) list.add(SettingType.SUBTITLES)

        _availableSettings.value = list
    }

    private fun calculateFrameSize(inputType: StereoInputType, videoSize: VideoSize) {
        val width = videoSize.width.toFloat()
        val height = videoSize.height.toFloat()
        var finalFrameWidth: Float
        var finalFrameHeight: Float

        when (inputType) {
            StereoInputType.SIDE_BY_SIDE -> {
                val halfWidth = width / 2
                val halfAR = halfWidth / height
                // Логика определения Full/Half SBS. Если AR половины кадра не широкое (например, 1:1),
                // то предполагаем Full SBS (кадр 2:1), иначе Half SBS (кадр 1:1).
                if (halfAR < 1.2f) {
                    finalFrameWidth = width
                    finalFrameHeight = height
                } else {
                    finalFrameWidth = halfWidth
                    finalFrameHeight = height
                }
            }
            StereoInputType.TOP_BOTTOM -> {
                val halfHeight = height / 2
                val halfAR = width / halfHeight
                // Логика определения Full/Half OU. Если AR половины кадра очень широкое,
                // то предполагаем Full OU, иначе Half OU.
                if (halfAR > 2.5f) {
                    finalFrameWidth = width
                    finalFrameHeight = height
                } else {
                    finalFrameWidth = width
                    finalFrameHeight = halfHeight
                }
            }
            else -> { finalFrameWidth = width; finalFrameHeight = height }
        }
        _singleFrameSize.value = Pair(finalFrameWidth, finalFrameHeight)
    }

    private fun savePreferredFilter(type: StereoRenderer.AnaglyphType) {
        val group = AnaglyphLogic.getGlassesGroup(type)
        repository.putGlobalInt("pref_filter_${group.name}", type.ordinal)
    }

    private fun getPreferredFilter(group: GlassesGroup): StereoRenderer.AnaglyphType {
        val savedOrdinal = repository.getGlobalInt("pref_filter_${group.name}", -1)
        if (savedOrdinal != -1) {
            val values = StereoRenderer.AnaglyphType.entries.toTypedArray()
            if (savedOrdinal in values.indices) {
                val savedType = values[savedOrdinal]
                if (AnaglyphLogic.getGlassesGroup(savedType) == group) return savedType
            }
        }
        return AnaglyphLogic.getFiltersForGroup(group).first()
    }

    // --- Hot Restart Logic ---

    fun checkSettingsAndRestart() {
        val currentHash = repository.getHardSettingsSignature()

        if (currentHash != lastSettingsHash) {
            restartPlayer()
            lastSettingsHash = currentHash
        } else {
            playerManager.updateTrackSelectionParameters()
        }
    }

    fun restartPlayer() {
        // Сохраняем текущее состояние перед пересозданием
        playerManager.releasePlayer(saveState = true)
        playerManager.initializePlayer()
    }

    override fun onCleared() {
        super.onCleared()
        handler.removeCallbacks(progressUpdater)
        playerManager.releasePlayer(saveState = false)
    }
}