package top.rootu.dddplayer.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import top.rootu.dddplayer.logic.AudioMixerLogic
import java.nio.ByteBuffer

/**
 * Сводит звук в стерео, пока скорость, выбранная пользователем ([requestedSpeed]), не 1x.
 *
 * При ускорении битстрим (проброс, перекодирование в AC3) уступает место PCM (SpeedDecodeSink), а многоканальный PCM
 * проходит не по всякому выходу (оптика, ARC). Выбор делается при настройке процессора: смена скорости
 * перенастраивает Sink там, где звук меняет вид (битстрим -> PCM, кодировщик -> PCM), поэтому раскладка
 * каналов внутри одного воспроизведения не меняется. На 1x процессор не активен и звук не трогает.
 * Только PCM 16 бит и float (как у ChannelMixingAudioProcessor), остальное идет как есть.
 */
internal class SpeedStereoMixer(
    mixParams: AudioMixerLogic.MixParams,
    private val requestedSpeed: () -> Float
) : AudioProcessor {

    private val stereo = ChannelMixingAudioProcessor().apply {
        AudioMixerLogic.createMatrices(mixParams, AudioMixerLogic.Layouts.STEREO).forEach { putChannelMixingMatrix(it) }
    }
    private var active: AudioProcessor? = null

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        val supported = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        active = if (supported && inputAudioFormat.channelCount > 2 && requestedSpeed() != 1f) stereo else null
        return active?.configure(inputAudioFormat) ?: AudioFormat.NOT_SET
    }

    override fun isActive(): Boolean = active?.isActive == true

    override fun queueInput(inputBuffer: ByteBuffer) {
        active?.queueInput(inputBuffer)
    }

    override fun queueEndOfStream() {
        active?.queueEndOfStream()
    }

    override fun getOutput(): ByteBuffer = active?.output ?: AudioProcessor.EMPTY_BUFFER

    override fun isEnded(): Boolean = active?.isEnded ?: true

    override fun flush() {
        active?.flush()
    }

    override fun reset() {
        stereo.reset()
        active = null
    }
}
