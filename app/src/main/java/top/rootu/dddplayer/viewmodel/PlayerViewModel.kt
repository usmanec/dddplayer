package top.rootu.dddplayer.viewmodel

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import top.rootu.dddplayer.model.MediaItem
import top.rootu.dddplayer.model.StereoType
import top.rootu.dddplayer.renderer.AnaglyphShader
import androidx.media3.common.MediaItem as Media3MediaItem

class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    // LiveData для UI
    private val _isPlaying = MutableLiveData<Boolean>()
    val isPlaying: LiveData<Boolean> = _isPlaying

    private val _currentPosition = MutableLiveData<Long>()
    val currentPosition: LiveData<Long> = _currentPosition

    private val _duration = MutableLiveData<Long>()
    val duration: LiveData<Long> = _duration

    // LiveData для кастомных настроек
    private val _stereoMode = MutableLiveData<AnaglyphShader.StereoMode>()
    val stereoMode: LiveData<AnaglyphShader.StereoMode> = _stereoMode

    private val _anaglyphType = MutableLiveData<AnaglyphShader.AnaglyphType>()
    val anaglyphType: LiveData<AnaglyphShader.AnaglyphType> = _anaglyphType

    private val handler = Handler(Looper.getMainLooper())

    // ИСПРАВЛЕНИЕ 1: Инициализируем плеер ЗДЕСЬ, до того как он понадобится.
    val player: ExoPlayer = ExoPlayer.Builder(application).build()

    private val progressUpdater = object : Runnable {
        override fun run() {
            // Теперь 'player' гарантированно существует
            _currentPosition.value = player.currentPosition
            handler.postDelayed(this, 500)
        }
    }

    init {
        // ИСПРАВЛЕНИЕ 2: Убираем инициализацию отсюда, оставляем только настройку.
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
                if (playbackState == Player.STATE_READY) {
                    _duration.value = player.duration
                }
            }
        })

        _stereoMode.value = AnaglyphShader.StereoMode.NONE
        _anaglyphType.value = AnaglyphShader.AnaglyphType.DUBOIS
    }

    fun loadMedia(mediaItem: MediaItem) {
        val exoMediaItem = Media3MediaItem.Builder().setUri(mediaItem.uri).build()
        player.setMediaItem(exoMediaItem)
        player.prepare()
        player.playWhenReady = true
    }

    fun togglePlayPause() {
        if (player.isPlaying) {
            player.pause()
        } else {
            player.play()
        }
    }

    fun seekForward() {
        player.seekTo(player.currentPosition + 10000) // 10 секунд вперед
    }

    fun seekBack() {
        player.seekTo(player.currentPosition - 10000) // 10 секунд назад
    }

    fun setStereoType(stereoType: StereoType) {
        _stereoMode.value = when (stereoType) {
            StereoType.SIDE_BY_SIDE -> AnaglyphShader.StereoMode.SIDE_BY_SIDE
            StereoType.TOP_BOTTOM -> AnaglyphShader.StereoMode.TOP_BOTTOM
            StereoType.NONE -> AnaglyphShader.StereoMode.NONE
        }
    }

    fun setAnaglyphType(anaglyphType: AnaglyphShader.AnaglyphType) {
        _anaglyphType.value = anaglyphType
    }

    override fun onCleared() {
        super.onCleared()
        handler.removeCallbacks(progressUpdater)
        player.release()
    }
}
