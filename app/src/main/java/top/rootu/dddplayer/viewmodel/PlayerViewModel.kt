package top.rootu.dddplayer.viewmodel

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import top.rootu.dddplayer.model.MediaItem
import top.rootu.dddplayer.model.StereoInputType
import top.rootu.dddplayer.model.StereoOutputMode
import top.rootu.dddplayer.renderer.StereoRenderer
import androidx.media3.common.MediaItem as Media3MediaItem

class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    val player: ExoPlayer = ExoPlayer.Builder(application).build()

    private val _isPlaying = MutableLiveData<Boolean>()
    val isPlaying: LiveData<Boolean> = _isPlaying
    private val _currentPosition = MutableLiveData<Long>()
    val currentPosition: LiveData<Long> = _currentPosition
    private val _duration = MutableLiveData<Long>()
    val duration: LiveData<Long> = _duration
    private val _videoSize = MutableLiveData<VideoSize>()
    val videoSize: LiveData<VideoSize> = _videoSize

    private val _inputType = MutableLiveData<StereoInputType>()
    val inputType: LiveData<StereoInputType> = _inputType
    private val _outputMode = MutableLiveData<StereoOutputMode>()
    val outputMode: LiveData<StereoOutputMode> = _outputMode
    private val _anaglyphType = MutableLiveData<StereoRenderer.AnaglyphType>()
    val anaglyphType: LiveData<StereoRenderer.AnaglyphType> = _anaglyphType
    private val _swapEyes = MutableLiveData<Boolean>()
    val swapEyes: LiveData<Boolean> = _swapEyes

    private val handler = Handler(Looper.getMainLooper())
    private val progressUpdater = object : Runnable {
        override fun run() {
            _currentPosition.value = player.currentPosition
            handler.postDelayed(this, 500)
        }
    }

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
                if (isPlaying) handler.post(progressUpdater) else handler.removeCallbacks(progressUpdater)
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) _duration.value = player.duration
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                _videoSize.value = videoSize
            }
        })

        _inputType.value = StereoInputType.NONE
        _outputMode.value = StereoOutputMode.ANAGLYPH
        _anaglyphType.value = StereoRenderer.AnaglyphType.DUBOIS
        _swapEyes.value = false
    }

    fun loadMedia(mediaItem: MediaItem) {
        val exoMediaItem = Media3MediaItem.Builder().setUri(mediaItem.uri).build()
        player.setMediaItem(exoMediaItem)
        player.prepare()
        player.playWhenReady = true
    }

    fun togglePlayPause() { if (player.isPlaying) player.pause() else player.play() }
    fun seekForward() { player.seekTo(player.currentPosition + 10000) }
    fun seekBack() { player.seekTo(player.currentPosition - 10000) }
    fun setInputType(inputType: StereoInputType) { _inputType.value = inputType }
    fun setOutputMode(outputMode: StereoOutputMode) { _outputMode.value = outputMode }
    fun setAnaglyphType(anaglyphType: StereoRenderer.AnaglyphType) { _anaglyphType.value = anaglyphType }
    fun toggleSwapEyes() { _swapEyes.value = !(_swapEyes.value ?: false) }

    override fun onCleared() {
        super.onCleared()
        handler.removeCallbacks(progressUpdater)
        player.release()
    }
}