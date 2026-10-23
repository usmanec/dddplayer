package top.rootu.dddplayer.player

import android.media.AudioDeviceInfo
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.audio.AudioSink
import top.rootu.dddplayer.logic.ChannelLayoutLogic
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Перекодирование многоканального PCM в AC3 / E-AC3 для вывода битстримом (ТВ и саундбары по ARC и оптике
 * часто принимают многоканальный звук только так). Декодированный звук сводится в выходную раскладку
 * (с усилением центра - голоса, перевода), кодируется FFmpeg ([AudioEncoderJni]) и уходит в [delegate]
 * как обычный проброс AC3/E-AC3.
 *
 * Перекодируется только PCM 16 бит / float 32, 44.1 и 48 кГц, если устройство умеет выводить
 * этот формат битстримом. Остальное идет в [delegate] как раньше.
 * Скорость воспроизведения к битстриму не применяется (как при обычном пробросе).
 */
@OptIn(UnstableApi::class)
internal class TranscodingAudioSink(
    private val delegate: AudioSink,
    private val config: Config
) : AudioSink {

    /**
     * @param eac3 E-AC3 вместо AC3 (тоже не больше 5.1: кодировщик FFmpeg не умеет 7.1)
     * @param layoutSetting выходная раскладка ([ChannelLayoutLogic.LAYOUT_AUTO] и др.)
     * @param centerGainDb усиление центра (голоса), дБ
     */
    data class Config(val eac3: Boolean, val layoutSetting: Int, val centerGainDb: Int) {
        val bitRate: Int get() = if (eac3) EAC3_BITRATE else AC3_BITRATE
        /** Нужно ли сводить сам звук: тогда и AC3/DTS источника декодируются, а не пробрасываются как есть. */
        val mixingRequired: Boolean get() = centerGainDb != 0 || layoutSetting != ChannelLayoutLogic.LAYOUT_AUTO
    }

    private var transcoding = false
    private var encoder = 0L
    private var frameSize = 0
    private var sampleRate = 0
    private var inChannels = 0
    private var outChannels = 0
    private var inputFloat = false
    private var matrix = FloatArray(0)
    private var volume = 1f
    private var encoderSpec: Triple<Boolean, Int, Long>? = null

    // Набираемый кадр: планарный float [канал][отсчет]
    private var frame = FloatArray(0)
    private var frameFill = 0
    private val packet: ByteBuffer = ByteBuffer.allocateDirect(MAX_PACKET_SIZE).order(ByteOrder.nativeOrder())
    private var packetPending = false
    private var packetTimeUs = 0L
    private var endOfStreamQueued = false

    // Время кадров считается от якоря по числу закодированных кадров (без дрейфа округления)
    private var anchorUs = C.TIME_UNSET
    private var framesSinceAnchor = 0L
    private var lastBuffer: ByteBuffer? = null
    private var lastBufferTimeUs = C.TIME_UNSET

    val isTranscoding: Boolean get() = transcoding

    /** Кодек на выходе для бейджа аудио, null - перекодирования нет. */
    val outputLabel: String? get() = if (transcoding) "${if (config.eac3) "E-AC3" else "AC3"} ${outChannels}ch" else null

    override fun supportsFormat(format: Format): Boolean =
        getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun getFormatSupport(format: Format): Int {
        // Своя раскладка или усиление голоса: многоканальный битстрим источника декодируем, чтобы свести
        if (config.mixingRequired && isEncodedMultichannel(format) && AudioEncoderJni.isAvailable) {
            return AudioSink.SINK_FORMAT_UNSUPPORTED
        }
        return delegate.getFormatSupport(format)
    }

    private fun isEncodedMultichannel(format: Format): Boolean =
        format.sampleMimeType != MimeTypes.AUDIO_RAW && format.channelCount != 1 && format.channelCount != 2

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        val layout = transcodeLayout(inputFormat)
        val encodedFormat = layout?.let { encodedFormat(it, inputFormat.sampleRate) }
        val useEncoder = layout != null && encodedFormat != null && delegate.supportsFormat(encodedFormat) &&
            openEncoder(layout, inputFormat)
        if (useEncoder != transcoding || useEncoder) {
            Log.i(TAG, if (useEncoder) {
                "PCM ${inputFormat.channelCount}ch ${inputFormat.sampleRate}Hz -> ${outputLabelFor(layout!!)}, " +
                    "center ${config.centerGainDb} dB"
            } else {
                "Transcoding off for ${inputFormat.sampleMimeType} ${inputFormat.channelCount}ch"
            })
        }
        transcoding = useEncoder
        discardState()
        if (useEncoder) {
            delegate.configure(encodedFormat!!, 0, null)
        } else {
            releaseEncoder()
            delegate.configure(inputFormat, specifiedBufferSize, outputChannels)
        }
    }

    /** Раскладка, в которую перекодируем этот вход, null - вход идет как есть. */
    private fun transcodeLayout(format: Format): ChannelLayoutLogic.Layout? {
        if (format.sampleMimeType != MimeTypes.AUDIO_RAW || !AudioEncoderJni.isAvailable) return null
        if (format.pcmEncoding != C.ENCODING_PCM_16BIT && format.pcmEncoding != C.ENCODING_PCM_FLOAT) return null
        if (format.sampleRate != 48_000 && format.sampleRate != 44_100) return null
        ChannelLayoutLogic.inputPositions(format.channelCount) ?: return null
        // Стерео в стерео кодировать незачем; стерео - только если раскладку задали явно
        if (format.channelCount <= 2 && config.layoutSetting == ChannelLayoutLogic.LAYOUT_AUTO) return null
        return ChannelLayoutLogic.targetLayout(format.channelCount, config.layoutSetting)
    }

    private fun encodedFormat(layout: ChannelLayoutLogic.Layout, rate: Int): Format = Format.Builder()
        .setSampleMimeType(if (config.eac3) MimeTypes.AUDIO_E_AC3 else MimeTypes.AUDIO_AC3)
        .setChannelCount(layout.channelCount)
        .setSampleRate(rate)
        .setAverageBitrate(config.bitRate)
        .build()

    private fun outputLabelFor(layout: ChannelLayoutLogic.Layout) =
        "${if (config.eac3) "E-AC3" else "AC3"} ${layout.channelCount}ch ${config.bitRate / 1000} kbps"

    private fun openEncoder(layout: ChannelLayoutLogic.Layout, format: Format): Boolean {
        val spec = Triple(config.eac3, format.sampleRate, layout.ffmpegMask)
        if (encoder == 0L || spec != encoderSpec) {
            releaseEncoder()
            encoder = AudioEncoderJni.nCreate(config.eac3, format.sampleRate, layout.ffmpegMask, config.bitRate)
            if (encoder == 0L) {
                Log.w(TAG, "Encoder not created for ${outputLabelFor(layout)}")
                return false
            }
            encoderSpec = spec
            frameSize = AudioEncoderJni.nFrameSize(encoder)
        }
        sampleRate = format.sampleRate
        inChannels = format.channelCount
        outChannels = layout.channelCount
        inputFloat = format.pcmEncoding == C.ENCODING_PCM_FLOAT
        matrix = ChannelLayoutLogic.mixMatrix(
            ChannelLayoutLogic.inputPositions(inChannels)!!, layout, ChannelLayoutLogic.dbToGain(config.centerGainDb)
        )
        frame = FloatArray(outChannels * frameSize)
        return true
    }

    private fun releaseEncoder() {
        if (encoder != 0L) AudioEncoderJni.nRelease(encoder)
        encoder = 0L
        encoderSpec = null
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (!transcoding) return delegate.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)

        // Отказанный кадр отдаем первым, иначе потеряем его
        if (packetPending) {
            if (!delegate.handleBuffer(packet, packetTimeUs, 1)) return false
            packetPending = false
        }
        if (!buffer.hasRemaining()) return true

        // Якорь времени. Проверяем только новый буфер: повтор того же буфера (вывод не принял кадр)
        // продолжает его с середины. Если вход разошелся с расчетом (пропуск данных), отсчет заново
        val newBuffer = buffer !== lastBuffer || presentationTimeUs != lastBufferTimeUs
        lastBuffer = buffer
        lastBufferTimeUs = presentationTimeUs
        val expectedUs = anchorUs + samplesToUs(framesSinceAnchor * frameSize + frameFill)
        if (anchorUs == C.TIME_UNSET || (newBuffer && abs(presentationTimeUs - expectedUs) > RESYNC_THRESHOLD_US)) {
            if (anchorUs != C.TIME_UNSET) Log.i(TAG, "Input jumped by ${(presentationTimeUs - expectedUs) / 1000} ms, re-anchoring")
            anchorUs = presentationTimeUs
            framesSinceAnchor = 0
            frameFill = 0
        }

        val data = buffer.order(ByteOrder.nativeOrder())
        val bytesPerFrame = inChannels * if (inputFloat) 4 else 2
        val input = FloatArray(inChannels)
        while (data.remaining() >= bytesPerFrame) {
            for (c in 0 until inChannels) {
                input[c] = if (inputFloat) data.float else data.short / 32768f
            }
            for (o in 0 until outChannels) {
                var sum = 0f
                for (c in 0 until inChannels) sum += input[c] * matrix[c * outChannels + o]
                frame[o * frameSize + frameFill] = (sum * volume).coerceIn(-1f, 1f)
            }
            frameFill++
            if (frameFill == frameSize && !encodeAndWrite()) return false
        }
        // Неполный отсчет в конце буфера (не бывает у целых сэмплов) отбрасываем
        if (data.hasRemaining()) data.position(data.limit())
        return true
    }

    /** Кодирует набранный кадр и отдает его. false - вывод не принял кадр (он остается в очереди). */
    private fun encodeAndWrite(): Boolean {
        frameFill = 0
        packet.clear()
        val size = AudioEncoderJni.nEncode(encoder, frame, packet)
        val timeUs = anchorUs + samplesToUs(framesSinceAnchor * frameSize)
        framesSinceAnchor++
        if (size <= 0) {
            if (size < 0) Log.w(TAG, "Encode failed: $size")
            return true
        }
        packet.position(0).limit(size)
        if (!delegate.handleBuffer(packet, timeUs, 1)) {
            packetPending = true
            packetTimeUs = timeUs
            return false
        }
        return true
    }

    private fun samplesToUs(samples: Long): Long = samples * C.MICROS_PER_SECOND / sampleRate

    override fun playToEndOfStream() {
        if (transcoding) {
            if (packetPending) {
                if (!delegate.handleBuffer(packet, packetTimeUs, 1)) return
                packetPending = false
            }
            // Хвост неполного кадра дополняем тишиной
            if (!endOfStreamQueued && frameFill > 0) {
                endOfStreamQueued = true
                for (o in 0 until outChannels) frame.fill(0f, o * frameSize + frameFill, (o + 1) * frameSize)
                if (!encodeAndWrite()) return
            }
            endOfStreamQueued = true
        }
        delegate.playToEndOfStream()
    }

    override fun isEnded(): Boolean = delegate.isEnded() && !packetPending

    override fun hasPendingData(): Boolean = packetPending || delegate.hasPendingData()

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long = delegate.getCurrentPositionUs(sourceEnded)

    override fun handleDiscontinuity() {
        discardState()
        delegate.handleDiscontinuity()
    }

    override fun flush() {
        discardState()
        delegate.flush()
    }

    private fun discardState() {
        frameFill = 0
        packetPending = false
        endOfStreamQueued = false
        anchorUs = C.TIME_UNSET
        framesSinceAnchor = 0
        lastBuffer = null
        lastBufferTimeUs = C.TIME_UNSET
    }

    override fun play() = delegate.play()

    override fun pause() = delegate.pause()

    override fun getAudioTrackBufferSizeUs(): Long = delegate.getAudioTrackBufferSizeUs()

    override fun setListener(listener: AudioSink.Listener) = delegate.setListener(listener)

    override fun setPlayerId(playerId: PlayerId?) = delegate.setPlayerId(playerId)

    override fun setClock(clock: Clock) = delegate.setClock(clock)

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) =
        delegate.setPlaybackParameters(playbackParameters)

    override fun getPlaybackParameters(): PlaybackParameters = delegate.getPlaybackParameters()

    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) = delegate.setSkipSilenceEnabled(skipSilenceEnabled)

    override fun getSkipSilenceEnabled(): Boolean = delegate.getSkipSilenceEnabled()

    override fun setAudioAttributes(audioAttributes: AudioAttributes) = delegate.setAudioAttributes(audioAttributes)

    override fun getAudioAttributes(): AudioAttributes? = delegate.audioAttributes

    override fun setAudioSessionId(audioSessionId: Int) = delegate.setAudioSessionId(audioSessionId)

    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) = delegate.setAuxEffectInfo(auxEffectInfo)

    override fun setPreferredDevice(audioDeviceInfo: AudioDeviceInfo?) = delegate.setPreferredDevice(audioDeviceInfo)

    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) = delegate.setOutputStreamOffsetUs(outputStreamOffsetUs)

    override fun setVolume(volume: Float) {
        // Громкость битстрима выход не меняет: при перекодировании применяем ее сами
        this.volume = volume
        delegate.setVolume(volume)
    }

    override fun enableTunnelingV21() = delegate.enableTunnelingV21()

    override fun disableTunneling() = delegate.disableTunneling()

    override fun reset() {
        discardState()
        transcoding = false
        releaseEncoder()
        delegate.reset()
    }

    override fun release() {
        releaseEncoder()
        delegate.release()
    }

    companion object {
        private const val TAG = "TranscodingAudioSink"
        const val AC3_BITRATE = 640_000
        const val EAC3_BITRATE = 1_024_000
        // Кадр AC3 не больше 3840 байт, E-AC3 при 1024 кбит/с - 4096
        private const val MAX_PACKET_SIZE = 8192
        private const val RESYNC_THRESHOLD_US = 200_000L
    }
}
