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

    // 相机校准数据
    private var intrinsicCalibration: FloatArray? = null
    private var lensDistortion: FloatArray? = null
    private var sensorReferenceWidth: Int = 0
    private var sensorReferenceHeight: Int = 0
    
    // 兼容性标志位
    var isCalibrationValid: Boolean = true
        private set

    // 子组件
    private val arucoEngine = ArucoEngine()

    fun setCalibrationData(intrinsic: FloatArray, distortion: FloatArray, sensorWidth: Int, sensorHeight: Int) {
        this.intrinsicCalibration = intrinsic
        this.lensDistortion = distortion
        this.sensorReferenceWidth = sensorWidth
        this.sensorReferenceHeight = sensorHeight
        
        // 校验内参合法性：如果焦距 (fx, fy) 均为 0，则视为无效
        if (intrinsic.size >= 2 && intrinsic[0] == 0f && intrinsic[1] == 0f) {
            isCalibrationValid = false
            Log.e(TAG, "检测到无效的相机内参 (焦距为0)！将进入原始兼容模式。")
            // 立即后台上报异常机型
            com.example.asparagusclassifier.util.MqttReporter.reportInvalidIntrinsic(intrinsic, sensorWidth, sensorHeight)
        } else {
            isCalibrationValid = true
            Log.i(TAG, "已更新相机校准参数及参考分辨率: ${sensorWidth}x${sensorHeight}")
        }
    }
    
    fun processImage(bitmap: Bitmap, viewMode: Int = 3): AlgorithmResult {
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
            if (isCalibrationValid && intrinsicCalibration != null && lensDistortion != null && sensorReferenceWidth > 0) {
                undistortImage(rgba, rgbaUndistorted, intrinsicCalibration!!, lensDistortion!!, workBitmap.width, workBitmap.height)
                Log.i(TAG, "[Step 1] 去畸变完成")
            } else {
                rgba.copyTo(rgbaUndistorted)
                if (!isCalibrationValid) {
                    Log.w(TAG, "[Step 1] 机型不兼容，已跳过去畸变 (原始模式)")
                } else {
                    Log.w(TAG, "[Step 1] 跳过去畸变 (校准参数缺失)")
                }
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
            
            // --- Step 4.5: 坐标同步 (针对 C3 模式映射 ArUco 标记点) ---
            val finalArucoCorners = if (viewMode == 3 && transMat != null) {
                arucoCorners.map { corners ->
                    val srcMat = MatOfPoint2f(*corners.map { org.opencv.core.Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray())
                    val dstMat = MatOfPoint2f()
                    Core.perspectiveTransform(srcMat, dstMat, transMat)
                    val transformed = dstMat.toArray().map { android.graphics.PointF(it.x.toFloat(), it.y.toFloat()) }.toTypedArray()
                    srcMat.release()
                    dstMat.release()
                    transformed
                }
            } else {
                arucoCorners
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
                
                // 坐标返回：对齐视图 3，直接使用标准画布坐标（10px/mm）
                asparagusContour = visionResult.contourPoints,
                axisPath = visionResult.axisPoints,
                tailPoint = visionResult.purpleRootPoint,
                diameterLine = visionResult.diameterLines,
                asparagusRect = calculateBoundingRect(visionResult.contourPoints),
                
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

    private fun undistortImage(src: Mat, dst: Mat, intrinsic: FloatArray, distortion: FloatArray, currentW: Int, currentH: Int) {
        val camMatrix = Mat(3, 3, CvType.CV_32F)
        
        // 关键逻辑：根据当前图像分辨率与传感器参考分辨率的比例，动态缩放内参
        val scaleX = currentW.toDouble() / sensorReferenceWidth.toDouble()
        val scaleY = currentH.toDouble() / sensorReferenceHeight.toDouble()
        
        val fx = intrinsic[0].toDouble() * scaleX
        val fy = intrinsic[1].toDouble() * scaleY
        val cx = intrinsic[2].toDouble() * scaleX
        val cy = intrinsic[3].toDouble() * scaleY
        val skew = intrinsic[4].toDouble() * scaleX
        
        camMatrix.put(0, 0, fx)
        camMatrix.put(0, 1, skew)
        camMatrix.put(0, 2, cx)
        camMatrix.put(1, 1, fy)
        camMatrix.put(1, 2, cy)
        camMatrix.put(2, 2, 1.0)
        
        Log.d(TAG, "执行去畸变: 比例X=%.3f, 焦距=(%.1f, %.1f), 主点=(%.1f, %.1f)".format(scaleX, fx, fy, cx, cy))
        
        val distCoeffs = MatOfDouble(*DoubleArray(distortion.size) { distortion[it].toDouble() })
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
}