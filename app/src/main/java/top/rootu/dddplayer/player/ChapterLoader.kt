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
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * Reads the chapter list out of the container header.
 *
 * The first few megabytes usually contain the Matroska header, but `Chapters`
 * can sit much further (this is the reason some files showed no chapters at
 * all). The loader follows `SeekHead` when possible and, as a last resort,
 * walks the top-level elements by their declared sizes — the same navigation a
 * full EBML parser performs. Media3 does not expose chapters at all, which is
 * why this exists.
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
        val first = read(url, isHttp, headers, 0, HEAD_BYTES)
        if (first == null || first.bytes.isEmpty()) {
            Log.d(TAG, "no header for $url")
            return emptyList()
        }

        val readAt: (Long, Long) -> ByteArray? = { offset, length ->
            read(url, isHttp, headers, offset, length)?.bytes
        }

        var chapters = ChapterParser.parse(first.bytes)
        if (chapters.isEmpty() && isMatroska(first.bytes)) {
            chapters = ChapterParser.loadViaSeekHead(first.bytes, readAt)
        }
        if (chapters.isEmpty() && isMatroska(first.bytes)) {
            chapters = ChapterParser.findChaptersByWalking(
                first.bytes,
                first.fileSize ?: Long.MAX_VALUE,
                readAt
            )
        }

        cache.put(url, chapters)
        Log.d(TAG, "chapters=${chapters.size} for $url")
        return chapters
    }

    private fun isMatroska(data: ByteArray): Boolean =
        data.size >= 4 &&
            data[0] == 0x1A.toByte() &&
            data[1] == 0x45.toByte() &&
            data[2] == 0xDF.toByte() &&
            data[3] == 0xA3.toByte()

    private fun read(
        url: String,
        isHttp: Boolean,
        headers: List<String>,
        offset: Long,
        length: Long
    ): Fetched? {
        if (length <= 0) return null
        return if (isHttp) fetch(url, headers, offset, length) else readLocal(url, offset, length)
    }

    private fun readLocal(url: String, offset: Long, length: Long): Fetched? {
        return try {
            val path = Uri.parse(url).path ?: return null
            val file = File(path)
            if (!file.isFile) return null

            RandomAccessFile(file, "r").use { raf ->
                val total = raf.length()
                if (offset >= total) return Fetched(ByteArray(0), total)

                val size = minOf(length, total - offset).toInt()
                val bytes = ByteArray(size)
                raf.seek(offset)
                var read = 0
                while (read < size) {
                    val n = raf.read(bytes, read, size - read)
                    if (n <= 0) break
                    read += n
                }
                Fetched(bytes.copyOf(read), total)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun fetch(url: String, headers: List<String>, offset: Long, length: Long): Fetched? {
        val builder = Request.Builder()
            .url(url)
            .header("Range", "bytes=$offset-${offset + length - 1}")
        var i = 0
        while (i + 1 < headers.size) {
            builder.header(headers[i], headers[i + 1])
            i += 2
        }

        return try {
            client.newCall(builder.build()).execute().use { response ->
                // A server that ignores Range returns the whole file; that is only
                // usable when the request started at zero.
                if (response.code != 206 && !(response.code == 200 && offset == 0L)) return null
                val body = response.body ?: return null
                val total = if (response.code == 206) {
                    response.header("Content-Range")
                        ?.substringAfterLast('/')
                        ?.toLongOrNull()
                } else {
                    body.contentLength().takeIf { it >= 0 }
                }

                val source = body.source()
                val buffer = Buffer()
                while (buffer.size < length) {
                    if (source.read(buffer, length - buffer.size) == -1L) break
                }
                Fetched(buffer.readByteArray(), total)
            }
        } catch (e: Exception) {
            null
        }
    }

    private class Fetched(val bytes: ByteArray, val fileSize: Long?)

    private companion object {
        const val TAG = "ChapterLoader"

        /** Header window: covers Matroska headers and MP4 `chpl` in the common case. */
        const val HEAD_BYTES = 6L * 1024 * 1024
    }
}
