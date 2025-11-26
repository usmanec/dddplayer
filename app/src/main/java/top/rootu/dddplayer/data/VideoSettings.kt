package top.rootu.dddplayer.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import top.rootu.dddplayer.model.StereoInputType
import top.rootu.dddplayer.model.StereoOutputMode
import top.rootu.dddplayer.renderer.StereoRenderer

@Entity(tableName = "video_settings")
data class VideoSettings(
    @PrimaryKey val uri: String,
    val lastUpdated: Long,
    val inputType: StereoInputType,
    val outputMode: StereoOutputMode,
    val anaglyphType: StereoRenderer.AnaglyphType,
    val swapEyes: Boolean,
    val depth: Int
)