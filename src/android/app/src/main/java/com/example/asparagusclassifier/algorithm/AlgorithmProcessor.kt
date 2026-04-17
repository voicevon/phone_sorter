package com.example.asparagusclassifier.algorithm

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.objdetect.*
import org.opencv.imgproc.Imgproc
import kotlin.math.sqrt
import kotlin.math.abs

import android.graphics.PointF

data class AlgorithmResult(
    val success: Boolean,
    val grade: String = "F",
    val diameter: Double = 0.0,
    val length: Double = 0.0,
    val purpleRootPosition: String = "未检测到",
    val asparagusRect: Rect? = null,
    val asparagusContour: List<PointF>? = null, // 芦笋轮廓点
    val tailPoint: PointF? = null, // 芦笋头尾标记（紫根位置）
    val diameterLine: List<PointF>? = null, // 直径测量线 (2个点)
    val arucoCorners: List<Array<PointF>>? = null, // 每个标记的4个角点
    val arucoIds: List<Int>? = null,
    val error: String? = null,
    val mode: ClassificationMode = ClassificationMode.ASPARAGUS,
    val screwSpec: String? = null, // 螺丝规格
    val roiRect: Rect? = null // 感兴趣区域 (例如白纸区域)
)

object AlgorithmProcessor {
    private const val TAG = "AsparagusClassifier"
    // 假设 ArUco 标记的物理尺寸为 50mm
    private const val ARUCO_SIZE_MM = 50.0 
    
    fun processImage(bitmap: Bitmap): AlgorithmResult {
        return processAsparagus(bitmap)
    }

    private fun processAsparagus(bitmap: Bitmap): AlgorithmResult {
        Log.i(TAG, "开始执行算法: 芦笋分级")
        val rgba = Mat()
        val hsv = Mat()
        val mask = Mat()
        val purpleMask = Mat()
        val roiMask = Mat()
        val hierarchy = Mat()
        val ids = Mat()
        val corners = mutableListOf<Mat>()
        val rejected = mutableListOf<Mat>()
        
        try {
            Utils.bitmapToMat(bitmap, rgba)
            
            // --- 1. ArUco 检测与标定 ---
            val dictionary = Objdetect.getPredefinedDictionary(Objdetect.DICT_4X4_50)
            val detector = ArucoDetector(dictionary)
            detector.detectMarkers(rgba, corners, ids, rejected)
            
            val markerCornersList = mutableListOf<Array<PointF>>()
            val markerIds = mutableListOf<Int>()
            var pixelsPerMm = 0.0
            
            if (ids.rows() > 0) {
                for (i in 0 until corners.size) {
                    val cornerMat = corners[i]
                    val id = ids.get(i, 0)[0].toInt()
                    markerIds.add(id)
                    
                    val p0 = PointF(cornerMat.get(0, 0)[0].toFloat(), cornerMat.get(0, 0)[1].toFloat())
                    val p1 = PointF(cornerMat.get(0, 1)[0].toFloat(), cornerMat.get(0, 1)[1].toFloat())
                    val p2 = PointF(cornerMat.get(0, 2)[0].toFloat(), cornerMat.get(0, 2)[1].toFloat())
                    val p3 = PointF(cornerMat.get(0, 3)[0].toFloat(), cornerMat.get(0, 3)[1].toFloat())
                    
                    markerCornersList.add(arrayOf(p0, p1, p2, p3))
                    
                    // 计算相邻角点的像素距离 (取平均值)
                    val d1 = dist(p0, p1)
                    val d2 = dist(p1, p2)
                    val d3 = dist(p2, p3)
                    val d4 = dist(p3, p0)
                    val avgPixelSize = (d1 + d2 + d3 + d4) / 4.0
                    
                    // 简单的标定：假设相机垂直，取第一个标记计算比例
                    if (pixelsPerMm == 0.0) {
                        pixelsPerMm = avgPixelSize / ARUCO_SIZE_MM
                        Log.i(TAG, "标定参数: pixelsPerMm=$pixelsPerMm (基于ID=$id, Pixel=$avgPixelSize, Real=${ARUCO_SIZE_MM}mm)")
                    }
                }
            } else {
                 Log.w(TAG, "未检测到 ArUco 标记，无法进行精确测量。")
            }
            
            // --- 2. 芦笋检测 (HSV 颜色分割) ---
            Imgproc.cvtColor(rgba, hsv, Imgproc.COLOR_RGB2HSV)
            
            val lowerGreen = Scalar(35.0, 50.0, 50.0)
            val upperGreen = Scalar(85.0, 255.0, 255.0)
            Core.inRange(hsv, lowerGreen, upperGreen, mask)
            
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, kernel)
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel)
            
            val contours = mutableListOf<MatOfPoint>()
            Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            
            val maxContour = contours.maxByOrNull { Imgproc.contourArea(it) }
            
