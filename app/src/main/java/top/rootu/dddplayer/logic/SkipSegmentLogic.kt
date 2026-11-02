package top.rootu.dddplayer.logic

import top.rootu.dddplayer.model.SkipSegment

/**
 * Отрезки для кнопки «Пропустить» (ключи Intent `segments` и `video_list.segments`).
 * Формат: `intro:0-58867;recap:95000-259000;credits:3395489-` (мс; без конца - до конца видео).
 */
object SkipSegmentLogic {
    // Титры, которые кончаются в последние 15 с видео, считаются идущими до конца
    private const val END_SLACK_MS = 15_000L
    // Заставка не дальше 3 с от конца пересказа идет сразу за ним; дальше - сцена серии между ними
    private const val JOIN_GAP_MS = 3_000L
    private val SEGMENT = Regex("""^\s*(intro|recap|credits)\s*:\s*(\d+)\s*-\s*(\d*)\s*$""")

    fun parse(text: String?): List<SkipSegment> = text.orEmpty().split(';').mapNotNull { part ->
        val m = SEGMENT.find(part) ?: return@mapNotNull null
        val kind = SkipSegment.Kind.valueOf(m.groupValues[1].uppercase())
        val start = m.groupValues[2].toLongOrNull() ?: return@mapNotNull null
        val end = m.groupValues[3].ifEmpty { null }?.let { it.toLongOrNull() ?: return@mapNotNull null }
        when {
            end != null && end <= start -> null
            end == null && kind != SkipSegment.Kind.CREDITS -> null // заставка без конца - некуда прыгать
            else -> SkipSegment(kind, start, end)
        }
    }

    /** Отрезок, в котором сейчас позиция, или null. */
    fun active(segments: List<SkipSegment>, positionMs: Long): SkipSegment? =
        segments.firstOrNull { positionMs >= it.startMs && positionMs < (it.endMs ?: Long.MAX_VALUE) }

    /** Титры идут до конца видео: после них - следующий элемент плейлиста или выход. */
    fun runsToEnd(segment: SkipSegment, durationMs: Long): Boolean =
        segment.endMs == null || (durationMs > 0 && segment.endMs >= durationMs - END_SLACK_MS)

    /** Начало финальных титров, идущих до конца видео (для «Закончится в»), или null. */
    fun creditsStart(segments: List<SkipSegment>, durationMs: Long): Long? =
        segments.firstOrNull { it.kind == SkipSegment.Kind.CREDITS && runsToEnd(it, durationMs) }?.startMs

    /**
     * Заставка сразу за пересказом [segment], или null: «Пропустить пересказ и заставку» прыгает за обе (кто
     * пропускает пересказ, почти никогда не смотрит заставку, а иначе через секунду нажимать еще раз).
     */
    fun introAfter(segment: SkipSegment, segments: List<SkipSegment>): SkipSegment? {
        val end = segment.endMs?.takeIf { segment.kind == SkipSegment.Kind.RECAP } ?: return null
        return segments.firstOrNull {
            it.kind == SkipSegment.Kind.INTRO && it.endMs != null && it.startMs in end - JOIN_GAP_MS..end + JOIN_GAP_MS
        }
    }

    /**
     * Куда перематывать: конец отрезка (пересказа - вместе с заставкой сразу за ним), для титров до конца - конец
     * видео (null, если длительность неизвестна).
     */
    fun target(segment: SkipSegment, durationMs: Long, segments: List<SkipSegment> = emptyList()): Long? =
        if (runsToEnd(segment, durationMs)) durationMs.takeIf { it > 0 }
        else introAfter(segment, segments)?.endMs ?: segment.endMs
}
