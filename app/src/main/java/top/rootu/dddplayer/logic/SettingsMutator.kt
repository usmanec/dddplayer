package top.rootu.dddplayer.logic

object SettingsMutator {

    /**
     * Циклический перебор Enum значений.
     */
    inline fun <reified T : Enum<T>> cycleEnum(current: T, direction: Int): T {
        val values = enumValues<T>()
        val nextOrd = (current.ordinal + direction + values.size) % values.size
        return values[nextOrd]
    }

    /**
     * Циклический перебор списка.
     */
    fun <T> cycleList(current: T, list: List<T>, direction: Int): T {
        if (list.isEmpty()) return current
        val idx = list.indexOf(current)
        if (idx == -1) return list.first()
        val nextIdx = (idx + direction + list.size) % list.size
        return list[nextIdx]
    }

    /**
     * Изменение Float значения с шагом и границами.
     */
    fun modifyFloat(current: Float, direction: Int, step: Float, min: Float, max: Float): Float {
        return (current + direction * step).coerceIn(min, max)
    }

    /**
     * Изменение Int значения с шагом и границами.
     */
    fun modifyInt(current: Int, direction: Int, step: Int = 1, min: Int, max: Int): Int {
        return (current + direction * step).coerceIn(min, max)
    }
}