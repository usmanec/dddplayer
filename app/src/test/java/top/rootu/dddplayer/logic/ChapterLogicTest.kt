package top.rootu.dddplayer.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChapterLogicTest {

    private val chapters = listOf(
        Chapter(0, "Intro"),
        Chapter(60_000, "Part 1"),
        Chapter(120_000, "Part 2")
    )

    @Test
    fun `next chapter is the first one after the current position`() {
        assertEquals(60_000L, ChapterLogic.nextChapterStart(chapters, 30_000))
        assertEquals(60_000L, ChapterLogic.nextChapterStart(chapters, 59_999))
    }

    @Test
    fun `current chapter start is skipped`() {
        assertEquals(120_000L, ChapterLogic.nextChapterStart(chapters, 60_000))
    }

    @Test
    fun `there is no next chapter after the last one`() {
        assertNull(ChapterLogic.nextChapterStart(chapters, 120_000))
        assertNull(ChapterLogic.nextChapterStart(chapters, 999_999))
    }

    @Test
    fun `previous chapter restarts the current one`() {
        assertEquals(60_000L, ChapterLogic.previousChapterStart(chapters, 90_000))
        assertEquals(0L, ChapterLogic.previousChapterStart(chapters, 60_000))
        assertEquals(0L, ChapterLogic.previousChapterStart(chapters, 1))
        assertNull(ChapterLogic.previousChapterStart(chapters, 0))
    }

    @Test
    fun `empty chapter list has no next or previous`() {
        assertNull(ChapterLogic.nextChapterStart(emptyList(), 0))
        assertNull(ChapterLogic.previousChapterStart(emptyList(), 0))
    }

    @Test
    fun `fallback next chunk is the next five minute boundary`() {
        assertEquals(300_000L, ChapterLogic.nextChunk(0))
        assertEquals(300_000L, ChapterLogic.nextChunk(1))
        assertEquals(300_000L, ChapterLogic.nextChunk(299_999))
        assertEquals(600_000L, ChapterLogic.nextChunk(300_000))
        assertEquals(1_541_236L, ChapterLogic.nextChunk(1_540_000, 1_541_236))
    }

    @Test
    fun `fallback previous chunk is the previous five minute boundary`() {
        assertEquals(0L, ChapterLogic.previousChunk(0))
        assertEquals(0L, ChapterLogic.previousChunk(299_999))
        assertEquals(0L, ChapterLogic.previousChunk(300_000))
        assertEquals(300_000L, ChapterLogic.previousChunk(600_000))
    }
}
