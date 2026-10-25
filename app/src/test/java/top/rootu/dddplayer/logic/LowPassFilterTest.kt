package top.rootu.dddplayer.logic

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

class LowPassFilterTest {

    /** Амплитуда синуса после фильтра (после установления). */
    private fun gainAt(frequency: Float): Float {
        val rate = 48_000
        val filter = LowPassFilter(rate)
        var peak = 0f
        for (n in 0 until rate) {
            val y = filter.process(sin(2.0 * PI * frequency * n / rate).toFloat())
            if (n > rate / 2) peak = max(peak, abs(y))
        }
        return peak
    }

    @Test
    fun `bass passes and mids are cut`() {
        assertTrue(gainAt(40f) > 0.95f)
        // Срез -3 дБ на 120 Гц
        assertTrue(abs(gainAt(120f) - 0.7071f) < 0.02f)
        // 1 кГц: 2-й порядок, около -37 дБ
        assertTrue(gainAt(1000f) < 0.02f)
    }
}
