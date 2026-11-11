package top.rootu.dddplayer.logic

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import top.rootu.dddplayer.data.SettingsRepository

/**
 * Настройки звука, по которым собирается плеер. В экспертном режиме это ручные настройки как есть,
 * в простом - профиль по аудиосистеме (стерео или многоканальная) и усилению голоса.
 */
@OptIn(UnstableApi::class)
object AudioProfileLogic {

    const val SYSTEM_STEREO = 0
    const val SYSTEM_MULTICHANNEL = 1

    /** Ступени усиления голоса в простом режиме, дБ (0 - выкл). */
    val VOICE_BOOST_STEPS = listOf(0, 3, 6, 9, 12)

    data class AudioConfig(
        val decoderPriority: Int,
        val passthrough: Boolean,
        val passthroughFormats: Set<String>,
        val hdAudioIec: Boolean,
        val transcode: Int,
        val outputChannels: Int,
        val upmix: Boolean,
        val generateLfe: Boolean,
        val mixParams: AudioMixerLogic.MixParams
    )

    /** Пресет простого режима: стандартный микшер, центр поднят на [voiceDb]. */
    fun voiceParams(voiceDb: Int): AudioMixerLogic.MixParams =
        AudioMixerLogic.STANDARD_PARAMS.copy(center = AudioMixerLogic.dbToGain(voiceDb.toFloat()))

    /**
     * Профиль простого режима.
     * Стерео: декодирует FFmpeg, все сводится в стерео 2.0 (RFC 7845), без проброса.
     * Многоканальная: проброс всего, что принимает выход; остальной многоканальный звук перекодируется в AC3
     * (выход без AC3 - в E-AC3, без обоих - PCM как в источнике). С усилением голоса проброса нет: чтобы поднять
     * центр, плеер декодирует и перекодирует весь многоканальный звук.
     * @param canAc3 / [canEac3] выход принимает AC3 / E-AC3 битстримом и кодировщик собран
     */
    fun simpleConfig(system: Int, voiceDb: Int, canAc3: Boolean, canEac3: Boolean): AudioConfig {
        val multichannel = system == SYSTEM_MULTICHANNEL
        val transcode = when {
            !multichannel -> SettingsRepository.AUDIO_TRANSCODE_OFF
            canAc3 -> SettingsRepository.AUDIO_TRANSCODE_AC3
            canEac3 -> SettingsRepository.AUDIO_TRANSCODE_EAC3
            else -> SettingsRepository.AUDIO_TRANSCODE_OFF
        }
        return AudioConfig(
            decoderPriority = DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER,
            passthrough = multichannel && voiceDb == 0,
            passthroughFormats = AudioPassthroughLogic.ALL_FORMATS.toSet(),
            hdAudioIec = false,
            transcode = transcode,
            outputChannels = if (multichannel) AudioMixerLogic.CHANNELS_SOURCE else AudioMixerLogic.CHANNELS_20,
            upmix = false,
            generateLfe = false,
            mixParams = voiceParams(voiceDb)
        )
    }

    /**
     * Простой профиль, совпадающий с ручными настройками: (аудиосистема, усиление голоса) или null.
     * Нужен для перехода на режимы: у кого настройки как у простого профиля, тот переходит в простой режим,
     * остальные - в экспертный, чтобы поведение не поменялось.
     */
    fun matchSimpleProfile(expert: AudioConfig, standardPreset: Boolean): Pair<Int, Int>? {
        if (expert.decoderPriority != DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER) return null
        if (!standardPreset || expert.upmix || expert.hdAudioIec) return null
        val stereo = !expert.passthrough && expert.transcode == SettingsRepository.AUDIO_TRANSCODE_OFF &&
            expert.outputChannels == AudioMixerLogic.CHANNELS_20
        if (stereo) return SYSTEM_STEREO to 0
        val multichannel = expert.passthrough && expert.outputChannels == AudioMixerLogic.CHANNELS_SOURCE &&
            expert.transcode != SettingsRepository.AUDIO_TRANSCODE_OFF &&
            expert.passthroughFormats.containsAll(AudioPassthroughLogic.ALL_FORMATS)
        return if (multichannel) SYSTEM_MULTICHANNEL to 0 else null
    }
}
