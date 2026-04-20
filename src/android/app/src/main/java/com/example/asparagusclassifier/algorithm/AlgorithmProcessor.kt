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
            
            srcPoints.release()
            dstPoints.release()
            // transMat 将在业务逻辑结束后由 finally 块释放

            // --- Step 4: 逻辑画布分析 (Canvas 3 基准) ---
            val visionResult = AsparagusVisionCore.analyze(warpedRgba, AlgorithmConfig.MM_TO_PX)
            
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
                viewMode = viewMode
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
        }
    }

    private fun undistortImage(src: Mat, dst: Mat, calibration: CalibrationData, currentW: Int, currentH: Int) {
        val camMatrix = Mat(3, 3, CvType.CV_32F)
        
        // 关键逻辑：根据当前图像分辨率与传感器参考分辨率的比例，动态缩放内参
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
        
        Log.d(TAG, "执行去畸变: 比例X=%.3f, 焦距=(%.1f, %.1f), 主点=(%.1f, %.1f)".format(scaleX, fx, fy, cx, cy))
        
        val distCoeffs = MatOfDouble(*DoubleArray(calibration.distortion.size) { calibration.distortion[it].toDouble() })
        Calib3d.undistort(src, dst, camMatrix, distCoeffs)
        
        camMatrix.release()
        distCoeffs.release()
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