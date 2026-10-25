package top.rootu.dddplayer.logic

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPassthroughLogicTest {

    @Test
    fun `atmos and dts x share groups with their base formats`() {
        assertEquals(AudioPassthroughLogic.EAC3, AudioPassthroughLogic.formatKey(MimeTypes.AUDIO_E_AC3_JOC))
        assertEquals(AudioPassthroughLogic.DTS_HD, AudioPassthroughLogic.formatKey(MimeTypes.AUDIO_DTS_X))
        assertEquals(AudioPassthroughLogic.DTS, AudioPassthroughLogic.formatKey(MimeTypes.AUDIO_DTS_EXPRESS))
        assertNull(AudioPassthroughLogic.formatKey(MimeTypes.AUDIO_AC4))
    }

    @Test
    fun `passthrough follows the switch and the format list`() {
        val formats = setOf(AudioPassthroughLogic.AC3, AudioPassthroughLogic.DTS)
        assertTrue(AudioPassthroughLogic.isAllowed(MimeTypes.AUDIO_AC3, true, formats))
        assertFalse(AudioPassthroughLogic.isAllowed(MimeTypes.AUDIO_TRUEHD, true, formats))
        assertFalse(AudioPassthroughLogic.isAllowed(MimeTypes.AUDIO_AC3, false, formats))
        // Форматы вне списка - как раньше, по переключателю
        assertTrue(AudioPassthroughLogic.isAllowed(MimeTypes.AUDIO_AC4, true, formats))
        assertFalse(AudioPassthroughLogic.isAllowed(MimeTypes.AUDIO_AC4, false, formats))
    }
}
