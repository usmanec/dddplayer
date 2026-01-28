package top.rootu.dddplayer.utils

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import top.rootu.dddplayer.model.StereoInputType
import java.util.Locale
import java.util.regex.Pattern

object StereoTypeDetector {

    // Универсальный разделитель:
    // Точка, тире, подчеркивание, пробел, любые скобки (круглые, квадратные, фигурные)
    private const val SEP = "[\\.\\-_ \\[\\]\\(\\)\\{\\}]"

    // Граница начала: любой разделитель
    private const val B_START = SEP

    // Граница конца: Либо конец строки ($), либо любой разделитель
    private const val B_END = "($SEP|$)"

    // Горизонтальная (Side-by-Side)
    // sbs, hsbs, half-sbs, full-sbs, lr, lrq, rl, rlq, side-by-side, горизонтальная
    private val SBS_PATTERN = Pattern.compile(
        "$B_START((half|h|full)?-?sbs|lrq?|rlq?|side-?by-?side|горизонтальная)$B_END"
    )

    // Вертикальная (Top-Bottom)
    // ou, hou, half-ou, tb, htb (Top-Bottom), ab, abq, ba, over-under, top-bottom, вертикальная
    private val TB_PATTERN = Pattern.compile(
        "$B_START((half|h)?-?ou|(half|h)?-?tb|abq?|ba|over-?under|top-?bottom|вертикальная)$B_END"
    )

    // Чересстрочная (Interlaced)
    private val INTERLACED_PATTERN = Pattern.compile(
        "${B_START}interlace${B_END}"
    )

    // 3D(z) Tiled Format
    private val TILED_1080P = Pattern.compile(
        "${B_START}3d(z)?[-_. ]?tiled[-_. ]?(format)?${B_END}"
    )

    /**
     * Определяет тип стереопары.
     * Приоритет:
     * 1. Метаданные контейнера (MKV StereoMode, MP4 SEI)
     * 2. Имя файла (Regex)
     */
    fun detect(format: Format?, uri: Uri?): StereoInputType {
        // 1. Проверка метаданных ExoPlayer (Media3)
        if (format != null && format.stereoMode != Format.NO_VALUE) {
            when (format.stereoMode) {
                C.STEREO_MODE_LEFT_RIGHT -> return StereoInputType.SIDE_BY_SIDE
                C.STEREO_MODE_TOP_BOTTOM -> return StereoInputType.TOP_BOTTOM
                C.STEREO_MODE_INTERLEAVED_LEFT_PRIMARY,
                C.STEREO_MODE_INTERLEAVED_RIGHT_PRIMARY -> return StereoInputType.INTERLACED
                else -> {}
            }
        }

        // 2. Попытка определить тип стереопары по имени файла
        val filename =
            uri?.lastPathSegment?.lowercase(Locale.getDefault()) ?: return StereoInputType.NONE

        if (SBS_PATTERN.matcher(filename).find()) {
            return StereoInputType.SIDE_BY_SIDE
        }

        if (TB_PATTERN.matcher(filename).find()) {
            return StereoInputType.TOP_BOTTOM
        }

        if (INTERLACED_PATTERN.matcher(filename).find()) {
            return StereoInputType.INTERLACED
        }

        if (TILED_1080P.matcher(filename).find()) {
            return StereoInputType.TILED_1080P
        }

        // 3. Если не удалось определить, возвращаем NONE
        return StereoInputType.NONE
    }
}