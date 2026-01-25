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
import top.rootu.dddplayer.BuildConfig
import top.rootu.dddplayer.data.SettingsRepository
import top.rootu.dddplayer.data.VideoSettings
import top.rootu.dddplayer.logic.AnaglyphLogic
import top.rootu.dddplayer.logic.SettingsMutator
import top.rootu.dddplayer.logic.TrackLogic
import top.rootu.dddplayer.logic.UpdateInfo
import top.rootu.dddplayer.logic.UpdateManager
import top.rootu.dddplayer.model.MediaItem
import top.rootu.dddplayer.model.StereoInputType
import top.rootu.dddplayer.model.StereoOutputMode
import top.rootu.dddplayer.player.PlayerManager
import top.rootu.dddplayer.renderer.StereoRenderer
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
    private val _currentWindowIndex = MutableLiveData<Int>(0)
    val currentWindowIndex: LiveData<Int> = _currentWindowIndex
    private val _playlistSize = MutableLiveData<Int>(0)
    val playlistSize: LiveData<Int> = _playlistSize
    private val _hasPrevious = MutableLiveData(false)
    val hasPrevious: LiveData<Boolean> = _hasPrevious
    private val _hasNext = MutableLiveData(false)
    val hasNext: LiveData<Boolean> = _hasNext

    // Quality & Info
    private val _videoResolution = MutableLiveData<String>()
    val videoResolution: LiveData<String> = _videoResolution
    private val _videoAspectRatio = MutableLiveData<Float>(1.777f)
    val videoAspectRatio: LiveData<Float> = _videoAspectRatio
    private val _videoQualityOptions = MutableLiveData<List<VideoQualityOption>>()
    val videoQualityOptions: LiveData<List<VideoQualityOption>> = _videoQualityOptions
    private val _currentQualityName = MutableLiveData<String>("Auto")
    val currentQualityName: LiveData<String> = _currentQualityName

    // Renderer Settings
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

    // VR Settings
    private val _vrK1 = MutableLiveData(0.34f)
    val vrK1: LiveData<Float> = _vrK1
    private val _vrK2 = MutableLiveData(0.10f)
    val vrK2: LiveData<Float> = _vrK2
    private val _vrScale = MutableLiveData(1.2f)
    val vrScale: LiveData<Float> = _vrScale

    // Custom Anaglyph
    private val _customHueOffsetL = MutableLiveData(0)
    val customHueOffsetL: LiveData<Int> = _customHueOffsetL
    private val _customHueOffsetR = MutableLiveData(0)
    val customHueOffsetR: LiveData<Int> = _customHueOffsetR
    private val _customLeakL = MutableLiveData(0.20f)
    val customLeakL: LiveData<Float> = _customLeakL
    private val _customLeakR = MutableLiveData(0.20f)
    val customLeakR: LiveData<Float> = _customLeakR
    private val _customSpaceLms = MutableLiveData(false)
    val customSpaceLms: LiveData<Boolean> = _customSpaceLms
    private val _calculatedColorL = MutableLiveData(0)
    val calculatedColorL: LiveData<Int> = _calculatedColorL
    private val _calculatedColorR = MutableLiveData(0)
    val calculatedColorR: LiveData<Int> = _calculatedColorR
    private val _currentMatrices = MutableLiveData<Pair<FloatArray, FloatArray>>()
    val currentMatrices: LiveData<Pair<FloatArray, FloatArray>> = _currentMatrices
    private val _isMatrixValid = MutableLiveData(true)
    val isMatrixValid: LiveData<Boolean> = _isMatrixValid

    // UI State
    private val _singleFrameSize = MutableLiveData<Pair<Float, Float>>()
    val singleFrameSize: LiveData<Pair<Float, Float>> = _singleFrameSize
    private val _isSettingsPanelVisible = MutableLiveData(false)
    val isSettingsPanelVisible: LiveData<Boolean> = _isSettingsPanelVisible
    private val _currentSettingType = MutableLiveData(SettingType.VIDEO_TYPE)
    val currentSettingType: LiveData<SettingType> = _currentSettingType

    // Tracks & Nav
    private val _audioOutputInfo = MutableLiveData<String>()
    val audioOutputInfo: LiveData<String> = _audioOutputInfo
    private val _currentAudioName = MutableLiveData<String>()
    val currentAudioName: LiveData<String> = _currentAudioName
    private val _currentSubtitleName = MutableLiveData<String>()
    val currentSubtitleName: LiveData<String> = _currentSubtitleName
    private val _videoDisabledError = MutableLiveData<PlaybackException?>()
    val videoDisabledError: LiveData<PlaybackException?> = _videoDisabledError
    // Для фатальных ошибок (когда плеер остановился)
    private val _fatalError = MutableLiveData<PlaybackException?>()
    val fatalError: LiveData<PlaybackException?> = _fatalError
    private val _bufferedPercentage = MutableLiveData<Int>(0)
    val bufferedPercentage: LiveData<Int> = _bufferedPercentage
    private val _bufferedPosition = MutableLiveData<Long>(0)
    val bufferedPosition: LiveData<Long> = _bufferedPosition

    // Internal
    private var audioOptions = listOf<TrackOption>()
    private var subtitleOptions = listOf<TrackOption>()
    private var currentAudioIndex = 0
    private var currentSubtitleIndex = 0
    private var availableSettings = listOf<SettingType>()
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

    private val updateManager = UpdateManager(application)
    private val _updateInfo = MutableLiveData<UpdateInfo?>()
    val updateInfo: LiveData<UpdateInfo?> = _updateInfo
    private val _downloadProgress = MutableLiveData<Int>()
    val downloadProgress: LiveData<Int> = _downloadProgress
    private val _isCheckingUpdates = MutableLiveData<Boolean>(false)
    val isCheckingUpdates: LiveData<Boolean> = _isCheckingUpdates

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

    // Listener вынесен в поле класса
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
        loadCustomSettingsForCurrentType()

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

        checkUpdates()
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

    private fun checkUpdates() {
        viewModelScope.launch {
            val currentVersion = BuildConfig.VERSION_NAME
            var hasUpdate = false

            val savedJson = repository.getLastUpdateInfo()
            if (savedJson != null) {
                val savedInfo = updateManager.fromJson(savedJson)
                if (savedInfo != null && updateManager.isNewer(savedInfo.version, currentVersion)) {
                    _updateInfo.postValue(savedInfo)
                    hasUpdate = true
                } else {
                    updateManager.deleteUpdateFile()
                    repository.saveUpdateInfo(null)
                }
            }

            if (!hasUpdate) {
                _updateInfo.postValue(null)
            }

            val lastCheck = repository.getLastUpdateTime()
            if (System.currentTimeMillis() - lastCheck < 10800000) {
                return@launch
            }

            try {
                val info = updateManager.checkForUpdates(currentVersion)
                if (info != null) {
                    _updateInfo.postValue(info)
                    repository.saveUpdateInfo(updateManager.toJson(info))
                } else {
                    repository.saveUpdateInfo(null)
                    _updateInfo.postValue(null)
                }
            } catch (e: Exception) {
            }
        }
    }

    fun forceCheckUpdates() {
        if (_isCheckingUpdates.value == true) return
        _isCheckingUpdates.value = true

        viewModelScope.launch {
            try {
                val pInfo = getApplication<Application>().packageManager.getPackageInfo(getApplication<Application>().packageName, 0)
                val currentVersion = pInfo.versionName

                val info = updateManager.checkForUpdates(currentVersion)

                if (info != null) {
                    _updateInfo.postValue(info)
                    repository.saveUpdateInfo(updateManager.toJson(info))
                    _toastMessage.postValue("Найдено обновление: ${info.version}")
                } else {
                    repository.saveUpdateInfo(null)
                    _updateInfo.postValue(null)
                    _toastMessage.postValue("У вас последняя версия")
                }
                repository.setLastUpdateTime(System.currentTimeMillis())

            } catch (e: Exception) {
                _toastMessage.postValue("Ошибка проверки обновлений")
            } finally {
                _isCheckingUpdates.postValue(false)
            }
        }
    }

    fun startUpdate() {
        val info = _updateInfo.value ?: return
        viewModelScope.launch {
            val file = updateManager.downloadApk(info.downloadUrl) { progress ->
                _downloadProgress.postValue(progress)
            }
            if (file != null) {
                updateManager.installApk(file)
            }
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
                _toastMessage.postValue("Ошибка видео. Переключено в аудио-режим.")
            } else if (trackType == C.TRACK_TYPE_AUDIO) {
                if (audioOptions.size > 1) {
                    _toastMessage.postValue("Ошибка аудио. Звук отключен.\nПопробуйте другую дорожку.\n${error.errorCodeName}: ${error.message}")
                } else {
                    _toastMessage.postValue("Ошибка аудио. Звук отключен.\n${error.errorCodeName}: ${error.message}")
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

    private fun loadCustomSettingsForCurrentType() {
        val prefix = AnaglyphLogic.getCustomPrefix(_anaglyphType.value!!)
        _customHueOffsetL.value = repository.getGlobalInt("${prefix}hue_l", 0)
        _customHueOffsetR.value = repository.getGlobalInt("${prefix}hue_r", 0)
        _customLeakL.value = repository.getGlobalFloat("${prefix}leak_l", 0.20f)
        _customLeakR.value = repository.getGlobalFloat("${prefix}leak_r", 0.20f)
        _customSpaceLms.value = repository.getGlobalBoolean("${prefix}space_lms", false)
        updateCalculatedColors()
    }

    private fun saveCustomSettings() {
        val prefix = AnaglyphLogic.getCustomPrefix(_anaglyphType.value!!)
        repository.putGlobalInt("${prefix}hue_l", _customHueOffsetL.value!!)
        repository.putGlobalInt("${prefix}hue_r", _customHueOffsetR.value!!)
        repository.putGlobalFloat("${prefix}leak_l", _customLeakL.value!!)
        repository.putGlobalFloat("${prefix}leak_r", _customLeakR.value!!)
        repository.putGlobalBoolean("${prefix}space_lms", _customSpaceLms.value!!)
        updateCalculatedColors()
        updateAnaglyphMatrix()
    }

    private fun loadGlobalVrParams() {
        _screenSeparation.postValue(repository.getGlobalFloat("global_screen_separation_pct", 0f))
        _vrK1.postValue(repository.getGlobalFloat("vr_k1", 0.34f))
        _vrK2.postValue(repository.getGlobalFloat("vr_k2", 0.10f))
        _vrScale.postValue(repository.getGlobalFloat("vr_scale", 1.2f))
    }

    private fun loadGlobalDefaults() {
        val outModeOrd = repository.getGlobalInt("def_output_mode", StereoOutputMode.ANAGLYPH.ordinal)
        _outputMode.postValue(StereoOutputMode.values()[outModeOrd])

        val anaTypeOrd = repository.getGlobalInt("def_anaglyph_type", StereoRenderer.AnaglyphType.RC_DUBOIS.ordinal)
        val type = StereoRenderer.AnaglyphType.values()[anaTypeOrd]
        _anaglyphType.postValue(type)

        loadGlobalVrParams()

        if (AnaglyphLogic.isCustomType(type)) loadCustomSettingsForCurrentType()
        updateAvailableSettings()
        updateAnaglyphMatrix()
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
        repository.putGlobalFloat("vr_k1", _vrK1.value!!)
        repository.putGlobalFloat("vr_k2", _vrK2.value!!)
        repository.putGlobalFloat("vr_scale", _vrScale.value!!)

        isSettingsLoadedFromDb = true
    }

    fun openSettingsPanel() {
        backupSettings = VideoSettings("", 0, _inputType.value!!, _outputMode.value!!, _anaglyphType.value!!, _swapEyes.value!!, _depth.value!!)
        updateAvailableSettings()
        if (_currentSettingType.value !in availableSettings) {
            _currentSettingType.value = availableSettings.firstOrNull() ?: SettingType.VIDEO_TYPE
        }
        _isSettingsPanelVisible.postValue(true)
    }

    fun closeSettingsPanel(save: Boolean) {
        if (save) saveCurrentSettings() else {
            backupSettings?.let { applySettings(it) }
            loadGlobalVrParams()
        }
        _isSettingsPanelVisible.value = false
    }

    private fun applySettings(s: VideoSettings) {
        _inputType.postValue(s.inputType)
        _outputMode.postValue(s.outputMode)
        _anaglyphType.postValue(s.anaglyphType)
        _swapEyes.postValue(s.swapEyes)
        _depth.postValue(s.depth)

        loadGlobalVrParams()

        handler.post { lastVideoSize?.let { calculateFrameSize(s.inputType, it) } }
        if (AnaglyphLogic.isCustomType(s.anaglyphType)) loadCustomSettingsForCurrentType()
        updateAvailableSettings()
        updateAnaglyphMatrix()
    }

    // --- Menu Navigation ---
    fun onMenuUp() {
        _currentSettingType.value = SettingsMutator.cycleList(_currentSettingType.value!!, availableSettings, -1)
    }

    fun onMenuDown() {
        _currentSettingType.value = SettingsMutator.cycleList(_currentSettingType.value!!, availableSettings, 1)
    }

    fun onMenuLeft() { changeSettingValue(-1) }
    fun onMenuRight() { changeSettingValue(1) }

    private fun changeSettingValue(direction: Int) {
        when (_currentSettingType.value) {
            SettingType.VIDEO_TYPE -> setInputType(SettingsMutator.cycleEnum(_inputType.value!!, direction))
            SettingType.OUTPUT_FORMAT -> {
                _outputMode.value = SettingsMutator.cycleEnum(_outputMode.value!!, direction)
                updateAvailableSettings()
            }
            SettingType.GLASSES_TYPE -> {
                val currentGroup = AnaglyphLogic.getGlassesGroup(_anaglyphType.value!!)
                val nextGroup = SettingsMutator.cycleEnum(currentGroup, direction)
                _anaglyphType.value = getPreferredFilter(nextGroup)
                if (AnaglyphLogic.isCustomType(_anaglyphType.value!!)) loadCustomSettingsForCurrentType()
                updateAvailableSettings()
                updateAnaglyphMatrix()
            }
            SettingType.FILTER_MODE -> {
                val currentGroup = AnaglyphLogic.getGlassesGroup(_anaglyphType.value!!)
                val filters = AnaglyphLogic.getFiltersForGroup(currentGroup)
                val nextFilter = SettingsMutator.cycleList(_anaglyphType.value!!, filters, direction)
                _anaglyphType.value = nextFilter
                savePreferredFilter(nextFilter)
                if (AnaglyphLogic.isCustomType(nextFilter)) loadCustomSettingsForCurrentType()
                updateAvailableSettings()
                updateAnaglyphMatrix()
            }
            SettingType.CUSTOM_HUE_L -> {
                _customHueOffsetL.value = SettingsMutator.modifyInt(_customHueOffsetL.value!!, direction, 1, -100, 100)
                saveCustomSettings()
            }
            SettingType.CUSTOM_HUE_R -> {
                _customHueOffsetR.value = SettingsMutator.modifyInt(_customHueOffsetR.value!!, direction, 1, -100, 100)
                saveCustomSettings()
            }
            SettingType.CUSTOM_LEAK_L -> {
                _customLeakL.value = SettingsMutator.modifyFloat(_customLeakL.value!!, direction, 0.01f, 0f, 0.5f)
                saveCustomSettings()
            }
            SettingType.CUSTOM_LEAK_R -> {
                _customLeakR.value = SettingsMutator.modifyFloat(_customLeakR.value!!, direction, 0.01f, 0f, 0.5f)
                saveCustomSettings()
            }
            SettingType.CUSTOM_SPACE -> {
                _customSpaceLms.value = !_customSpaceLms.value!!
                saveCustomSettings()
            }
            SettingType.SWAP_EYES -> _swapEyes.value = !_swapEyes.value!!
            SettingType.DEPTH_3D -> _depth.value = SettingsMutator.modifyInt(_depth.value!!, direction, 1, -50, 50)
            SettingType.SCREEN_SEPARATION -> _screenSeparation.value = SettingsMutator.modifyFloat(_screenSeparation.value!!, direction, 0.005f, -0.15f, 0.15f)
            SettingType.VR_DISTORTION -> _vrK1.value = SettingsMutator.modifyFloat(_vrK1.value!!, direction, 0.02f, 0.0f, 2.0f)
            SettingType.VR_ZOOM -> _vrScale.value = SettingsMutator.modifyFloat(_vrScale.value!!, direction, 0.05f, 0.5f, 3.0f)
            SettingType.AUDIO_TRACK -> {
                if (audioOptions.isNotEmpty()) {
                    currentAudioIndex = (currentAudioIndex + direction + audioOptions.size) % audioOptions.size
                    val option = audioOptions[currentAudioIndex]
                    _currentAudioName.value = option.name

                    if (player != null) {
                        val builder = player!!.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                            .setOverrideForType(
                                TrackSelectionOverride(
                                    option.group!!.mediaTrackGroup,
                                    option.trackIndex
                                )
                            )

                        player!!.trackSelectionParameters = builder.build()

                        val current = player!!.currentPosition ?: 0
                        player!!.seekTo(current + 16)

                        if (player!!.playerError != null) {
                            player!!.prepare()
                            player!!.play()
                        }
                    }
                }
            }
            SettingType.SUBTITLES -> {
                if (subtitleOptions.isNotEmpty()) {
                    currentSubtitleIndex = (currentSubtitleIndex + direction + subtitleOptions.size) % subtitleOptions.size
                    val option = subtitleOptions[currentSubtitleIndex]
                    _currentSubtitleName.value = option.name

                    // Используем playerManager для выбора трека (он использует trackSelectionParameters)
                    // Но так как у нас нет метода selectTrack в новом PlayerManager, реализуем тут
                    if (player != null) {
                        val builder = player!!.trackSelectionParameters.buildUpon()
                        if (option.isOff) {
                            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        } else {
                            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
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
            else -> {}
        }
    }

    // --- Helpers ---
    fun getOptionsForCurrentSetting(): Pair<List<String>, Int>? {
        return when (_currentSettingType.value) {
            SettingType.VIDEO_TYPE -> {
                val values = StereoInputType.values()
                val list = values.map { it.name.replace("_", " ") }
                Pair(list, _inputType.value!!.ordinal)
            }
            SettingType.OUTPUT_FORMAT -> {
                val values = StereoOutputMode.values()
                val list = values.map { it.name.replace("_", " ") }
                Pair(list, _outputMode.value!!.ordinal)
            }
            SettingType.GLASSES_TYPE -> {
                val currentGroup = AnaglyphLogic.getGlassesGroup(_anaglyphType.value!!)
                val groups = GlassesGroup.values()
                val list = groups.map {
                    when(it) {
                        GlassesGroup.RED_CYAN -> "Red - Cyan"
                        GlassesGroup.YELLOW_BLUE -> "Yellow - Blue"
                        GlassesGroup.GREEN_MAGENTA -> "Green - Magenta"
                        GlassesGroup.RED_BLUE -> "Red - Blue"
                    }
                }
                Pair(list, currentGroup.ordinal)
            }
            SettingType.FILTER_MODE -> {
                val currentGroup = AnaglyphLogic.getGlassesGroup(_anaglyphType.value!!)
                val filters = AnaglyphLogic.getFiltersForGroup(currentGroup)
                val list = filters.map {
                    if (it.name.endsWith("_CUSTOM")) "Custom" else it.name
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
        _toastMessage.value = "Автоопределение: $typeName"
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
        availableSettings = list
        if (_isSettingsPanelVisible.value == true && _currentSettingType.value !in list) {
            _currentSettingType.value = list.first()
        }
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
            val values = StereoRenderer.AnaglyphType.values()
            if (savedOrdinal in values.indices) {
                val savedType = values[savedOrdinal]
                if (AnaglyphLogic.getGlassesGroup(savedType) == group) return savedType
            }
        }
        return AnaglyphLogic.getFiltersForGroup(group).first()
    }

    private fun updateCalculatedColors() {
        val (baseL, baseR) = AnaglyphLogic.getBaseColors(_anaglyphType.value!!)
        _calculatedColorL.value = AnaglyphLogic.applyHueOffset(baseL, _customHueOffsetL.value!!)
        _calculatedColorR.value = AnaglyphLogic.applyHueOffset(baseR, _customHueOffsetR.value!!)
    }

    private fun updateAnaglyphMatrix() {
        val type = _anaglyphType.value ?: return
        val matrices = AnaglyphLogic.calculateMatrix(
            type,
            _customHueOffsetL.value!!, _customHueOffsetR.value!!,
            _customLeakL.value!!, _customLeakR.value!!,
            _customSpaceLms.value!!
        )
        _currentMatrices.value = Pair(matrices.left, matrices.right)
        _isMatrixValid.value = matrices.isValid
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