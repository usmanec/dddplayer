package top.rootu.dddplayer.logic

import org.junit.Assert.assertEquals
import org.junit.Test

class SeekStepLogicTest {

    @Test
    fun `first press of a series is at least 5 seconds`() {
        // 20-минутная серия: формула дала бы 0.75 сек
        assertEquals(5000, SeekStepLogic.step(1_200_000, 0))
        // 2-часовой фильм: 4.5 сек по формуле, но не меньше 5
        assertEquals(5000, SeekStepLogic.step(7_200_000, 0))
        // 3-часовой фильм: формула уже больше 5 сек, не урезаем
        assertEquals(6750, SeekStepLogic.step(10_800_000, 0))
    }

    @Test
    fun `following presses keep the duration-based formula`() {
        assertEquals(750, SeekStepLogic.step(1_200_000, 1))
        assertEquals(1500, SeekStepLogic.step(1_200_000, 5))
        assertEquals(3000, SeekStepLogic.step(1_200_000, 15))
        assertEquals(6000, SeekStepLogic.step(1_200_000, 30))
    }

    @Test
    fun `step stays between 1 and 60 seconds`() {
        // Очень короткий ролик: 250 мс по формуле, но итоговый шаг не меньше 0.5 сек
        assertEquals(500, SeekStepLogic.step(60_000, 1))
        // 60-е нажатие подряд: 10 сек * 16 = 160 сек, обрезаем до 60
        assertEquals(60000, SeekStepLogic.step(2_000_000_000, 60))
    }
}
