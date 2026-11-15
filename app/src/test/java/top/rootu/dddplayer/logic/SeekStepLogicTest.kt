package top.rootu.dddplayer.logic

import org.junit.Assert.assertEquals
import org.junit.Test

class SeekStepLogicTest {

    @Test
    fun `single press uses the configured step`() {
        assertEquals(5000, SeekStepLogic.step(7_200_000, 0))
        assertEquals(500, SeekStepLogic.step(7_200_000, 0, 500))
        assertEquals(60000, SeekStepLogic.step(7_200_000, 0, 60000))
        // Длина видео на нажатие не влияет
        assertEquals(5000, SeekStepLogic.step(10_800_000, 0))
    }

    @Test
    fun `holding the key uses the duration formula and accelerates`() {
        // 20-минутная серия: базовый шаг 0.75 сек, настройка шага на удержание не влияет
        assertEquals(750, SeekStepLogic.step(1_200_000, 1, 30000))
        assertEquals(1500, SeekStepLogic.step(1_200_000, 5))
        assertEquals(3000, SeekStepLogic.step(1_200_000, 15))
        assertEquals(6000, SeekStepLogic.step(1_200_000, 30))
        assertEquals(12000, SeekStepLogic.step(1_200_000, 50))
        // 10 сек * 16 = 160 сек, обрезаем до 60
        assertEquals(60000, SeekStepLogic.step(2_000_000_000, 60))
    }

    @Test
    fun `steps list goes from half a second to a minute`() {
        val steps = SeekStepLogic.STEPS
        assertEquals(listOf(500) + (1..60).map { it * 1000 }, steps)
        assertEquals(5000, SeekStepLogic.DEFAULT_STEP_MS)
    }
}
