package top.rootu.dddplayer.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import top.rootu.dddplayer.logic.SettingsMutator

class SettingsViewModel : ViewModel() {

    private val _isSettingsPanelVisible = MutableLiveData(false)
    val isSettingsPanelVisible: LiveData<Boolean> = _isSettingsPanelVisible

    private val _currentSettingType = MutableLiveData(SettingType.VIDEO_TYPE)
    val currentSettingType: LiveData<SettingType> = _currentSettingType

    fun openPanel(availableSettings: List<SettingType>) {
        // Если текущий пункт недоступен в новом списке, сбрасываем на первый
        if (_currentSettingType.value !in availableSettings) {
            _currentSettingType.value = availableSettings.firstOrNull() ?: SettingType.VIDEO_TYPE
        }
        _isSettingsPanelVisible.value = true
    }

    fun closePanel() {
        _isSettingsPanelVisible.value = false
    }

    fun onMenuUp(availableSettings: List<SettingType>) {
        _currentSettingType.value = SettingsMutator.cycleList(_currentSettingType.value!!, availableSettings, -1)
    }

    fun onMenuDown(availableSettings: List<SettingType>) {
        _currentSettingType.value = SettingsMutator.cycleList(_currentSettingType.value!!, availableSettings, 1)
    }
}