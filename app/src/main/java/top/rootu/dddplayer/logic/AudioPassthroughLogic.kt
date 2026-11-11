package top.rootu.dddplayer.logic

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi

/**
 * Проброс (битстрим) звука на ресивер: группы форматов, которые пользователь выбирает в настройках,
 * и правило, какой сжатый поток можно отдать выходу как есть.
 */
@OptIn(UnstableApi::class)
object AudioPassthroughLogic {

    const val AC3 = "ac3"
    const val EAC3 = "eac3"
    const val DTS = "dts"
    const val DTS_HD = "dtshd"
    const val TRUEHD = "truehd"

    /** Порядок пунктов в настройках. */
    val ALL_FORMATS = listOf(AC3, EAC3, DTS, DTS_HD, TRUEHD)

    /** Группа формата по MIME, null - формат не из списка (AC-4, MPEG-H и т.п.). */
    fun formatKey(mimeType: String?): String? = when (mimeType) {
        MimeTypes.AUDIO_AC3 -> AC3
        MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> EAC3
        MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_EXPRESS -> DTS
        MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_X -> DTS_HD
        MimeTypes.AUDIO_TRUEHD -> TRUEHD
        else -> null
    }

    /** Кодировки AudioTrack, хотя бы одну из которых выход должен принимать для проброса группы. */
    fun encodings(key: String): IntArray = when (key) {
        AC3 -> intArrayOf(C.ENCODING_AC3)
        EAC3 -> intArrayOf(C.ENCODING_E_AC3, C.ENCODING_E_AC3_JOC)
        DTS -> intArrayOf(C.ENCODING_DTS)
        DTS_HD -> intArrayOf(C.ENCODING_DTS_HD)
        TRUEHD -> intArrayOf(C.ENCODING_DOLBY_TRUEHD)
        else -> IntArray(0)
    }

    fun label(key: String): String = when (key) {
        AC3 -> "AC3"
        EAC3 -> "E-AC3 (Atmos)"
        DTS -> "DTS"
        DTS_HD -> "DTS-HD MA"
        TRUEHD -> "TrueHD (Atmos)"
        else -> key
    }

    /**
     * Можно ли отдать сжатый поток выходу как есть. Форматы не из списка пробрасываются при включенном
     * пробросе, как раньше; PCM сюда не попадает.
     */
    fun isAllowed(mimeType: String?, passthroughEnabled: Boolean, formats: Set<String>): Boolean {
        if (!passthroughEnabled) return false
        val key = formatKey(mimeType) ?: return true
        return key in formats
    }
}
