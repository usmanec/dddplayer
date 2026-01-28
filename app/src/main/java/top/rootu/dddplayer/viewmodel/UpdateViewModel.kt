package top.rootu.dddplayer.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import top.rootu.dddplayer.BuildConfig
import top.rootu.dddplayer.R
import top.rootu.dddplayer.data.SettingsRepository
import top.rootu.dddplayer.logic.UpdateInfo
import top.rootu.dddplayer.logic.UpdateManager
import top.rootu.dddplayer.utils.getString

class UpdateViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SettingsRepository(application)
    private val updateManager = UpdateManager(application)

    private val _updateInfo = MutableLiveData<UpdateInfo?>()
    val updateInfo: LiveData<UpdateInfo?> = _updateInfo

    private val _downloadProgress = MutableLiveData<Int>()
    val downloadProgress: LiveData<Int> = _downloadProgress

    private val _isCheckingUpdates = MutableLiveData(false)
    val isCheckingUpdates: LiveData<Boolean> = _isCheckingUpdates

    private val _toastMessage = MutableLiveData<String?>()
    val toastMessage: LiveData<String?> = _toastMessage

    init {
        checkUpdatesAuto()
    }

    private fun checkUpdatesAuto() {
        viewModelScope.launch {
            val currentVersion = BuildConfig.VERSION_NAME
            var hasUpdate = false

            // 1. Проверяем сохраненный кэш
            val savedJson = repository.getLastUpdateInfo()
            if (savedJson != null) {
                val savedInfo = updateManager.fromJson(savedJson)
                if (savedInfo != null && updateManager.isNewer(savedInfo.version, currentVersion)) {
                    _updateInfo.postValue(savedInfo)
                    hasUpdate = true
                } else {
                    updateManager.deleteUpdateFile()
                    repository.saveUpdateInfo(null)
                }
            }

            if (!hasUpdate) {
                _updateInfo.postValue(null)
            }

            // 2. Проверяем сеть, если прошло достаточно времени (3 часа)
            val lastCheck = repository.getLastUpdateTime()
            if (System.currentTimeMillis() - lastCheck < 10800000) {
                return@launch
            }

            try {
                val info = updateManager.checkForUpdates(currentVersion)
                if (info != null) {
                    _updateInfo.postValue(info)
                    repository.saveUpdateInfo(updateManager.toJson(info))
                } else {
                    repository.saveUpdateInfo(null)
                    _updateInfo.postValue(null)
                }
            } catch (_: Exception) {
                // Тихая ошибка при автопроверке
            }
        }
    }

    fun forceCheckUpdates() {
        if (_isCheckingUpdates.value == true) return
        _isCheckingUpdates.value = true

        viewModelScope.launch {
            try {
                val currentVersion = BuildConfig.VERSION_NAME
                val info = updateManager.checkForUpdates(currentVersion)

                if (info != null) {
                    _updateInfo.postValue(info)
                    repository.saveUpdateInfo(updateManager.toJson(info))
                    _toastMessage.postValue(getString(R.string.update_found, info.version))
                } else {
                    repository.saveUpdateInfo(null)
                    _updateInfo.postValue(null)
                    _toastMessage.postValue(getString(R.string.update_latest))
                }
                repository.setLastUpdateTime(System.currentTimeMillis())

            } catch (_: Exception) {
                _toastMessage.postValue(getString(R.string.update_error))
            } finally {
                _isCheckingUpdates.postValue(false)
            }
        }
    }

    fun startUpdate() {
        val info = _updateInfo.value ?: return
        viewModelScope.launch {
            val file = updateManager.downloadApk(info.downloadUrl) { progress ->
                _downloadProgress.postValue(progress)
            }
            if (file != null) {
                updateManager.installApk(file)
            }
        }
    }

    fun clearToast() {
        _toastMessage.value = null
    }
}