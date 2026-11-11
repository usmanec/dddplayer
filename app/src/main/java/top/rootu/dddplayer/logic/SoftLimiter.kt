package top.rootu.dddplayer.logic

import kotlin.math.abs
import kotlin.math.tanh

/**
 * Мягкий ограничитель после микшера: усиление голоса до +12 дБ и прежний уровень даунмикса легко
 * выходят за 0 дБ, а жесткая обрезка дает хрип. До [THRESHOLD] сигнал не меняется, выше плавно
 * (tanh, без излома) подходит к 1.0 и никогда ее не превышает.
 */
object SoftLimiter {
    /** Порог (-2 дБ): обычный уровень проходит без изменений. */
    const val THRESHOLD = 0.8f
    private const val KNEE = 1f - THRESHOLD

    fun apply(x: Float): Float {
        val a = abs(x)
        if (a <= THRESHOLD) return x
        val limited = THRESHOLD + KNEE * tanh((a - THRESHOLD) / KNEE)
        return if (x < 0f) -limited else limited
    }
}
