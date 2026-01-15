package top.rootu.dddplayer.logic

import java.io.InputStream
import java.io.PushbackInputStream

object UnifiedMetadataReader {
    enum class TrackType { VIDEO, AUDIO, SUBTITLE, UNKNOWN }

    data class TrackInfo(
        val trackId: Int,
        val name: String?,
        val language: String?,
        val type: TrackType = TrackType.UNKNOWN
    )

    fun parse(inputStream: InputStream): List<TrackInfo> {
        val pushbackStream = PushbackInputStream(inputStream, 8)
        val header = ByteArray(8)
        val bytesRead = pushbackStream.read(header)
        if (bytesRead > 0) pushbackStream.unread(header, 0, bytesRead)

        return try {
            when {
                header.take(4).toByteArray().contentEquals(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())) -> {
                    MatroskaMetadataReader.parse(pushbackStream)
                }

                bytesRead >= 8 && String(header, 4, 4, Charsets.US_ASCII) == "ftyp" -> {
                    Mp4MetadataReader.parse(pushbackStream)
                }

                else -> emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}