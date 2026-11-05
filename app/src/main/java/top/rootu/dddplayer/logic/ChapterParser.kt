package top.rootu.dddplayer.logic

/**
 * Chapters embedded in the container (Matroska `Chapters`, MP4 `chpl`).
 *
 * Media3 does not expose them, so the player reads the header itself and shows
 * the result as seek points.
 */
data class Chapter(val startMs: Long, val title: String)

/** One Matroska `SeekHead` entry: target element id and offset from the Segment payload start. */
data class SeekEntry(val id: Long, val position: Long)

/** An EBML element header read from the stream. */
data class ElementHeader(val id: Long, val headerSize: Int, val payloadSize: Long?)

object ChapterParser {

    const val ID_SEGMENT = 0x18538067L
    const val ID_SEEK_HEAD = 0x114D9B74L
    const val ID_CHAPTERS = 0x1043A770L

    private const val ID_SEEK_ENTRY = 0x4DBBL
    private const val ID_SEEK_ID = 0x53ABL
    private const val ID_SEEK_POSITION = 0x53ACL
    private const val ID_EDITION = 0x45B9L
    private const val ID_CHAPTER_ATOM = 0xB6L
    private const val ID_TIME_START = 0x91L
    private const val ID_TIME_END = 0x92L
    private const val ID_DISPLAY = 0x80L
    private const val ID_CHAP_STRING = 0x85L

    private const val HEADER_BYTES = 32L
    private const val CHAPTER_HEAD_BYTES = 64L * 1024
    private const val MAX_CHAPTERS_BYTES = 8L * 1024 * 1024
    private const val SEEK_CHUNK = 256L * 1024
    private const val MAX_SEEK_HOPS = 4
    private const val MAX_WALK_ELEMENTS = 512

    /** Parses the first container header found in [data]. */
    fun parse(data: ByteArray): List<Chapter> {
        if (data.isEmpty()) return emptyList()
        val matroska = parseMatroska(data)
        if (matroska.isNotEmpty()) return matroska
        return parseMp4Chpl(data)
    }

    // ───────────────────────────── Matroska ─────────────────────────────

    private fun parseMatroska(data: ByteArray): List<Chapter> {
        val segment = findElement(data, 0, data.size, ID_SEGMENT) ?: return emptyList()
        val chapters = findElement(data, segment.payloadStart, segment.payloadEnd, ID_CHAPTERS)
            ?: return emptyList()
        return parseChaptersElement(data, chapters.start)
    }

