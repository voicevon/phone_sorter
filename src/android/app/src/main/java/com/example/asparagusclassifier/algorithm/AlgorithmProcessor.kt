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
    val axisPath: List<PointF>? = null, // 曲线轴线路径
    val diameterLine: List<PointF>? = null, // 直径测量线 (2个点)
    val arucoCorners: List<Array<PointF>>? = null, // 每个标记的4个角点
    val arucoIds: List<Int>? = null,
    val error: String? = null
)

object AlgorithmProcessor {
    private const val TAG = "AsparagusClassifier"
    // 假设 ArUco 标记的物理尺寸为 50mm
    private const val ARUCO_SIZE_MM = 50.0 
    
    fun processImage(bitmap: Bitmap): AlgorithmResult {
        return processAsparagus(bitmap)
    }

    private fun processAsparagus(bitmap: Bitmap): AlgorithmResult {
        Log.i(TAG, "开始执行算法: 芦笋曲线测量版")
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
                    
                    val d1 = dist(p0, p1)
                    val d2 = dist(p1, p2)
                    val d3 = dist(p2, p3)
                    val d4 = dist(p3, p0)
                    val avgPixelSize = (d1 + d2 + d3 + d4) / 4.0
                    
                    if (pixelsPerMm == 0.0) {
                        pixelsPerMm = avgPixelSize / ARUCO_SIZE_MM
                    }
                }
            }
            
            // --- 2. 芦笋分割 ---
            Imgproc.cvtColor(rgba, hsv, Imgproc.COLOR_RGB2HSV)
            val lowerGreen = Scalar(25.0, 30.0, 30.0)
            val upperGreen = Scalar(95.0, 255.0, 255.0)
            Core.inRange(hsv, lowerGreen, upperGreen, mask)
            
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, kernel)
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel)
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel)
            
            val contours = mutableListOf<MatOfPoint>()
            Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            val maxContour = contours.maxByOrNull { Imgproc.contourArea(it) }
            
            if (maxContour != null && Imgproc.contourArea(maxContour) > 800) {
                val rect = Imgproc.boundingRect(maxContour)
                val androidRect = Rect(rect.x, rect.y, rect.x + rect.width, rect.y + rect.height)
                
                // --- 3. 曲线轴线提取 (骨架) ---
                val currentRoiMask = Mat.zeros(rgba.size(), CvType.CV_8UC1)
                Imgproc.drawContours(currentRoiMask, listOf(maxContour), -1, Scalar(255.0), -1)
                
                val skeleton = thinning(currentRoiMask)
                val axisPoints = extractAxisPath(skeleton)
                
                var diameterMm = 0.0
                var lengthMm = 0.0
                val diameterLinePoints = mutableListOf<PointF>()
                val purpleRootPoint = detectPurpleRoot(hsv, maxContour, rgba.size())
                
                if (pixelsPerMm > 0 && axisPoints.isNotEmpty()) {
                    // 计算长度
                    var totalLengthPx = 0.0
                    for (i in 0 until axisPoints.size - 1) {
                        totalLengthPx += dist(axisPoints[i], axisPoints[i+1])
                    }
                    lengthMm = totalLengthPx / pixelsPerMm
                    
                    // 计算直径 (距底部 20mm 采样)
                    val offsetPx = 20.0 * pixelsPerMm
                    val (samplePoint, normalVec) = findPointAndNormalAtDistance(axisPoints, offsetPx)
                    
                    if (samplePoint != null && normalVec != null) {
                        val widthPx = measureWidthAlongNormal(currentRoiMask, samplePoint, normalVec)
                        diameterMm = widthPx / pixelsPerMm
                        
                        diameterLinePoints.add(PointF((samplePoint.x - normalVec.x * widthPx/2).toFloat(), (samplePoint.y - normalVec.y * widthPx/2).toFloat()))
                        diameterLinePoints.add(PointF((samplePoint.x + normalVec.x * widthPx/2).toFloat(), (samplePoint.y + normalVec.y * widthPx/2).toFloat()))
                    }
                }

                val grade = when {
                    diameterMm > 15.0 -> "A"
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
                    purpleRootPosition = if (purpleRootPoint != null) "(${purpleRootPoint.x.toInt()}, ${purpleRootPoint.y.toInt()})" else "未检测到",
                    asparagusRect = androidRect,
                    asparagusContour = maxContour.toArray().map { PointF(it.x.toFloat(), it.y.toFloat()) },
                    tailPoint = purpleRootPoint?.let { PointF(it.x.toFloat(), it.y.toFloat()) },
                    axisPath = axisPoints,
                    diameterLine = diameterLinePoints,
                    arucoCorners = markerCornersList,
                    arucoIds = markerIds
                )
            } else {
                return AlgorithmResult(false, error = "未检测到芦笋", arucoCorners = markerCornersList, arucoIds = markerIds)
            }
        } catch (e: Exception) {
            Log.e(TAG, "算法异常: ${e.message}")
            return AlgorithmResult(false, error = e.message)
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
            val lowerPurple = Scalar(110.0, 30.0, 30.0)
            val upperPurple = Scalar(170.0, 255.0, 255.0)
            Core.inRange(hsv, lowerPurple, upperPurple, purpleMask)
            Imgproc.drawContours(roiMask, listOf(contour), -1, Scalar(255.0), -1)
            Core.bitwise_and(purpleMask, roiMask, purpleInContour)
            Core.findNonZero(purpleInContour, nonZeroPoints)
            if (nonZeroPoints.total() > 0) {
                val matPoints = nonZeroPoints.toArray()
                var sumX = 0.0
                var sumY = 0.0
                for (p in matPoints) { sumX += p.x; sumY += p.y }
                return Point(sumX / matPoints.size, sumY / matPoints.size)
            }
        } catch (e: Exception) {} finally {
            purpleMask.release(); roiMask.release(); purpleInContour.release(); nonZeroPoints.release()
        }
        return null
    }

    private fun thinning(src: Mat): Mat {
        val dest = src.clone()
        var changed = true
        while (changed) {
            changed = false
            for (iter in 0..1) {
                val toDelete = mutableListOf<Point>()
                for (r in 1 until dest.rows() - 1) {
                    for (c in 1 until dest.cols() - 1) {
                        if (dest.get(r, c)[0] == 255.0) {
                            val p2 = if (dest.get(r-1, c)[0] == 255.0) 1 else 0
                            val p3 = if (dest.get(r-1, c+1)[0] == 255.0) 1 else 0
                            val p4 = if (dest.get(r, c+1)[0] == 255.0) 1 else 0
                            val p5 = if (dest.get(r+1, c+1)[0] == 255.0) 1 else 0
                            val p6 = if (dest.get(r+1, c)[0] == 255.0) 1 else 0
                            val p7 = if (dest.get(r+1, c-1)[0] == 255.0) 1 else 0
                            val p8 = if (dest.get(r, c-1)[0] == 255.0) 1 else 0
                            val p9 = if (dest.get(r-1, c-1)[0] == 255.0) 1 else 0
                            val count = p2+p3+p4+p5+p6+p7+p8+p9
                            if (count in 2..6) {
                                val trans = (if(p2==0&&p3==1)1 else 0)+(if(p3==0&&p4==1)1 else 0)+(if(p4==0&&p5==1)1 else 0)+(if(p5==0&&p6==1)1 else 0)+(if(p6==0&&p7==1)1 else 0)+(if(p7==0&&p8==1)1 else 0)+(if(p8==0&&p9==1)1 else 0)+(if(p9==0&&p2==1)1 else 0)
                                if (trans == 1) {
                                    val c1 = if(iter==0) p2*p4*p6 else p2*p4*p8
                                    val c2 = if(iter==0) p4*p6*p8 else p2*p6*p8
                                    if (c1==0 && c2==0) { toDelete.add(Point(c.toDouble(), r.toDouble())); changed=true }
                                }
                            }
                        }
                    }
                }
                for (p in toDelete) dest.put(p.y.toInt(), p.x.toInt(), 0.0)
            }
        }
        return dest
    }

    private fun extractAxisPath(skeleton: Mat): List<PointF> {
        val points = mutableListOf<Point>()
        for (r in 0 until skeleton.rows()) {
            for (c in 0 until skeleton.cols()) {
                if (skeleton.get(r, c)[0] == 255.0) points.add(Point(c.toDouble(), r.toDouble()))
            }
        }
        if (points.isEmpty()) return emptyList()
        var pS = points[0]; var pE = points[0]; var maxD = 0.0
        for (i in points.indices) {
            for (j in i+1 until points.size) {
                val d = dist(points[i], points[j]); if (d > maxD) { maxD = d; pS = points[i]; pE = points[j] }
            }
        }
        if (pS.y < pE.y) { val t = pS; pS = pE; pE = t }
        val sorted = mutableListOf<PointF>()
        val rem = points.toMutableList()
        var curr = pS
        sorted.add(PointF(curr.x.toFloat(), curr.y.toFloat()))
        rem.remove(curr)
        while (rem.isNotEmpty()) {
            val next = rem.minByOrNull { dist(curr, it) } ?: break
            if (dist(curr, next) > 15.0) break
            curr = next
            sorted.add(PointF(curr.x.toFloat(), curr.y.toFloat()))
            rem.remove(curr)
        }
        return sorted
    }

    private fun findPointAndNormalAtDistance(path: List<PointF>, distPx: Double): Pair<Point?, Point?> {
        var acc = 0.0
        for (i in 0 until path.size - 1) {
            val d = dist(path[i], path[i+1])
            if (acc + d >= distPx) {
                val r = (distPx - acc) / d
                val p = Point((path[i].x + (path[i+1].x - path[i].x) * r).toDouble(), (path[i].y + (path[i+1].y - path[i].y) * r).toDouble())
                val dx = (path[i+1].x - path[i].x).toDouble()
                val dy = (path[i+1].y - path[i].y).toDouble()
                val l = sqrt(dx*dx + dy*dy)
                return Pair(p, Point(-dy/l, dx/l))
            }
            acc += d
        }
        return Pair(null, null)
    }

    private fun measureWidthAlongNormal(mask: Mat, c: Point, n: Point): Double {
        var w1 = 0.0; var w2 = 0.0
        for (i in 1..250) {
            val px = c.x + n.x * i; val py = c.y + n.y * i
            if (px<0||px>=mask.cols()||py<0||py>=mask.rows()) break
            if (mask.get(py.toInt(), px.toInt())[0] == 0.0) { w1 = i.toDouble(); break }
        }
        for (i in 1..250) {
            val px = c.x - n.x * i; val py = c.y - n.y * i
            if (px<0||px>=mask.cols()||py<0||py>=mask.rows()) break
            if (mask.get(py.toInt(), px.toInt())[0] == 0.0) { w2 = i.toDouble(); break }
        }
        return w1 + w2
    }

    private fun dist(p1: Point, p2: Point) = sqrt((p1.x-p2.x)*(p1.x-p2.x)+(p1.y-p2.y)*(p1.y-p2.y))
    private fun dist(p1: PointF, p2: PointF) = sqrt((p1.x-p2.x).toDouble()*(p1.x-p2.x)+(p1.y-p2.y)*(p1.y-p2.y))
}