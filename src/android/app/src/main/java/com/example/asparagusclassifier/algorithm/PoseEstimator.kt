package com.example.asparagusclassifier.algorithm

import android.graphics.PointF
import android.util.Log
import com.example.asparagusclassifier.util.useMatScope
import org.opencv.calib3d.Calib3d
import org.opencv.core.*

/**
 * 相机位姿估算引擎
 * 专门处理 3D 空间坐标变换与 solvePnP
 */
object PoseEstimator {
    private const val TAG = "PoseEstimator"

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

    /**
     * 估算相机在标定板坐标系中的 3D 位姿
     */
    fun estimateCameraPose(
        imagePoints: MatOfPoint2f,
        calibration: CalibrationData,
        currentW: Int,
        currentH: Int
    ): PoseInfo? {
        val camMatrix = constructCameraMatrix(calibration, currentW, currentH)
        val distCoeffs = MatOfDouble(0.0, 0.0, 0.0, 0.0, 0.0) // 已预先去畸变，系数为0
        
        // 使用 16 点模型或 4 点模型
        val objectPoints = if (imagePoints.total().toInt() == 16) {
            AlgorithmConfig.BOARD_OBJECT_POINTS_16
        } else {
            AlgorithmConfig.BOARD_OBJECT_POINTS
        }
        
        val rvecs = mutableListOf<Mat>()
        val tvecs = mutableListOf<Mat>()
        
        // 使用 solvePnPGeneric 获取所有可能的解，解决平面标志物的翻转二义性
        Calib3d.solvePnPGeneric(objectPoints, imagePoints, camMatrix, distCoeffs, rvecs, tvecs, false, Calib3d.SOLVEPNP_IPPE)
        
        if (rvecs.isEmpty()) {
            Log.e(TAG, "solvePnP 失败")
            camMatrix.release()
            return null
        }
        
        // 挑选“物理正确”的解：相机必须在标定板正面 (Z > 0)
        var bestIdx = 0
        if (rvecs.size > 1) {
            for (i in rvecs.indices) {
                val rMat = Mat()
                Calib3d.Rodrigues(rvecs[i], rMat)
                val rMatT = rMat.t()
                val camInWorld = Mat()
                Core.gemm(rMatT, tvecs[i], -1.0, Mat(), 0.0, camInWorld)
                
                val zInWorld = camInWorld.get(2, 0)[0]
                rMat.release(); rMatT.release(); camInWorld.release()
                
                if (zInWorld > 0) {
                    bestIdx = i
                    break
                }
            }
        }
        
        val rvec = rvecs[bestIdx].clone()
        val tvec = tvecs[bestIdx].clone()
        
        // 释放所有解
        rvecs.forEach { it.release() }
        tvecs.forEach { it.release() }
        
        // 计算物理距离 (欧氏距离)
        val tvecArray = DoubleArray(3)
        tvec.get(0, 0, tvecArray)
        val distance = Math.sqrt(tvecArray[0] * tvecArray[0] + tvecArray[1] * tvecArray[1] + tvecArray[2] * tvecArray[2])
        
        // 计算相机倾角 (旋转向量模长)
        val rvecArray = DoubleArray(3)
        rvec.get(0, 0, rvecArray)
        val rotationRad = Math.sqrt(rvecArray[0] * rvecArray[0] + rvecArray[1] * rvecArray[1] + rvecArray[2] * rvecArray[2])
        val tiltAngleDeg = Math.toDegrees(rotationRad)

        // 计算相机在世界坐标系中的具体位置: Pw = -R^T * t
        val rMat = Mat()
        Calib3d.Rodrigues(rvec, rMat)
        val rMatT = rMat.t()
        val camWorldMat = Mat()
        Core.gemm(rMatT, tvec, -1.0, Mat(), 0.0, camWorldMat)
        val camPosWorld = DoubleArray(3)
        camWorldMat.get(0, 0, camPosWorld)
        
        // 验证 Z 坐标一致性
        if (camPosWorld[2] < 0) {
            Log.w(TAG, "警告：解得的相机位姿位于平面背面 (Z=%.1f)".format(camPosWorld[2]))
        }

        val info = PoseInfo(rvec, tvec, camMatrix, distance, tiltAngleDeg, camPosWorld)
        
        rMat.release()
        rMatT.release()
        camWorldMat.release()
        
        return info
    }

