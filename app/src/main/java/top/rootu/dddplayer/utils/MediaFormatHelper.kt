package top.rootu.dddplayer.utils

import android.media.AudioFormat
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MimeTypes

object MediaFormatHelper {

    // ==================== ОСНОВНЫЕ УТИЛИТЫ ====================

    /**
     * Извлекает расширение файла из пути
     */
    fun getFileExtension(path: String): String? {
        val lastDotIndex = path.lastIndexOf('.')
        return if (lastDotIndex != -1 && lastDotIndex < path.length - 1) {
            path.substring(lastDotIndex + 1).lowercase()
        } else {
            null
        }
    }

    // ==================== ОПРЕДЕЛЕНИЕ MIME-ТИПОВ ====================

    /**
     * Определяет MIME-тип видео по URI
     */
    fun getVideoMimeType(uri: Uri): String? {
        val scheme = uri.scheme?.lowercase()
        val path = uri.path ?: return null

        // Проверяем протоколы стриминга
        if (scheme in listOf("rtsp", "rtcp")) {
            return MimeTypes.APPLICATION_RTSP
        }

        val extension = getFileExtension(path) ?: return null

        return when (extension) {
            // Форматы с явной поддержкой в ExoPlayer
            "mp4", "m4v" -> MimeTypes.VIDEO_MP4
            "mkv" -> MimeTypes.VIDEO_MATROSKA
            "webm" -> MimeTypes.VIDEO_WEBM
//            "mov" -> MimeTypes.VIDEO_QUICKTIME
            "ts" -> MimeTypes.VIDEO_MP2T
            "3gp", "3gpp" -> MimeTypes.VIDEO_H263
            "avi" -> MimeTypes.VIDEO_AVI
            "flv" -> MimeTypes.VIDEO_FLV
            "ogv", "ogg" -> MimeTypes.VIDEO_OGG
            "mpg", "mpeg" -> MimeTypes.VIDEO_MPEG
            "m2ts", "mts" -> MimeTypes.VIDEO_MP2T
            "wmv" -> MimeTypes.VIDEO_VC1
            "divx" -> MimeTypes.VIDEO_DIVX
            "hevc", "h265" -> MimeTypes.VIDEO_H265
            "h264", "avc" -> MimeTypes.VIDEO_H264
            "vp8" -> MimeTypes.VIDEO_VP8
            "vp9" -> MimeTypes.VIDEO_VP9
            "av1" -> MimeTypes.VIDEO_AV1
            "mjpeg" -> MimeTypes.VIDEO_MJPEG

            // Форматы потокового вещания (манифесты)
            "m3u8" -> MimeTypes.APPLICATION_M3U8
            "mpd" -> MimeTypes.APPLICATION_MPD
            "ism", "isml" -> MimeTypes.APPLICATION_SS

            // Другие поддерживаемые форматы
            "mp4v" -> MimeTypes.VIDEO_MP4V
            "mp42" -> MimeTypes.VIDEO_MP42
            "mp43" -> MimeTypes.VIDEO_MP43
            "ps", "mp2p" -> MimeTypes.VIDEO_PS
            "mpg2", "mpeg2" -> MimeTypes.VIDEO_MPEG2

            // Форматы с поддержкой Dolby Vision
            "dovi", "dv" -> MimeTypes.VIDEO_DOLBY_VISION

            // Матричные видеоформаты
            "mvhevc" -> MimeTypes.VIDEO_MV_HEVC

            // Приложение-контейнеры
            "matroska" -> MimeTypes.APPLICATION_MATROSKA

            // Неподдерживаемые или устаревшие форматы
            else -> null
        }
    }

    /**
     * Определяет MIME-тип аудио по URI
     */
    fun getAudioMimeType(uri: Uri): String? {
        val path = uri.path ?: return null
        val extension = getFileExtension(path) ?: return null

        return when (extension) {
            // Основные аудиоформаты
            "mp3" -> MimeTypes.AUDIO_MPEG
            "aac" -> MimeTypes.AUDIO_AAC
            "flac" -> MimeTypes.AUDIO_FLAC
            "wav", "wave" -> MimeTypes.AUDIO_WAV
            "ogg", "oga" -> MimeTypes.AUDIO_OGG
            "opus" -> MimeTypes.AUDIO_OPUS
            "m4a" -> MimeTypes.AUDIO_MP4
            "ac3" -> MimeTypes.AUDIO_AC3
            "eac3" -> MimeTypes.AUDIO_E_AC3
            "dts" -> MimeTypes.AUDIO_DTS
            "amr" -> MimeTypes.AUDIO_AMR
            "amr-wb" -> MimeTypes.AUDIO_AMR_WB
            "mid", "midi" -> MimeTypes.AUDIO_MIDI

            // Форматы без явной поддержки
            else -> null
        }
    }

