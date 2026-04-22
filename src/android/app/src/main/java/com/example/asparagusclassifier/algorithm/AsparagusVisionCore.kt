package com.example.asparagusclassifier.algorithm

import android.graphics.PointF
import android.util.Log
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import com.example.asparagusclassifier.util.useMatScope
import kotlin.math.sqrt

/**
 * 芦笋视觉核心算法 (重构版)
 * 作为“分析管线指挥官”，负责调度各个子引擎执行特定分析任务。
 */
object AsparagusVisionCore {
    private const val TAG = "AsparagusVisionCore"

    data class AnalysisResult(
        val success: Boolean,
        val grade: String = "F",
        val diameterMm: Double = 0.0,
        val lengthMm: Double = 0.0,
        val rawDiameterMm: Double = 0.0,
        val contourPoints: List<PointF> = emptyList(),
        val axisPoints: List<PointF> = emptyList(),
        val purpleRootPoint: PointF? = null,
        val diameterLines: List<List<PointF>> = emptyList(),
        val straightnessOverall: Double = 0.0,
        val straightnessHead: Double = 0.0,
        val straightnessTail: Double = 0.0,
        val baselineOverall: List<PointF>? = null,
        val baselineHead: List<PointF>? = null,
        val baselineTail: List<PointF>? = null,
        val error: String? = null
    )

    /**
     * 在物理去畸变空间执行全流程分析
     */
    fun analyze(
        rgbaUndistorted: Mat, 
        poseInfo: PoseEstimator.PoseInfo? = null
    ): AnalysisResult = useMatScope { scope ->
        val hsv = scope.createMat()
        val mask = scope.createMat()
        val hierarchy = scope.createMat()
        
        try {
            // 1. 预处理与分割
            Imgproc.cvtColor(rgbaUndistorted, hsv, Imgproc.COLOR_RGB2HSV)
            
            // 使用内缩 5% 的区域作为安全分析区，避免边缘标记干扰
            val roiMask = scope.createMat()
            roiMask.create(rgbaUndistorted.size(), CvType.CV_8UC1)
            roiMask.setTo(Scalar(0.0))
            val marginW = (rgbaUndistorted.cols() * 0.05).toInt()
            val marginH = (rgbaUndistorted.rows() * 0.05).toInt()
            val safeRect = Rect(marginW, marginH, rgbaUndistorted.cols() - 2 * marginW, rgbaUndistorted.rows() - 2 * marginH)
            Imgproc.rectangle(roiMask, safeRect, Scalar(255.0), -1)
            
            Core.inRange(hsv, AlgorithmConfig.LOWER_GREEN, AlgorithmConfig.UPPER_GREEN, mask)
            Core.bitwise_and(mask, roiMask, mask)
            
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, kernel)
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel)
            kernel.release()
 
            // 2. 轮廓提取
            val contours = mutableListOf<MatOfPoint>()
            Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            
            contours.forEach { scope.manage(it) }
            val maxContour = contours.maxByOrNull { Imgproc.contourArea(it) }
            
            if (maxContour == null || Imgproc.contourArea(maxContour) < AlgorithmConfig.MIN_CONTOUR_AREA) {
                return@useMatScope AnalysisResult(false, error = "未检测到芦笋或面积过小")
            }
 
            // 3. 特征提取
            val features = FeatureExtractor.extract(hsv, mask, maxContour)
            val orientedAxis = features.axisPoints
 
            // 4. 物理测量 (完全基于 3D Pose)
            if (poseInfo == null) {
                return@useMatScope AnalysisResult(false, error = "缺少位姿信息，无法测量")
            }
 
            var lengthMm = 0.0
            if (orientedAxis.size >= 2) {
                // 假设芦笋平均厚度为 16mm，因此中心面在 Z=8mm
                val pStart3D = PoseEstimator.mapImageToWorld3D(orientedAxis.first(), 8.0, poseInfo)
                val pEnd3D = PoseEstimator.mapImageToWorld3D(orientedAxis.last(), 8.0, poseInfo)
                lengthMm = dist3D(pStart3D, pEnd3D)
            }
 
