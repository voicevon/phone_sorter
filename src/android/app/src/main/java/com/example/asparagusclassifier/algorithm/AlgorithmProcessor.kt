package com.example.asparagusclassifier.algorithm

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.calib3d.Calib3d
import android.graphics.PointF
import com.example.asparagusclassifier.util.useMatScope

    fun processImage(bitmap: Bitmap, calibration: CalibrationData?, viewMode: Int = 2): AlgorithmResult {
        val startTime = System.currentTimeMillis()
        Log.i(TAG, ">>> 开始执行算法管道 (3D 位姿架构) <<<")
        
        return useMatScope { scope ->
            val rgbaUndistorted = scope.createMat()
            val context = preprocess(bitmap, calibration, scope, rgbaUndistorted)
            val workW = context.workW
            val workH = context.workH
 
            try {
                // --- Step 2: 在物理画布上检测 ArUco ---
                val arucoResult = arucoEngine.detectBoardMarkers(rgbaUndistorted)
                
                val arucoCorners = arucoResult.markerMap.values.toList()
                val arucoIds = arucoResult.markerMap.keys.toList()
 
                if (!arucoResult.success) {
                    Log.e(TAG, "[Step 2] ArUco 检测失败: ${arucoResult.error}")
                    val duration = System.currentTimeMillis() - startTime
                    return@useMatScope AlgorithmResult(false, 
                        error = arucoResult.error, 
                        executionTimeMs = duration,
                        arucoCorners = arucoCorners, 
                        arucoIds = arucoIds,
                        canvas2Bitmap = matToBitmap(rgbaUndistorted),
                        processedBitmap = if (viewMode == 1) bitmap else matToBitmap(rgbaUndistorted),
                        viewMode = viewMode
                    )
                }
                Log.i(TAG, "[Step 2] ArUco 检测成功")
 
                // --- Step 3: 位姿解算 ---
                val allImagePoints = collectArucoPoints(arucoResult)
                val poseInfo = if (allImagePoints.total().toInt() >= 4 && calibration != null && calibration.isValid()) {
                    PoseEstimator.estimateCameraPose(allImagePoints, calibration, workW, workH)
                } else null
 
                if (poseInfo == null) {
                    PoseEstimator.reset()
                    return@useMatScope AlgorithmResult(false, error = "位姿解算失败")
                }
                
                // --- Step 4: 芦笋视觉分析 (直接在去畸变图上分析) ---
                val visionResult = AsparagusVisionCore.analyze(rgbaUndistorted, poseInfo)
                
                if (!visionResult.success) {
                    Log.e(TAG, "[Step 4] 芦笋视觉分析失败: ${visionResult.error}")
                    val duration = System.currentTimeMillis() - startTime
                    return@useMatScope AlgorithmResult(false, 
                        error = visionResult.error, 
                        executionTimeMs = duration,
                        arucoCorners = arucoCorners, 
                        arucoIds = arucoIds,
                        canvas2Bitmap = matToBitmap(rgbaUndistorted),
                        processedBitmap = matToBitmap(rgbaUndistorted),
                        viewMode = viewMode
                    )
                }
                Log.i(TAG, "[Step 4] 芦笋视觉分析成功: 直径=%.1fmm, 长度=%.1fmm".format(visionResult.diameterMm, visionResult.lengthMm))
                
                // --- Step 5: 结果构造 ---
                val duration = System.currentTimeMillis() - startTime
                val c1Bmp = bitmap
                val c2Bmp = matToBitmap(rgbaUndistorted)
                
                // 根据当前选择的视图确定返回的 processedBitmap
                val displayBitmap = when(viewMode) {
                    1 -> c1Bmp
                    else -> c2Bmp
                }
 
                Log.i(TAG, "<<< 管道执行完毕，总耗时: ${duration}ms >>>")
 
                return@useMatScope AlgorithmResult(
                    success = true,
                    grade = visionResult.grade,
                    diameter = visionResult.diameterMm,
                    rawDiameter = visionResult.rawDiameterMm,
                    length = visionResult.lengthMm,
                    executionTimeMs = duration,
                    straightnessOverall = visionResult.straightnessOverall,
                    straightnessHead = visionResult.straightnessHead,
                    straightnessTail = visionResult.straightnessTail,
                    baselineOverall = visionResult.baselineOverall,
                    baselineHead = visionResult.baselineHead,
                    baselineTail = visionResult.baselineTail,
                    
                    // 生成诊断条图 (从去畸变图截取)
                    diagStrips = generateDiagnosticStrips(rgbaUndistorted, visionResult),
                    
                    asparagusContour = visionResult.contourPoints,
                    axisPath = visionResult.axisPoints,
                    tailPoint = visionResult.purpleRootPoint,
                    diameterLine = visionResult.diameterLines,
                    asparagusRect = calculateBoundingRect(visionResult.contourPoints),
                    
                    arucoCorners = arucoCorners,
                    arucoIds = arucoIds,
                    
                    canvas1Bitmap = c1Bmp,
                    canvas2Bitmap = c2Bmp,
                    processedBitmap = displayBitmap,
                    viewMode = viewMode,
                    
                    // 3D 位姿数据
                    poseDistanceMm = poseInfo.distanceMm,
                    tiltAngle = poseInfo.tiltAngleDeg,
                    cameraPosWorld = poseInfo.cameraPosWorld,
                    axis3DPoints = PoseEstimator.projectAxes(poseInfo),
                    markerAxes = arucoResult.markerMap.mapValues { (id, corners) ->
                        PoseEstimator.estimateSingleMarkerPose(id, corners, calibration, workW, workH)
                    }.filterValues { it != null } as Map<Int, List<android.graphics.PointF>>,
                    
                    // 芦笋头尾 3D 坐标
                    headPosWorld = visionResult.axisPoints.firstOrNull()?.let {
                        val p3d = PoseEstimator.mapImageToWorld3D(it, 8.0, poseInfo)
                        doubleArrayOf(p3d.x, p3d.y, p3d.z)
                    },
                    tailPosWorld = visionResult.axisPoints.lastOrNull()?.let {
                        val p3d = PoseEstimator.mapImageToWorld3D(it, 8.0, poseInfo)
                        doubleArrayOf(p3d.x, p3d.y, p3d.z)
                    }
                )
 
            } catch (e: Exception) {
                Log.e(TAG, "算法异常: ${e.message}")
                return@useMatScope AlgorithmResult(false, error = e.message)
            }
        }
    }
    
    fun processRealtimePose(bitmap: Bitmap, calibration: CalibrationData?): AlgorithmResult {
        return useMatScope { scope ->
            val rgbaUndistorted = scope.createMat()
            val context = preprocess(bitmap, calibration, scope, rgbaUndistorted)
            
            try {
                // 1. 检测标记
                val arucoResult = arucoEngine.detectBoardMarkers(rgbaUndistorted)
                if (!arucoResult.success) {
                    PoseEstimator.reset()
                    return@useMatScope AlgorithmResult(false, error = "Aruco Lost")
                }
                
                // 2. 解算位姿
                val allPoints = collectArucoPoints(arucoResult)
                val poseInfo = if (allPoints.total().toInt() >= 4 && calibration != null && calibration.isValid()) {
                    PoseEstimator.estimateCameraPose(allPoints, calibration, context.workW, context.workH)
                } else null
                
                val c2Bmp = matToBitmap(rgbaUndistorted)
                
                return@useMatScope AlgorithmResult(
                    success = true,
                    arucoCorners = arucoResult.markerMap.values.toList(),
                    arucoIds = arucoResult.markerMap.keys.toList(),
                    poseDistanceMm = poseInfo?.distanceMm ?: 0.0,
                    tiltAngle = poseInfo?.tiltAngleDeg ?: 0.0,
                    cameraPosWorld = poseInfo?.cameraPosWorld,
                    axis3DPoints = poseInfo?.let { PoseEstimator.projectAxes(it) },
                    markerAxes = arucoResult.markerMap.mapValues { (id, corners) ->
                        PoseEstimator.estimateSingleMarkerPose(id, corners, calibration, context.workW, context.workH)
                    }.filterValues { it != null } as Map<Int, List<android.graphics.PointF>>,
                    canvas2Bitmap = c2Bmp,
                    processedBitmap = c2Bmp,
                    viewMode = 2
                )
            } catch (e: Exception) {
                Log.e(TAG, "processRealtimePose Error: ${e.message}", e)
                return@useMatScope AlgorithmResult(false, error = e.message)
            }
        }
    }

    private data class PreprocessContext(val workW: Int, val workH: Int, val scale: Double)

    private fun preprocess(bitmap: Bitmap, calibration: CalibrationData?, scope: com.example.asparagusclassifier.util.MatScope, dstUndistorted: Mat): PreprocessContext {
        val origW = bitmap.width
        val origH = bitmap.height
        val scaleFactor = if (maxOf(origW, origH) > AlgorithmConfig.SCAN_MAX_SIDE) {
            AlgorithmConfig.SCAN_MAX_SIDE.toDouble() / maxOf(origW, origH).toDouble()
        } else 1.0

        val workW = (origW * scaleFactor).toInt()
        val workH = (origH * scaleFactor).toInt()
        
        val workBitmap = if (scaleFactor < 1.0) {
            Bitmap.createScaledBitmap(bitmap, workW, workH, true)
        } else bitmap

        val rgba = scope.createMat()
        Utils.bitmapToMat(workBitmap, rgba)
        
        if (calibration != null && calibration.isValid()) {
            undistortImage(rgba, dstUndistorted, calibration, workW, workH)
        } else {
            rgba.copyTo(dstUndistorted)
        }
        
        return PreprocessContext(workW, workH, scaleFactor)
    }

    private fun collectArucoPoints(result: ArucoEngine.DetectionResult): MatOfPoint2f {
        val allImagePoints = mutableListOf<Point>()
        // 审计修正：严格按照 16 点模型的 CCW 顺序收集 (BL, BR, TR, TL)
        val targetIds = listOf(AlgorithmConfig.ID_BL, AlgorithmConfig.ID_BR, AlgorithmConfig.ID_TR, AlgorithmConfig.ID_TL)
        for (id in targetIds) {
            result.markerMap[id]?.let { corners ->
                // 每个 Marker 内部也必须是 CCW 映射 (ArUco 默认 0:TL, 1:TR, 2:BR, 3:BL 为 CW)
                // 对应我们的 3D 点 P0,P1,P2,P3 (BL,BR,TR,TL)
                // 所以映射顺序为 ArUco 角点 3, 2, 1, 0
                allImagePoints.add(Point(corners[3].x.toDouble(), corners[3].y.toDouble()))
                allImagePoints.add(Point(corners[2].x.toDouble(), corners[2].y.toDouble()))
                allImagePoints.add(Point(corners[1].x.toDouble(), corners[1].y.toDouble()))
                allImagePoints.add(Point(corners[0].x.toDouble(), corners[0].y.toDouble()))
            }
        }
        return MatOfPoint2f(*allImagePoints.toTypedArray())
    }

    /**
     * 根据标定数据和当前分辨率构建相机内参矩阵
     * (已迁移至 PoseEstimator，此处仅为向后兼容保留或供内部 undistort 使用)
     */
    private fun constructCameraMatrix(calibration: CalibrationData, currentW: Int, currentH: Int): Mat {
        return PoseEstimator.constructCameraMatrix(calibration, currentW, currentH)
    }

    private fun undistortImage(src: Mat, dst: Mat, calibration: CalibrationData, currentW: Int, currentH: Int) {
        val camMatrix = constructCameraMatrix(calibration, currentW, currentH)
        val distCoeffs = MatOfDouble(*DoubleArray(calibration.distortion.size) { calibration.distortion[it].toDouble() })
        
        Calib3d.undistort(src, dst, camMatrix, distCoeffs)
        
        camMatrix.release()
        distCoeffs.release()
    }

    // 移除 PoseInfo 数据类定义，使用 PoseEstimator.PoseInfo

    private fun matToBitmap(mat: Mat): Bitmap {
        val bmp = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(mat, bmp)
        return bmp
    }

    private fun calculateBoundingRect(points: List<PointF>): Rect? {
        if (points.isEmpty()) return null
        val left = points.minOf { it.x }.toInt()
        val top = points.minOf { it.y }.toInt()
        val right = points.maxOf { it.x }.toInt()
        val bottom = points.maxOf { it.y }.toInt()
        return Rect(left, top, right, bottom)
    }

    private fun centerOf(corners: Array<PointF>): PointF {
        var x = 0f; var y = 0f
        for (p in corners) { x += p.x; y += p.y }
        return PointF(x / 4f, y / 4f)
    }
    /**
     * 生成头、中、尾三段诊断对比图
     */
    private fun generateDiagnosticStrips(undistortedMat: Mat, vision: AsparagusVisionCore.AnalysisResult): List<Bitmap> {
        val strips = mutableListOf<Bitmap>()
        if (vision.axisPoints.size < 8) return emptyList()
 
        val segmentSize = vision.axisPoints.size / 4
        val tasks = listOf(
            Triple(vision.axisPoints.take(segmentSize), vision.baselineHead, "头部"),
            Triple(vision.axisPoints, vision.baselineOverall, "整体"),
            Triple(vision.axisPoints.takeLast(segmentSize), vision.baselineTail, "尾部")
        )
 
        tasks.forEach { (axis, baseline, _) ->
            generateDiagnosticStrip(undistortedMat, axis, baseline)?.let { strips.add(it) }
        }
        return strips
    }

    private fun generateDiagnosticStrip(sourceMat: Mat, axis: List<PointF>, baseline: List<PointF>?): Bitmap? {
        if (baseline == null || baseline.size < 2 || axis.isEmpty()) return null
        val p1 = baseline[0]; val p2 = baseline[1]
        val dx = (p2.x - p1.x).toDouble(); val dy = (p2.y - p1.y).toDouble()
        val lineLen = Math.sqrt(dx * dx + dy * dy); val angle = Math.atan2(dy, dx) * 180.0 / Math.PI
        
        val stripW = 240; val stripH = (lineLen + 100).toInt()
        val targetMat = Mat(stripH, stripW, sourceMat.type(), Scalar(30.0, 30.0, 30.0, 255.0))
        val cx = (p1.x + p2.x) / 2.0; val cy = (p1.y + p2.y) / 2.0
        val rotationAngle = -90.0 - angle
        val rotMat = Imgproc.getRotationMatrix2D(Point(cx, cy), rotationAngle, 1.0)
        
        val m = DoubleArray(6)
        rotMat.get(0, 0, m)
        m[2] += (stripW / 2.0 - (m[0] * cx + m[1] * cy + m[2]))
        m[5] += (stripH / 2.0 - (m[3] * cx + m[4] * cy + m[5]))
        rotMat.put(0, 0, *m)
        
        Imgproc.warpAffine(sourceMat, targetMat, rotMat, targetMat.size(), Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT, Scalar(0.0, 0.0, 0.0, 255.0))
        
        val b1 = Point(m[0] * p1.x + m[1] * p1.y + m[2], m[3] * p1.x + m[4] * p1.y + m[5])
        val b2 = Point(m[0] * p2.x + m[1] * p2.y + m[2], m[3] * p2.x + m[4] * p2.y + m[5])
        Imgproc.line(targetMat, b1, b2, Scalar(255.0, 50.0, 50.0, 255.0), 3)
        
        var prevP: Point? = null
        for (pt in axis) {
            val currP = Point(m[0] * pt.x + m[1] * pt.y + m[2], m[3] * pt.x + m[4] * pt.y + m[5])
            if (prevP != null) Imgproc.line(targetMat, prevP, currP, Scalar(0.0, 100.0, 255.0, 255.0), 4)
            prevP = currP
        }
        
        val bmp = Bitmap.createBitmap(targetMat.cols(), targetMat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(targetMat, bmp)
        targetMat.release(); rotMat.release()
        return bmp
    }
}