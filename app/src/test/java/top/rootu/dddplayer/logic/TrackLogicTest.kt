package top.rootu.dddplayer.logic

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import top.rootu.dddplayer.data.SettingsRepository
import top.rootu.dddplayer.viewmodel.TrackOption

// Format.Builder нормализует код языка через Android API - нужен Robolectric
@RunWith(RobolectricTestRunner::class)
class TrackLogicTest {

    private fun audio(label: String?, language: String?, mime: String, channels: Int) =
        Format.Builder()
            .setLabel(label)
            .setLanguage(language)
            .setSampleMimeType(mime)
            .setChannelCount(channels)
            .build()

    private fun option(format: Format?, nameFromMeta: String? = null, isOff: Boolean = false) =
        TrackOption(format, nameFromMeta, 1, null, 0, isOff)

    @Test
    fun `signature contains name, language, codec and channels`() {
        val format = audio("DUB Mosfilm", "ru", MimeTypes.AUDIO_AC3, 6)
        assertEquals("DUB Mosfilm|ru|${MimeTypes.AUDIO_AC3}|6", TrackLogic.audioTrackSignature(option(format)))
    }

    @Test
    fun `name from file metadata has priority over the player label`() {
        val format = audio("Label", "en", MimeTypes.AUDIO_AAC, 2)
        assertEquals(
            "Meta name|en|${MimeTypes.AUDIO_AAC}|2",
            TrackLogic.audioTrackSignature(option(format, nameFromMeta = "  Meta name "))
        )
    }

    @Test
    fun `same track in different videos has the same signature`() {
        val a = audio("Original", "en", MimeTypes.AUDIO_E_AC3, 6)
        val b = audio("Original", "en", MimeTypes.AUDIO_E_AC3, 6)
        assertEquals(TrackLogic.audioTrackSignature(option(a)), TrackLogic.audioTrackSignature(option(b)))
        val stereo = audio("Original", "en", MimeTypes.AUDIO_E_AC3, 2)
        assertNotEquals(TrackLogic.audioTrackSignature(option(a)), TrackLogic.audioTrackSignature(option(stereo)))
    }

    @Test
    fun `missing language becomes und and newlines are removed`() {
        val format = audio("Two\nlines", null, MimeTypes.AUDIO_AAC, 2)
        assertEquals("Two lines|und|${MimeTypes.AUDIO_AAC}|2", TrackLogic.audioTrackSignature(option(format)))
    }

    @Test
    fun `off item has no signature`() {
        assertNull(TrackLogic.audioTrackSignature(option(null, isOff = true)))
        assertNull(TrackLogic.audioTrackSignature(option(null)))
    }

    // Субтитры: пункт "Выкл" первым, как в extractSubtitleTracks
    private fun sub(language: String?, flags: Int = 0) =
        option(Format.Builder().setLanguage(language).setSelectionFlags(flags).setSampleMimeType(MimeTypes.APPLICATION_SUBRIP).build())

    private val subs = listOf(
        option(null, isOff = true),
        sub("en"),
        sub("ru", C.SELECTION_FLAG_FORCED),
        sub("ru"),
        sub("en", C.SELECTION_FLAG_FORCED)
    )

    private fun auto(mode: Int, langs: List<String> = emptyList(), audio: String? = null, options: List<TrackOption> = subs) =
        TrackLogic.autoSubtitleIndex(options, mode, langs, audio)

    @Test
    fun `default mode keeps the player choice`() {
        assertNull(auto(SettingsRepository.SUB_AUTO_DEFAULT, listOf("ru")))
    }

    @Test
    fun `off mode selects the off item`() {
        assertEquals(0, auto(SettingsRepository.SUB_AUTO_OFF))
    }

    @Test
    fun `full mode prefers the language and skips forced tracks`() {
        assertEquals(3, auto(SettingsRepository.SUB_AUTO_FULL, listOf("rus")))
        assertEquals(1, auto(SettingsRepository.SUB_AUTO_FULL, listOf("de", "en-US")))
    }

    @Test
    fun `full mode without a language match uses the default flag then the first track`() {
        assertEquals(1, auto(SettingsRepository.SUB_AUTO_FULL, listOf("de")))
        val flagged = listOf(option(null, isOff = true), sub("en"), sub("fr", C.SELECTION_FLAG_DEFAULT))
        assertEquals(2, auto(SettingsRepository.SUB_AUTO_FULL, options = flagged))
    }

    @Test
    fun `full mode falls back to forced tracks when there are no full ones`() {
        val onlyForced = listOf(option(null, isOff = true), sub("en", C.SELECTION_FLAG_FORCED))
        assertEquals(1, auto(SettingsRepository.SUB_AUTO_FULL, listOf("ru"), options = onlyForced))
    }

    @Test
    fun `forced mode matches the audio language first`() {
        assertEquals(4, auto(SettingsRepository.SUB_AUTO_FORCED, listOf("ru"), audio = "en"))
        assertEquals(2, auto(SettingsRepository.SUB_AUTO_FORCED, listOf("ru"), audio = "ja"))
        assertEquals(2, auto(SettingsRepository.SUB_AUTO_FORCED))
    }

    @Test
    fun `forced mode turns subtitles off when there are no forced tracks`() {
        val noForced = listOf(option(null, isOff = true), sub("en"), sub("ru"))
        assertEquals(0, auto(SettingsRepository.SUB_AUTO_FORCED, listOf("ru"), audio = "ru", options = noForced))
    }

    @Test
    fun `language codes are compared without region and format`() {
        assertTrue(TrackLogic.sameLanguage("ru", "rus"))
        assertTrue(TrackLogic.sameLanguage("en-GB", "en"))
        assertFalse(TrackLogic.sameLanguage("und", "und"))
        assertFalse(TrackLogic.sameLanguage("ext", "ext"))
        assertFalse(TrackLogic.sameLanguage(null, "en"))
    }

    // Субтитры, выбранные в плейлисте: такие же в следующей серии
    private fun named(name: String, language: String, flags: Int = 0) =
        option(Format.Builder().setLabel(name).setLanguage(language).setSelectionFlags(flags)
            .setSampleMimeType(MimeTypes.APPLICATION_SUBRIP).build())

    @Test
    fun `forced subtitles are found in the next episode by language when names differ`() {
        val chosen = TrackLogic.subtitleTrackSignature(named("Надписи", "ru", C.SELECTION_FLAG_FORCED))!!
        val next = listOf(option(null, isOff = true), named("Полные", "ru"), named("Forced", "ru", C.SELECTION_FLAG_FORCED))
        assertEquals(2, TrackLogic.findSubtitleIndex(next, chosen))
    }

    @Test
    fun `exact subtitle match wins and off is remembered`() {
        val options = listOf(option(null, isOff = true), named("A", "en"), named("B", "en"))
        val chosen = TrackLogic.subtitleTrackSignature(options[2])!!
        assertEquals(2, TrackLogic.findSubtitleIndex(options, chosen))
        assertEquals(TrackLogic.SUBTITLE_OFF, TrackLogic.subtitleTrackSignature(options[0]))
        assertEquals(0, TrackLogic.findSubtitleIndex(options, TrackLogic.SUBTITLE_OFF))
        // Нет такого языка - решает автовключение
        assertNull(TrackLogic.findSubtitleIndex(options, TrackLogic.subtitleTrackSignature(named("X", "de"))!!))
    }
}
