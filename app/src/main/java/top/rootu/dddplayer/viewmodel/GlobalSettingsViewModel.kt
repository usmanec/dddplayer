package top.rootu.dddplayer.viewmodel

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.media3.exoplayer.DefaultRenderersFactory
import top.rootu.dddplayer.R
import top.rootu.dddplayer.data.SettingsRepository
import top.rootu.dddplayer.logic.AudioMixerLogic

/**
 * ViewModel для управления всеми глобальными настройками приложения.
 * Инкапсулирует логику чтения/записи из SettingsRepository и предоставляет LiveData для UI.
 */
class GlobalSettingsViewModel(application: Application) : AndroidViewModel(application) {
    val repository = SettingsRepository(application)

    // --- Playback Settings ---
    private val _decoderPriority = MutableLiveData(repository.getDecoderPriority())
    val decoderPriority: LiveData<Int> = _decoderPriority

    private val _isTunnelingEnabled = MutableLiveData(repository.isTunnelingEnabled())
    val isTunnelingEnabled: LiveData<Boolean> = _isTunnelingEnabled

    private val _isMapDv7ToHevcEnabled = MutableLiveData(repository.isMapDv7ToHevcEnabled())
    val isMapDv7ToHevcEnabled: LiveData<Boolean> = _isMapDv7ToHevcEnabled

    private val _isFrameRateMatchingEnabled = MutableLiveData(repository.isFrameRateMatchingEnabled())
    val isFrameRateMatchingEnabled: LiveData<Boolean> = _isFrameRateMatchingEnabled

    private val _isSkipSilenceEnabled = MutableLiveData(repository.isSkipSilenceEnabled())
    val isSkipSilenceEnabled: LiveData<Boolean> = _isSkipSilenceEnabled

    private val _loudnessBoost = MutableLiveData(repository.getLoudnessBoost())
    val loudnessBoost: LiveData<Int> = _loudnessBoost

    // --- Audio Mix Settings ---
    private val _isStereoDownmixEnabled = MutableLiveData(repository.isStereoDownmixEnabled())
    val isStereoDownmixEnabled: LiveData<Boolean> = _isStereoDownmixEnabled

    private val _mixPresetId = MutableLiveData(repository.getMixPreset())
    val mixPresetId: LiveData<Int> = _mixPresetId

    // Custom Mix Params (для диалога)
    private val _mixParams = MutableLiveData(AudioMixerLogic.getParamsForPreset(AudioMixerLogic.MixPreset.CUSTOM, repository))
    val mixParams: LiveData<AudioMixerLogic.MixParams> = _mixParams

    // --- Language Settings ---
    private val _preferredAudioLang = MutableLiveData(repository.getPreferredAudioLang())
    val preferredAudioLang: LiveData<String> = _preferredAudioLang

    private val _preferredSubLang = MutableLiveData(repository.getPreferredSubLang())
    val preferredSubLang: LiveData<String> = _preferredSubLang

    private val _appLanguage = MutableLiveData(repository.getAppLanguage())
    val appLanguage: LiveData<String> = _appLanguage

    // --- UI Settings ---
    private val _isRememberZoomEnabled = MutableLiveData(repository.isRememberZoomEnabled())
    val isRememberZoomEnabled: LiveData<Boolean> = _isRememberZoomEnabled

    private val _isShowPlaylistIndexEnabled = MutableLiveData(repository.isShowPlaylistIndexEnabled())
    val isShowPlaylistIndexEnabled: LiveData<Boolean> = _isShowPlaylistIndexEnabled
    private val _upButtonAction = MutableLiveData(repository.getUpButtonAction())
    val upButtonAction: LiveData<Int> = _upButtonAction
    private val _horizontalSwipeAction = MutableLiveData(repository.getHorizontalSwipeAction())
    val horizontalSwipeAction: LiveData<Int> = _horizontalSwipeAction

    // --- Actions ---

    fun toggleRememberZoom(enabled: Boolean) {
        repository.setRememberZoomEnabled(enabled)
        _isRememberZoomEnabled.value = enabled
    }

    fun toggleShowPlaylistIndex(enabled: Boolean) {
        repository.setShowPlaylistIndexEnabled(enabled)
        _isShowPlaylistIndexEnabled.value = enabled
    }
    fun toggleDecoderPriority() {
        val current = _decoderPriority.value ?: DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
        val next = when (current) {
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
            else -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
        }
        repository.setDecoderPriority(next)
        _decoderPriority.value = next
    }

    fun toggleTunneling(enabled: Boolean) {
        repository.setTunnelingEnabled(enabled)
        _isTunnelingEnabled.value = enabled
    }

