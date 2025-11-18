package top.rootu.dddplayer.viewmodel

import android.app.Application
import android.media.audiofx.LoudnessEnhancer
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.media3.common.MediaItem as Media3MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import top.rootu.dddplayer.model.MediaItem
import top.rootu.dddplayer.model.StereoInputType
import top.rootu.dddplayer.model.StereoOutputMode
import top.rootu.dddplayer.renderer.StereoRenderer

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

    // --- LiveData для настроек рендерера ---
    private val _inputType = MutableLiveData(StereoInputType.NONE)
    val inputType: LiveData<StereoInputType> = _inputType

    private val _outputMode = MutableLiveData(StereoOutputMode.ANAGLYPH)
    val outputMode: LiveData<StereoOutputMode> = _outputMode

    private val _anaglyphType = MutableLiveData(StereoRenderer.AnaglyphType.DUBOIS)
    val anaglyphType: LiveData<StereoRenderer.AnaglyphType> = _anaglyphType

    private val _swapEyes = MutableLiveData(false)
    val swapEyes: LiveData<Boolean> = _swapEyes

    private val _singleFrameSize = MutableLiveData<Pair<Float, Float>>()
    val singleFrameSize: LiveData<Pair<Float, Float>> = _singleFrameSize

    private val handler = Handler(Looper.getMainLooper())
    val player: ExoPlayer = ExoPlayer.Builder(application).build()
    private var lastVideoSize: VideoSize? = null

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

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                lastVideoSize = videoSize // Сохраняем последний известный размер

                val width = videoSize.width.toFloat()
                val height = videoSize.height.toFloat()
                if (width == 0f || height == 0f) return

                val videoAspectRatio = width / height

                var singleFrameWidth = width
                var singleFrameHeight = height

                val detectedInputType = when {
                    videoAspectRatio > 3.0 -> StereoInputType.SIDE_BY_SIDE
                    videoAspectRatio > 0.8 && videoAspectRatio < 1.2 && height > width * 1.1 -> StereoInputType.TOP_BOTTOM
                    else -> StereoInputType.NONE
                }

                _inputType.value = detectedInputType
                calculateFrameSize(detectedInputType, videoSize)
            }
        })
    }

    private fun calculateFrameSize(inputType: StereoInputType, videoSize: VideoSize) {
        val width = videoSize.width.toFloat()
        val height = videoSize.height.toFloat()

        var singleFrameWidth = width
        var singleFrameHeight = height

        when (inputType) {
            StereoInputType.SIDE_BY_SIDE -> {
                singleFrameWidth = width / 2
                singleFrameHeight = height
            }
            StereoInputType.TOP_BOTTOM -> {
                singleFrameWidth = width
                singleFrameHeight = height / 2
            }
            else -> { // NONE и другие
                singleFrameWidth = width
                singleFrameHeight = height
            }
        }
        _singleFrameSize.value = Pair(singleFrameWidth, singleFrameHeight)
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
    fun setInputType(inputType: StereoInputType) {
        _inputType.value = inputType
        lastVideoSize?.let {
            calculateFrameSize(inputType, it)
        }
    }
    fun setOutputMode(outputMode: StereoOutputMode) { _outputMode.value = outputMode }
    fun setAnaglyphType(anaglyphType: StereoRenderer.AnaglyphType) { _anaglyphType.value = anaglyphType }
    fun toggleSwapEyes() { _swapEyes.value = !(_swapEyes.value ?: false) }

    override fun onCleared() {
        super.onCleared()
        handler.removeCallbacks(progressUpdater)
        player.release()
    }
}