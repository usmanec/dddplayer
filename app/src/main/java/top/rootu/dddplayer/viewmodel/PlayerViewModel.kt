package top.rootu.dddplayer.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
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
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistTracker
import kotlinx.coroutines.launch
import top.rootu.dddplayer.R
import top.rootu.dddplayer.data.SettingsRepository
import top.rootu.dddplayer.data.VideoSettings
import top.rootu.dddplayer.logic.AnaglyphLogic
import top.rootu.dddplayer.logic.SettingsMutator
import top.rootu.dddplayer.logic.StereoFormatLogic
import top.rootu.dddplayer.logic.StreamTypeLogic
import top.rootu.dddplayer.logic.TrackLogic
import top.rootu.dddplayer.model.AspectRatioMode
import top.rootu.dddplayer.model.HistoryEntry
import top.rootu.dddplayer.model.HistorySource
import top.rootu.dddplayer.model.MediaItem
import top.rootu.dddplayer.model.MenuItem
import top.rootu.dddplayer.model.PlaybackSpeed
import top.rootu.dddplayer.model.ResizeMode
import top.rootu.dddplayer.model.StereoInputType
import top.rootu.dddplayer.model.StereoOutputMode
import top.rootu.dddplayer.model.SubtitleItem
import top.rootu.dddplayer.player.PlayerManager
import top.rootu.dddplayer.renderer.StereoRenderer
import top.rootu.dddplayer.utils.MediaFormatHelper
import top.rootu.dddplayer.utils.StereoTypeDetector
import top.rootu.dddplayer.utils.afr.RuntimeFpsDetector
import top.rootu.dddplayer.utils.getString
import androidx.media3.common.MediaItem as Media3MediaItem

// --- Enums & Data Classes ---
enum class SettingType {
    VIDEO_TYPE, OUTPUT_FORMAT, GLASSES_TYPE, RENDER_API, FILTER_MODE,
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

// Обертка для одноразовых событий LiveData
open class Event<out T>(private val content: T) {
    var hasBeenHandled = false
        private set

    fun getContentIfNotHandled(): T? {
        return if (hasBeenHandled) {
            null
        } else {
            hasBeenHandled = true
            content
        }
    }
}

// Состояние плейлиста для UI (фильтр по группе M3U)
data class PlaylistUiState(
    val items: List<MediaItem> = emptyList(),
    val groupName: String? = null, // null = все группы
    val availableGroups: List<String> = emptyList() // Пусто, если в плейлисте нет групп
)

@UnstableApi
// Пункт "Загрузить из файла" в меню субтитров
const val SUBTITLE_MENU_LOAD_FILE = "load_file"
// Пункт "Своя скорость" в меню скорости
const val SPEED_MENU_CUSTOM = "custom_speed"

// Сторож зависшей загрузки: сколько ждать роста буфера и сколько раз переподключаться подряд
private const val STALL_TIMEOUT_MS = 20_000L
private const val MAX_STALL_RECONNECTS = 3

class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private var ioRetryCount = 0
    // Элементы (mediaId), которые уже пробовали открыть как HLS после ошибки контейнера
    private val hlsFallbackTried = mutableSetOf<String>()
    private val MAX_IO_RETRIES = 3

    // true, пока плейлист перезагружается для восстановления после ошибки.
    // В этот момент onMediaItemTransition приходит с тем же видео, и сбрасывать
    // счетчик повторов и настройки видео нельзя.
    private var isRecoveringFromError = false

    private val repository = SettingsRepository.getInstance(application)

    // Делегат для 3D/VR настроек
    val anaglyphDelegate = AnaglyphDelegate(repository)

    // --- LiveData ---
    private val _isPlaying = MutableLiveData<Boolean>()
    val isPlaying: LiveData<Boolean> = _isPlaying
    private val _afrTriggerEvent = MutableLiveData<Format>()
    val afrTriggerEvent: LiveData<Format> = _afrTriggerEvent

    private var afrAppliedForCurrentItem = false

    // Детектор FPS
    private val fpsDetector = RuntimeFpsDetector()

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
    private val _playlistUiState = MutableLiveData(PlaylistUiState())
    val playlistUiState: LiveData<PlaylistUiState> = _playlistUiState

    // Плейлист загружен из M3U файла/ссылки
    private val _isM3uPlaylist = MutableLiveData(false)
    val isM3uPlaylist: LiveData<Boolean> = _isM3uPlaylist
    private var loadMediaJob: kotlinx.coroutines.Job? = null

    // В текущем плейлисте уже играл live-поток (условие эфирного режима)
    private val _isSessionLive = MutableLiveData(false)
    val isSessionLive: LiveData<Boolean> = _isSessionLive

    // Переключение каналов с задержкой: канал, который будет включен
    private var zapJob: kotlinx.coroutines.Job? = null
    private val _zapPreviewItem = MutableLiveData<MediaItem?>(null)
    val zapPreviewItem: LiveData<MediaItem?> = _zapPreviewItem

    private val _showInfoPanelEvent = MutableLiveData<Event<Unit>>()
    val showInfoPanelEvent: LiveData<Event<Unit>> = _showInfoPanelEvent
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
    private val _tunnelingActive = MutableLiveData(false)
    val tunnelingActive: LiveData<Boolean> = _tunnelingActive
    // Соотношение сторон для отображения: принудительное (если выбрано) или из видео
    private val _videoAspectRatio = MutableLiveData(1.777f)
    val videoAspectRatio: LiveData<Float> = _videoAspectRatio
    private var naturalAspectRatio = 1.777f
    private val _aspectRatioMode = MutableLiveData(AspectRatioMode.AUTO)
    val aspectRatioMode: LiveData<AspectRatioMode> = _aspectRatioMode
    private val _videoQualityOptions = MutableLiveData<List<VideoQualityOption>>()
    val videoQualityOptions: LiveData<List<VideoQualityOption>> = _videoQualityOptions

    /** Число дорожек видео/аудио/субтитров (без "Авто" и "Выкл") - для скрытия лишних кнопок панели */
    data class TrackCounts(val video: Int, val audio: Int, val subtitles: Int)
    private val _trackCounts = MutableLiveData<TrackCounts>()
    val trackCounts: LiveData<TrackCounts> = _trackCounts
    private val _currentQualityName = MutableLiveData("Auto")
    val currentQualityName: LiveData<String> = _currentQualityName

    // Renderer Settings
    private var deviceSupportsEs3 = false
    private val _renderApiMode = MutableLiveData(repository.getRenderApiMode())
    val renderApiMode: LiveData<Int> = _renderApiMode
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
    // Скорость воспроизведения (может быть своей, не из списка PlaybackSpeed)
    private val _playbackSpeed = MutableLiveData(repository.getDefaultPlaybackSpeed())
    val playbackSpeed: LiveData<Float> = _playbackSpeed
    // Скорость взята из настройки по умолчанию, а не выбрана в меню: в прямом эфире ее сбрасываем на 1.00x
    private var speedFromDefault = true

    private val _resizeMode = MutableLiveData(ResizeMode.FIT)
    val resizeMode: LiveData<ResizeMode> = _resizeMode

    private val _zoomScale = MutableLiveData(repository.getZoomScalePercent())
    val zoomScale: LiveData<Int> = _zoomScale

    private val _isLive = MutableLiveData<Boolean>()
    val isLive: LiveData<Boolean> = _isLive

