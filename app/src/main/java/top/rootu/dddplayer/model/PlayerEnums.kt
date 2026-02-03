package top.rootu.dddplayer.model

/**
 * Доступные режимы масштабирования видео.
 */
enum class ResizeMode {
    FIT,            // Вписать (по умолчанию, RESIZE_MODE_FIT)
    ZOOM,           // Заполнить (Обрезать, RESIZE_MODE_ZOOM)
    SCALE,          // Пользовательское увеличение (Ручное масштабирование поверх FIT)
    FILL            // Растянуть (Игнорирует пропорции, RESIZE_MODE_FILL)
}

/**
 * Доступные скорости воспроизведения.
 */
enum class PlaybackSpeed(val value: Float, val label: String) {
    X0_50(0.50f, "0.50x"),
    X0_75(0.75f, "0.75x"),
    X1_00(1.00f, "1.00x"),
    X1_25(1.25f, "1.25x"),
    X1_50(1.50f, "1.50x"),
    X1_75(1.75f, "1.75x"),
    X2_00(2.00f, "2.00x"),
    X2_25(2.25f, "2.25x"),
    X2_50(2.50f, "2.50x"),
    X2_75(2.75f, "2.75x"),
    X3_00(3.00f, "3.00x");

    companion object {
        fun fromValue(value: Float) = entries.firstOrNull { it.value == value } ?: X1_00
    }
}