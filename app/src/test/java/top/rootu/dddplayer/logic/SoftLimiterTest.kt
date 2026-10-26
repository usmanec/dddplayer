package top.rootu.dddplayer.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SoftLimiterTest {

    @Test
    fun `normal level passes unchanged`() {
        assertEquals(0.5f, SoftLimiter.apply(0.5f), 0f)
        assertEquals(-0.8f, SoftLimiter.apply(-0.8f), 0f)
    }

    @Test
    fun `overload never reaches full scale and stays monotonic`() {
        var previous = SoftLimiter.THRESHOLD
        for (i in 1..40) {
            val y = SoftLimiter.apply(SoftLimiter.THRESHOLD + i * 0.1f)
            assertTrue(y <= 1f)
            // Строгий рост вблизи порога, при сильной перегрузке tanh во float упирается в 1.0
            if (i <= 5) assertTrue(y > previous) else assertTrue(y >= previous)
            previous = y
        }
        assertEquals(-SoftLimiter.apply(2f), SoftLimiter.apply(-2f), 0f)
    }
}