    // Храним ID, которые нужно применить, когда треки загрузятся (восстановление треков)
    private var pendingAudioId: String? = null
    private var pendingSubtitleId: String? = null

    // Название внешних субтитров, которые нужно включить после их добавления
    private var pendingSubtitleLabel: String? = null

    // Аудиодорожку выбрал пользователь (запоминаем ее для других видео)
    private var audioChosenByUser = false
    // Выбор пользователя еще не учтен в статистике дорожек (учитываем один раз на видео)
    private var audioChoicePendingStat = false
    // Дорожка, выбранная пользователем в текущем плейлисте (для следующих элементов)
    private var sessionAudioSignature: String? = null
    // Дорожка для текущего видео уже определена (сохраненная, выбранная или найденная среди последних)
    private var audioTrackResolved = false
    // Субтитры для текущего видео уже выбраны (сохраненные, пользователем или автовключением)
    private var subtitleResolved = false
    // Настройки видео из базы прочитаны (автовключение ждет их, чтобы не перебить сохраненные субтитры)
    private var videoSettingsChecked = false

    // Internal
    private var audioOptions = listOf<TrackOption>()
    private var subtitleOptions = listOf<TrackOption>()
    private var currentAudioIndex = 0
    private var currentSubtitleIndex = 0
    private var backupSettings: VideoSettings? = null
    private var isSettingsLoadedFromDb = false
    private var currentUri: String? = null
    private var lastVideoSize: VideoSize? = null

    /** Размер кадра текущего видео (для подписи стереоформата: Half или Full) */
    val videoSize: VideoSize? get() = lastVideoSize
    var isUserInteracting = false

    // Состояние воспроизведения при уходе с экрана (onPause), чтобы в onResume
    // не запускать видео, которое пользователь сам поставил на паузу
    var resumePlayWhenReady = true
    private val _playbackEnded = MutableLiveData<Boolean>()
    val playbackEnded: LiveData<Boolean> = _playbackEnded

    private val handler = Handler(Looper.getMainLooper())

    // Player Manager
    private val playerManager: PlayerManager

    // Безопасный доступ к плееру (может быть null, если не инициализирован)
    val player: ExoPlayer? get() = playerManager.exoPlayer
    val isDirectAudioOutput: Boolean get() = playerManager.isDirectAudioOutput
    /** Плеер остановлен в onStop, чтобы освободить HDMI-выход; в onStart его нужно подготовить заново. */
    var stoppedInBackground = false

    // Событие: Плеер был пересоздан (нужно привязать Surface)
    // Плеер нужно закрыть: "Стоп" через MediaSession
    private val _closeRequested = MutableLiveData<Event<Unit>>()
    val closeRequested: LiveData<Event<Unit>> = _closeRequested

    private val _playerRecreatedEvent = MutableLiveData<ExoPlayer>()
    val playerRecreatedEvent: LiveData<ExoPlayer> = _playerRecreatedEvent

    // Флаг для однократного срабатывания Resume при открытии плеера
    private var isFirstItemLoaded = false

    // Позиция из внешнего приложения, о которой нужно спросить (id элемента -> позиция)
    private var pendingExternalPosition: Pair<String, Long>? = null
    private val _showResumeDialogEvent = MutableLiveData<Pair<Long, Long>?>()
    val showResumeDialogEvent: LiveData<Pair<Long, Long>?> = _showResumeDialogEvent

    // Храним хэш настроек при старте
    private var lastSettingsHash = repository.getHardSettingsSignature()

    // Сторож зависшей загрузки: буфер не растет при буферизации - переоткрываем соединение
    private var stallBufferedPos = -1L
    private var stallSinceMs = 0L
    private var stallReconnects = 0

    /**
     * После долгой паузы сервер (TorrServer) или роутер молча закрывает соединение, и плеер ждет данных
     * из мертвого сокета до тайм-аута чтения (120 с), а то и дольше. Помогала только перемотка за буфер.
     * Если при буферизации буфер не растет [STALL_TIMEOUT_MS], повторяем то же самое: seekTo на текущую
     * позицию (данных под ней нет - загрузчик открывает новое соединение).
     */
    private fun checkLoadingStall(p: Player) {
        val stalled = p.playbackState == Player.STATE_BUFFERING && p.playWhenReady && !p.isCurrentMediaItemLive &&
            p.currentMediaItem?.localConfiguration?.uri?.scheme?.startsWith("http") == true
        val now = SystemClock.elapsedRealtime()
        if (!stalled) {
            stallBufferedPos = -1L
            if (p.playbackState == Player.STATE_READY) stallReconnects = 0
            return
        }
        if (p.bufferedPosition != stallBufferedPos) {
            stallBufferedPos = p.bufferedPosition
            stallSinceMs = now
            return
        }
        if (now - stallSinceMs < STALL_TIMEOUT_MS || stallReconnects >= MAX_STALL_RECONNECTS) return
        stallReconnects++
        stallSinceMs = now
        Log.w("PlayerViewModel", "Loading stalled for ${STALL_TIMEOUT_MS / 1000}s, reconnecting ($stallReconnects/$MAX_STALL_RECONNECTS)")
        p.seekTo(p.currentPosition)
    }

    private val progressUpdater = object : Runnable {
        override fun run() {
            val p = playerManager.exoPlayer ?: return
            checkLoadingStall(p)
            if (p.isPlaying || p.isLoading) {
                if (!isUserInteracting) {
                    _currentPosition.value = p.currentPosition
                }
                updateBufferInfo(p)

                // Если AFR еще не сработал, проверяем формат
                if (!afrAppliedForCurrentItem) {
                    val format = p.videoFormat
                    if (format != null) {
                        updateVideoInfoBadge(format) // Обновляем инфо, а он сам решит, запускать ли детектор
                    }
                }
            }
            handler.postDelayed(this, 200)
        }
    }

    private fun updateBufferInfo(p: Player) {
        _bufferedPosition.value = p.bufferedPosition

        // Логика расчета процента буферизации вперед
        // (ExoPlayer.bufferedPercentage не подходит,
        // т.к. он показывает % буфера на прогрессе, а не заполненность буфера)
        // Если для сетевого видео задан размер буфера в МБ - считаем по объему
        val percent = playerManager.getNetworkBufferFillPercent() ?: run {
            val bufferedDuration = p.bufferedPosition - p.currentPosition
            val targetBuffer = if (bufferedDuration > 6_000L) 50_000L else 5_000L
            val maxPercent = if (targetBuffer == 5_000L) 99 else 100
            ((bufferedDuration * 101) / targetBuffer).toInt().coerceIn(0, maxPercent)
        }
        _bufferedPercentage.value = percent
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            updateProgressUpdaterState()
        }

        // На паузе буфер продолжает загружаться: обновляем его, пока идет загрузка
        override fun onIsLoadingChanged(isLoading: Boolean) {
            updateProgressUpdaterState()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _isBuffering.value = (playbackState == Player.STATE_BUFFERING)
            if (playbackState == Player.STATE_READY) {
                ioRetryCount = 0 // Сброс счетчика, если видео успешно заиграло
                _duration.value = player?.duration
                // После буферизации или старта обновляем инфо
                player?.videoFormat?.let { updateVideoInfoBadge(it) }
            }
            if (playbackState == Player.STATE_ENDED) _playbackEnded.value = true
            updateProgressUpdaterState()
            val isCurrentItemLive = player?.isCurrentMediaItemLive ?: false
            _isLive.value = isCurrentItemLive
            // Ускоренный эфир упирается в край трансляции: скорость по умолчанию к нему не применяем
            if (speedFromDefault) {
                val target = if (isCurrentItemLive) 1.0f else repository.getDefaultPlaybackSpeed()
                if (_playbackSpeed.value != target) applyPlaybackSpeed(target)
            }
            if (isCurrentItemLive) _isSessionLive.value = true
        }

