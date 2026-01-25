package top.rootu.dddplayer.data

import android.content.Context
import android.content.SharedPreferences
import androidx.media3.exoplayer.DefaultRenderersFactory
import top.rootu.dddplayer.model.StereoOutputMode
import top.rootu.dddplayer.renderer.StereoRenderer

class SettingsRepository(context: Context) {
    private val db = AppDatabase.getDatabase(context)
    private val prefs: SharedPreferences =
        context.getSharedPreferences("global_prefs", Context.MODE_PRIVATE)

    companion object {
        const val TRACK_DEFAULT = ""
        const val TRACK_DEVICE = "device"
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

    // --- Global Player Preferences ---
    fun getDecoderPriority(): Int = prefs.getInt("decoder_priority", DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
    fun setDecoderPriority(mode: Int) = prefs.edit().putInt("decoder_priority", mode).apply()

    fun isTunnelingEnabled(): Boolean = prefs.getBoolean("tunneling_enabled", false)
    fun setTunnelingEnabled(enabled: Boolean) = prefs.edit().putBoolean("tunneling_enabled", enabled).apply()

    fun isMapDv7ToHevcEnabled(): Boolean = prefs.getBoolean("map_dv7_to_hevc", false)
    fun setMapDv7ToHevcEnabled(enabled: Boolean) = prefs.edit().putBoolean("map_dv7_to_hevc", enabled).apply()

    fun isFrameRateMatchingEnabled(): Boolean = prefs.getBoolean("frame_rate_matching", false)
    fun setFrameRateMatchingEnabled(enabled: Boolean) = prefs.edit().putBoolean("frame_rate_matching", enabled).apply()

    fun isSkipSilenceEnabled(): Boolean = prefs.getBoolean("skip_silence", false)
    fun setSkipSilenceEnabled(enabled: Boolean) = prefs.edit().putBoolean("skip_silence", enabled).apply()
    fun getPreferredAudioLang(): String = prefs.getString("pref_audio_lang", TRACK_DEFAULT) ?: TRACK_DEFAULT
    fun setPreferredAudioLang(lang: String) = prefs.edit().putString("pref_audio_lang", lang).apply()

    fun getPreferredSubLang(): String = prefs.getString("pref_sub_lang", TRACK_DEFAULT) ?: TRACK_DEFAULT
    fun setPreferredSubLang(lang: String) = prefs.edit().putString("pref_sub_lang", lang).apply()

    fun getLoudnessBoost(): Int = prefs.getInt("loudness_boost", 0)
    fun setLoudnessBoost(boost: Int) = prefs.edit().putInt("loudness_boost", boost).apply()

    // Сигнатура настроек, требующих полного перезапуска плеера
    fun getHardSettingsSignature(): String {
        return return "${getDecoderPriority()}_${isTunnelingEnabled()}_${isMapDv7ToHevcEnabled()}"
    }

    // --- Global Preferences Helpers ---
    fun getGlobalFloat(key: String, def: Float) = prefs.getFloat(key, def)
    fun putGlobalFloat(key: String, value: Float) = prefs.edit().putFloat(key, value).apply()

    fun getGlobalInt(key: String, def: Int) = prefs.getInt(key, def)
    fun putGlobalInt(key: String, value: Int) = prefs.edit().putInt(key, value).apply()

    fun getGlobalBoolean(key: String, def: Boolean) = prefs.getBoolean(key, def)
    fun putGlobalBoolean(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()

    fun getLastUpdateTime(): Long = prefs.getLong("last_update_check", 0)
    fun setLastUpdateTime(time: Long) = prefs.edit().putLong("last_update_check", time).apply()
    fun getLastUpdateInfo(): String? = prefs.getString("last_update_info_json", null)
    fun saveUpdateInfo(json: String?) {
        prefs.edit()
            .putString("last_update_info_json", json)
            .putLong("last_update_check", System.currentTimeMillis())
            .apply()
    }

    fun saveGlobalDefaults(
        outputMode: StereoOutputMode,
        anaglyphType: StereoRenderer.AnaglyphType
    ) {
        prefs.edit()
            .putInt("def_output_mode", outputMode.ordinal)
            .putInt("def_anaglyph_type", anaglyphType.ordinal)
            .apply()
    }
}