    /** Parses a buffer that starts with a `Chapters` element. */
    fun parseChaptersElement(data: ByteArray, offset: Int = 0): List<Chapter> {
        val chapters = readElement(data, offset, data.size) ?: return emptyList()
        if (chapters.id != ID_CHAPTERS) return emptyList()

        val result = mutableListOf<Chapter>()
        var pos = chapters.payloadStart
        while (pos < chapters.payloadEnd) {
            val element = readElement(data, pos, chapters.payloadEnd) ?: break
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
                val raw = String(data, child.payloadStart, child.payloadEnd - child.payloadStart, Charsets.UTF_8)
                return repairMojibake(raw)
            }
            pos = child.next
        }
        return ""
    }

    // ───────────────────────────── SeekHead navigation ─────────────────────────────

    /** Absolute offset of the Segment element in [data], or null when there is no Segment. */
    fun segmentStart(data: ByteArray): Long? {
        val segment = findElement(data, 0, data.size, ID_SEGMENT) ?: return null
        return segment.start.toLong()
    }

    /** Absolute offset of the Segment payload in [data], or null when there is no Segment. */
    fun segmentPayloadStart(data: ByteArray): Long? {
        val segment = findElement(data, 0, data.size, ID_SEGMENT) ?: return null
        return segment.payloadStart.toLong()
    }

    /** Absolute offset of the Chapters element advertised by a SeekHead, if any. */
    fun findChaptersOffset(data: ByteArray): Long? {
        val segmentStart = segmentPayloadStart(data) ?: return null
        val entry = seekEntries(data).firstOrNull { it.id == ID_CHAPTERS } ?: return null
        return segmentStart + entry.position
    }

    /** All SeekHead entries found in the first buffer (several SeekHeads are allowed). */
    fun seekEntries(data: ByteArray): List<SeekEntry> {
        val segment = findElement(data, 0, data.size, ID_SEGMENT) ?: return emptyList()
        val out = mutableListOf<SeekEntry>()
        var pos = segment.payloadStart
        while (pos < segment.payloadEnd) {
            val element = readElement(data, pos, segment.payloadEnd) ?: break
            if (element.id == ID_SEEK_HEAD) parseSeekHead(data, element, out)
            pos = element.next
        }
        return out
    }

    /** Entries of a SeekHead element that starts at [offset] (used for secondary SeekHeads). */
    fun seekEntriesAt(data: ByteArray, offset: Int = 0): List<SeekEntry> {
        val element = readElement(data, offset, data.size) ?: return emptyList()
        if (element.id != ID_SEEK_HEAD) return emptyList()
        val out = mutableListOf<SeekEntry>()
        parseSeekHead(data, element, out)
        return out
    }

    private fun parseSeekHead(data: ByteArray, head: Element, out: MutableList<SeekEntry>) {
        var pos = head.payloadStart
        while (pos < head.payloadEnd) {
            val entry = readElement(data, pos, head.payloadEnd) ?: return
            if (entry.id == ID_SEEK_ENTRY) {
                var id: Long? = null
                var position: Long? = null
                var inner = entry.payloadStart
                while (inner < entry.payloadEnd) {
                    val child = readElement(data, inner, entry.payloadEnd) ?: break
                    when (child.id) {
                        ID_SEEK_ID -> id = readId(data, child.payloadStart, child.payloadEnd)?.first
                        ID_SEEK_POSITION -> position = readUint(data, child.payloadStart, child.payloadEnd)
                        else -> Unit
                    }
                    inner = child.next
                }
                val seekId = id
                val seekPos = position
                if (seekId != null && seekPos != null && seekPos >= 0) {
                    out.add(SeekEntry(seekId, seekPos))
                }
            }
            pos = entry.next
        }
    }

    /**
     * Loads chapters using SeekHead offsets, following secondary SeekHeads.
     *
     * [readAt] must return bytes starting at the requested absolute offset.
     */
    fun loadViaSeekHead(
        first: ByteArray,
        readAt: (offset: Long, length: Long) -> ByteArray?
    ): List<Chapter> {
        val segmentStart = segmentPayloadStart(first) ?: return emptyList()
        var entries = seekEntries(first)
        val visited = HashSet<Long>()

        repeat(MAX_SEEK_HOPS) {
            entries.firstOrNull { it.id == ID_CHAPTERS }?.let { entry ->
                return readChaptersAt(segmentStart + entry.position, readAt)
            }

            // Large files may keep the main index in a second SeekHead near the end.
            val next = entries.firstOrNull { it.id == ID_SEEK_HEAD } ?: return emptyList()
            val absolute = segmentStart + next.position
            if (!visited.add(absolute)) return emptyList()

            val chunk = readAt(absolute, SEEK_CHUNK) ?: return emptyList()
            entries = seekEntriesAt(chunk, 0)
        }
        return emptyList()
    }

    private fun readChaptersAt(
        offset: Long,
        readAt: (offset: Long, length: Long) -> ByteArray?
    ): List<Chapter> {
        val head = readAt(offset, CHAPTER_HEAD_BYTES) ?: return emptyList()
        val header = readHeader(head, 0) ?: return emptyList()
        if (header.id != ID_CHAPTERS) return emptyList()

        val payloadSize = header.payloadSize ?: return emptyList()
        val needed = header.headerSize.toLong() + payloadSize
        val full = when {
            needed <= head.size -> head
            needed > MAX_CHAPTERS_BYTES -> head
            else -> readAt(offset, needed) ?: head
        }
        return parseChaptersElement(full)
    }

    /**
     * Walks the top-level Segment elements like a full EBML parser does: reads only
     * element headers and skips payloads by their declared size. This finds
     * `Chapters` even when the file has no SeekHead or the entry is missing.
     */
    fun findChaptersByWalking(
        first: ByteArray,
        fileSize: Long,
        readAt: (offset: Long, length: Long) -> ByteArray?
    ): List<Chapter> {
        val segmentOffset = segmentStart(first) ?: return emptyList()
        val segmentHeader = readHeader(first, segmentOffset.toInt()) ?: return emptyList()
        val segmentPayload = segmentOffset + segmentHeader.headerSize
        val segmentEnd = segmentHeader.payloadSize
            ?.let { segmentPayload + it }
            ?: fileSize

        var pos = segmentPayload
        var hops = 0
        while (pos < segmentEnd && hops++ < MAX_WALK_ELEMENTS) {
            val header = if (pos < first.size) {
                readHeader(first, pos.toInt())
                    ?: readAt(pos, HEADER_BYTES)?.let { readHeader(it, 0) }
            } else {
                readAt(pos, HEADER_BYTES)?.let { readHeader(it, 0) }
            } ?: return emptyList()

            if (header.id == ID_CHAPTERS) {
                return readChaptersAt(pos, readAt)
            }

            val size = header.payloadSize ?: return emptyList()
            if (size < 0) return emptyList()
            val next = pos + header.headerSize + size
            if (next <= pos) return emptyList()
            pos = next
        }
        return emptyList()
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
            val title = repairMojibake(String(data, pos, length, Charsets.UTF_8))
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

    // ───────────────────────────── Mojibake ─────────────────────────────

    /**
     * Some muxers store chapter titles that were already decoded from UTF-8 as a
     * single-byte charset and encoded again ("ã€€ã€Œ…"). Undo that when the string
     * looks like double-encoded UTF-8.
     */
    internal fun repairMojibake(value: String): String {
        if (!looksLikeMojibake(value)) return value
        val bytes = encodeCp1252(value) ?: return value
        val fixed = String(bytes, Charsets.UTF_8)
        if (fixed.contains('\uFFFD')) return value
        if (mojibakeScore(fixed) >= mojibakeScore(value)) return value
        return fixed
    }

    private fun looksLikeMojibake(value: String): Boolean {
        val strong = value.any {
            it.code in 0x80..0x9F ||
                it in CP1252_SPECIALS ||
                it == '\u00C2' || it == '\u00C3' || it == '\u00E2' || it == '\u00E3'
        }
        return strong && mojibakeScore(value) >= 3
    }

    private fun mojibakeScore(value: String): Int {
        var score = 0
        for (i in value.indices) {
            val ch = value[i]
            val code = ch.code
            score += when {
                code in 0x80..0x9F -> 2 // C1 controls never appear in a real title
                code == 0xC2 || code == 0xC3 -> 2 // the usual UTF-8 lead bytes
                code in 0xC0..0xFF -> 1
                ch in CP1252_SPECIALS -> 1
                else -> 0
            }
            if (i > 0) {
                val prev = value[i - 1]
                val continuation = code in 0x80..0xBF || ch in CP1252_SPECIALS
                if (continuation && (prev == '\u00C2' || prev == '\u00C3' ||
                        prev == '\u00E2' || prev == '\u00E3')) {
                    score += 1
                }
            }
        }
        return score
    }

    /** Encodes a Windows-1252 mojibake string back to the bytes it was decoded from. */
    private fun encodeCp1252(value: String): ByteArray? {
        val out = ByteArray(value.length)
        for (i in value.indices) {
            out[i] = CP1252_BYTES[value[i]] ?: return null
        }
        return out
    }

    private val CP1252_SPECIALS: Set<Char> = hashSetOf(
        '\u20AC', '\u201A', '\u0192', '\u201E', '\u2026', '\u2020', '\u2021',
        '\u02C6', '\u2030', '\u0160', '\u2039', '\u0152', '\u017D', '\u2018',
        '\u2019', '\u201C', '\u201D', '\u2022', '\u2013', '\u2014', '\u02DC',
        '\u2122', '\u0161', '\u203A', '\u0153', '\u017E', '\u0178'
    )

    private val CP1252_BYTES: Map<Char, Byte> = HashMap<Char, Byte>().apply {
        // Latin-1 range, including the C1 controls that cp1252 leaves undefined.
        for (b in 0..0xFF) put(b.toChar(), b.toByte())
        put('\u20AC', 0x80.toByte())
        put('\u201A', 0x82.toByte())
        put('\u0192', 0x83.toByte())
        put('\u201E', 0x84.toByte())
        put('\u2026', 0x85.toByte())
        put('\u2020', 0x86.toByte())
        put('\u2021', 0x87.toByte())
        put('\u02C6', 0x88.toByte())
        put('\u2030', 0x89.toByte())
        put('\u0160', 0x8A.toByte())
        put('\u2039', 0x8B.toByte())
        put('\u0152', 0x8C.toByte())
        put('\u017D', 0x8E.toByte())
        put('\u2018', 0x91.toByte())
        put('\u2019', 0x92.toByte())
        put('\u201C', 0x93.toByte())
        put('\u201D', 0x94.toByte())
        put('\u2022', 0x95.toByte())
        put('\u2013', 0x96.toByte())
        put('\u2014', 0x97.toByte())
        put('\u02DC', 0x98.toByte())
        put('\u2122', 0x99.toByte())
        put('\u0161', 0x9A.toByte())
        put('\u203A', 0x9B.toByte())
        put('\u0153', 0x9C.toByte())
        put('\u017E', 0x9E.toByte())
        put('\u0178', 0x9F.toByte())
    }

    // ───────────────────────────── EBML ─────────────────────────────

    private class Element(
        val id: Long,
        val start: Int,
        val payloadStart: Int,
        val payloadEnd: Int,
        val next: Int
    )

    private fun readElement(data: ByteArray, pos: Int, limit: Int): Element? {
        if (pos >= limit) return null
        val (id, afterId) = readId(data, pos, limit) ?: return null
        val (size, afterSize) = readSize(data, afterId, limit) ?: return null
        val end = if (size < 0) {
            limit
        } else {
            val value = afterSize.toLong() + size
            if (value < afterSize || value > limit) limit else value.toInt()
        }
        return Element(id, pos, afterSize, end, end)
    }

    /** Reads just the element header, keeping the declared size even when it exceeds [data]. */
    fun readHeader(data: ByteArray, offset: Int = 0): ElementHeader? {
        if (offset < 0 || offset >= data.size) return null
        val (id, afterId) = readId(data, offset, data.size) ?: return null
        val (size, afterSize) = readSize(data, afterId, data.size) ?: return null
        return ElementHeader(id, afterSize - offset, if (size < 0) null else size)
    }

    /** Finds an element with [want] among the children of the range. */
    private fun findElement(data: ByteArray, start: Int, end: Int, want: Long): Element? {
        var pos = start
        while (pos < end) {
            val element = readElement(data, pos, end) ?: return null
            if (element.id == want) return element
            if (element.id == ID_SEGMENT && want != ID_SEGMENT) {
                findElement(data, element.payloadStart, element.payloadEnd, want)?.let { return it }
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
