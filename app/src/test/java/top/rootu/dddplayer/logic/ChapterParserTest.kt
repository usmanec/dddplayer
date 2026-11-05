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
    fun `seekhead points at chapters outside the header window`() {
        val mkv = elemSized(
            ChapterParser.ID_SEGMENT,
            1L shl 30,
            seekHead(seekEntry(ChapterParser.ID_CHAPTERS, 5_000_000L))
        )

        val expected = ChapterParser.segmentPayloadStart(mkv)!! + 5_000_000L
        assertEquals(expected, ChapterParser.findChaptersOffset(mkv))
    }

    @Test
    fun `chapters are loaded from the seekhead offset`() {
        val chaptersAt = chapters(
            edition(
                atom(0L, "Intro"),
                atom(60_000_000_000L, "Part 1")
            )
        )
        val first = elemSized(
            ChapterParser.ID_SEGMENT,
            1L shl 30,
            seekHead(seekEntry(ChapterParser.ID_CHAPTERS, 5_000_000L))
        )
        val offset = ChapterParser.segmentPayloadStart(first)!! + 5_000_000L

        val loaded = ChapterParser.loadViaSeekHead(first) { at, _ ->
            if (at == offset) chaptersAt else null
        }

        assertEquals(
            listOf(Chapter(0, "Intro"), Chapter(60_000, "Part 1")),
            loaded
        )
    }

    @Test
    fun `secondary seekhead is followed`() {
        val chaptersAt = chapters(
            edition(
                atom(0L, "One"),
                atom(90_000_000_000L, "Two")
            )
        )
        val first = elemSized(
            ChapterParser.ID_SEGMENT,
            1L shl 30,
            seekHead(seekEntry(ChapterParser.ID_SEEK_HEAD, 7_000_000L))
        )
        val segmentStart = ChapterParser.segmentPayloadStart(first)!!
        val secondarySeekHead = seekHead(seekEntry(ChapterParser.ID_CHAPTERS, 9_000_000L))

        val loaded = ChapterParser.loadViaSeekHead(first) { at, _ ->
            when (at) {
                segmentStart + 7_000_000L -> secondarySeekHead
                segmentStart + 9_000_000L -> chaptersAt
                else -> null
            }
        }

        assertEquals(
            listOf(Chapter(0, "One"), Chapter(90_000, "Two")),
            loaded
        )
    }

    @Test
    fun `walking skips large elements when there is no seekhead`() {
        val chaptersAt = chapters(
            edition(
                atom(0L, "Intro"),
                atom(60_000_000_000L, "Part 1")
            )
        )
        val info = elem(0x1549A966L, byteArrayOf())
        val clusterHeader = elemSized(0x1F43B675L, 5_000_000L, byteArrayOf())
        val first = elemSized(ChapterParser.ID_SEGMENT, 1L shl 30, info + clusterHeader)

        val offset = ChapterParser.segmentPayloadStart(first)!! + info.size + clusterHeader.size + 5_000_000L
        val loaded = ChapterParser.findChaptersByWalking(
            first,
            offset + chaptersAt.size
        ) { at, _ ->
            if (at == offset) chaptersAt else null
        }

        assertEquals(
            listOf(Chapter(0, "Intro"), Chapter(60_000, "Part 1")),
            loaded
        )
    }

    @Test
    fun `double encoded chapter titles are repaired`() {
        val original = "　「黒髪の少女」"
        val broken = mojibake(original)

        assertEquals(original, ChapterParser.repairMojibake(broken))
        assertEquals(
            listOf(Chapter(0, original)),
            ChapterParser.parse(segment(chapters(edition(atom(0L, broken)))))
        )
    }

    @Test
    fun `double encoded latin title is repaired`() {
        assertEquals("Café", ChapterParser.repairMojibake(mojibake("Café")))
    }

    @Test
    fun `normal accented titles are left alone`() {
        assertEquals("São Paulo", ChapterParser.repairMojibake("São Paulo"))
        assertEquals("Café — Chapter 1", ChapterParser.repairMojibake("Café — Chapter 1"))
        assertEquals("Amélie", ChapterParser.repairMojibake("Amélie"))
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

    private fun segment(payload: ByteArray) = elem(ChapterParser.ID_SEGMENT, payload)

    private fun chapters(payload: ByteArray) = elem(ChapterParser.ID_CHAPTERS, payload)

    private fun seekHead(vararg entries: ByteArray) =
        elem(ChapterParser.ID_SEEK_HEAD, concat(*entries))

    private fun seekEntry(id: Long, position: Long) = elem(
        0x4DBBL,
        elem(0x53ABL, idToBytes(id)) + elem(0x53ACL, uintBytes(position, 4))
    )

    private fun edition(vararg atoms: ByteArray) = elem(0x45B9L, concat(*atoms))

    private fun atom(timeNs: Long, title: String, vararg children: ByteArray): ByteArray {
        val time = elem(0x91L, uintBytes(timeNs))
        val display = elem(0x80L, elem(0x85L, title.toByteArray(Charsets.UTF_8)))
        return elem(0xB6L, concat(time, display, *children))
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

    /** Emulates a muxer that decoded UTF-8 bytes as Windows-1252 and encoded them again. */
    private fun mojibake(value: String): String {
        val sb = StringBuilder()
        for (b in value.toByteArray(Charsets.UTF_8)) {
            val code = b.toInt() and 0xFF
            sb.append(
                when (code) {
                    0x80 -> '\u20AC'
                    0x82 -> '\u201A'
                    0x83 -> '\u0192'
                    0x84 -> '\u201E'
                    0x85 -> '\u2026'
                    0x86 -> '\u2020'
                    0x87 -> '\u2021'
                    0x88 -> '\u02C6'
                    0x89 -> '\u2030'
                    0x8A -> '\u0160'
                    0x8B -> '\u2039'
                    0x8C -> '\u0152'
                    0x8E -> '\u017D'
                    0x91 -> '\u2018'
                    0x92 -> '\u2019'
                    0x93 -> '\u201C'
                    0x94 -> '\u201D'
                    0x95 -> '\u2022'
                    0x96 -> '\u2013'
                    0x97 -> '\u2014'
                    0x98 -> '\u02DC'
                    0x99 -> '\u2122'
                    0x9A -> '\u0161'
                    0x9B -> '\u203A'
                    0x9C -> '\u0153'
                    0x9E -> '\u017E'
                    0x9F -> '\u0178'
                    else -> code.toChar()
                }
            )
        }
        return sb.toString()
    }

    private fun elem(id: Long, payload: ByteArray): ByteArray =
        elemSized(id, payload.size.toLong(), payload)

    private fun elemSized(id: Long, size: Long, payload: ByteArray): ByteArray =
        idToBytes(id) + sizeBytes(size) + payload

    private fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var pos = 0
        for (part in parts) {
            part.copyInto(out, pos)
            pos += part.size
        }
        return out
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