    fun toggleMapDv7ToHevc(enabled: Boolean) {
        repository.setMapDv7ToHevcEnabled(enabled)
        _isMapDv7ToHevcEnabled.value = enabled
    }

    fun toggleFrameRateMatching(enabled: Boolean) {
        repository.setFrameRateMatchingEnabled(enabled)
        _isFrameRateMatchingEnabled.value = enabled
    }

    fun toggleSkipSilence(enabled: Boolean) {
        repository.setSkipSilenceEnabled(enabled)
        _isSkipSilenceEnabled.value = enabled
    }

    fun cycleLoudnessBoost() {
        val current = _loudnessBoost.value ?: 0
        val next = if (current >= 1000) 0 else current + 200
        repository.setLoudnessBoost(next)
        _loudnessBoost.value = next
    }

    fun setPreferredAudioLang(langCode: String) {
        repository.setPreferredAudioLang(langCode)
        _preferredAudioLang.value = langCode
    }

    fun setPreferredSubLang(langCode: String) {
        repository.setPreferredSubLang(langCode)
        _preferredSubLang.value = langCode
    }

    fun setAppLanguage(langCode: String) {
        repository.setAppLanguage(langCode)
        _appLanguage.value = langCode
    }

    fun cycleUpButtonAction() {
        val current = _upButtonAction.value ?: 1
        val next = (current + 1) % 3 // 0, 1, 2
        repository.setUpButtonAction(next)
        _upButtonAction.value = next
    }

    fun cycleHorizontalSwipeAction() {
        val current = _horizontalSwipeAction.value ?: 0
        val next = (current + 1) % 3 // 0, 1, 2
        repository.setHorizontalSwipeAction(next)
        _horizontalSwipeAction.value = next
    }

    // --- Downmix Logic ---

    fun toggleStereoDownmix(enabled: Boolean) {
        repository.setStereoDownmixEnabled(enabled)
        _isStereoDownmixEnabled.value = enabled
    }

    fun setMixPreset(presetId: Int) {
        repository.setMixPreset(presetId)
        _mixPresetId.value = presetId
        // Если переключились на пресет, обновляем кастомные параметры, чтобы они отражали текущие значения
        if (presetId != AudioMixerLogic.MixPreset.CUSTOM.id) {
            val params = AudioMixerLogic.getParamsForPreset(AudioMixerLogic.MixPreset.entries.find { it.id == presetId } ?: AudioMixerLogic.MixPreset.STANDARD, repository)
            _mixParams.value = params
        }
    }

    /**
     * Обновляет кастомный параметр микширования.
     * Вызывается только если выбран CUSTOM пресет.
     */
    fun updateCustomMixParam(type: String, value: Float) {
        if (_mixPresetId.value != AudioMixerLogic.MixPreset.CUSTOM.id) return

        when (type) {
            "front" -> repository.setMixFront(value)
            "center" -> repository.setMixCenter(value)
            "rear" -> repository.setMixRear(value)
            "middle" -> repository.setMixMiddle(value)
            "lfe" -> repository.setMixLfe(value)
        }
        // LiveData не обновляем тут, чтобы разорвать циклическую связь
        // это решении баги по застреванию ползунков на значениях  кратных 52 из-за приведения типов
    }

    fun resetCustomMixParams() {
        repository.setMixFront(1.0f)
        repository.setMixCenter(1.0f)
        repository.setMixRear(1.0f)
        repository.setMixMiddle(1.0f)
        repository.setMixLfe(0.0f)
        _mixParams.value = AudioMixerLogic.getParamsForPreset(AudioMixerLogic.MixPreset.CUSTOM, repository)
    }

    // --- UI Helpers (Возвращают ID ресурсов или сырые данные) ---

    fun getDecoderValueString(mode: Int): String {
        return when (mode) {
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF -> "HW"
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON -> "HW+"
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER -> "SW"
            else -> "Unknown"
        }
    }

    @StringRes
    fun getDecoderDescResId(mode: Int): Int {
        return when (mode) {
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF -> R.string.pref_decoder_priority_only_device
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON -> R.string.pref_decoder_priority_prefer_device
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER -> R.string.pref_decoder_priority_prefer_app
            else -> R.string.pref_decoder_priority_prefer_device
        }
    }

    @StringRes
    fun getAfrDescResId(enabled: Boolean): Int =
        if (enabled) R.string.pref_framerate_matching_on else R.string.pref_framerate_matching_off

    @StringRes
    fun getSkipSilenceDescResId(enabled: Boolean): Int =
        if (enabled) R.string.pref_skip_silence_on else R.string.pref_skip_silence_off

}