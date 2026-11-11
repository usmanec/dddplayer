package top.rootu.dddplayer.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import top.rootu.dddplayer.logic.SoftLimiter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Цепочка микшера без обрезки: [ToFloat] перед микшером переводит 16 бит во float (микшер Media3 на 16 битах
 * сам обрезает перегрузку), а этот процессор в конце мягко ограничивает ([SoftLimiter]) и возвращает 16 бит:
 * AudioTrack на старых чипах остается 16-битным, как и раньше.
 */
@OptIn(UnstableApi::class)
internal class SoftLimitAudioProcessor : BaseAudioProcessor() {

    private var isFloat = false

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        val encoding = inputAudioFormat.encoding
        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) return AudioFormat.NOT_SET
        isFloat = encoding == C.ENCODING_PCM_FLOAT
        return AudioFormat(inputAudioFormat.sampleRate, inputAudioFormat.channelCount, C.ENCODING_PCM_16BIT)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        val bytesPerSample = if (isFloat) 4 else 2
        val output = replaceOutputBuffer(input.remaining() / bytesPerSample * 2)
        while (input.remaining() >= bytesPerSample) {
            val sample = if (isFloat) input.float else input.short / 32768f
            val limited = SoftLimiter.apply(sample) * 32768f
            output.putShort(limited.coerceIn(-32768f, 32767f).toInt().toShort())
        }
        // Неполный отсчет в конце (не бывает у целых сэмплов) отбрасываем
        input.position(input.limit())
        output.flip()
    }

    /** 16 бит -> float перед микшером, чтобы сумма каналов не обрезалась до ограничителя. */
    class ToFloat : BaseAudioProcessor() {
        override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
            if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) return AudioFormat.NOT_SET
            return AudioFormat(inputAudioFormat.sampleRate, inputAudioFormat.channelCount, C.ENCODING_PCM_FLOAT)
        }

        override fun queueInput(inputBuffer: ByteBuffer) {
            val input = inputBuffer.order(ByteOrder.nativeOrder())
            val output = replaceOutputBuffer(input.remaining() / 2 * 4)
            while (input.remaining() >= 2) output.putFloat(input.short / 32768f)
            input.position(input.limit())
            output.flip()
        }
    }
}
