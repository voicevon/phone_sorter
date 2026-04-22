package com.example.asparagusclassifier.algorithm

import android.graphics.PointF
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.sqrt

/**
 * 特征提取器
 * 负责解析图像中的原始几何特征：轮廓、紫根、中心轴线
 */
object FeatureExtractor {
    private const val TAG = "FeatureExtractor"

    /**
     * 提取芦笋特征
     */
    fun extract(hsv: Mat, mask: Mat, contour: MatOfPoint): AsparagusFeatures {
        val purplePoint = detectPurpleRoot(hsv, contour)
        val rawAxis = computeCenterline(mask, contour)
        val orientedAxis = orientAxis(rawAxis, purplePoint, mask)
        
        return AsparagusFeatures(
            contourPoints = contour.toArray().map { PointF(it.x.toFloat(), it.y.toFloat()) },
            axisPoints = orientedAxis,
            purpleRootPoint = purplePoint?.let { PointF(it.x.toFloat(), it.y.toFloat()) }
        )
    }

    data class AsparagusFeatures(
        val contourPoints: List<PointF>,
        val axisPoints: List<PointF>,
        val purpleRootPoint: PointF?
    )

    private fun detectPurpleRoot(hsv: Mat, contour: MatOfPoint): Point? {
        val purpleMask = Mat()
        val roiMask = Mat.zeros(hsv.size(), CvType.CV_8UC1)
        val filtered = Mat()
        val nonZero = MatOfPoint()
        try {
            Core.inRange(hsv, AlgorithmConfig.LOWER_PURPLE, AlgorithmConfig.UPPER_PURPLE, purpleMask)
            Imgproc.drawContours(roiMask, listOf(contour), -1, Scalar(255.0), -1)
            Core.bitwise_and(purpleMask, roiMask, filtered)
            Core.findNonZero(filtered, nonZero)
            
            if (nonZero.total() > 0) {
                val pts = nonZero.toArray()
                return Point(pts.map { it.x }.average(), pts.map { it.y }.average())
            }
        } finally {
            purpleMask.release()
            roiMask.release()
            filtered.release()
            nonZero.release()
        }
        return null
    }

    private fun computeCenterline(mask: Mat, contour: MatOfPoint): List<PointF> {
        val contour2f = MatOfPoint2f(*contour.toArray())
        val rotRect = Imgproc.minAreaRect(contour2f)
        contour2f.release()

        val center = rotRect.center
        val angle = if (rotRect.size.width > rotRect.size.height) rotRect.angle + 90.0 else rotRect.angle

        val rotMat = Imgproc.getRotationMatrix2D(center, angle, 1.0)
        val rotated = Mat()
        Imgproc.warpAffine(mask, rotated, rotMat, mask.size())

        val nonZero = Mat()
        Core.findNonZero(rotated, nonZero)
        if (nonZero.total() == 0L) {
            rotated.release(); nonZero.release(); rotMat.release()
            return emptyList()
        }
        val bbox = Imgproc.boundingRect(nonZero)
        nonZero.release()

        val points = mutableListOf<PointF>()
        for (y in bbox.y until bbox.y + bbox.height) {
            var left = -1; var right = -1
            for (x in bbox.x until bbox.x + bbox.width) {
                if (rotated.get(y, x)[0] > 0.0) {
                    if (left == -1) left = x
                    right = x
                }
            }
            if (left != -1) points.add(PointF(((left + right) / 2f), y.toFloat()))
        }
        rotated.release()

        val invRotMat = Imgproc.getRotationMatrix2D(center, -angle, 1.0)
        val m = DoubleArray(6)
        invRotMat.get(0, 0, m)
        invRotMat.release()
        rotMat.release()

        return points.map { p ->
            PointF(
                (m[0] * p.x + m[1] * p.y + m[2]).toFloat(),
                (m[3] * p.x + m[4] * p.y + m[5]).toFloat()
            )
        }
    }

    private fun orientAxis(axis: List<PointF>, purple: Point?, mask: Mat): List<PointF> {
        if (axis.size < 4) return axis
        var needsReverse = false
        
        if (purple != null) {
            val dFirst = dist(axis.first(), PointF(purple.x.toFloat(), purple.y.toFloat()))
            val dLast = dist(axis.last(), PointF(purple.x.toFloat(), purple.y.toFloat()))
            needsReverse = dLast < dFirst
        } else {
            val segmentSize = axis.size / 4
            val headSeg = axis.take(segmentSize)
            val tailSeg = axis.takeLast(segmentSize)
            
            val sHead = StraightnessAnalyzer.analyze(headSeg).rmse
            val sTail = StraightnessAnalyzer.analyze(tailSeg).rmse
            
            val R = 20
            fun density(pt: PointF): Double {
                var cnt = 0
                for (dy in -R..R) {
                    for (dx in -R..R) {
                        if (dx*dx + dy*dy > R*R) continue
                        val px = (pt.x + dx).toInt(); val py = (pt.y + dy).toInt()
                        if (px in 0 until mask.cols() && py in 0 until mask.rows()) {
                            if (mask.get(py, px)[0] > 0) cnt++
                        }
                    }
                }
                return cnt.toDouble()
            }
            val dHead = headSeg.map { density(it) }.average()
            val dTail = tailSeg.map { density(it) }.average()
            
            val scoreAtStart = dHead / (sHead + 1.0)
            val scoreAtEnd = dTail / (sTail + 1.0)
            needsReverse = scoreAtEnd > scoreAtStart
        }
        
        return if (needsReverse) axis.reversed() else axis
    }

    private fun dist(p1: PointF, p2: PointF) = sqrt(((p1.x - p2.x) * (p1.x - p2.x) + (p1.y - p2.y) * (p1.y - p2.y)).toDouble())
}
