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

    // LiveData для UI
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

    // LiveData для настроек рендерера
    private val _inputType = MutableLiveData(StereoInputType.NONE)
    val inputType: LiveData<StereoInputType> = _inputType

    private val _outputMode = MutableLiveData(StereoOutputMode.ANAGLYPH)
    val outputMode: LiveData<StereoOutputMode> = _outputMode

    private val _anaglyphType = MutableLiveData(StereoRenderer.AnaglyphType.DUBOIS)
    val anaglyphType: LiveData<StereoRenderer.AnaglyphType> = _anaglyphType

    private val _swapEyes = MutableLiveData(false)
    val swapEyes: LiveData<Boolean> = _swapEyes

    private val _isAnamorphic = MutableLiveData(false)
    val isAnamorphic: LiveData<Boolean> = _isAnamorphic

    private val handler = Handler(Looper.getMainLooper())
    val player: ExoPlayer = ExoPlayer.Builder(application).build()

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

            override fun onMediaItemTransition(mediaItem: Media3MediaItem?, reason: Int) {
                val title = mediaItem?.mediaMetadata?.title?.toString()
                _videoTitle.value = title ?: mediaItem?.localConfiguration?.uri?.lastPathSegment
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                val width = videoSize.width
                val height = videoSize.height
                if (width == 0 || height == 0) return

                val videoAspectRatio = width.toFloat() / height.toFloat()
                val pixelAspectRatio = videoSize.pixelWidthHeightRatio

                var detectedInputType = StereoInputType.NONE
                var detectedAnamorphic = false

                // Соотношение сторон одного кадра с учетом неквадратных пикселей
                val singleFrameAR = pixelAspectRatio * videoAspectRatio

                // Эвристика для анаморфных пар
                if (singleFrameAR > 2.2) { // Очень широкое -> анаморфная SBS (например, 2.35:1 пожатый в 16:9)
                    detectedInputType = StereoInputType.SIDE_BY_SIDE
                    detectedAnamorphic = true
                } else if (singleFrameAR < 1.0 && singleFrameAR > 0.6) { // Очень высокое -> анаморфная TB
                    detectedInputType = StereoInputType.TOP_BOTTOM
                    detectedAnamorphic = true
                } else {
                    // Эвристика для полных пар
                    if (videoAspectRatio > 2.2) { // Шире, чем 21:9 -> полная SBS
                        detectedInputType = StereoInputType.SIDE_BY_SIDE
                        detectedAnamorphic = false
                    } else if (videoAspectRatio < 1.0 && videoAspectRatio > 0.6) { // Выше, чем 16:9 -> полная TB
                        detectedInputType = StereoInputType.TOP_BOTTOM
                        detectedAnamorphic = false
                    }
                }

                _inputType.value = detectedInputType
                _isAnamorphic.value = detectedAnamorphic
            }
        })
    }

    fun loadMedia(mediaItem: MediaItem) {
        val exoMediaItem = Media3MediaItem.Builder()
            .setUri(mediaItem.uri)
            .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(mediaItem.title).build())
            .build()
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