package top.rootu.dddplayer.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.media3.exoplayer.DefaultRenderersFactory
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import top.rootu.dddplayer.logic.AnaglyphLogic
import top.rootu.dddplayer.logic.HomeLogic
import top.rootu.dddplayer.model.AnaglyphCalibration
import top.rootu.dddplayer.model.AudioTrackStat
import top.rootu.dddplayer.model.HistoryEntry
import top.rootu.dddplayer.model.SavedPlaylist
import top.rootu.dddplayer.model.SmbServer
import top.rootu.dddplayer.model.StereoOutputMode
import top.rootu.dddplayer.model.SubtitleStyle
import top.rootu.dddplayer.player.NetworkBufferLoadControl
import top.rootu.dddplayer.renderer.StereoRenderer
import top.rootu.dddplayer.viewmodel.GlassesGroup
import java.util.UUID

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

        // Позиция, переданная внешним приложением (например, Лампой)
        const val EXTERNAL_POSITION_APPLY = 0
        const val EXTERNAL_POSITION_ASK = 1

        // Действие кнопки "Вверх"
        const val UP_ACTION_NONE = 0
        const val UP_ACTION_OSD = 1
        const val UP_ACTION_MENU = 2
        const val UP_ACTION_INFO = 3

        // Действие кнопки "OK" при скрытой панели
        const val OK_ACTION_PAUSE = 0
        const val OK_ACTION_PAUSE_PANEL = 1
        const val OK_ACTION_PANEL = 2

        // Горизонтальный свайп
        const val SWIPE_NONE = 0
        const val SWIPE_SEEK = 1
        const val SWIPE_PLAYLIST = 2 // Пред/След, выход на краях плейлиста
        const val SWIPE_EXIT_NEXT = 3 // Вправо - выход, влево - следующее видео

        // Кнопки нижней панели, которые можно скрыть
        const val BUTTON_SPEED = "speed"
        const val BUTTON_RESIZE = "resize"
        const val BUTTON_PIP = "pip"
        const val BUTTON_QUALITY = "quality"
        const val BUTTON_AUDIO = "audio"
        const val BUTTON_SUBTITLES = "subtitles"
        val HIDEABLE_BUTTONS = listOf(BUTTON_SPEED, BUTTON_RESIZE, BUTTON_PIP, BUTTON_QUALITY, BUTTON_AUDIO, BUTTON_SUBTITLES)

        // Прозрачность часов: -1 = часы выключены, 0..80 = прозрачность в %
        const val CLOCK_OFF = -1

        // Отображение процента буфера
        const val BUFFER_DISPLAY_OFF = 0
        const val BUFFER_DISPLAY_PANEL = 1
        const val BUFFER_DISPLAY_CLOCK = 2 // Рядом с часами (одиночные часы и инфопанель)

        const val BUFFER_SIZE_AUTO = -1
        // Запоминание аудиодорожки
        const val AUDIO_MEMORY_OFF = 0
        const val AUDIO_MEMORY_PLAYLIST = 1 // Выбранная дорожка - для следующих элементов плейлиста
        const val AUDIO_MEMORY_ALL = 2 // + наиболее используемые дорожки для любых видео (экспериментально)
        const val MAX_AUDIO_TRACK_STATS = 20
        // Автовключение субтитров при загрузке видео
        const val SUB_AUTO_DEFAULT = 0 // Как решит плеер (прежнее поведение)
        const val SUB_AUTO_OFF = 1
        const val SUB_AUTO_FORCED = 2 // Только форсированные
        const val SUB_AUTO_FULL = 3 // Полные на предпочитаемом языке

        // Совместимость с Dolby Vision: подмена декодера DV на HEVC (базовый слой HDR10/HLG)
        const val DV_MODE_OFF = 0 // Системный DV декодер
        const val DV_MODE_AUTO = 1 // HEVC, только если экран или декодер не поддерживают Dolby Vision
        const val DV_MODE_ALWAYS = 2 // Всегда HEVC
        const val DV_MODE_CONVERT = 3 // Profile 7 -> 8.1 (libdovi), иначе как Авто
        const val RENDER_API_AUTO = 0

        // Не настройки, а данные пользователя: сброс настроек их сохраняет
        val USER_DATA_KEYS = setOf(
            "watch_history", "saved_playlists", "smb_servers", "startup_playlist_uri", "last_channel_uri",
            "last_update_check", "last_update_info_json"
        )
        const val RENDER_API_GLES2 = 1

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

    // RENDER_API_AUTO = GLES 3.0 (если доступно), RENDER_API_GLES2 = принудительно GLES 2.0
    fun getRenderApiMode(): Int = prefs.getInt("render_api_mode", RENDER_API_AUTO)
    fun setRenderApiMode(mode: Int) = prefs.edit { putInt("render_api_mode", mode) }

    // --- Zoom Settings ---
    fun isRememberZoomEnabled(): Boolean = prefs.getBoolean("remember_zoom", false)
    fun setRememberZoomEnabled(enabled: Boolean) = prefs.edit { putBoolean("remember_zoom", enabled) }

    // Сохраняем сам режим (Fit, Fill, Zoom, Scale)
    fun getGlobalResizeMode(): Int = prefs.getInt("global_resize_mode", 0) // 0 = FIT
    fun setGlobalResizeMode(modeOrdinal: Int) = prefs.edit { putInt("global_resize_mode", modeOrdinal) }

    // Custom Video Zoom Scale
    fun getZoomScalePercent(): Int = prefs.getInt("video_zoom_scale", 115)
    fun setZoomScalePercent(percent: Int) = prefs.edit { putInt("video_zoom_scale", percent) }

    // Скорость, с которой начинается воспроизведение (прямой эфир всегда идет на 1.00x)
    fun getDefaultPlaybackSpeed(): Float = prefs.getFloat("default_playback_speed", 1.0f)
    fun setDefaultPlaybackSpeed(speed: Float) = prefs.edit { putFloat("default_playback_speed", speed) }

    // Своя скорость (пункт меню скорости с ползунком)
    fun getCustomPlaybackSpeed(): Float = prefs.getFloat("custom_playback_speed", 1.10f)
    fun setCustomPlaybackSpeed(speed: Float) = prefs.edit { putFloat("custom_playback_speed", speed) }

    // --- Playlist Settings ---
    // Плейлист, который открывается при запуске из лаунчера
    // --- Стартовый экран: плейлисты по ссылке и история просмотра ---

    /**
     * Плейлисты стартового экрана. Прежний "стартовый плейлист" (startup_playlist_uri) переносится сюда
     * один раз отдельным пунктом.
     */
    fun getSavedPlaylists(): List<SavedPlaylist> {
        migrateStartupPlaylist()
        val json = prefs.getString("saved_playlists", null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                SavedPlaylist(obj.getString("id"), obj.optString("name"), obj.getString("url"))
            }
        } catch (e: JSONException) {
            emptyList()
        }
    }

    fun saveSavedPlaylists(playlists: List<SavedPlaylist>) {
        val array = JSONArray()
        playlists.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name).put("url", it.url)) }
        prefs.edit { putString("saved_playlists", array.toString()) }
    }

    /** Добавляет новый плейлист или заменяет плейлист с тем же id. */
    fun putSavedPlaylist(playlist: SavedPlaylist) {
        val list = getSavedPlaylists()
        val updated = if (list.any { it.id == playlist.id }) list.map { if (it.id == playlist.id) playlist else it } else list + playlist
        saveSavedPlaylists(updated)
    }

    fun removeSavedPlaylist(id: String) = saveSavedPlaylists(getSavedPlaylists().filter { it.id != id })

    private fun migrateStartupPlaylist() {
        if (!prefs.contains("startup_playlist_uri")) return
        val url = prefs.getString("startup_playlist_uri", null)?.trim()
        prefs.edit { remove("startup_playlist_uri") }
        if (url.isNullOrEmpty()) return
        val json = prefs.getString("saved_playlists", null)
        val array = try { if (json != null) JSONArray(json) else JSONArray() } catch (e: JSONException) { JSONArray() }
        array.put(JSONObject().put("id", UUID.randomUUID().toString()).put("name", HomeLogic.defaultPlaylistName(url)).put("url", url))
        prefs.edit { putString("saved_playlists", array.toString()) }
    }

    /** Сетевые папки SMB стартового экрана (пароль хранится в настройках приложения, недоступных другим приложениям). */
    fun getSmbServers(): List<SmbServer> {
        val json = prefs.getString("smb_servers", null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                SmbServer(
                    id = obj.getString("id"),
                    name = obj.optString("name"),
                    address = obj.getString("address"),
                    user = obj.optString("user"),
                    password = obj.optString("password"),
                    domain = obj.optString("domain")
                )
            }
        } catch (e: JSONException) {
            emptyList()
        }
    }

    private fun saveSmbServers(servers: List<SmbServer>) {
        val array = JSONArray()
        servers.forEach {
            array.put(
                JSONObject().put("id", it.id).put("name", it.name).put("address", it.address)
                    .put("user", it.user).put("password", it.password).put("domain", it.domain)
            )
        }
        prefs.edit { putString("smb_servers", array.toString()) }
    }

    fun putSmbServer(server: SmbServer) {
        val list = getSmbServers()
        saveSmbServers(if (list.any { it.id == server.id }) list.map { if (it.id == server.id) server else it } else list + server)
    }

    fun removeSmbServer(id: String) = saveSmbServers(getSmbServers().filter { it.id != id })

    /** История "Продолжить просмотр", новые сверху. */
    fun getWatchHistory(): List<HistoryEntry> {
        val json = prefs.getString("watch_history", null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                HistoryEntry(
                    kind = obj.getString("kind"),
                    source = obj.getString("source"),
                    sourceName = obj.optString("sourceName"),
                    title = obj.optString("title"),
                    uri = obj.getString("uri"),
                    positionMs = obj.optLong("position"),
                    durationMs = obj.optLong("duration"),
                    updatedAt = obj.optLong("updatedAt")
                )
            }
        } catch (e: JSONException) {
            emptyList()
        }
    }

    private fun saveWatchHistory(history: List<HistoryEntry>) {
        val array = JSONArray()
        history.forEach {
            array.put(
                JSONObject().put("kind", it.kind).put("source", it.source).put("sourceName", it.sourceName)
                    .put("title", it.title).put("uri", it.uri).put("position", it.positionMs)
                    .put("duration", it.durationMs).put("updatedAt", it.updatedAt)
            )
        }
        prefs.edit { putString("watch_history", array.toString()) }
    }

    /** Обновляет запись источника (серия в той же папке заменяет прежнюю запись). */
    fun recordWatchHistory(entry: HistoryEntry) = saveWatchHistory(HomeLogic.upsertHistory(getWatchHistory(), entry))

    fun removeWatchHistory(key: String) = saveWatchHistory(getWatchHistory().filter { it.key != key })

    // Последний канал M3U плейлиста (восстанавливается только по точному URI)
    fun getLastPlayedChannelUri(): String? = prefs.getString("last_channel_uri", null)
    fun setLastPlayedChannelUri(uri: String?) = prefs.edit { putString("last_channel_uri", uri) }

    // Эфирный режим (OK - плейлист, Влево/Вправо - переключение каналов) для плейлистов с live-потоком.
    // По умолчанию выключен
    fun isLiveModeEnabled(): Boolean = prefs.getBoolean("live_mode_control", false)
    fun setLiveModeEnabled(enabled: Boolean) = prefs.edit { putBoolean("live_mode_control", enabled) }

    // Показывать инфопанель при смене элемента плейлиста. По умолчанию выключено
    fun isShowInfoOnTrackChange(): Boolean = prefs.getBoolean("show_info_on_change", false)
    fun setShowInfoOnTrackChange(enabled: Boolean) = prefs.edit { putBoolean("show_info_on_change", enabled) }

    // --- Buffer Settings ---
    // Отображение процента буфера (BUFFER_DISPLAY_*)
    fun getBufferDisplayMode(): Int = prefs.getInt("buffer_display_mode", BUFFER_DISPLAY_OFF)
    fun setBufferDisplayMode(mode: Int) = prefs.edit { putInt("buffer_display_mode", mode) }

    // Размер буфера сетевого видео в мегабайтах (BUFFER_SIZE_AUTO = текущие параметры плеера)
    fun getTargetBufferOptions(): List<Int> {
        val bufferSizeOptions = listOf(BUFFER_SIZE_AUTO, 16, 32, 48, 64, 96, 128, 160, 192, 224, 256, 320, 352, 384, 448, 512, 768, 1024)

        // Разрешаем выбирать буфер в пределах кучи с учетом резерва под UI (тот же лимит, что у плеера)
        val safeLimit = NetworkBufferLoadControl.maxBufferBytesForHeap() / 1024 / 1024

        return bufferSizeOptions.filter { it == BUFFER_SIZE_AUTO || it <= safeLimit }
    }

    // Значение из настроек, если оно допустимо для текущего устройства, иначе "Авто"
    fun getTargetBufferCorrectMB(): Int {
        val currentVal = getTargetBufferMB()
        return if (currentVal in getTargetBufferOptions()) currentVal else BUFFER_SIZE_AUTO
    }
    fun getTargetBufferMB(): Int = prefs.getInt("target_buffer_mb", BUFFER_SIZE_AUTO)
    fun setTargetBufferMB(sizeInMB: Int) = prefs.edit { putInt("target_buffer_mb", sizeInMB) }

    // --- UI Settings ---
    fun getPauseDimLevel(): Int = prefs.getInt("pause_dim_level", 60)
    fun setPauseDimLevel(level: Int) = prefs.edit { putInt("pause_dim_level", level) }
    fun isShowPlaylistIndexEnabled(): Boolean = prefs.getBoolean("show_playlist_index", true)
    fun setShowPlaylistIndexEnabled(enabled: Boolean) = prefs.edit { putBoolean("show_playlist_index", enabled) }

    // --- Global Player Preferences ---
    // Автоматический переход в PiP при сворачивании. По умолчанию выключен
    fun isAutoPipEnabled(): Boolean = prefs.getBoolean("auto_pip", false)
    fun setAutoPipEnabled(enabled: Boolean) = prefs.edit { putBoolean("auto_pip", enabled) }

    // Заставку и краткое содержание (отрезки из Intent) пропускать без кнопки
    fun isAutoSkipIntroEnabled(): Boolean = prefs.getBoolean("auto_skip_intro", false)
    fun setAutoSkipIntroEnabled(enabled: Boolean) = prefs.edit { putBoolean("auto_skip_intro", enabled) }

    // Приоритет встроенных декодеров (EXTENSION_RENDERER_MODE_*) отдельно для видео (AV1) и аудио (FFmpeg).
    // Пока не заданы, берут прежнюю общую настройку "decoder_priority", если ее меняли
    private fun legacyDecoderPriority(default: Int): Int = prefs.getInt("decoder_priority", default)

    // AV1 по умолчанию HW+: аппаратный декодер устройства, программный dav1d - запасной (он тяжел для 4K)
    fun getVideoDecoderPriority(): Int =
        prefs.getInt("video_decoder_priority", legacyDecoderPriority(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON))
    fun setVideoDecoderPriority(mode: Int) = prefs.edit { putInt("video_decoder_priority", mode) }

    fun getAudioDecoderPriority(): Int =
        prefs.getInt("audio_decoder_priority", legacyDecoderPriority(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER))
    fun setAudioDecoderPriority(mode: Int) = prefs.edit { putInt("audio_decoder_priority", mode) }

    fun isTunnelingEnabled(): Boolean = prefs.getBoolean("tunneling_enabled", false)

    // Динамические метаданные HDR10+: выключено - вырезаем их, видео играет как HDR10
    fun isHdr10PlusEnabled(): Boolean = prefs.getBoolean("hdr10plus_enabled", true)
    fun setHdr10PlusEnabled(enabled: Boolean) = prefs.edit { putBoolean("hdr10plus_enabled", enabled) }
    fun setTunnelingEnabled(enabled: Boolean) = prefs.edit { putBoolean("tunneling_enabled", enabled) }

    // DTS-HD MA и TrueHD через несущую IEC 61937 (как Kodi). По умолчанию выкл, чтобы не менять поведение
    fun isHdAudioIecEnabled(): Boolean = prefs.getBoolean("hd_audio_iec", false)
    fun setHdAudioIecEnabled(enabled: Boolean) = prefs.edit { putBoolean("hd_audio_iec", enabled) }

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

    // Режим Dolby Vision (DV_MODE_*), по умолчанию "Авто": DV там, где экран и декодер его умеют, иначе HDR10/HLG.
    // Миграция (один раз): выключенный старый переключатель "map_dv_to_hevc" -> "Dolby Vision" (DV_MODE_OFF);
    // "HDR10/HLG" (прежнее значение по умолчанию, его записывала и версия 1.4.0) -> "Авто".
    // Явно выбранные "Dolby Vision" и "P7 -> P8.1" не меняются.
    fun getDvMode(): Int {
        if (!prefs.getBoolean("dv_mode_auto_default", false)) {
            val legacyOff = prefs.contains("map_dv_to_hevc") && !prefs.getBoolean("map_dv_to_hevc", true)
            val stored = if (prefs.contains("dv_mode")) prefs.getInt("dv_mode", DV_MODE_AUTO) else null
            val mode = when {
                legacyOff -> DV_MODE_OFF
                stored == null || stored == DV_MODE_ALWAYS -> DV_MODE_AUTO
                else -> stored
            }
            prefs.edit {
                putInt("dv_mode", mode)
                remove("map_dv_to_hevc")
                putBoolean("dv_mode_auto_default", true)
            }
            return mode
        }
        return prefs.getInt("dv_mode", DV_MODE_AUTO)
    }
    fun setDvMode(mode: Int) = prefs.edit { putInt("dv_mode", mode) }

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

    fun getSubtitleAutoMode(): Int = prefs.getInt("subtitle_auto_mode", SUB_AUTO_DEFAULT)
    fun setSubtitleAutoMode(mode: Int) = prefs.edit { putInt("subtitle_auto_mode", mode) }

    fun getLoudnessBoost(): Int = prefs.getInt("loudness_boost", 0)
    fun setLoudnessBoost(boost: Int) = prefs.edit { putInt("loudness_boost", boost) }

    fun getAppLanguage(): String = prefs.getString("app_language", LANG_SYSTEM_DEFAULT) ?: LANG_SYSTEM_DEFAULT
    fun setAppLanguage(langCode: String) = prefs.edit { putString("app_language", langCode) }

    // Запоминание аудиодорожки (AUDIO_MEMORY_*): в плейлисте и (экспериментально) между разными видео
    fun getAudioTrackMemoryMode(): Int = prefs.getInt("audio_track_memory", AUDIO_MEMORY_OFF)
    fun setAudioTrackMemoryMode(mode: Int) = prefs.edit { putInt("audio_track_memory", mode) }

    /**
     * Наиболее используемые аудиодорожки в порядке приоритета:
     * сначала упорядоченные пользователем, затем по числу выборов.
     */
    fun getAudioTrackStats(): List<AudioTrackStat> {
        val json = prefs.getString("audio_track_stats", null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                AudioTrackStat(
                    signature = obj.getString("signature"),
                    label = obj.optString("label"),
                    count = obj.optInt("count", 1),
                    pinned = obj.optBoolean("pinned", false)
                )
            }
        } catch (e: JSONException) {
            emptyList()
        }
    }

    private fun saveAudioTrackStats(stats: List<AudioTrackStat>) {
        // Закрепленные - в заданном порядке, остальные - по числу выборов; лишние незакрепленные отбрасываем
        val pinned = stats.filter { it.pinned }
        val others = stats.filter { !it.pinned }.sortedByDescending { it.count }
        val ordered = pinned + others.take((MAX_AUDIO_TRACK_STATS - pinned.size).coerceAtLeast(0))
        val array = JSONArray()
        ordered.forEach { stat ->
            array.put(JSONObject().apply {
                put("signature", stat.signature)
                put("label", stat.label)
                put("count", stat.count)
                put("pinned", stat.pinned)
            })
        }
        prefs.edit { putString("audio_track_stats", array.toString()) }
    }

    /** Учитывает выбор дорожки пользователем. */
    fun recordAudioTrackChoice(signature: String, label: String) {
        val stats = getAudioTrackStats().toMutableList()
        val index = stats.indexOfFirst { it.signature == signature }
        val updated = if (index != -1) {
            stats.removeAt(index).let { it.copy(label = label, count = it.count + 1) }
        } else {
            AudioTrackStat(signature, label, 1)
        }
        // Незакрепленные сортируются по счетчику (стабильно): при равном счетчике выше последняя выбранная,
        // иначе новая дорожка не попала бы в заполненный список
        stats.add(if (updated.pinned) index else 0, updated)
        saveAudioTrackStats(stats)
    }

    /**
     * Перемещает дорожку в списке приоритетов. Дорожки до новой позиции включительно закрепляются,
     * чтобы порядок, заданный пользователем, не менялся от счетчиков.
     */
    fun moveAudioTrackStat(from: Int, to: Int) {
        val stats = getAudioTrackStats().toMutableList()
        if (from !in stats.indices) return
        val target = to.coerceIn(0, stats.lastIndex)
        stats.add(target, stats.removeAt(from))
        val pinUntil = maxOf(from, target)
        saveAudioTrackStats(stats.mapIndexed { i, stat -> if (i <= pinUntil) stat.copy(pinned = true) else stat })
    }

    fun removeAudioTrackStat(index: Int) {
        val stats = getAudioTrackStats().toMutableList()
        if (index !in stats.indices) return
        stats.removeAt(index)
        saveAudioTrackStats(stats)
    }

    fun getResumeMode(): Int = prefs.getInt("resume_mode", RESUME_ASK)
    fun setResumeMode(mode: Int) = prefs.edit { putInt("resume_mode", mode) }

    // Позиция из внешнего приложения: применять сразу (по умолчанию) или спрашивать
    fun getExternalPositionMode(): Int = prefs.getInt("external_position_mode", EXTERNAL_POSITION_APPLY)
    fun setExternalPositionMode(mode: Int) = prefs.edit { putInt("external_position_mode", mode) }

    // Действие кнопки "Вверх" (UP_ACTION_*). По умолчанию - боковое меню
    // По умолчанию "Инфопанель, затем боковое меню"; кто выбирал действие сам, сохраняет свой выбор
    fun getUpButtonAction(): Int = prefs.getInt("up_button_action", UP_ACTION_INFO)
    fun setUpButtonAction(action: Int) = prefs.edit { putInt("up_button_action", action) }

    // Подтверждение выхода из плеера повторным нажатием "Назад"
    fun isConfirmExitEnabled(): Boolean = prefs.getBoolean("confirm_exit", true)
    fun setConfirmExitEnabled(enabled: Boolean) = prefs.edit { putBoolean("confirm_exit", enabled) }

    // Действие кнопки "OK" (OK_ACTION_*). По умолчанию - пауза + панель (текущее поведение)
    fun getOkButtonAction(): Int = prefs.getInt("ok_button_action", OK_ACTION_PAUSE_PANEL)
    fun setOkButtonAction(action: Int) = prefs.edit { putInt("ok_button_action", action) }
    // Горизонтальный свайп ( 0 = None, 1 = Seek, 2 = Playlist)
    // Линзы анаглиф-очков переставлены (например, голубая слева): матрицы глаз меняются местами
    fun isAnaglyphLensSwapped(): Boolean = prefs.getBoolean("anaglyph_lens_swap", false)
    fun setAnaglyphLensSwapped(swapped: Boolean) = prefs.edit { putBoolean("anaglyph_lens_swap", swapped) }

    // Калибровка анаглиф-очков из визарда (null - встроенная матрица Dubois)
    fun getAnaglyphCalibration(group: GlassesGroup): AnaglyphCalibration? {
        val key = "calib_${group.name.lowercase()}_"
        if (!prefs.getBoolean("${key}set", false)) return null
        val def = AnaglyphCalibration()
        return AnaglyphCalibration(
            leakA = prefs.getFloat("${key}leak_a", def.leakA),
            leakB = prefs.getFloat("${key}leak_b", def.leakB)
        )
    }

    fun setAnaglyphCalibration(group: GlassesGroup, calibration: AnaglyphCalibration) {
        val key = "calib_${group.name.lowercase()}_"
        prefs.edit {
            putBoolean("${key}set", true)
            putFloat("${key}leak_a", calibration.leakA)
            putFloat("${key}leak_b", calibration.leakB)
        }
    }

    /** Сброс калибровки анаглифа к значениям приложения: встроенные матрицы и обычный порядок линз. */
    fun resetAnaglyphCalibration() {
        prefs.edit {
            GlassesGroup.entries.forEach { group ->
                val key = "calib_${group.name.lowercase()}_"
                listOf("set", "hue_a", "hue_b", "leak_a", "leak_b").forEach { remove(key + it) }
            }
            remove("anaglyph_lens_swap")
            // Выбранный фильтр "Калибровка" заменяем встроенным фильтром группы
            val types = StereoRenderer.AnaglyphType.entries
            fun reset(key: String) {
                val type = types.getOrNull(prefs.getInt(key, -1)) ?: return
                if (AnaglyphLogic.isCalibratedType(type)) {
                    putInt(key, AnaglyphLogic.defaultFilter(AnaglyphLogic.getGlassesGroup(type)).ordinal)
                }
            }
            reset("def_anaglyph_type")
            GlassesGroup.entries.forEach { reset("pref_filter_${it.name}") }
        }
        bumpCalibrationVersion()
    }

    /** Сброс калибровки VR к значениям приложения. */
    fun resetVrCalibration() {
        prefs.edit {
            listOf("vr_k1", "vr_k2", "vr_scale", "global_screen_separation_pct").forEach { remove(it) }
        }
        bumpCalibrationVersion()
    }

    /**
     * Сброс всех настроек к значениям по умолчанию. Пользовательские данные ([USER_DATA_KEYS]: история,
     * плейлисты, SMB-серверы и т.п.) и настройки отдельных видео (Room) не трогаем.
     */
    fun resetAllSettings() {
        val keep = prefs.all.filterKeys { it in USER_DATA_KEYS }
        // Новая версия калибровки: плеер перечитает VR и анаглиф уже без сохраненных значений
        val calibrationVersion = getCalibrationVersion() + 1
        prefs.edit(commit = true) {
            clear()
            keep.forEach { (key, value) ->
                @Suppress("UNCHECKED_CAST")
                when (value) {
                    is String -> putString(key, value)
                    is Set<*> -> putStringSet(key, value as Set<String>)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is Boolean -> putBoolean(key, value)
                }
            }
            putInt("calibration_version", calibrationVersion)
        }
    }

    // Счетчик сохранений калибровки: плеер перечитывает параметры VR и анаглифа, если он изменился
    fun getCalibrationVersion(): Int = prefs.getInt("calibration_version", 0)
    fun bumpCalibrationVersion() = prefs.edit { putInt("calibration_version", getCalibrationVersion() + 1) }

    // Стиль субтитров (без сохраненных значений - стандартный вид SubtitleView)
    fun getSubtitleStyle(): SubtitleStyle {
        val def = SubtitleStyle()
        return SubtitleStyle(
            font = prefs.getInt("sub_font", def.font),
            sizePercent = prefs.getInt("sub_size", def.sizePercent),
            textColor = prefs.getInt("sub_text_color", def.textColor),
            backgroundColor = prefs.getInt("sub_bg_color", def.backgroundColor),
            edgeType = prefs.getInt("sub_edge_type", def.edgeType),
            edgeColor = prefs.getInt("sub_edge_color", def.edgeColor),
            bottomPercent = prefs.getInt("sub_bottom", def.bottomPercent),
            applyEmbedded = prefs.getBoolean("sub_embedded", def.applyEmbedded)
        )
    }

    fun setSubtitleStyle(style: SubtitleStyle) = prefs.edit {
        putInt("sub_font", style.font)
        putInt("sub_size", style.sizePercent)
        putInt("sub_text_color", style.textColor)
        putInt("sub_bg_color", style.backgroundColor)
        putInt("sub_edge_type", style.edgeType)
        putInt("sub_edge_color", style.edgeColor)
        putInt("sub_bottom", style.bottomPercent)
        putBoolean("sub_embedded", style.applyEmbedded)
    }

    // Жесты громкости (справа) и яркости (слева) вертикальным свайпом
    fun isVolumeBrightnessGesturesEnabled(): Boolean = prefs.getBoolean("gesture_volume_brightness", true)
    fun setVolumeBrightnessGesturesEnabled(enabled: Boolean) = prefs.edit { putBoolean("gesture_volume_brightness", enabled) }

    fun getHorizontalSwipeAction(): Int = prefs.getInt("horizontal_swipe_action", SWIPE_PLAYLIST)
    fun setHorizontalSwipeAction(action: Int) = prefs.edit { putInt("horizontal_swipe_action", action) }

    // Кнопка выхода (стрелка) в инфопанели для сенсорных экранов
    fun isBackButtonEnabled(): Boolean = prefs.getBoolean("show_back_button", false)
    fun setBackButtonEnabled(enabled: Boolean) = prefs.edit { putBoolean("show_back_button", enabled) }

    // Скрытые пользователем кнопки нижней панели. Храним скрытые, чтобы новые кнопки по умолчанию были видны
    fun getHiddenControlButtons(): Set<String> =
        prefs.getStringSet("hidden_control_buttons", null)?.toSet() ?: emptySet()
    fun setHiddenControlButtons(buttons: Set<String>) = prefs.edit { putStringSet("hidden_control_buttons", buttons) }

    // Прозрачность часов (CLOCK_OFF или 0..80 %).
    // Миграция со старого переключателя "show_clock": выкл -> часы выключены, вкл -> без прозрачности.
    fun getClockTransparency(): Int {
        if (!prefs.contains("clock_transparency")) {
            val migrated = if (prefs.getBoolean("show_clock", false)) 0 else CLOCK_OFF
            prefs.edit {
                putInt("clock_transparency", migrated)
                remove("show_clock")
            }
            return migrated
        }
        return prefs.getInt("clock_transparency", CLOCK_OFF)
    }
    fun setClockTransparency(transparency: Int) = prefs.edit { putInt("clock_transparency", transparency) }

    // Сигнатура настроек, требующих полного перезапуска плеера
    fun getHardSettingsSignature(): String {
        val videoParams = "${getVideoDecoderPriority()}_${getAudioDecoderPriority()}_${isTunnelingEnabled()}_${getDvMode()}_${isHdAudioIecEnabled()}_${isHdr10PlusEnabled()}"
        val audioDownmix = "${isStereoDownmixEnabled()}_${getMixPreset()}_${getMixFront()}_${getMixCenter()}_${getMixRear()}_${getMixMiddle()}_${getMixLfe()}"
        return "${videoParams}_${audioDownmix}_${getTargetBufferCorrectMB()}"
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

    // Автоопределение стереопары для видео без сохраненных настроек (false - "Не обрабатывать")
    fun isStereoAutoDetectEnabled(): Boolean = prefs.getBoolean("stereo_auto_detect", true)
    fun setStereoAutoDetectEnabled(enabled: Boolean) = prefs.edit { putBoolean("stereo_auto_detect", enabled) }

    // Вывод по умолчанию для нового стерео-видео (также обновляется при сохранении настроек видео в OSD)
    fun getDefaultOutputMode(): StereoOutputMode =
        StereoOutputMode.entries.getOrNull(prefs.getInt("def_output_mode", StereoOutputMode.ANAGLYPH.ordinal))
            ?: StereoOutputMode.ANAGLYPH
    fun setDefaultOutputMode(mode: StereoOutputMode) = prefs.edit { putInt("def_output_mode", mode.ordinal) }

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