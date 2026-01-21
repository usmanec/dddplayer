package top.rootu.dddplayer.utils

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import androidx.core.net.toUri
import top.rootu.dddplayer.model.MediaItem
import top.rootu.dddplayer.model.SubtitleItem

object IntentUtils {

    fun parseIntent(intent: Intent): Pair<List<MediaItem>, Int> {
        val dataUri = intent.data
        val extras = intent.extras ?: Bundle.EMPTY

        // Headers
        val headersMap = mutableMapOf<String, String>()
        val headersArray = getSmartStringArray(extras, "headers")
        if (headersArray != null) {
            for (i in 0 until headersArray.size - 1 step 2) {
                headersMap[headersArray[i]] = headersArray[i + 1]
            }
        }

        // Single Video Data
        val singleTitle = extras.getString("title")
        val singleFilename = extras.getString("filename")
        val startPosition = extras.getInt("position", 0).toLong()
        // Single poster
        val singlePoster = extras.getString("thumbnail")
        // Single Video Subtitles
        val singleSubs = parseSubtitles(extras, "subs")

        // Playlist Data
        val videoListUris = getParcelableArrayCompat(extras, "video_list")

        if (videoListUris.isNullOrEmpty()) {
            // --- SINGLE FILE MODE ---
            if (dataUri == null) return Pair(emptyList(), 0)

            val item = MediaItem(
                uri = dataUri,
                title = singleTitle ?: singleFilename ?: dataUri.lastPathSegment,
                filename = singleFilename,
                posterUri = singlePoster?.toUri(),
                headers = headersMap,
                subtitles = singleSubs,
                startPositionMs = startPosition
            )
            return Pair(listOf(item), 0)
        } else {
            // --- PLAYLIST MODE ---
            // Используем "умный" метод извлечения массивов
            val names = getSmartStringArray(extras, "video_list.name")
            val filenames = getSmartStringArray(extras, "video_list.filename")
            val posters = getSmartStringArray(extras, "video_list.thumbnail")

            val playlistSubsBundles = getParcelableArrayListCompat<Bundle>(extras, "video_list.subtitles")

            val playlist = mutableListOf<MediaItem>()
            var startIndex = 0

            for (i in videoListUris.indices) {
                val uri = (videoListUris[i] as? Uri) ?: (videoListUris[i] as? String)?.toUri() ?: continue

                var title = names?.getOrNull(i)
                if (title.isNullOrEmpty()) title = filenames?.getOrNull(i)
                if (title.isNullOrEmpty()) title = uri.lastPathSegment

                val itemSubs = if (playlistSubsBundles != null && i < playlistSubsBundles.size) {
                    parseSubtitles(playlistSubsBundles[i], "uris", "names")
                } else if (dataUri != null && uri == dataUri) {
                    singleSubs
                } else {
                    emptyList()
                }

                val pos = if (dataUri != null && uri == dataUri) startPosition else 0L
                if (dataUri != null && uri == dataUri) startIndex = i

                playlist.add(
                    MediaItem(
                        uri = uri,
                        title = title,
                        filename = filenames?.getOrNull(i),
                        posterUri = posters?.getOrNull(i)?.takeIf { it.isNotEmpty() }?.toUri(),
                        headers = headersMap,
                        subtitles = itemSubs,
                        startPositionMs = pos
                    )
                )
            }
            return Pair(playlist, startIndex)
        }
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
            list.add(SubtitleItem(uri, names?.getOrNull(i), filenames?.getOrNull(i)))
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