package com.example.asparagusclassifier.algorithm

import android.graphics.PointF
import android.util.Log
import com.example.asparagusclassifier.util.useMatScope
import org.opencv.calib3d.Calib3d
import org.opencv.core.*

/**
 * 相机位姿估算引擎
 * 重构版：引入时序一致性选择与位姿平滑过滤，彻底解决 Z 轴翻转与跳动问题
 */
object PoseEstimator {
    private const val TAG = "PoseEstimator"

    // --- 时序状态缓存 ---
    private var lastBoardPose: PoseInfo? = null
    private val lastMarkerPoses = mutableMapOf<Int, PoseInfo>()
    
    // 平滑系数 (0.0 - 1.0)，越小越平滑，但也意味着延迟越高
    private const val ALPHA_T = 0.4 
    private const val ALPHA_R = 0.3

    /**
     * 封装位姿信息
     */
    data class PoseInfo(
        val rvec: Mat,
        val tvec: Mat,
        val cameraMatrix: Mat,
        val distanceMm: Double,
        val tiltAngleDeg: Double,
        val cameraPosWorld: DoubleArray? = null
    ) {
        fun clone(): PoseInfo {
            return PoseInfo(rvec.clone(), tvec.clone(), cameraMatrix.clone(), distanceMm, tiltAngleDeg, cameraPosWorld?.clone())
        }
        fun release() {
            rvec.release()
            tvec.release()
            cameraMatrix.release()
        }
    }

    /**
     * 重置所有位姿记忆（在检测丢失或系统重置时调用）
     */
    fun reset() {
        lastBoardPose?.release()
        lastBoardPose = null
        lastMarkerPoses.values.forEach { it.release() }
        lastMarkerPoses.clear()
        Log.i(TAG, "PoseEstimator 记忆已重置")
    }

    /**
     * 估算标定板整体位姿
     */
    fun estimateCameraPose(
        imagePoints: MatOfPoint2f,
        calibration: CalibrationData,
        currentW: Int,
        currentH: Int
    ): PoseInfo? {
        val camMatrix = constructCameraMatrix(calibration, currentW, currentH)
        val distCoeffs = MatOfDouble(0.0, 0.0, 0.0, 0.0, 0.0)
        
        val objectPoints = if (imagePoints.total().toInt() == 16) {
            AlgorithmConfig.BOARD_OBJECT_POINTS_16
        } else {
            AlgorithmConfig.BOARD_OBJECT_POINTS
        }
        
        val rvecs = mutableListOf<Mat>()
        val tvecs = mutableListOf<Mat>()
        
        // 标定板多点模式使用 SQPNP
        Calib3d.solvePnPGeneric(objectPoints, imagePoints, camMatrix, distCoeffs, rvecs, tvecs, false, Calib3d.SOLVEPNP_SQPNP)
        
        if (rvecs.isEmpty()) {
            camMatrix.release()
            return null
        }
        
        // 选择最佳解：优先考虑时序连续性，其次重投影误差
        val bestIdx = selectBestSolution(rvecs, tvecs, objectPoints, imagePoints, camMatrix, distCoeffs, lastBoardPose)
        
        var finalRvec = rvecs[bestIdx].clone()
        var finalTvec = tvecs[bestIdx].clone()

        // 应用平滑滤波
        lastBoardPose?.let { last ->
            finalRvec = applyLowPassFilterR(finalRvec, last.rvec, ALPHA_R)
            finalTvec = applyLowPassFilterT(finalTvec, last.tvec, ALPHA_T)
        }

        // 计算辅助信息
        val info = buildPoseInfo(finalRvec, finalTvec, camMatrix)
        
        // 更新缓存 (注意释放旧的)
        lastBoardPose?.release()
        lastBoardPose = info.clone()
        
        // 释放 solvePnPGeneric 产生的中间结果
        rvecs.forEach { it.release() }
        tvecs.forEach { it.release() }
        
        return info
    }

