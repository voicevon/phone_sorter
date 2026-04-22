package com.example.asparagusclassifier.data

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.asparagusclassifier.algorithm.AlgorithmProcessor
import com.example.asparagusclassifier.algorithm.AlgorithmResult
import com.example.asparagusclassifier.algorithm.CalibrationData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * 视觉业务仓库 (重构版)
 * 采用 Kotlin Coroutines 实现高效的异步调度
 */
class VisionRepository(private val context: Context) {
    private val TAG = "VisionRepository"
    
    // 会话状态：是否已弹出过兼容性警告
    private var warnedThisSession = false

    /**
     * 执行全流程分析流水线 (挂起函数)
     */
    suspend fun analyzeImage(
        bitmap: Bitmap,
        calibration: CalibrationData?,
        viewMode: Int,
        onShowWarning: suspend () -> Unit
    ): AlgorithmResult = withContext(Dispatchers.Default) {
        
        // 1. 检查内参兼容性 (如果需要 UI 交互则挂起等待)
        if ((calibration == null || !calibration.isValid()) && !warnedThisSession) {
            onShowWarning()
            warnedThisSession = true
        }

        // 2. 执行核心算法
        val t0 = System.currentTimeMillis()
        val result = AlgorithmProcessor.processImage(bitmap, calibration, viewMode)
        val elapsed = System.currentTimeMillis() - t0
        Log.i(TAG, "算法总耗时: ${elapsed}ms")

        // 3. 异步保存调试图片 (不阻塞算法返回)
        saveDebugBitmap(bitmap, "aruco_debug")

        result
    }

    /**
     * 执行实时位姿解算 (挂起函数)
     */
    suspend fun analyzeRealtimePose(
        bitmap: Bitmap,
        calibration: CalibrationData?
    ): AlgorithmResult = withContext(Dispatchers.Default) {
        AlgorithmProcessor.processRealtimePose(bitmap, calibration)
    }

    /**
     * 保存调试图片 (内部 IO 调度)
     */
    private suspend fun saveDebugBitmap(bitmap: Bitmap, prefix: String) = withContext(Dispatchers.IO) {
        try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "${prefix}_${timeStamp}.jpg"
            val file = File(context.getExternalFilesDir(null), fileName)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            Log.d(TAG, "已保存调试图像: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "保存调试图像失败: ${e.message}")
        }
    }

    fun release() {
        // 协程不需要手动关闭线程池，生命周期由调用方作用域管理
    }
}
