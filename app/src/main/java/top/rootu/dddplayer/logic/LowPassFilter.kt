package top.rootu.dddplayer.logic

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * ФНЧ Баттерворта 2-го порядка (биквад по RBJ Audio EQ Cookbook) для одного канала.
 * Нужен созданному из фронтов LFE: в сабвуфер должен идти только бас.
 */
class LowPassFilter(sampleRate: Int, cutoffHz: Float = LFE_CUTOFF_HZ) {
    private val b0: Float
    private val b1: Float
    private val b2: Float
    private val a1: Float
    private val a2: Float
    private var x1 = 0f
    private var x2 = 0f
    private var y1 = 0f
    private var y2 = 0f

    init {
        val w0 = 2.0 * PI * cutoffHz / sampleRate
        val alpha = sin(w0) / (2.0 * (1.0 / sqrt(2.0)))
        val cosW0 = cos(w0)
        val a0 = 1.0 + alpha
        b0 = ((1.0 - cosW0) / 2.0 / a0).toFloat()
        b1 = ((1.0 - cosW0) / a0).toFloat()
        b2 = b0
        a1 = (-2.0 * cosW0 / a0).toFloat()
        a2 = ((1.0 - alpha) / a0).toFloat()
    }

    fun process(x: Float): Float {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = x
        y2 = y1
        y1 = y
        return y
    }

    fun reset() {
        x1 = 0f; x2 = 0f; y1 = 0f; y2 = 0f
    }

    companion object {
        /** Типичная граница LFE у ресиверов и AC3. */
        const val LFE_CUTOFF_HZ = 120f
    }
}
