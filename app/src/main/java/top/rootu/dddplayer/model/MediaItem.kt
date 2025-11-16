package top.rootu.dddplayer.model

import android.net.Uri

data class MediaItem(
    val uri: Uri,
    val title: String? = null,
    val isStereo: Boolean = false,
    val stereoType: StereoType = StereoType.NONE
)

enum class StereoType {
    NONE,
    SIDE_BY_SIDE, // Горизонтальная стереопара
    TOP_BOTTOM    // Вертикальная стереопара
}
