package top.rootu.dddplayer.logic

import top.rootu.dddplayer.logic.UnifiedMetadataReader.TrackInfo
import top.rootu.dddplayer.logic.UnifiedMetadataReader.TrackType
import java.io.EOFException
import java.io.InputStream

object MatroskaMetadataReader {
    private const val TAG = "MkvDebug"

    fun parse(inputStream: InputStream): List<TrackInfo> {
        try {
            val reader = EbmlReader(inputStream)
            // 1. Читаем EBML Header (1A 45 DF A3)
            if (reader.readId() != 0x1A45DFA3L) return emptyList()
            val headerSize = reader.readSize()
            reader.skip(headerSize)

            val tracks = mutableListOf<TrackInfo>()

            // 2. Ищем Segment (18 53 80 67)
            while (true) {
                val id = reader.readId()
                val size = reader.readSize()

                if (id == 0x18538067L) { // Segment
                    parseSegment(reader, size, tracks)
                    break
                } else {
                    reader.skip(size)
                }
            }
            return tracks
        } catch (_: Exception) {
            return emptyList()
        }
    }

    private fun parseSegment(reader: EbmlReader, size: Long, tracks: MutableList<TrackInfo>) {
        var bytesRead = 0L
        val limit = 512 * 1024 // Лимит поиска Tracks внутри Segment

        try {
            while (bytesRead < limit) {
                val id = reader.readId()
                val s = reader.readSize()

                if (id == 0x1654AE6BL) { // Tracks
                    parseTracks(reader, s, tracks)
                    return
                } else {
                    reader.skip(s)
                }
                bytesRead += s // Грубая оценка
            }
        } catch (_: Exception) { }
    }

    private fun parseTracks(reader: EbmlReader, size: Long, out: MutableList<TrackInfo>) {
        var read = 0L
        while (read < size) {
            try {
                val id = reader.readId()
                val s = reader.readSize()

                if (id == 0xAEL) { // TrackEntry
                    out.add(parseTrackEntry(reader, s))
                } else {
                    reader.skip(s)
                }
                read += s
            } catch (_: Exception) { break }
        }
    }

    private fun parseTrackEntry(reader: EbmlReader, size: Long): TrackInfo {
        var number = 0
        var uid = 0L
        var name: String? = null
        var lang: String? = "und"
        var type = TrackType.UNKNOWN

        val startPos = reader.totalBytesRead

        while ((reader.totalBytesRead - startPos) < size) {
            try {
                val id = reader.readId()
                val s = reader.readSize()

                when (id) {
                    0xD7L -> number = reader.readUInt(s).toInt() // TrackNumber
                    0x73C5L -> uid = reader.readUInt(s) // TrackUID
                    0x536EL -> name = reader.readString(s) // Name
                    0x22B59CL -> lang = reader.readString(s) // Language
                    0x83L -> { // TrackType
                        val mkvType = reader.readUInt(s).toInt()
                        type = when(mkvType) {
                            1 -> TrackType.VIDEO
                            2 -> TrackType.AUDIO
                            17 -> TrackType.SUBTITLE
                            else -> TrackType.UNKNOWN
                        }
                    }
                    else -> reader.skip(s)
                }
            } catch (_: Exception) { break }
        }
        return TrackInfo(number, name, lang, type)
    }

    private class EbmlReader(private val input: InputStream) {
        var totalBytesRead = 0L

        fun readByte(): Int {
            val b = input.read()
            if (b != -1) totalBytesRead++
            return b
        }

        fun readVInt(): Long {
            val first = readByte()
            if (first == -1) throw EOFException()
            var mask = 0x80
            var length = 1
            while ((first and mask) == 0) {
                mask = mask shr 1
                length++
            }
            var value = (first and (mask - 1)).toLong()
            repeat(length - 1) {
                val b = readByte()
                if (b == -1) throw EOFException()
                value = (value shl 8) or b.toLong()
            }
            return value
        }

        fun readId(): Long = readVInt()
        fun readSize(): Long = readVInt()

        fun skip(bytes: Long) {
            var skipped = 0L
            while (skipped < bytes) {
                val s = input.skip(bytes - skipped)
                if (s <= 0) break
                skipped += s
            }
            totalBytesRead += skipped
        }

        fun readString(size: Long): String {
            val bytes = ByteArray(size.toInt())
            var read = 0
            while (read < size) {
                val r = input.read(bytes, read, (size - read).toInt())
                if (r == -1) break
                read += r
            }
            totalBytesRead += read
            return String(bytes, Charsets.UTF_8).trimEnd('\u0000')
        }

        fun readUInt(size: Long): Long {
            var value = 0L
            repeat(size.toInt()) {
                val b = readByte()
                if (b != -1) value = (value shl 8) or b.toLong()
            }
            return value
        }
    }
}