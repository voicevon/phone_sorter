package com.example.asparagusclassifier.algorithm

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.calib3d.Calib3d
import android.graphics.PointF

/**
 * 芦笋分级处理器 (三画布架构重构版)
 * 1. 原始视图 (Original)
 * 2. 物理去畸变视图 (Physical Undistorted) -> UI预览与ArUco检测基准
 * 3. 标准逻辑画布 (Logical Canvas) -> 芦笋测量基准
 */
object AlgorithmProcessor {
    private const val TAG = "AlgorithmProcessor"

    // 子组件
    private val arucoEngine = ArucoEngine()
    
    fun processImage(bitmap: Bitmap, calibration: CalibrationData?, viewMode: Int = 3): AlgorithmResult {
        val startTime = System.currentTimeMillis()
        Log.i(TAG, ">>> 开始执行算法管道 (三画布架构) <<<")
        
        val origW = bitmap.width
        val origH = bitmap.height
        val scaleFactor = if (maxOf(origW, origH) > AlgorithmConfig.SCAN_MAX_SIDE) {
            AlgorithmConfig.SCAN_MAX_SIDE.toDouble() / maxOf(origW, origH).toDouble()
        } else 1.0

        val workW = (origW * scaleFactor).toInt()
        val workH = (origH * scaleFactor).toInt()
        Log.i(TAG, "图像尺寸: ${origW}x${origH} -> 缩放后: ${workW}x${workH} (scale=%.3f)".format(scaleFactor))

        val workBitmap = if (scaleFactor < 1.0) {
            Bitmap.createScaledBitmap(bitmap, workW, workH, true)
        } else bitmap

        val rgba = Mat()
        val rgbaUndistorted = Mat()
        val warpedRgba = Mat(AlgorithmConfig.TARGET_HEIGHT, AlgorithmConfig.TARGET_WIDTH, CvType.CV_8UC4)
        var transMat: Mat? = null
        var mapper: CoordinateMapper? = null
        var poseInfo: PoseInfo? = null

        try {
            Utils.bitmapToMat(workBitmap, rgba)
            
            // --- Step 1: 物理去畸变 (生成 Canvas 2) ---
            if (calibration != null && calibration.isValid()) {
                undistortImage(rgba, rgbaUndistorted, calibration, workBitmap.width, workBitmap.height)
                Log.i(TAG, "[Step 1] 去畸变完成")
            } else {
                rgba.copyTo(rgbaUndistorted)
                Log.w(TAG, "[Step 1] 跳过去畸变 (机型不兼容或校准参数缺失)")
            }
            
            // --- Step 2: 在物理画布上检测 ArUco (Canvas 2 基准) ---
            val arucoResult = arucoEngine.detectBoardMarkers(rgbaUndistorted)
            
            val arucoCorners = arucoResult.markerMap.values.toList()
            val arucoIds = arucoResult.markerMap.keys.toList()

            if (!arucoResult.success) {
                Log.e(TAG, "[Step 2] ArUco 检测失败: ${arucoResult.error}")
                val duration = System.currentTimeMillis() - startTime
                return AlgorithmResult(false, 
                    error = arucoResult.error, 
                    executionTimeMs = duration,
                    arucoCorners = arucoCorners, 
                    arucoIds = arucoIds,
                    canvas1Bitmap = workBitmap,
                    canvas2Bitmap = matToBitmap(rgbaUndistorted),
                    processedBitmap = if (viewMode == 2) matToBitmap(rgbaUndistorted) else workBitmap,
                    viewMode = viewMode
                )
            }
            Log.i(TAG, "[Step 2] ArUco 检测成功")

            // --- Step 3: 透视变换 (Canvas 2 -> Canvas 3) ---
            val pTL = centerOf(arucoResult.markerMap[AlgorithmConfig.ID_TL]!!)
            val pTR = centerOf(arucoResult.markerMap[AlgorithmConfig.ID_TR]!!)
            val pBR = centerOf(arucoResult.markerMap[AlgorithmConfig.ID_BR]!!)
            val pBL = centerOf(arucoResult.markerMap[AlgorithmConfig.ID_BL]!!)

            val srcPoints = MatOfPoint2f(
                Point(pTL.x.toDouble(), pTL.y.toDouble()),
                Point(pTR.x.toDouble(), pTR.y.toDouble()),
                Point(pBR.x.toDouble(), pBR.y.toDouble()),
                Point(pBL.x.toDouble(), pBL.y.toDouble())
            )
            val dstPoints = MatOfPoint2f(
                AlgorithmConfig.TARGET_TL,
                AlgorithmConfig.TARGET_TR,
                AlgorithmConfig.TARGET_BR,
                AlgorithmConfig.TARGET_BL
            )

            transMat = Imgproc.getPerspectiveTransform(srcPoints, dstPoints)
            Imgproc.warpPerspective(rgbaUndistorted, warpedRgba, transMat!!, warpedRgba.size())
            
            mapper = CoordinateMapper(scaleFactor, transMat)
            Log.i(TAG, "[Step 3] 透视变换完成 (Warping)")
            
            // --- Step 3.5: V2 位姿估算 (基于 Canvas 2) ---
            poseInfo = if (calibration != null && calibration.isValid()) {
                estimateCameraPose(srcPoints, calibration, workW, workH)
            } else null
            
            srcPoints.release()
            dstPoints.release()
            // transMat 将在业务逻辑结束后由 finally 块释放

            // --- Step 4: 逻辑画布分析 (Canvas 3 基准) ---
            val visionResult = AsparagusVisionCore.analyze(
                warpedRgba, 
                AlgorithmConfig.MM_TO_PX_IN_CANVAS_3,
                poseInfo
            )
            
            if (!visionResult.success) {
                Log.e(TAG, "[Step 4] 芦笋视觉分析失败: ${visionResult.error}")
                val duration = System.currentTimeMillis() - startTime
                return AlgorithmResult(false, 
                    error = visionResult.error, 
                    executionTimeMs = duration,
                    arucoCorners = arucoCorners, 
                    arucoIds = arucoIds,
                    canvas1Bitmap = workBitmap,
                    canvas2Bitmap = matToBitmap(rgbaUndistorted),
                    canvas3Bitmap = matToBitmap(warpedRgba),
                    processedBitmap = matToBitmap(warpedRgba),
                    viewMode = viewMode
                )
            }
            Log.i(TAG, "[Step 4] 芦笋视觉分析成功: 直径=%.1fmm, 长度=%.1fmm".format(visionResult.diameterMm, visionResult.lengthMm))
            
            // --- Step 4.5: 坐标同步与反向投影 ---
            var finalArucoCorners = arucoCorners
            var finalContour = visionResult.contourPoints
            var finalAxis = visionResult.axisPoints
            var finalTail = visionResult.purpleRootPoint
            var finalDiameterLines = visionResult.diameterLines
            var finalBaselineOverall = visionResult.baselineOverall
            var finalBaselineHead = visionResult.baselineHead
            var finalBaselineTail = visionResult.baselineTail
            
            if (viewMode == 3 && transMat != null) {
                // 将标记点从 C2 投影到 C3 (用于标准分析视图叠加)
                finalArucoCorners = arucoCorners.map { corners ->
                    val srcMat = MatOfPoint2f(*corners.map { org.opencv.core.Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray())
                    val dstMat = MatOfPoint2f()
                    Core.perspectiveTransform(srcMat, dstMat, transMat)
                    val transformed = dstMat.toArray().map { android.graphics.PointF(it.x.toFloat(), it.y.toFloat()) }.toTypedArray()
                    srcMat.release()
                    dstMat.release()
                    transformed
                }
            } else if (viewMode == 2 && mapper != null) {
                // 反透视变换：将分析结果从 C3 投影回 C2 (用于去畸变视图叠加)
                finalContour = visionResult.contourPoints.map { mapper.mapWarpedToWork(it) }
                finalAxis = visionResult.axisPoints.map { mapper.mapWarpedToWork(it) }
                finalTail = visionResult.purpleRootPoint?.let { mapper.mapWarpedToWork(it) }
                finalDiameterLines = visionResult.diameterLines.map { line ->
                    line.map { mapper.mapWarpedToWork(it) }
                }
                finalBaselineOverall = visionResult.baselineOverall?.map { mapper.mapWarpedToWork(it) }
                finalBaselineHead = visionResult.baselineHead?.map { mapper.mapWarpedToWork(it) }
                finalBaselineTail = visionResult.baselineTail?.map { mapper.mapWarpedToWork(it) }
            }

            // --- Step 5: 结果构造 ---
            val duration = System.currentTimeMillis() - startTime
            val c1Bmp = workBitmap
            val c2Bmp = matToBitmap(rgbaUndistorted)
            val c3Bmp = matToBitmap(warpedRgba)
            
            // 根据当前选择的视图确定返回的 processedBitmap
            val displayBitmap = when(viewMode) {
                1 -> c1Bmp
                2 -> c2Bmp
                else -> c3Bmp
            }

            Log.i(TAG, "<<< 管道执行完毕，总耗时: ${duration}ms >>>")

            return AlgorithmResult(
                success = true,
                grade = visionResult.grade,
                diameter = visionResult.diameterMm,
                rawDiameter = visionResult.rawDiameterMm,
                length = visionResult.lengthMm,
                executionTimeMs = duration,
                straightnessOverall = visionResult.straightnessOverall,
                straightnessHead = visionResult.straightnessHead,
                straightnessTail = visionResult.straightnessTail,
                baselineOverall = finalBaselineOverall,
                baselineHead = finalBaselineHead,
                baselineTail = finalBaselineTail,
                
                // 生成诊断条图
                diagStrips = generateDiagnosticStrips(warpedRgba, visionResult),
                
                // 坐标返回：根据视图模式已同步
                asparagusContour = finalContour,
                axisPath = finalAxis,
                tailPoint = finalTail,
                diameterLine = finalDiameterLines,
                asparagusRect = calculateBoundingRect(finalContour),
                
                arucoCorners = finalArucoCorners,
                arucoIds = arucoIds,
                
                canvas1Bitmap = c1Bmp,
                canvas2Bitmap = c2Bmp,
                canvas3Bitmap = c3Bmp,
                processedBitmap = displayBitmap,
                viewMode = viewMode,
                
                // 3D 位姿数据
                poseDistanceMm = poseInfo?.distanceMm ?: 0.0,
                tiltAngle = poseInfo?.tiltAngleDeg ?: 0.0,
                cameraPosWorld = poseInfo?.cameraPosWorld,
                axis3DPoints = poseInfo?.let { projectAxes(it) },
                
                // 芦笋头尾 3D 坐标 (假设 Z=8mm)
                headPosWorld = visionResult.axisPoints.firstOrNull()?.let {
                    doubleArrayOf(
                        (it.x - AlgorithmConfig.PADDING_PX) / AlgorithmConfig.MM_TO_PX_IN_CANVAS_3,
                        (it.y - AlgorithmConfig.PADDING_PX) / AlgorithmConfig.MM_TO_PX_IN_CANVAS_3,
                        8.0
                    )
                },
                tailPosWorld = visionResult.axisPoints.lastOrNull()?.let {
                    doubleArrayOf(
                        (it.x - AlgorithmConfig.PADDING_PX) / AlgorithmConfig.MM_TO_PX_IN_CANVAS_3,
                        (it.y - AlgorithmConfig.PADDING_PX) / AlgorithmConfig.MM_TO_PX_IN_CANVAS_3,
                        8.0
                    )
                }
            )

        } catch (e: Exception) {
            Log.e(TAG, "算法异常: ${e.message}")
            return AlgorithmResult(false, error = e.message)
        } finally {
            rgba.release()
            rgbaUndistorted.release()
            warpedRgba.release()
            transMat?.release()
            mapper?.release()
            poseInfo?.release()
        }
    }
    
    /**
     * 实时位姿处理逻辑：
     * 去除所有芦笋分割和测量，仅保留 ArUco 检测和 solvePnP
     */
    fun processRealtimePose(bitmap: Bitmap, calibration: CalibrationData?): AlgorithmResult {
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

        val rgba = Mat()
        val rgbaUndistorted = Mat()
        var poseInfo: PoseInfo? = null
        
        try {
            Utils.bitmapToMat(workBitmap, rgba)
            
            // 1. 去畸变 (使用缩放后的尺寸)
            if (calibration != null && calibration.isValid()) {
                undistortImage(rgba, rgbaUndistorted, calibration, workW, workH)
            } else {
                rgba.copyTo(rgbaUndistorted)
            }
            
            // 2. 检测标记
            val arucoResult = arucoEngine.detectBoardMarkers(rgbaUndistorted)
            if (!arucoResult.success) {
                Log.d(TAG, "RealtimePose: ArUco Lost")
                return AlgorithmResult(false, error = "Aruco Lost (${workW}x${workH})")
            }
            Log.d(TAG, "RealtimePose: ArUco Detected, ID=${arucoResult.markerMap.keys}")
            
            // 3. 解算位姿
            val pTL = centerOf(arucoResult.markerMap[AlgorithmConfig.ID_TL]!!)
            val pTR = centerOf(arucoResult.markerMap[AlgorithmConfig.ID_TR]!!)
            val pBR = centerOf(arucoResult.markerMap[AlgorithmConfig.ID_BR]!!)
            val pBL = centerOf(arucoResult.markerMap[AlgorithmConfig.ID_BL]!!)

            val imagePoints = MatOfPoint2f(
                Point(pTL.x.toDouble(), pTL.y.toDouble()),
                Point(pTR.x.toDouble(), pTR.y.toDouble()),
                Point(pBR.x.toDouble(), pBR.y.toDouble()),
                Point(pBL.x.toDouble(), pBL.y.toDouble())
            )
            
            poseInfo = if (calibration != null && calibration.isValid()) {
                estimateCameraPose(imagePoints, calibration, workW, workH)
            } else null
            
            imagePoints.release()
            
            return AlgorithmResult(
                success = true,
                arucoCorners = arucoResult.markerMap.values.toList(),
                arucoIds = arucoResult.markerMap.keys.toList(),
                poseDistanceMm = poseInfo?.distanceMm ?: 0.0,
                tiltAngle = poseInfo?.tiltAngleDeg ?: 0.0,
                cameraPosWorld = poseInfo?.cameraPosWorld,
                axis3DPoints = poseInfo?.let { projectAxes(it) },
                viewMode = 2
            )
        } catch (e: Exception) {
            return AlgorithmResult(false, error = e.message)
        } finally {
            rgba.release()
            rgbaUndistorted.release()
            poseInfo?.release()
        }
    }

    /**
     * 投影 3D 坐标轴到 2D 像平面
     * @return [原点, X末端, Y末端, Z末端] 的 2D 坐标列表
     */
    private fun projectAxes(pose: PoseInfo): List<android.graphics.PointF> {
        val axisPoints3D = MatOfPoint3f(
            Point3(0.0, 0.0, 0.0),    // 原点 (标定板中心)
            Point3(50.0, 0.0, 0.0),   // X 轴 (红)
            Point3(0.0, 50.0, 0.0),   // Y 轴 (绿)
            Point3(0.0, 0.0, 50.0)    // Z 轴 (蓝)
        )
        val imagePoints2D = MatOfPoint2f()
        val distCoeffs = MatOfDouble(0.0, 0.0, 0.0, 0.0, 0.0) // solvePnP 输入已去畸变
        
        Calib3d.projectPoints(axisPoints3D, pose.rvec, pose.tvec, pose.cameraMatrix, distCoeffs, imagePoints2D)
        
        val list = mutableListOf<android.graphics.PointF>()
        val data = imagePoints2D.toArray()
        for (p in data) {
            list.add(android.graphics.PointF(p.x.toFloat(), p.y.toFloat()))
        }
        Log.d(TAG, "RealtimePose: Axis points projected: ${list.size}")
        
        axisPoints3D.release()
        imagePoints2D.release()
        distCoeffs.release()
        return list
    }

    /**
     * 估算相机在标定板坐标系中的 3D 位姿
     */
    private fun estimateCameraPose(
        imagePoints: MatOfPoint2f, 
        calibration: CalibrationData,
        currentW: Int, 
        currentH: Int
    ): PoseInfo? {
        val camMatrix = constructCameraMatrix(calibration, currentW, currentH)
        val distCoeffs = MatOfDouble(0.0, 0.0, 0.0, 0.0, 0.0) // 已去畸变，系数为0
        
        // 直接使用预置的居中标定点矩阵
        val objectPoints = AlgorithmConfig.BOARD_OBJECT_POINTS
        
        val rvec = Mat()
        val tvec = Mat()
        
        val success = Calib3d.solvePnP(objectPoints, imagePoints, camMatrix, distCoeffs, rvec, tvec)
        
        if (!success) {
            Log.e(TAG, "solvePnP 失败")
            camMatrix.release()
            objectPoints.release()
            rvec.release()
            tvec.release()
            return null
        }
        
        // 计算距离和角度
        val tvecArray = DoubleArray(3)
        tvec.get(0, 0, tvecArray)
        val distance = Math.sqrt(tvecArray[0] * tvecArray[0] + tvecArray[1] * tvecArray[1] + tvecArray[2] * tvecArray[2])
        
        // 角度计算 (简化版：旋转向量的模表示旋转角度)
        val rvecArray = DoubleArray(3)
        rvec.get(0, 0, rvecArray)
        val rotationRad = Math.sqrt(rvecArray[0] * rvecArray[0] + rvecArray[1] * rvecArray[1] + rvecArray[2] * rvecArray[2])
        val tiltAngleDeg = Math.toDegrees(rotationRad)

        Log.i(TAG, "位姿估算成功: 距离=%.1fmm, 倾角=%.1f°".format(distance, tiltAngleDeg))
        
        // 计算相机在世界坐标系 (标定板) 中的位置: Pw = -R^T * t
        val rMat = Mat()
        Calib3d.Rodrigues(rvec, rMat)
        val rMatT = rMat.t()
        val camWorldMat = Mat()
        Core.gemm(rMatT, tvec, -1.0, Mat(), 0.0, camWorldMat)
        
        val camPosWorld = DoubleArray(3)
        camWorldMat.get(0, 0, camPosWorld)
        
        val info = PoseInfo(rvec.clone(), tvec.clone(), camMatrix.clone(), distance, tiltAngleDeg, camPosWorld)
        
        rMat.release()
        rMatT.release()
        camWorldMat.release()
        
        camMatrix.release()
        rvec.release()
        tvec.release()
        
        return info
    }

    private fun constructCameraMatrix(calibration: CalibrationData, currentW: Int, currentH: Int): Mat {
        val camMatrix = Mat(3, 3, CvType.CV_32F)
        val scaleX = currentW.toDouble() / calibration.sensorWidth.toDouble()
        val scaleY = currentH.toDouble() / calibration.sensorHeight.toDouble()
        
        val fx = calibration.intrinsic[0].toDouble() * scaleX
        val fy = calibration.intrinsic[1].toDouble() * scaleY
        val cx = calibration.intrinsic[2].toDouble() * scaleX
        val cy = calibration.intrinsic[3].toDouble() * scaleY
        val skew = calibration.intrinsic[4].toDouble() * scaleX
        
        camMatrix.put(0, 0, fx)
        camMatrix.put(0, 1, skew)
        camMatrix.put(0, 2, cx)
        camMatrix.put(1, 1, fy)
        camMatrix.put(1, 2, cy)
        camMatrix.put(2, 2, 1.0)
        return camMatrix
    }

    private fun undistortImage(src: Mat, dst: Mat, calibration: CalibrationData, currentW: Int, currentH: Int) {
        val camMatrix = constructCameraMatrix(calibration, currentW, currentH)
        val distCoeffs = MatOfDouble(*DoubleArray(calibration.distortion.size) { calibration.distortion[it].toDouble() })
        
        Log.d(TAG, "执行去畸变...")
        Calib3d.undistort(src, dst, camMatrix, distCoeffs)
        
        camMatrix.release()
        distCoeffs.release()
    }

    /**
     * 封装位姿信息，便于传递和释放
     */
    data class PoseInfo(
        val rvec: Mat,
        val tvec: Mat,
        val cameraMatrix: Mat,
        val distanceMm: Double,
        val tiltAngleDeg: Double,
        val cameraPosWorld: DoubleArray? = null
    ) {
        fun release() {
            rvec.release()
            tvec.release()
            cameraMatrix.release()
        }
    }

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
    private fun generateDiagnosticStrips(canvas3: Mat, vision: AsparagusVisionCore.AnalysisResult): List<Bitmap> {
        val strips = mutableListOf<Bitmap>()
        if (vision.axisPoints.size < 8) return emptyList()

        val segmentSize = vision.axisPoints.size / 4
        val tasks = listOf(
            Triple(vision.axisPoints.take(segmentSize), vision.baselineHead, "头部"),
            Triple(vision.axisPoints, vision.baselineOverall, "整体"),
            Triple(vision.axisPoints.takeLast(segmentSize), vision.baselineTail, "尾部")
        )

        tasks.forEach { (axis, baseline, _) ->
            generateDiagnosticStrip(canvas3, axis, baseline)?.let { strips.add(it) }
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