package top.rootu.dddplayer.utils

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import top.rootu.dddplayer.model.MediaItem
import top.rootu.dddplayer.model.SubtitleItem
import java.util.ArrayList

object IntentUtils {

    fun parseIntent(intent: Intent): Pair<List<MediaItem>, Int> {
        val dataUri = intent.data
        val extras = intent.extras ?: Bundle.EMPTY

        // 1. Headers
        val headersMap = mutableMapOf<String, String>()
        val headersArray = extras.getStringArray("headers")
        if (headersArray != null) {
            for (i in 0 until headersArray.size - 1 step 2) {
                headersMap[headersArray[i]] = headersArray[i + 1]
            }
        }

        // 2. Single Video Data
        val singleTitle = extras.getString("title")
        val singleFilename = extras.getString("filename")
        val startPosition = extras.getInt("position", 0).toLong()

        // 3. Single Video Subtitles
        val singleSubs = parseSubtitles(extras, "subs")

        // 4. Playlist Data
        val videoListUris = getParcelableArrayCompat(extras, "video_list")

        if (videoListUris.isNullOrEmpty()) {
            // --- SINGLE FILE MODE ---
            if (dataUri == null) return Pair(emptyList(), 0)

            val item = MediaItem(
                uri = dataUri,
                title = singleTitle ?: singleFilename ?: dataUri.lastPathSegment,
                filename = singleFilename,
                headers = headersMap,
                subtitles = singleSubs,
                startPositionMs = startPosition
            )
            return Pair(listOf(item), 0)
        } else {
            // --- PLAYLIST MODE ---
            val names = extras.getStringArray("video_list.name")
            val filenames = extras.getStringArray("video_list.filename")

            // Custom extension for playlist subtitles: ArrayList<Bundle>
            // Bundle keys: "uris" (Parcelable[]), "names" (String[])
            val playlistSubsBundles = extras.getParcelableArrayList<Bundle>("video_list.subtitles")

            val playlist = mutableListOf<MediaItem>()
            var startIndex = 0

            for (i in videoListUris.indices) {
                val uri = videoListUris[i] as Uri

                // Determine title
                var title = names?.getOrNull(i)
                if (title == null) title = filenames?.getOrNull(i)
                if (title == null) title = uri.lastPathSegment

                // Determine subtitles for this specific item
                val itemSubs = if (playlistSubsBundles != null && i < playlistSubsBundles.size) {
                    val bundle = playlistSubsBundles[i]
                    parseSubtitles(bundle, "uris", "names")
                } else if (dataUri != null && uri == dataUri) {
                    // If this is the "main" intent URI, attach the main "subs" extras to it
                    singleSubs
                } else {
                    emptyList()
                }

                // Determine start position
                val pos = if (dataUri != null && uri == dataUri) startPosition else 0L

                if (dataUri != null && uri == dataUri) {
                    startIndex = i
                }

                playlist.add(
                    MediaItem(
                        uri = uri,
                        title = title,
                        filename = filenames?.getOrNull(i),
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