            if (maxContour != null && Imgproc.contourArea(maxContour) > 1000) {
                val rect = Imgproc.boundingRect(maxContour)
                val androidRect = Rect(rect.x, rect.y, rect.x + rect.width, rect.y + rect.height)
                
                val matOfPoint2f = MatOfPoint2f()
                maxContour.convertTo(matOfPoint2f, CvType.CV_32F)
                val rotatedRect = Imgproc.minAreaRect(matOfPoint2f)
                
                val width = rotatedRect.size.width
                val height = rotatedRect.size.height
                val rawDiameterPx = kotlin.math.min(width, height)
                var rawLengthPx = kotlin.math.max(width, height)
                
                var purpleRootPosStr = "未检测到"
                val purpleRootPoint = detectPurpleRoot(hsv, maxContour, rgba.size())
                
                if (purpleRootPoint != null) {
                    purpleRootPosStr = "(${purpleRootPoint.x.toInt()}, ${purpleRootPoint.y.toInt()})"
                    val effectiveLength = purpleRootPoint.y - rect.y
                    if (effectiveLength > 0) {
                        rawLengthPx = effectiveLength
                    }
                }

                var diameterMm: Double = 0.0
                val lengthMm: Double
                val diameterLinePoints = mutableListOf<PointF>()
                
                if (pixelsPerMm > 0) {
                    val offsetMm = 20.0
                    val offsetPx = offsetMm * pixelsPerMm
                    
                    val angle = rotatedRect.angle
                    val center = rotatedRect.center
                    
                    val contourPoints = maxContour.toArray()
                    val rotatedContourPoints = contourPoints.map { 
                        rotatePoint(it, center, -angle)
                    }
                    
                    val rotatedYs = rotatedContourPoints.map { it.y }
                    val maxY = rotatedYs.maxOrNull() ?: 0.0
                    
                    var tailY_rotated = maxY
                    if (purpleRootPoint != null) {
                        val rotatedRoot = rotatePoint(purpleRootPoint, center, -angle)
                        tailY_rotated = rotatedRoot.y
                    } else {
                        tailY_rotated = maxY
                    }
                    
                    val targetY = tailY_rotated - offsetPx
                    val range = 2.0
                    val pointsAtSlice = rotatedContourPoints.filter { abs(it.y - targetY) < range }
                    
                    if (pointsAtSlice.isNotEmpty()) {
                        val minX = pointsAtSlice.minOf { it.x }
                        val maxX = pointsAtSlice.maxOf { it.x }
                        val diameterPx = maxX - minX
                        diameterMm = diameterPx / pixelsPerMm
                        
                        val p1_original = rotatePoint(Point(minX, targetY), center, angle)
                        val p2_original = rotatePoint(Point(maxX, targetY), center, angle)
                        
                        diameterLinePoints.add(PointF(p1_original.x.toFloat(), p1_original.y.toFloat()))
                        diameterLinePoints.add(PointF(p2_original.x.toFloat(), p2_original.y.toFloat()))
                    } else {
                        diameterMm = rawDiameterPx / pixelsPerMm
                    }
                    lengthMm = rawLengthPx / pixelsPerMm
                } else {
                    diameterMm = 0.0
                    lengthMm = 0.0
                }
                
                val grade = when {
                    diameterMm > 15.0 -> "A" // 符合新标准
                    diameterMm > 12.0 -> "B"
                    diameterMm > 10.0 -> "C"
                    diameterMm > 8.0 -> "D"
                    diameterMm > 5.0 -> "E"
                    else -> "F"
                }
                
                return AlgorithmResult(
                    success = true,
                    grade = grade,
                    diameter = "%.2f".format(diameterMm).toDouble(),
                    length = "%.2f".format(lengthMm).toDouble(),
                    purpleRootPosition = purpleRootPosStr,
                    asparagusRect = androidRect,
                    asparagusContour = maxContour.toArray().map { PointF(it.x.toFloat(), it.y.toFloat()) },
                    tailPoint = purpleRootPoint?.let { PointF(it.x.toFloat(), it.y.toFloat()) },
                    diameterLine = diameterLinePoints,
                    arucoCorners = markerCornersList,
                    arucoIds = markerIds
                )
            } else {
                return AlgorithmResult(
                    success = false, 
                    error = "未检测到芦笋",
                    arucoCorners = markerCornersList,
                    arucoIds = markerIds
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "算法异常: ${e.message}")
            return AlgorithmResult(false, error = "算法异常: ${e.message}")
        } finally {
            rgba.release()
            hsv.release()
            mask.release()
            purpleMask.release()
            roiMask.release()
            hierarchy.release()
            ids.release()
            corners.forEach { it.release() }
            rejected.forEach { it.release() }
        }
    }
    
    private fun detectPurpleRoot(hsv: Mat, contour: MatOfPoint, size: Size): Point? {
        val purpleMask = Mat()
        val roiMask = Mat.zeros(size, CvType.CV_8UC1)
        val purpleInContour = Mat()
        val nonZeroPoints = MatOfPoint()
        
        try {
            val lowerPurple = Scalar(120.0, 50.0, 50.0)
            val upperPurple = Scalar(160.0, 255.0, 255.0)
            Core.inRange(hsv, lowerPurple, upperPurple, purpleMask)
            
            val contours = listOf(contour)
            Imgproc.drawContours(roiMask, contours, -1, Scalar(255.0), -1)
            Core.bitwise_and(purpleMask, roiMask, purpleInContour)
            Core.findNonZero(purpleInContour, nonZeroPoints)
            
            if (nonZeroPoints.total() > 0) {
                val points = nonZeroPoints.toArray()
                var sumX = 0.0
                var sumY = 0.0
                for (p in points) {
                    sumX += p.x
                    sumY += p.y
                }
                return Point(sumX / points.size, sumY / points.size)
            }
            return null
        } catch (e: Exception) {
            return null
        } finally {
            purpleMask.release()
            roiMask.release()
            purpleInContour.release()
            nonZeroPoints.release()
        }
    }
    
    private fun dist(p1: PointF, p2: PointF): Double {
        return sqrt((p1.x - p2.x).toDouble() * (p1.x - p2.x) + (p1.y - p2.y) * (p1.y - p2.y))
    }
    
    private fun rotatePoint(point: Point, center: Point, angleDeg: Double): Point {
        val angleRad = Math.toRadians(angleDeg)
        val cosA = Math.cos(angleRad)
        val sinA = Math.sin(angleRad)
        val dx = point.x - center.x
        val dy = point.y - center.y
        return Point(center.x + (dx * cosA - dy * sinA), center.y + (dx * sinA + dy * cosA))
    }
}