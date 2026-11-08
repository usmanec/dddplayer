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
import org.json.JSONArray
import org.json.JSONObject
import top.rootu.dddplayer.model.HistorySource
import top.rootu.dddplayer.model.MediaItem
import top.rootu.dddplayer.model.SubtitleItem
import top.rootu.dddplayer.model.UrlQuality

object IntentUtils {

    // Внутренние ключи стартового экрана (FileBrowserActivity -> PlayerActivity), внешним приложениям не нужны
    const val EXTRA_HISTORY_KIND = "top.rootu.dddplayer.history_kind"
    const val EXTRA_HISTORY_SOURCE = "top.rootu.dddplayer.history_source"
    const val EXTRA_HISTORY_NAME = "top.rootu.dddplayer.history_name"
    // Ссылка может быть плейлистом без расширения .m3u (например, get.php?...): проверить содержимое
    const val EXTRA_FORCE_M3U = "top.rootu.dddplayer.force_m3u"

    /** Источник для истории "Продолжить просмотр" или null, если видео открыто не со стартового экрана. */
    fun parseHistorySource(intent: Intent): HistorySource? {
        val kind = intent.getStringExtra(EXTRA_HISTORY_KIND) ?: return null
        val source = intent.getStringExtra(EXTRA_HISTORY_SOURCE) ?: return null
        return HistorySource(kind, source, intent.getStringExtra(EXTRA_HISTORY_NAME).orEmpty())
    }

    fun isForceM3u(intent: Intent): Boolean = intent.getBooleanExtra(EXTRA_FORCE_M3U, false)

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

        val headers = parseHeaders(extras)
        val urlQualities = parseUrlQualities(extras).ifEmpty { parseUrlQualitiesFromHeaders(headers) }

        val item = MediaItem(
            uri = uri,
            title = title,
            filename = filename,
            posterUri = singlePoster?.toUri(),
            headers = stripQualityHeaders(headers),
            // Если субтитры не переданы, ищем рядом с файлом (movie.mkv -> movie.srt)
            subtitles = singleSubs.ifEmpty { SubtitleFinder.findSidecarSubtitles(uri) },
            startPositionMs = startPosition,
            urlQualities = urlQualities
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

        val headers = parseHeaders(extras)
        val urlQualities = parseUrlQualities(extras).ifEmpty { parseUrlQualitiesFromHeaders(headers) }
        val cleanHeaders = stripQualityHeaders(headers)

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
                    headers = cleanHeaders,
                    subtitles = itemSubs.ifEmpty { SubtitleFinder.findSidecarSubtitles(uri, listDir) },
                    startPositionMs = pos
                )
            )
        }
        startIndex = startIndex.coerceIn(0, (playlist.size - 1).coerceAtLeast(0))

        // Качества относятся к текущей серии, а не ко всему плейлисту.
        if (urlQualities.isNotEmpty() && playlist.isNotEmpty()) {
            playlist[startIndex] = playlist[startIndex].copy(urlQualities = urlQualities)
        }

        return Pair(playlist, startIndex)
    }

    /**
     * URL-качества одного видео.
     *
     * Поддерживаются:
     *  - ddd_quality: JSON-строка [{"name":"1080p","url":"..."}] или {"1080p":"..."};
     *  - quality: JSON-строка или Bundle (на случай, если Lampa передаст карту как Bundle).
     */
    private fun parseUrlQualities(extras: Bundle): List<UrlQuality> {
        extras.getString("ddd_quality")?.let { return parseUrlQualitiesJson(it) }
        extras.getString("quality")?.let { return parseUrlQualitiesJson(it) }

        val qualityBundle = extras.getBundle("quality") ?: return emptyList()
        val result = mutableListOf<UrlQuality>()

        for (key in qualityBundle.keySet()) {
            when (val value = qualityBundle.get(key)) {
                is String -> if (value.isNotBlank()) result.add(UrlQuality(key, value))
                is Bundle -> {
                    val url = value.getString("url") ?: value.getString("link") ?: continue
                    if (url.isNotBlank()) {
                        val label = value.getString("label")?.takeIf { it.isNotBlank() } ?: key
                        result.add(UrlQuality(label, url))
                    }
                }
            }
        }

        return result
    }

    /**
     * Запасной транспорт для приложений, которые не пробрасывают произвольные
     * extras, но пробрасывают HTTP-заголовки (Lampa Android -> DDD):
     * X-DDD-Quality: [{"name":"1080p","url":"..."}]
     */
    private fun parseUrlQualitiesFromHeaders(headers: Map<String, String>): List<UrlQuality> {
        val raw = headers.entries
            .firstOrNull { it.key.equals("X-DDD-Quality", ignoreCase = true) }
            ?.value
            ?: return emptyList()

        return parseUrlQualitiesJson(raw)
    }

    /** Служебный заголовок не должен уходить на сервер видео. */
    private fun stripQualityHeaders(headers: Map<String, String>): Map<String, String> {
        return headers.filterKeys { !it.equals("X-DDD-Quality", ignoreCase = true) }
    }

    private fun parseUrlQualitiesJson(raw: String): List<UrlQuality> {
        val text = raw.trim()
        if (text.isEmpty()) return emptyList()

        val result = mutableListOf<UrlQuality>()

        try {
            if (text.startsWith("[")) {
                val array = JSONArray(text)
                for (i in 0 until array.length()) {
                    when (val value = array.opt(i)) {
                        is JSONObject -> {
                            val url = value.optString("url").ifBlank { value.optString("link") }
                            if (url.isBlank()) continue
                            val name = value.optString("name").ifBlank {
                                value.optString("label").ifBlank { "Quality ${result.size + 1}" }
                            }
                            result.add(UrlQuality(name, url))
                        }
                        is String -> if (value.isNotBlank()) {
                            result.add(UrlQuality("Quality ${result.size + 1}", value))
                        }
                    }
                }
            } else if (text.startsWith("{")) {
                val obj = JSONObject(text)
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = obj.opt(key)
                    val url = when (value) {
                        is String -> value
                        is JSONObject -> value.optString("url").ifBlank { value.optString("link") }
                        else -> ""
                    }
                    if (url.isBlank()) continue
                    val label = if (value is JSONObject) {
                        value.optString("label").ifBlank { key }
                    } else key
                    result.add(UrlQuality(label, url))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val seen = mutableSetOf<String>()
        return result.filter { seen.add(it.url) }
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