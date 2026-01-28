package top.rootu.dddplayer.logic

import android.util.Log
import top.rootu.dddplayer.logic.UnifiedMetadataReader.TrackInfo
import top.rootu.dddplayer.logic.UnifiedMetadataReader.TrackType
import java.io.DataInputStream
import java.io.InputStream

object Mp4MetadataReader {
    private const val TAG = "Mp4Debug"

    fun parse(inputStream: InputStream): List<TrackInfo> {
        val tracks = mutableListOf<TrackInfo>()
        try {
            val stream = DataInputStream(inputStream)
            while (stream.available() > 0) {
                val size = stream.readInt().toLong() and 0xffffffffL
                val type = readType(stream)
                val bodySize = if (size == 1L) stream.readLong() - 16 else if (size == 0L) stream.available().toLong() else size - 8

                if (type == "moov") {
                    Log.d(TAG, "Found moov atom")
                    parseMoov(stream, bodySize, tracks)
                    break
                } else {
                    if (bodySize > 0) stream.skipBytes(bodySize.toInt())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing MP4", e)
        }
        return tracks
    }

    private fun parseMoov(stream: DataInputStream, size: Long, out: MutableList<TrackInfo>) {
        var remaining = size
        while (remaining > 8) {
            val boxSize = stream.readInt().toLong() and 0xffffffffL
            val type = readType(stream)
            val bodySize = if (boxSize == 1L) stream.readLong() - 16 else boxSize - 8
            val actualBoxSize = if (boxSize == 1L) bodySize + 16 else boxSize

            if (actualBoxSize > remaining) break

            if (type == "trak") {
                Log.d(TAG, "Found trak atom")
                parseTrak(stream, bodySize)?.let { out.add(it) }
            } else {
                stream.skipBytes(bodySize.toInt())
            }
            remaining -= actualBoxSize
        }
    }

    private fun parseTrak(stream: DataInputStream, size: Long): TrackInfo? {
        var remaining = size
        var trackId = -1
        var trackName: String? = null
        var language: String? = null
        var type = TrackType.UNKNOWN

        while (remaining > 8) {
            val boxSize = stream.readInt().toLong() and 0xffffffffL
            val typeStr = readType(stream)
            val bodySize = if (boxSize == 1L) stream.readLong() - 16 else boxSize - 8
            val actualBoxSize = if (boxSize == 1L) bodySize + 16 else boxSize

            if (actualBoxSize > remaining) break

            when (typeStr) {
                "tkhd" -> trackId = parseTkhd(stream, bodySize)
                "mdia" -> {
                    val (lang, hdlrType) = parseMdia(stream, bodySize)
                    if (lang != null) language = lang

                    Log.d(TAG, "  Track ID $trackId handler type: '$hdlrType'")

                    type = when(hdlrType) {
                        "vide" -> TrackType.VIDEO
                        "soun" -> TrackType.AUDIO
                        "sbtl", "text", "clcp" -> TrackType.SUBTITLE
                        else -> TrackType.UNKNOWN
                    }
                }
                "udta" -> {
                    val name = parseUdta(stream, bodySize)
                    if (name != null) {
                        trackName = name
                        Log.d(TAG, "  Track ID $trackId name found: '$name'")
                    }
                }
                else -> stream.skipBytes(bodySize.toInt())
            }
            remaining -= actualBoxSize
        }
        return if (trackId != -1) TrackInfo(trackId, trackName, language, type) else null
    }

    private fun parseTkhd(stream: DataInputStream, size: Long): Int {
        val version = stream.readByte().toInt()
        stream.skipBytes(3) // flags
        stream.skipBytes(if (version == 1) 16 else 8)
        val trackId = stream.readInt()
        val readSoFar = if (version == 1) 24 else 16
        stream.skipBytes((size - readSoFar).toInt())
        return trackId
    }

    private fun parseMdia(stream: DataInputStream, size: Long): Pair<String?, String?> {
        var remaining = size
        var language: String? = null
        var hdlrType: String? = null

        while (remaining > 8) {
            val boxSize = stream.readInt().toLong() and 0xffffffffL
            val type = readType(stream)
            val bodySize = if (boxSize == 1L) stream.readLong() - 16 else boxSize - 8
            val actualBoxSize = if (boxSize == 1L) bodySize + 16 else boxSize

            if (actualBoxSize > remaining) break

            when (type) {
                "mdhd" -> {
                    language = parseMdhd(stream, bodySize)
                }
                "hdlr" -> {
                    hdlrType = parseHdlrType(stream, bodySize)
                }
                else -> {
                    stream.skipBytes(bodySize.toInt())
                }
            }
            remaining -= actualBoxSize
        }
        return language to hdlrType
    }

    private fun parseMdhd(stream: DataInputStream, size: Long): String {
        val version = stream.readByte().toInt()
        stream.skipBytes(3)
        stream.skipBytes(if (version == 1) 16 else 8)
        stream.skipBytes(4)
        stream.skipBytes(if (version == 1) 8 else 4)

        val langBits = stream.readUnsignedShort()
        val readSoFar = if (version == 1) 34 else 22
        stream.skipBytes((size - readSoFar).toInt())

        val c1 = ((langBits shr 10) and 0x1F) + 0x60
        val c2 = ((langBits shr 5) and 0x1F) + 0x60
        val c3 = (langBits and 0x1F) + 0x60
        return "${c1.toChar()}${c2.toChar()}${c3.toChar()}"
    }

    private fun parseHdlrType(stream: DataInputStream, size: Long): String {
        // hdlr structure:
        // 1 byte version
        // 3 bytes flags
        // 4 bytes pre_defined
        // 4 bytes handler_type <--- WE NEED THIS
        // ... reserved ... name ...

        stream.skipBytes(8) // version (1) + flags (3) + pre_defined (4)
        val typeBytes = ByteArray(4)
        stream.readFully(typeBytes) // handler_type

        val typeStr = String(typeBytes, Charsets.US_ASCII)

        // Пропускаем остаток
        val readSoFar = 12 // 8 + 4
        val left = size - readSoFar
        if (left > 0) stream.skipBytes(left.toInt())

        return typeStr
    }

    private fun parseUdta(stream: DataInputStream, size: Long): String? {
        var remaining = size
        var name: String? = null

        while (remaining > 8) {
            val boxSize = stream.readInt().toLong() and 0xffffffffL
            val type = readType(stream)
            val bodySize = if (boxSize == 1L) stream.readLong() - 16 else boxSize - 8
            val actualBoxSize = if (boxSize == 1L) bodySize + 16 else boxSize

            if (actualBoxSize > remaining) break

            if (type == "name") {
                val buffer = ByteArray(bodySize.toInt())
                stream.readFully(buffer)

                Log.d(TAG, "    Raw name bytes: ${buffer.joinToString { "%02x".format(it) }}")

                var offset = 0
                // Эвристика для FullBox (00 00 00 00)
                if (buffer.size > 4 && buffer[0].toInt() == 0 && buffer[1].toInt() == 0 && buffer[2].toInt() == 0 && buffer[3].toInt() == 0) {
                    offset = 4
                    // Эвристика для Language (00 00)
                    if (buffer.size > 6 && buffer[4].toInt() == 0 && buffer[5].toInt() == 0) {
                        offset = 6
                    }
                }

                name = String(buffer, offset, buffer.size - offset, Charsets.UTF_8).trim()
                name = name.trimEnd('\u0000')

            } else {
                stream.skipBytes(bodySize.toInt())
            }

            if (name != null && name.isNotEmpty()) {
                val left = remaining - actualBoxSize
                if (left > 0) stream.skipBytes(left.toInt())
                return name
            }

            remaining -= actualBoxSize
        }
        return name
    }

    private fun readType(stream: DataInputStream): String {
        val bytes = ByteArray(4)
        stream.readFully(bytes)
        return String(bytes, Charsets.US_ASCII)
    }
}