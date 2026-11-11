package top.rootu.dddplayer.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import top.rootu.dddplayer.logic.DolbyVisionLogic

/**
 * Видеорендерер для режима совместимости Dolby Vision (подмена декодера DV на HEVC).
 * Селектор декодеров видит только MIME-тип, поэтому профиль проверяется здесь: потоки без
 * совместимого слоя HEVC (профиль 5 и др.) получают обычный выбор декодера вместо подмены.
 */
class DolbyVisionVideoRenderer(builder: MediaCodecVideoRenderer.Builder) : MediaCodecVideoRenderer(builder) {

    override fun supportsFormat(mediaCodecSelector: MediaCodecSelector, format: Format): Int =
        super.supportsFormat(selectorFor(mediaCodecSelector, format), format)

    override fun getDecoderInfos(
        mediaCodecSelector: MediaCodecSelector,
        format: Format,
        requiresSecureDecoder: Boolean
    ): List<MediaCodecInfo> =
        super.getDecoderInfos(selectorFor(mediaCodecSelector, format), format, requiresSecureDecoder)

    private fun selectorFor(selector: MediaCodecSelector, format: Format): MediaCodecSelector =
        if (format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION && !DolbyVisionLogic.canPlayAsHevc(format.codecs)) {
            MediaCodecSelector.DEFAULT
        } else {
            selector
        }
}
