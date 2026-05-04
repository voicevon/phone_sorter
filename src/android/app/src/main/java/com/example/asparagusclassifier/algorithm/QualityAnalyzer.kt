package com.example.asparagusclassifier.algorithm

import android.graphics.PointF
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.MatOfInt
import org.opencv.imgproc.Imgproc
import kotlin.math.sqrt

/**
 * 质量分析器
 * 负责检测“散头/开花”、“直线度不足”等不合格项
 */
object QualityAnalyzer {

    data class QualityReport(
        val isQualified: Boolean,
        val reason: String? = null,
        val headComplexity: Double = 0.0
    )

    /**
     * 执行质量审计
     */
    fun audit(
        contour: List<PointF>,
        axis: List<PointF>,
        rmseMm: Double
    ): QualityReport {
        // 1. 直线度审计
        if (rmseMm > AlgorithmConfig.MAX_STRAIGHTNESS_RMSE_MM) {
            return QualityReport(false, "直线度不足 (RMSE: %.1f mm)".format(rmseMm))
        }

        // 2. 散头/开花审计
        val headComp = checkHeadComplexity(contour, axis)
        if (headComp > AlgorithmConfig.SCATTERED_HEAD_THRESHOLD) {
            return QualityReport(false, "散头/开花 (复杂度: %.2f)".format(headComp), headComp)
        }

        return QualityReport(true, headComplexity = headComp)
    }

    /**
     * 检测头部复杂度 (散头检测)
     * 逻辑：提取头部的轮廓段，计算轮廓周长与凸包周长的比值
     */
    private fun checkHeadComplexity(contour: List<PointF>, axis: List<PointF>): Double {
        if (axis.size < 10 || contour.size < 20) return 1.0
        
        val headPt = axis.last()
        // 提取距离头部 30mm 以内的轮廓点
        val headRegionPoints = contour.filter { dist(it, headPt) < 50.0 } // 50px 约 5mm
        if (headRegionPoints.size < 10) return 1.0

        val points = headRegionPoints.map { org.opencv.core.Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray()
        val mat2f = MatOfPoint2f(*points)
        val mat = MatOfPoint(*points)
        
        // 轮廓长度
        val arcLength = Imgproc.arcLength(mat2f, false)
        
        // 凸包
        val hullIndices = MatOfInt()
        Imgproc.convexHull(mat, hullIndices)
        
        val hullPoints = mutableListOf<org.opencv.core.Point>()
        val indices = hullIndices.toArray()
        val originalPoints = mat2f.toArray()
        for (idx in indices) {
            hullPoints.add(originalPoints[idx])
        }
        val hullMat = MatOfPoint2f(*hullPoints.toTypedArray())
        val hullLength = Imgproc.arcLength(hullMat, true)
        
        val complexity = if (hullLength > 0) arcLength / hullLength else 1.0
        
        mat.release()
        mat2f.release()
        hullIndices.release()
        hullMat.release()
        
        return complexity
    }

    private fun dist(p1: PointF, p2: PointF) = sqrt(((p1.x - p2.x) * (p1.x - p2.x) + (p1.y - p2.y) * (p1.y - p2.y)).toDouble())
}
