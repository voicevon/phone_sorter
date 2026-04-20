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
import org.opencv.calib3d.Calib3d
import android.graphics.PointF

data class AlgorithmResult(
    val success: Boolean,
    val grade: String = "F",
    val diameter: Double = 0.0,
    val rawDiameter: Double = 0.0,
    val length: Double = 0.0,
    val purpleRootPosition: String = "未检测到",
    val asparagusRect: Rect? = null,
    val asparagusContour: List<PointF>? = null, // 芦笋轮廓点
    val tailPoint: PointF? = null, // 芦笋头尾标记（紫根位置）
    val axisPath: List<PointF>? = null, // 曲线轴线路径
    val diameterLine: List<List<PointF>>? = null, // 多条直径测量线 (每条线2个点)
    val arucoCorners: List<Array<PointF>>? = null, // 每个标记的4个角点
    val arucoIds: List<Int>? = null,
    val error: String? = null
)

object AlgorithmProcessor {
    private const val TAG = "AsparagusClassifier"
    // 假设 ArUco 标记的物理尺寸为 25mm
    private const val ARUCO_SIZE_MM = 25.0 

    // 相机校准数据 (Phase 2)
    private var intrinsicCalibration: FloatArray? = null
    private var lensDistortion: FloatArray? = null

    /**
     * 由 CameraManager 调用，传入当前镜头的物理参数
     */
    fun setCalibrationData(intrinsic: FloatArray, distortion: FloatArray) {
        this.intrinsicCalibration = intrinsic
        this.lensDistortion = distortion
        Log.i(TAG, "已更新相机校准参数")
    }
    
    fun processImage(bitmap: Bitmap): AlgorithmResult {
        return processAsparagus(bitmap)
    }

    private fun processAsparagus(bitmap: Bitmap): AlgorithmResult {
        Log.i(TAG, "开始执行算法: 芦笋曲线测量版")

        // --- 尾列缩放：长边超过 800px 就按比例缩小，大幅降低计算负荷---
        val MAX_SIDE = 800
        val origW = bitmap.width
        val origH = bitmap.height
        val scaleFactor = if (maxOf(origW, origH) > MAX_SIDE) {
            MAX_SIDE.toDouble() / maxOf(origW, origH).toDouble()
        } else 1.0
        val workBitmap = if (scaleFactor < 1.0) {
            val nw = (origW * scaleFactor).toInt()
            val nh = (origH * scaleFactor).toInt()
            Bitmap.createScaledBitmap(bitmap, nw, nh, true)
        } else bitmap
        Log.i(TAG, "缩放系数: ${".2f".format(scaleFactor)}, 工作分辨率: ${workBitmap.width}x${workBitmap.height}")
        val rgba = Mat()
        val hsv = Mat()
        val mask = Mat()
        val purpleMask = Mat()
        val roiMask = Mat()
        val hierarchy = Mat()
        val ids = Mat()
        val corners = mutableListOf<Mat>()
        val rejected = mutableListOf<Mat>()
        
        // ROI 相關 Mat
        var rgbaRoi: Mat? = null
        val hsvRoi = Mat()
        val maskRoi = Mat()

        try {
            Utils.bitmapToMat(workBitmap, rgba)
            
            // --- 0. 镜头去畸变 (Phase 2) ---
            intrinsicCalibration?.let { intrinsic ->
                lensDistortion?.let { distortion ->
                    val camMatrix = Mat(3, 3, CvType.CV_32F)
                    // intrinsic: [fx, fy, cx, cy, s]
                    camMatrix.put(0, 0, intrinsic[0].toDouble()) // fx
                    camMatrix.put(0, 1, intrinsic[4].toDouble()) // s
                    camMatrix.put(0, 2, intrinsic[2].toDouble()) // cx
                    camMatrix.put(1, 1, intrinsic[1].toDouble()) // fy
                    camMatrix.put(1, 2, intrinsic[3].toDouble()) // cy
                    camMatrix.put(2, 2, 1.0)
                    
                    val distCoeffs = MatOfDouble(*DoubleArray(distortion.size) { distortion[it].toDouble() })
                    val undistorted = Mat()
                    Calib3d.undistort(rgba, undistorted, camMatrix, distCoeffs)
                    undistorted.copyTo(rgba)
                    undistorted.release()
                    camMatrix.release()
                    distCoeffs.release()
                    Log.d(TAG, "已应用镜头去畸变校正")
                }
            }

            // --- 1. ArUco 检测与标定 ---
            val dictionary = Objdetect.getPredefinedDictionary(Objdetect.DICT_4X4_50)
            val detector = ArucoDetector(dictionary)
            detector.detectMarkers(rgba, corners, ids, rejected)
            
            val markerCornersList = mutableListOf<Array<PointF>>()
            val markerIds = mutableListOf<Int>()
            var primaryPixelsPerMm = 0.0
            
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
                    
                    // ArUco 尺寸已在缩放后的坐标系下，像素/mm 系数不受影响
                    if (primaryPixelsPerMm == 0.0) {
                        primaryPixelsPerMm = avgPixelSize / ARUCO_SIZE_MM
                    }
                }
            }
            
