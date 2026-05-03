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
import top.rootu.dddplayer.bridge.BridgeConfig
import top.rootu.dddplayer.bridge.BridgeMode
import top.rootu.dddplayer.bridge.BroadcastTransport
import top.rootu.dddplayer.model.MediaItem
import top.rootu.dddplayer.model.SubtitleItem

object IntentUtils {

    private fun parseFragmentParams(uri: Uri?): Map<String, String> {
        val fragment = uri?.encodedFragment ?: return emptyMap()
        if (fragment.isBlank()) return emptyMap()

        return fragment.split("&")
            .mapNotNull { pair ->
                val idx = pair.indexOf("=")
                if (idx <= 0) return@mapNotNull null
                val key = Uri.decode(pair.substring(0, idx))
                val value = Uri.decode(pair.substring(idx + 1))
                if (key.isBlank()) null else key to value
            }
            .toMap()
    }

    private fun stripFragment(uri: Uri): Uri {
        return uri.buildUpon().fragment(null).build()
    }


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

    fun parseBridgeConfig(intent: Intent): BridgeConfig {
        val fragment = parseFragmentParams(intent.data)

        val enabledFromFragment =
            fragment.containsKey("ddd_mode") ||
                fragment.containsKey("ddd_sid") ||
                fragment.containsKey("ddd_port") ||
                fragment.containsKey("ddd_token")

        val modeString =
            intent.getStringExtra("bridge_mode")
                ?: fragment["ddd_mode"]
                ?: "broadcast"

        val mode = when (modeString.lowercase()) {
            "local" -> BridgeMode.LOCAL
            "both" -> BridgeMode.BOTH
            "broadcast" -> BridgeMode.BROADCAST
            else -> BridgeMode.BROADCAST
        }

        return BridgeConfig(
            enabled = intent.getBooleanExtra("bridge_enabled", false) || enabledFromFragment,
            sessionId = intent.getStringExtra("bridge_session_id") ?: fragment["ddd_sid"],
            mode = mode,
            emitPosition = intent.getBooleanExtra("bridge_emit_position", true),
            emitUserActions = intent.getBooleanExtra("bridge_emit_user_actions", true),
            positionIntervalMs = intent.getLongExtra("bridge_position_interval_ms", 1000L).coerceAtLeast(250L),
            client = intent.getStringExtra("bridge_client") ?: fragment["ddd_client"] ?: "lampa",
            eventAction = intent.getStringExtra("bridge_event_action") ?: BroadcastTransport.DEFAULT_ACTION_EVENT,
            receiverPackage = intent.getStringExtra("bridge_receiver_package"),
            schemaVersion = intent.getIntExtra("bridge_schema_version", 1),
            localPort = fragment["ddd_port"]?.toIntOrNull() ?: 39677,
            localToken = intent.getStringExtra("bridge_local_token") ?: fragment["ddd_token"]
        )
    }

        private fun parseSingleFile(context: Context, intent: Intent): Pair<List<MediaItem>, Int> {
        val rawUri = intent.data ?: return Pair(emptyList(), 0)
        val uri = stripFragment(rawUri)
        val extras = intent.extras ?: Bundle.EMPTY

        // Пытаемся найти заголовок в Extras (некоторые приложения передают его)
        var title = extras.getString("title") ?: extras.getString("android.intent.extra.TITLE")

        // Если заголовка нет, пытаемся получить имя файла из URI
        val filename = resolveFileName(context, uri)

        if (title.isNullOrEmpty()) {
            title = filename ?: uri.lastPathSegment ?: "Video"
        }

        val startPosition = getLongExtraCompat(extras, "position", 0L)
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
            subtitles = singleSubs,
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
            val pos = if (dataUri != null && uri == dataUri) getLongExtraCompat(extras, "position", 0L) else 0L
            if (dataUri != null && uri == dataUri) startIndex = i

            playlist.add(
                MediaItem(
                    uri = stripFragment(uri),
                    title = title,
                    filename = filenames?.getOrNull(i),
                    posterUri = posters?.getOrNull(i)?.takeIf { it.isNotEmpty() }?.toUri(),
                    headers = headersMap,
                    subtitles = itemSubs,
                    startPositionMs = pos
                )
            )
        }
        startIndex = startIndex.coerceIn(0, (playlist.size - 1).coerceAtLeast(0))
        return Pair(playlist, startIndex)
    }


    private fun parseHeaders(extras: Bundle): Map<String, String> {
        val headersArray = getSmartStringArray(extras, "headers") ?: return emptyMap()
        val result = mutableMapOf<String, String>()
        for (i in 0 until headersArray.size - 1 step 2) {
            val key = headersArray[i]
            val value = headersArray[i + 1]
            if (key.isNotBlank()) result[key] = value
        }
        return result
    }

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
    private fun resolveFileName(context: Context, uri: Uri): String? {
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
