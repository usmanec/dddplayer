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
import top.rootu.dddplayer.model.AudioTrackStat
import top.rootu.dddplayer.model.StereoOutputMode

/**
 * ViewModel для управления всеми глобальными настройками приложения.
 * Инкапсулирует логику чтения/записи из SettingsRepository и предоставляет LiveData для UI.
 */
class GlobalSettingsViewModel(application: Application) : AndroidViewModel(application) {
    val repository = SettingsRepository.getInstance(application)

    // --- Playback Settings ---
    private val _isAutoPipEnabled = MutableLiveData(repository.isAutoPipEnabled())
    val isAutoPipEnabled: LiveData<Boolean> = _isAutoPipEnabled
    private val _isAutoSkipIntroEnabled = MutableLiveData(repository.isAutoSkipIntroEnabled())
    val isAutoSkipIntroEnabled: LiveData<Boolean> = _isAutoSkipIntroEnabled

    private val _videoDecoderPriority = MutableLiveData(repository.getVideoDecoderPriority())
    val videoDecoderPriority: LiveData<Int> = _videoDecoderPriority
    private val _audioDecoderPriority = MutableLiveData(repository.getAudioDecoderPriority())
    val audioDecoderPriority: LiveData<Int> = _audioDecoderPriority

    private val _isTunnelingEnabled = MutableLiveData(repository.isTunnelingEnabled())
    val isTunnelingEnabled: LiveData<Boolean> = _isTunnelingEnabled

    private val _isHdr10PlusEnabled = MutableLiveData(repository.isHdr10PlusEnabled())
    val isHdr10PlusEnabled: LiveData<Boolean> = _isHdr10PlusEnabled
    private val _isHdAudioIecEnabled = MutableLiveData(repository.isHdAudioIecEnabled())
    val isHdAudioIecEnabled: LiveData<Boolean> = _isHdAudioIecEnabled

    private val _dvMode = MutableLiveData(repository.getDvMode())
    val dvMode: LiveData<Int> = _dvMode

    private val _isFrameRateMatchingEnabled = MutableLiveData(repository.isFrameRateMatchingEnabled())
    val isFrameRateMatchingEnabled: LiveData<Boolean> = _isFrameRateMatchingEnabled

    private val _isAfrResolutionSwitchEnabled = MutableLiveData(repository.isAfrResolutionSwitchEnabled())
    val isAfrResolutionSwitchEnabled: LiveData<Boolean> = _isAfrResolutionSwitchEnabled

    private val _isAfrFpsCorrectionEnabled = MutableLiveData(repository.isAfrFpsCorrectionEnabled())
    val isAfrFpsCorrectionEnabled: LiveData<Boolean> = _isAfrFpsCorrectionEnabled

    private val _isAfrDoubleRefreshRateEnabled = MutableLiveData(repository.isAfrDoubleRefreshRateEnabled())
    val isAfrDoubleRefreshRateEnabled: LiveData<Boolean> = _isAfrDoubleRefreshRateEnabled

    private val _isAfrSkip24RateEnabled = MutableLiveData(repository.isAfrSkip24RateEnabled())
    val isAfrSkip24RateEnabled: LiveData<Boolean> = _isAfrSkip24RateEnabled

    private val _afrPauseMs = MutableLiveData(repository.getAfrPauseMs())
    val afrPauseMs: LiveData<Int> = _afrPauseMs

    private val _isAfrSkipShortsEnabled = MutableLiveData(repository.isAfrSkipShortsEnabled())
    val isAfrSkipShortsEnabled: LiveData<Boolean> = _isAfrSkipShortsEnabled

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
    private val _pauseDimLevel = MutableLiveData(repository.getPauseDimLevel())
    val pauseDimLevel: LiveData<Int> = _pauseDimLevel

    private val _isRememberZoomEnabled = MutableLiveData(repository.isRememberZoomEnabled())
    val isRememberZoomEnabled: LiveData<Boolean> = _isRememberZoomEnabled

    private val _isShowPlaylistIndexEnabled = MutableLiveData(repository.isShowPlaylistIndexEnabled())
    val isShowPlaylistIndexEnabled: LiveData<Boolean> = _isShowPlaylistIndexEnabled

    private val _isConfirmExitEnabled = MutableLiveData(repository.isConfirmExitEnabled())
    val isConfirmExitEnabled: LiveData<Boolean> = _isConfirmExitEnabled