            // --- 2. 透视变换 / 标准化画布 (Phase 1) ---
            // 目标：将图像归一化为 1mm = 10px 的 1000px*800px 标准空间
            val MM_TO_PX = 10.0
            val targetWidth = 1000
            val targetHeight = 800
            val warpedRgba = Mat(targetHeight, targetWidth, rgba.type())
            var hasWarped = false
            var currentPixelsPerMm = MM_TO_PX // 默认在 Warped 空间下 1mm = 10px

            var leftMarker: Array<PointF>? = null
            var rightMarker: Array<PointF>? = null

            if (markerCornersList.size >= 2) {
                // 找到最左和最右的两个 Marker 建立基准
                val sortedMarkers = markerCornersList.indices.sortedBy { markerCornersList[it][0].x }
                leftMarker = markerCornersList[sortedMarkers.first()]
                rightMarker = markerCornersList[sortedMarkers.last()]

                // 物理间距定义 (假设 Marker 中心间距为 100mm，基于您的布局可调整)
                // 这里为了通用性，先基于 Marker 自身中心点和角度拉平
                val srcPoints = MatOfPoint2f(
                    org.opencv.core.Point(leftMarker[0].x.toDouble(), leftMarker[0].y.toDouble()),
                    org.opencv.core.Point(rightMarker[1].x.toDouble(), rightMarker[1].y.toDouble()),
                    org.opencv.core.Point(rightMarker[2].x.toDouble(), rightMarker[2].y.toDouble()),
                    org.opencv.core.Point(leftMarker[3].x.toDouble(), leftMarker[3].y.toDouble())
                )
                
                // 映射到标准坐标：左侧 Marker 左上(200, 300) 到 右侧 Marker 右下(800, 500)
                // 这将提供约 60mm 的主测量区，分辨率 10px/mm
                val dstPoints = MatOfPoint2f(
                    org.opencv.core.Point(200.0, 300.0),
                    org.opencv.core.Point(800.0, 300.0),
                    org.opencv.core.Point(800.0, 500.0),
                    org.opencv.core.Point(200.0, 500.0)
                )

                val transMat = Imgproc.getPerspectiveTransform(srcPoints, dstPoints)
                Imgproc.warpPerspective(rgba, warpedRgba, transMat, warpedRgba.size())
                hasWarped = true
                Log.i(TAG, "已应用透视校正，进入标准化空间")
                
                transMat.release()
                srcPoints.release()
                dstPoints.release()
            } else {
                    // 如果 Marker 不足，回退到原始缩放逻辑 (但会存在机型偏差)
                Log.w(TAG, "Marker 不足，回退到传统比例模式")
                rgba.copyTo(warpedRgba)
                currentPixelsPerMm = primaryPixelsPerMm
            }

            // 无论是否 Waped，后续逻辑都在 warpedRgba 上进行
            rgbaRoi = warpedRgba 
            Imgproc.cvtColor(rgbaRoi, hsvRoi, Imgproc.COLOR_RGB2HSV)
            
