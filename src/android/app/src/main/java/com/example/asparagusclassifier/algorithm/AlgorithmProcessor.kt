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
    // ArUco 标记配置 (基于 aruco_final_10_13_40_42.pdf)
    private const val ID_TL = 10
    private const val ID_TR = 13
    private const val ID_BR = 40
    private const val ID_BL = 42

    // 物理尺寸基准 (单位: mm)
    private const val BOARD_WIDTH_MM = 167.0
    private const val BOARD_HEIGHT_MM = 250.0
    private const val MM_TO_PX = 10.0 // 标准化分辨率: 10px/mm

    // 标准化画布尺寸
    private const val TARGET_WIDTH = 2000
    private const val TARGET_HEIGHT = 3000

    // 标准化坐标 (中心点)
    private val TARGET_TL = org.opencv.core.Point(150.0, 250.0)
    private val TARGET_TR = org.opencv.core.Point(150.0 + BOARD_WIDTH_MM * MM_TO_PX, 250.0)
    private val TARGET_BR = org.opencv.core.Point(150.0 + BOARD_WIDTH_MM * MM_TO_PX, 250.0 + BOARD_HEIGHT_MM * MM_TO_PX)
    private val TARGET_BL = org.opencv.core.Point(150.0, 250.0 + BOARD_HEIGHT_MM * MM_TO_PX)

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

        // --- 提高诊断分辨率：从 800px 提至 1280px ---
        val MAX_SIDE = 1600 // 增加分辨率以提高检测成功率
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
            
            // --- 0. 镜头去畸变 (已暂时禁用以排查 ArUco 识别问题) ---
            Log.e(TAG, "去畸变跳过 (调试模式)")
            /*
            intrinsicCalibration?.let { intrinsic: FloatArray ->
                lensDistortion?.let { distortion: FloatArray ->
                    val camMatrix = Mat(3, 3, CvType.CV_32F)
                    camMatrix.put(0, 0, intrinsic[0].toDouble())
                    camMatrix.put(0, 1, intrinsic[4].toDouble())
                    camMatrix.put(0, 2, intrinsic[2].toDouble())
                    camMatrix.put(1, 1, intrinsic[1].toDouble())
                    camMatrix.put(1, 2, intrinsic[3].toDouble())
                    camMatrix.put(2, 2, 1.0)
                    
                    val distCoeffs = MatOfDouble(*DoubleArray(distortion.size) { distortion[it].toDouble() })
                    val undistorted = Mat()
                    Calib3d.undistort(rgba, undistorted, camMatrix, distCoeffs)
                    undistorted.copyTo(rgba)
                    undistorted.release()
                    camMatrix.release()
                    distCoeffs.release()
                    Log.e(TAG, "已应用镜头去畸变校正")
                }
            } ?: Log.e(TAG, "相机校准参数为空，跳过去畸变")
            */

            // --- 1. ArUco 检测与验证 (优化：尝试多种参数与多阶段检测) ---
            val gray = Mat()
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            
            val dictionary = Objdetect.getPredefinedDictionary(Objdetect.DICT_4X4_50)
            val arucoParams = DetectorParameters()
            
            // 鲁棒性精调
            arucoParams.set_adaptiveThreshWinSizeMin(3)
            arucoParams.set_adaptiveThreshWinSizeMax(23) // 恢复默认，避免窗口过大导致的边缘模糊
            arucoParams.set_adaptiveThreshWinSizeStep(10)
            arucoParams.set_minMarkerPerimeterRate(0.01) // 降低阈值以支持更小的/更远的标记
            
            val detector = ArucoDetector(dictionary, arucoParams)
            detector.detectMarkers(gray, corners, ids, rejected)
            
            Log.e(TAG, "ArUco 检测详情 (阶段1): 尺寸=${gray.cols()}x${gray.rows()}, 已检测ID数=${ids.rows()}, 被拒绝数量=${rejected.size}")
            
            // 如果阶段1识别出的 ID 数量小于4，且有校准数据，尝试去畸变后再测
            if (ids.rows() < 4) {
                intrinsicCalibration?.let { intrinsic ->
                    lensDistortion?.let { distortion ->
                        Log.i(TAG, "阶段1检测不足，尝试应用去畸变后再检测...")
                        val camMatrix = Mat(3, 3, CvType.CV_32F)
                        camMatrix.put(0, 0, intrinsic[0].toDouble())
                        camMatrix.put(0, 1, intrinsic[4].toDouble())
                        camMatrix.put(0, 2, intrinsic[2].toDouble())
                        camMatrix.put(1, 1, intrinsic[1].toDouble())
                        camMatrix.put(1, 2, intrinsic[3].toDouble())
                        camMatrix.put(2, 2, 1.0)
                        val distCoeffs = MatOfDouble(*DoubleArray(distortion.size) { distortion[it].toDouble() })
                        
                        val undistortedRaw = Mat()
                        Calib3d.undistort(rgba, undistortedRaw, camMatrix, distCoeffs)
                        
                        val grayUndist = Mat()
                        Imgproc.cvtColor(undistortedRaw, grayUndist, Imgproc.COLOR_RGBA2GRAY)
                        detector.detectMarkers(grayUndist, corners, ids, rejected)
                        Log.e(TAG, "ArUco 检测详情 (阶段2 - 去畸变后): 已检测ID数=${ids.rows()}")
                        
                        // 使用去畸变后的图覆盖原始图以进行后续分析
                        undistortedRaw.copyTo(rgba)
                        
                        undistortedRaw.release()
                        grayUndist.release()
                        camMatrix.release()
                        distCoeffs.release()
                    }
                }
            } else {
                // 如果原始图已经检测成功，为了后续测量精度，仍然应用去畸变
                intrinsicCalibration?.let { intrinsic ->
                    lensDistortion?.let { distortion ->
                        val camMatrix = Mat(3, 3, CvType.CV_32F)
                        camMatrix.put(0, 0, intrinsic[0].toDouble())
                        camMatrix.put(0, 1, intrinsic[4].toDouble())
                        camMatrix.put(0, 2, intrinsic[2].toDouble())
                        camMatrix.put(1, 1, intrinsic[1].toDouble())
                        camMatrix.put(1, 2, intrinsic[3].toDouble())
                        camMatrix.put(2, 2, 1.0)
                        val distCoeffs = MatOfDouble(*DoubleArray(distortion.size) { distortion[it].toDouble() })
                        val undistortedRaw = Mat()
                        Calib3d.undistort(rgba, undistortedRaw, camMatrix, distCoeffs)
                        undistortedRaw.copyTo(rgba)
                        undistortedRaw.release()
                        camMatrix.release()
                        distCoeffs.release()
                        Log.i(TAG, "检测完成后应用镜头去畸变")
                    }
                }
            }
            gray.release()
            
            val detectedMarkers = mutableMapOf<Int, Array<PointF>>()
            val markerCornersList = mutableListOf<Array<PointF>>()
            val markerIds = mutableListOf<Int>()
            
            if (ids.rows() > 0) {
                val allIds = mutableListOf<Int>()
                for (i in 0 until corners.size) {
                    val cornerMat = corners[i]
                    val id = ids.get(i, 0)[0].toInt()
                    allIds.add(id)
                    
                    // 将坐标从缩放后的工作空间映射回原始 Bitmap 坐标空间
                    val invScale = (1.0 / scaleFactor).toFloat()
                    val p0 = PointF(cornerMat.get(0, 0)[0].toFloat() * invScale, cornerMat.get(0, 0)[1].toFloat() * invScale)
                    val p1 = PointF(cornerMat.get(0, 1)[0].toFloat() * invScale, cornerMat.get(0, 1)[1].toFloat() * invScale)
                    val p2 = PointF(cornerMat.get(0, 2)[0].toFloat() * invScale, cornerMat.get(0, 2)[1].toFloat() * invScale)
                    val p3 = PointF(cornerMat.get(0, 3)[0].toFloat() * invScale, cornerMat.get(0, 3)[1].toFloat() * invScale)
                    
                    val cornersArray = arrayOf(p0, p1, p2, p3)
                    detectedMarkers[id] = cornersArray
                    markerCornersList.add(cornersArray)
                    markerIds.add(id)
                }
                Log.e(TAG, "检出的所有 ID: $allIds")
            }
            // gray 已在上方 line 202 释放
            
            // 验证是否集齐 4 个指定标记
            val requiredIds = listOf(ID_TL, ID_TR, ID_BR, ID_BL)
            val missingIds = requiredIds.filter { !detectedMarkers.containsKey(it) }
            
            if (missingIds.isNotEmpty()) {
                Log.w(TAG, "标记不足或 ID 错误，缺失: $missingIds")
                return AlgorithmResult(false, error = "检测到标定板不完整，请确保 ID 10, 13, 40, 42 全部可见", arucoCorners = markerCornersList, arucoIds = markerIds)
            }
            
            // --- 2. 标定板合法性校验 (Proportionality Test) ---
            val pTL = centerOf(detectedMarkers[ID_TL]!!)
            val pTR = centerOf(detectedMarkers[ID_TR]!!)
            val pBR = centerOf(detectedMarkers[ID_BR]!!)
            val pBL = centerOf(detectedMarkers[ID_BL]!!)
            
            val actualW = dist(pTL, pTR)
            val actualH = dist(pTL, pBL)
            val expectedRatio = BOARD_WIDTH_MM / BOARD_HEIGHT_MM
            val actualRatio = actualW / actualH
            val ratioError = abs(actualRatio - expectedRatio) / expectedRatio
            
            Log.i(TAG, "标定板比例校验: 预期=$expectedRatio, 实际=$actualRatio, 误差=${"%.1f%%".format(ratioError * 100)}")
            
            if (ratioError > 0.15) { // 允许 15% 的透视变形误差 (宽容处理，靠 Homography 修正)
                Log.e(TAG, "标定板比例严重失真")
                return AlgorithmResult(false, error = "非法标定板：比例严重失真", arucoCorners = markerCornersList, arucoIds = markerIds)
            }
            
            // --- 3. 透视变换 / 标准化画布 ---
            val warpedRgba = Mat(TARGET_HEIGHT, TARGET_WIDTH, rgba.type())
            var currentPixelsPerMm = MM_TO_PX 

            val srcPoints = MatOfPoint2f(
                org.opencv.core.Point(pTL.x.toDouble(), pTL.y.toDouble()),
                org.opencv.core.Point(pTR.x.toDouble(), pTR.y.toDouble()),
                org.opencv.core.Point(pBR.x.toDouble(), pBR.y.toDouble()),
                org.opencv.core.Point(pBL.x.toDouble(), pBL.y.toDouble())
            )
            
            val dstPoints = MatOfPoint2f(
                TARGET_TL,
                TARGET_TR,
                TARGET_BR,
                TARGET_BL
            )

            val transMat = Imgproc.getPerspectiveTransform(srcPoints, dstPoints)
            Imgproc.warpPerspective(rgba, warpedRgba, transMat, warpedRgba.size())
            Log.i(TAG, "已应用四点透视校正，进入标准化空间")
            
            transMat.release()
            srcPoints.release()
            dstPoints.release()

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
                val invTransMat = run {
                    val m = Imgproc.getPerspectiveTransform(
                        MatOfPoint2f(
                            TARGET_TL,
                            TARGET_TR,
                            TARGET_BR,
                            TARGET_BL
                        ),
                        MatOfPoint2f(
                            org.opencv.core.Point(pTL.x.toDouble(), pTL.y.toDouble()),
                            org.opencv.core.Point(pTR.x.toDouble(), pTR.y.toDouble()),
                            org.opencv.core.Point(pBR.x.toDouble(), pBR.y.toDouble()),
                            org.opencv.core.Point(pBL.x.toDouble(), pBL.y.toDouble())
                        )
                    )
                    m
                }

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

    private fun centerOf(corners: Array<PointF>): PointF {
        var x = 0f; var y = 0f
        for (p in corners) { x += p.x; y += p.y }
        return PointF(x / 4f, y / 4f)
    }
}