    /**
     * Определяет MIME-тип субтитров по URI
     */
    fun getSubtitleMimeType(uri: Uri): String? {
        val path = uri.path ?: return null
        val extension = getFileExtension(path) ?: return MimeTypes.TEXT_UNKNOWN

        return when (extension) {
            // Форматы с явной поддержкой в ExoPlayer
            "srt" -> MimeTypes.APPLICATION_SUBRIP
            "vtt" -> MimeTypes.TEXT_VTT
            "ssa", "ass" -> MimeTypes.TEXT_SSA
            "ttml", "xml", "dfxp", "tt" -> MimeTypes.APPLICATION_TTML
            "tx3g" -> MimeTypes.APPLICATION_TX3G
            "mp4vtt" -> MimeTypes.APPLICATION_MP4VTT
            "mp4cea608" -> MimeTypes.APPLICATION_MP4CEA608
            "sup" -> MimeTypes.APPLICATION_PGS
            "dvbsub", "dvbsubs" -> MimeTypes.APPLICATION_DVBSUBS
            "rawcc" -> MimeTypes.APPLICATION_RAWCC
            "sub" -> MimeTypes.APPLICATION_VOBSUB

            // Бинарные форматы, которые ExoPlayer НЕ поддерживает
            "idx", "pac", "dks", "mks", "cdg", "scr" -> null

            // Все остальные случаи
            else -> MimeTypes.TEXT_UNKNOWN
        }
    }

    /**
     * Универсальный метод определения MIME-типа
     */
    fun getMimeType(uri: Uri): String? {
        return getVideoMimeType(uri) ?: getAudioMimeType(uri) ?: getSubtitleMimeType(uri)
    }

    // ==================== ПРЕОБРАЗОВАНИЕ В ЧЕЛОВЕКОЧИТАЕМЫЙ ВИД ====================

    /**
     * Преобразует конфигурацию каналов в читаемую строку
     */
    fun getChannelConfigString(channelConfig: Int): String {
        return when (channelConfig) {
            AudioFormat.CHANNEL_OUT_MONO -> "mono"
            AudioFormat.CHANNEL_OUT_STEREO -> "stereo"
            AudioFormat.CHANNEL_OUT_QUAD -> "quad"
            AudioFormat.CHANNEL_OUT_SURROUND -> "4.0"
            AudioFormat.CHANNEL_OUT_5POINT1 -> "5.1"
            AudioFormat.CHANNEL_OUT_6POINT1 -> "6.1"
            AudioFormat.CHANNEL_OUT_7POINT1 -> "7.1 (5 fronts)"
            AudioFormat.CHANNEL_OUT_7POINT1_SURROUND -> "7.1"
            AudioFormat.CHANNEL_OUT_5POINT1POINT2 -> "5.1.2"
            AudioFormat.CHANNEL_OUT_5POINT1POINT4 -> "5.1.4"
            AudioFormat.CHANNEL_OUT_7POINT1POINT2 -> "7.1.2"
            AudioFormat.CHANNEL_OUT_7POINT1POINT4 -> "7.1.4"
            AudioFormat.CHANNEL_OUT_9POINT1POINT4 -> "9.1.4"
            AudioFormat.CHANNEL_OUT_9POINT1POINT6 -> "9.1.6"
            else -> {
                // Считаем количество установленных битов, чтобы узнать число каналов
                val channelCount = Integer.bitCount(channelConfig)
                if (channelCount > 0) "${channelCount}ch" else ""
            }
        }
    }

    /**
     * Преобразует аудиокодек в читаемое название
     */
    fun getAudioCodecName(encoding: Int): String {
        return when (encoding) {
            C.ENCODING_INVALID -> "Invalid"

            // PCM форматы
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_8BIT,
            C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT -> "PCM"

            // Big Endian PCM форматы
            C.ENCODING_PCM_16BIT_BIG_ENDIAN -> "PCM-16(BE)"
            C.ENCODING_PCM_24BIT_BIG_ENDIAN -> "PCM-24(BE)"
            C.ENCODING_PCM_32BIT_BIG_ENDIAN -> "PCM-32(BE)"

            // AAC форматы
            C.ENCODING_AAC_LC, C.ENCODING_AAC_HE_V1, C.ENCODING_AAC_HE_V2 -> "AAC"
            C.ENCODING_AAC_XHE -> "AAC XHE"
            C.ENCODING_AAC_ELD -> "AAC ELD"
            C.ENCODING_AAC_ER_BSAC -> "AAC ER BSAC"

            // MP3
            C.ENCODING_MP3 -> "MP3"

            // Dolby форматы
            C.ENCODING_AC3 -> "AC3"
            C.ENCODING_E_AC3 -> "E-AC3"
            C.ENCODING_E_AC3_JOC -> "DDP (Atmos)"
            C.ENCODING_AC4 -> "AC4"
            C.ENCODING_DOLBY_TRUEHD -> "TrueHD"

            // DTS форматы
            C.ENCODING_DTS -> "DTS"
            C.ENCODING_DTS_HD -> "DTS-HD"
            C.ENCODING_DTS_UHD_P2 -> "DTS-UHD P2"

            // Opus
            C.ENCODING_OPUS -> "Opus"

            else -> "Raw ($encoding)"
        }
    }

