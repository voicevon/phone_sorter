package com.example.asparagusclassifier.algorithm

import android.graphics.PointF
import android.util.Log
import org.opencv.core.*
import org.opencv.objdetect.ArucoDetector
import org.opencv.objdetect.DetectorParameters
import org.opencv.objdetect.Objdetect
import org.opencv.imgproc.Imgproc

/**
 * ArUco 标记检测引擎
 * 负责识别标定板上的 4 个关键标记
 * 输入通常为去畸变后的物理图像
 */
class ArucoEngine(
    private val dictionaryId: Int = Objdetect.DICT_4X4_50
) {
    private val TAG = "ArucoEngine"
    private val dictionary = Objdetect.getPredefinedDictionary(dictionaryId)
    private val detectorParams = DetectorParameters().apply {
        set_adaptiveThreshWinSizeMin(3)
        set_adaptiveThreshWinSizeMax(63) // 扩大搜索窗口，适应高分辨率画面
        set_adaptiveThreshWinSizeStep(5)  // 提高搜索密度
        set_minMarkerPerimeterRate(0.01)
        set_cornerRefinementMethod(Objdetect.CORNER_REFINE_SUBPIX) // 启用子像素精细化
    }
    private val detector = ArucoDetector(dictionary, detectorParams)

    data class DetectionResult(
        val success: Boolean,
        val markerMap: Map<Int, Array<PointF>>, // 在该图像上的像素坐标
        val error: String? = null
    )

    /**
     * 在提供的 Mat 上检测 ArUco 标记
     */
    fun detectBoardMarkers(
        rgba: Mat
    ): DetectionResult {
        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)

        val ids = Mat()
        val corners = mutableListOf<Mat>()
        val rejected = mutableListOf<Mat>()

        // 执行检测
        detector.detectMarkers(gray, corners, ids, rejected)
        val detectedCount = ids.rows()
        val rejectedCount = rejected.size
        Log.i(TAG, "ArUco 检测完成: 发现标记数=$detectedCount, 拒绝的候选数=$rejectedCount")

        // 解析结果
        val markerMap = mutableMapOf<Int, Array<PointF>>()
        val foundIds = mutableListOf<Int>()
        if (detectedCount > 0) {
            for (i in 0 until detectedCount) {
                val id = ids.get(i, 0)[0].toInt()
                foundIds.add(id)
                val cornerMat = corners[i]
                val decoded = Array(4) { j -> 
                    PointF(cornerMat.get(0, j)[0].toFloat(), cornerMat.get(0, j)[1].toFloat())
                }
                markerMap[id] = decoded
                
                // 计算并记录中心点，方便判断坐标是否在合理范围内
                val centerX = (decoded[0].x + decoded[1].x + decoded[2].x + decoded[3].x) / 4f
                val centerY = (decoded[0].y + decoded[1].y + decoded[2].y + decoded[3].y) / 4f
                Log.d(TAG, "  -> ID: $id, 中心点: (%.1f, %.1f)".format(centerX, centerY))
            }
        }
        Log.i(TAG, "当前画面中包含的 ID 列表: $foundIds")

        // 验证关键标记
        val requiredIds = listOf(
            AlgorithmConfig.ID_TL, AlgorithmConfig.ID_TR, 
            AlgorithmConfig.ID_BR, AlgorithmConfig.ID_BL
        )
        val missingIds = requiredIds.filter { !markerMap.containsKey(it) }

        // 清理中间 Mat
        gray.release()
        ids.release()
        for (m in corners) m.release()
        for (m in rejected) m.release()

        return if (missingIds.isEmpty()) {
            Log.i(TAG, "所有标定标记已找齐，可以进行透视变换")
            DetectionResult(true, markerMap)
        } else {
            val errorMsg = "检测失败，找齐:${markerMap.keys}, 缺失:$missingIds (已忽略干扰项:${markerMap.keys.filter { it !in requiredIds }})"
            Log.w(TAG, "ArUco 检测失败诊断: $errorMsg, 拒绝候选数=$rejectedCount")
            DetectionResult(false, markerMap, errorMsg)
        }
    }
}
