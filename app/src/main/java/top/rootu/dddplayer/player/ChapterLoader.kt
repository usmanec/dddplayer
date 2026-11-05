package top.rootu.dddplayer.player

import android.net.Uri
import android.util.Log
import android.util.LruCache
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import top.rootu.dddplayer.logic.Chapter
import top.rootu.dddplayer.logic.ChapterParser
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Reads the chapter list out of the container header.
 *
 * The player only needs the first few megabytes (Matroska keeps `Chapters` in
 * the header, MP4 keeps `chpl` in `udta`), so a bounded range request is enough.
 * Media3 does not expose chapters at all, which is why this exists.
 */
class ChapterLoader {

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val cache = object : LruCache<String, List<Chapter>>(16) {}
    private val inFlight = HashSet<String>()

    /** Returns an empty list for local files, failed requests and files without chapters. */
    fun load(url: String?, headers: List<String> = emptyList()): List<Chapter> {
        if (url.isNullOrBlank()) return emptyList()

        val isHttp = url.startsWith("http://", true) || url.startsWith("https://", true)
        val isFile = url.startsWith("file://", true)
        if (!isHttp && !isFile) return emptyList()

        cache.get(url)?.let { return it }
        synchronized(inFlight) {
            // A playlist load and the first item transition ask for the same URL.
            if (!inFlight.add(url)) return emptyList()
        }
        try {
            return loadUncached(url, isHttp, headers)
        } finally {
            synchronized(inFlight) { inFlight.remove(url) }
        }
    }

    private fun loadUncached(url: String, isHttp: Boolean, headers: List<String>): List<Chapter> {
        val bytes = if (isHttp) fetch(url, headers) else readLocal(url)
        if (bytes == null) {
            Log.d(TAG, "no header for $url")
            return emptyList()
        }

        val chapters = ChapterParser.parse(bytes)
        cache.put(url, chapters)
        Log.d(TAG, "chapters=${chapters.size} for $url")
        return chapters
    }

    private fun readLocal(url: String): ByteArray? {
        return try {
            val path = Uri.parse(url).path ?: return null
            File(path).inputStream().use { input ->
                val buffer = ByteArray(MAX_BYTES.toInt())
                var read = 0
                while (read < buffer.size) {
                    val n = input.read(buffer, read, buffer.size - read)
                    if (n <= 0) break
                    read += n
                }
                buffer.copyOf(read)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun fetch(url: String, headers: List<String>): ByteArray? {
        val builder = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-${MAX_BYTES - 1}")
        var i = 0
        while (i + 1 < headers.size) {
            builder.header(headers[i], headers[i + 1])
            i += 2
        }

        return try {
            client.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body ?: return null
                val source = body.source()
                val buffer = Buffer()
                while (buffer.size < MAX_BYTES) {
                    if (source.read(buffer, MAX_BYTES - buffer.size) == -1L) break
                }
                buffer.readByteArray()
            }
        } catch (e: Exception) {
            null
        }
    }

    private companion object {
        const val TAG = "ChapterLoader"

        /** Matroska headers of typical releases fit well inside this window. */
        const val MAX_BYTES = 6L * 1024 * 1024
    }
}
