package top.rootu.dddplayer.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import top.rootu.dddplayer.logic.AudioMixerLogic

@RunWith(RobolectricTestRunner::class)
class SpeedStereoMixerTest {
    private var speed = 1f
    private val mixer = SpeedStereoMixer(AudioMixerLogic.STANDARD_PARAMS) { speed }
    private val surround = AudioFormat(48_000, 6, C.ENCODING_PCM_16BIT)

    @Test
    fun leavesTheAudioAloneAtUnitSpeed() {
        assertEquals(AudioFormat.NOT_SET, mixer.configure(surround))
        assertFalse(mixer.isActive)
    }

    @Test
    fun downmixesToStereoWhileSpedUp() {
        speed = 1.5f
        val out = mixer.configure(surround)
        assertEquals(2, out.channelCount)
        assertEquals(48_000, out.sampleRate)
        assertTrue(mixer.isActive)
    }

    @Test
    fun stereoAndHighResolutionPcmGoAsIs() {
        speed = 2f
        assertEquals(AudioFormat.NOT_SET, mixer.configure(AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT)))
        assertFalse(mixer.isActive)
        assertEquals(AudioFormat.NOT_SET, mixer.configure(AudioFormat(48_000, 6, C.ENCODING_PCM_24BIT)))
        assertFalse(mixer.isActive)
    }

    @Test
    fun layoutIsChosenOnConfigureOnly() {
        mixer.configure(surround) // 1x
        speed = 2f // скорость сменилась, а Sink не перенастраивали: раскладка внутри воспроизведения не меняется
        mixer.flush()
        assertFalse(mixer.isActive)
        assertEquals(2, mixer.configure(surround).channelCount)
        speed = 1f
        mixer.flush()
        assertTrue(mixer.isActive)
    }
}
