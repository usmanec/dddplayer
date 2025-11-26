package top.rootu.dddplayer.model

/**
 * Типы входного стерео-формата видеофайла.
 */
enum class StereoInputType {
    NONE,
    SIDE_BY_SIDE,
    TOP_BOTTOM,
    INTERLACED,
    TILED_1080P
}

/**
 * Типы выходного рендеринга на экране.
 */
enum class StereoOutputMode {
    ANAGLYPH,       // Анаглиф
    LEFT_ONLY,      // Только левый глаз
    RIGHT_ONLY,     // Только правый глаз
    CARDBOARD_VR    // Side-by-side для VR-гарнитур
}