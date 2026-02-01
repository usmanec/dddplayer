package top.rootu.dddplayer.logic

import androidx.media3.common.audio.ChannelMixingMatrix
import top.rootu.dddplayer.R
import top.rootu.dddplayer.data.SettingsRepository
import kotlin.math.sqrt

object AudioMixerLogic {

    enum class MixPreset(val id: Int, val titleResId: Int) {
        STANDARD(0, R.string.mix_preset_standard),
        BOOST_CENTER(1, R.string.mix_preset_boost_center),
        NIGHT_MODE(2, R.string.mix_preset_night_mode),
        CUSTOM(3, R.string.mix_preset_custom)
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
        val preset = MixPreset.entries.find { it.id == presetId } ?: MixPreset.STANDARD
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
                val matrix = floatArrayOf(p.center * SQRT2_INV, p.center * SQRT2_INV)
                ChannelMixingMatrix(1, 2, matrix)
            }
            2 -> { // Stereo -> Stereo
                val matrix = floatArrayOf(p.front, 0f, 0f, p.front)
                ChannelMixingMatrix(2, 2, matrix)
            }
            3 -> { // L, R, C -> Stereo (RFC Fig 4)
                val norm = 1f / (1f + SQRT2_INV)
                val cFL = 1f * norm * p.front
                val cFC = SQRT2_INV * norm * p.center
                val matrix = floatArrayOf(cFL, 0f, 0f, cFL, cFC, cFC)
                ChannelMixingMatrix(3, 2, matrix)
            }
            4 -> { // Quad -> Stereo (RFC Fig 5)
                val norm = 1f / (1f + SQRT3_HALF + 0.5f)
                val cFL = 1f * norm * p.front
                val cBLL = SQRT3_HALF * norm * p.rear
                val cBLR = 0.5f * norm * p.rear
                val matrix = floatArrayOf(cFL, 0f, 0f, cFL, cBLL, cBLR, cBLR, cBLL)
                ChannelMixingMatrix(4, 2, matrix)
            }
            5 -> { // 5.0 -> Stereo (RFC Fig 6)
                val norm = 2f / (1f + SQRT2_INV + SQRT3_HALF + 0.5f)
                val cFL = 1f * norm * p.front
                val cFC = SQRT2_INV * norm * p.center
                val cBLL = SQRT3_HALF * norm * p.rear
                val cBLR = 0.5f * norm * p.rear
                val matrix = floatArrayOf(cFL, 0f, 0f, cFL, cFC, cFC, cBLL, cBLR, cBLR, cBLL)
                ChannelMixingMatrix(5, 2, matrix)
            }
            6 -> { // 5.1 -> Stereo (RFC Fig 7)
                val norm = 2f / (1f + SQRT2_INV + SQRT3_HALF + 0.5f + SQRT2_INV)
                val cFL = 1f * norm * p.front
                val cFC = SQRT2_INV * norm * p.center
                val cLFE = SQRT2_INV * norm * p.lfe
                val cBLL = SQRT3_HALF * norm * p.rear
                val cBLR = 0.5f * norm * p.rear
                val matrix = floatArrayOf(cFL, 0f, 0f, cFL, cFC, cFC, cLFE, cLFE, cBLL, cBLR, cBLR, cBLL)
                ChannelMixingMatrix(6, 2, matrix)
            }
            7 -> { // 6.1 -> Stereo (RFC Fig 8)
                val norm = 2f / (1f + SQRT2_INV + SQRT3_HALF + 0.5f + (SQRT3_HALF / SQRT2_INV) + SQRT2_INV)
                val cFL = 1f * norm * p.front
                val cFC = SQRT2_INV * norm * p.center
                val cLFE = SQRT2_INV * norm * p.lfe
                val cBCL = (SQRT3_HALF / SQRT2_INV) * norm * p.rear // Back Center
                val cBCR = (SQRT3_HALF / SQRT2_INV) * norm * p.rear
                val cSLL = SQRT3_HALF * norm * p.middle
                val cSLR = 0.5f * norm * p.middle
                val matrix = floatArrayOf(cFL, 0f, 0f, cFL, cFC, cFC, cLFE, cLFE, cBCL, cBCR, cSLL, cSLR, cSLR, cSLL)
                ChannelMixingMatrix(7, 2, matrix)
            }
            8 -> { // 7.1 -> Stereo (RFC Fig 9)
                val norm = 2f / (2f + 2f * SQRT2_INV + SQRT3_HALF)
                val cFL = 1f * norm * p.front
                val cFC = SQRT2_INV * norm * p.center
                val cLFE = SQRT2_INV * norm * p.lfe
                val cBLL = SQRT3_HALF * norm * p.rear
                val cBLR = 0.5f * norm * p.rear
                val cSLL = SQRT3_HALF * norm * p.middle
                val cSLR = 0.5f * norm * p.middle
                val matrix = floatArrayOf(cFL, 0f, 0f, cFL, cFC, cFC, cLFE, cLFE, cBLL, cBLR, cBLR, cBLL, cSLL, cSLR, cSLR, cSLL)
                ChannelMixingMatrix(8, 2, matrix)
            }
            else -> null
        }
    }
}