package top.rootu.dddplayer.data

import android.content.Context
import android.content.SharedPreferences
import top.rootu.dddplayer.model.StereoOutputMode
import top.rootu.dddplayer.renderer.StereoRenderer

class SettingsRepository(context: Context) {
    private val db = AppDatabase.getDatabase(context)
    private val prefs: SharedPreferences =
        context.getSharedPreferences("global_prefs", Context.MODE_PRIVATE)

    suspend fun getVideoSettings(uri: String): VideoSettings? {
        return db.videoSettingsDao().getSettings(uri)
    }

    suspend fun saveVideoSettings(settings: VideoSettings) {
        db.videoSettingsDao().saveSettings(settings)
    }

    suspend fun cleanupOldSettings() {
        db.videoSettingsDao().deleteOldSettings(System.currentTimeMillis() - 2592000000L)
    }

    // Global Player Preferences
    fun getDecoderPriority(): Int = prefs.getInt("decoder_priority", 1) // 1 = EXTENSION_RENDERER_MODE_ON
    fun setDecoderPriority(mode: Int) = prefs.edit().putInt("decoder_priority", mode).apply()

    fun isTunnelingEnabled(): Boolean = prefs.getBoolean("tunneling_enabled", true)
    fun setTunnelingEnabled(enabled: Boolean) = prefs.edit().putBoolean("tunneling_enabled", enabled).apply()

    fun isAudioPassthroughEnabled(): Boolean = prefs.getBoolean("audio_passthrough", false) // По умолчанию выкл, т.к. может ломать громкость
    fun setAudioPassthroughEnabled(enabled: Boolean) = prefs.edit().putBoolean("audio_passthrough", enabled).apply()

    fun getPreferredAudioLang(): String = prefs.getString("pref_audio_lang", "") ?: "" // "" = System Default
    fun setPreferredAudioLang(lang: String) = prefs.edit().putString("pref_audio_lang", lang).apply()

    fun getPreferredSubLang(): String = prefs.getString("pref_sub_lang", "") ?: ""
    fun setPreferredSubLang(lang: String) = prefs.edit().putString("pref_sub_lang", lang).apply()
    /**
     * Возвращает строку, зависящую от настроек, требующих пересоздания плеера
     */
    fun getHardSettingsSignature(): String {
        return "${getDecoderPriority()}_${isAudioPassthroughEnabled()}"
    }

    // Global Preferences Helpers
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