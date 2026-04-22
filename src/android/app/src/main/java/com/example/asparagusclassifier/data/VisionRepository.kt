package com.example.asparagusclassifier.data

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.asparagusclassifier.algorithm.AlgorithmProcessor
import com.example.asparagusclassifier.algorithm.AlgorithmResult
import com.example.asparagusclassifier.algorithm.CalibrationData
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

/**
 * 视觉业务仓库
 * 负责调度图像处理算法、后台线程管理以及数据持久化（调试图像保存）
 */
class VisionRepository(private val context: Context) {
    private val TAG = "VisionRepository"
    private val diskExecutor = Executors.newSingleThreadExecutor()
    
    // 会话状态：是否已弹出过兼容性警告
    private var warnedThisSession = false

    /**
     * 执行全流程分析流水线
     * @param bitmap 待分析的原始图像
     * @param calibration 相机标定数据
     * @param viewMode 当前视图模式
     * @param onPreAnalysis 预处理回调（用于更新 UI 状态，如显示加载中）
     * @param onResult 分析结果回调
     * @param onShowWarning 兼容性警告回调（需要 UI 层确认后继续）
     */
    fun analyzeImage(
        bitmap: Bitmap,
        calibration: CalibrationData?,
        viewMode: Int,
        onPreAnalysis: () -> Unit,
        onResult: (AlgorithmResult) -> Unit,
        onShowWarning: (onContinue: () -> Unit) -> Unit
    ) {
        onPreAnalysis()

        diskExecutor.execute {
            // 检查内参兼容性
            if ((calibration == null || !calibration.isValid()) && !warnedThisSession) {
                onShowWarning {
                    warnedThisSession = true
                    executePipeline(bitmap, calibration, viewMode, onResult)
                }
            } else {
                executePipeline(bitmap, calibration, viewMode, onResult)
            }
        }
    }

    /**
     * 执行实时位姿解算（轻量级）
     */
    fun analyzeRealtimePose(
        bitmap: Bitmap,
        calibration: CalibrationData?,
        onResult: (AlgorithmResult) -> Unit
    ) {
        diskExecutor.execute {
            val result = AlgorithmProcessor.processRealtimePose(bitmap, calibration)
            onResult(result)
        }
    }

    private fun executePipeline(
        bitmap: Bitmap,
        calibration: CalibrationData?,
        viewMode: Int,
        onResult: (AlgorithmResult) -> Unit
    ) {
        val t0 = System.currentTimeMillis()
        val result = AlgorithmProcessor.processImage(bitmap, calibration, viewMode)
        val elapsed = System.currentTimeMillis() - t0
        Log.i(TAG, "算法总耗时: ${elapsed}ms")

        // 保存调试图片
        saveDebugBitmap(bitmap, "aruco_debug")

        onResult(result)
    }

    private fun saveDebugBitmap(bitmap: Bitmap, prefix: String) {
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
        diskExecutor.shutdown()
    }
}
