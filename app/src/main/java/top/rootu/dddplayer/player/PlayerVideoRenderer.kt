package top.rootu.dddplayer.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import top.rootu.dddplayer.logic.DolbyVisionLogic

/**
 * Видеорендерер плеера (MediaCodec) с параметрами как в DefaultRenderersFactory и двумя поправками:
 *
 * - [mapDvToHevc] - режим совместимости Dolby Vision (подмена декодера DV на HEVC). Селектор декодеров видит
 *   только MIME-тип, поэтому профиль проверяется здесь: потоки без совместимого слоя HEVC (профиль 5 и др.)
 *   получают обычный выбор декодера вместо подмены;
 * - декодеры HiSilicon не умеют менять поверхность на ходу: после ухода в настройки (декодер переключен на
 *   временную поверхность) возврат на SurfaceView падал в setOutputSurface ("cannot change usage"),
 *   оверлей оставался зеленым. Для них декодер пересоздается при смене поверхности (штатный обход Media3).
 */
@UnstableApi
class PlayerVideoRenderer(
    builder: MediaCodecVideoRenderer.Builder,
    private val mapDvToHevc: Boolean
) : MediaCodecVideoRenderer(builder) {

    override fun supportsFormat(mediaCodecSelector: MediaCodecSelector, format: Format): Int =
        super.supportsFormat(selectorFor(mediaCodecSelector, format), format)

    override fun getDecoderInfos(
        mediaCodecSelector: MediaCodecSelector,
        format: Format,
        requiresSecureDecoder: Boolean
    ): List<MediaCodecInfo> =
        super.getDecoderInfos(selectorFor(mediaCodecSelector, format), format, requiresSecureDecoder)

    private fun selectorFor(selector: MediaCodecSelector, format: Format): MediaCodecSelector =
        if (mapDvToHevc && format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION &&
            !DolbyVisionLogic.canPlayAsHevc(format.codecs)
        ) {
            MediaCodecSelector.DEFAULT
        } else {
            selector
        }

    override fun codecNeedsSetOutputSurfaceWorkaround(name: String): Boolean =
        name.startsWith(HISILICON_DECODER_PREFIX) || super.codecNeedsSetOutputSurfaceWorkaround(name)

    private companion object {
        const val HISILICON_DECODER_PREFIX = "OMX.hisi."
    }
}
