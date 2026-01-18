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
        // Читаем заголовок (8 байт достаточно для MKV и MP4 ftyp)
        val header = ByteArray(8)

        // Важно: используем read, который гарантированно читает или возвращает -1
        // Но для InputStream это не гарантировано, поэтому лучше читать в цикле или использовать DataInputStream
        // Но здесь мы просто попробуем прочитать один раз.
        val bytesRead = inputStream.read(header)

        if (bytesRead < 4) {
            // Слишком мало данных, даже для сигнатуры
            return emptyList()
        }

        // Создаем PushbackInputStream, чтобы вернуть прочитанные байты обратно в поток
        val pushbackStream = PushbackInputStream(inputStream, 8)
        pushbackStream.unread(header, 0, bytesRead)

        return try {
            when {
                // MKV Header: 1A 45 DF A3
//                header.take(4).toByteArray().contentEquals(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())) -> {
//                    MatroskaMetadataReader.parse(pushbackStream)
//                }

                // MP4 ftyp (обычно с 4-го байта)
                // ftyp сигнатура: 4 байта размер, 4 байта 'ftyp'
                bytesRead >= 8 && String(header, 4, 4, Charsets.US_ASCII) == "ftyp" -> {
                    Mp4MetadataReader.parse(pushbackStream)
                }
                else -> {
                    // Неизвестный формат.
                    // ВАЖНО: Мы не читаем дальше. Мы просто выходим.
                    // ParsingDataSource увидит, что мы закончили, и закроет пайп.
                    emptyList()
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}