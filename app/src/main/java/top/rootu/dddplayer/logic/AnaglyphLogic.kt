package top.rootu.dddplayer.logic

import android.graphics.Color
import top.rootu.dddplayer.renderer.StereoRenderer
import top.rootu.dddplayer.utils.DuboisMath
import top.rootu.dddplayer.viewmodel.GlassesGroup

object AnaglyphLogic {

    fun getGlassesGroup(type: StereoRenderer.AnaglyphType): GlassesGroup {
        return when {
            type == StereoRenderer.AnaglyphType.RC_CUSTOM -> GlassesGroup.RED_CYAN
            type == StereoRenderer.AnaglyphType.YB_CUSTOM -> GlassesGroup.YELLOW_BLUE
            type == StereoRenderer.AnaglyphType.GM_CUSTOM -> GlassesGroup.GREEN_MAGENTA
            type.name.startsWith("RC_") -> GlassesGroup.RED_CYAN
            type.name.startsWith("YB_") -> GlassesGroup.YELLOW_BLUE
            type.name.startsWith("GM_") -> GlassesGroup.GREEN_MAGENTA
            type.name.startsWith("RB_") -> GlassesGroup.RED_BLUE
            else -> GlassesGroup.RED_CYAN
        }
    }

    fun getFiltersForGroup(group: GlassesGroup): List<StereoRenderer.AnaglyphType> {
        return when (group) {
            GlassesGroup.RED_CYAN -> listOf(
                StereoRenderer.AnaglyphType.RC_DUBOIS, StereoRenderer.AnaglyphType.RC_COLOR,
                StereoRenderer.AnaglyphType.RC_HALF_COLOR, StereoRenderer.AnaglyphType.RC_OPTIMIZED,
                StereoRenderer.AnaglyphType.RC_MONO, StereoRenderer.AnaglyphType.RC_CUSTOM
            )
            GlassesGroup.YELLOW_BLUE -> listOf(
                StereoRenderer.AnaglyphType.YB_DUBOIS, StereoRenderer.AnaglyphType.YB_COLOR,
                StereoRenderer.AnaglyphType.YB_HALF_COLOR, StereoRenderer.AnaglyphType.YB_MONO,
                StereoRenderer.AnaglyphType.YB_CUSTOM
            )
            GlassesGroup.GREEN_MAGENTA -> listOf(
                StereoRenderer.AnaglyphType.GM_DUBOIS, StereoRenderer.AnaglyphType.GM_COLOR,
                StereoRenderer.AnaglyphType.GM_HALF_COLOR, StereoRenderer.AnaglyphType.GM_MONO,
                StereoRenderer.AnaglyphType.GM_CUSTOM
            )
            GlassesGroup.RED_BLUE -> listOf(StereoRenderer.AnaglyphType.RB_MONO)
        }
    }

    fun isCustomType(type: StereoRenderer.AnaglyphType) = type.name.endsWith("_CUSTOM")

    fun getCustomPrefix(type: StereoRenderer.AnaglyphType): String {
        return when (type) {
            StereoRenderer.AnaglyphType.RC_CUSTOM -> "rc_"
            StereoRenderer.AnaglyphType.YB_CUSTOM -> "yb_"
            StereoRenderer.AnaglyphType.GM_CUSTOM -> "gm_"
            else -> "rc_"
        }
    }

    fun getBaseColors(type: StereoRenderer.AnaglyphType): Pair<Int, Int> {
        return when (type) {
            StereoRenderer.AnaglyphType.RC_CUSTOM -> Pair(Color.RED, Color.CYAN)
            StereoRenderer.AnaglyphType.YB_CUSTOM -> Pair(Color.YELLOW, Color.BLUE)
            StereoRenderer.AnaglyphType.GM_CUSTOM -> Pair(Color.GREEN, Color.MAGENTA)
            else -> Pair(Color.RED, Color.CYAN)
        }
    }

    fun applyHueOffset(baseColor: Int, offset: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(baseColor, hsv)
        // Offset -100..100. 1 unit = ~0.6 degrees. Range +/- ~60 degrees.
        val shift = offset * 0.5940594f
        hsv[0] = (hsv[0] + shift + 360f) % 360f
        return Color.HSVToColor(hsv)
    }

    fun calculateMatrix(
        type: StereoRenderer.AnaglyphType,
        hueL: Int, hueR: Int,
        leakL: Float, leakR: Float,
        useLms: Boolean
    ): DuboisMath.ResultMatrices {
        if (isCustomType(type)) {
            val (baseL, baseR) = getBaseColors(type)
            val finalL = applyHueOffset(baseL, hueL)
            val finalR = applyHueOffset(baseR, hueR)
            return DuboisMath.calculate(
                DuboisMath.DuboisParams(finalL, finalR, leakL, leakR, useLms)
            )
        } else {
            return getPresetMatrix(type)
        }
    }