    /**
     * Форматирует битрейт в читаемый вид (кбит/с)
     */
    fun formatBitrate(bitrate: Long): String {
        return when {
            bitrate <= 0 -> ""
            bitrate < 1000 -> "$bitrate бит/с"
            bitrate < 1_000_000 -> "${bitrate / 1000} кбит/с"
            else -> "${bitrate / 1_000_000} Мбит/с"
        }
    }

    /**
     * Форматирует частоту дискретизации
     */
    fun formatSampleRate(sampleRate: Int): String {
        return when {
            sampleRate <= 0 -> ""
            sampleRate < 1000 -> "$sampleRate Гц"
            else -> "${sampleRate / 1000} кГц"
        }
    }

    /**
     * Преобразует MIME-тип в читаемое название кодеков
     */
    fun getCodecName(mimeType: String): String {
        return when (mimeType) {
            MimeTypes.VIDEO_H264 -> "H.264"
            MimeTypes.VIDEO_H265 -> "H.265"
            MimeTypes.VIDEO_VP8 -> "VP8"
            MimeTypes.VIDEO_VP9 -> "VP9"
            MimeTypes.VIDEO_AV1 -> "AV1"
            MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
            MimeTypes.AUDIO_AAC -> "AAC"
            MimeTypes.AUDIO_MPEG -> "MP3"
            MimeTypes.AUDIO_AC3 -> "AC-3"
            MimeTypes.AUDIO_E_AC3 -> "E-AC-3"
            MimeTypes.AUDIO_DTS -> "DTS"
            MimeTypes.AUDIO_FLAC -> "FLAC"
            MimeTypes.AUDIO_OPUS -> "Opus"
            else -> mimeType.substringAfterLast('/').uppercase()
        }
    }

    /**
     * Форматирует разрешение видео
     */
    fun formatResolution(width: Int, height: Int): String {
        return when {
            width <= 0 || height <= 0 -> ""
            else -> "${width}x${height}"
        }
    }

    /**
     * Форматирует соотношение сторон
     */
    fun formatAspectRatio(width: Int, height: Int): String {
        return when {
            width <= 0 || height <= 0 -> ""
            else -> {
                val gcd = greatestCommonDivisor(width, height)
                "${width / gcd}:${height / gcd}"
            }
        }
    }

    /**
     * Форматирует частоту кадров
     */
    fun formatFrameRate(frameRate: Float): String {
        return when {
            frameRate <= 0 -> ""
            else -> String.format("%.2f FPS", frameRate)
        }
    }

    // ==================== ВСПОМОГАТЕЛЬНЫЕ ФУНКЦИИ ====================

    /**
     * Наибольший общий делитель (для соотношения сторон)
     */
    private fun greatestCommonDivisor(a: Int, b: Int): Int {
        return if (b == 0) a else greatestCommonDivisor(b, a % b)
    }

    /**
     * Проверяет, является ли MIME-тип видео
     */
    fun isVideoMimeType(mimeType: String?): Boolean {
        return mimeType?.startsWith("video/") == true ||
                mimeType == MimeTypes.APPLICATION_M3U8 ||
                mimeType == MimeTypes.APPLICATION_MPD ||
                mimeType == MimeTypes.APPLICATION_SS ||
                mimeType == MimeTypes.APPLICATION_RTSP
    }

    /**
     * Проверяет, является ли MIME-тип аудио
     */
    fun isAudioMimeType(mimeType: String?): Boolean {
        return mimeType?.startsWith("audio/") == true
    }

    /**
     * Проверяет, является ли MIME-тип субтитрами
     */
    fun isSubtitleMimeType(mimeType: String?): Boolean {
        return mimeType?.startsWith("text/") == true ||
                mimeType == MimeTypes.APPLICATION_SUBRIP ||
                mimeType == MimeTypes.APPLICATION_TTML ||
                mimeType == MimeTypes.APPLICATION_TX3G ||
                mimeType == MimeTypes.APPLICATION_MP4VTT ||
                mimeType == MimeTypes.APPLICATION_MP4CEA608 ||
                mimeType == MimeTypes.APPLICATION_VOBSUB ||
                mimeType == MimeTypes.APPLICATION_PGS ||
                mimeType == MimeTypes.APPLICATION_DVBSUBS ||
                mimeType == MimeTypes.APPLICATION_RAWCC
    }
}