            // 估算局部 pixelsPerMm (用于直径采样和直线度)
            val centerPt = if (orientedAxis.isNotEmpty()) orientedAxis[orientedAxis.size / 2] else PointF(0f, 0f)
            val p1_3d = PoseEstimator.mapImageToWorld3D(centerPt, 8.0, poseInfo)
            val p2_3d = PoseEstimator.mapImageToWorld3D(PointF(centerPt.x + 10f, centerPt.y), 8.0, poseInfo)
            val pixelsPerMm = 10.0 / sqrt((p1_3d.x - p2_3d.x) * (p1_3d.x - p2_3d.x) + (p1_3d.y - p2_3d.y) * (p1_3d.y - p2_3d.y))
 
            // 确保采样轴线从“变紫点”或“物理底端”开始
            val samplingAxis = if (features.purpleRootPoint != null) {
                val purple = features.purpleRootPoint
                val closestIdx = orientedAxis.indices.minByOrNull { dist(orientedAxis[it], purple) } ?: 0
                orientedAxis.drop(closestIdx)
            } else {
                orientedAxis
            }

            val samplingResult = DiameterSampler.samples(mask, samplingAxis, pixelsPerMm, poseInfo)
            val rawDiameterMm = samplingResult.diameterMm
            val correctedDiameter = maxOf(0.0, rawDiameterMm - AlgorithmConfig.DIAMETER_CORRECTION_MM)
 
            // 5. 直线度分析
            var sOverall = 0.0; var sHead = 0.0; var sTail = 0.0
            var bOverall: List<PointF>? = null; var bHead: List<PointF>? = null; var bTail: List<PointF>? = null
            
            if (orientedAxis.size >= 4) {
                val resOverall = StraightnessAnalyzer.analyze(orientedAxis)
                sOverall = resOverall.rmse / pixelsPerMm
                bOverall = resOverall.endpoints
                
                val segmentSize = orientedAxis.size / 4
                val resHead = StraightnessAnalyzer.analyze(orientedAxis.take(segmentSize))
                val resTail = StraightnessAnalyzer.analyze(orientedAxis.takeLast(segmentSize))
                
                sHead = resHead.rmse / pixelsPerMm
                sTail = resTail.rmse / pixelsPerMm
                bHead = resHead.endpoints
                bTail = resTail.endpoints
            }
 
            // 5. 质量与分级审计
            val qualityReport = QualityAnalyzer.audit(features.contourPoints, orientedAxis, sOverall)
            
            val finalGrade = if (!qualityReport.isQualified) {
                "F" // 不合格
            } else {
                GradingConfig.calculateGrade(correctedDiameter)
            }

            AnalysisResult(
                success = true,
                grade = finalGrade,
                diameterMm = correctedDiameter,
                lengthMm = lengthMm,
                rawDiameterMm = rawDiameterMm,
                contourPoints = features.contourPoints,
                axisPoints = orientedAxis,
                purpleRootPoint = features.purpleRootPoint,
                diameterLines = samplingResult.diameterLines,
                straightnessOverall = sOverall,
                straightnessHead = sHead,
                straightnessTail = sTail,
                baselineOverall = bOverall,
                baselineHead = bHead,
                baselineTail = bTail,
                error = qualityReport.reason // 将不合格原因存入错误字段供 UI 显示
            )

        } catch (e: Exception) {
            Log.e(TAG, "分析过程异常: ${e.message}")
            AnalysisResult(false, error = e.message)
        }
    }

    private fun computePixelPathLength(path: List<PointF>): Double {
        var total = 0.0
        for (i in 0 until path.size - 1) {
            val dx = (path[i].x - path[i+1].x).toDouble()
            val dy = (path[i].y - path[i+1].y).toDouble()
            total += sqrt(dx*dx + dy*dy)
        }
        return total
    }

    private fun dist3D(p1: Point3, p2: Point3) = 
        sqrt((p1.x - p2.x) * (p1.x - p2.x) + (p1.y - p2.y) * (p1.y - p2.y) + (p1.z - p2.z) * (p1.z - p2.z))

    private fun dist(p1: PointF, p2: PointF) = 
        sqrt(((p1.x - p2.x) * (p1.x - p2.x) + (p1.y - p2.y) * (p1.y - p2.y)).toDouble())
}
