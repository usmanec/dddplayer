package top.rootu.dddplayer.data

import android.content.Context
import androidx.core.content.edit
import androidx.media3.exoplayer.DefaultRenderersFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import top.rootu.dddplayer.logic.AudioMixerLogic
import top.rootu.dddplayer.logic.AudioPassthroughLogic
import top.rootu.dddplayer.logic.AudioProfileLogic
import top.rootu.dddplayer.model.AnaglyphCalibration
import top.rootu.dddplayer.model.HistoryEntry
import top.rootu.dddplayer.model.SavedPlaylist
import top.rootu.dddplayer.model.SmbServer
import top.rootu.dddplayer.model.StereoOutputMode
import top.rootu.dddplayer.model.SubtitleStyle
import top.rootu.dddplayer.renderer.StereoRenderer
import top.rootu.dddplayer.viewmodel.GlassesGroup

@RunWith(RobolectricTestRunner::class)
class SettingsRepositoryTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val prefs = context.getSharedPreferences("global_prefs", Context.MODE_PRIVATE)

    @Before
    fun resetSingletons() {
        // Синглтоны живут между тестами, а приложение Robolectric для каждого теста новое
        listOf(SettingsRepository::class.java, AppDatabase::class.java).forEach { cls ->
            cls.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        }
    }

    private fun repo() = SettingsRepository.getInstance(context)

    private fun SettingsRepository.order() = getAudioTrackStats().map { it.signature }

    @Test
    fun `audio tracks are ordered by count, recent first on equal count`() {
        val repo = repo()
        repo.recordAudioTrackChoice("A", "Track A")
        repo.recordAudioTrackChoice("B", "Track B")
        repo.recordAudioTrackChoice("B", "Track B")
        repo.recordAudioTrackChoice("C", "Track C")

        assertEquals(listOf("B", "C", "A"), repo.order())
        assertEquals(listOf(2, 1, 1), repo.getAudioTrackStats().map { it.count })
    }

    @Test
    fun `moved tracks keep their place regardless of counts`() {
        val repo = repo()
        repo.recordAudioTrackChoice("A", "A")
        repo.recordAudioTrackChoice("B", "B")
        repo.recordAudioTrackChoice("B", "B")
        repo.recordAudioTrackChoice("C", "C") // B, C, A

        repo.moveAudioTrackStat(1, 0) // C, B закреплены
        assertEquals(listOf("C", "B", "A"), repo.order())

        repeat(5) { repo.recordAudioTrackChoice("A", "A") }
        repo.recordAudioTrackChoice("D", "D")
        // Незакрепленные - после закрепленных, по счетчику
        assertEquals(listOf("C", "B", "A", "D"), repo.order())
        assertEquals(listOf(true, true, false, false), repo.getAudioTrackStats().map { it.pinned })
    }

    @Test
    fun `move down and remove`() {
        val repo = repo()
        listOf("A", "B", "C").forEach { repo.recordAudioTrackChoice(it, it) } // C, B, A

        repo.moveAudioTrackStat(0, 1)
        assertEquals(listOf("B", "C", "A"), repo.order())

        repo.removeAudioTrackStat(0)
        assertEquals(listOf("C", "A"), repo.order())
    }

    @Test
    fun `list keeps the most recent tracks when full`() {
        val repo = repo()
        repeat(25) { repo.recordAudioTrackChoice("t$it", "t$it") }

        val order = repo.order()
        assertEquals(SettingsRepository.MAX_AUDIO_TRACK_STATS, order.size)
        assertEquals("t24", order.first())
        assertFalse("t0" in order)
    }

    @Test
    fun `dolby vision switch is migrated to the mode`() {
        prefs.edit { putBoolean("map_dv_to_hevc", false) }
        assertEquals(SettingsRepository.DV_MODE_OFF, repo().getDvMode())
        assertFalse(prefs.contains("map_dv_to_hevc"))
    }

    @Test
    fun `dolby vision is auto by default`() {
        assertEquals(SettingsRepository.DV_MODE_AUTO, repo().getDvMode())
        assertTrue(prefs.contains("dv_mode"))
    }

    @Test
    fun `hdr10 mode stored by default is moved to auto once`() {
        // Версия 1.4.0 записывала "HDR10/HLG" (DV_MODE_ALWAYS) как значение по умолчанию
        prefs.edit { putInt("dv_mode", SettingsRepository.DV_MODE_ALWAYS) }
        val repo = repo()
        assertEquals(SettingsRepository.DV_MODE_AUTO, repo.getDvMode())
        // Выбранный после этого "HDR10/HLG" сохраняется
        repo.setDvMode(SettingsRepository.DV_MODE_ALWAYS)
        assertEquals(SettingsRepository.DV_MODE_ALWAYS, repo.getDvMode())
    }

    @Test
    fun `explicit dolby vision mode is kept`() {
        prefs.edit { putInt("dv_mode", SettingsRepository.DV_MODE_OFF) }
        assertEquals(SettingsRepository.DV_MODE_OFF, repo().getDvMode())
    }

    @Test
    fun `video and audio decoder priorities start from the old common setting`() {
        prefs.edit { putInt("decoder_priority", DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF) }
        val repo = repo()
        assertEquals(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF, repo.getVideoDecoderPriority())
        assertEquals(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF, repo.getAudioDecoderPriority())
        // Настройки независимы
        repo.setAudioDecoderPriority(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        assertEquals(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF, repo.getVideoDecoderPriority())
        assertEquals(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER, repo.getAudioDecoderPriority())
    }

    @Test
    fun `av1 prefers device decoders and ffmpeg prefers the app by default`() {
        assertEquals(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON, repo().getVideoDecoderPriority())
        assertEquals(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER, repo().getAudioDecoderPriority())
    }

    @Test
    fun `clock switch is migrated to transparency`() {
        prefs.edit { putBoolean("show_clock", true) }
        assertEquals(0, repo().getClockTransparency())
        assertFalse(prefs.contains("show_clock"))
    }

    @Test
    fun `new install keeps decoded stereo without passthrough`() {
        val repo = repo()
        assertFalse(repo.isAudioPassthroughEnabled())
        assertEquals(AudioMixerLogic.CHANNELS_20, repo.getAudioOutputChannels())
        assertEquals(SettingsRepository.AUDIO_TRANSCODE_OFF, repo.getAudioTranscode())
        assertEquals(AudioPassthroughLogic.ALL_FORMATS.toSet(), repo.getAudioPassthroughFormats())
        // Простой режим, стерео, голос не усиливается
        assertFalse(repo.isAudioExpertMode())
        assertEquals(AudioProfileLogic.SYSTEM_STEREO, repo.getAudioSystem())
        assertEquals(0, repo.getVoiceBoostDb())
    }

    @Test
    fun `hardware decoder with passthrough migrates to expert mode`() {
        prefs.edit {
            putInt("audio_decoder_priority", DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            putBoolean("stereo_downmix", false)
        }
        val repo = repo()
        assertTrue(repo.isAudioExpertMode())
        // Экспертный режим собирает плеер по ручным настройкам
        assertEquals(repo.getExpertAudioConfig(), repo.getAudioConfig(canAc3 = true, canEac3 = true))
    }

    @Test
    fun `device decoders first and no downmix migrate to passthrough with source channels`() {
        prefs.edit {
            putInt("audio_decoder_priority", DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            putBoolean("stereo_downmix", false)
            putInt("center_gain_db", 6)
        }
        val repo = repo()
        assertTrue(repo.isAudioPassthroughEnabled())
        assertEquals(AudioMixerLogic.CHANNELS_SOURCE, repo.getAudioOutputChannels())
        assertFalse(prefs.contains("stereo_downmix"))
        assertFalse(prefs.contains("center_gain_db"))
        // Приоритет декодеров остается своим, проброс от него больше не зависит
        repo.setAudioDecoderPriority(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        assertTrue(repo.isAudioPassthroughEnabled())
    }

    @Test
    fun `transcoding settings migrate to transcode and channels`() {
        prefs.edit {
            putInt("audio_output_codec", 2)
            putInt("audio_output_layout", 2)
        }
        val repo = repo()
        assertEquals(SettingsRepository.AUDIO_TRANSCODE_EAC3, repo.getAudioTranscode())
        assertEquals(AudioMixerLogic.CHANNELS_40, repo.getAudioOutputChannels())
        assertFalse(repo.isAudioPassthroughEnabled())
        assertFalse(prefs.contains("audio_output_codec"))
    }

    @Test
    fun `subtitle style is stored and defaults to the standard look`() {
        val repo = repo()
        assertEquals(SubtitleStyle(), repo.getSubtitleStyle())

        val custom = SubtitleStyle(
            font = 2, sizePercent = 130, textColor = 0xFFFFEB3B.toInt(), backgroundColor = 0,
            edgeType = 1, edgeColor = 0xFF424242.toInt(), bottomPercent = 15, applyEmbedded = false
        )
        repo.setSubtitleStyle(custom)
        assertEquals(custom, repo.getSubtitleStyle())
    }

    @Test
    fun `anaglyph calibration is stored per glasses group and can be reset`() {
        val repo = repo()
        assertNull(repo.getAnaglyphCalibration(GlassesGroup.RED_CYAN))

        val calibration = AnaglyphCalibration(leakA = 0.12f, leakB = 0.3f)
        repo.setAnaglyphCalibration(GlassesGroup.RED_CYAN, calibration)
        repo.setAnaglyphLensSwapped(true)
        repo.putGlobalInt("def_anaglyph_type", StereoRenderer.AnaglyphType.RC_CALIBRATED.ordinal)
        repo.putGlobalInt("pref_filter_RED_CYAN", StereoRenderer.AnaglyphType.RC_CALIBRATED.ordinal)
        val version = repo.getCalibrationVersion()

        assertEquals(calibration, repo.getAnaglyphCalibration(GlassesGroup.RED_CYAN))
        assertNull(repo.getAnaglyphCalibration(GlassesGroup.YELLOW_BLUE))

        repo.resetAnaglyphCalibration()
        assertNull(repo.getAnaglyphCalibration(GlassesGroup.RED_CYAN))
        assertFalse(repo.isAnaglyphLensSwapped())
        // Выбранный фильтр "Калибровка" заменяется встроенным фильтром группы
        assertEquals(StereoRenderer.AnaglyphType.RC_DUBOIS.ordinal, repo.getGlobalInt("def_anaglyph_type", -1))
        assertEquals(StereoRenderer.AnaglyphType.RC_DUBOIS.ordinal, repo.getGlobalInt("pref_filter_RED_CYAN", -1))
        // Плеер перечитывает калибровку по смене версии
        assertTrue(repo.getCalibrationVersion() > version)
    }

    @Test
    fun `vr calibration reset restores app defaults`() {
        val repo = repo()
        repo.putGlobalFloat("vr_k1", 0.5f)
        repo.putGlobalFloat("global_screen_separation_pct", 0.05f)

        repo.resetVrCalibration()
        assertEquals(0.34f, repo.getGlobalFloat("vr_k1", 0.34f), 0f)
        assertFalse(prefs.contains("vr_k1"))
        assertFalse(prefs.contains("global_screen_separation_pct"))
    }

    @Test
    fun `defaults keep the previous behaviour`() {
        val repo = repo()
        assertEquals(SettingsRepository.CLOCK_OFF, repo.getClockTransparency())
        // Кнопка "Вверх": первое нажатие - инфопанель, второе - боковое меню
        assertEquals(SettingsRepository.UP_ACTION_INFO, repo.getUpButtonAction())
        // Озвучка, выбранная в плейлисте, по умолчанию сохраняется для следующих серий
        assertEquals(SettingsRepository.AUDIO_MEMORY_PLAYLIST, repo.getAudioTrackMemoryMode())
        assertEquals(SettingsRepository.EXTERNAL_POSITION_APPLY, repo.getExternalPositionMode())
        assertEquals(SettingsRepository.BUFFER_SIZE_AUTO, repo.getTargetBufferMB())
        assertFalse(repo.isLiveModeEnabled())
        assertFalse(repo.isAutoPipEnabled())
        assertTrue(repo.isConfirmExitEnabled())
        assertTrue(repo.isVolumeBrightnessGesturesEnabled())
    }

    @Test
    fun `default 3d mode is anaglyph with stereo detection`() {
        val repo = repo()
        assertTrue(repo.isStereoAutoDetectEnabled())
        assertEquals(StereoOutputMode.ANAGLYPH, repo.getDefaultOutputMode())

        repo.setDefaultOutputMode(StereoOutputMode.LEFT_ONLY)
        repo.setStereoAutoDetectEnabled(false)
        assertEquals(StereoOutputMode.LEFT_ONLY, repo.getDefaultOutputMode())
        assertFalse(repo.isStereoAutoDetectEnabled())

        // Сохранение настроек видео в OSD обновляет тот же ключ
        repo.saveGlobalDefaults(StereoOutputMode.CARDBOARD_VR, StereoRenderer.AnaglyphType.RC_DUBOIS)
        assertEquals(StereoOutputMode.CARDBOARD_VR, repo.getDefaultOutputMode())
    }

    @Test
    fun `old startup playlist becomes a saved playlist once`() {
        prefs.edit { putString("startup_playlist_uri", "http://iptv.example.com/get.php?u=1") }
        val repo = repo()
        val playlists = repo.getSavedPlaylists()
        assertEquals(1, playlists.size)
        assertEquals("iptv.example.com", playlists[0].name)
        assertEquals("http://iptv.example.com/get.php?u=1", playlists[0].url)
        assertFalse(prefs.contains("startup_playlist_uri"))
        // Повторное чтение не добавляет копию
        assertEquals(1, repo.getSavedPlaylists().size)
    }

    @Test
    fun `saved playlists can be added, edited and removed`() {
        val repo = repo()
        repo.putSavedPlaylist(SavedPlaylist("1", "TV", "http://a/tv.m3u"))
        repo.putSavedPlaylist(SavedPlaylist("2", "Films", "http://b/f.m3u"))
        repo.putSavedPlaylist(SavedPlaylist("1", "TV HD", "http://a/hd.m3u"))
        assertEquals(listOf("TV HD", "Films"), repo.getSavedPlaylists().map { it.name })
        repo.removeSavedPlaylist("1")
        assertEquals(listOf("2"), repo.getSavedPlaylists().map { it.id })
    }

    @Test
    fun `watch history keeps one entry per source`() {
        val repo = repo()
        fun entry(source: String, uri: String) = HistoryEntry(HistoryEntry.KIND_FOLDER, source, "S", uri, uri, 5, 10, 0)
        repo.recordWatchHistory(entry("/series", "/series/e1.mkv"))
        repo.recordWatchHistory(entry("/movies", "/movies/m.mkv"))
        repo.recordWatchHistory(entry("/series", "/series/e2.mkv"))
        assertEquals(listOf("/series/e2.mkv", "/movies/m.mkv"), repo.getWatchHistory().map { it.uri })
        repo.removeWatchHistory("folder:/movies")
        assertEquals(1, repo.getWatchHistory().size)
    }

    @Test
    fun `smb servers keep credentials`() {
        val repo = repo()
        repo.putSmbServer(SmbServer("1", "NAS", "smb://nas/Video", "user", "secret", "HOME"))
        repo.putSmbServer(SmbServer("1", "NAS 2", "smb://nas/Video", "user", "secret2", "HOME"))
        val saved = repo.getSmbServers().single()
        assertEquals("NAS 2", saved.name)
        assertEquals("secret2", saved.password)
        assertEquals("HOME", saved.domain)
        repo.removeSmbServer("1")
        assertTrue(repo.getSmbServers().isEmpty())
    }

    @Test
    fun `control bar defaults keep all buttons and no back arrow`() {
        val repo = repo()
        assertTrue(repo.getHiddenControlButtons().isEmpty())
        assertFalse(repo.isBackButtonEnabled())
        assertEquals(SettingsRepository.SWIPE_PLAYLIST, repo.getHorizontalSwipeAction())

        repo.setHiddenControlButtons(setOf(SettingsRepository.BUTTON_SPEED, SettingsRepository.BUTTON_RESIZE))
        assertEquals(setOf(SettingsRepository.BUTTON_SPEED, SettingsRepository.BUTTON_RESIZE), repo.getHiddenControlButtons())
        repo.setHiddenControlButtons(emptySet())
        assertTrue(repo.getHiddenControlButtons().isEmpty())
    }

    @Test
    fun `hdr10plus is enabled by default and restarts the player`() {
        val repo = repo()
        assertTrue(repo.isHdr10PlusEnabled())
        val before = repo.getHardSettingsSignature()
        repo.setHdr10PlusEnabled(false)
        assertFalse(repo.isHdr10PlusEnabled())
        assertTrue(before != repo.getHardSettingsSignature())
    }

    @Test
    fun `reset all settings keeps user data`() {
        val repo = repo()
        repo.putSavedPlaylist(SavedPlaylist("1", "TV", "http://a/tv.m3u"))
        repo.setConfirmExitEnabled(false)
        repo.setAppLanguage("ru")
        repo.setDefaultPlaybackSpeed(1.5f)
        repo.setHiddenControlButtons(setOf(SettingsRepository.BUTTON_SPEED))
        val calibrationVersion = repo.getCalibrationVersion()

        repo.resetAllSettings()

        assertTrue(repo.isConfirmExitEnabled())
        assertEquals(SettingsRepository.LANG_SYSTEM_DEFAULT, repo.getAppLanguage())
        assertEquals(1.0f, repo.getDefaultPlaybackSpeed())
        assertTrue(repo.getHiddenControlButtons().isEmpty())
        assertEquals(listOf("TV"), repo.getSavedPlaylists().map { it.name })
        // Плеер перечитает калибровку
        assertTrue(repo.getCalibrationVersion() > calibrationVersion)
    }
}
