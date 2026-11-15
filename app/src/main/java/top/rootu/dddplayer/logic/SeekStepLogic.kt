package top.rootu.dddplayer.logic

/** Шаг перемотки стрелками по сикбару. */
object SeekStepLogic {

    /**
     * @param durationMs длина видео
     * @param accelerationCount номер нажатия в серии (0 — первое)
     * @return шаг в миллисекундах, всегда ≥ 1 сек
     */
    fun step(durationMs: Long, accelerationCount: Int): Long {
        // Базовый шаг зависит от длины видео (для 2-часового фильма 4.5 сек)
        val baseStep = (durationMs / 200 / 8).coerceIn(250, 10000)

        // Первое нажатие серии — минимум 5 сек: на сериях в ~20 минут формула дает
        // 0.75 сек, и в оверлее видно "-0s". Дальше шаг считается по формуле.
        if (accelerationCount == 0) return baseStep.coerceAtLeast(5000)

        val multiplier = when {
            accelerationCount < 5 -> 1
            accelerationCount < 15 -> 2
            accelerationCount < 30 -> 4
            accelerationCount < 50 -> 8
            else -> 16
        }
        return (baseStep * multiplier).coerceIn(500, 60000)
    }
}