            // 绿色分割掩膜 (在 ROI 内进行)
            val lowerGreen = Scalar(25.0, 30.0, 30.0)
            val upperGreen = Scalar(95.0, 255.0, 255.0)
            Core.inRange(hsvRoi, lowerGreen, upperGreen, maskRoi)
            
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
            Imgproc.morphologyEx(maskRoi, maskRoi, Imgproc.MORPH_OPEN, kernel)
            Imgproc.morphologyEx(maskRoi, maskRoi, Imgproc.MORPH_CLOSE, kernel)
            Imgproc.morphologyEx(maskRoi, maskRoi, Imgproc.MORPH_CLOSE, kernel)
            
            val contours = mutableListOf<MatOfPoint>()
            Imgproc.findContours(maskRoi, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            
            val maxContour = contours.maxByOrNull { Imgproc.contourArea(it) }
            
            // 如果检测到足够大的芦笋轮廓 (在 ROI 坐标系下)
            if (maxContour != null && Imgproc.contourArea(maxContour) > 500) {
                val rectInRoi = Imgproc.boundingRect(maxContour)

                // --- 3. 轴线提取 (仅在 ROI 掩膜内操作，大幅提升速度) ---
                val currentRoiMask = Mat.zeros(rgbaRoi.size(), CvType.CV_8UC1)
                Imgproc.drawContours(currentRoiMask, listOf(maxContour), -1, Scalar(255.0), -1)

                // 紫根检测 (在 ROI HSV 空间进行) —— 必须先于轴线方向判断
                val purpleRootPointInRoi = detectPurpleRootInRoi(hsvRoi, maxContour)

                val axisPointsInRoi = computeCenterlineFromMask(currentRoiMask, maxContour)
                
                // 确保点序列是从根部（粗端）开始，头部（尖端）在末尾：
                // 策略1（优先）：检测到紫根 → 距紫根近的一端是根
                // 策略2（通用）：比较两端宽度 → 宽的一端是根/切割端，窄的一端是尖端
                //   对有紫根和已切割的芦笋均有效
                val rootToHeadAxis = if (axisPointsInRoi.size >= 2) {
                    val rootIsAtEnd = if (purpleRootPointInRoi != null) {
                        // 策略1：距紫根更近的一端 == 根
                        val first = axisPointsInRoi.first()
                        val last  = axisPointsInRoi.last()
                        val dFirst = dist(first, PointF(purpleRootPointInRoi.x.toFloat(), purpleRootPointInRoi.y.toFloat()))
                        val dLast  = dist(last,  PointF(purpleRootPointInRoi.x.toFloat(), purpleRootPointInRoi.y.toFloat()))
                        Log.d(TAG, "头尾判向[紫根]: dFirst=${"%.1f".format(dFirst)} dLast=${"%.1f".format(dLast)}")
                        dLast < dFirst   // last 更近 → 需要反转
                    } else {
                        // 策略2：局部像素密度判向（方向无关，对有/无紫根均有效）
                        // 在轴线两端各取 10% 的点，统计每个点周围半径 R 内的前景像素数
                        // 前景像素多 == 该端更粗 == 根/切割端
                        val sampleCount = maxOf(3, axisPointsInRoi.size / 10)
                        val R = 20 // 采样半径（像素），可根据实际芦笋粗细调整
                        fun localDensity(pt: PointF): Int {
                            var cnt = 0
                            val cx = pt.x.toInt(); val cy = pt.y.toInt()
                            for (dy in -R..R) {
                                val py = cy + dy
                                if (py < 0 || py >= currentRoiMask.rows()) continue
                                for (dx in -R..R) {
                                    if (dx*dx + dy*dy > R*R) continue
                                    val px = cx + dx
                                    if (px < 0 || px >= currentRoiMask.cols()) continue
                                    if (currentRoiMask.get(py, px)[0] > 0.0) cnt++
                                }
                            }
                            return cnt
                        }
                        val firstPts = axisPointsInRoi.take(sampleCount)
                        val lastPts  = axisPointsInRoi.takeLast(sampleCount)
                        val dFirst = firstPts.map { localDensity(it) }.average()
                        val dLast  = lastPts.map  { localDensity(it) }.average()
                        Log.d(TAG, "头尾判向[密度]: dFirst=${"%.1f".format(dFirst)} dLast=${"%.1f".format(dLast)}")
                        dLast > dFirst   // last 更粗 → last 是根/切割端 → 需要反转
                    }
                    if (rootIsAtEnd) axisPointsInRoi.reversed() else axisPointsInRoi
                } else {
                    axisPointsInRoi
                }

                var rawDiameterMm = 0.0
                var correctedDiameterMm = 0.0
                var lengthMm = 0.0
                val allDiameterLinesInRoi = mutableListOf<List<PointF>>()
                
                    if (currentPixelsPerMm > 0 && rootToHeadAxis.isNotEmpty()) {
                    // 1. 计算轴向长度
                    var totalLengthPx = 0.0
                    for (i in 0 until rootToHeadAxis.size - 1) {
                        totalLengthPx += dist(rootToHeadAxis[i], rootToHeadAxis[i+1])
                    }
                    lengthMm = totalLengthPx / currentPixelsPerMm
                    
                    // 2. 多点直径采样 (5mm, 10mm, 15mm 处)
                    val samplingOffsetsMm = listOf(5.0, 10.0, 15.0)
                    val diameterSamples = mutableListOf<Double>()
                    
                    for (offsetMm in samplingOffsetsMm) {
                        val offsetPx = offsetMm * currentPixelsPerMm
                        val (samplePoint, normalVec) = findPointAndNormalAtDistanceStable(rootToHeadAxis, offsetPx)
                        
                        if (samplePoint != null && normalVec != null) {
                            val widthPx = measureWidthAlongNormal(currentRoiMask, samplePoint, normalVec)
                            diameterSamples.add(widthPx / currentPixelsPerMm)
                            
                            allDiameterLinesInRoi.add(listOf(
                                PointF((samplePoint.x - normalVec.x * widthPx/2).toFloat(), (samplePoint.y - normalVec.y * widthPx/2).toFloat()),
                                PointF((samplePoint.x + normalVec.x * widthPx/2).toFloat(), (samplePoint.y + normalVec.y * widthPx/2).toFloat())
                            ))
                        }
                    }
                    
                    if (diameterSamples.isNotEmpty()) {
                        rawDiameterMm = diameterSamples.average()
                        correctedDiameterMm = maxOf(0.0, rawDiameterMm - 1.5)
                    }
                }
                currentRoiMask.release()

                val grade = when {
                    correctedDiameterMm > 15.0 -> "A"
                    correctedDiameterMm > 12.0 -> "B"
                    correctedDiameterMm > 10.0 -> "C"
                    correctedDiameterMm > 8.0 -> "D"
                    correctedDiameterMm > 5.0 -> "E"
                    else -> "F"
                }
                
                // 还原坐标到原始分辨率空间 (Phase 3)
                val invScale = 1.0 / scaleFactor
                val invTransMat = if (hasWarped && leftMarker != null && rightMarker != null) {
                    val m = Imgproc.getPerspectiveTransform(
                        MatOfPoint2f(
                            org.opencv.core.Point(200.0, 300.0),
                            org.opencv.core.Point(800.0, 300.0),
                            org.opencv.core.Point(800.0, 500.0),
                            org.opencv.core.Point(200.0, 500.0)
                        ),
                        MatOfPoint2f(
                            org.opencv.core.Point(leftMarker[0].x.toDouble(), leftMarker[0].y.toDouble()),
                            org.opencv.core.Point(rightMarker[1].x.toDouble(), rightMarker[1].y.toDouble()),
                            org.opencv.core.Point(rightMarker[2].x.toDouble(), rightMarker[2].y.toDouble()),
                            org.opencv.core.Point(leftMarker[3].x.toDouble(), leftMarker[3].y.toDouble())
                        )
                    )
                    m
                } else null

                fun mapToOriginal(p: PointF): PointF {
                    if (invTransMat != null) {
                        val src = MatOfPoint2f(org.opencv.core.Point(p.x.toDouble(), p.y.toDouble()))
                        val dst = MatOfPoint2f()
                        Core.perspectiveTransform(src, dst, invTransMat)
                        val res = dst.toArray()[0]
                        src.release(); dst.release()
                        return PointF((res.x * invScale).toFloat(), (res.y * invScale).toFloat())
                    }
                    // 回退方案 (无 Warp)
                    return PointF((p.x * invScale).toFloat(), (p.y * invScale).toFloat())
                }

                val finalContour = maxContour.toArray().map { p -> mapToOriginal(PointF(p.x.toFloat(), p.y.toFloat())) }
                val finalTailPoint = purpleRootPointInRoi?.let { pt -> mapToOriginal(PointF(pt.x.toFloat(), pt.y.toFloat())) }
                val finalAxisPath = rootToHeadAxis.map { pt -> mapToOriginal(pt) }
                val finalDiameterLines = allDiameterLinesInRoi.map { line -> line.map { pt -> mapToOriginal(pt) } }

                val result = AlgorithmResult(
                    success = true,
                    grade = grade,
                    diameter = correctedDiameterMm,
                    rawDiameter = rawDiameterMm,
                    length = lengthMm,
                    purpleRootPosition = if (finalTailPoint != null) "(${finalTailPoint.x.toInt()}, ${finalTailPoint.y.toInt()})" else "未检测到",
                    asparagusRect = if (finalContour.isNotEmpty()) {
                        val left = finalContour.minOf { it.x }.toInt()
                        val top = finalContour.minOf { it.y }.toInt()
                        val right = finalContour.maxOf { it.x }.toInt()
                        val bottom = finalContour.maxOf { it.y }.toInt()
                        android.graphics.Rect(left, top, right, bottom)
                    } else null,
                    asparagusContour = finalContour,
                    tailPoint = finalTailPoint,
                    axisPath = finalAxisPath,
                    diameterLine = finalDiameterLines,
                    arucoCorners = markerCornersList.map { marker -> 
                        marker.map { pt -> 
                            PointF((pt.x * invScale).toFloat(), (pt.y * invScale).toFloat()) 
                        }.toTypedArray() 
                    },
                    arucoIds = markerIds
                )
                invTransMat?.release()
                return result
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
            rgbaRoi?.release()
            hsvRoi.release()
            maskRoi.release()
            for (m in corners) { m.release() }
            for (m in rejected) { m.release() }
        }
    }