    /**
     * 为单个 ArUco 标记估算位姿
     */
    fun estimateSingleMarkerPose(
        markerId: Int,
        corners: Array<PointF>,
        calibration: CalibrationData?,
        workW: Int,
        workH: Int
    ): List<PointF>? {
        if (calibration == null || !calibration.isValid()) return null
        
        return useMatScope { scope ->
            val camMatrix = constructCameraMatrix(calibration, workW, workH)
            val distCoeffs = MatOfDouble(0.0, 0.0, 0.0, 0.0, 0.0)
            
            val s = AlgorithmConfig.MARKER_SIZE_MM / 2.0 
            val objPoints = scope.manage(MatOfPoint3f(
                Point3(-s, s, 0.0), Point3(s, s, 0.0), Point3(s, -s, 0.0), Point3(-s, -s, 0.0)
            ))
            
            val imgPoints = scope.manage(MatOfPoint2f(
                Point(corners[3].x.toDouble(), corners[3].y.toDouble()), // BL
                Point(corners[2].x.toDouble(), corners[2].y.toDouble()), // BR
                Point(corners[1].x.toDouble(), corners[1].y.toDouble()), // TR
                Point(corners[0].x.toDouble(), corners[0].y.toDouble())  // TL
            ))
            
            val rvecs = mutableListOf<Mat>()
            val tvecs = mutableListOf<Mat>()
            // 单标志切换为 IPPE_SQUARE，它是平面歧义性的克星
            Calib3d.solvePnPGeneric(objPoints, imgPoints, camMatrix, distCoeffs, rvecs, tvecs, false, Calib3d.SOLVEPNP_IPPE_SQUARE)
            
            if (rvecs.isEmpty()) return@useMatScope null
            
            val bestIdx = selectBestSolution(rvecs, tvecs, objPoints as MatOfPoint3f, imgPoints as MatOfPoint2f, camMatrix, distCoeffs, lastMarkerPoses[markerId])
            
            var finalRvec = rvecs[bestIdx].clone()
            var finalTvec = tvecs[bestIdx].clone()

            // 平滑处理
            lastMarkerPoses[markerId]?.let { last ->
                finalRvec = applyLowPassFilterR(finalRvec, last.rvec, ALPHA_R)
                finalTvec = applyLowPassFilterT(finalTvec, last.tvec, ALPHA_T)
            }

            val pose = buildPoseInfo(finalRvec, finalTvec, camMatrix)
            
            // 更新缓存
            lastMarkerPoses[markerId]?.release()
            lastMarkerPoses[markerId] = pose.clone()
            
            val axes = projectAxes(pose, AlgorithmConfig.MARKER_SIZE_MM)
            
            rvecs.forEach { it.release() }
            tvecs.forEach { it.release() }
            camMatrix.release()
            distCoeffs.release()
            pose.release() // axes 已计算完毕，本地 pose 可释放
            
            axes
        }
    }

    /**
     * 在多个解中挑选最合理的那个
     * 策略：
     * 1. 排除位于平面背面的解 (相机坐标系 Z 必须指向物体)
     * 2. 如果有上一帧参考，选择旋转角距离最小的解 (防止 Z 轴翻转)
     * 3. 如果没有参考，选择重投影误差最小的解
     */
    private fun selectBestSolution(
        rvecs: List<Mat>,
        tvecs: List<Mat>,
        objPoints: MatOfPoint3f,
        imgPoints: MatOfPoint2f,
        camMatrix: Mat,
        distCoeffs: MatOfDouble,
        prevPose: PoseInfo?
    ): Int {
        if (rvecs.size == 1) return 0
        
        var bestIdx = 0
        var minTotalCost = Double.MAX_VALUE
        
        for (i in rvecs.indices) {
            // 物理合法性检查：相机必须在正面
            val rMat = Mat()
            Calib3d.Rodrigues(rvecs[i], rMat)
            val camInWorld = Mat()
            Core.gemm(rMat.t(), tvecs[i], -1.0, Mat(), 0.0, camInWorld)
            val zInWorld = camInWorld.get(2, 0)[0]
            
            val isBackside = zInWorld < 0
            
            // 计算重投影误差
            val projected = MatOfPoint2f()
            Calib3d.projectPoints(objPoints, rvecs[i], tvecs[i], camMatrix, distCoeffs, projected)
            val reprojError = Core.norm(imgPoints, projected, Core.NORM_L2)
            
            // 计算时序代价 (与上一帧的旋转差异)
            val temporalCost = if (prevPose != null) {
                calculateRotationDiff(rvecs[i], prevPose.rvec)
            } else 0.0
            
            // 综合 Cost
            // 如果是背面解，加上极高的惩罚项
            val backsidePenalty = if (isBackside) 1000.0 else 0.0
            
            // 权重平衡：重投影误差 vs 角度一致性
            // 角度 1 弧度的权重等同于 50 像素的误差，这样可以强迫系统在误差相近时选择不翻转的解
            val cost = reprojError + (temporalCost * 50.0) + backsidePenalty
            
            if (cost < minTotalCost) {
                minTotalCost = cost
                bestIdx = i
            }
            
            rMat.release(); camInWorld.release(); projected.release()
        }
        
        return bestIdx
    }

