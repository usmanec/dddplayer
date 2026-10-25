package top.rootu.dddplayer.player

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink

/**
 * Какой сжатый звук отдавать выходу как есть. Остальной сжатый звук выход "не поддерживает",
 * и рендереры его декодируют. PCM проходит без проверки.
 */
@OptIn(UnstableApi::class)
internal class PassthroughPolicySink(
    sink: AudioSink,
    private val allowEncoded: (Format) -> Boolean
) : ForwardingAudioSink(sink) {

    override fun supportsFormat(format: Format): Boolean = allowed(format) && super.supportsFormat(format)

    override fun getFormatSupport(format: Format): Int =
        if (allowed(format)) super.getFormatSupport(format) else AudioSink.SINK_FORMAT_UNSUPPORTED

    private fun allowed(format: Format): Boolean =
        format.sampleMimeType == MimeTypes.AUDIO_RAW || allowEncoded(format)
}
