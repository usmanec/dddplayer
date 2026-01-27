package top.rootu.dddplayer.logic

import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi
import top.rootu.dddplayer.data.SettingsRepository
import kotlin.math.sqrt

@UnstableApi
object AudioMixerLogic {

    enum class MixPreset(val id: Int, val title: String) {
        STANDARD(0, "Стандарт - RFC 7845 (Section 5.1.1.5)"),
        BOOST_CENTER(1, "Усиление голоса"),
        NIGHT_MODE(2, "Ночной (Тихие эффекты)"),
        CUSTOM(3, "Пользовательский")
    }

    data class MixParams(
        val front: Float,
        val center: Float,
        val rear: Float,
        val middle: Float,
        val lfe: Float
    )

    // Базовые константы из RFC 7845
    private val SQRT2_INV = (1.0 / sqrt(2.0)).toFloat() // 0.7071
    private val SQRT3_HALF = (sqrt(3.0) / 2.0).toFloat() // 0.8660

    fun getParamsForPreset(preset: MixPreset, repo: SettingsRepository): MixParams {
        return when (preset) {
            MixPreset.STANDARD -> MixParams(1.0f, 1.0f, 1.0f, 1.0f, 1.0f) // Базовые веса RFC
            MixPreset.BOOST_CENTER -> MixParams(0.8f, 1.5f, 0.6f, 0.6f, 0.5f)
            MixPreset.NIGHT_MODE -> MixParams(1.0f, 1.2f, 0.3f, 0.3f, 0.0f)
            MixPreset.CUSTOM -> MixParams(
                repo.getMixFront(),
                repo.getMixCenter(),
                repo.getMixRear(),
                repo.getMixMiddle(),
                repo.getMixLfe()
            )
        }
    }

    fun createMatrices(repo: SettingsRepository): List<ChannelMixingMatrix> {
        val presetId = repo.getMixPreset()
        val preset = MixPreset.values().find { it.id == presetId } ?: MixPreset.STANDARD
        val params = getParamsForPreset(preset, repo)

        val matrices = mutableListOf<ChannelMixingMatrix>()
        for (inputChannels in 1..8) {
            createMatrix(inputChannels, params)?.let { matrices.add(it) }
        }
        return matrices
    }

    private fun createMatrix(inputChannels: Int, p: MixParams): ChannelMixingMatrix? {
        // Порядок каналов Android (Media3):
        // 1: Mono (C)
        // 2: Stereo (FL, FR)
        // 3: L, R, C
        // 4: FL, FR, BL, BR (Quad)
        // 5: FL, FR, C, BL, BR
        // 6: FL, FR, C, LFE, BL, BR (5.1)
        // 7: FL, FR, C, LFE, BC, SL, SR (6.1)
        // 8: FL, FR, C, LFE, BL, BR, SL, SR (7.1)

        return when (inputChannels) {
            1 -> { // Mono -> Stereo
                val coeffs = floatArrayOf(p.center * SQRT2_INV, p.center * SQRT2_INV)
                ChannelMixingMatrix(1, 2, coeffs)
            }
            2 -> { // Stereo -> Stereo
                val coeffs = floatArrayOf(p.front, 0f, 0f, p.front)
                ChannelMixingMatrix(2, 2, coeffs)
            }
            3 -> { // L, R, C -> Stereo (RFC Fig 4)
                val norm = 1f / (1f + SQRT2_INV)
                val cFL = 1f * norm * p.front
                val cFC = SQRT2_INV * norm * p.center
                val coeffs = floatArrayOf(cFL, 0f, 0f, cFL, cFC, cFC)
                ChannelMixingMatrix(3, 2, coeffs)
            }
            4 -> { // Quad -> Stereo (RFC Fig 5)
                val norm = 1f / (1f + SQRT3_HALF + 0.5f)
                val cFL = 1f * norm * p.front
                val cBL_L = SQRT3_HALF * norm * p.rear
                val cBL_R = 0.5f * norm * p.rear
                val coeffs = floatArrayOf(cFL, 0f, 0f, cFL, cBL_L, cBL_R, cBL_R, cBL_L)
                ChannelMixingMatrix(4, 2, coeffs)
            }
            5 -> { // 5.0 -> Stereo (RFC Fig 6)
                val norm = 2f / (1f + SQRT2_INV + SQRT3_HALF + 0.5f)
                val cFL = 1f * norm * p.front
                val cFC = SQRT2_INV * norm * p.center
                val cBL_L = SQRT3_HALF * norm * p.rear
                val cBL_R = 0.5f * norm * p.rear
                val coeffs = floatArrayOf(cFL, 0f, 0f, cFL, cFC, cFC, cBL_L, cBL_R, cBL_R, cBL_L)
                ChannelMixingMatrix(5, 2, coeffs)
            }
            6 -> { // 5.1 -> Stereo (RFC Fig 7)
                val norm = 2f / (1f + SQRT2_INV + SQRT3_HALF + 0.5f + SQRT2_INV)
                val cFL = 1f * norm * p.front
                val cFC = SQRT2_INV * norm * p.center
                val cLFE = SQRT2_INV * norm * p.lfe
                val cBL_L = SQRT3_HALF * norm * p.rear
                val cBL_R = 0.5f * norm * p.rear
                val coeffs = floatArrayOf(cFL, 0f, 0f, cFL, cFC, cFC, cLFE, cLFE, cBL_L, cBL_R, cBL_R, cBL_L)
                ChannelMixingMatrix(6, 2, coeffs)
            }
            7 -> { // 6.1 -> Stereo (RFC Fig 8)
                val norm = 2f / (1f + SQRT2_INV + SQRT3_HALF + 0.5f + (SQRT3_HALF / SQRT2_INV) + SQRT2_INV)
                val cFL = 1f * norm * p.front
                val cFC = SQRT2_INV * norm * p.center
                val cLFE = SQRT2_INV * norm * p.lfe
                val cBC_L = (SQRT3_HALF / SQRT2_INV) * norm * p.rear // Back Center
                val cBC_R = (SQRT3_HALF / SQRT2_INV) * norm * p.rear
                val cSL_L = SQRT3_HALF * norm * p.middle
                val cSL_R = 0.5f * norm * p.middle
                val coeffs = floatArrayOf(cFL, 0f, 0f, cFL, cFC, cFC, cLFE, cLFE, cBC_L, cBC_R, cSL_L, cSL_R, cSL_R, cSL_L)
                ChannelMixingMatrix(7, 2, coeffs)
            }
            8 -> { // 7.1 -> Stereo (RFC Fig 9)
                val norm = 2f / (2f + 2f * SQRT2_INV + SQRT3_HALF)
                val cFL = 1f * norm * p.front
                val cFC = SQRT2_INV * norm * p.center
                val cLFE = SQRT2_INV * norm * p.lfe
                val cBL_L = SQRT3_HALF * norm * p.rear
                val cBL_R = 0.5f * norm * p.rear
                val cSL_L = SQRT3_HALF * norm * p.middle
                val cSL_R = 0.5f * norm * p.middle
                val coeffs = floatArrayOf(cFL, 0f, 0f, cFL, cFC, cFC, cLFE, cLFE, cBL_L, cBL_R, cBL_R, cBL_L, cSL_L, cSL_R, cSL_R, cSL_L)
                ChannelMixingMatrix(8, 2, coeffs)
            }
            else -> null
        }
    }
}