    private fun getPresetMatrix(type: StereoRenderer.AnaglyphType): DuboisMath.ResultMatrices {
        val l: FloatArray
        val r: FloatArray
        when (type) {
            StereoRenderer.AnaglyphType.RC_DUBOIS -> {
                l = floatArrayOf(0.4154f, 0.4710f, 0.1669f, -0.0458f, -0.0484f, -0.0257f, -0.0547f, -0.0615f, 0.0128f)
                r = floatArrayOf(-0.0109f, -0.0364f, -0.0060f, 0.3756f, 0.7333f, 0.0111f, -0.0651f, -0.1287f, 1.2971f)
            }
            StereoRenderer.AnaglyphType.RC_HALF_COLOR -> {
                l = floatArrayOf(0.299f, 0.587f, 0.114f, 0f, 0f, 0f, 0f, 0f, 0f)
                r = floatArrayOf(0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
            }
            StereoRenderer.AnaglyphType.RC_COLOR -> {
                l = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
                r = floatArrayOf(0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
            }
            StereoRenderer.AnaglyphType.RC_MONO -> {
                l = floatArrayOf(0.299f, 0.587f, 0.114f, 0f, 0f, 0f, 0f, 0f, 0f)
                r = floatArrayOf(0f, 0f, 0f, 0.299f, 0.587f, 0.114f, 0.299f, 0.587f, 0.114f)
            }
            StereoRenderer.AnaglyphType.RC_OPTIMIZED -> {
                l = floatArrayOf(0.4122f, 0.5604f, 0.2008f, -0.0723f, -0.0409f, -0.0697f, -0.0004f, -0.0011f, 0.1662f)
                r = floatArrayOf(-0.0211f, -0.1121f, -0.0402f, 0.3616f, 0.8075f, 0.0139f, 0.0021f, 0.0002f, 0.8330f)
            }
            StereoRenderer.AnaglyphType.YB_DUBOIS -> {
                l = floatArrayOf(1.0615f, -0.0585f, -0.0159f, 0.1258f, 0.7697f, -0.0892f, -0.0458f, -0.0838f, -0.0020f)
                r = floatArrayOf(-0.0223f, -0.0593f, -0.0088f, -0.0263f, -0.0348f, -0.0038f, 0.1874f, 0.3367f, 0.7649f)
            }
            StereoRenderer.AnaglyphType.YB_HALF_COLOR -> {
                l = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f)
                r = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0.299f, 0.587f, 0.114f)
            }
            StereoRenderer.AnaglyphType.YB_COLOR -> {
                l = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f)
                r = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f)
            }
            StereoRenderer.AnaglyphType.YB_MONO -> {
                l = floatArrayOf(0.299f, 0.587f, 0.114f, 0.299f, 0.587f, 0.114f, 0f, 0f, 0f)
                r = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0.299f, 0.587f, 0.114f)
            }
            StereoRenderer.AnaglyphType.GM_DUBOIS -> {
                l = floatArrayOf(-0.062f, -0.158f, -0.039f, 0.284f, 0.668f, 0.143f, -0.015f, -0.027f, 0.021f)
                r = floatArrayOf(0.529f, 0.705f, 0.024f, -0.016f, -0.015f, -0.065f, 0.009f, -0.075f, 0.937f)
            }
            StereoRenderer.AnaglyphType.GM_COLOR -> {
                l = floatArrayOf(0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f)
                r = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f)
            }
            StereoRenderer.AnaglyphType.GM_HALF_COLOR -> {
                l = floatArrayOf(0f, 0f, 0f, 0.299f, 0.587f, 0.114f, 0f, 0f, 0f)
                r = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f)
            }
            StereoRenderer.AnaglyphType.GM_MONO -> {
                l = floatArrayOf(0f, 0f, 0f, 0.299f, 0.587f, 0.114f, 0f, 0f, 0f)
                r = floatArrayOf(0.299f, 0.587f, 0.114f, 0f, 0f, 0f, 0.299f, 0.587f, 0.114f)
            }
            StereoRenderer.AnaglyphType.RB_MONO -> {
                l = floatArrayOf(0.299f, 0.587f, 0.114f, 0f, 0f, 0f, 0f, 0f, 0f)
                r = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0.299f, 0.587f, 0.114f)
            }
            else -> {
                l = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
                r = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
            }
        }
        return DuboisMath.ResultMatrices(l, r, true)
    }
}