    private val _clockTransparency = MutableLiveData(repository.getClockTransparency())
    val clockTransparency: LiveData<Int> = _clockTransparency

    private val _resumeModeAction = MutableLiveData(repository.getResumeMode())
    val resumeModeAction: LiveData<Int> = _resumeModeAction

    private val _externalPositionMode = MutableLiveData(repository.getExternalPositionMode())
    val externalPositionMode: LiveData<Int> = _externalPositionMode

    private val _upButtonAction = MutableLiveData(repository.getUpButtonAction())
    val upButtonAction: LiveData<Int> = _upButtonAction

    private val _okButtonAction = MutableLiveData(repository.getOkButtonAction())
    val okButtonAction: LiveData<Int> = _okButtonAction

    private val _defaultPlaybackSpeed = MutableLiveData(repository.getDefaultPlaybackSpeed())
    val defaultPlaybackSpeed: LiveData<Float> = _defaultPlaybackSpeed

    private val _horizontalSwipeAction = MutableLiveData(repository.getHorizontalSwipeAction())
    val horizontalSwipeAction: LiveData<Int> = _horizontalSwipeAction

    private val _isBackButtonEnabled = MutableLiveData(repository.isBackButtonEnabled())
    val isBackButtonEnabled: LiveData<Boolean> = _isBackButtonEnabled

    private val _hiddenControlButtons = MutableLiveData(repository.getHiddenControlButtons())
    val hiddenControlButtons: LiveData<Set<String>> = _hiddenControlButtons

    // --- Playlist Settings ---
    private val _isLiveModeEnabled = MutableLiveData(repository.isLiveModeEnabled())
    val isLiveModeEnabled: LiveData<Boolean> = _isLiveModeEnabled

    private val _isShowInfoOnTrackChange = MutableLiveData(repository.isShowInfoOnTrackChange())
    val isShowInfoOnTrackChange: LiveData<Boolean> = _isShowInfoOnTrackChange

    fun toggleLiveMode(enabled: Boolean) {
        repository.setLiveModeEnabled(enabled)
        _isLiveModeEnabled.value = enabled
    }

    // 3D режим по умолчанию: null - "Не обрабатывать" (без автоопределения стереопары), иначе вывод
    private val _default3dMode = MutableLiveData(
        if (repository.isStereoAutoDetectEnabled()) repository.getDefaultOutputMode() else null
    )
    val default3dMode: LiveData<StereoOutputMode?> = _default3dMode

    fun setDefault3dMode(mode: StereoOutputMode?) {
        repository.setStereoAutoDetectEnabled(mode != null)
        if (mode != null) repository.setDefaultOutputMode(mode)
        _default3dMode.value = mode
    }

    private val _subtitleAutoMode = MutableLiveData(repository.getSubtitleAutoMode())
    val subtitleAutoMode: LiveData<Int> = _subtitleAutoMode

    fun setSubtitleAutoMode(mode: Int) {
        repository.setSubtitleAutoMode(mode)
        _subtitleAutoMode.value = mode
    }

    private val _audioTrackMemoryMode = MutableLiveData(repository.getAudioTrackMemoryMode())
    val audioTrackMemoryMode: LiveData<Int> = _audioTrackMemoryMode

    fun setAudioTrackMemoryMode(mode: Int) {
        repository.setAudioTrackMemoryMode(mode)
        _audioTrackMemoryMode.value = mode
    }

    // Список приоритетов аудиодорожек (наиболее используемые)
    private val _audioTrackStats = MutableLiveData(repository.getAudioTrackStats())
    val audioTrackStats: LiveData<List<AudioTrackStat>> = _audioTrackStats

    fun moveAudioTrackStat(from: Int, to: Int) {
        repository.moveAudioTrackStat(from, to)
        _audioTrackStats.value = repository.getAudioTrackStats()
    }

    fun removeAudioTrackStat(index: Int) {
        repository.removeAudioTrackStat(index)
        _audioTrackStats.value = repository.getAudioTrackStats()
    }

    fun toggleShowInfoOnTrackChange(enabled: Boolean) {
        repository.setShowInfoOnTrackChange(enabled)
        _isShowInfoOnTrackChange.value = enabled
    }

