package top.rootu.dddplayer.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink

/**
 * Без изменений только Dolby Digital и Dolby Digital Plus (вместе с Atmos в DD+): для HDMI-связей, которые больше
 * ничего не передают (ARC; телевизор или проектор, пропускающий со входа только AC3/E-AC3), хотя EDID устройства
 * обещает DTS и TrueHD. Те декодируются (FFmpeg), а не уходят в тишину; DD и DD+ доходят до ресивера как есть,
 * без декодирования и повторного кодирования системой (Fire TV: стерео-микшер, затем MS12 обратно в DD+).
 */
@UnstableApi
class DolbyOnlySink(sink: AudioSink) : ForwardingAudioSink(sink) {

    override fun getFormatSupport(format: Format): Int =
        if (isBlocked(format)) AudioSink.SINK_FORMAT_UNSUPPORTED else super.getFormatSupport(format)

    @Deprecated("AudioSink.supportsFormat", ReplaceWith("getFormatSupport(format)"))
    override fun supportsFormat(format: Format): Boolean = !isBlocked(format) && super.supportsFormat(format)

    companion object {
        private val DOLBY = setOf(MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC)

        // Битовый поток не из семейства Dolby Digital (PCM и неизвестное решает сам Sink)
        fun isBlocked(format: Format): Boolean {
            val mime = format.sampleMimeType ?: return false
            return mime != MimeTypes.AUDIO_RAW && mime !in DOLBY
        }
    }
}
