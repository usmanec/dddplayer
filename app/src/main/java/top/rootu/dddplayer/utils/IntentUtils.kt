package top.rootu.dddplayer.utils

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.provider.OpenableColumns
import androidx.core.net.toUri
import top.rootu.dddplayer.logic.TrackLogic
import top.rootu.dddplayer.model.HistorySource
import top.rootu.dddplayer.model.MediaItem
import top.rootu.dddplayer.model.SubtitleItem

object IntentUtils {

    // Внутренние ключи стартового экрана (FileBrowserActivity -> PlayerActivity), внешним приложениям не нужны
    const val EXTRA_HISTORY_KIND = "top.rootu.dddplayer.history_kind"
    const val EXTRA_HISTORY_SOURCE = "top.rootu.dddplayer.history_source"
    const val EXTRA_HISTORY_NAME = "top.rootu.dddplayer.history_name"
    // Ссылка может быть плейлистом без расширения .m3u (например, get.php?...): проверить содержимое
    const val EXTRA_FORCE_M3U = "top.rootu.dddplayer.force_m3u"
    // Для внешних приложений: языки аудио этого видео через запятую, по порядку предпочтения
    const val EXTRA_AUDIO_LANG = "audio_lang"
    // Для внешних приложений: дорожки и скорость, которые приложение помнит для фильма или сериала.
    // Плеер возвращает текущие в результате теми же ключами
    const val EXTRA_AUDIO_TRACK = "audio_track"       // подпись аудиодорожки (TrackLogic.audioTrackSignature)
    const val EXTRA_SUBTITLE_TRACK = "subtitle_track" // подпись субтитров (TrackLogic.subtitleTrackSignature) или "off"
    const val EXTRA_SPEED = "speed"                    // скорость воспроизведения
    const val SUBTITLES_OFF = TrackLogic.SUBTITLES_OFF

    /** Дорожки и скорость фильма/сериала; null у поля - приложение его не помнит. */
    data class TitleTracks(val audio: String?, val subtitle: String?, val speed: Float?)

    /**
     * Дорожки фильма/сериала от приложения. null - ни одного ключа: приложение их не помнит.
     * Ключ, переданный хоть пустым, включает память: дорожка, выбранная в этом запуске, переходит на следующие видео списка.
     */
    @Suppress("DEPRECATION")
    fun parseTitleTracks(intent: Intent): TitleTracks? {
        val extras = intent.extras ?: return null
        if (listOf(EXTRA_AUDIO_TRACK, EXTRA_SUBTITLE_TRACK, EXTRA_SPEED).none { extras.containsKey(it) }) return null
        val speed = when (val value = extras.get(EXTRA_SPEED)) {
            is Number -> value.toFloat()
            is String -> value.toFloatOrNull()
            else -> null
        }
        return TitleTracks(
            extras.getString(EXTRA_AUDIO_TRACK)?.ifEmpty { null },
            extras.getString(EXTRA_SUBTITLE_TRACK)?.ifEmpty { null },
            speed?.takeIf { it > 0f }
        )
    }

    /** Источник для истории "Продолжить просмотр" или null, если видео открыто не со стартового экрана. */
    fun parseHistorySource(intent: Intent): HistorySource? {
        val kind = intent.getStringExtra(EXTRA_HISTORY_KIND) ?: return null
        val source = intent.getStringExtra(EXTRA_HISTORY_SOURCE) ?: return null
        return HistorySource(kind, source, intent.getStringExtra(EXTRA_HISTORY_NAME).orEmpty())
    }

    fun isForceM3u(intent: Intent): Boolean = intent.getBooleanExtra(EXTRA_FORCE_M3U, false)

