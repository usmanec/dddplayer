package top.rootu.dddplayer.logic

import org.junit.Assert.assertEquals
import org.junit.Test
import top.rootu.dddplayer.logic.ChannelLayoutLogic.Layout
import top.rootu.dddplayer.logic.ChannelLayoutLogic.Position

class ChannelLayoutLogicTest {

    private fun coef(matrix: FloatArray, input: List<Position>, output: Layout, from: Position, to: Position): Float =
        matrix[input.indexOf(from) * output.channelCount + output.positions.indexOf(to)]

    @Test
    fun `51 to 51 is identity`() {
        val input = ChannelLayoutLogic.inputPositions(6)!!
        val m = ChannelLayoutLogic.mixMatrix(input, Layout.SURROUND_51, 1f)
        for (i in input.indices) for (o in 0 until 6) {
            assertEquals(if (i == o) 1f else 0f, m[i * 6 + o], 1e-6f)
        }
    }

    @Test
    fun `center gain boosts only the center`() {
        val input = ChannelLayoutLogic.inputPositions(6)!!
        val gain = ChannelLayoutLogic.dbToGain(6)
        val m = ChannelLayoutLogic.mixMatrix(input, Layout.SURROUND_51, gain)
        assertEquals(gain, coef(m, input, Layout.SURROUND_51, Position.FC, Position.FC), 1e-6f)
        assertEquals(1f, coef(m, input, Layout.SURROUND_51, Position.FL, Position.FL), 1e-6f)
        assertEquals(1.995f, gain, 0.01f)
    }

    @Test
    fun `51 to quad folds center and lfe into the fronts`() {
        val input = ChannelLayoutLogic.inputPositions(6)!!
        val m = ChannelLayoutLogic.mixMatrix(input, Layout.QUAD, 1f)
        val fl = coef(m, input, Layout.QUAD, Position.FL, Position.FL)
        val fc = coef(m, input, Layout.QUAD, Position.FC, Position.FL)
        val lfe = coef(m, input, Layout.QUAD, Position.LFE, Position.FL)
        // Нормировка: сумма вкладов в FL = 1
        assertEquals(1f, fl + fc + lfe, 1e-5f)
        assertEquals(0f, coef(m, input, Layout.QUAD, Position.FC, Position.BL), 1e-6f)
        assertEquals(1f, coef(m, input, Layout.QUAD, Position.BL, Position.BL), 1e-6f)
    }

    @Test
    fun `quad to 51 builds the center from the fronts`() {
        val input = ChannelLayoutLogic.inputPositions(4)!!
        val m = ChannelLayoutLogic.mixMatrix(input, Layout.SURROUND_51, 1f)
        assertEquals(0.5f, coef(m, input, Layout.SURROUND_51, Position.FL, Position.FC), 1e-6f)
        assertEquals(0.5f, coef(m, input, Layout.SURROUND_51, Position.FR, Position.FC), 1e-6f)
        assertEquals(1f, coef(m, input, Layout.SURROUND_51, Position.BL, Position.BL), 1e-6f)
        // LFE не выдумываем
        for (i in input.indices) assertEquals(0f, m[i * 6 + 3], 1e-6f)
    }

    @Test
    fun `71 to 51 shares the rear between back and side`() {
        val input = ChannelLayoutLogic.inputPositions(8)!!
        val m = ChannelLayoutLogic.mixMatrix(input, Layout.SURROUND_51, 1f)
        val back = coef(m, input, Layout.SURROUND_51, Position.BL, Position.BL)
        val side = coef(m, input, Layout.SURROUND_51, Position.SL, Position.BL)
        assertEquals(back, side, 1e-6f)
        assertEquals(1f, back + side, 1e-5f)
    }

    @Test
    fun `auto layout keeps the source shape up to 51`() {
        assertEquals(Layout.STEREO, ChannelLayoutLogic.targetLayout(2, ChannelLayoutLogic.LAYOUT_AUTO))
        assertEquals(Layout.QUAD, ChannelLayoutLogic.targetLayout(4, ChannelLayoutLogic.LAYOUT_AUTO))
        assertEquals(Layout.SURROUND_51, ChannelLayoutLogic.targetLayout(6, ChannelLayoutLogic.LAYOUT_AUTO))
        assertEquals(Layout.SURROUND_51, ChannelLayoutLogic.targetLayout(8, ChannelLayoutLogic.LAYOUT_AUTO))
        assertEquals(Layout.QUAD, ChannelLayoutLogic.targetLayout(6, ChannelLayoutLogic.LAYOUT_40))
    }
}
