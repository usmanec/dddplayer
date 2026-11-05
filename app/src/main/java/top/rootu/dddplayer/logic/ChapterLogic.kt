package top.rootu.dddplayer.logic

/** Pure chapter navigation helpers, kept out of the Android view model for tests. */
object ChapterLogic {

    /** Vimu-compatible fallback step: files without chapters jump in 5-minute blocks. */
    const val FALLBACK_STEP_MS = 5 * 60 * 1000L

    /** Start of the first chapter strictly after [positionMs], or null on the last one. */
    fun nextChapterStart(chapters: List<Chapter>, positionMs: Long): Long? =
        chapters.firstOrNull { it.startMs > positionMs }?.startMs

    /**
     * Start of the chapter before [positionMs]. Inside a chapter this restarts
     * it; when already at its start it jumps to the previous chapter.
     */
    fun previousChapterStart(chapters: List<Chapter>, positionMs: Long): Long? =
        chapters.lastOrNull { it.startMs < positionMs }?.startMs

    /** Next 5-minute block for files without chapters; clamped to the duration when known. */
    fun nextChunk(positionMs: Long, durationMs: Long = 0L): Long {
        val next = (positionMs / FALLBACK_STEP_MS + 1) * FALLBACK_STEP_MS
        return if (durationMs > 0L) next.coerceAtMost(durationMs) else next
    }

    /** Previous 5-minute block for files without chapters; never negative. */
    fun previousChunk(positionMs: Long): Long =
        ((positionMs / FALLBACK_STEP_MS - 1) * FALLBACK_STEP_MS).coerceAtLeast(0L)
}
