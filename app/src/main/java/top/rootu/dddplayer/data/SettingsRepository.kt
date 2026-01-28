package top.rootu.dddplayer.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
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
    fun setDecoderPriority(mode: Int) = prefs.edit { putInt("decoder_priority", mode) }

    fun isTunnelingEnabled(): Boolean = prefs.getBoolean("tunneling_enabled", false)
    fun setTunnelingEnabled(enabled: Boolean) = prefs.edit { putBoolean("tunneling_enabled", enabled) }

    // Принудительный Downmix в стерео
    fun isStereoDownmixEnabled(): Boolean = prefs.getBoolean("stereo_downmix", false)
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

    fun isMapDv7ToHevcEnabled(): Boolean = prefs.getBoolean("map_dv7_to_hevc", false)
    fun setMapDv7ToHevcEnabled(enabled: Boolean) = prefs.edit { putBoolean("map_dv7_to_hevc", enabled) }

    fun isFrameRateMatchingEnabled(): Boolean = prefs.getBoolean("frame_rate_matching", false)
    fun setFrameRateMatchingEnabled(enabled: Boolean) = prefs.edit { putBoolean("frame_rate_matching", enabled) }

    fun isSkipSilenceEnabled(): Boolean = prefs.getBoolean("skip_silence", false)
    fun setSkipSilenceEnabled(enabled: Boolean) = prefs.edit { putBoolean("skip_silence", enabled) }

    fun getPreferredAudioLang(): String = prefs.getString("pref_audio_lang", TRACK_DEFAULT) ?: TRACK_DEFAULT
    fun setPreferredAudioLang(lang: String) = prefs.edit { putString("pref_audio_lang", lang) }

    fun getPreferredSubLang(): String = prefs.getString("pref_sub_lang", TRACK_DEFAULT) ?: TRACK_DEFAULT
    fun setPreferredSubLang(lang: String) = prefs.edit { putString("pref_sub_lang", lang) }

    fun getLoudnessBoost(): Int = prefs.getInt("loudness_boost", 0)
    fun setLoudnessBoost(boost: Int) = prefs.edit { putInt("loudness_boost", boost) }

    // Действие кнопки "Вверх" ( 0 = Nothing, 1 = OSD, 2 = Side Menu)
    fun getUpButtonAction(): Int = prefs.getInt("up_button_action", 1)
    fun setUpButtonAction(action: Int) = prefs.edit { putInt("up_button_action", action) }

    // Сигнатура настроек, требующих полного перезапуска плеера
    fun getHardSettingsSignature(): String {
        val videoParams = "${getDecoderPriority()}_${isTunnelingEnabled()}_${isMapDv7ToHevcEnabled()}"
        val audioDownmix = "${isStereoDownmixEnabled()}_${getMixPreset()}}_${getMixFront()}_${getMixCenter()}_${getMixRear()}_${getMixMiddle()}_${getMixLfe()}"
        return "${videoParams}_${audioDownmix}"
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