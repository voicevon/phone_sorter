package com.example.asparagusclassifier.algorithm

import android.graphics.PointF
import android.util.Log
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
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
     * 在标准化空间执行全流程分析
     */
    fun analyze(
        warpedRgba: Mat, 
        pixelsPerMm: Double,
        poseInfo: PoseEstimator.PoseInfo? = null
    ): AnalysisResult {
        val hsv = Mat()
        val mask = Mat()
        val hierarchy = Mat()
        
        try {
            // 1. 预处理与分割
            Imgproc.cvtColor(warpedRgba, hsv, Imgproc.COLOR_RGB2HSV)
            
            val roiMask = Mat.zeros(warpedRgba.size(), CvType.CV_8UC1)
            val boardRect = Rect(10, 10, warpedRgba.cols() - 20, warpedRgba.rows() - 20)
            Imgproc.rectangle(roiMask, boardRect, Scalar(255.0), -1)
            
            Core.inRange(hsv, AlgorithmConfig.LOWER_GREEN, AlgorithmConfig.UPPER_GREEN, mask)
            Core.bitwise_and(mask, roiMask, mask)
            roiMask.release()
            
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, kernel)
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel)
            kernel.release()

            // 2. 轮廓提取
            val contours = mutableListOf<MatOfPoint>()
            Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            val maxContour = contours.maxByOrNull { Imgproc.contourArea(it) }
            
            if (maxContour == null || Imgproc.contourArea(maxContour) < AlgorithmConfig.MIN_CONTOUR_AREA) {
                return AnalysisResult(false, error = "未检测到芦笋或面积过小")
            }

            // 3. 特征提取 (紫根、中心线、方向)
            val features = FeatureExtractor.extract(hsv, mask, maxContour)
            val orientedAxis = features.axisPoints

            // 4. 物理测量 (长度与直径)
            var lengthMm = 0.0
            if (orientedAxis.size >= 2) {
                lengthMm = if (poseInfo != null) {
                    val pStart3D = PoseEstimator.mapCanvas3ToWorld3D(orientedAxis.first(), 8.0)
                    val pEnd3D = PoseEstimator.mapCanvas3ToWorld3D(orientedAxis.last(), 8.0)
                    dist3D(pStart3D, pEnd3D)
                } else {
                    computePixelPathLength(orientedAxis) / pixelsPerMm
                }
            }

            val samplingResult = DiameterSampler.samples(mask, orientedAxis, pixelsPerMm, poseInfo)
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

            return AnalysisResult(
                success = true,
                grade = calculateGrade(correctedDiameter),
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
                baselineTail = bTail
            )

        } catch (e: Exception) {
            Log.e(TAG, "分析过程异常: ${e.message}")
            return AnalysisResult(false, error = e.message)
        } finally {
            hsv.release()
            mask.release()
            hierarchy.release()
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

    private fun calculateGrade(d: Double) = when {
        d > 15.0 -> "A"
        d > 12.0 -> "B"
        d > 10.0 -> "C"
        d > 8.0 -> "D"
        d > 5.0 -> "E"
        else -> "F"
    }

    private fun dist3D(p1: Point3, p2: Point3) = 
        sqrt((p1.x - p2.x) * (p1.x - p2.x) + (p1.y - p2.y) * (p1.y - p2.y) + (p1.z - p2.z) * (p1.z - p2.z))
}
