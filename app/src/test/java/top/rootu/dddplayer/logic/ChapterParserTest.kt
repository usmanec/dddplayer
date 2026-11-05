package top.rootu.dddplayer.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterParserTest {

    @Test
    fun `matroska chapters keep order, times and titles`() {
        val mkv = segment(
            chapters(
                edition(
                    atom(0L, "Intro"),
                    atom(60_000_000_000L, "Part 1"),
                    atom(3_600_000_000_000L, "Finale")
                )
            )
        )

        val parsed = ChapterParser.parse(mkv)

        assertEquals(
            listOf(
                Chapter(0, "Intro"),
                Chapter(60_000, "Part 1"),
                Chapter(3_600_000, "Finale")
            ),
            parsed
        )
    }

    @Test
    fun `matroska without chapters returns nothing`() {
        val mkv = segment(byteArrayOf())
        assertTrue(ChapterParser.parse(mkv).isEmpty())
    }

    @Test
    fun `matroska with nested chapter atoms`() {
        val nested = atom(
            120_000_000_000L,
            "Chapter 2",
            atom(150_000_000_000L, "Scene 2.1")
        )
        val mkv = segment(chapters(edition(atom(0L, "Chapter 1"), nested)))

        assertEquals(
            listOf(
                Chapter(0, "Chapter 1"),
                Chapter(120_000, "Chapter 2"),
                Chapter(150_000, "Scene 2.1")
            ),
            ChapterParser.parse(mkv)
        )
    }

    @Test
    fun `mp4 chpl chapters are parsed`() {
        val mp4 = mp4WithChpl(
            listOf(
                0L to "Opening",
                90_000_0000L to "Middle" // 90 s in 100 ns units
            )
        )

        assertEquals(
            listOf(Chapter(0, "Opening"), Chapter(90_000, "Middle")),
            ChapterParser.parse(mp4)
        )
    }

    @Test
    fun `garbage input is ignored`() {
        assertTrue(ChapterParser.parse(ByteArray(64) { 0x7F }).isEmpty())
    }

    // ───────────────────────────── fixtures ─────────────────────────────

    private fun segment(payload: ByteArray) = elem(0x18538067L, payload)

    private fun chapters(payload: ByteArray) = elem(0x1043A770L, payload)

    private fun edition(vararg atoms: ByteArray) = elem(0x45B9L, atoms.reduce { a, b -> a + b })

    private fun atom(timeNs: Long, title: String, vararg children: ByteArray): ByteArray {
        val time = elem(0x91L, uintBytes(timeNs))
        val display = elem(0x80L, elem(0x85L, title.toByteArray(Charsets.UTF_8)))
        val nested = children.reduceOrNull { a, b -> a + b } ?: byteArrayOf()
        return elem(0xB6L, time + display + nested)
    }

    private fun mp4WithChpl(entries: List<Pair<Long, String>>): ByteArray {
        val body = mutableListOf<Byte>()
        body += byteArrayOf(0, 0, 0, 0).toList() // version + flags
        body += byteArrayOf(0, 0, 0, 0).toList() // reserved
        body += byteArrayOf(entries.size.toByte()).toList()
        entries.forEach { (start100ns, title) ->
            val name = title.toByteArray(Charsets.UTF_8)
            body += uintBytes(start100ns, 8).toList()
            body += byteArrayOf(name.size.toByte()).toList()
            body += name.toList()
        }
        val payload = body.toByteArray()
        val atom = uintBytes((payload.size + 4).toLong(), 4) + "chpl".toByteArray() + payload
        return byteArrayOf(0, 0, 0, 0) + atom
    }

    private fun elem(id: Long, payload: ByteArray): ByteArray {
        val idBytes = idToBytes(id)
        return idBytes + sizeBytes(payload.size.toLong()) + payload
    }

    private fun idToBytes(id: Long): ByteArray {
        var length = 1
        while (id >= (1L shl (8 * length)) && length < 4) length++
        val out = ByteArray(length)
        for (i in 0 until length) out[i] = ((id shr (8 * (length - 1 - i))) and 0xFF).toByte()
        return out
    }

    private fun sizeBytes(size: Long): ByteArray {
        var length = 1
        while (size >= (1L shl (7 * length)) - 1 && length < 8) length++
        val out = ByteArray(length)
        var value = size
        for (i in length - 1 downTo 0) {
            out[i] = (value and 0xFF).toByte()
            value = value shr 8
        }
        out[0] = (out[0].toInt() or (1 shl (8 - length))).toByte()
        return out
    }

    private fun uintBytes(value: Long, length: Int = 8): ByteArray {
        val out = ByteArray(length)
        var v = value
        for (i in length - 1 downTo 0) {
            out[i] = (v and 0xFF).toByte()
            v = v shr 8
        }
        return out
    }
}
