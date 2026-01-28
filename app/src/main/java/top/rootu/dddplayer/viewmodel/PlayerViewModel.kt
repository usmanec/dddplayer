package top.rootu.dddplayer.viewmodel

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
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
import top.rootu.dddplayer.model.StereoInputType
import top.rootu.dddplayer.model.StereoOutputMode
import top.rootu.dddplayer.player.PlayerManager
import top.rootu.dddplayer.renderer.StereoRenderer
import top.rootu.dddplayer.utils.getString
import top.rootu.dddplayer.utils.StereoTypeDetector
import androidx.media3.common.MediaItem as Media3MediaItem

// --- Enums & Data Classes ---
enum class SettingType {
    VIDEO_TYPE, OUTPUT_FORMAT, GLASSES_TYPE, FILTER_MODE,
    CUSTOM_HUE_L, CUSTOM_HUE_R, CUSTOM_LEAK_L, CUSTOM_LEAK_R, CUSTOM_SPACE,
    SWAP_EYES, DEPTH_3D, SCREEN_SEPARATION, VR_DISTORTION, VR_ZOOM, AUDIO_TRACK, SUBTITLES
}

enum class GlassesGroup { RED_CYAN, YELLOW_BLUE, GREEN_MAGENTA, RED_BLUE }

data class TrackOption(
    val name: String,
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
    private val _screenSeparation = MutableLiveData(0f)
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
    private val _currentAudioName = MutableLiveData<String>()
    val currentAudioName: LiveData<String> = _currentAudioName
    private val _currentSubtitleName = MutableLiveData<String>()
    val currentSubtitleName: LiveData<String> = _currentSubtitleName
    private val _videoDisabledError = MutableLiveData<PlaybackException?>()
    val videoDisabledError: LiveData<PlaybackException?> = _videoDisabledError
    private val _fatalError = MutableLiveData<PlaybackException?>()
    val fatalError: LiveData<PlaybackException?> = _fatalError
    private val _bufferedPercentage = MutableLiveData(0)
    val bufferedPercentage: LiveData<Int> = _bufferedPercentage
    private val _bufferedPosition = MutableLiveData(0L)
    val bufferedPosition: LiveData<Long> = _bufferedPosition

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

                val bufferedDuration = p.bufferedPosition - p.currentPosition
                val targetBuffer = if (bufferedDuration > 6_000L) 50_000L else  5_000L
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
            val p = player ?: return

            val title = mediaItem?.mediaMetadata?.title?.toString()
            currentUri = mediaItem?.localConfiguration?.uri?.toString()
            _videoTitle.value = title ?: mediaItem?.localConfiguration?.uri?.lastPathSegment

            _hasPrevious.value = p.hasPreviousMediaItem()
            _hasNext.value = p.hasNextMediaItem()
            _playlistSize.value = p.mediaItemCount
            _currentWindowIndex.value = p.currentMediaItemIndex

            isSettingsLoadedFromDb = false
            _inputType.value = StereoInputType.NONE

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

        override fun onVideoSizeChanged(videoSize: VideoSize) {
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

            if (_inputType.value == StereoInputType.NONE) {
                val detectedType = StereoTypeDetector.detect(
                    player?.videoFormat,
                    player?.currentMediaItem?.localConfiguration?.uri
                )
                if (detectedType != StereoInputType.NONE) {
                    setInputType(detectedType)
                    showAutoDetectToast(detectedType)
                } else {
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
            handler.post {
                if (player != null) {
                    val tracks = player!!.currentTracks
                    updateTracksInfo(tracks)
                }
            }
        }

        playerManager.onAudioOutputFormatChanged = { info ->
            _audioOutputInfo.postValue(info)
        }

        // Инициализируем плеер сразу
        playerManager.initializePlayer()
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

        if (player != null) {
            val trackType = player!!.getRendererType(rendererIndex)

            if (trackType == C.TRACK_TYPE_VIDEO) {
                _videoDisabledError.postValue(error)
                _toastMessage.postValue(getString(R.string.error_video_decoder))
            } else if (trackType == C.TRACK_TYPE_AUDIO) {
                if (audioOptions.size > 1) {
                    _toastMessage.postValue(getString(R.string.error_audio_disabled_hint, "${error.errorCodeName}: ${error.message}"))
                } else {
                    _toastMessage.postValue(getString(R.string.error_audio_disabled, "${error.errorCodeName}: ${error.message}"))
                }
            }

            val parameters = player!!.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(trackType, true)
                .build()

            player!!.trackSelectionParameters = parameters

            // Перезапускаем воспроизведение
            val currentPos = player!!.currentPosition
            val currentWindow = player!!.currentMediaItemIndex
            player!!.seekTo(currentWindow, currentPos)
            player!!.prepare()
            player!!.play()
        }
        return true
    }

    private fun updateTracksInfo(tracks: Tracks) {
        val metadata = playerManager.getTrackMetadata()

        val (audio, audioIdx) = TrackLogic.extractAudioTracks(tracks, metadata)
        audioOptions = audio
        currentAudioIndex = audioIdx
        if (audioOptions.isNotEmpty()) _currentAudioName.postValue(audioOptions[currentAudioIndex].name)

        val (subs, subIdx) = TrackLogic.extractSubtitleTracks(tracks, getApplication(), metadata)
        subtitleOptions = subs
        currentSubtitleIndex = subIdx
        _currentSubtitleName.postValue(subtitleOptions[currentSubtitleIndex].name)

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
    fun nextTrack() { if (player?.hasNextMediaItem() ?: false) player!!.seekToNextMediaItem() }
    fun prevTrack() { if (player?.hasPreviousMediaItem() ?: false) player!!.seekToPreviousMediaItem() }

    fun loadPlaylist(items: List<MediaItem>, startIndex: Int) {
        _currentPlaylist.value = items
        val startPos = if (items.isNotEmpty() && startIndex in items.indices) {
            items[startIndex].startPositionMs
        } else {
            0L
        }
        playerManager.loadPlaylist(items, startIndex, startPos)
        viewModelScope.launch { repository.cleanupOldSettings() }
    }

    // --- Settings Logic ---

    private fun loadGlobalDefaults() {
        val outModeOrd = repository.getGlobalInt("def_output_mode", StereoOutputMode.ANAGLYPH.ordinal)
        _outputMode.postValue(StereoOutputMode.entries[outModeOrd])

        val anaTypeOrd = repository.getGlobalInt("def_anaglyph_type", StereoRenderer.AnaglyphType.RC_DUBOIS.ordinal)
        val type = StereoRenderer.AnaglyphType.entries[anaTypeOrd]
        _anaglyphType.postValue(type)

        _screenSeparation.postValue(repository.getGlobalFloat("global_screen_separation_pct", 0f))
        anaglyphDelegate.loadGlobalVrParams()

        if (AnaglyphLogic.isCustomType(type)) anaglyphDelegate.loadCustomSettings(type)
        updateAvailableSettings()
        anaglyphDelegate.updateAnaglyphMatrix(type)
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
        backupSettings = VideoSettings("", 0, _inputType.value!!, _outputMode.value!!, _anaglyphType.value!!, _swapEyes.value!!, _depth.value!!)
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

    // --- Menu Navigation ---
    fun getMainMenuItems(): List<MenuItem> {
        return listOf(
            MenuItem(
                "audio",
                "Аудиодорожка (${audioOptions.size})",
                currentAudioName.value,
                R.drawable.ic_audio_track
            ),
            MenuItem(
                "subtitles",
                "Субтитры (${subtitleOptions.size - 1})",
                currentSubtitleName.value,
                R.drawable.ic_subtitles
            ),
            MenuItem(
                "quick_settings",
                "Панель настройки",
                "3D, параллакс...",
                R.drawable.ic_settings_3d
            ),
            MenuItem(
                "global_settings",
                "Глобальные настройки",
                "Декодер, язык...",
                R.drawable.ic_build
            )
        )
    }

    fun getAudioTrackMenuItems(): List<MenuItem> {
        val options = audioOptions
        val currentIndex = currentAudioIndex

        return options.mapIndexed { index, option ->
            MenuItem(index.toString(), option.name, isSelected = index == currentIndex)
        }
    }

    fun getSubtitleMenuItems(): List<MenuItem> {
        val options = subtitleOptions
        val currentIndex = currentSubtitleIndex

        return options.mapIndexed { index, option ->
            MenuItem(index.toString(), option.name, isSelected = index == currentIndex)
        }
    }

    fun selectTrackByIndex(trackType: Int, index: Int) {
        val options = if (trackType == C.TRACK_TYPE_AUDIO) audioOptions else subtitleOptions

        if (index in options.indices) {
            val option = options[index]

            if (trackType == C.TRACK_TYPE_AUDIO) {
                currentAudioIndex = index
                _currentAudioName.value = option.name
            } else {
                currentSubtitleIndex = index
                _currentSubtitleName.value = option.name
            }

            // Общая логика применения
            if (player != null) {
                val builder = player!!.trackSelectionParameters.buildUpon()
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
                player!!.trackSelectionParameters = builder.build()
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
                _anaglyphType.value = getPreferredFilter(nextGroup)
                if (AnaglyphLogic.isCustomType(_anaglyphType.value!!)) anaglyphDelegate.loadCustomSettings(_anaglyphType.value!!)
                updateAvailableSettings()
                anaglyphDelegate.updateAnaglyphMatrix(_anaglyphType.value!!)
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

    // --- Helpers ---
    fun getOptionsForSetting(type: SettingType): Pair<List<String>, Int>? {
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
                    getString(resId)
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
                    getString(resId)
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
                    getString(resId)
                }
                Pair(list, currentGroup.ordinal)
            }
            SettingType.FILTER_MODE -> {
                val currentGroup = AnaglyphLogic.getGlassesGroup(_anaglyphType.value!!)
                val filters = AnaglyphLogic.getFiltersForGroup(currentGroup)
                val list = filters.map {
                    if (it.name.endsWith("_CUSTOM"))
                        getString(R.string.val_custom)
                    else it.name
                }
                val index = filters.indexOf(_anaglyphType.value!!)
                Pair(list, index)
            }
            SettingType.AUDIO_TRACK -> {
                if (audioOptions.isNotEmpty()) {
                    val list = audioOptions.map { it.name }
                    Pair(list, currentAudioIndex)
                } else null
            }
            SettingType.SUBTITLES -> {
                if (subtitleOptions.isNotEmpty()) {
                    val list = subtitleOptions.map { it.name }
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
        if (player == null) return
        val builder = player!!.trackSelectionParameters.buildUpon()
        if (option.isAuto) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
                .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        } else {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
            option.group?.let {
                builder.setOverrideForType(TrackSelectionOverride(it.mediaTrackGroup, option.trackIndex))
            }
        }
        player!!.trackSelectionParameters = builder.build()
        _currentQualityName.value = option.name
    }

    fun clearToastMessage() { _toastMessage.value = null }

    private fun showAutoDetectToast(type: StereoInputType) {
        val typeName = type.name.replace("_", " ")
        _toastMessage.value = getString(R.string.msg_auto_detect, typeName)
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
                if (halfAR < 1.2f) { finalFrameWidth = halfWidth * 2; finalFrameHeight = height }
                else { finalFrameWidth = halfWidth; finalFrameHeight = height }
            }
            StereoInputType.TOP_BOTTOM -> {
                val halfHeight = height / 2
                val halfAR = width / halfHeight
                if (halfAR > 2.5f) { finalFrameWidth = width; finalFrameHeight = halfHeight * 2 }
                else { finalFrameWidth = width; finalFrameHeight = halfHeight }
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
        playerManager.initializePlayer()
    }

    override fun onCleared() {
        super.onCleared()
        handler.removeCallbacks(progressUpdater)
        playerManager.releasePlayer(saveState = false)
    }
}