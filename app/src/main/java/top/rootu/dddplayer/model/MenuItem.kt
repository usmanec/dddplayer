package top.rootu.dddplayer.model

import androidx.annotation.DrawableRes

data class MenuItem(
    val id: String,
    val title: String,
    val description: String? = null,
    @param:DrawableRes val iconRes: Int? = null,
    val isSelected: Boolean = false,
    val data: Any? = null // Универсальное поле для передачи данных (например, TrackOption)
)