    /**
     * 计算两个旋转向量之间的角度差 (弧度)
     */
    private fun calculateRotationDiff(rvec1: Mat, rvec2: Mat): Double {
        val rMat1 = Mat()
        val rMat2 = Mat()
        Calib3d.Rodrigues(rvec1, rMat1)
        Calib3d.Rodrigues(rvec2, rMat2)
        
        // R_diff = R1 * R2^T
        val rDiff = Mat()
        Core.gemm(rMat1, rMat2.t(), 1.0, Mat(), 0.0, rDiff)
        
        // trace(R_diff) = 1 + 2*cos(theta)
        val trace = rDiff.get(0, 0)[0] + rDiff.get(1, 1)[0] + rDiff.get(2, 2)[0]
        val cosTheta = (trace - 1.0) / 2.0
        val theta = Math.acos(Math.max(-1.0, Math.min(1.0, cosTheta)))
        
        rMat1.release(); rMat2.release(); rDiff.release()
        return theta
    }

    private fun applyLowPassFilterT(current: Mat, last: Mat, alpha: Double): Mat {
        val result = current.clone()
        val cData = DoubleArray(3); current.get(0, 0, cData)
        val lData = DoubleArray(3); last.get(0, 0, lData)
        for (i in 0..2) {
            cData[i] = alpha * cData[i] + (1.0 - alpha) * lData[i]
        }
        result.put(0, 0, *cData)
        return result
    }

    private fun applyLowPassFilterR(current: Mat, last: Mat, alpha: Double): Mat {
        // 对于旋转向量，简单的线性插值在旋转幅度较小时是有效的
        // 如果需要极高精度，应使用 Slerp (四元数球面线性插值)
        val result = current.clone()
        val cData = DoubleArray(3); current.get(0, 0, cData)
        val lData = DoubleArray(3); last.get(0, 0, lData)
        
        // 检查点积判断是否需要反向插值 (处理旋转向量的歧义性)
        var dot = 0.0
        for (i in 0..2) dot += cData[i] * lData[i]
        
        for (i in 0..2) {
            cData[i] = alpha * cData[i] + (1.0 - alpha) * lData[i]
        }
        result.put(0, 0, *cData)
        return result
    }

    private fun buildPoseInfo(rvec: Mat, tvec: Mat, camMatrix: Mat): PoseInfo {
        val tvecArray = DoubleArray(3)
        tvec.get(0, 0, tvecArray)
        val distance = Math.sqrt(tvecArray[0] * tvecArray[0] + tvecArray[1] * tvecArray[1] + tvecArray[2] * tvecArray[2])
        
        val rvecArray = DoubleArray(3)
        rvec.get(0, 0, rvecArray)
        val rotationRad = Math.sqrt(rvecArray[0] * rvecArray[0] + rvecArray[1] * rvecArray[1] + rvecArray[2] * rvecArray[2])
        
        // 计算相机在世界坐标系中的具体位置
        val rMat = Mat()
        Calib3d.Rodrigues(rvec, rMat)
        val camWorldMat = Mat()
        Core.gemm(rMat.t(), tvec, -1.0, Mat(), 0.0, camWorldMat)
        val camPosWorld = DoubleArray(3)
        camWorldMat.get(0, 0, camPosWorld)
        
        rMat.release(); camWorldMat.release()
        
        return PoseInfo(rvec, tvec, camMatrix, distance, Math.toDegrees(rotationRad), camPosWorld)
    }

