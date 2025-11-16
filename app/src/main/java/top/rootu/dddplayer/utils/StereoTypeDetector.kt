package top.rootu.dddplayer.utils

import android.net.Uri
import top.rootu.dddplayer.model.StereoType
import java.util.*

object StereoTypeDetector {

    fun detectStereoType(uri: Uri, metadata: Map<String, Any>? = null): StereoType {
        // 1. Попытка определить тип стереопары из метаданных (если есть)
        metadata?.let {
            when (it["stereo_type"] as? String) {
                "side_by_side" -> return StereoType.SIDE_BY_SIDE
                "top_bottom" -> return StereoType.TOP_BOTTOM
                else -> { /* Ничего не делаем, переходим к проверке имени файла */ }
            }
        }

        // 2. Попытка определить тип стереопары по имени файла
        val filename = uri.lastPathSegment?.lowercase(Locale.getDefault())
        filename?.let {
            if (it.contains("sbs") || it.contains("sidebyside")) {
                return StereoType.SIDE_BY_SIDE
            }
            if (it.contains("ou") || it.contains("topbottom") || it.contains("stereo_abl")) {
                return StereoType.TOP_BOTTOM
            }
        }

        // 3. Если не удалось определить, возвращаем NONE
        return StereoType.NONE
    }
}
