package top.rootu.dddplayer.logic

/**
 * Chapters embedded in the container (Matroska `Chapters`, MP4 `chpl`).
 *
 * Media3 does not expose them, so the player reads the header itself and shows
 * the result as seek points.
 */
data class Chapter(val startMs: Long, val title: String)

object ChapterParser {

    /** Parses the first container header found in [data]. */
    fun parse(data: ByteArray): List<Chapter> {
        if (data.isEmpty()) return emptyList()
        val matroska = parseMatroska(data)
        if (matroska.isNotEmpty()) return matroska
        return parseMp4Chpl(data)
    }

    // ───────────────────────────── Matroska ─────────────────────────────

    private const val ID_SEGMENT = 0x18538067L
    private const val ID_CHAPTERS = 0x1043A770L
    private const val ID_EDITION = 0x45B9L
    private const val ID_CHAPTER_ATOM = 0xB6L
    private const val ID_TIME_START = 0x91L
    private const val ID_TIME_END = 0x92L
    private const val ID_DISPLAY = 0x80L
    private const val ID_CHAP_STRING = 0x85L

    private fun parseMatroska(data: ByteArray): List<Chapter> {
        val segment = findChild(data, 0, data.size, ID_SEGMENT) ?: return emptyList()
        val chapters = findChild(data, segment.first, segment.second, ID_CHAPTERS) ?: return emptyList()

        val result = mutableListOf<Chapter>()
        var pos = chapters.first
        while (pos < chapters.second) {
            val element = readElement(data, pos, chapters.second) ?: break
            if (element.id == ID_EDITION) {
                collectAtoms(data, element.payloadStart, element.payloadEnd, result)
            }
            pos = element.next
        }
        return result.sortedBy { it.startMs }
    }

    private fun collectAtoms(data: ByteArray, start: Int, end: Int, out: MutableList<Chapter>) {
        var pos = start
        while (pos < end) {
            val element = readElement(data, pos, end) ?: return
            if (element.id == ID_CHAPTER_ATOM) parseAtom(data, element, out)
            pos = element.next
        }
    }

    /** One ChapterAtom: its own start/title plus any nested atoms. */
    private fun parseAtom(data: ByteArray, element: Element, out: MutableList<Chapter>) {
        var timeMs = -1L
        var title = ""
        var inner = element.payloadStart
        while (inner < element.payloadEnd) {
            val child = readElement(data, inner, element.payloadEnd) ?: break
            when (child.id) {
                ID_TIME_START -> {
                    val ns = readUint(data, child.payloadStart, child.payloadEnd)
                    if (ns >= 0) timeMs = ns / 1_000_000L
                }
                ID_DISPLAY -> title = readChapString(data, child.payloadStart, child.payloadEnd)
                ID_CHAPTER_ATOM -> parseAtom(data, child, out)
                else -> Unit
            }
            inner = child.next
        }
        if (timeMs >= 0) out.add(Chapter(timeMs, title))
    }

    private fun readChapString(data: ByteArray, start: Int, end: Int): String {
        var pos = start
        while (pos < end) {
            val child = readElement(data, pos, end) ?: break
            if (child.id == ID_CHAP_STRING) {
                return String(data, child.payloadStart, child.payloadEnd - child.payloadStart, Charsets.UTF_8)
            }
            pos = child.next
        }
        return ""
    }

    // ───────────────────────────── MP4 (chpl) ─────────────────────────────

    private fun parseMp4Chpl(data: ByteArray): List<Chapter> {
        val marker = byteArrayOf('c'.code.toByte(), 'h'.code.toByte(), 'p'.code.toByte(), 'l'.code.toByte())
        val at = indexOf(data, marker)
        if (at < 4) return emptyList()

        // size(4) 'chpl' version(1) flags(3) reserved(4) count(1) entries…
        var pos = at + 4
        if (pos + 9 > data.size) return emptyList()
        pos += 4 // version + flags
        pos += 4 // reserved
        var count = data[pos].toInt() and 0xFF
        pos += 1
        if (count == 0 && pos + 4 <= data.size) {
            // Some muxers write a 32-bit count instead of a byte.
            val wide = readUint(data, pos, pos + 4)
            if (wide in 1..9999) {
                count = wide.toInt()
                pos += 4
            }
        }

        val out = mutableListOf<Chapter>()
        repeat(count) {
            if (pos + 9 > data.size) return@repeat
            val start100ns = readUint(data, pos, pos + 8)
            pos += 8
            val length = data[pos].toInt() and 0xFF
            pos += 1
            if (length <= 0 || pos + length > data.size) return@repeat
            val title = String(data, pos, length, Charsets.UTF_8)
            pos += length
            if (start100ns >= 0) out.add(Chapter(start100ns / 10_000L, title))
        }
        return out
    }

    private fun indexOf(data: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..data.size - needle.size) {
            for (j in needle.indices) if (data[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    // ───────────────────────────── EBML ─────────────────────────────

    private class Element(
        val id: Long,
        val payloadStart: Int,
        val payloadEnd: Int,
        val next: Int
    )

    private fun readElement(data: ByteArray, pos: Int, limit: Int): Element? {
        if (pos >= limit) return null
        val (id, afterId) = readId(data, pos, limit) ?: return null
        val (size, afterSize) = readSize(data, afterId, limit) ?: return null
        val end = if (size < 0) limit else (afterSize + size).toInt().coerceAtMost(limit)
        if (end < afterSize) return null
        return Element(id, afterSize, end, end)
    }

    /** Finds an element with [want] among the children of the range. */
    private fun findChild(data: ByteArray, start: Int, end: Int, want: Long): Pair<Int, Int>? {
        var pos = start
        while (pos < end) {
            val element = readElement(data, pos, end) ?: return null
            if (element.id == want) return element.payloadStart to element.payloadEnd
            if (element.id == ID_SEGMENT && want != ID_SEGMENT) {
                val nested = findChild(data, element.payloadStart, element.payloadEnd, want)
                if (nested != null) return nested
            }
            pos = element.next
        }
        return null
    }

    private fun readId(data: ByteArray, pos: Int, limit: Int): Pair<Long, Int>? {
        if (pos >= limit) return null
        val first = data[pos].toInt() and 0xFF
        val length = when {
            first and 0x80 != 0 -> 1
            first and 0x40 != 0 -> 2
            first and 0x20 != 0 -> 3
            first and 0x10 != 0 -> 4
            else -> return null
        }
        if (pos + length > limit) return null
        var value = 0L
        for (i in 0 until length) value = (value shl 8) or (data[pos + i].toLong() and 0xFF)
        return value to (pos + length)
    }

    private fun readSize(data: ByteArray, pos: Int, limit: Int): Pair<Long, Int>? {
        if (pos >= limit) return null
        val first = data[pos].toInt() and 0xFF
        var length = 0
        for (bit in 7 downTo 0) {
            if (first and (1 shl bit) != 0) {
                length = 8 - bit
                break
            }
        }
        if (length == 0 || pos + length > limit) return null
        var value = (first and (0xFF shr length)).toLong()
        var unknown = value == (0xFF shr length).toLong()
        for (i in 1 until length) {
            val b = data[pos + i].toInt() and 0xFF
            if (b != 0xFF) unknown = false
            value = (value shl 8) or b.toLong()
        }
        return (if (unknown) -1L else value) to (pos + length)
    }

    private fun readUint(data: ByteArray, start: Int, end: Int): Long {
        if (start >= end) return -1
        if (end - start > 8) return -1
        var value = 0L
        for (i in start until end) value = (value shl 8) or (data[i].toLong() and 0xFF)
        return value
    }
}
