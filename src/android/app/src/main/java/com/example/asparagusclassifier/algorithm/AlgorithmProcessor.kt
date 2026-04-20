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

    // 子组件
    private val arucoEngine = ArucoEngine()

    fun setCalibrationData(intrinsic: FloatArray, distortion: FloatArray) {
        this.intrinsicCalibration = intrinsic
        this.lensDistortion = distortion
        Log.i(TAG, "已更新相机校准参数")
    }
    
    fun processImage(bitmap: Bitmap): AlgorithmResult {
        Log.i(TAG, "开始执行算法管道 (三画布架构)")
        
        val origW = bitmap.width
        val origH = bitmap.height
        val scaleFactor = if (maxOf(origW, origH) > AlgorithmConfig.SCAN_MAX_SIDE) {
            AlgorithmConfig.SCAN_MAX_SIDE.toDouble() / maxOf(origW, origH).toDouble()
        } else 1.0

        val workBitmap = if (scaleFactor < 1.0) {
            Bitmap.createScaledBitmap(bitmap, (origW * scaleFactor).toInt(), (origH * scaleFactor).toInt(), true)
        } else bitmap

        val rgba = Mat()
        val rgbaUndistorted = Mat()
        val warpedRgba = Mat(AlgorithmConfig.TARGET_HEIGHT, AlgorithmConfig.TARGET_WIDTH, CvType.CV_8UC4)
        var mapper: CoordinateMapper? = null

        try {
            Utils.bitmapToMat(workBitmap, rgba)
            
            // --- Step 1: 物理去畸变 (生成 Canvas 2) ---
            if (intrinsicCalibration != null && lensDistortion != null) {
                undistortImage(rgba, rgbaUndistorted, intrinsicCalibration!!, lensDistortion!!)
            } else {
                rgba.copyTo(rgbaUndistorted)
            }
            
            // --- Step 2: 在物理画布上检测 ArUco (Canvas 2 基准) ---
            val arucoResult = arucoEngine.detectBoardMarkers(rgbaUndistorted)
            
            val arucoCorners = arucoResult.markerMap.values.toList()
            val arucoIds = arucoResult.markerMap.keys.toList()

            if (!arucoResult.success) {
                // 即使失败，也可返回去畸变后的图供调试显示
                return AlgorithmResult(false, 
                    error = arucoResult.error, 
                    arucoCorners = arucoCorners, 
                    arucoIds = arucoIds,
                    processedBitmap = matToBitmap(rgbaUndistorted)
                )
            }

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

            val transMat = Imgproc.getPerspectiveTransform(srcPoints, dstPoints)
            Imgproc.warpPerspective(rgbaUndistorted, warpedRgba, transMat, warpedRgba.size())
            
            // 初始化坐标映射器
            mapper = CoordinateMapper(scaleFactor, transMat)
            
            srcPoints.release()
            dstPoints.release()
            transMat.release()

            // --- Step 4: 逻辑画布分析 (Canvas 3 基准) ---
            val visionResult = AsparagusVisionCore.analyze(warpedRgba, AlgorithmConfig.MM_TO_PX)
            
            if (!visionResult.success) {
                return AlgorithmResult(false, 
                    error = visionResult.error, 
                    arucoCorners = arucoCorners, 
                    arucoIds = arucoIds,
                    processedBitmap = matToBitmap(rgbaUndistorted)
                )
            }

            // --- Step 5: 结果构造 (回归 Canvas 2 坐标供渲染) ---
            // 注意：所有的 PointF 现在都相对于 rgbaUndistorted 的尺寸
            val finalContour = visionResult.contourPoints.map { mapper.mapWarpedToWork(it) }
            val finalAxis = visionResult.axisPoints.map { mapper.mapWarpedToWork(it) }
            val finalTail = visionResult.purpleRootPoint?.let { mapper.mapWarpedToWork(it) }
            val finalDiameterLines = visionResult.diameterLines.map { line -> line.map { mapper.mapWarpedToWork(it) } }

            // ArUco 标记已经在 Canvas 2 上，仅需通过 invScale 还原回 Original Undistorted
            // 但如果 UI 逻辑层也显示的是经过 AlgorithmProcessor 返回的 processedBitmap，
            // 那么比例应当匹配 processedBitmap 的分辨率。
            val processedBmp = matToBitmap(rgbaUndistorted)

            return AlgorithmResult(
                success = true,
                grade = visionResult.grade,
                diameter = visionResult.diameterMm,
                rawDiameter = visionResult.rawDiameterMm,
                length = visionResult.lengthMm,
                purpleRootPosition = if (finalTail != null) "(${finalTail.x.toInt()}, ${finalTail.y.toInt()})" else "未检测到",
                asparagusRect = calculateBoundingRect(finalContour),
                asparagusContour = finalContour,
                tailPoint = finalTail,
                axisPath = finalAxis,
                diameterLine = finalDiameterLines,
                arucoCorners = arucoCorners, // 已在物理去畸变空间
                arucoIds = arucoIds,
                processedBitmap = processedBmp
            )

        } catch (e: Exception) {
            Log.e(TAG, "算法异常: ${e.message}")
            return AlgorithmResult(false, error = e.message)
        } finally {
            rgba.release()
            rgbaUndistorted.release()
            warpedRgba.release()
            mapper?.release()
        }
    }

    private fun undistortImage(src: Mat, dst: Mat, intrinsic: FloatArray, distortion: FloatArray) {
        val camMatrix = Mat(3, 3, CvType.CV_32F)
        camMatrix.put(0, 0, intrinsic[0].toDouble())
        camMatrix.put(0, 1, intrinsic[4].toDouble())
        camMatrix.put(0, 2, intrinsic[2].toDouble())
        camMatrix.put(1, 1, intrinsic[1].toDouble())
        camMatrix.put(1, 2, intrinsic[3].toDouble())
        camMatrix.put(2, 2, 1.0)
        
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

    private fun dist(p1: PointF, p2: PointF) = Math.sqrt(((p1.x - p2.x) * (p1.x - p2.x) + (p1.y - p2.y) * (p1.y - p2.y)).toDouble())

    private fun centerOf(corners: Array<PointF>): PointF {
        var x = 0f; var y = 0f
        for (p in corners) { x += p.x; y += p.y }
        return PointF(x / 4f, y / 4f)
    }
}