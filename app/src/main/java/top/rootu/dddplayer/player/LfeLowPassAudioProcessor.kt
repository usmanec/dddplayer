package top.rootu.dddplayer.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import top.rootu.dddplayer.logic.AudioMixerLogic
import top.rootu.dddplayer.logic.LowPassFilter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Созданный микшером LFE (апмикс с "Создавать LFE") пропускается через ФНЧ 120 Гц: в сабвуфер только бас.
 * Микшер видит вход, а этот процессор - уже его выход, поэтому число входных каналов запоминает
 * [Probe], стоящий перед микшером. Настоящий LFE источника не трогаем.
 */
@OptIn(UnstableApi::class)
internal class LfeLowPassAudioProcessor(
    private val probe: Probe,
    private val channelsSetting: Int,
    private val maxChannels: Int
) : BaseAudioProcessor() {

    /** Ничего не меняет, только запоминает число каналов на входе микшера. */
    class Probe : BaseAudioProcessor() {
        @Volatile
        var inputChannels = 0
            private set

        override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
            inputChannels = inputAudioFormat.channelCount
            return AudioFormat.NOT_SET
        }

        override fun queueInput(inputBuffer: ByteBuffer) {
            // Неактивный процессор данные не получает
        }
    }

    private var lfeIndex = -1
    private var channels = 0
    private var isFloat = false
    private var filter: LowPassFilter? = null

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        val input = AudioMixerLogic.inputLayoutFor(probe.inputChannels) ?: return AudioFormat.NOT_SET
        val output = AudioMixerLogic.outputLayoutFor(input, channelsSetting, maxChannels, upmix = true)
        val index = AudioMixerLogic.generatedLfeIndex(input, output, upmix = true, generateLfe = true)
        val encoding = inputAudioFormat.encoding
        if (index < 0 || output.channelCount != inputAudioFormat.channelCount ||
            (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT)
        ) {
            return AudioFormat.NOT_SET
        }
        lfeIndex = index
        channels = inputAudioFormat.channelCount
        isFloat = encoding == C.ENCODING_PCM_FLOAT
        filter = LowPassFilter(inputAudioFormat.sampleRate)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        val output = replaceOutputBuffer(input.remaining())
        val lpf = filter
        if (lpf == null) {
            output.put(input).flip()
            return
        }
        val bytes = if (isFloat) 4 else 2
        var channel = 0
        while (input.remaining() >= bytes) {
            if (isFloat) {
                val sample = input.float
                output.putFloat(if (channel == lfeIndex) lpf.process(sample) else sample)
            } else {
                val sample = input.short
                if (channel == lfeIndex) {
                    val filtered = lpf.process(sample / 32768f) * 32768f
                    output.putShort(filtered.coerceIn(-32768f, 32767f).toInt().toShort())
                } else {
                    output.putShort(sample)
                }
            }
            channel = (channel + 1) % channels
        }
        // Неполный отсчет в конце (не бывает у целых сэмплов) отбрасываем
        input.position(input.limit())
        output.flip()
    }

    override fun onReset() {
        filter = null
        lfeIndex = -1
    }
}
