package com.example.asparagusclassifier

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.example.asparagusclassifier.algorithm.AlgorithmResult
import com.example.asparagusclassifier.algorithm.CalibrationData

/**
 * MainActivity 的状态管理器
 */
class MainViewModel : ViewModel() {

    // 视图模式 (1: Raw, 2: Corrected, 3: Analysis)
    private val _viewMode = MutableLiveData(2)
    val viewMode: LiveData<Int> = _viewMode

    // 最近一次分析结果
    private val _lastResult = MutableLiveData<AlgorithmResult?>()
    val lastResult: LiveData<AlgorithmResult?> = _lastResult

    // 当前相机标定数据
    private val _currentCalibration = MutableLiveData<CalibrationData?>()
    val currentCalibration: LiveData<CalibrationData?> = _currentCalibration

    // 实时位姿追踪是否激活
    private val _isRealtimePoseActive = MutableLiveData(true)
    val isRealtimePoseActive: LiveData<Boolean> = _isRealtimePoseActive

    // 自动抓拍开关
    private val _isAutoCaptureEnabled = MutableLiveData(false)
    val isAutoCaptureEnabled: LiveData<Boolean> = _isAutoCaptureEnabled

    fun setViewMode(mode: Int) {
        _viewMode.value = mode
    }

    fun setLastResult(result: AlgorithmResult?) {
        _lastResult.value = result
    }

    fun setCurrentCalibration(calibration: CalibrationData?) {
        _currentCalibration.value = calibration
    }

    fun setRealtimePoseActive(active: Boolean) {
        _isRealtimePoseActive.value = active
    }

    fun setAutoCaptureEnabled(enabled: Boolean) {
        _isAutoCaptureEnabled.value = enabled
    }
}
