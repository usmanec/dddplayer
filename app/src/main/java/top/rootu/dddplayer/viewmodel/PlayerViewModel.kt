package top.rootu.dddplayer.viewmodel

import android.app.Application
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.launch
import top.rootu.dddplayer.R
import top.rootu.dddplayer.data.AppDatabase
import top.rootu.dddplayer.data.VideoSettings
import top.rootu.dddplayer.model.MediaItem
import top.rootu.dddplayer.model.StereoInputType
import top.rootu.dddplayer.model.StereoOutputMode
import top.rootu.dddplayer.renderer.StereoRenderer
import top.rootu.dddplayer.utils.DuboisMath
import top.rootu.dddplayer.utils.StereoTypeDetector
import java.util.Locale
import androidx.media3.common.MediaItem as Media3MediaItem

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

    // --- LiveData для UI ---
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

    // --- Video Quality & Info ---
    private val _videoResolution = MutableLiveData<String>()
    val videoResolution: LiveData<String> = _videoResolution

    private val _videoQualityOptions = MutableLiveData<List<VideoQualityOption>>()
    val videoQualityOptions: LiveData<List<VideoQualityOption>> = _videoQualityOptions

    private val _currentQualityName = MutableLiveData<String>("Auto")
    val currentQualityName: LiveData<String> = _currentQualityName

    private val _playlistSize = MutableLiveData<Int>(0)
    val playlistSize: LiveData<Int> = _playlistSize

    // --- LiveData для настроек рендерера ---
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

    // --- CUSTOM ANAGLYPH SETTINGS ---
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

    private val _singleFrameSize = MutableLiveData<Pair<Float, Float>>()
    val singleFrameSize: LiveData<Pair<Float, Float>> = _singleFrameSize
    private val _isSettingsPanelVisible = MutableLiveData(false)
    val isSettingsPanelVisible: LiveData<Boolean> = _isSettingsPanelVisible
    private val _currentSettingType = MutableLiveData(SettingType.VIDEO_TYPE)
    val currentSettingType: LiveData<SettingType> = _currentSettingType

    // Audio/Subs/Playlist
    private var audioOptions = listOf<TrackOption>()
    private var subtitleOptions = listOf<TrackOption>()
    private var currentAudioIndex = 0
    private var currentSubtitleIndex = 0
    private val _currentAudioName = MutableLiveData<String>()
    val currentAudioName: LiveData<String> = _currentAudioName
    private val _currentSubtitleName = MutableLiveData<String>()
    val currentSubtitleName: LiveData<String> = _currentSubtitleName
    private val _hasPrevious = MutableLiveData(false)
    val hasPrevious: LiveData<Boolean> = _hasPrevious
    private val _hasNext = MutableLiveData(false)
    val hasNext: LiveData<Boolean> = _hasNext

    private var availableSettings = listOf<SettingType>()
    private var backupSettings: VideoSettings? = null
    private var isSettingsLoadedFromDb = false

    private val handler = Handler(Looper.getMainLooper())
    private val httpDataSourceFactory = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setUserAgent("DDDPlayer/1.0")

    private val renderersFactory = DefaultRenderersFactory(application)
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

    val player: ExoPlayer = ExoPlayer.Builder(application, renderersFactory)
        .setMediaSourceFactory(DefaultMediaSourceFactory(httpDataSourceFactory))
        .setSeekBackIncrementMs(15000) // 15 сек для кнопок перемотки
        .setSeekForwardIncrementMs(15000)
        .build()

    private var lastVideoSize: VideoSize? = null
    private val db = AppDatabase.getDatabase(application)
    private val prefs = application.getSharedPreferences("global_prefs", Context.MODE_PRIVATE)
    private var currentUri: String? = null

    // Флаг, что пользователь сейчас мотает (чтобы таймер не сбивал позицию)
    var isUserInteracting = false

    private val progressUpdater = object : Runnable {
        override fun run() {
            if (player.isPlaying && !isUserInteracting) {
                _currentPosition.value = player.currentPosition
            }
            handler.postDelayed(this, 500)
        }
    }

    init {
        loadCustomSettingsForCurrentType()

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
                if (isPlaying) {
                    handler.post(progressUpdater)
                } else {
                    handler.removeCallbacks(progressUpdater)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                _isBuffering.value = (playbackState == Player.STATE_BUFFERING)
                if (playbackState == Player.STATE_READY) {
                    _duration.value = player.duration
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val errorMsg = "Ошибка воспроизведения:\n${error.errorCodeName}"
                _toastMessage.postValue(errorMsg)
                _isPlaying.postValue(false)
            }

            override fun onMediaItemTransition(mediaItem: Media3MediaItem?, reason: Int) {
                val title = mediaItem?.mediaMetadata?.title?.toString()
                currentUri = mediaItem?.localConfiguration?.uri?.toString()
                _videoTitle.value = title ?: mediaItem?.localConfiguration?.uri?.lastPathSegment

                updatePlaylistNavState()
                isSettingsLoadedFromDb = false
                _inputType.value = StereoInputType.NONE

                // Обновляем размер плейлиста
                _playlistSize.value = player.mediaItemCount

                if (currentUri != null) {
                    viewModelScope.launch {
                        val saved = db.videoSettingsDao().getSettings(currentUri!!)
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
                val width = videoSize.width.toFloat()
                val height = videoSize.height.toFloat()

                // Обновляем текст разрешения
                _videoResolution.value = "${videoSize.width}x${videoSize.height}"

                if (width == 0f || height == 0f) return

                if (isSettingsLoadedFromDb) {
                    calculateFrameSize(_inputType.value!!, videoSize)
                    return
                }

                if (_inputType.value == StereoInputType.NONE) {
                    val detectedType = StereoTypeDetector.detect(
                        player.videoFormat,
                        player.currentMediaItem?.localConfiguration?.uri
                    )
                    if (detectedType != StereoInputType.NONE) {
                        setInputType(detectedType)
                        showAutoDetectToast(detectedType, "метаданным/имени")
                    } else {
                        val videoAspectRatio = width / height
                        val heuristicType = when {
                            videoAspectRatio > 3.0 -> StereoInputType.SIDE_BY_SIDE
                            videoAspectRatio > 0.8 && videoAspectRatio < 1.2 && height > width * 1.1 -> StereoInputType.TOP_BOTTOM
                            else -> StereoInputType.NONE
                        }
                        if (heuristicType != StereoInputType.NONE) {
                            setInputType(heuristicType)
                            showAutoDetectToast(heuristicType, "соотношению сторон")
                        } else {
                            calculateFrameSize(StereoInputType.NONE, videoSize)
                        }
                    }
                } else {
                    calculateFrameSize(_inputType.value!!, videoSize)
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                extractTracks(tracks)
                extractVideoTracks(tracks)
                updateAvailableSettings()
            }

            override fun onCues(cueGroup: CueGroup) {
                _cues.value = cueGroup.cues
            }
        })
    }

    // --- Video Quality Logic ---
    private fun extractVideoTracks(tracks: Tracks) {
        val options = mutableListOf<VideoQualityOption>()

        // Сначала собираем только реальные треки
        for (group in tracks.groups) {
            if (group.type == C.TRACK_TYPE_VIDEO) {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    if (format.width > 0 && format.height > 0) {
                        val name = "${format.height}p"
                        options.add(
                            VideoQualityOption(
                                name,
                                format.width,
                                format.height,
                                format.bitrate,
                                group,
                                i
                            )
                        )
                    }
                }
            }
        }

        // Сортируем по высоте (качеству)
        val sortedOptions = options.sortedByDescending { it.height }.toMutableList()

        // Добавляем "Auto" в самое начало
        sortedOptions.add(0, VideoQualityOption("Auto", 0, 0, 0, null, -1, true))

        _videoQualityOptions.postValue(sortedOptions)
    }

    fun setVideoQuality(option: VideoQualityOption) {
        if (option.isAuto) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
                .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                .build()
            _currentQualityName.value = "Auto"
        } else {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
                .setOverrideForType(
                    TrackSelectionOverride(
                        option.group!!.mediaTrackGroup,
                        option.trackIndex
                    )
                )
                .build()
            _currentQualityName.value = option.name
        }
    }

    // --- Перемотка ---
    fun seekForward() {
        val current = player.currentPosition
        val target = (current + player.seekForwardIncrement).coerceAtMost(player.duration)
        _currentPosition.value = target // Мгновенное обновление UI
        player.seekTo(target)
    }

    fun seekBack() {
        val current = player.currentPosition
        val target = (current - player.seekBackIncrement).coerceAtLeast(0)
        _currentPosition.value = target // Мгновенное обновление UI
        player.seekTo(target)
    }

    // Прямая перемотка (для SeekBar)
    fun seekTo(position: Long) {
        _currentPosition.value = position
        player.seekTo(position)
    }

    fun togglePlayPause() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun nextTrack() {
        if (player.hasNextMediaItem()) player.seekToNextMediaItem()
    }

    fun prevTrack() {
        if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem()
    }

    private fun updatePlaylistNavState() {
        _hasPrevious.value = player.hasPreviousMediaItem()
        _hasNext.value = player.hasNextMediaItem()
    }

    private fun showAutoDetectToast(type: StereoInputType, method: String) {
        val typeName = type.name.replace("_", " ")
        val msg = getApplication<Application>().getString(R.string.auto_detect_toast, typeName)
        _toastMessage.value = msg
    }

    fun clearToastMessage() {
        _toastMessage.value = null
    }

    private fun extractTracks(tracks: Tracks) {
        val context = getApplication<Application>()
        val audioList = mutableListOf<TrackOption>()
        var selectedAudioIdx = 0
        var undCounter = 1
        for (group in tracks.groups) {
            if (group.type == C.TRACK_TYPE_AUDIO) {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val isSelected = group.isTrackSelected(i)
                    val name = buildAudioLabel(format, undCounter)
                    if (name.startsWith("und")) undCounter++
                    if (isSelected) selectedAudioIdx = audioList.size
                    audioList.add(TrackOption(name, group, i))
                }
            }
        }
        audioOptions = audioList
        currentAudioIndex = selectedAudioIdx
        if (audioOptions.isNotEmpty()) _currentAudioName.postValue(audioOptions[currentAudioIndex].name)
        val subList = mutableListOf<TrackOption>()
        subList.add(
            TrackOption(
                context.getString(R.string.track_off),
                null,
                -1,
                true
            )
        )
        var selectedSubIdx = 0
        undCounter = 1
        for (group in tracks.groups) {
            if (group.type == C.TRACK_TYPE_TEXT) {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val isSelected = group.isTrackSelected(i)
                    val name = buildSubtitleLabel(format, undCounter)
                    if (name.startsWith("und")) undCounter++
                    if (isSelected) selectedSubIdx = subList.size
                    subList.add(TrackOption(name, group, i))
                }
            }
        }
        subtitleOptions = subList
        currentSubtitleIndex = selectedSubIdx
        _currentSubtitleName.postValue(subtitleOptions[currentSubtitleIndex].name)
    }

    private fun buildAudioLabel(format: Format, undIndex: Int): String {
        val techInfo = getTechInfo(format)
        if (!format.label.isNullOrEmpty()) return "${format.label} ($techInfo)"
        val lang = format.language ?: "und"
        val locale = Locale(lang)
        var displayLang = locale.displayLanguage
        if (lang == "und" || displayLang.isEmpty()) displayLang = "und $undIndex"
        else displayLang =
            displayLang.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        return "$displayLang ($techInfo)"
    }

    private fun buildSubtitleLabel(format: Format, undIndex: Int): String {
        if (!format.label.isNullOrEmpty()) return format.label!!
        val lang = format.language ?: "und"
        val locale = Locale(lang)
        var displayLang = locale.displayLanguage
        if (lang == "und" || displayLang.isEmpty()) displayLang = "und $undIndex"
        else displayLang =
            displayLang.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        return displayLang
    }

    private fun getTechInfo(format: Format): String {
        val codec = when (format.sampleMimeType) {
            MimeTypes.AUDIO_AC3 -> "AC3"; MimeTypes.AUDIO_E_AC3 -> "E-AC3"; MimeTypes.AUDIO_E_AC3_JOC -> "DDP"; MimeTypes.AUDIO_DTS -> "DTS"; MimeTypes.AUDIO_DTS_HD -> "DTS-HD"; MimeTypes.AUDIO_DTS_EXPRESS -> "DTS-X"; MimeTypes.AUDIO_TRUEHD -> "TrueHD"; MimeTypes.AUDIO_AAC -> "AAC"; MimeTypes.AUDIO_MPEG -> "MP3"; MimeTypes.AUDIO_FLAC -> "FLAC"; MimeTypes.AUDIO_OPUS -> "Opus"; MimeTypes.AUDIO_VORBIS -> "Vorbis"; else -> ""
        }
        val channels = when (format.channelCount) {
            1 -> "1.0"; 2 -> "2.0"; 3 -> "2.1"; 6 -> "5.1"; 8 -> "7.1"; else -> if (format.channelCount != Format.NO_VALUE) "${format.channelCount}ch" else ""
        }
        return if (codec.isNotEmpty() && channels.isNotEmpty()) "$codec $channels" else codec + channels
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
                // Если половинка кадра "худая" (AR < 1.2), значит это анаморф и его надо растянуть
                if (halfAR < 1.2f) {
                    finalFrameWidth = halfWidth * 2 // Растягиваем до нормальной ширины
                    finalFrameHeight = height
                } else { // Иначе это полная стереопара, и половинка уже имеет правильные пропорции
                    finalFrameWidth = halfWidth
                    finalFrameHeight = height
                }
            }

            StereoInputType.TOP_BOTTOM -> {
                val halfHeight = height / 2
                val halfAR = width / halfHeight
                // Если половинка кадра "слишком широкая" (AR > 2.5), это анаморф
                if (halfAR > 2.5f) {
                    finalFrameWidth = width
                    finalFrameHeight = halfHeight * 2 // Растягиваем до нормальной высоты
                } else { // Иначе это полная стереопара
                    finalFrameWidth = width
                    finalFrameHeight = halfHeight
                }
            }
            else -> { // NONE и другие
                finalFrameWidth = width
                finalFrameHeight = height
            }
        }
        _singleFrameSize.value = Pair(finalFrameWidth, finalFrameHeight)
    }

    fun loadPlaylist(items: List<MediaItem>, startIndex: Int) {
        if (items.isNotEmpty()) httpDataSourceFactory.setDefaultRequestProperties(items[0].headers)
        val exoItems = items.map { item ->
            val subConfigs = item.subtitles.map { sub ->
                Media3MediaItem.SubtitleConfiguration.Builder(sub.uri)
                    .setMimeType(sub.mimeType ?: MimeTypes.APPLICATION_SUBRIP)
                    .setLanguage("und")
                    .setLabel(sub.name ?: sub.filename)
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build()
            }
            Media3MediaItem.Builder()
                .setUri(item.uri)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(item.title).build())
                .setSubtitleConfigurations(subConfigs)
                .build()
        }
        player.setMediaItems(exoItems, startIndex, items[startIndex].startPositionMs)
        player.prepare()
        player.playWhenReady = true
        viewModelScope.launch {
            db.videoSettingsDao().deleteOldSettings(System.currentTimeMillis() - 2592000000L)
        }
    }

    // --- Custom Settings Logic ---

    private fun getCustomPrefix(): String {
        return when (_anaglyphType.value) {
            StereoRenderer.AnaglyphType.RC_CUSTOM -> "rc_"
            StereoRenderer.AnaglyphType.YB_CUSTOM -> "yb_"
            StereoRenderer.AnaglyphType.GM_CUSTOM -> "gm_"
            else -> "rc_" // Default fallback
        }
    }

    private fun loadCustomSettingsForCurrentType() {
        val prefix = getCustomPrefix()
        _customHueOffsetL.value = prefs.getInt("${prefix}hue_l", 0)
        _customHueOffsetR.value = prefs.getInt("${prefix}hue_r", 0)
        _customLeakL.value = prefs.getFloat("${prefix}leak_l", 0.20f)
        _customLeakR.value = prefs.getFloat("${prefix}leak_r", 0.20f)
        _customSpaceLms.value = prefs.getBoolean("${prefix}space_lms", false)

        updateCalculatedColors()
    }

    private fun saveCustomSettings() {
        val prefix = getCustomPrefix()
        prefs.edit()
            .putInt("${prefix}hue_l", _customHueOffsetL.value!!)
            .putInt("${prefix}hue_r", _customHueOffsetR.value!!)
            .putFloat("${prefix}leak_l", _customLeakL.value!!)
            .putFloat("${prefix}leak_r", _customLeakR.value!!)
            .putBoolean("${prefix}space_lms", _customSpaceLms.value!!)
            .apply()

        updateCalculatedColors()
        updateAnaglyphMatrix()
    }

    private fun savePreferredFilter(type: StereoRenderer.AnaglyphType) {
        val group = getGlassesGroup(type)
        prefs.edit().putInt("pref_filter_${group.name}", type.ordinal).apply()
    }

    private fun getPreferredFilter(group: GlassesGroup): StereoRenderer.AnaglyphType {
        val savedOrdinal = prefs.getInt("pref_filter_${group.name}", -1)
        if (savedOrdinal != -1) {
            val values = StereoRenderer.AnaglyphType.values()
            if (savedOrdinal in values.indices) {
                val savedType = values[savedOrdinal]
                if (getGlassesGroup(savedType) == group) {
                    return savedType
                }
            }
        }
        return getDefaultFilterForGroup(group)
    }

    private fun getBaseColors(): Pair<Int, Int> {
        return when (_anaglyphType.value) {
            StereoRenderer.AnaglyphType.RC_CUSTOM -> Pair(Color.RED, Color.CYAN)
            StereoRenderer.AnaglyphType.YB_CUSTOM -> Pair(Color.YELLOW, Color.BLUE)
            StereoRenderer.AnaglyphType.GM_CUSTOM -> Pair(Color.GREEN, Color.MAGENTA)
            else -> Pair(Color.RED, Color.CYAN)
        }
    }

    private fun applyHueOffset(baseColor: Int, offset: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(baseColor, hsv)
        // Offset -100..100.
        // 1 unit = 0.5940594 degrees shift. Range +/- ~59.4 degrees.
        val shift = offset * 0.5940594f
        hsv[0] = (hsv[0] + shift + 360f) % 360f
        return Color.HSVToColor(hsv)
    }

    private fun updateCalculatedColors() {
        val (baseL, baseR) = getBaseColors()
        _calculatedColorL.value = applyHueOffset(baseL, _customHueOffsetL.value!!)
        _calculatedColorR.value = applyHueOffset(baseR, _customHueOffsetR.value!!)
    }

    private fun updateAnaglyphMatrix() {
        val type = _anaglyphType.value ?: return

        val matrices = if (isCustomType(type)) {
            val (baseL, baseR) = getBaseColors()
            val finalL = applyHueOffset(baseL, _customHueOffsetL.value!!)
            val finalR = applyHueOffset(baseR, _customHueOffsetR.value!!)

            DuboisMath.calculate(
                DuboisMath.DuboisParams(
                    colorLeft = finalL,
                    colorRight = finalR,
                    leakL = _customLeakL.value!!,
                    leakR = _customLeakR.value!!,
                    useLms = _customSpaceLms.value!!
                )
            )
        } else {
            getPresetMatrix(type)
        }

        _currentMatrices.value = Pair(matrices.left, matrices.right)
        _isMatrixValid.value = matrices.isValid
    }

    private fun isCustomType(type: StereoRenderer.AnaglyphType): Boolean {
        return type == StereoRenderer.AnaglyphType.RC_CUSTOM ||
                type == StereoRenderer.AnaglyphType.YB_CUSTOM ||
                type == StereoRenderer.AnaglyphType.GM_CUSTOM
    }

    private fun applySettings(s: VideoSettings) {
        _inputType.postValue(s.inputType)
        _outputMode.postValue(s.outputMode)
        _anaglyphType.postValue(s.anaglyphType)
        _swapEyes.postValue(s.swapEyes)
        _depth.postValue(s.depth)

        val sep = prefs.getFloat("global_screen_separation_pct", 0f)
        _screenSeparation.postValue(sep)

        val k1 = prefs.getFloat("vr_k1", 0.34f)
        val k2 = prefs.getFloat("vr_k2", 0.10f)
        val scale = prefs.getFloat("vr_scale", 1.2f)
        _vrK1.postValue(k1)
        _vrK2.postValue(k2)
        _vrScale.postValue(scale)

        handler.post { lastVideoSize?.let { calculateFrameSize(s.inputType, it) } }

        if (isCustomType(s.anaglyphType)) {
            loadCustomSettingsForCurrentType()
        }

        updateAvailableSettings()
        updateAnaglyphMatrix()
    }

    private fun loadGlobalDefaults() {
        val outModeOrd = prefs.getInt("def_output_mode", StereoOutputMode.ANAGLYPH.ordinal)
        _outputMode.postValue(StereoOutputMode.values()[outModeOrd])
        val anaTypeOrd =
            prefs.getInt("def_anaglyph_type", StereoRenderer.AnaglyphType.RC_DUBOIS.ordinal)
        val type = StereoRenderer.AnaglyphType.values()[anaTypeOrd]
        _anaglyphType.postValue(type)

        val sep = prefs.getFloat("global_screen_separation_pct", 0f)
        _screenSeparation.postValue(sep)

        val k1 = prefs.getFloat("vr_k1", 0.34f)
        val k2 = prefs.getFloat("vr_k2", 0.10f)
        val scale = prefs.getFloat("vr_scale", 1.2f)
        _vrK1.postValue(k1)
        _vrK2.postValue(k2)
        _vrScale.postValue(scale)

        if (isCustomType(type)) {
            loadCustomSettingsForCurrentType()
        }

        updateAvailableSettings()
        updateAnaglyphMatrix()
    }

    fun saveCurrentSettings() {
        val uri = currentUri ?: return
        val settings = VideoSettings(
            uri,
            System.currentTimeMillis(),
            _inputType.value!!,
            _outputMode.value!!,
            _anaglyphType.value!!,
            _swapEyes.value!!,
            _depth.value!!
        )
        viewModelScope.launch { db.videoSettingsDao().saveSettings(settings) }

        prefs.edit()
            .putInt("def_output_mode", settings.outputMode.ordinal)
            .putInt("def_anaglyph_type", settings.anaglyphType.ordinal)
            .putFloat("global_screen_separation_pct", _screenSeparation.value!!)
            .putFloat("vr_k1", _vrK1.value!!)
            .putFloat("vr_k2", _vrK2.value!!)
            .putFloat("vr_scale", _vrScale.value!!)
            .apply()

        isSettingsLoadedFromDb = true
    }

    fun openSettingsPanel(isFirstRun: Boolean = false) {
        backupSettings = VideoSettings(
            "",
            0,
            _inputType.value!!,
            _outputMode.value!!,
            _anaglyphType.value!!,
            _swapEyes.value!!,
            _depth.value!!
        )
        updateAvailableSettings()
        if (_currentSettingType.value !in availableSettings) {
            _currentSettingType.value = availableSettings.firstOrNull() ?: SettingType.VIDEO_TYPE
        }
        _isSettingsPanelVisible.postValue(true)
    }

    fun closeSettingsPanel(save: Boolean) {
        if (save) saveCurrentSettings() else {
            backupSettings?.let { applySettings(it) }
            // Восстанавливаем глобальные настройки из префов
            val sep = prefs.getFloat("global_screen_separation_pct", 0f)
            _screenSeparation.postValue(sep)
            val k1 = prefs.getFloat("vr_k1", 0.34f)
            val k2 = prefs.getFloat("vr_k2", 0.10f)
            val scale = prefs.getFloat("vr_scale", 1.2f)
            _vrK1.postValue(k1)
            _vrK2.postValue(k2)
            _vrScale.postValue(scale)
        }
        _isSettingsPanelVisible.value = false
    }

    private fun updateAvailableSettings() {
        val list = mutableListOf<SettingType>()
        list.add(SettingType.VIDEO_TYPE)
        if (_inputType.value != StereoInputType.NONE) {
            list.add(SettingType.OUTPUT_FORMAT)
            if (_outputMode.value == StereoOutputMode.ANAGLYPH) {
                list.add(SettingType.GLASSES_TYPE)
                list.add(SettingType.FILTER_MODE)

                if (isCustomType(_anaglyphType.value!!)) {
                    list.add(SettingType.CUSTOM_HUE_L)
                    list.add(SettingType.CUSTOM_LEAK_L)
                    list.add(SettingType.CUSTOM_HUE_R)
                    list.add(SettingType.CUSTOM_LEAK_R)
                    list.add(SettingType.CUSTOM_SPACE)
                }
            }
            list.add(SettingType.SWAP_EYES)
            list.add(SettingType.DEPTH_3D)

            if (_outputMode.value == StereoOutputMode.CARDBOARD_VR) {
                list.add(SettingType.SCREEN_SEPARATION)
                list.add(SettingType.VR_DISTORTION)
                list.add(SettingType.VR_ZOOM)
            }
        }
        if (audioOptions.size > 1) list.add(SettingType.AUDIO_TRACK)
        if (subtitleOptions.size > 1) list.add(SettingType.SUBTITLES)
        availableSettings = list

        if (_isSettingsPanelVisible.value == true && _currentSettingType.value !in list) {
            _currentSettingType.value = list.first()
        }
    }

    fun onMenuUp() {
        val current = _currentSettingType.value ?: return
        val idx = availableSettings.indexOf(current)
        _currentSettingType.value =
            if (idx > 0) availableSettings[idx - 1] else availableSettings.last()
    }

    fun onMenuDown() {
        val current = _currentSettingType.value ?: return
        val idx = availableSettings.indexOf(current)
        _currentSettingType.value =
            if (idx < availableSettings.size - 1) availableSettings[idx + 1] else availableSettings.first()
    }

    fun onMenuLeft() {
        changeSettingValue(-1)
    }

    fun onMenuRight() {
        changeSettingValue(1)
    }

    private fun changeSettingValue(direction: Int) {
        when (_currentSettingType.value) {
            SettingType.VIDEO_TYPE -> {
                val values = StereoInputType.values()
                val nextOrd = (_inputType.value!!.ordinal + direction + values.size) % values.size
                setInputType(values[nextOrd])
            }

            SettingType.OUTPUT_FORMAT -> {
                val values = StereoOutputMode.values()
                val nextOrd = (_outputMode.value!!.ordinal + direction + values.size) % values.size
                _outputMode.value = values[nextOrd]
                updateAvailableSettings()
            }

            SettingType.GLASSES_TYPE -> {
                val currentGroup = getGlassesGroup(_anaglyphType.value!!)
                val groups = GlassesGroup.values()
                val nextGroup =
                    groups[(currentGroup.ordinal + direction + groups.size) % groups.size]

                _anaglyphType.value = getPreferredFilter(nextGroup)

                if (isCustomType(_anaglyphType.value!!)) {
                    loadCustomSettingsForCurrentType()
                }

                updateAvailableSettings()
                updateAnaglyphMatrix()
            }

            SettingType.FILTER_MODE -> {
                val currentGroup = getGlassesGroup(_anaglyphType.value!!)
                val filters = getFiltersForGroup(currentGroup)
                val currentIdx = filters.indexOf(_anaglyphType.value!!)
                val nextIdx = (currentIdx + direction + filters.size) % filters.size
                val nextFilter = filters[nextIdx]

                _anaglyphType.value = nextFilter
                savePreferredFilter(nextFilter)

                if (isCustomType(nextFilter)) {
                    loadCustomSettingsForCurrentType()
                }

                updateAvailableSettings()
                updateAnaglyphMatrix()
            }

            SettingType.CUSTOM_HUE_L -> {
                val current = _customHueOffsetL.value!!
                _customHueOffsetL.value = (current + direction).coerceIn(-100, 100)
                saveCustomSettings()
            }

            SettingType.CUSTOM_HUE_R -> {
                val current = _customHueOffsetR.value!!
                _customHueOffsetR.value = (current + direction).coerceIn(-100, 100)
                saveCustomSettings()
            }

            SettingType.CUSTOM_LEAK_L -> {
                val current = _customLeakL.value!!
                _customLeakL.value = (current + direction * 0.01f).coerceIn(0f, 0.5f)
                saveCustomSettings()
            }

            SettingType.CUSTOM_LEAK_R -> {
                val current = _customLeakR.value!!
                _customLeakR.value = (current + direction * 0.01f).coerceIn(0f, 0.5f)
                saveCustomSettings()
            }

            SettingType.CUSTOM_SPACE -> {
                _customSpaceLms.value = !_customSpaceLms.value!!
                saveCustomSettings()
            }

            SettingType.SWAP_EYES -> _swapEyes.value = !_swapEyes.value!!
            SettingType.DEPTH_3D -> {
                val current = _depth.value ?: 0
                _depth.value = (current + direction).coerceIn(-50, 50)
            }

            SettingType.SCREEN_SEPARATION -> {
                val current = _screenSeparation.value ?: 0f
                // Шаг 0.5% от ширины экрана
                val newVal = (current + direction * 0.005f).coerceIn(-0.15f, 0.15f)
                _screenSeparation.value = newVal
            }

            SettingType.VR_DISTORTION -> {
                val current = _vrK1.value ?: 0.34f
                val newVal = (current + direction * 0.02f).coerceIn(0.0f, 2.0f)
                _vrK1.value = newVal
            }

            SettingType.VR_ZOOM -> {
                val current = _vrScale.value ?: 1.2f
                val newVal = (current + direction * 0.05f).coerceIn(0.5f, 3.0f)
                _vrScale.value = newVal
            }

            SettingType.AUDIO_TRACK -> {
                if (audioOptions.isNotEmpty()) {
                    currentAudioIndex =
                        (currentAudioIndex + direction + audioOptions.size) % audioOptions.size
                    val option = audioOptions[currentAudioIndex]
                    _currentAudioName.value = option.name
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setOverrideForType(
                            TrackSelectionOverride(
                                option.group!!.mediaTrackGroup,
                                option.trackIndex
                            )
                        )
                        .build()

                    if (player.playerError != null) {
                        player.prepare()
                        player.play()
                    }
                }
            }

            SettingType.SUBTITLES -> {
                if (subtitleOptions.isNotEmpty()) {
                    currentSubtitleIndex =
                        (currentSubtitleIndex + direction + subtitleOptions.size) % subtitleOptions.size
                    val option = subtitleOptions[currentSubtitleIndex]
                    _currentSubtitleName.value = option.name
                    val builder = player.trackSelectionParameters.buildUpon()
                    if (option.isOff) builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    else {
                        builder.setTrackTypeDisabled(
                            C.TRACK_TYPE_TEXT,
                            false
                        )
                        builder.setOverrideForType(
                            TrackSelectionOverride(
                                option.group!!.mediaTrackGroup,
                                option.trackIndex
                            )
                        )
                    }
                    player.trackSelectionParameters = builder.build()
                }
            }

            else -> {}
        }
    }

    private fun getGlassesGroup(type: StereoRenderer.AnaglyphType): GlassesGroup {
        return when {
            type == StereoRenderer.AnaglyphType.RC_CUSTOM -> GlassesGroup.RED_CYAN
            type == StereoRenderer.AnaglyphType.YB_CUSTOM -> GlassesGroup.YELLOW_BLUE
            type == StereoRenderer.AnaglyphType.GM_CUSTOM -> GlassesGroup.GREEN_MAGENTA
            type.name.startsWith("RC_") -> GlassesGroup.RED_CYAN
            type.name.startsWith("YB_") -> GlassesGroup.YELLOW_BLUE
            type.name.startsWith("GM_") -> GlassesGroup.GREEN_MAGENTA
            type.name.startsWith("RB_") -> GlassesGroup.RED_BLUE
            else -> GlassesGroup.RED_CYAN
        }
    }

    private fun getFiltersForGroup(group: GlassesGroup): List<StereoRenderer.AnaglyphType> {
        return when (group) {
            GlassesGroup.RED_CYAN -> listOf(
                StereoRenderer.AnaglyphType.RC_DUBOIS,
                StereoRenderer.AnaglyphType.RC_COLOR,
                StereoRenderer.AnaglyphType.RC_HALF_COLOR,
                StereoRenderer.AnaglyphType.RC_OPTIMIZED,
                StereoRenderer.AnaglyphType.RC_MONO,
                StereoRenderer.AnaglyphType.RC_CUSTOM
            )

            GlassesGroup.YELLOW_BLUE -> listOf(
                StereoRenderer.AnaglyphType.YB_DUBOIS,
                StereoRenderer.AnaglyphType.YB_COLOR,
                StereoRenderer.AnaglyphType.YB_HALF_COLOR,
                StereoRenderer.AnaglyphType.YB_MONO,
                StereoRenderer.AnaglyphType.YB_CUSTOM
            )

            GlassesGroup.GREEN_MAGENTA -> listOf(
                StereoRenderer.AnaglyphType.GM_DUBOIS,
                StereoRenderer.AnaglyphType.GM_COLOR,
                StereoRenderer.AnaglyphType.GM_HALF_COLOR,
                StereoRenderer.AnaglyphType.GM_MONO,
                StereoRenderer.AnaglyphType.GM_CUSTOM
            )

            GlassesGroup.RED_BLUE -> listOf(StereoRenderer.AnaglyphType.RB_MONO)
        }
    }

    private fun getDefaultFilterForGroup(group: GlassesGroup): StereoRenderer.AnaglyphType {
        return getFiltersForGroup(group).first()
    }

    fun setInputType(inputType: StereoInputType) {
        if (_inputType.value == inputType) return
        _inputType.value = inputType
        lastVideoSize?.let { calculateFrameSize(inputType, it) }
        updateAvailableSettings()
    }

    fun toggleSwapEyes() {
        _swapEyes.value = !(_swapEyes.value ?: false)
    }

    override fun onCleared() {
        super.onCleared()
        handler.removeCallbacks(progressUpdater)
        player.release()
    }

    // --- Preset Matrices Helper ---
    private fun getPresetMatrix(type: StereoRenderer.AnaglyphType): DuboisMath.ResultMatrices {
        val l: FloatArray
        val r: FloatArray

        when (type) {
            StereoRenderer.AnaglyphType.RC_DUBOIS -> {
                l = floatArrayOf(0.4154f, 0.4710f, 0.1669f, -0.0458f, -0.0484f, -0.0257f,-0.0547f, -0.0615f, 0.0128f)
                r = floatArrayOf(-0.0109f, -0.0364f, -0.0060f, 0.3756f, 0.7333f, 0.0111f, -0.0651f, -0.1287f, 1.2971f)
            }

            StereoRenderer.AnaglyphType.RC_HALF_COLOR -> {
                l = floatArrayOf(0.299f, 0.587f, 0.114f, 0f, 0f, 0f, 0f, 0f, 0f)
                r = floatArrayOf(0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
            }

            StereoRenderer.AnaglyphType.RC_COLOR -> {
                l = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
                r = floatArrayOf(0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
            }

            StereoRenderer.AnaglyphType.RC_MONO -> {
                l = floatArrayOf(0.299f, 0.587f, 0.114f, 0f, 0f, 0f, 0f, 0f, 0f)
                r = floatArrayOf(0f, 0f, 0f, 0.299f, 0.587f, 0.114f, 0.299f, 0.587f, 0.114f)
            }

            StereoRenderer.AnaglyphType.RC_OPTIMIZED -> {
                l = floatArrayOf(0.4122f, 0.5604f, 0.2008f, -0.0723f, -0.0409f, -0.0697f, -0.0004f, -0.0011f, 0.1662f)
                r = floatArrayOf(-0.0211f, -0.1121f, -0.0402f, 0.3616f, 0.8075f, 0.0139f, 0.0021f, 0.0002f, 0.8330f)
            }

            StereoRenderer.AnaglyphType.YB_DUBOIS -> {
                l = floatArrayOf(1.0615f, -0.0585f, -0.0159f, 0.1258f, 0.7697f, -0.0892f, -0.0458f, -0.0838f, -0.0020f)
                r = floatArrayOf(-0.0223f, -0.0593f, -0.0088f, -0.0263f, -0.0348f, -0.0038f, 0.1874f, 0.3367f, 0.7649f)
            }

            StereoRenderer.AnaglyphType.YB_HALF_COLOR -> {
                l = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f)
                r = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0.299f, 0.587f, 0.114f)
            }

            StereoRenderer.AnaglyphType.YB_COLOR -> {
                l = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f)
                r = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f)
            }

            StereoRenderer.AnaglyphType.YB_MONO -> {
                l = floatArrayOf(0.299f, 0.587f, 0.114f, 0.299f, 0.587f, 0.114f, 0f, 0f, 0f)
                r = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0.299f, 0.587f, 0.114f)
            }

            StereoRenderer.AnaglyphType.GM_DUBOIS -> {
                l = floatArrayOf(-0.062f, -0.158f, -0.039f, 0.284f, 0.668f, 0.143f, -0.015f, -0.027f, 0.021f)
                r = floatArrayOf(0.529f, 0.705f, 0.024f, -0.016f, -0.015f, -0.065f, 0.009f, -0.075f, 0.937f)
            }

            StereoRenderer.AnaglyphType.GM_COLOR -> {
                l = floatArrayOf(0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f)
                r = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f)
            }

            StereoRenderer.AnaglyphType.GM_HALF_COLOR -> {
                l = floatArrayOf(0f, 0f, 0f, 0.299f, 0.587f, 0.114f, 0f, 0f, 0f)
                r = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f)
            }

            StereoRenderer.AnaglyphType.GM_MONO -> {
                l = floatArrayOf(0f, 0f, 0f, 0.299f, 0.587f, 0.114f, 0f, 0f, 0f)
                r = floatArrayOf(0.299f, 0.587f, 0.114f, 0f, 0f, 0f, 0.299f, 0.587f, 0.114f)
            }

            StereoRenderer.AnaglyphType.RB_MONO -> {
                l = floatArrayOf(0.299f, 0.587f, 0.114f, 0f, 0f, 0f, 0f, 0f, 0f)
                r = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0.299f, 0.587f, 0.114f)
            }

            else -> {
                l = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
                r = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
            }
        }
        return DuboisMath.ResultMatrices(l, r, true)
    }
}