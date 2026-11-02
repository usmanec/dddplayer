package top.rootu.dddplayer.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.rootu.dddplayer.model.SkipSegment
import top.rootu.dddplayer.model.SkipSegment.Kind.CREDITS
import top.rootu.dddplayer.model.SkipSegment.Kind.INTRO
import top.rootu.dddplayer.model.SkipSegment.Kind.RECAP

class SkipSegmentLogicTest {

    @Test
    fun `parse keeps intro, recap and credits, credits may run to the end`() {
        assertEquals(
            listOf(SkipSegment(INTRO, 0, 58867), SkipSegment(RECAP, 95000, 259000), SkipSegment(CREDITS, 3395489, null)),
            SkipSegmentLogic.parse("intro:0-58867;recap:95000-259000;credits:3395489-")
        )
    }

    @Test
    fun `parse drops what it can't use`() {
        assertEquals(emptyList<SkipSegment>(), SkipSegmentLogic.parse(null))
        assertEquals(emptyList<SkipSegment>(), SkipSegmentLogic.parse(""))
        // неизвестный вид, конец раньше начала, заставка без конца, мусор
        assertEquals(
            listOf(SkipSegment(CREDITS, 100, 200)),
            SkipSegmentLogic.parse("preview:0-10;intro:50-40;intro:5-;credits:100-200;x")
        )
    }

    @Test
    fun `active segment by position, the end is outside`() {
        val segments = SkipSegmentLogic.parse("intro:0-58867;credits:3395489-")
        assertEquals(INTRO, SkipSegmentLogic.active(segments, 0)?.kind)
        assertEquals(INTRO, SkipSegmentLogic.active(segments, 58866)?.kind)
        assertNull(SkipSegmentLogic.active(segments, 58867))
        assertEquals(CREDITS, SkipSegmentLogic.active(segments, 3_400_000)?.kind)
    }

    @Test
    fun `skip goes to the segment's end, credits near the end go to the end of the video`() {
        val duration = 3_452_000L
        assertEquals(58867L, SkipSegmentLogic.target(SkipSegment(INTRO, 0, 58867), duration))
        // IntroDB: титры до 3451 с при длительности 3452 с - это конец видео
        val credits = SkipSegment(CREDITS, 3_390_000, 3_451_000)
        assertTrue(SkipSegmentLogic.runsToEnd(credits, duration))
        assertEquals(duration, SkipSegmentLogic.target(credits, duration))
        // сцена после титров: только до конца титров
        val beforeScene = SkipSegment(CREDITS, 6_000_000, 6_300_000)
        assertFalse(SkipSegmentLogic.runsToEnd(beforeScene, 6_500_000))
        assertEquals(6_300_000L, SkipSegmentLogic.target(beforeScene, 6_500_000))
        // длительность неизвестна: некуда
        assertNull(SkipSegmentLogic.target(SkipSegment(CREDITS, 100, null), -9223372036854775807L))
    }

    @Test
    fun `credits start for "ends at" only when they run to the end`() {
        val segments = SkipSegmentLogic.parse("intro:32000-111000;credits:3390000-3451000")
        assertEquals(3_390_000L, SkipSegmentLogic.creditsStart(segments, 3_452_000))
        assertNull(SkipSegmentLogic.creditsStart(SkipSegmentLogic.parse("credits:6000000-6300000"), 6_500_000))
        assertNull(SkipSegmentLogic.creditsStart(emptyList(), 3_452_000))
    }

    @Test
    fun `a recap with the intro right after it skips both, a cold open between keeps them apart`() {
        val foundation = SkipSegmentLogic.parse("recap:0-31900;intro:31900-113900;credits:3390000-")
        val recap = foundation[0]
        assertEquals(INTRO, SkipSegmentLogic.introAfter(recap, foundation)?.kind)
        assertEquals(113900L, SkipSegmentLogic.target(recap, 3_452_000, foundation))
        assertEquals("the intro alone", 113900L, SkipSegmentLogic.target(foundation[1], 3_452_000, foundation))
        // 2 s of black between them still counts; a 40 s cold open doesn't
        val gap = SkipSegmentLogic.parse("recap:0-30000;intro:32000-90000")
        assertEquals(90000L, SkipSegmentLogic.target(gap[0], 3_000_000, gap))
        val coldOpen = SkipSegmentLogic.parse("recap:0-30000;intro:70000-120000")
        assertNull(SkipSegmentLogic.introAfter(coldOpen[0], coldOpen))
        assertEquals(30000L, SkipSegmentLogic.target(coldOpen[0], 3_000_000, coldOpen))
        assertNull("only a recap joins", SkipSegmentLogic.introAfter(foundation[1], foundation))
    }
}
