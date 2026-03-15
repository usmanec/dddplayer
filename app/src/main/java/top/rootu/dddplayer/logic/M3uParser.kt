package top.rootu.dddplayer.logic

import android.net.Uri
import android.util.Log
import org.json.JSONObject
import top.rootu.dddplayer.model.MediaItem
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import androidx.core.net.toUri

object M3uParser {
    private const val TAG = "M3uParser"
    private const val EXT_INF = "#EXTINF"
    private const val EXT_GRP = "#EXTGRP"
    private const val EXT_VLC_OPT = "#EXTVLCOPT"
    private const val EXT_HTTP = "#EXTHTTP"

    fun isPlaylist(content: String): Boolean {
        val upper = content.take(500).uppercase()
        return upper.contains(EXT_INF) && !upper.contains("#EXT-X-TARGETDURATION")
    }

    fun parse(inputStream: InputStream, baseUri: Uri, parentHeaders: Map<String, String>): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        val reader = BufferedReader(InputStreamReader(inputStream))

        var currentTitle: String? = null
        var currentPoster: Uri? = null
        var currentGroup: String? = null
        var defGroup: String? = null
        val currentItemHeaders = mutableMapOf<String, String>()

        val attrRegex = Regex("""([a-zA-Z0-9_-]+)=((?:"[^"]*")|(?:[^\s,]+))""")

        try {
            reader.forEachLine { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@forEachLine

                when {
                    trimmed.startsWith(EXT_GRP, true) -> {
                        defGroup = trimmed.substringAfter(":").trim()
                    }
                    trimmed.startsWith(EXT_INF, true) -> {
                        currentTitle = trimmed.substringAfterLast(",").trim()
                        val attrsOnly = trimmed.substringAfter(":").substringBeforeLast(",")
                        attrRegex.findAll(attrsOnly).forEach { match ->
                            val key = match.groupValues[1].lowercase()
                            val value = match.groupValues[2].removeSurrounding("\"")
                            when (key) {
                                "tvg-logo", "logo" -> currentPoster = Uri.parse(value)
                                "group-title" -> currentGroup = value
                            }
                        }
                    }
                    trimmed.startsWith(EXT_VLC_OPT, true) -> parseVlcOpt(trimmed, currentItemHeaders)
                    trimmed.startsWith(EXT_HTTP, true) -> parseExtHttp(trimmed, currentItemHeaders)
                    !trimmed.startsWith("#") -> {
                        val uri = try {
                            val parsed = Uri.parse(trimmed)
                            if (parsed.scheme != null) parsed else resolveRelative(baseUri, trimmed)
                        } catch (e: Exception) {
                            resolveRelative(baseUri, trimmed)
                        }

                        items.add(
                            MediaItem(
                                uri = uri,
                                title = currentTitle ?: "No Title",
                                posterUri = currentPoster,
                                group = currentGroup ?: defGroup, // Наследование группы
                                headers = parentHeaders.toMutableMap().apply { putAll(currentItemHeaders) }
                            )
                        )
                        currentTitle = null
                        currentPoster = null
                        currentGroup = null
                        currentItemHeaders.clear()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Parsing error: ${e.message}")
        } finally {
            inputStream.close()
        }
        return items
    }

    private fun parseVlcOpt(line: String, outHeaders: MutableMap<String, String>) {
        try {
            val opt = line.substringAfter(":").trim()
            val key = opt.substringBefore("=").lowercase()
            val value = opt.substringAfter("=")
            when (key) {
                "http-user-agent" -> outHeaders["User-Agent"] = value
                "http-referrer" -> outHeaders["Referer"] = value
                "http-cookie" -> outHeaders["Cookie"] = value
            }
        } catch (_: Exception) {}
    }

    private fun parseExtHttp(line: String, outHeaders: MutableMap<String, String>) {
        try {
            val json = JSONObject(line.substringAfter(":").trim())
            json.keys().forEach { outHeaders[it] = json.getString(it) }
        } catch (_: Exception) {}
    }

    private fun resolveRelative(baseUri: Uri, relativePath: String): Uri {
        return try {
            val baseStr = baseUri.toString()
            val lastSlash = baseStr.lastIndexOf('/')
            if (lastSlash != -1) (baseStr.substring(0, lastSlash + 1) + relativePath).toUri() else baseUri
        } catch (e: Exception) {
            relativePath.toUri()
        }
    }
}