    /**
     * 投影 3D 坐标轴到 2D 像平面
     * @return [原点, X末端, Y末端, Z末端] 的 2D 坐标列表
     */
    fun projectAxes(pose: PoseInfo, lengthMm: Double = 50.0): List<PointF> {
        val axisPoints3D = MatOfPoint3f(
            Point3(0.0, 0.0, 0.0),            // 原点
            Point3(lengthMm, 0.0, 0.0),       // X 轴
            Point3(0.0, lengthMm, 0.0),       // Y 轴
            Point3(0.0, 0.0, lengthMm)        // Z 轴
        )
        val imagePoints2D = MatOfPoint2f()
        val distCoeffs = MatOfDouble(0.0, 0.0, 0.0, 0.0, 0.0) 
        
        Calib3d.projectPoints(axisPoints3D, pose.rvec, pose.tvec, pose.cameraMatrix, distCoeffs, imagePoints2D)
        
        val result = imagePoints2D.toArray().map { PointF(it.x.toFloat(), it.y.toFloat()) }
        
        axisPoints3D.release()
        imagePoints2D.release()
        distCoeffs.release()
        
        return result
    }

    /**
     * 为单个 ArUco 标记估算位姿并投射坐标轴
     */
    fun estimateSingleMarkerPose(
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
                Point(corners[0].x.toDouble(), corners[0].y.toDouble()),
                Point(corners[1].x.toDouble(), corners[1].y.toDouble()),
                Point(corners[2].x.toDouble(), corners[2].y.toDouble()),
                Point(corners[3].x.toDouble(), corners[3].y.toDouble())
            ))
            
            val rvecs = mutableListOf<Mat>()
            val tvecs = mutableListOf<Mat>()
            Calib3d.solvePnPGeneric(objPoints, imgPoints, camMatrix, distCoeffs, rvecs, tvecs, false, Calib3d.SOLVEPNP_IPPE)
            
            if (rvecs.isEmpty()) return@useMatScope null
            
            val bestIdx = if (rvecs.size > 1) {
                var idx = 0
                for (i in rvecs.indices) {
                    val rMat = scope.createMat()
                    Calib3d.Rodrigues(rvecs[i], rMat)
                    val rMatT = rMat.t()
                    val camInMarker = scope.createMat()
                    Core.gemm(rMatT, tvecs[i], -1.0, Mat(), 0.0, camInMarker)
                    if (camInMarker.get(2, 0)[0] > 0) { idx = i; break }
                }
                idx
            } else 0
            
            val pose = PoseInfo(rvecs[bestIdx], tvecs[bestIdx], camMatrix, 0.0, 0.0)
            val axes = projectAxes(pose, AlgorithmConfig.MARKER_SIZE_MM)
            
            // 显式释放 solvePnP 生成的 Mat
            rvecs.forEach { it.release() }
            tvecs.forEach { it.release() }
            camMatrix.release()
            distCoeffs.release()
            
            axes
        }
    }

    /**
     * 根据标定数据和当前分辨率构建相机内参矩阵
     */
    fun constructCameraMatrix(calibration: CalibrationData, currentW: Int, currentH: Int): Mat {
        val camMatrix = Mat.eye(3, 3, CvType.CV_32F)
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
        return camMatrix
    }

    /**
     * 将 Canvas 3 坐标映射到世界 3D 坐标
     * Canvas 3 是 Z=0 平面的透视映射
     */
    fun mapCanvas3ToWorld3D(p: android.graphics.PointF, z: Double): Point3 {
        val xWorld = (p.x - AlgorithmConfig.PADDING_PX) / AlgorithmConfig.MM_TO_PX_IN_CANVAS_3
        val yWorld = (p.y - AlgorithmConfig.PADDING_PX) / AlgorithmConfig.MM_TO_PX_IN_CANVAS_3
        return Point3(xWorld, yWorld, z)
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