    // --- Buffer Settings ---
    private val _bufferDisplayMode = MutableLiveData(repository.getBufferDisplayMode())
    val bufferDisplayMode: LiveData<Int> = _bufferDisplayMode
    private val _targetBufferMB = MutableLiveData(repository.getTargetBufferCorrectMB())
    val targetBufferMB: LiveData<Int> = _targetBufferMB
    val targetBufferOptions = repository.getTargetBufferOptions()

    // --- Actions ---
    fun toggleAutoPip(enabled: Boolean) {
        repository.setAutoPipEnabled(enabled)
        _isAutoPipEnabled.value = enabled
    }

    fun toggleAutoSkipIntro(enabled: Boolean) {
        repository.setAutoSkipIntroEnabled(enabled)
        _isAutoSkipIntroEnabled.value = enabled
    }

    fun setBufferDisplayMode(mode: Int) {
        repository.setBufferDisplayMode(mode)
        _bufferDisplayMode.value = mode
    }

    fun setTargetBufferMB(sizeInMB: Int) {
        repository.setTargetBufferMB(sizeInMB)
        _targetBufferMB.value = sizeInMB
    }

    fun toggleRememberZoom(enabled: Boolean) {
        repository.setRememberZoomEnabled(enabled)
        _isRememberZoomEnabled.value = enabled
    }

    fun toggleConfirmExit(enabled: Boolean) {
        repository.setConfirmExitEnabled(enabled)
        _isConfirmExitEnabled.value = enabled
    }

    fun toggleShowPlaylistIndex(enabled: Boolean) {
        repository.setShowPlaylistIndexEnabled(enabled)
        _isShowPlaylistIndexEnabled.value = enabled
    }

    fun cycleClockTransparency() {
        val current = _clockTransparency.value ?: SettingsRepository.CLOCK_OFF

        // цикл: Выкл (-1) -> 0% -> 20% -> 40% -> 60% -> 80% -> Выкл (-1)
        val next = when {
            current == SettingsRepository.CLOCK_OFF -> 0 // С Выкл. переходим на 0% прозрачности (полностью видимо)
            current >= 80 -> SettingsRepository.CLOCK_OFF // С 80% прозрачности переходим на Выкл.
            else -> current + 20    // Увеличиваем прозрачность
        }

        repository.setClockTransparency(next)
        _clockTransparency.value = next
    }

    // Перебор режимов по кругу: HW+ -> SW -> HW
    private fun nextDecoderPriority(current: Int?): Int = when (current ?: DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER) {
        DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
        DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
        else -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
    }

    fun toggleVideoDecoderPriority() {
        val next = nextDecoderPriority(_videoDecoderPriority.value)
        repository.setVideoDecoderPriority(next)
        _videoDecoderPriority.value = next
    }

    fun toggleAudioDecoderPriority() {
        val next = nextDecoderPriority(_audioDecoderPriority.value)
        repository.setAudioDecoderPriority(next)
        _audioDecoderPriority.value = next
    }

    fun toggleHdr10Plus(enabled: Boolean) {
        repository.setHdr10PlusEnabled(enabled)
        _isHdr10PlusEnabled.value = enabled
    }

    fun toggleTunneling(enabled: Boolean) {
        repository.setTunnelingEnabled(enabled)
        _isTunnelingEnabled.value = enabled
    }

    fun toggleHdAudioIec(enabled: Boolean) {
        repository.setHdAudioIecEnabled(enabled)
        _isHdAudioIecEnabled.value = enabled
    }

    fun setDvMode(mode: Int) {
        repository.setDvMode(mode)
        _dvMode.value = mode
    }

    fun toggleFrameRateMatching(enabled: Boolean) {
        repository.setFrameRateMatchingEnabled(enabled)
        _isFrameRateMatchingEnabled.value = enabled
    }

    fun toggleAfrResolutionSwitch(enabled: Boolean) {
        repository.setAfrResolutionSwitchEnabled(enabled)
        _isAfrResolutionSwitchEnabled.value = enabled
    }

    fun toggleAfrFpsCorrection(enabled: Boolean) {
        repository.setAfrFpsCorrectionEnabled(enabled)
        _isAfrFpsCorrectionEnabled.value = enabled
    }