    /**
     * 在 ROI 区域内进行紫根检测
     * @param hsvRoi ROI 区域的 HSV 图像
     * @param contourInRoi ROI 坐标系下的芦笋轮廓
     */
    private fun detectPurpleRootInRoi(hsvRoi: Mat, contourInRoi: MatOfPoint): Point? {
        val purpleMask = Mat()
        val roiMask = Mat.zeros(hsvRoi.size(), CvType.CV_8UC1)
        val purpleInContour = Mat()
        val nonZeroPoints = MatOfPoint()
        try {
            // 紫色/深红色范围
            val lowerPurple = Scalar(110.0, 30.0, 30.0)
            val upperPurple = Scalar(170.0, 255.0, 255.0)
            Core.inRange(hsvRoi, lowerPurple, upperPurple, purpleMask)
            
            // 限制在轮廓内部
            Imgproc.drawContours(roiMask, listOf(contourInRoi), -1, Scalar(255.0), -1)
            Core.bitwise_and(purpleMask, roiMask, purpleInContour)
            
            // 提取所有紫根像素并取均值点
            Core.findNonZero(purpleInContour, nonZeroPoints)
            if (nonZeroPoints.total() > 0) {
                val matPoints = nonZeroPoints.toArray()
                var sumX = 0.0
                var sumY = 0.0
                for (p in matPoints) { sumX += p.x; sumY += p.y }
                return Point(sumX / matPoints.size, sumY / matPoints.size)
            }
        } catch (e: Exception) {
            Log.e("AlgorithmProcessor", "紫根检测异常: ${e.message}")
        } finally {
            purpleMask.release()
            roiMask.release()
            purpleInContour.release()
            nonZeroPoints.release()
        }
        return null
    }

