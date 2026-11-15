package top.rootu.dddplayer.logic

/** Шаг перемотки стрелками по сикбару. */
object SeekStepLogic {

    /** Выбор шага одного нажатия: 0.5 с, затем от 1 до 60 с по секунде. */
    val STEPS: List<Int> = listOf(500) + (1..60).map { it * 1000 }

    const val DEFAULT_STEP_MS = 5000

    /**
     * @param durationMs длина видео
     * @param holdRepeat номер события удержания: 0 — обычное нажатие, больше — автоповтор
     * @param pressStepMs шаг одного нажатия из настроек
     * @return шаг в миллисекундах
     */
    fun step(durationMs: Long, holdRepeat: Int, pressStepMs: Int = DEFAULT_STEP_MS): Long {
        // Одиночное нажатие — выбранный в настройках шаг
        if (holdRepeat == 0) return pressStepMs.toLong()

        // Удержание: шаг от длины видео (для 2-часового фильма 4.5 сек), растет с автоповтором
        val baseStep = (durationMs / 200 / 8).coerceIn(250, 10000)
        val multiplier = when {
            holdRepeat < 5 -> 1
            holdRepeat < 15 -> 2
            holdRepeat < 30 -> 4
            holdRepeat < 50 -> 8
            else -> 16
        }
        return (baseStep * multiplier).coerceIn(500, 60000)
    }
}
