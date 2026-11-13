package top.rootu.dddplayer.logic

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioBufferLogicTest {

    @Test
    fun capIsRoundedDownToWholeFrames() {
        // 6 каналов 16 бит = 12 байт: 262144 % 12 = 4, AudioTrack такой буфер отклонял
        assertEquals(262_140, AudioBufferLogic.capToWholeFrames(1_000_000, 12))
        // 6 каналов float = 24 байта
        assertEquals(262_128, AudioBufferLogic.capToWholeFrames(1_000_000, 24))
        for (frame in listOf(2, 4, 8, 10, 12, 16, 24, 32)) {
            assertEquals(0, AudioBufferLogic.capToWholeFrames(1_000_000, frame) % frame)
        }
    }

    @Test
    fun framesThatDivideTheCapAreNotShortened() {
        assertEquals(262_144, AudioBufferLogic.capToWholeFrames(1_000_000, 4)) // стерео 16 бит
        assertEquals(262_144, AudioBufferLogic.capToWholeFrames(1_000_000, 16)) // 7.1 16 бит
    }

    @Test
    fun smallerSizesAndUnknownFramesPass() {
        assertEquals(100_000, AudioBufferLogic.capToWholeFrames(100_000, 12)) // плеер уже округлил
        assertEquals(262_144, AudioBufferLogic.capToWholeFrames(1_000_000, 0)) // не PCM
    }
}