    /**
     * 方向无关轴线提取：
     *   1. 用 minAreaRect 检测芦笋主轴方向
     *   2. 旋转掩膜使主轴对齐 Y 轴
     *   3. 逐行扫描取左右边界中心
     *   4. 逆仳射变换回原始坐标
     * 适用于任意方向，精度与骨架化相当且速度快得多。
     */
    private fun computeCenterlineFromMask(mask: Mat, contour: MatOfPoint): List<PointF> {
        val contour2f = MatOfPoint2f(*contour.toArray())
        val rotRect = Imgproc.minAreaRect(contour2f)
        contour2f.release()

        val w = rotRect.size.width
        val h = rotRect.size.height
        val center = rotRect.center

        // minAreaRect 返回角度范围 [-90, 0)
        // 若 w > h，长轴是水平的，需再加 90° 使其垂直
        val rotAngle = if (w > h) rotRect.angle + 90.0 else rotRect.angle

        // 旋转掩膜，主轴对齐 Y 轴
        val rotMat = Imgproc.getRotationMatrix2D(center, rotAngle, 1.0)
        val rotatedMask = Mat()
        Imgproc.warpAffine(mask, rotatedMask, rotMat, mask.size())

        // 找旋转后非零区域的包围框
        val nonZeroPts = Mat()
        Core.findNonZero(rotatedMask, nonZeroPts)
        if (nonZeroPts.total() == 0L) {
            rotatedMask.release(); nonZeroPts.release(); rotMat.release()
            return emptyList()
        }
        val bbox = Imgproc.boundingRect(nonZeroPts)
        nonZeroPts.release()

        // 逐行扫描，每行取左右边界中心
        val rotPoints = mutableListOf<PointF>()
        for (r in bbox.y until bbox.y + bbox.height) {
            var left = -1; var right = -1
            for (c in bbox.x until bbox.x + bbox.width) {
                if (rotatedMask.get(r, c)[0] > 0.0) {
                    if (left == -1) left = c
                    right = c
                }
            }
            if (left != -1) {
                rotPoints.add(PointF(((left + right) / 2.0).toFloat(), r.toFloat()))
            }
        }
        rotatedMask.release()
        rotMat.release()

        if (rotPoints.isEmpty()) return emptyList()

        // 逆旋转：把旋转空间的点还原到原始坐标系
        val invRotMat = Imgproc.getRotationMatrix2D(center, -rotAngle, 1.0)
        val m00 = invRotMat.get(0, 0)[0]; val m01 = invRotMat.get(0, 1)[0]; val m02 = invRotMat.get(0, 2)[0]
        val m10 = invRotMat.get(1, 0)[0]; val m11 = invRotMat.get(1, 1)[0]; val m12 = invRotMat.get(1, 2)[0]
        invRotMat.release()

        return rotPoints.map { pt ->
            PointF(
                (m00 * pt.x + m01 * pt.y + m02).toFloat(),
                (m10 * pt.x + m11 * pt.y + m12).toFloat()
            )
        }
    }

