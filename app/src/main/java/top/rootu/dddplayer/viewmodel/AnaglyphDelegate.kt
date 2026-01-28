package top.rootu.dddplayer.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import top.rootu.dddplayer.data.SettingsRepository
import top.rootu.dddplayer.logic.AnaglyphLogic
import top.rootu.dddplayer.renderer.StereoRenderer

/**
 * Делегат для управления состоянием рендеринга (Анаглиф, VR, Матрицы).
 * Используется внутри PlayerViewModel.
 */
class AnaglyphDelegate(private val repository: SettingsRepository) {

    // VR Settings
    private val _vrK1 = MutableLiveData(0.34f)
    val vrK1: LiveData<Float> = _vrK1
    private val _vrK2 = MutableLiveData(0.10f)
    val vrK2: LiveData<Float> = _vrK2
    private val _vrScale = MutableLiveData(1.2f)
    val vrScale: LiveData<Float> = _vrScale

    // Custom Anaglyph Params
    private val _customHueOffsetL = MutableLiveData(0)
    val customHueOffsetL: LiveData<Int> = _customHueOffsetL
    private val _customHueOffsetR = MutableLiveData(0)
    val customHueOffsetR: LiveData<Int> = _customHueOffsetR
    private val _customLeakL = MutableLiveData(0.20f)
    val customLeakL: LiveData<Float> = _customLeakL
    private val _customLeakR = MutableLiveData(0.20f)
    val customLeakR: LiveData<Float> = _customLeakR
    private val _customSpaceLms = MutableLiveData(false)
    val customSpaceLms: LiveData<Boolean> = _customSpaceLms

    // Calculated Values
    private val _calculatedColorL = MutableLiveData(0)
    val calculatedColorL: LiveData<Int> = _calculatedColorL
    private val _calculatedColorR = MutableLiveData(0)
    val calculatedColorR: LiveData<Int> = _calculatedColorR
    private val _currentMatrices = MutableLiveData<Pair<FloatArray, FloatArray>>()
    val currentMatrices: LiveData<Pair<FloatArray, FloatArray>> = _currentMatrices
    private val _isMatrixValid = MutableLiveData(true)
    val isMatrixValid: LiveData<Boolean> = _isMatrixValid

    fun loadGlobalVrParams() {
        _vrK1.postValue(repository.getGlobalFloat("vr_k1", 0.34f))
        _vrK2.postValue(repository.getGlobalFloat("vr_k2", 0.10f))
        _vrScale.postValue(repository.getGlobalFloat("vr_scale", 1.2f))
    }

    fun saveGlobalVrParams() {
        repository.putGlobalFloat("vr_k1", _vrK1.value!!)
        repository.putGlobalFloat("vr_k2", _vrK2.value!!)
        repository.putGlobalFloat("vr_scale", _vrScale.value!!)
    }

    fun loadCustomSettings(anaglyphType: StereoRenderer.AnaglyphType) {
        val prefix = AnaglyphLogic.getCustomPrefix(anaglyphType)
        _customHueOffsetL.value = repository.getGlobalInt("${prefix}hue_l", 0)
        _customHueOffsetR.value = repository.getGlobalInt("${prefix}hue_r", 0)
        _customLeakL.value = repository.getGlobalFloat("${prefix}leak_l", 0.20f)
        _customLeakR.value = repository.getGlobalFloat("${prefix}leak_r", 0.20f)
        _customSpaceLms.value = repository.getGlobalBoolean("${prefix}space_lms", false)
        updateCalculatedColors(anaglyphType)
    }

    fun saveCustomSettings(anaglyphType: StereoRenderer.AnaglyphType) {
        val prefix = AnaglyphLogic.getCustomPrefix(anaglyphType)
        repository.putGlobalInt("${prefix}hue_l", _customHueOffsetL.value!!)
        repository.putGlobalInt("${prefix}hue_r", _customHueOffsetR.value!!)
        repository.putGlobalFloat("${prefix}leak_l", _customLeakL.value!!)
        repository.putGlobalFloat("${prefix}leak_r", _customLeakR.value!!)
        repository.putGlobalBoolean("${prefix}space_lms", _customSpaceLms.value!!)

        updateCalculatedColors(anaglyphType)
        updateAnaglyphMatrix(anaglyphType)
    }

    fun updateCalculatedColors(anaglyphType: StereoRenderer.AnaglyphType) {
        val (baseL, baseR) = AnaglyphLogic.getBaseColors(anaglyphType)
        _calculatedColorL.value = AnaglyphLogic.applyHueOffset(baseL, _customHueOffsetL.value ?: 0)
        _calculatedColorR.value = AnaglyphLogic.applyHueOffset(baseR, _customHueOffsetR.value ?: 0)
    }

    fun updateAnaglyphMatrix(anaglyphType: StereoRenderer.AnaglyphType) {
        val matrices = AnaglyphLogic.calculateMatrix(
            anaglyphType,
            _customHueOffsetL.value ?: 0, _customHueOffsetR.value ?: 0,
            _customLeakL.value ?: 0.2f, _customLeakR.value ?: 0.2f,
            _customSpaceLms.value ?: false
        )
        _currentMatrices.value = Pair(matrices.left, matrices.right)
        _isMatrixValid.value = matrices.isValid
    }

    // Setters for modification from ViewModel
    fun setVrK1(value: Float) { _vrK1.value = value }
    fun setVrK2(value: Float) { _vrK2.value = value }
    fun setVrScale(value: Float) { _vrScale.value = value }
    fun setCustomHueL(value: Int) { _customHueOffsetL.value = value }
    fun setCustomHueR(value: Int) { _customHueOffsetR.value = value }
    fun setCustomLeakL(value: Float) { _customLeakL.value = value }
    fun setCustomLeakR(value: Float) { _customLeakR.value = value }
    fun setCustomSpaceLms(value: Boolean) { _customSpaceLms.value = value }
}