    /** Языки аудио для этого видео ("ru,uk"): важнее настройки плеера. null - как в настройке. */
    fun parseAudioLanguages(intent: Intent): Array<String>? =
        intent.getStringExtra(EXTRA_AUDIO_LANG)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }?.toTypedArray()

    /**
     * Парсит Intent и возвращает список медиа-элементов и стартовую позицию.
     * Требует Context для разрешения имен файлов из content:// URI.
     */
    fun parseIntent(context: Context, intent: Intent): Pair<List<MediaItem>, Int> {
        val dataUri = intent.data
        val extras = intent.extras ?: Bundle.EMPTY

        // 1. Проверяем, есть ли специфичный список воспроизведения (внутренний формат)
        val videoListUris = getParcelableArrayCompat(extras, "video_list")

        if (!videoListUris.isNullOrEmpty()) {
            // --- PLAYLIST MODE (Внутренний запуск) ---
            return parseInternalPlaylist(extras, videoListUris, dataUri)
        }

        // 2. Проверяем одиночный файл (Запуск из файлового менеджера или ACTION_VIEW)
        if (dataUri != null) {
            return parseSingleFile(context, intent)
        }

        // 3. Пусто
        return Pair(emptyList(), 0)
    }

    private fun parseSingleFile(context: Context, intent: Intent): Pair<List<MediaItem>, Int> {
        val uri = intent.data ?: return Pair(emptyList(), 0)
        val extras = intent.extras ?: Bundle.EMPTY

        // Пытаемся найти заголовок в Extras (некоторые приложения передают его)
        var title = extras.getString("title") ?: extras.getString("android.intent.extra.TITLE")

        // Если заголовка нет, пытаемся получить имя файла из URI
        val filename = resolveFileName(context, uri)

        if (title.isNullOrEmpty()) {
            title = filename ?: uri.lastPathSegment ?: "Video"
        }

        val startPosition = getLongExtraCompat(extras, "position")
        // Single poster
        val singlePoster = extras.getString("thumbnail")
        // Single Video Subtitles
        val singleSubs = parseSubtitles(extras, "subs")

        val item = MediaItem(
            uri = uri,
            title = title,
            filename = filename,
            posterUri = singlePoster?.toUri(),
            headers = parseHeaders(extras),
            // Если субтитры не переданы, ищем рядом с файлом (movie.mkv -> movie.srt)
            subtitles = singleSubs.ifEmpty { SubtitleFinder.findSidecarSubtitles(uri) },
            startPositionMs = startPosition
        )

        return Pair(listOf(item), 0)
    }

    private fun parseInternalPlaylist(
        extras: Bundle,
        videoListUris: Array<Parcelable>,
        dataUri: Uri?
    ): Pair<List<MediaItem>, Int> {
        val names = getSmartStringArray(extras, "video_list.name")
        val filenames = getSmartStringArray(extras, "video_list.filename")
        val posters = getSmartStringArray(extras, "video_list.thumbnail")
        val playlistSubsBundles = getParcelableArrayListCompat<Bundle>(extras, "video_list.subtitles")

        val headersMap = parseHeaders(extras)

        val playlist = mutableListOf<MediaItem>()
        // Видео плейлиста обычно лежат в одной папке: читаем ее для поиска субтитров один раз
        val listDir = SubtitleFinder.cachedLister()
        // Стартовый элемент: явный start_index, либо элемент, совпадающий с intent.data (ниже)
        var startIndex = extras.getInt("start_index", 0)

        for (i in videoListUris.indices) {
            val uri = (videoListUris[i] as? Uri) ?: (videoListUris[i] as? String)?.toUri() ?: continue

            var title = names?.getOrNull(i)
            if (title.isNullOrEmpty()) title = filenames?.getOrNull(i)
            if (title.isNullOrEmpty()) title = uri.lastPathSegment

            val itemSubs = if (playlistSubsBundles != null && i < playlistSubsBundles.size) {
                parseSubtitles(playlistSubsBundles[i], "uris", "names")
            } else {
                emptyList()
            }

            // Если dataUri совпадает с текущим элементом списка, берем позицию из extras
            val pos = if (dataUri != null && uri == dataUri) getLongExtraCompat(extras, "position") else 0L
            if (dataUri != null && uri == dataUri) startIndex = i

            playlist.add(
                MediaItem(
                    uri = uri,
                    title = title,
                    filename = filenames?.getOrNull(i),
                    posterUri = posters?.getOrNull(i)?.takeIf { it.isNotEmpty() }?.toUri(),
                    headers = headersMap,
                    subtitles = itemSubs.ifEmpty { SubtitleFinder.findSidecarSubtitles(uri, listDir) },
                    startPositionMs = pos
                )
            )
        }
        startIndex = startIndex.coerceIn(0, (playlist.size - 1).coerceAtLeast(0))
        return Pair(playlist, startIndex)
    }

    // Заголовки передаются массивом ["Имя", "Значение", "Имя", "Значение", ...]
    private fun parseHeaders(extras: Bundle): Map<String, String> {
        val headersArray = getSmartStringArray(extras, "headers") ?: return emptyMap()
        val result = mutableMapOf<String, String>()
        for (i in 0 until headersArray.size - 1 step 2) {
            val key = headersArray[i]
            if (key.isNotBlank()) result[key] = headersArray[i + 1]
        }
        return result
    }

    // Позиция может прийти как Int, Long или String (разные приложения передают по-разному)
    @Suppress("DEPRECATION")
    private fun getLongExtraCompat(bundle: Bundle, key: String, defaultValue: Long = 0L): Long {
        return when (val value = bundle.get(key)) {
            is Long -> value
            is Int -> value.toLong()
            is String -> value.toLongOrNull() ?: defaultValue
            else -> defaultValue
        }
    }

    /**
     * Извлекает реальное имя файла из content:// URI.
     */
    fun resolveFileName(context: Context, uri: Uri): String? {
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index != -1) {
                            return cursor.getString(index)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        // Fallback для file:// или если query не сработал
        return uri.lastPathSegment
    }

    /**
     * Пытается извлечь массив строк любым доступным способом.
     * Поддерживает: String[], ArrayList<String>, CharSequence[]
     */
    private fun getSmartStringArray(bundle: Bundle, key: String): Array<String>? {
        val strArray = bundle.getStringArray(key)
        if (strArray != null) return strArray

        val strList = bundle.getStringArrayList(key)
        if (strList != null) return strList.toTypedArray()

        val charSeqArray = bundle.getCharSequenceArray(key)
        if (charSeqArray != null) {
            return charSeqArray.map { it.toString() }.toTypedArray()
        }

        val charSeqList = bundle.getCharSequenceArrayList(key)
        if (charSeqList != null) {
            return charSeqList.map { it.toString() }.toTypedArray()
        }

        return null
    }

    private fun parseSubtitles(bundle: Bundle, keyUri: String, keyName: String = "$keyUri.name"): List<SubtitleItem> {
        val uris = getParcelableArrayCompat(bundle, keyUri) ?: return emptyList()
        val names = getSmartStringArray(bundle, keyName)
        val filenames = getSmartStringArray(bundle, "$keyUri.filename")

        val list = mutableListOf<SubtitleItem>()
        for (i in uris.indices) {
            val uri = (uris[i] as? Uri) ?: (uris[i] as? String)?.toUri() ?: continue
            list.add(
                SubtitleItem(
                    uri,
                    names?.getOrNull(i),
                    filenames?.getOrNull(i),
                    MediaFormatHelper.getSubtitleMimeType(uri)
                )
            )
        }
        return list
    }

    // Универсальный метод для получения массива Parcelable (совместимость с API 33+)
    @Suppress("DEPRECATION")
    private fun getParcelableArrayCompat(bundle: Bundle, key: String): Array<Parcelable>? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            bundle.getParcelableArray(key, Parcelable::class.java)
        } else {
            bundle.getParcelableArray(key)
        } ?: run {
            // Fallback: некоторые передают ArrayList вместо Array
            getParcelableArrayListCompat<Parcelable>(bundle, key)?.toTypedArray()
        } ?: run {
            // Fallback: строки
            bundle.getStringArrayList(key)?.map { it.toUri() }?.toTypedArray()
        }
    }

    // Универсальный метод для получения ArrayList (совместимость с API 33+)
    @Suppress("DEPRECATION")
    private inline fun <reified T : Parcelable> getParcelableArrayListCompat(bundle: Bundle, key: String): ArrayList<T>? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            bundle.getParcelableArrayList(key, T::class.java)
        } else {
            bundle.getParcelableArrayList(key)
        }
    }
}