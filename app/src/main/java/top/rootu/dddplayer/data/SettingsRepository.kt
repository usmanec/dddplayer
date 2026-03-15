package top.rootu.dddplayer.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.media3.exoplayer.DefaultRenderersFactory
import top.rootu.dddplayer.model.StereoOutputMode
import top.rootu.dddplayer.renderer.StereoRenderer

class SettingsRepository private constructor(context: Context) {

    private val db = AppDatabase.getDatabase(context)
    private val prefs: SharedPreferences =
        context.getSharedPreferences("global_prefs", Context.MODE_PRIVATE)

    companion object {
        const val TRACK_DEFAULT = ""
        const val TRACK_DEVICE = "device"
        const val LANG_SYSTEM_DEFAULT = "system"
        const val RESUME_ASK = 0
        const val RESUME_ALWAYS = 1
        const val RESUME_NEVER = 2

        @Volatile
        private var instance: SettingsRepository? = null

        /**
         * Получение единственного экземпляра репозитория (Singleton).
         * Гарантирует потокобезопасность при инициализации.
         */
        fun getInstance(context: Context): SettingsRepository {
            return instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    suspend fun getVideoSettings(uri: String): VideoSettings? {
        return db.videoSettingsDao().getSettings(uri)
    }

    suspend fun saveVideoSettings(settings: VideoSettings) {
        db.videoSettingsDao().saveSettings(settings)
    }

    suspend fun cleanupOldSettings() {
        db.videoSettingsDao().deleteOldSettings(System.currentTimeMillis() - 2592000000L)
    }

    // --- Zoom Settings ---
    fun isRememberZoomEnabled(): Boolean = prefs.getBoolean("remember_zoom", false)
    fun setRememberZoomEnabled(enabled: Boolean) = prefs.edit { putBoolean("remember_zoom", enabled) }

    // Сохраняем сам режим (Fit, Fill, Zoom, Scale)
    fun getGlobalResizeMode(): Int = prefs.getInt("global_resize_mode", 0) // 0 = FIT
    fun setGlobalResizeMode(modeOrdinal: Int) = prefs.edit { putInt("global_resize_mode", modeOrdinal) }

    // Custom Video Zoom Scale
    fun getZoomScalePercent(): Int = prefs.getInt("video_zoom_scale", 115)
    fun setZoomScalePercent(percent: Int) = prefs.edit { putInt("video_zoom_scale", percent) }

    // --- UI Settings ---
    fun getPauseDimLevel(): Int = prefs.getInt("pause_dim_level", 60)
    fun setPauseDimLevel(level: Int) = prefs.edit { putInt("pause_dim_level", level) }
    fun isShowPlaylistIndexEnabled(): Boolean = prefs.getBoolean("show_playlist_index", true)
    fun setShowPlaylistIndexEnabled(enabled: Boolean) = prefs.edit { putBoolean("show_playlist_index", enabled) }

    // --- Global Player Preferences ---
    fun getDecoderPriority(): Int = prefs.getInt("decoder_priority", DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
    fun setDecoderPriority(mode: Int) = prefs.edit { putInt("decoder_priority", mode) }

    fun isTunnelingEnabled(): Boolean = prefs.getBoolean("tunneling_enabled", false)
    fun setTunnelingEnabled(enabled: Boolean) = prefs.edit { putBoolean("tunneling_enabled", enabled) }

    // Принудительный Downmix в стерео
    fun isStereoDownmixEnabled(): Boolean = prefs.getBoolean("stereo_downmix", true)
    fun setStereoDownmixEnabled(enabled: Boolean) = prefs.edit { putBoolean("stereo_downmix", enabled) }

    fun getMixPreset(): Int = prefs.getInt("mix_preset", 0)
    fun setMixPreset(id: Int) = prefs.edit { putInt("mix_preset", id) }

    fun getMixFront(): Float = prefs.getFloat("mix_front", 1.0f)
    fun setMixFront(value: Float) = prefs.edit { putFloat("mix_front", value) }

    fun getMixCenter(): Float = prefs.getFloat("mix_center", 1.0f)
    fun setMixCenter(value: Float) = prefs.edit { putFloat("mix_center", value) }

    fun getMixLfe(): Float = prefs.getFloat("mix_lfe", 0.0f)
    fun setMixLfe(value: Float) = prefs.edit { putFloat("mix_lfe", value) }

    fun getMixRear(): Float = prefs.getFloat("mix_rear", 1.0f)
    fun setMixRear(value: Float) = prefs.edit { putFloat("mix_rear", value) }

    fun getMixMiddle(): Float = prefs.getFloat("mix_middle", 1.0f)
    fun setMixMiddle(value: Float) = prefs.edit { putFloat("mix_middle", value) }

    fun isMapDvToHevcEnabled(): Boolean = prefs.getBoolean("map_dv_to_hevc", true)
    fun setMapDvToHevcEnabled(enabled: Boolean) = prefs.edit { putBoolean("map_dv_to_hevc", enabled) }

    fun isFrameRateMatchingEnabled(): Boolean = prefs.getBoolean("frame_rate_matching", false)
    fun setFrameRateMatchingEnabled(enabled: Boolean) = prefs.edit { putBoolean("frame_rate_matching", enabled) }

    fun isAfrResolutionSwitchEnabled(): Boolean = prefs.getBoolean("afr_resolution", false)
    fun setAfrResolutionSwitchEnabled(enabled: Boolean) = prefs.edit { putBoolean("afr_resolution", enabled) }

    fun isAfrFpsCorrectionEnabled(): Boolean = prefs.getBoolean("afr_fps_correction", false)
    fun setAfrFpsCorrectionEnabled(enabled: Boolean) = prefs.edit { putBoolean("afr_fps_correction", enabled) }

    fun isAfrDoubleRefreshRateEnabled(): Boolean = prefs.getBoolean("afr_double_refresh", true)
    fun setAfrDoubleRefreshRateEnabled(enabled: Boolean) = prefs.edit { putBoolean("afr_double_refresh", enabled) }

    fun isAfrSkip24RateEnabled(): Boolean = prefs.getBoolean("afr_skip_24", false)
    fun setAfrSkip24RateEnabled(enabled: Boolean) = prefs.edit { putBoolean("afr_skip_24", enabled) }

    fun getAfrPauseMs(): Int = prefs.getInt("afr_pause_ms", 2000)
    fun setAfrPauseMs(ms: Int) = prefs.edit { putInt("afr_pause_ms", ms) }

    fun isAfrSkipShortsEnabled(): Boolean = prefs.getBoolean("afr_skip_shorts", true)
    fun setAfrSkipShortsEnabled(enabled: Boolean) = prefs.edit { putBoolean("afr_skip_shorts", enabled) }

    fun isSkipSilenceEnabled(): Boolean = prefs.getBoolean("skip_silence", false)
    fun setSkipSilenceEnabled(enabled: Boolean) = prefs.edit { putBoolean("skip_silence", enabled) }

    fun getPreferredAudioLang(): String = prefs.getString("pref_audio_lang", TRACK_DEFAULT) ?: TRACK_DEFAULT
    fun setPreferredAudioLang(lang: String) = prefs.edit { putString("pref_audio_lang", lang) }

    fun getPreferredSubLang(): String = prefs.getString("pref_sub_lang", TRACK_DEFAULT) ?: TRACK_DEFAULT
    fun setPreferredSubLang(lang: String) = prefs.edit { putString("pref_sub_lang", lang) }

    fun getLoudnessBoost(): Int = prefs.getInt("loudness_boost", 0)
    fun setLoudnessBoost(boost: Int) = prefs.edit { putInt("loudness_boost", boost) }

    fun getAppLanguage(): String = prefs.getString("app_language", LANG_SYSTEM_DEFAULT) ?: LANG_SYSTEM_DEFAULT
    fun setAppLanguage(langCode: String) = prefs.edit { putString("app_language", langCode) }

    fun getResumeMode(): Int = prefs.getInt("resume_mode", RESUME_ASK)
    fun setResumeMode(mode: Int) = prefs.edit { putInt("resume_mode", mode) }

    fun saveLastPlayedChannel(uri: String?, title: String?, group: String?) {
        prefs.edit {
            putString("last_channel_uri", uri)
            putString("last_channel_title", title)
            putString("last_channel_group", group)
        }
    }

    fun getLastPlayedChannel(): Triple<String?, String?, String?> {
        return Triple(
            prefs.getString("last_channel_uri", null),
            prefs.getString("last_channel_title", null),
            prefs.getString("last_channel_group", null)
        )
    }

    // Плейлист при запуске
    fun getStartupPlaylistUri(): String? = prefs.getString("startup_playlist_uri", null)
    fun setStartupPlaylistUri(uri: String?) = prefs.edit { putString("startup_playlist_uri", uri) }

    // Эфирный режим
    fun isLiveModeEnabled(): Boolean = prefs.getBoolean("live_mode_control", true)
    fun setLiveModeEnabled(enabled: Boolean) = prefs.edit { putBoolean("live_mode_control", enabled) }

    // Инфопанель при смене трека
    fun isShowInfoOnTrackChange(): Boolean = prefs.getBoolean("show_info_on_change", true)
    fun setShowInfoOnTrackChange(enabled: Boolean) = prefs.edit { putBoolean("show_info_on_change", enabled) }

    // Отображение буфера (0 = Выкл, 1 = В панели, 2 = У часов)
    fun getBufferDisplayMode(): Int = prefs.getInt("buffer_display_mode", 0)
    fun setBufferDisplayMode(mode: Int) = prefs.edit { putInt("buffer_display_mode", mode) }

    // Размер буфера в мегабайтах (-1 = Авто)
    fun getTargetBufferOptions(): List<Int> {
        val uiReserveMB = 96
        val bufferSizeOptions = listOf(-1, 16, 32, 48, 64, 96, 128, 160, 192, 224, 256, 320, 352, 384, 448, 512, 768, 1024) // -1 for Auto
        val maxHeapMB = Runtime.getRuntime().maxMemory() / 1024 / 1024

        // Разрешаем выбирать буфер, от кучи с учетом резерва под UI
        val safeLimit = (maxHeapMB - uiReserveMB).toInt()

        return bufferSizeOptions.filter { it == -1 || it <= safeLimit || it <= 64}
    }
    fun getTargetBufferCorrectMB(): Int {
        val availableOptions = getTargetBufferOptions()
        val currentVal = getTargetBufferMB()
        var currentIndex = availableOptions.indexOf(currentVal)
        if (currentIndex < 0) {
            currentIndex = 0
            setTargetBufferMB(availableOptions[currentIndex])
        }

        return availableOptions[currentIndex]
    }
    fun getTargetBufferMB(): Int = prefs.getInt("target_buffer_mb", -1)
    fun setTargetBufferMB(sizeInMB: Int) = prefs.edit { putInt("target_buffer_mb", sizeInMB) }

    // Действие кнопки "Вверх" ( 0 = Nothing, 1 = OSD, 2 = Side Menu, 3=InfoPanel)
    fun getUpButtonAction(): Int = prefs.getInt("up_button_action", 3)
    fun setUpButtonAction(action: Int) = prefs.edit { putInt("up_button_action", action) }

    // Действие кнопки "OK" (0 = Pause, 1 = Pause+Panel, 2 = Panel)
    fun getOkButtonAction(): Int = prefs.getInt("ok_button_action", 1) // По умолчанию Pause+Panel
    fun setOkButtonAction(action: Int) = prefs.edit { putInt("ok_button_action", action) }

    // Горизонтальный свайп ( 0 = None, 1 = Seek, 2 = Playlist)
    fun getHorizontalSwipeAction(): Int = prefs.getInt("horizontal_swipe_action", 2)
    fun setHorizontalSwipeAction(action: Int) = prefs.edit { putInt("horizontal_swipe_action", action) }

    fun isShowBuffer(): Boolean = prefs.getBoolean("show_buffer", false)
    fun setShowBuffer(enabled: Boolean) = prefs.edit { putBoolean("show_buffer", enabled) }

    // Храним уровень прозрачности часов (-1 = выкл, 0-100 = уровень)
    fun getClockTransparency(): Int = prefs.getInt("clock_transparency", 40)
    fun setClockTransparency(transparency: Int) = prefs.edit { putInt("clock_transparency", transparency) }

    // Сигнатура настроек, требующих полного перезапуска плеера
    fun getHardSettingsSignature(): Int {
        val videoParams = "${getDecoderPriority()}_${isTunnelingEnabled()}_${isMapDvToHevcEnabled()}"
        val audioDownmix = "${isStereoDownmixEnabled()}_${getMixPreset()}}_${getMixFront()}_${getMixCenter()}_${getMixRear()}_${getMixMiddle()}_${getMixLfe()}"
        val audioParams = "${isSkipSilenceEnabled()}_${getLoudnessBoost()}_${audioDownmix}"
        return "${videoParams}_${audioParams}_${getTargetBufferMB()}".hashCode()
    }

    // --- Global Preferences Helpers ---
    fun getGlobalFloat(key: String, def: Float) = prefs.getFloat(key, def)
    fun putGlobalFloat(key: String, value: Float) = prefs.edit { putFloat(key, value) }

    fun getGlobalInt(key: String, def: Int) = prefs.getInt(key, def)
    fun putGlobalInt(key: String, value: Int) = prefs.edit { putInt(key, value) }

    fun getGlobalBoolean(key: String, def: Boolean) = prefs.getBoolean(key, def)
    fun putGlobalBoolean(key: String, value: Boolean) = prefs.edit { putBoolean(key, value) }

    fun getLastUpdateTime(): Long = prefs.getLong("last_update_check", 0)
    fun setLastUpdateTime(time: Long) = prefs.edit { putLong("last_update_check", time) }

    fun getLastUpdateInfo(): String? = prefs.getString("last_update_info_json", null)
    fun saveUpdateInfo(json: String?) {
        prefs.edit {
            putString("last_update_info_json", json)
            putLong("last_update_check", System.currentTimeMillis())
        }
    }

    fun saveGlobalDefaults(
        outputMode: StereoOutputMode,
        anaglyphType: StereoRenderer.AnaglyphType
    ) {
        prefs.edit {
            putInt("def_output_mode", outputMode.ordinal)
            putInt("def_anaglyph_type", anaglyphType.ordinal)
        }
    }
}