package com.example.asparagusclassifier

import android.graphics.Bitmap
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.example.asparagusclassifier.algorithm.AlgorithmResult
import com.example.asparagusclassifier.algorithm.CalibrationData
import com.example.asparagusclassifier.data.VisionRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * MainActivity 的状态管理器 (重构版)
 * 负责调度业务流、管理分析状态与并发任务
 */
class MainViewModel(private val visionRepository: VisionRepository) : ViewModel() {
    private val TAG = "MainViewModel"

    // 视图模式 (1: Raw, 2: 3D Analysis)
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

    // 抓拍请求事件
    private val _captureRequest = MutableLiveData<Long>()
    val captureRequest: LiveData<Long> = _captureRequest

    // 平滑后的相机位姿 (X, Y, Z)
    private var smoothedCameraPos: DoubleArray? = null
    private val SMOOTHING_FACTOR = 0.25 // 越小越稳定，越大越灵敏
    private val _isAnalyzing = MutableLiveData(false)
    val isAnalyzing: LiveData<Boolean> = _isAnalyzing

    // 兼容性警告信号
    private val _showWarningEvent = Channel<Unit>()
    val showWarningEvent = _showWarningEvent.receiveAsFlow()

    fun setViewMode(mode: Int) {
        if (_viewMode.value != mode) {
            _viewMode.value = mode
        }
    }

    fun setCurrentCalibration(calibration: CalibrationData?) {
        _currentCalibration.value = calibration
    }

    fun setRealtimePoseActive(active: Boolean) {
        if (_isRealtimePoseActive.value != active) {
            _isRealtimePoseActive.value = active
        }
    }

    fun setAutoCaptureEnabled(enabled: Boolean) {
        if (_isAutoCaptureEnabled.value != enabled) {
            _isAutoCaptureEnabled.value = enabled
        }
    }

    fun requestCapture() {
        _captureRequest.value = System.currentTimeMillis()
    }

    /**
     * 执行全流程分析
     */
    fun analyzeImage(bitmap: Bitmap) {
        if (_isAnalyzing.value == true) return
        
        viewModelScope.launch {
            _isAnalyzing.value = true
            
            try {
                val result = visionRepository.analyzeImage(
                    bitmap = bitmap,
                    calibration = _currentCalibration.value,
                    viewMode = _viewMode.value ?: 3,
                    onShowWarning = {
                        // 显式调用挂起函数
                        _showWarningEvent.send(Unit)
                    }
                )
                _lastResult.value = result
            } catch (e: Exception) {
                Log.e(TAG, "分析启动失败: ${e.message}")
            } finally {
                _isAnalyzing.value = false
            }
        }
    }

    /**
     * 执行实时位姿解算
     */
    fun analyzeRealtimePose(bitmap: Bitmap, onResult: (AlgorithmResult) -> Unit) {
        // 使用显式作用域启动
        this.viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            try {
                // 确保在挂起环境中调用
                val result = visionRepository.analyzeRealtimePose(bitmap, _currentCalibration.value)
                
                // 应用一阶低通滤波平滑位姿
                val rawPos = result.cameraPosWorld
                if (rawPos != null) {
                    val currentSmooth = smoothedCameraPos
                    if (currentSmooth == null) {
                        smoothedCameraPos = rawPos.copyOf()
                    } else {
                        for (i in rawPos.indices) {
                            currentSmooth[i] = SMOOTHING_FACTOR * rawPos[i] + (1 - SMOOTHING_FACTOR) * currentSmooth[i]
                        }
                    }
                }
                
                // 将平滑后的数据回传给结果对象（保持引用透明）
                val filteredResult = if (smoothedCameraPos != null) {
                    result.copy(cameraPosWorld = smoothedCameraPos!!.copyOf())
                } else {
                    result
                }
                
                onResult(filteredResult)
            } catch (e: Exception) {
                Log.e(TAG, "实时分析失败: ${e.message}")
            }
        }
    }
}
