package top.rootu.dddplayer.logic

import androidx.media3.exoplayer.DefaultRenderersFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.rootu.dddplayer.data.SettingsRepository

class AudioProfileLogicTest {

    @Test
    fun `stereo profile decodes everything to stereo`() {
        val c = AudioProfileLogic.simpleConfig(AudioProfileLogic.SYSTEM_STEREO, 0, canAc3 = true, canEac3 = true)
        assertEquals(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER, c.decoderPriority)
        assertFalse(c.passthrough)
        assertEquals(SettingsRepository.AUDIO_TRANSCODE_OFF, c.transcode)
        assertEquals(AudioMixerLogic.CHANNELS_20, c.outputChannels)
        assertEquals(AudioMixerLogic.STANDARD_PARAMS, c.mixParams)
    }

    @Test
    fun `multichannel profile passes through and transcodes the rest to ac3`() {
        val c = AudioProfileLogic.simpleConfig(AudioProfileLogic.SYSTEM_MULTICHANNEL, 0, canAc3 = true, canEac3 = true)
        assertTrue(c.passthrough)
        assertEquals(SettingsRepository.AUDIO_TRANSCODE_AC3, c.transcode)
        assertEquals(AudioMixerLogic.CHANNELS_SOURCE, c.outputChannels)
        // Нет AC3 - E-AC3, нет обоих - PCM
        assertEquals(
            SettingsRepository.AUDIO_TRANSCODE_EAC3,
            AudioProfileLogic.simpleConfig(AudioProfileLogic.SYSTEM_MULTICHANNEL, 0, canAc3 = false, canEac3 = true).transcode
        )
        assertEquals(
            SettingsRepository.AUDIO_TRANSCODE_OFF,
            AudioProfileLogic.simpleConfig(AudioProfileLogic.SYSTEM_MULTICHANNEL, 0, canAc3 = false, canEac3 = false).transcode
        )
    }

    @Test
    fun `voice boost lifts the center and turns passthrough off on multichannel`() {
        val c = AudioProfileLogic.simpleConfig(AudioProfileLogic.SYSTEM_MULTICHANNEL, 12, canAc3 = true, canEac3 = true)
        assertFalse(c.passthrough)
        assertEquals(SettingsRepository.AUDIO_TRANSCODE_AC3, c.transcode)
        assertEquals(AudioMixerLogic.dbToGain(12f), c.mixParams.center, 1e-5f)
        assertEquals(1f, c.mixParams.front, 0f)
    }

    @Test
    fun `only settings equal to a simple profile migrate to simple mode`() {
        val stereo = AudioProfileLogic.simpleConfig(AudioProfileLogic.SYSTEM_STEREO, 0, canAc3 = true, canEac3 = true)
        assertEquals(AudioProfileLogic.SYSTEM_STEREO to 0, AudioProfileLogic.matchSimpleProfile(stereo, standardPreset = true))
        val multi = AudioProfileLogic.simpleConfig(AudioProfileLogic.SYSTEM_MULTICHANNEL, 0, canAc3 = true, canEac3 = true)
        assertEquals(AudioProfileLogic.SYSTEM_MULTICHANNEL to 0, AudioProfileLogic.matchSimpleProfile(multi, standardPreset = true))
        // Свой пресет, HW-декодер или 5.1 на выходе - экспертный режим
        assertNull(AudioProfileLogic.matchSimpleProfile(stereo, standardPreset = false))
        assertNull(
            AudioProfileLogic.matchSimpleProfile(
                stereo.copy(decoderPriority = DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON), standardPreset = true
            )
        )
        assertNull(AudioProfileLogic.matchSimpleProfile(stereo.copy(outputChannels = AudioMixerLogic.CHANNELS_51), true))
    }
}
