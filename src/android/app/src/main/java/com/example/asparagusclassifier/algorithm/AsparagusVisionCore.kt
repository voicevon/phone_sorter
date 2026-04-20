package com.example.asparagusclassifier.algorithm

import android.graphics.PointF
import android.util.Log
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.sqrt

/**
 * 芦笋视觉核心算法
 * 负责解析标准化画布（Warped Mat）中的芦笋几何特征
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
        val error: String? = null
    )

    /**
     * 在标准化空间执行分析
     */
    fun analyze(warpedRgba: Mat, pixelsPerMm: Double): AnalysisResult {
        val hsv = Mat()
        val mask = Mat()
        val hierarchy = Mat()
        
        try {
            Imgproc.cvtColor(warpedRgba, hsv, Imgproc.COLOR_RGB2HSV)
            
            // 1. 绿色分割
            Core.inRange(hsv, AlgorithmConfig.LOWER_GREEN, AlgorithmConfig.UPPER_GREEN, mask)
            
            // 形态学滤波
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, kernel)
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel)
            kernel.release()

            val contours = mutableListOf<MatOfPoint>()
            Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            
            val maxContour = contours.maxByOrNull { Imgproc.contourArea(it) }
            
            if (maxContour == null || Imgproc.contourArea(maxContour) < AlgorithmConfig.MIN_CONTOUR_AREA) {
                return AnalysisResult(false, error = "未检测到芦笋或面积过小")
            }

            // 2. 几何分析
            val contourPoints = maxContour.toArray().map { PointF(it.x.toFloat(), it.y.toFloat()) }
            
            // 紫根检测
            val purplePoint = detectPurpleRoot(hsv, maxContour)
            
            // 轴线提取
            val rawAxis = computeCenterline(mask, maxContour)
            
            // 方向校正 (根据紫根或像素密度)
            val orientedAxis = orientAxis(rawAxis, purplePoint, mask)
            
            // 3. 物理测量
            var lengthMm = 0.0
            var rawDiameterMm = 0.0
            val diameterLines = mutableListOf<List<PointF>>()

            if (orientedAxis.size >= 2) {
                // 长度
                var totalLengthPx = 0.0
                for (i in 0 until orientedAxis.size - 1) {
                    totalLengthPx += dist(orientedAxis[i], orientedAxis[i+1])
                }
                lengthMm = totalLengthPx / pixelsPerMm

                // 直径采样
                val diameterSamples = mutableListOf<Double>()
                for (offsetMm in AlgorithmConfig.SAMPLING_OFFSETS_MM) {
                    val offsetPx = offsetMm * pixelsPerMm
                    val (sampleP, normalV) = findPointAndNormalAtDistance(orientedAxis, offsetPx)
                    
                    if (sampleP != null && normalV != null) {
                        val widthPx = measureWidth(mask, sampleP, normalV)
                        diameterSamples.add(widthPx / pixelsPerMm)
                        
                        diameterLines.add(listOf(
                            PointF((sampleP.x - normalV.x * widthPx/2).toFloat(), (sampleP.y - normalV.y * widthPx/2).toFloat()),
                            PointF((sampleP.x + normalV.x * widthPx/2).toFloat(), (sampleP.y + normalV.y * widthPx/2).toFloat())
                        ))
                    }
                }
                
                if (diameterSamples.isNotEmpty()) {
                    rawDiameterMm = diameterSamples.average()
                }
            }

            val correctedDiameter = maxOf(0.0, rawDiameterMm - AlgorithmConfig.DIAMETER_CORRECTION_MM)
            val grade = calculateGrade(correctedDiameter)

            return AnalysisResult(
                success = true,
                grade = grade,
                diameterMm = correctedDiameter,
                lengthMm = lengthMm,
                rawDiameterMm = rawDiameterMm,
                contourPoints = contourPoints,
                axisPoints = orientedAxis,
                purpleRootPoint = purplePoint?.let { PointF(it.x.toFloat(), it.y.toFloat()) },
                diameterLines = diameterLines
            )

        } finally {
            hsv.release()
            mask.release()
            hierarchy.release()
        }
    }

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

        // 逆旋转
        val invRotMat = Imgproc.getRotationMatrix2D(center, -angle, 1.0)
        val m = DoubleArray(6)
        invRotMat.get(0, 0, m)
        invRotMat.release()

        return points.map { p ->
            PointF(
                (m[0] * p.x + m[1] * p.y + m[2]).toFloat(),
                (m[3] * p.x + m[4] * p.y + m[5]).toFloat()
            )
        }
    }

    private fun orientAxis(axis: List<PointF>, purple: Point?, mask: Mat): List<PointF> {
        if (axis.size < 2) return axis
        
        val needsReverse = if (purple != null) {
            val dFirst = dist(axis.first(), PointF(purple.x.toFloat(), purple.y.toFloat()))
            val dLast = dist(axis.last(), PointF(purple.x.toFloat(), purple.y.toFloat()))
            dLast < dFirst
        } else {
            // 密度判向
            val sampleCount = maxOf(3, axis.size / 10)
            val R = 20
            fun density(pt: PointF): Double {
                var cnt = 0
                for (dy in -R..R) {
                    for (dx in -R..R) {
                        if (dx*dx + dy*dy > R*R) continue
                        val px = (pt.x + dx).toInt()
                        val py = (pt.y + dy).toInt()
                        if (px in 0 until mask.cols() && py in 0 until mask.rows()) {
                            if (mask.get(py, px)[0] > 0) cnt++
                        }
                    }
                }
                return cnt.toDouble()
            }
            val dFirst = axis.take(sampleCount).map { density(it) }.average()
            val dLast = axis.takeLast(sampleCount).map { density(it) }.average()
            dLast > dFirst
        }
        
        return if (needsReverse) axis.reversed() else axis
    }

    private fun findPointAndNormalAtDistance(path: List<PointF>, targetDist: Double): Pair<Point?, Point?> {
        var acc = 0.0
        val window = 12
        for (i in 0 until path.size - 1) {
            val d = dist(path[i], path[i+1])
            if (acc + d >= targetDist) {
                val r = (targetDist - acc) / d
                val p = Point((path[i].x + (path[i+1].x - path[i].x) * r).toDouble(), (path[i].y + (path[i+1].y - path[i].y) * r).toDouble())
                
                val sIdx = maxOf(0, i - window)
                val eIdx = minOf(path.size - 1, i + window)
                if (eIdx > sIdx) {
                    val dx = (path[eIdx].x - path[sIdx].x).toDouble()
                    val dy = (path[eIdx].y - path[sIdx].y).toDouble()
                    val l = sqrt(dx*dx + dy*dy)
                    if (l > 1e-5) return Pair(p, Point(-dy/l, dx/l))
                }
                val dx = (path[i+1].x - path[i].x).toDouble()
                val dy = (path[i+1].y - path[i].y).toDouble()
                val l = sqrt(dx*dx + dy*dy)
                return Pair(p, Point(-dy/l, dx/l))
            }
            acc += d
        }
        return Pair(null, null)
    }

    private fun measureWidth(mask: Mat, c: Point, n: Point): Double {
        var w1 = 0.0; var w2 = 0.0
        for (i in 1..250) {
            val px = (c.x + n.x * i).toInt(); val py = (c.y + n.y * i).toInt()
            if (px !in 0 until mask.cols() || py !in 0 until mask.rows()) break
            if (mask.get(py, px)[0] == 0.0) { w1 = i.toDouble(); break }
        }
        for (i in 1..250) {
            val px = (c.x - n.x * i).toInt(); val py = (c.y - n.y * i).toInt()
            if (px !in 0 until mask.cols() || py !in 0 until mask.rows()) break
            if (mask.get(py, px)[0] == 0.0) { w2 = i.toDouble(); break }
        }
        return w1 + w2
    }

    private fun calculateGrade(d: Double) = when {
        d > 15.0 -> "A"
        d > 12.0 -> "B"
        d > 10.0 -> "C"
        d > 8.0 -> "D"
        d > 5.0 -> "E"
        else -> "F"
    }

    private fun dist(p1: PointF, p2: PointF) = sqrt(((p1.x - p2.x) * (p1.x - p2.x) + (p1.y - p2.y) * (p1.y - p2.y)).toDouble())
}
