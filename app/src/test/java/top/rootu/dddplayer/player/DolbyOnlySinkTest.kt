package top.rootu.dddplayer.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Format.Builder нормализует код языка через Android API - нужен Robolectric
@RunWith(RobolectricTestRunner::class)
class DolbyOnlySinkTest {
    private fun format(mime: String?) = Format.Builder().setSampleMimeType(mime).build()

    @Test
    fun dolbyBitstreamsAndPcmPass() {
        for (mime in listOf(MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC, MimeTypes.AUDIO_RAW)) {
            assertFalse(mime, DolbyOnlySink.isBlocked(format(mime)))
        }
    }

    @Test
    fun otherBitstreamsAreDecodedInstead() {
        for (mime in listOf(MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_EXPRESS,
            MimeTypes.AUDIO_TRUEHD, MimeTypes.AUDIO_AC4)) {
            assertTrue(mime, DolbyOnlySink.isBlocked(format(mime)))
        }
    }

    @Test
    fun noMimeTypeIsLeftToTheSink() {
        assertFalse(DolbyOnlySink.isBlocked(format(null)))
    }
}