    fun toggleAfrDoubleRefreshRate(enabled: Boolean) {
        repository.setAfrDoubleRefreshRateEnabled(enabled)
        _isAfrDoubleRefreshRateEnabled.value = enabled
    }

    fun toggleAfrSkip24Rate(enabled: Boolean) {
        repository.setAfrSkip24RateEnabled(enabled)
        _isAfrSkip24RateEnabled.value = enabled
    }

    fun cycleAfrPause() {
        val current = _afrPauseMs.value ?: 2000
        // Цикл: 0 -> 1000 -> 2000 -> 3000 -> 4000 -> 5000 -> 0
        val next = if (current >= 5000) 0 else current + 1000
        repository.setAfrPauseMs(next)
        _afrPauseMs.value = next
    }

    fun toggleAfrSkipShorts(enabled: Boolean) {
        repository.setAfrSkipShortsEnabled(enabled)
        _isAfrSkipShortsEnabled.value = enabled
    }

    fun toggleSkipSilence(enabled: Boolean) {
        repository.setSkipSilenceEnabled(enabled)
        _isSkipSilenceEnabled.value = enabled
    }

    fun cyclePauseDimLevel() {
        val current = _pauseDimLevel.value ?: 60
        // Цикл: 0 (Выкл) -> 10 -> 20 -> ... -> 80 -> 90 -> 100 -> 0
        val next = if (current >= 100) 0 else current + 10
        repository.setPauseDimLevel(next)
        _pauseDimLevel.value = next
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

    fun setExternalPositionMode(mode: Int) {
        repository.setExternalPositionMode(mode)
        _externalPositionMode.value = mode
    }

    fun setResumeModeAction(action: Int) {
        repository.setResumeMode(action)
        _resumeModeAction.value = action
    }

    fun setUpButtonAction(action: Int) {
        repository.setUpButtonAction(action)
        _upButtonAction.value = action
    }

    fun setOkButtonAction(action: Int) {
        repository.setOkButtonAction(action)
        _okButtonAction.value = action
    }

    private val _isVolumeBrightnessGestures = MutableLiveData(repository.isVolumeBrightnessGesturesEnabled())
    val isVolumeBrightnessGestures: LiveData<Boolean> = _isVolumeBrightnessGestures

    fun toggleVolumeBrightnessGestures(enabled: Boolean) {
        repository.setVolumeBrightnessGesturesEnabled(enabled)
        _isVolumeBrightnessGestures.value = enabled
    }

    fun setDefaultPlaybackSpeed(speed: Float) {
        repository.setDefaultPlaybackSpeed(speed)
        _defaultPlaybackSpeed.value = speed
    }

    fun getCustomPlaybackSpeed(): Float = repository.getCustomPlaybackSpeed()

    fun setHorizontalSwipeAction(action: Int) {
        repository.setHorizontalSwipeAction(action)
        _horizontalSwipeAction.value = action
    }

    fun toggleBackButton(enabled: Boolean) {
        repository.setBackButtonEnabled(enabled)
        _isBackButtonEnabled.value = enabled
    }

    fun setHiddenControlButtons(buttons: Set<String>) {
        repository.setHiddenControlButtons(buttons)
        _hiddenControlButtons.value = buttons
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

    fun refreshCustomMixParams() {
        _mixParams.value = AudioMixerLogic.getParamsForPreset(AudioMixerLogic.MixPreset.CUSTOM, repository)
    }

    // --- UI Helpers ---

    fun getDecoderValueString(mode: Int): String {
        return when (mode) {
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF -> "HW"
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON -> "HW+"
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER -> "SW"
            else -> "Unknown"
        }
    }

    @StringRes
    fun getDecoderDescResId(mode: Int, video: Boolean): Int {
        return when (mode) {
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF -> R.string.decoder_only_device
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER ->
                if (video) R.string.decoder_video_prefer_app else R.string.decoder_audio_prefer_app
            else -> if (video) R.string.decoder_video_prefer_device else R.string.decoder_audio_prefer_device
        }
    }

    @StringRes
    fun getAfrDescResId(enabled: Boolean): Int =
        if (enabled) R.string.pref_framerate_matching_on else R.string.pref_framerate_matching_off

    @StringRes
    fun getSkipSilenceDescResId(enabled: Boolean): Int =
        if (enabled) R.string.pref_skip_silence_on else R.string.pref_skip_silence_off

}