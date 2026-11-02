package top.rootu.dddplayer.model

import android.net.Uri
import java.util.UUID

data class MediaItem(
    val uri: Uri,
    val title: String? = null,
    val filename: String? = null,
    val posterUri: Uri? = null,
    val group: String? = null, // Группа (group-title) из M3U
    val absoluteIndex: Int = 0, // Позиция в полном плейлисте (для номера при фильтрации по группе)
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<SubtitleItem> = emptyList(),
    val startPositionMs: Long = 0,
    val stereoType: StereoInputType = StereoInputType.NONE,
    val segments: List<SkipSegment> = emptyList(), // что можно пропустить (заставка, титры), из Intent
    // Уникальный ID для DiffUtil, генерируется автоматически при создании объекта
    val uuid: String = UUID.randomUUID().toString()
)

data class SubtitleItem(
    val uri: Uri,
    val name: String? = null,
    val filename: String? = null,
    val mimeType: String? = null
)
// Отрезок, который можно пропустить: заставка, краткое содержание прошлых серий, титры (мс; endMs = null - до конца)
data class SkipSegment(
    val kind: Kind,
    val startMs: Long,
    val endMs: Long?
) {
    enum class Kind { INTRO, RECAP, CREDITS }
}
