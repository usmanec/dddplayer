package top.rootu.dddplayer.player

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import java.util.concurrent.ConcurrentHashMap

// Вывод DTS-HD MA и TrueHD (MAT) через несущую IEC 61937 192 кГц/7.1, как у Kodi.
// Fire OS принимает сырой ENCODING_DTS_HD, но передает только ядро DTS, а сырой TrueHD не передает вовсе.
// Упаковщики и IecCarrierSink перенесены из edde746/plezy (GPL-3.0).
@OptIn(UnstableApi::class)
internal object IecCarrierOutput {
    private const val TAG = "IecCarrier"
    private const val CHANNEL_MASK = AudioFormat.CHANNEL_OUT_7POINT1_SURROUND

    /** Оборачивает обычный Sink: DTS-HD и TrueHD идут через несущую, остальное как раньше. */
    fun wrap(context: Context, defaultSink: AudioSink): IecCarrierSink =
        IecCarrierSink(
            defaultSink = defaultSink,
            carrierSink = buildCarrierSink(context),
            carrierRouteAvailable = { routeAvailable(context, it) },
            directOutputBlocked = { false },
            log = { level, _, message -> if (level == "warn") Log.w(TAG, message) else Log.i(TAG, message) }
        )

    /**
     * HDMI принимает IEC 61937 192 кГц/7.1 напрямую, а для DTS-HD приемник еще и объявляет DTS.
     * Не сделано: на API 24–28 (проверка через AudioDeviceInfo) несущей нет, там остается прежний путь.
     */
    // Проверка маршрута - несколько binder-вызовов, а ExoPlayer спрашивает о каждом формате много раз
    private const val ROUTE_CACHE_MS = 2_000L
    private val routeCache = ConcurrentHashMap<Boolean, Pair<Long, Boolean>>()

    /** Есть ли маршрут несущей для DTS-HD ([dts]) или TrueHD: для списка форматов в настройках. */
    fun isRouteAvailable(context: Context, dts: Boolean): Boolean = routeAvailable(
        context, Format.Builder().setSampleMimeType(if (dts) MimeTypes.AUDIO_DTS_HD else MimeTypes.AUDIO_TRUEHD).build()
    )

    private fun routeAvailable(context: Context, format: Format): Boolean {
        val isDts = format.sampleMimeType == MimeTypes.AUDIO_DTS_HD || format.sampleMimeType == MimeTypes.AUDIO_DTS
        val now = SystemClock.elapsedRealtime()
        routeCache[isDts]?.let { (time, value) -> if (now - time < ROUTE_CACHE_MS) return value }
        return probeRoute(context, isDts).also { routeCache[isDts] = now to it }
    }

    private fun probeRoute(context: Context, isDts: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return try {
            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_IEC61937)
                .setChannelMask(CHANNEL_MASK)
                .setSampleRate(IecCarrier.SAMPLE_RATE)
                .build()
            val attributes = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()
            @Suppress("DEPRECATION")
            val direct = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                AudioManager.getDirectPlaybackSupport(audioFormat, attributes) and
                    AudioManager.DIRECT_PLAYBACK_BITSTREAM_SUPPORTED != 0
            } else {
                AudioTrack.isDirectPlaybackSupported(audioFormat, attributes)
            }
            if (!direct || AudioTrack.getMinBufferSize(IecCarrier.SAMPLE_RATE, CHANNEL_MASK, AudioFormat.ENCODING_IEC61937) <= 0) {
                return false
            }
            if (!isDts) return true
            val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .filter { it.type == AudioDeviceInfo.TYPE_HDMI || it.type == AudioDeviceInfo.TYPE_HDMI_ARC }
                .any { device -> device.encodings.any { it == AudioFormat.ENCODING_DTS || it == AudioFormat.ENCODING_DTS_HD } }
        } catch (e: Exception) {
            Log.w(TAG, "IEC 61937 route probe failed", e)
            false
        }
    }

    /**
     * Отдельный Sink без аудиопроцессоров: несущая только похожа на PCM, любое изменение сэмплов
     * (даунмикс, Sonic, пропуск тишины) превращает ее в шум. AudioTrack создается как ENCODING_IEC61937.
     */
    private fun buildCarrierSink(context: Context): AudioSink {
        val provider = AudioTrackAudioOutputProvider.Builder(context)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    setAudioTrackBuilderModifier { builder, config ->
                        builder.setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_IEC61937)
                                .setChannelMask(CHANNEL_MASK)
                                .setSampleRate(config.sampleRate)
                                .build()
                        )
                        Log.i(TAG, "Carrier AudioTrack: IEC61937 ${config.sampleRate} Hz, buffer=${config.bufferSize}")
                    }
                }
            }
            .build()
        return DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(false)
            .setAudioProcessorChain(EmptyAudioProcessorChain())
            .setAudioOutputProvider(provider)
            .build()
    }

    private class EmptyAudioProcessorChain : DefaultAudioSink.AudioProcessorChain {
        override fun getAudioProcessors(): Array<AudioProcessor> = emptyArray()
        override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters = playbackParameters
        override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean = false
        override fun getMediaDuration(playoutDuration: Long): Long = playoutDuration
        override fun getSkippedOutputFrameCount(): Long = 0L
    }
}
