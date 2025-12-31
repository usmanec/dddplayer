package top.rootu.dddplayer.utils

import android.content.Intent
import android.net.Uri
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
        val headersArray = extras.getStringArray("headers")
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
        val singlePoster = extras.getString("poster")
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
            val names = extras.getStringArray("video_list.name")
            val filenames = extras.getStringArray("video_list.filename")
            val posters = extras.getStringArray("video_list.poster") // Playlist posters
            val playlistSubsBundles = extras.getParcelableArrayList<Bundle>("video_list.subtitles")

            val playlist = mutableListOf<MediaItem>()
            var startIndex = 0

            for (i in videoListUris.indices) {
                val uri = videoListUris[i] as Uri

                var title = names?.getOrNull(i)
                if (title == null) title = filenames?.getOrNull(i)
                if (title == null) title = uri.lastPathSegment

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
                        posterUri = posters?.getOrNull(i)?.toUri(),
                        headers = headersMap,
                        subtitles = itemSubs,
                        startPositionMs = pos
                    )
                )
            }
            return Pair(playlist, startIndex)
        }
    }

    private fun parseSubtitles(
        bundle: Bundle,
        keyUri: String,
        keyName: String = "$keyUri.name"
    ): List<SubtitleItem> {
        val uris = getParcelableArrayCompat(bundle, keyUri) ?: return emptyList()
        val names = bundle.getStringArray(keyName)
        val filenames = bundle.getStringArray("$keyUri.filename")

        val list = mutableListOf<SubtitleItem>()
        for (i in uris.indices) {
            val uri = uris[i] as Uri
            list.add(
                SubtitleItem(
                    uri = uri,
                    name = names?.getOrNull(i),
                    filename = filenames?.getOrNull(i)
                )
            )
        }
        return list
    }

    @Suppress("DEPRECATION")
    private fun getParcelableArrayCompat(bundle: Bundle, key: String): Array<Parcelable>? {
        return bundle.getParcelableArray(key)
    }
}