    /**
     * 投影 3D 坐标轴到 2D 像平面
     */
    fun projectAxes(pose: PoseInfo, lengthMm: Double = 50.0): List<PointF> {
        val axisPoints3D = MatOfPoint3f(
            Point3(0.0, 0.0, 0.0), Point3(lengthMm, 0.0, 0.0), 
            Point3(0.0, lengthMm, 0.0), Point3(0.0, 0.0, lengthMm)
        )
        val imagePoints2D = MatOfPoint2f()
        val distCoeffs = MatOfDouble(0.0, 0.0, 0.0, 0.0, 0.0) 
        Calib3d.projectPoints(axisPoints3D, pose.rvec, pose.tvec, pose.cameraMatrix, distCoeffs, imagePoints2D)
        val result = imagePoints2D.toArray().map { PointF(it.x.toFloat(), it.y.toFloat()) }
        axisPoints3D.release(); imagePoints2D.release(); distCoeffs.release()
        return result
    }

    /**
     * 构建相机内参矩阵
     */
    fun constructCameraMatrix(calibration: CalibrationData, currentW: Int, currentH: Int): Mat {
        val camMatrix = Mat.eye(3, 3, CvType.CV_64F)
        val scale = currentH.toDouble() / calibration.sensorWidth.toDouble()
        val fx_final = calibration.intrinsic[1].toDouble() * scale
        val fy_final = calibration.intrinsic[0].toDouble() * scale
        val idealW = calibration.sensorHeight.toDouble() * scale
        val cropOffsetX = (idealW - currentW.toDouble()) / 2.0
        val cx_final = calibration.intrinsic[3].toDouble() * scale - cropOffsetX
        val cy_final = calibration.intrinsic[2].toDouble() * scale
        val skew = calibration.intrinsic[4].toDouble() * scale
        
        camMatrix.put(0, 0, fx_final)
        camMatrix.put(0, 1, skew)
        camMatrix.put(0, 2, cx_final)
        camMatrix.put(1, 1, fy_final)
        camMatrix.put(1, 2, cy_final)
        return camMatrix
    }

    /**
     * 像平面 2D -> 世界坐标 3D (假设 Z = zWorld)
     */
    fun mapImageToWorld3D(p: android.graphics.PointF, zWorld: Double, pose: PoseInfo): Point3 {
        val rMat = Mat()
        Calib3d.Rodrigues(pose.rvec, rMat)
        val fx = pose.cameraMatrix.get(0, 0)[0]; val fy = pose.cameraMatrix.get(1, 1)[0]
        val cx = pose.cameraMatrix.get(0, 2)[0]; val cy = pose.cameraMatrix.get(1, 2)[0]
        val x_norm = (p.x - cx) / fx; val y_norm = (p.y - cy) / fy
        
        val rT = rMat.t()
        val r31 = rT.get(2, 0)[0]; val r32 = rT.get(2, 1)[0]; val r33 = rT.get(2, 2)[0]
        val tvec = DoubleArray(3); pose.tvec.get(0, 0, tvec)
        val tx = tvec[0]; val ty = tvec[1]; val tz = tvec[2]
        
        val s = (zWorld + r31 * tx + r32 * ty + r33 * tz) / (r31 * x_norm + r32 * y_norm + r33)
        val pcMat = Mat(3, 1, CvType.CV_64F).apply { put(0, 0, s * x_norm, s * y_norm, s) }
        val pwMat = Mat(); Core.subtract(pcMat, pose.tvec, pwMat)
        val pwFinalMat = Mat(); Core.gemm(rT, pwMat, 1.0, Mat(), 0.0, pwFinalMat)
        val pw = DoubleArray(3); pwFinalMat.get(0, 0, pw)
        
        rMat.release(); rT.release(); pcMat.release(); pwMat.release(); pwFinalMat.release()
        return Point3(pw[0], pw[1], pw[2])
    }

    /**
     * 世界坐标 -> 相机坐标系
     * Pc = R * Pw + t
     */
    fun transformWorldToCamera(pw: Point3, poseInfo: PoseInfo): Point3 {
        val rMat = Mat()
        Calib3d.Rodrigues(poseInfo.rvec, rMat)
        
        val rData = DoubleArray(9)
        rMat.get(0, 0, rData)
        
        val tData = DoubleArray(3)
        poseInfo.tvec.get(0, 0, tData)
        
        val xc = rData[0] * pw.x + rData[1] * pw.y + rData[2] * pw.z + tData[0]
        val yc = rData[3] * pw.x + rData[4] * pw.y + rData[5] * pw.z + tData[1]
        val zc = rData[6] * pw.x + rData[7] * pw.y + rData[8] * pw.z + tData[2]
        
        rMat.release()
        return Point3(xc, yc, zc)
    }
}