        override fun onPlayerError(error: PlaybackException) {
            if (tryRecoverFromError(error)) {
                return
            }
            _fatalError.postValue(error)
            _isPlaying.postValue(false)
        }

        override fun onMediaItemTransition(mediaItem: Media3MediaItem?, reason: Int) {
            if (isRecoveringFromError) {
                isRecoveringFromError = false
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED &&
                    mediaItem?.localConfiguration?.uri?.toString() == currentUri
                ) {
                    // То же видео после перезагрузки плейлиста - ничего не сбрасываем
                    return
                }
            }
            ioRetryCount = 0 // Сброс счетчика при смене видео
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

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                // При перемотке просто останавливаем текущий процесс детекции, если он был запущен.
                fpsDetector.stop(player)
            }
        }
    }

    init {
        // Загружаем кастомные настройки через делегат
        anaglyphDelegate.loadCustomSettings(_anaglyphType.value!!)

        playerManager = PlayerManager(application, playerListener)

        // Подписываемся на создание нового плеера
        playerManager.onPlayerCreated = { newPlayer ->
            // Новый плеер (и пересозданный после смены настроек) идет с выбранной скоростью
            newPlayer.setPlaybackSpeed(_playbackSpeed.value ?: 1.0f)
            _playerRecreatedEvent.postValue(newPlayer)
            // Принудительно обновляем UI состояние
            _isPlaying.postValue(newPlayer.isPlaying)
            _duration.postValue(newPlayer.duration)
        }

        playerManager.onVideoFormatChanged = { format ->
            updateVideoInfoBadge(format)
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
        playerManager.onTunnelingChanged = { _tunnelingActive.postValue(it) }
        playerManager.onStopRequested = { _closeRequested.value = Event(Unit) }

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
        val shouldRun = p.isPlaying || p.isLoading || p.playbackState == Player.STATE_BUFFERING

        handler.removeCallbacks(progressUpdater)
        if (shouldRun) {
            handler.post(progressUpdater)
        } else {
            // Последнее значение после остановки загрузки (на паузе буфер мог дозаполниться)
            updateBufferInfo(p)
        }
    }

    private fun tryRecoverFromError(error: PlaybackException): Boolean {
        val p = player ?: return false
        val cause = error.cause
        val TAG = "tryRecoverFromError"

        // Перехват застрявшего HLS плейлиста
        if (cause is HlsPlaylistTracker.PlaylistStuckException) {
            Log.w(TAG, "HLS Playlist stuck. Forcing reload...")
            val currentIndex = p.currentMediaItemIndex
            val currentPos = p.currentPosition

            // Для Live прыгаем на край, для VOD восстанавливаем позицию
            if (p.isCurrentMediaItemLive) {
                p.seekTo(currentIndex, C.TIME_UNSET)
            } else {
                p.seekTo(currentIndex, currentPos)
            }
            p.prepare()
            p.play()
            return true
        }

        // Универсальный перехват IO и сетевых ошибок (MKV/MP4/HLS)
        if (error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT) {

            if (ioRetryCount < MAX_IO_RETRIES) {
                ioRetryCount++
                Log.w(TAG, "IO Error detected ($ioRetryCount/$MAX_IO_RETRIES). Retrying at current position...")

                val currentIndex = p.currentMediaItemIndex
                val currentPos = p.currentPosition // Сохраняем текущую точку

                // Собираем новый список MediaItem
                val newItems = mutableListOf<Media3MediaItem>()
                for (i in 0 until p.mediaItemCount) {
                    newItems.add(p.getMediaItemAt(i))
                }

                // Переподготавливаем плеер и возвращаем его на то же место
                isRecoveringFromError = true
                playerManager.reloadMediaItems(newItems, currentIndex, currentPos)
                return true
            }
        }

        // Попытка восстановления: Неверно определенный контейнер (скрытый HLS/DASH)
        if (error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED) {
            val currentItem = p.currentMediaItem
            val originalUri = currentItem?.localConfiguration?.uri

            if (originalUri != null) {
                // Тип от сервера (Content-Type, расширение после редиректов). Если сервер его не сообщил,
                // а файл не похож на обычное видео, один раз пробуем HLS (ссылки балансеров без .m3u8)
                val resolvedMimeType = playerManager.getResolvedMimeType(originalUri)
                    ?: MimeTypes.APPLICATION_M3U8.takeIf {
                        StreamTypeLogic.shouldTryHls(originalUri.path) && hlsFallbackTried.add(currentItem.mediaId)
                    }

                if (resolvedMimeType != null && currentItem.localConfiguration?.mimeType != resolvedMimeType) {

                    // ВАЖНО: Чтобы ExoPlayer гарантированно пересоздал MediaSource (например, сменил
                    // ProgressiveMediaSource на HlsMediaSource), нам нужно пересобрать весь плейлист.
                    // Метод replaceMediaItem может не сработать, если URI остался прежним.

                    val currentIndex = p.currentMediaItemIndex
                    val currentPos = p.currentPosition

                    // Собираем новый список MediaItem
                    val newItems = mutableListOf<Media3MediaItem>()
                    for (i in 0 until p.mediaItemCount) {
                        val item = p.getMediaItemAt(i)
                        if (i == currentIndex) {
                            // Подменяем MimeType только у проблемного элемента
                            newItems.add(
                                item.buildUpon()
                                    .setMimeType(resolvedMimeType)
                                    .build()
                            )
                        } else {
                            newItems.add(item)
                        }
                    }

                    // Полностью перезагружаем плейлист в плеер
                    isRecoveringFromError = true
                    playerManager.reloadMediaItems(newItems, currentIndex, currentPos)

                    return true // Успешно перехватили и исправили
                }
            }
        }

        if (error !is ExoPlaybackException || error.type != ExoPlaybackException.TYPE_RENDERER) {
            return false
        }

        val rendererIndex = error.rendererIndex
        if (rendererIndex == C.INDEX_UNSET) return false

        player?.let { p ->
            val trackType = p.getRendererType(rendererIndex)

            when (trackType) {
                C.TRACK_TYPE_VIDEO -> {
                    _videoDisabledError.postValue(error)
                    _toastMessage.postValue(getString(R.string.error_video_decoder))

                    // Отключаем проблемный трек
                    val parameters = p.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(trackType, true)
                        .build()
                    p.trackSelectionParameters = parameters

                }
                C.TRACK_TYPE_AUDIO -> {
                    val hint = if (audioOptions.size > 2) getString(R.string.error_audio_disabled_hint, "${error.errorCodeName}: ${error.message}")
                    else getString(R.string.error_audio_disabled, "${error.errorCodeName}: ${error.message}")
                    _toastMessage.postValue(hint)
                    selectTrackByIndex(C.TRACK_TYPE_AUDIO, 0) // Выкл
                }
                else -> {
                    return false
                }
            }

            // Перезапускаем воспроизведение
            p.seekTo(p.currentMediaItemIndex, p.currentPosition)
            p.prepare()
            p.play()
        }
        return true
    }

    private fun handleMediaItemTransition(mediaItem: Media3MediaItem?) {
        afrAppliedForCurrentItem = false
        fpsDetector.stop(player)
        fpsDetector.reset() // Сброс для нового видео

        // Сбрасываем отложенные ID дорожек при переходе на новое видео
        pendingAudioId = null
        pendingSubtitleId = null
        audioChosenByUser = false
        audioChoicePendingStat = false
        audioTrackResolved = false
        subtitleResolved = false
        videoSettingsChecked = false

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

            if (_isM3uPlaylist.value == true && currentUri != null) {
                repository.setLastPlayedChannelUri(currentUri)
            }
            // Новая серия сразу заменяет запись истории источника
            updateWatchHistory()

            // Инфопанель при смене элемента: по настройке или в эфирном режиме
            if (p.mediaItemCount > 1 && (repository.isShowInfoOnTrackChange() || isOverallLiveMode())) {
                _showInfoPanelEvent.value = Event(Unit)
            }

            if (currentUri != null) {
                viewModelScope.launch {
                    val saved = repository.getVideoSettings(currentUri!!)
                    if (saved != null) {
                        applySettings(saved)
                        // Сохраняем ID из базы как отложенные
                        pendingAudioId = saved.audioTrackId
                        pendingSubtitleId = saved.subtitleTrackId

                        isSettingsLoadedFromDb = true
                    } else {
                        loadGlobalDefaults()
                    }
                    videoSettingsChecked = true
                    // Дорожки могли прийти раньше настроек: применяем автовключение субтитров сейчас
                    if (!subtitleResolved && repository.getSubtitleAutoMode() != SettingsRepository.SUB_AUTO_DEFAULT) {
                        player?.let { updateTracksInfo(it.currentTracks) }
                    }
                    if (!isFirstItemLoaded) {
                        handleResumeLogic(saved)
                    }
                    // Помечаем, что первое видео обработавано.
                    isFirstItemLoaded = true
                }
            } else {
                videoSettingsChecked = true
            }
        }
    }

    /**
     * Продолжение просмотра для первого элемента.
     * Позиция из внешнего приложения (интент) приоритетнее сохраненной в плеере: при режиме
     * "Применять сразу" плеер уже стартовал с нее, при режиме "Спрашивать" - показываем диалог.
     * Сохраненная позиция используется, только если приложение позицию не передало.
     */
    private fun handleResumeLogic(settings: VideoSettings?) {
        if (player?.isCurrentMediaItemLive == true) {
            pendingExternalPosition = null
            return
        }

        val playingId = player?.currentMediaItem?.mediaId
        val external = pendingExternalPosition?.takeIf { it.first == playingId }?.second
        pendingExternalPosition = null

        val knownDuration = player?.duration?.takeIf { it > 0 && it != C.TIME_UNSET }
        if (external != null) {
            val duration = settings?.duration?.takeIf { it > 0 } ?: knownDuration ?: 0L
            if (isResumePositionMeaningful(external, duration)) {
                player?.pause()
                _showResumeDialogEvent.postValue(Pair(external, duration))
            }
            return
        }

        val currentIndex = player?.currentMediaItemIndex ?: -1
        val intentPos = if (currentIndex != -1) {
            _currentPlaylist.value?.getOrNull(currentIndex)?.startPositionMs ?: 0L
        } else 0L
        val resumeMode = repository.getResumeMode()
        // Если в интенте была позиция (не 0), игнорируем БД
        if (settings == null || intentPos > 0 || resumeMode == SettingsRepository.RESUME_NEVER) return

        val savedPos = settings.lastPosition
        val totalDur = settings.duration
        if (!isResumePositionMeaningful(savedPos, totalDur)) return

        // Применяем режим
        when (resumeMode) {
            SettingsRepository.RESUME_ALWAYS -> {
                seekTo(savedPos)
            }
            SettingsRepository.RESUME_ASK -> {
                player?.pause()
                _showResumeDialogEvent.postValue(Pair(savedPos, totalDur))
            }
        }
    }

    // Правило 95% или менее 5 секунд прогресса - начинаем с 0
    private fun isResumePositionMeaningful(position: Long, duration: Long): Boolean {
        if (position < 5000L) return false
        if (duration > 0 && position.toFloat() / duration.toFloat() > 0.95f) return false
        return true
    }

    fun resumePlayback(position: Long) {
        seekTo(position)
        player?.play()
        _showResumeDialogEvent.value = null
    }

    fun cancelResume() {
        player?.play()
        _showResumeDialogEvent.value = null
    }

    private fun updateVideoInfoBadge(format: Format) {
        // Используем сохраненный FPS, если он есть
        var fps = fpsDetector.detectedFrameRate ?: format.frameRate

        if (fps <= 0f) {
            fps = tryGetFpsFromDeepSources()
        }

        val originalUri = player?.currentMediaItem?.localConfiguration?.uri

        val manifest = player?.currentManifest

        val container = MediaFormatHelper.getShortContainerName(
            format.containerMimeType,
            originalUri,
            manifest
        )

        val codec = MediaFormatHelper.getShortVideoCodecName(format)
        val hdr = MediaFormatHelper.getHdrInfo(format)
        val fpsStr = MediaFormatHelper.formatFrameRate(fps)

        val builder = StringBuilder()

        if (container.isNotEmpty()) {
            builder.append(container)
            if (codec.isNotEmpty()) builder.append("($codec) ")
            else builder.append(" ")
        } else if (codec.isNotEmpty()) {
            builder.append("$codec ")
        }

        builder.append(MediaFormatHelper.formatResolution(format.width, format.height))

        if (fpsStr.isNotEmpty()) {
            builder.append("@$fpsStr")
            if (fpsDetector.detectedFrameRate != null && fpsDetector.detectedFrameRate!! > 0f) {
                builder.append("'")
            }
        }

        if (hdr.isNotEmpty()) {
            builder.append(" $hdr")
        }

        _videoResolution.postValue(builder.toString().trim())

        // ЛОГИКА ТРИГГЕРА AFR
        if (!afrAppliedForCurrentItem) {
            if (fps > 0f) {
                afrAppliedForCurrentItem = true
                val finalFormat = format.buildUpon().setFrameRate(fps).build()
                _afrTriggerEvent.postValue(finalFormat)
            } else {
                player?.let { p ->
                    fpsDetector.start(
                        player = p,
                        handler = handler,
                        onSuccess = { detectedFps, currentFormat ->
                            val updatedFormat = currentFormat.buildUpon().setFrameRate(detectedFps).build()
                            updateVideoInfoBadge(updatedFormat)
                        },
                        onVfrDetected = {
                            afrAppliedForCurrentItem = true
                        }
                    )
                }
            }
        }
    }

    private fun tryGetFpsFromDeepSources(): Float {
        val p = player ?: return -1f
        val manifest = p.currentManifest
        if (manifest is HlsManifest) {
            manifest.multivariantPlaylist.variants.forEach { variant ->
                if (variant.format.id == p.videoFormat?.id ||
                    (variant.format.width == p.videoFormat?.width && variant.format.height == p.videoFormat?.height)) {
                    if (variant.format.frameRate > 0) return variant.format.frameRate
                }
            }
        }
        if (manifest is DashManifest && manifest.periodCount > 0) {
            val period = manifest.getPeriod(0)
            period.adaptationSets.forEach { set ->
                if (set.type == C.TRACK_TYPE_VIDEO) {
                    set.representations.forEach { rep ->
                        if (rep.format.frameRate > 0) return rep.format.frameRate
                    }
                }
            }
        }
        val tracks = p.currentTracks
        tracks.groups.forEach { group ->
            if (group.type == C.TRACK_TYPE_VIDEO) {
                for (i in 0 until group.length) {
                    val f = group.getTrackFormat(i)
                    if (f.frameRate > 0) return f.frameRate
                }
            }
        }
        return -1f
    }

    private fun handleVideoSizeChanged(videoSize: VideoSize) {
        lastVideoSize = videoSize
        if (videoSize.height > 0) {
            val ratio = (videoSize.width * videoSize.pixelWidthHeightRatio) / videoSize.height
            naturalAspectRatio = ratio
            _videoAspectRatio.postValue(_aspectRatioMode.value?.ratio ?: ratio)
        }

        if (videoSize.width == 0 || videoSize.height == 0) return

        if (isSettingsLoadedFromDb) {
            calculateFrameSize(_inputType.value!!, videoSize)
            return
        }

        // Логика автоопределения (выключена в режиме "Не обрабатывать")
        if (_inputType.value == StereoInputType.NONE && repository.isStereoAutoDetectEnabled()) {
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
        // Пытаемся применить отложенную аудиодорожку
        var finalAudioIdx = audioIdx
        pendingAudioId?.let { id ->
            val foundIdx = audioOptions.indexOfFirst { it.format?.id == id }
            if (foundIdx != -1) {
                finalAudioIdx = foundIdx
                selectTrackByIndex(C.TRACK_TYPE_AUDIO, foundIdx)
                audioTrackResolved = true
            }
        }
        // Иначе ищем дорожку, выбранную в этом плейлисте, а затем (экспериментально) среди наиболее используемых.
        // Пока не нашли, пробуем при каждом обновлении: названия из метаданных файла приходят позже
        if (!audioTrackResolved && !audioChosenByUser) {
            findRememberedAudioTrackIndex()?.let { idx ->
                audioTrackResolved = true
                if (idx != finalAudioIdx) {
                    finalAudioIdx = idx
                    selectTrackByIndex(C.TRACK_TYPE_AUDIO, idx)
                }
            }
        }
        currentAudioIndex = finalAudioIdx
        _currentAudioTrack.postValue(audioOptions.getOrNull(currentAudioIndex))

        // 2. Субтитры
        val (subs, subIdx) = TrackLogic.extractSubtitleTracks(tracks, metadata)
        subtitleOptions = subs
        // Пытаемся применить отложенные субтитры
        var finalSubIdx = subIdx
        pendingSubtitleLabel?.let { label ->
            // Только что добавленные субтитры: последняя внешняя дорожка с этим названием
            val foundIdx = subtitleOptions.indexOfLast { it.format?.language == "ext" && it.format?.label == label }
            if (foundIdx != -1) {
                pendingSubtitleLabel = null
                pendingSubtitleId = null
                subtitleResolved = true
                finalSubIdx = foundIdx
                selectTrackByIndex(C.TRACK_TYPE_TEXT, foundIdx)
            }
        }
        pendingSubtitleId?.let { id ->
            // Специальная обработка для "Выкл"
            if (id == "disabled") {
                finalSubIdx = 0
                subtitleResolved = true
                selectTrackByIndex(C.TRACK_TYPE_TEXT, 0)
            } else {
                val foundIdx = subtitleOptions.indexOfFirst { it.format?.id == id }
                if (foundIdx != -1) {
                    finalSubIdx = foundIdx
                    subtitleResolved = true
                    selectTrackByIndex(C.TRACK_TYPE_TEXT, foundIdx)
                }
            }
        }
        // Автовключение субтитров по настройке: один раз на видео, если субтитры не сохранены для него.
        // Ждем появления дорожек субтитров: в HLS они могут прийти позже видео и звука
        if (!subtitleResolved && videoSettingsChecked && pendingSubtitleId == null && pendingSubtitleLabel == null &&
            subtitleOptions.any { !it.isOff }
        ) {
            subtitleResolved = true
            val mode = repository.getSubtitleAutoMode()
            if (mode != SettingsRepository.SUB_AUTO_DEFAULT) {
                val audioLang = audioOptions.getOrNull(finalAudioIdx)?.format?.language
                TrackLogic.autoSubtitleIndex(subtitleOptions, mode, preferredSubtitleLanguages(), audioLang)
                    ?.let { idx ->
                        if (idx != finalSubIdx) {
                            finalSubIdx = idx
                            selectTrackByIndex(C.TRACK_TYPE_TEXT, idx)
                        }
                    }
            }
        }
        currentSubtitleIndex = finalSubIdx
        _currentSubtitleTrack.postValue(subtitleOptions.getOrNull(currentSubtitleIndex))

        // Очищаем отложенные ID, так как мы их уже применили (или не нашли).
        // Пустой список дорожек приходит при перезагрузке источника - ждем настоящий
        if (!tracks.isEmpty) {
            pendingAudioId = null
            pendingSubtitleId = null
        }

        // 3. Видео (Quality)
        val videoOptions = TrackLogic.extractVideoTracks(tracks)
        _videoQualityOptions.postValue(videoOptions)
        // Пустой список приходит при перезагрузке источника - кнопки не прячем, чтобы не мигали
        if (!tracks.isEmpty) {
            _trackCounts.postValue(
                TrackCounts(
                    video = videoOptions.count { !it.isAuto },
                    audio = audioOptions.count { !it.isOff },
                    subtitles = subtitleOptions.count { !it.isOff }
                )
            )
        }

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

    fun setPlaybackSpeed(speed: Float) {
        speedFromDefault = false
        applyPlaybackSpeed(speed)
    }

    private fun applyPlaybackSpeed(speed: Float) {
        _playbackSpeed.value = speed
        player?.setPlaybackSpeed(speed)
    }

    /** Своя скорость с ползунка: запоминаем и сразу применяем. */
    fun setCustomPlaybackSpeed(speed: Float) {
        val normalized = PlaybackSpeed.normalize(speed)
        repository.setCustomPlaybackSpeed(normalized)
        setPlaybackSpeed(normalized)
    }

    fun getCustomPlaybackSpeed(): Float = repository.getCustomPlaybackSpeed()

    /** Быстрое переключение с пульта: на 1.00x - своя скорость, на любой другой - 1.00x. */
    fun toggleCustomSpeed() {
        setPlaybackSpeed(if ((_playbackSpeed.value ?: 1.0f) == 1.0f) repository.getCustomPlaybackSpeed() else 1.0f)
    }

    /** Скорость по умолчанию (долгое нажатие в меню скорости). */
    fun setDefaultPlaybackSpeed(speed: Float) = repository.setDefaultPlaybackSpeed(speed)

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

    /**
     * Загружает медиа из интента/настроек. Одна ссылка на .m3u/.m3u8 проверяется на плейлист M3U
     * (без повторного запроса для HLS), остальное загружается как раньше, сразу.
     */
    fun loadMedia(items: List<MediaItem>, startIndex: Int, forceM3uProbe: Boolean = false) {
        loadMediaJob?.cancel()
        if (items.size != 1 || (!forceM3uProbe && !playerManager.isM3uCandidate(items))) {
            loadPlaylist(items, startIndex)
            return
        }
        loadMediaJob = viewModelScope.launch {
            val expanded = playerManager.expandM3u(items, forceM3uProbe)
            if (expanded != null) {
                // Последний канал восстанавливаем только по точному URI
                val lastUri = repository.getLastPlayedChannelUri()
                val lastIndex = if (lastUri != null) expanded.indexOfFirst { it.uri.toString() == lastUri } else -1
                loadPlaylist(expanded, lastIndex.coerceAtLeast(0), isM3u = true)
            } else {
                loadPlaylist(items, startIndex)
            }
        }
    }

    fun loadPlaylist(items: List<MediaItem>, startIndex: Int, isM3u: Boolean = false) {
        val indexedItems = items.mapIndexed { index, item -> item.copy(absoluteIndex = index) }
        hlsFallbackTried.clear()
        _currentPlaylist.value = indexedItems
        _isM3uPlaylist.value = isM3u
        _isSessionLive.value = false
        stopZap()

        val groups = indexedItems.mapNotNull { it.group }.distinct()
        val startGroup = if (groups.isEmpty()) null else indexedItems.getOrNull(startIndex)?.group
        _playlistUiState.value = PlaylistUiState(
            items = filterByGroup(indexedItems, startGroup),
            groupName = startGroup,
            availableGroups = groups
        )

        resumePlayWhenReady = true // Новый плейлист всегда стартует
        val startItem = indexedItems.getOrNull(startIndex)
        var startPos = startItem?.startPositionMs ?: 0L
        pendingExternalPosition = null
        sessionAudioSignature = null // Новый плейлист - выбор дорожки начинается заново
        isFirstItemLoaded = false // Продолжение просмотра - для первого элемента нового плейлиста
        if (startItem != null && startPos > 0 &&
            repository.getExternalPositionMode() == SettingsRepository.EXTERNAL_POSITION_ASK
        ) {
            // Позицию из приложения не применяем сразу, а предлагаем в диалоге продолжения
            pendingExternalPosition = startItem.uuid to startPos
            startPos = 0L
        }
        playerManager.loadPlaylist(indexedItems, startIndex, startPos)
        viewModelScope.launch { repository.cleanupOldSettings() }
    }

    private fun filterByGroup(items: List<MediaItem>, group: String?): List<MediaItem> =
        if (group == null) items else items.filter { it.group == group }

    /** Показывает в плейлисте элементы группы (null = все). */
    fun selectGroup(groupName: String?) {
        val state = _playlistUiState.value ?: return
        val all = _currentPlaylist.value ?: return
        _playlistUiState.value = state.copy(items = filterByGroup(all, groupName), groupName = groupName)
    }

    /** Переключает группу в плейлисте (вместе с пунктом "Все"). */
    fun cycleGroup(direction: Int) {
        val state = _playlistUiState.value ?: return
        if (state.availableGroups.isEmpty()) return
        val options = listOf<String?>(null) + state.availableGroups
        val currentIndex = options.indexOf(state.groupName).coerceAtLeast(0)
        selectGroup(options[(currentIndex + direction + options.size) % options.size])
    }

    /** Возвращает в плейлисте группу текущего элемента (если группу сменили, но ничего не выбрали). */
    fun revertToPlayingGroup() {
        val state = _playlistUiState.value ?: return
        if (state.availableGroups.isEmpty()) return
        val playingItem = getPlayingItem()
        selectGroup(playingItem?.group)
    }

    /**
     * Эфирный режим: включен в настройках, в плейлисте больше одного элемента
     * и в нем уже играл live-поток (M3U с эфиром или любой плейлист с эфиром).
     */
    fun isOverallLiveMode(): Boolean {
        if (!repository.isLiveModeEnabled()) return false
        if ((_currentPlaylist.value?.size ?: 0) <= 1) return false
        return _isSessionLive.value == true
    }

    /** Элемент относительно текущего в пределах выбранной группы (по кругу). */
    fun getRelativePlaylistItem(offset: Int): MediaItem? {
        val items = _playlistUiState.value?.items ?: return null
        if (items.isEmpty()) return null
        val playingId = player?.currentMediaItem?.mediaId
        val currentIdx = items.indexOfFirst { it.uuid == playingId }
        if (currentIdx == -1) return null
        return items[(currentIdx + offset + items.size) % items.size]
    }

    /**
     * Переключение каналов Влево/Вправо: показываем выбранный канал и включаем его
     * после паузы в нажатиях (можно пролистать несколько каналов).
     */
    fun zapChannel(direction: Int) {
        val items = _playlistUiState.value?.items ?: return
        if (items.isEmpty()) return

        val fromId = _zapPreviewItem.value?.uuid ?: player?.currentMediaItem?.mediaId
        val currentIdx = items.indexOfFirst { it.uuid == fromId }.coerceAtLeast(0)
        val target = items[(currentIdx + direction + items.size) % items.size]
        _zapPreviewItem.value = target

        zapJob?.cancel()
        zapJob = viewModelScope.launch {
            kotlinx.coroutines.delay(1500)
            _zapPreviewItem.value = null
            playPlaylistItem(target.absoluteIndex)
        }
    }

    /** Переключает на выбранный канал сразу. @return false, если канал не выбирали. */
    fun confirmZap(): Boolean {
        val target = _zapPreviewItem.value ?: return false
        zapJob?.cancel()
        zapJob = null
        _zapPreviewItem.value = null
        playPlaylistItem(target.absoluteIndex)
        return true
    }

    fun stopZap() {
        zapJob?.cancel()
        zapJob = null
        if (_zapPreviewItem.value != null) _zapPreviewItem.value = null
    }

    fun getPlayingItem(): MediaItem? {
        val playingId = player?.currentMediaItem?.mediaId ?: return null
        return _currentPlaylist.value?.find { it.uuid == playingId }
    }

    // --- Settings Logic ---

    private fun loadGlobalDefaults() {
        _outputMode.value = repository.getDefaultOutputMode()

        val anaTypeOrd = repository.getGlobalInt("def_anaglyph_type", StereoRenderer.AnaglyphType.RC_DUBOIS.ordinal)
        val anaglyphType = StereoRenderer.AnaglyphType.entries.getOrNull(anaTypeOrd) ?: StereoRenderer.AnaglyphType.RC_DUBOIS
        _anaglyphType.value = anaglyphType

        _screenSeparation.value = repository.getGlobalFloat("global_screen_separation_pct", 0f)
        anaglyphDelegate.loadGlobalVrParams()

        if (AnaglyphLogic.isCustomType(anaglyphType)) anaglyphDelegate.loadCustomSettings(anaglyphType)
        updateAvailableSettings()
        anaglyphDelegate.updateAnaglyphMatrix(anaglyphType)
    }

    // Источник видео со стартового экрана: по нему обновляется история "Продолжить просмотр"
    private var historySource: HistorySource? = null

    fun setHistorySource(source: HistorySource?) {
        historySource = source
    }

    /**
     * Запись истории для источника: текущее видео и позиция. Серия в той же папке заменяет запись источника,
     * а не добавляет новую.
     */
    private fun updateWatchHistory() {
        val source = historySource ?: return
        val p = player ?: return
        val item = getPlayingItem() ?: return
        val isLive = p.isCurrentMediaItemLive
        val duration = p.duration.takeIf { it > 0 && it != C.TIME_UNSET && !isLive } ?: 0L
        repository.recordWatchHistory(
            HistoryEntry(
                kind = source.kind,
                source = source.source,
                sourceName = source.name,
                title = item.title ?: item.uri.lastPathSegment.orEmpty(),
                uri = item.uri.toString(),
                positionMs = if (isLive) 0L else p.currentPosition.coerceAtLeast(0L),
                durationMs = duration,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    fun saveCurrentSettings() {
        val uri = currentUri ?: return
        val p = player ?: return
        updateWatchHistory()

        val positionToSave = if (p.isCurrentMediaItemLive) 0L else p.currentPosition
        // Получаем ID текущих дорожек
        val audioId = _currentAudioTrack.value?.format?.id
        val subId = if (_currentSubtitleTrack.value?.isOff == true) "disabled"
        else _currentSubtitleTrack.value?.format?.id

        val settings = VideoSettings(
            uri, System.currentTimeMillis(),
            _inputType.value!!, _outputMode.value!!, _anaglyphType.value!!,
            _swapEyes.value!!, _depth.value!!,
            lastPosition = positionToSave,
            duration = p.duration.coerceAtLeast(0L),
            audioTrackId = audioId,
            subtitleTrackId = subId
        )
        viewModelScope.launch { repository.saveVideoSettings(settings) }

        // Выбранную вручную дорожку учитываем в статистике для других видео: один раз на видео
        // и итоговый выбор, а не каждую дорожку при переборе в OSD
        if (audioChoicePendingStat && repository.getAudioTrackMemoryMode() == SettingsRepository.AUDIO_MEMORY_ALL) {
            _currentAudioTrack.value?.let { option ->
                TrackLogic.audioTrackSignature(option)?.let { signature ->
                    repository.recordAudioTrackChoice(signature, TrackLogic.buildTrackLabel(option, getApplication<Application>()))
                }
            }
            audioChoicePendingStat = false
        }

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

    // Вызывается в главном потоке: значения выставляем синхронно,
    // чтобы updateAvailableSettings() ниже видел уже новые настройки.
    private fun applySettings(s: VideoSettings) {
        _inputType.value = s.inputType
        _outputMode.value = s.outputMode
        _anaglyphType.value = s.anaglyphType
        _swapEyes.value = s.swapEyes
        _depth.value = s.depth

        anaglyphDelegate.loadGlobalVrParams()

        lastVideoSize?.let { calculateFrameSize(s.inputType, it) }
        if (AnaglyphLogic.isCustomType(s.anaglyphType)) anaglyphDelegate.loadCustomSettings(s.anaglyphType)
        updateAvailableSettings()
        anaglyphDelegate.updateAnaglyphMatrix(s.anaglyphType)
    }

    // --- Menu Generation ---

    fun getPlaybackSpeedMenuItems(context: Context): List<MenuItem> {
        val currentSpeed = _playbackSpeed.value ?: 1.0f
        val defaultSpeed = repository.getDefaultPlaybackSpeed()
        val customSpeed = repository.getCustomPlaybackSpeed()
        val currentIsCustom = PlaybackSpeed.preset(currentSpeed) == null
        val defaultMark = context.getString(R.string.playback_speed_default_mark)
        val presets = PlaybackSpeed.entries.map { speed ->
            MenuItem(
                id = speed.value.toString(),
                title = speed.label,
                description = defaultMark.takeIf { PlaybackSpeed.preset(defaultSpeed) == speed },
                isSelected = PlaybackSpeed.preset(currentSpeed) == speed
            )
        }
        // Своя скорость: показываем текущую, если она не из списка, иначе сохраненную
        val customValue = if (currentIsCustom) currentSpeed else customSpeed
        val customDescription = listOfNotNull(
            PlaybackSpeed.label(customValue),
            defaultMark.takeIf { PlaybackSpeed.preset(defaultSpeed) == null }
        ).joinToString(" · ")
        return presets + MenuItem(
            id = SPEED_MENU_CUSTOM,
            title = context.getString(R.string.playback_speed_custom),
            description = customDescription,
            data = customValue,
            isSelected = currentIsCustom
        )
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

    fun setAspectRatioMode(mode: AspectRatioMode) {
        _aspectRatioMode.value = mode
        _videoAspectRatio.value = mode.ratio ?: naturalAspectRatio
    }

    private fun getAspectRatioLabel(context: Context, mode: AspectRatioMode): String =
        mode.label ?: context.getString(R.string.aspect_ratio_auto)

    fun getAspectRatioMenuItems(context: Context): List<MenuItem> {
        val current = _aspectRatioMode.value ?: AspectRatioMode.AUTO
        return AspectRatioMode.entries.map { mode ->
            MenuItem(
                id = mode.name,
                title = getAspectRatioLabel(context, mode),
                isSelected = mode == current
            )
        }
    }

    fun getMainMenuItems(context: Context): List<MenuItem> {
        val currentAudioName = currentAudioTrack.value?.let { TrackLogic.buildTrackLabel(it, context) } ?: ""
        val currentSubtitleName = currentSubtitleTrack.value?.let { TrackLogic.buildTrackLabel(it, context) } ?: ""
        val currentSpeed = PlaybackSpeed.label(_playbackSpeed.value ?: 1.0f)

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
                "aspect",
                context.getString(R.string.aspect_ratio),
                getAspectRatioLabel(context, _aspectRatioMode.value ?: AspectRatioMode.AUTO),
                R.drawable.ic_aspect_ratio
            ),
            MenuItem(
                "quick_settings",
                context.getString(R.string.menu_quick_settings_title),
                context.getString(R.string.menu_quick_settings_desc),
                R.drawable.ic_3d_settings_bar
            ),
            MenuItem(
                "global_settings",
                context.getString(R.string.menu_global_settings_title),
                context.getString(R.string.menu_global_settings_desc),
                R.drawable.ic_global_settings
            )
        )
    }

    /**
     * Метод для выбора элемента плейлиста из UI.
     * Гарантирует сброс ошибок и включение видео.
     */
    fun playPlaylistItem(index: Int) {
        player?.let { p ->
            val isVideoError = _videoDisabledError.value != null
            // Сбрасываем ошибку в UI немедленно
            _videoDisabledError.value = null

            // Включаем видео обратно (на случай, если оно было отключено из-за ошибки)
            val params = p.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
                .build()
            p.trackSelectionParameters = params

            if (p.currentMediaItemIndex == index) {
                // Если мы уже на этом треке и была ошибка перезапускаем его
                if (isVideoError) p.seekTo(index, C.TIME_UNSET) // C.TIME_UNSET для Live означает "край"
            } else {
                // Переключение на другой трек
                p.seekToDefaultPosition(index)
            }
            p.prepare() // На всякий случай
            p.play()
        }
    }

    /** Соседнее видео плейлиста (как у кнопок Пред/След плеера) для карточки в шторке свайпа. */
    fun getAdjacentPlaylistItem(next: Boolean): MediaItem? {
        val p = player ?: return null
        val index = when {
            next && p.hasNextMediaItem() -> p.nextMediaItemIndex
            !next && p.hasPreviousMediaItem() -> p.previousMediaItemIndex
            else -> return null
        }
        val mediaId = p.getMediaItemAt(index).mediaId
        return _currentPlaylist.value?.find { it.uuid == mediaId }
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
        val tracks = subtitleOptions.mapIndexed { index, option ->
            val name = TrackLogic.buildTrackLabel(option, context)
            MenuItem(index.toString(), name, isSelected = index == currentSubtitleIndex)
        }
        // Пункт загрузки субтитров из файла - в конце списка
        return tracks + MenuItem(SUBTITLE_MENU_LOAD_FILE, context.getString(R.string.subtitle_load_file))
    }

    /**
     * Добавляет внешние субтитры к текущему видео и включает их.
     * Плейлист перезагружается с той же позиции (дорожки субтитров задаются при подготовке источника).
     */
    fun addExternalSubtitle(uri: Uri, fileName: String?) {
        val p = player ?: return
        val playlist = _currentPlaylist.value ?: return
        val playingId = p.currentMediaItem?.mediaId ?: return
        val index = playlist.indexOfFirst { it.uuid == playingId }
        if (index == -1) return

        val name = fileName ?: uri.lastPathSegment ?: "Subtitles"
        val mimeType = MediaFormatHelper.getSubtitleMimeTypeByName(name)
        if (mimeType == null || mimeType == MimeTypes.TEXT_UNKNOWN) {
            _toastMessage.value = getString(R.string.subtitle_unsupported)
            return
        }

        val item = playlist[index]
        val updated = item.copy(subtitles = item.subtitles + SubtitleItem(uri, name, name, mimeType))
        val newPlaylist = playlist.toMutableList().apply { set(index, updated) }
        _currentPlaylist.value = newPlaylist
        _playlistUiState.value?.let { state ->
            _playlistUiState.value = state.copy(items = state.items.map { if (it.uuid == updated.uuid) updated else it })
        }

        // После перезагрузки оставляем текущую аудиодорожку и включаем новые субтитры
        pendingAudioId = _currentAudioTrack.value?.format?.id
        pendingSubtitleLabel = name
        // То же видео перезагружается: настройки и счетчики не сбрасываем
        isRecoveringFromError = true
        playerManager.reloadPlaylistItems(newPlaylist, p.currentMediaItemIndex, p.currentPosition)
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

    private fun findRememberedAudioTrackIndex(): Int? {
        val mode = repository.getAudioTrackMemoryMode()
        if (mode == SettingsRepository.AUDIO_MEMORY_OFF) return null
        val candidates = buildList {
            sessionAudioSignature?.let { add(it) }
            if (mode == SettingsRepository.AUDIO_MEMORY_ALL) {
                addAll(repository.getAudioTrackStats().map { it.signature })
            }
        }
        for (signature in candidates) {
            val idx = audioOptions.indexOfFirst { TrackLogic.audioTrackSignature(it) == signature }
            if (idx != -1) return idx
        }
        return null
    }

    private fun preferredSubtitleLanguages(): List<String> =
        when (val pref = repository.getPreferredSubLang()) {
            SettingsRepository.TRACK_DEFAULT -> emptyList()
            SettingsRepository.TRACK_DEVICE -> {
                val locales = LocaleListCompat.getAdjustedDefault()
                (0 until locales.size()).mapNotNull { locales.get(it)?.language }
            }
            else -> listOf(pref)
        }

    /** @param fromUser выбор из меню/OSD (запоминается для других видео) */
    fun selectTrackByIndex(trackType: Int, index: Int, fromUser: Boolean = false) {
        val options = if (trackType == C.TRACK_TYPE_AUDIO) audioOptions else subtitleOptions

        if (index in options.indices) {
            val option = options[index]

            if (trackType == C.TRACK_TYPE_AUDIO) {
                if (fromUser) {
                    audioChosenByUser = true
                    audioChoicePendingStat = true
                    audioTrackResolved = true
                    sessionAudioSignature = TrackLogic.audioTrackSignature(option)
                }
                currentAudioIndex = index
                _currentAudioTrack.value = option
            } else {
                if (fromUser) subtitleResolved = true
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
                val filters = filtersForGroup(currentGroup)
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
            SettingType.RENDER_API -> {
                val current = _renderApiMode.value ?: SettingsRepository.RENDER_API_AUTO
                val next = if (current == SettingsRepository.RENDER_API_AUTO) SettingsRepository.RENDER_API_GLES2
                else SettingsRepository.RENDER_API_AUTO
                repository.setRenderApiMode(next)
                _renderApiMode.value = next
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
                    selectTrackByIndex(C.TRACK_TYPE_AUDIO, nextIndex, fromUser = true)
                }
            }
            SettingType.SUBTITLES -> {
                val options = subtitleOptions
                if (options.isNotEmpty()) {
                    val nextIndex = (currentSubtitleIndex + direction + options.size) % options.size
                    selectTrackByIndex(C.TRACK_TYPE_TEXT, nextIndex, fromUser = true)
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
                        StereoInputType.TOP_BOTTOM -> R.string.stereo_mode_ou
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
                val filters = filtersForGroup(currentGroup)
                val list = filters.map { AnaglyphLogic.filterLabel(context, it) }
                val index = filters.indexOf(_anaglyphType.value!!)
                Pair(list, index)
            }
            SettingType.RENDER_API -> {
                val list = listOf(
                    context.getString(R.string.api_gles3), // 0
                    context.getString(R.string.api_gles2)  // 1
                )
                Pair(list, _renderApiMode.value ?: SettingsRepository.RENDER_API_AUTO)
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
            // Влияет на все 3D режимы: в ES 3.0 текстурные координаты считаются в highp
            // (mediump на части GPU дает ступенчатость на 4K), в анаглифе - еще и смешивание цветов
            if (deviceSupportsEs3) {
                list.add(SettingType.RENDER_API)
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
                // Half SBS растягивает каждый глаз на весь кадр, у Full SBS глаз - половина кадра
                finalFrameWidth = if (StereoFormatLogic.isHalfFrame(inputType, width, height)) width else width / 2
                finalFrameHeight = height
            }
            StereoInputType.TOP_BOTTOM -> {
                finalFrameWidth = width
                finalFrameHeight = if (StereoFormatLogic.isHalfFrame(inputType, width, height)) height else height / 2
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
        return filtersForGroup(group).first()
    }

    // Фильтры группы очков; "Калибровка" - только если очки откалиброваны в визарде
    private fun filtersForGroup(group: GlassesGroup) =
        AnaglyphLogic.getFiltersForGroup(group, includeCalibrated = repository.getAnaglyphCalibration(group) != null)

    // Версия калибровки (визарды VR и анаглифа), с которой загружены параметры
    private var calibrationVersion = repository.getCalibrationVersion()

    /** Перечитывает параметры VR и матрицы анаглифа, если калибровку сохранили или сбросили. */
    fun reloadCalibrationIfChanged() {
        val version = repository.getCalibrationVersion()
        if (version == calibrationVersion) return
        calibrationVersion = version
        _screenSeparation.value = repository.getGlobalFloat("global_screen_separation_pct", 0f)
        anaglyphDelegate.loadGlobalVrParams()
        _anaglyphType.value?.let { anaglyphDelegate.updateAnaglyphMatrix(it) }
    }

    fun setDeviceSupportsEs3(supported: Boolean) {
        deviceSupportsEs3 = supported
        updateAvailableSettings()
    }

    // --- Hot Restart Logic ---

    fun checkSettingsAndRestart() {
        val currentHash = repository.getHardSettingsSignature()

        if (currentHash != lastSettingsHash) {
            restartPlayer()
            lastSettingsHash = currentHash
        } else {
            playerManager.applyLiveSettings()
        }
    }

    fun restartPlayer() {
        // Сохраняем текущее состояние перед пересозданием
        playerManager.releasePlayer(isFinalRelease = false, saveState = true)
        playerManager.initializePlayer()
    }

    fun clearToast() {
        _toastMessage.value = null
    }

    override fun onCleared() {
        super.onCleared()
        handler.removeCallbacks(progressUpdater)
        playerManager.releasePlayer(isFinalRelease = true, saveState = false)
    }
}