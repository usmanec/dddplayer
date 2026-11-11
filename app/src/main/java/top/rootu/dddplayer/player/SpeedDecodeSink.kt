package top.rootu.dddplayer.player

import android.util.Log
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink

/**
 * Скорость не 1x и звук идет битстримом (passthrough AC3/E-AC3/DTS): DefaultAudioSink меняет темп только
 * у PCM (shouldApplyAudioProcessorPlaybackParameters), битстрим играет как есть, а за его часами и видео,
 * то есть 1.5x/2x молча не работают. Пока скорость не 1x, Sink отказывается от битстрима и сообщает
 * о смене возможностей - тот же путь, что у Media3 при смене HDMI (и у IecCarrierSink): плеер заново
 * включает рендереры на текущей позиции, звук декодируется. На 1x - обратно в битстрим тем же путем.
 *
 * Решает скорость, выбранная пользователем ([requestedSpeed]), а не параметры часов: в эфире
 * LivePlaybackSpeedControl сам двигает скорость около 1x (до 1.05x), и каждый переход через 1.0 стоил бы
 * перезапуска рендереров. Она же известна до выбора дорожек: видео, начатое не на 1x, сразу декодируется.
 * [transcoder] - TranscodingAudioSink внутри: при возврате на 1x он сам перенастраивается (звук снова
 * перекодируется), и второй сигнал был бы лишним перезапуском рендереров.
 * [iec] - IecCarrierSink внутри: он сам сообщает о смене скорости при активной несущей и при возврате на 1x.
 */
internal class SpeedDecodeSink(
    sink: AudioSink,
    private val requestedSpeed: () -> Float,
    private val iec: IecCarrierSink? = null,
    private val transcoder: TranscodingAudioSink? = null
) : ForwardingAudioSink(sink) {

    private var listener: AudioSink.Listener? = null
    private var input: Format? = null // формат, с которым Sink настроен сейчас
    private var sped = false // скорость не 1x (на последнем setPlaybackParameters)
    private var refused = false // при скорости не 1x отказали битстриму, который иначе ушел бы как есть

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
        super.setListener(listener)
    }

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        input = inputFormat
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun reset() {
        input = null
        super.reset()
    }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        super.setPlaybackParameters(playbackParameters)
        val now = requestedSpeed() != 1f
        if (now == sped) return
        sped = now
        // Сигнал только когда звук на самом деле переедет: уже декодированный PCM ускоряется и так,
        // а лишнее переключение - пауза на перезапуск рендереров
        val move = if (sped) input?.let(::isBitstream) == true else refused
        if (!sped) refused = false // отказы до этого (выбор дорожек на скорости не 1x) нужны для возврата
        val iecSignals = iec != null && (!sped || iec.isCarrierActive)
        val transcoderSignals = transcoder?.signalsSpeedChange == true
        if (move && !iecSignals && !transcoderSignals) {
            Log.i(TAG, "Speed ${requestedSpeed()}x: audio moves ${if (sped) "to the decoder" else "back to passthrough"}")
            listener?.onAudioCapabilitiesChanged()
        }
    }

    override fun getFormatSupport(format: Format): Int {
        val support = super.getFormatSupport(format)
        if (requestedSpeed() == 1f || !isBitstream(format) || support == AudioSink.SINK_FORMAT_UNSUPPORTED) return support
        refused = true
        return AudioSink.SINK_FORMAT_UNSUPPORTED
    }

    @Deprecated("AudioSink.supportsFormat", ReplaceWith("getFormatSupport(format)"))
    override fun supportsFormat(format: Format): Boolean = getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED

    private companion object {
        const val TAG = "SpeedDecodeSink"

        fun isBitstream(format: Format): Boolean = format.sampleMimeType.let { it != null && it != MimeTypes.AUDIO_RAW }
    }
}
