package top.rootu.dddplayer.player

import android.content.Context
import android.os.Handler
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import top.rootu.dddplayer.model.MediaItem
import top.rootu.dddplayer.viewmodel.TrackOption
import top.rootu.dddplayer.viewmodel.VideoQualityOption
import java.util.ArrayList
import androidx.media3.common.MediaItem as Media3MediaItem

@UnstableApi
class PlayerManager(context: Context, private val listener: Player.Listener) {

    private val httpDataSourceFactory = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setUserAgent("DDDPlayer/1.0")

    // 1. Определяем атрибуты аудио ЗАРАНЕЕ.
    // CONTENT_TYPE_MOVIE - ключевой флаг для Android TV, чтобы разрешить Passthrough (AC3/DTS).
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
        .build()

    // Настраиваем RenderersFactory
    private val renderersFactory = object : DefaultRenderersFactory(context) {
        override fun buildAudioRenderers(
            context: Context,
            extensionRendererMode: Int,
            mediaCodecSelector: MediaCodecSelector,
            enableDecoderFallback: Boolean,
            audioSink: AudioSink, // Игнорируем дефолтный, создаем свой
            eventHandler: Handler,
            eventListener: AudioRendererEventListener,
            out: ArrayList<Renderer>
        ) {
            // 2. Создаем AudioSink.
            // Мы передаем 'context' в конструктор Builder'а.
            // Благодаря этому Sink сам внутри вызовет AudioCapabilities.getCapabilities(...)
            // с учетом текущего подключения (HDMI/Optical) и наших AudioAttributes.
            val customSink = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(false) // Важно: Passthrough часто не работает с Float выходом
                .setEnableAudioTrackPlaybackParams(true)
                .build()

            // 3. Передаем наш настроенный Sink
            super.buildAudioRenderers(
                context,
                extensionRendererMode,
                mediaCodecSelector,
                enableDecoderFallback,
                customSink,
                eventHandler,
                eventListener,
                out
            )
        }
    }

    val exoPlayer: ExoPlayer = ExoPlayer.Builder(context, renderersFactory)
        .setMediaSourceFactory(DefaultMediaSourceFactory(httpDataSourceFactory))
        .setAudioAttributes(audioAttributes, true) // true = управлять аудиофокусом
        .setSeekBackIncrementMs(15000)
        .setSeekForwardIncrementMs(15000)
        .build()

    init {
        exoPlayer.addListener(listener)
    }

    fun loadPlaylist(items: List<MediaItem>, startIndex: Int) {
        if (items.isNotEmpty()) {
            httpDataSourceFactory.setDefaultRequestProperties(items[0].headers)
        }

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

        exoPlayer.setMediaItems(exoItems, startIndex, items[startIndex].startPositionMs)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    fun togglePlayPause() {
        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
    }

    fun seekForward() {
        val target = (exoPlayer.currentPosition + exoPlayer.seekForwardIncrement).coerceAtMost(exoPlayer.duration)
        exoPlayer.seekTo(target)
    }

    fun seekBack() {
        val target = (exoPlayer.currentPosition - exoPlayer.seekBackIncrement).coerceAtLeast(0)
        exoPlayer.seekTo(target)
    }

    fun selectTrack(option: TrackOption, trackType: Int) {
        val builder = exoPlayer.trackSelectionParameters.buildUpon()

        if (option.isOff) {
            builder.setTrackTypeDisabled(trackType, true)
        } else {
            builder.setTrackTypeDisabled(trackType, false)
            option.group?.let {
                builder.setOverrideForType(TrackSelectionOverride(it.mediaTrackGroup, option.trackIndex))
            }
        }

        exoPlayer.trackSelectionParameters = builder.build()
    }

    fun selectVideoQuality(option: VideoQualityOption) {
        val builder = exoPlayer.trackSelectionParameters.buildUpon()

        if (option.isAuto) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
                .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        } else {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
            option.group?.let {
                builder.setOverrideForType(TrackSelectionOverride(it.mediaTrackGroup, option.trackIndex))
            }
        }

        exoPlayer.trackSelectionParameters = builder.build()
    }

    fun release() {
        exoPlayer.removeListener(listener)
        exoPlayer.release()
    }
}