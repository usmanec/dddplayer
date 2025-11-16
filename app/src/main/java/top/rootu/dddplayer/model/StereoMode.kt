package top.rootu.dddplayer.model

/**
 * Типы входного стерео-формата видеофайла.
 */
enum class StereoInputType {
    NONE,                   // Обычное 2D видео
    SIDE_BY_SIDE,           // Параллельная пара (Левый/Правый)
    SIDE_BY_SIDE_CROSSED,   // Перекрестная пара (Правый/Левый)
    TOP_BOTTOM,             // Вертикальная пара (Левый/Правый)
    TOP_BOTTOM_REVERSED,    // Вертикальная пара (Правый/Левый)
    INTERLACED,             // Чересстрочный
    TILED_1080P             // 3D Tiles Format/3DZ Tiles Format
}

/**
 * Типы выходного рендеринга на экране.
 */
enum class StereoOutputMode {
    ANAGLYPH,       // Анаглиф (красно-синие очки)
    LEFT_ONLY,      // Только левый глаз
    RIGHT_ONLY,     // Только правый глаз
    CARDBOARD_VR    // Side-by-side для VR-гарнитур
}