    /**
     * 稳定的法线计算：通过滑动窗口平均方向来消除单段噪点。
     * @param path 轴心点序列
     * @param distPx 距离起点的像素长度
     * @param windowSize 左右参考点的跨度 (像素点个数)
     */
    private fun findPointAndNormalAtDistanceStable(path: List<PointF>, distPx: Double, windowSize: Int = 12): Pair<Point?, Point?> {
        var acc = 0.0
        for (i in 0 until path.size - 1) {
            val d = dist(path[i], path[i+1])
            if (acc + d >= distPx) {
                // 插值找到采样点
                val r = (distPx - acc) / d
                val p = Point((path[i].x + (path[i+1].x - path[i].x) * r).toDouble(), (path[i].y + (path[i+1].y - path[i].y) * r).toDouble())
                
                // --- 稳定法线计算 ---
                // 取采样点前后 windowSize 范围内的点，计算大尺度下的方向向量
                val startIdx = maxOf(0, i - windowSize)
                val endIdx = minOf(path.size - 1, i + windowSize)
                
                if (endIdx > startIdx) {
                    val dx = (path[endIdx].x - path[startIdx].x).toDouble()
                    val dy = (path[endIdx].y - path[startIdx].y).toDouble()
                    val l = sqrt(dx*dx + dy*dy)
                    if (l > 1e-5) {
                        // 返回垂直向量 (法线)
                        return Pair(p, Point(-dy/l, dx/l))
                    }
                }
                
                // 保底方案：使用当前线段方向
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