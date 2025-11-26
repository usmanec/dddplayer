package top.rootu.dddplayer.utils

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import top.rootu.dddplayer.model.StereoInputType
import java.util.Locale
import java.util.regex.Pattern

object StereoTypeDetector {

    // Используем разделители, чтобы не ловить части других слов (например "sound" не должно ловиться как "ou")
    private const val DELIMITERS = "[\\.\\-\\[\\(_ ]"
    private const val END_DELIMITERS = "([ _|\\]\\)\\(\\.,]|$)"

    // Горизонтальная (Side-by-Side)
    // sbs, hsbs, half-sbs, lr, lrq, rl, rlq, side-by-side, горизонтальная
    private val SBS_PATTERN = Pattern.compile(
        "$DELIMITERS((half|h)?sbs|lrq?|rlq?|side-?by-?side|горизонтальная)$END_DELIMITERS"
    )

    // Вертикальная (Top-Bottom)
    // ou, hou, half-ou, ab, abq, ba, over-under, top-bottom, вертикальная
    private val TB_PATTERN = Pattern.compile(
        "$DELIMITERS((half|h)?ou|abq?|ba|over-?under|top-?bottom|вертикальная)$END_DELIMITERS"
    )

    // Чересстрочная (Interlaced)
    private val INTERLACED_PATTERN = Pattern.compile(
        "${DELIMITERS}interlace${END_DELIMITERS}"
    )

    // 3D(z) Tiled Format
    private val TILED_1080P = Pattern.compile(
        "${DELIMITERS}3d(z)?[-_. ]?tiled[-_. ]?(format)?${END_DELIMITERS}"
    )

    /**
     * Определяет тип стереопары.
     * Приоритет:
     * 1. Метаданные контейнера (MKV StereoMode, MP4 SEI)
     * 2. Имя файла (Regex)
     */
    @OptIn(UnstableApi::class)
    fun detect(format: Format?, uri: Uri?): StereoInputType {
        // 1. Проверка метаданных ExoPlayer (Media3)
        if (format != null && format.stereoMode != Format.NO_VALUE) {
            when (format.stereoMode) {
                C.STEREO_MODE_LEFT_RIGHT -> return StereoInputType.SIDE_BY_SIDE
                C.STEREO_MODE_TOP_BOTTOM -> return StereoInputType.TOP_BOTTOM
                C.STEREO_MODE_INTERLEAVED_LEFT_PRIMARY,
                C.STEREO_MODE_INTERLEAVED_RIGHT_PRIMARY -> return StereoInputType.INTERLACED
            }
        }

        // 2. Попытка определить тип стереопары по имени файла
        val filename =
            uri?.lastPathSegment?.lowercase(Locale.getDefault()) ?: return StereoInputType.NONE

        if (SBS_PATTERN.matcher(filename).matches()) {
            return StereoInputType.SIDE_BY_SIDE
        }

        if (TB_PATTERN.matcher(filename).matches()) {
            return StereoInputType.TOP_BOTTOM
        }

        if (INTERLACED_PATTERN.matcher(filename).matches()) {
            return StereoInputType.INTERLACED
        }

        if (TILED_1080P.matcher(filename).matches()) {
            return StereoInputType.TILED_1080P
        }

        // 3. Если не удалось определить, возвращаем NONE
        return StereoInputType.NONE
    }
}