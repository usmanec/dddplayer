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
    // Альтернативные URL одного видео (Lampa quality map / DDD intent extra)
    val urlQualities: List<UrlQuality> = emptyList(),
    // Уникальный ID для DiffUtil, генерируется автоматически при создании объекта
    val uuid: String = UUID.randomUUID().toString()
)

data class UrlQuality(
    val name: String,
    val url: String
)

data class SubtitleItem(
    val uri: Uri,
    val name: String? = null,
    val filename: String? = null,
    val mimeType: String? = null
)