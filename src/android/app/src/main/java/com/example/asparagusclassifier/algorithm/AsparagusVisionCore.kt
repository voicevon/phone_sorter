package com.example.asparagusclassifier.algorithm

import android.graphics.PointF
import android.util.Log
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.calib3d.Calib3d
import kotlin.math.sqrt

/**
 * 芦笋视觉核心算法
 * 负责解析标准化画布（Warped Mat）中的芦笋几何特征
 */
object AsparagusVisionCore {
    private const val TAG = "AsparagusVisionCore"

    data class AnalysisResult(
        val success: Boolean,
        val grade: String = "F",
        val diameterMm: Double = 0.0,
        val lengthMm: Double = 0.0,
        val rawDiameterMm: Double = 0.0,
        val contourPoints: List<PointF> = emptyList(),
        val axisPoints: List<PointF> = emptyList(),
        val purpleRootPoint: PointF? = null,
        val diameterLines: List<List<PointF>> = emptyList(),
        val straightnessOverall: Double = 0.0,
        val straightnessHead: Double = 0.0,
        val straightnessTail: Double = 0.0,
        val baselineOverall: List<PointF>? = null,
        val baselineHead: List<PointF>? = null,
        val baselineTail: List<PointF>? = null,
        val error: String? = null
    )
    
    data class StraightnessResult(
        val rmse: Double,
        val endpoints: List<PointF> // 基准线的起止点
    )

    /**
     * 在标准化空间执行分析
     */
    fun analyze(
        warpedRgba: Mat, 
        pixelsPerMm: Double,
        poseInfo: AlgorithmProcessor.PoseInfo? = null
    ): AnalysisResult {
        val hsv = Mat()
        val mask = Mat()
        val hierarchy = Mat()
        
        try {
            Imgproc.cvtColor(warpedRgba, hsv, Imgproc.COLOR_RGB2HSV)
            
            // 1. 区域锁定 (ROI Masking)：只分析板内区域
            val roiMask = Mat.zeros(warpedRgba.size(), CvType.CV_8UC1)
            val boardRect = Rect(10, 10, warpedRgba.cols() - 20, warpedRgba.rows() - 20)
            Imgproc.rectangle(roiMask, boardRect, Scalar(255.0), -1)
            
            // 2. 颜色分割 (绿色)
            Core.inRange(hsv, AlgorithmConfig.LOWER_GREEN, AlgorithmConfig.UPPER_GREEN, mask)
            Core.bitwise_and(mask, roiMask, mask) // 强制裁剪
            roiMask.release()
            
            // 3. 形态学滤波
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, kernel)
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel)
            kernel.release()

            val contours = mutableListOf<MatOfPoint>()
            Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            
            val maxContour = contours.maxByOrNull { Imgproc.contourArea(it) }
            
            if (maxContour == null || Imgproc.contourArea(maxContour) < AlgorithmConfig.MIN_CONTOUR_AREA) {
                return AnalysisResult(false, error = "未检测到芦笋或面积过小")
            }

            // 2. 几何分析
            val contourPoints = maxContour.toArray().map { PointF(it.x.toFloat(), it.y.toFloat()) }
            
            // 紫根检测
            val purplePoint = detectPurpleRoot(hsv, maxContour)
            
            // 轴线提取
            val rawAxis = computeCenterline(mask, maxContour)
            
            // 方向校正 (根据紫根或像素密度)
            val orientedAxis = orientAxis(rawAxis, purplePoint, mask)
            
            // 3. 物理测量
            var lengthMm = 0.0
            var rawDiameterMm = 0.0
            val diameterLines = mutableListOf<List<PointF>>()

            if (orientedAxis.size >= 2) {
                // 计算 V1 长度（像素累加）作为对比
                var totalLengthPx = 0.0
                for (i in 0 until orientedAxis.size - 1) {
                    totalLengthPx += dist(orientedAxis[i], orientedAxis[i+1])
                }

                if (poseInfo != null) {
                    // 1. 长度计算 (3D 欧几里得距离)
                    val pStart3D = mapCanvas3ToWorld3D(orientedAxis.first(), 8.0, poseInfo) // 假设中心轴高度 8mm
                    val pEnd3D = mapCanvas3ToWorld3D(orientedAxis.last(), 8.0, poseInfo)
                    lengthMm = dist3D(pStart3D, pEnd3D)
                    Log.i(TAG, "V2 3D 长度: %.1fmm (V1 估计: %.1fmm)".format(lengthMm, totalLengthPx / pixelsPerMm))
                } else {
                    lengthMm = totalLengthPx / pixelsPerMm
                }

                // 2. 直径采样 (深度补偿)
                val diameterSamples = mutableListOf<Double>()
                for (offsetMm in AlgorithmConfig.SAMPLING_OFFSETS_MM) {
                    val offsetPx = offsetMm * pixelsPerMm
                    val (sampleP, normalV) = findPointAndNormalAtDistance(orientedAxis, offsetPx)
                    
                    if (sampleP != null && normalV != null) {
                        val widthPx = measureWidth(mask, sampleP, normalV)
                        
                        val sampleDiameterMm = if (poseInfo != null) {
                            // 计算采样点到相机的实时深度
                            val samplePF = PointF(sampleP.x.toFloat(), sampleP.y.toFloat())
                            val pWorld3D = mapCanvas3ToWorld3D(samplePF, 10.0, poseInfo) // 假设高度 10mm
                            val pCam = transformWorldToCamera(pWorld3D, poseInfo)
                            val depth = sqrt(pCam.x * pCam.x + pCam.y * pCam.y + pCam.z * pCam.z)
                            
                            // D_mm = D_px * (depth / focal_length)
                            // 注意：focal_length 在 cameraMatrix 的 (0,0) 和 (1,1)
                            val fx = poseInfo.cameraMatrix.get(0, 0)[0]
                            val fy = poseInfo.cameraMatrix.get(1, 1)[0]
                            val fAvg = (fx + fy) / 2.0
                            
                            val dMm = (widthPx / fAvg) * depth
                            Log.d(TAG, "采样点深度补偿: Depth=%.1fmm, D_px=%.1f, D_mm=%.2f".format(depth, widthPx, dMm))
                            dMm
                        } else {
                            widthPx / pixelsPerMm
                        }
                        
                        diameterSamples.add(sampleDiameterMm)
                        
                        // 记录测量线用于 UI 展示
                        diameterLines.add(listOf(
                            PointF((sampleP.x - normalV.x * widthPx/2).toFloat(), (sampleP.y - normalV.y * widthPx/2).toFloat()),
                            PointF((sampleP.x + normalV.x * widthPx/2).toFloat(), (sampleP.y + normalV.y * widthPx/2).toFloat())
                        ))
                    }
                }
                
                if (diameterSamples.isNotEmpty()) {
                    rawDiameterMm = diameterSamples.average()
                }
            }
            
            // 4. 直线度分析 (RMSE 偏离值，单位为像素，后续转成 mm)
            var sOverall = 0.0
            var sHead = 0.0
            var sTail = 0.0
            var bOverall: List<PointF>? = null
            var bHead: List<PointF>? = null
            var bTail: List<PointF>? = null
            
            if (orientedAxis.size >= 4) {
                val resOverall = computeStraightness(orientedAxis)
                sOverall = resOverall.rmse / pixelsPerMm
                bOverall = resOverall.endpoints
                
                // 截取头尾段 (各占 25%)
                val segmentSize = orientedAxis.size / 4
                val resHead = computeStraightness(orientedAxis.take(segmentSize))
                val resTail = computeStraightness(orientedAxis.takeLast(segmentSize))
                
                sHead = resHead.rmse / pixelsPerMm
                sTail = resTail.rmse / pixelsPerMm
                bHead = resHead.endpoints
                bTail = resTail.endpoints
                
                Log.i(TAG, "直线度计算完成: 整体=%.2f, 头=%.2f, 尾=%.2f".format(sOverall, sHead, sTail))
            }

            val correctedDiameter = maxOf(0.0, rawDiameterMm - AlgorithmConfig.DIAMETER_CORRECTION_MM)
            val grade = calculateGrade(correctedDiameter)

            return AnalysisResult(
                success = true,
                grade = grade,
                diameterMm = correctedDiameter,
                lengthMm = lengthMm,
                rawDiameterMm = rawDiameterMm,
                contourPoints = contourPoints,
                axisPoints = orientedAxis,
                purpleRootPoint = purplePoint?.let { PointF(it.x.toFloat(), it.y.toFloat()) },
                diameterLines = diameterLines,
                straightnessOverall = sOverall,
                straightnessHead = sHead,
                straightnessTail = sTail,
                baselineOverall = bOverall,
                baselineHead = bHead,
                baselineTail = bTail
            )

        } finally {
            hsv.release()
            mask.release()
            hierarchy.release()
        }
    }

    private fun detectPurpleRoot(hsv: Mat, contour: MatOfPoint): Point? {
        val purpleMask = Mat()
        val roiMask = Mat.zeros(hsv.size(), CvType.CV_8UC1)
        val filtered = Mat()
        val nonZero = MatOfPoint()
        try {
            Core.inRange(hsv, AlgorithmConfig.LOWER_PURPLE, AlgorithmConfig.UPPER_PURPLE, purpleMask)
            Imgproc.drawContours(roiMask, listOf(contour), -1, Scalar(255.0), -1)
            Core.bitwise_and(purpleMask, roiMask, filtered)
            Core.findNonZero(filtered, nonZero)
            
            if (nonZero.total() > 0) {
                val pts = nonZero.toArray()
                return Point(pts.map { it.x }.average(), pts.map { it.y }.average())
            }
        } finally {
            purpleMask.release()
            roiMask.release()
            filtered.release()
            nonZero.release()
        }
        return null
    }

    private fun computeCenterline(mask: Mat, contour: MatOfPoint): List<PointF> {
        val contour2f = MatOfPoint2f(*contour.toArray())
        val rotRect = Imgproc.minAreaRect(contour2f)
        contour2f.release()

        val center = rotRect.center
        val angle = if (rotRect.size.width > rotRect.size.height) rotRect.angle + 90.0 else rotRect.angle

        val rotMat = Imgproc.getRotationMatrix2D(center, angle, 1.0)
        val rotated = Mat()
        Imgproc.warpAffine(mask, rotated, rotMat, mask.size())

        val nonZero = Mat()
        Core.findNonZero(rotated, nonZero)
        if (nonZero.total() == 0L) {
            rotated.release(); nonZero.release(); rotMat.release()
            return emptyList()
        }
        val bbox = Imgproc.boundingRect(nonZero)
        nonZero.release()

        val points = mutableListOf<PointF>()
        for (y in bbox.y until bbox.y + bbox.height) {
            var left = -1; var right = -1
            for (x in bbox.x until bbox.x + bbox.width) {
                if (rotated.get(y, x)[0] > 0.0) {
                    if (left == -1) left = x
                    right = x
                }
            }
            if (left != -1) points.add(PointF(((left + right) / 2f), y.toFloat()))
        }
        rotated.release()

        // 逆旋转
        val invRotMat = Imgproc.getRotationMatrix2D(center, -angle, 1.0)
        val m = DoubleArray(6)
        invRotMat.get(0, 0, m)
        invRotMat.release()

        return points.map { p ->
            PointF(
                (m[0] * p.x + m[1] * p.y + m[2]).toFloat(),
                (m[3] * p.x + m[4] * p.y + m[5]).toFloat()
            )
        }
    }

    private fun orientAxis(axis: List<PointF>, purple: Point?, mask: Mat): List<PointF> {
        if (axis.size < 4) return axis
        
        var needsReverse = false
        
        if (purple != null) {
            // 逻辑 A: 颜色判向 (最高优先级)
            val dFirst = dist(axis.first(), PointF(purple.x.toFloat(), purple.y.toFloat()))
            val dLast = dist(axis.last(), PointF(purple.x.toFloat(), purple.y.toFloat()))
            needsReverse = dLast < dFirst
        } else {
            // 逻辑 B: 直线度与密度综合判向 (TailScore = Density / (RMSE + 1))
            val segmentSize = axis.size / 4
            val headSeg = axis.take(segmentSize)
            val tailSeg = axis.takeLast(segmentSize)
            
            // 1. 直线度比较
            val sHead = computeStraightness(headSeg).rmse
            val sTail = computeStraightness(tailSeg).rmse
            
            // 2. 密度（粗度）比较
            val R = 20
            fun density(pt: PointF): Double {
                var cnt = 0
                for (dy in -R..R) {
                    for (dx in -R..R) {
                        if (dx*dx + dy*dy > R*R) continue
                        val px = (pt.x + dx).toInt(); val py = (pt.y + dy).toInt()
                        if (px in 0 until mask.cols() && py in 0 until mask.rows()) {
                            if (mask.get(py, px)[0] > 0) cnt++
                        }
                    }
                }
                return cnt.toDouble()
            }
            val dHead = headSeg.map { density(it) }.average()
            val dTail = tailSeg.map { density(it) }.average()
            
            // 计算 TailScore：分值越高越可能是尾部
            val scoreAtStart = dHead / (sHead + 1.0)
            val scoreAtEnd = dTail / (sTail + 1.0)
            
            // 核心修复：测量逻辑（如直径采样偏移）是从 axis.first() 开始计算的。
            // 因此，得分更高的一端（即物理尾部）必须位于列表开头（Index 0）。
            // 如果末尾端的得分高于起始端，说明物理尾部在末尾，需要执行反转。
            needsReverse = scoreAtEnd > scoreAtStart
            
            Log.d(TAG, "智能判向诊断: 开始端(Score=%.2f, D=%.1f, S=%.2f), 结束端(Score=%.2f, D=%.1f, S=%.2f) -> NeedsReverse=$needsReverse"
                .format(scoreAtStart, dHead, sHead, scoreAtEnd, dTail, sTail))
        }
        
        return if (needsReverse) axis.reversed() else axis
    }

    /**
     * 计算一组点的直线度 (RMSE) 及其基准拟合线
     * 使用最小二乘法 (Total Least Squares) 拟合 2D 直线
     */
    private fun computeStraightness(points: List<PointF>): StraightnessResult {
        if (points.size < 2) return StraightnessResult(0.0, emptyList())
        
        val n = points.size
        var avgX = 0f; var avgY = 0f
        points.forEach { avgX += it.x; avgY += it.y }
        avgX /= n; avgY /= n
        
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        points.forEach {
            val dx = it.x - avgX; val dy = it.y - avgY
            sxx += dx * dx; syy += dy * dy; sxy += dx * dy
        }
        
        // 特征值分解
        val det = sxx - syy
        val distValue = sqrt(det * det + 4 * sxy * sxy)
        
        // 方向向量 (vx, vy)
        val vx: Double
        val vy: Double
        
        if (distValue < 1e-10) {
            // 所有点重合或极度对称，无唯一基准线
            return StraightnessResult(0.0, emptyList())
        } else {
            // 特征向量对应最大特征值
            val mag = sqrt((det + distValue) * (det + distValue) + 4 * sxy * sxy)
            vx = (det + distValue) / mag
            vy = (2.0 * sxy) / mag
        }
        
        // 法向量 (nx, ny)
        val nx = -vy
        val ny = vx
        
        var sumDistSq = 0.0
        val projections = mutableListOf<Double>()
        points.forEach {
            val dx = it.x - avgX; val dy = it.y - avgY
            // 正交距离
            val d = dx * nx + dy * ny
            sumDistSq += d * d
            // 沿直线投影距离，用于生成基准线起止点
            projections.add(dx * vx + dy * vy)
        }
        
        val minP = projections.minOrNull() ?: 0.0
        val maxP = projections.maxOrNull() ?: 0.0
        val endpoints = listOf(
            PointF((avgX + vx * minP).toFloat(), (avgY + vy * minP).toFloat()),
            PointF((avgX + vx * maxP).toFloat(), (avgY + vy * maxP).toFloat())
        )
        
        return StraightnessResult(sqrt(sumDistSq / n), endpoints)
    }

    private fun findPointAndNormalAtDistance(path: List<PointF>, targetDist: Double): Pair<Point?, Point?> {
        var acc = 0.0
        val window = 12
        for (i in 0 until path.size - 1) {
            val d = dist(path[i], path[i+1])
            if (acc + d >= targetDist) {
                val r = (targetDist - acc) / d
                val p = Point((path[i].x + (path[i+1].x - path[i].x) * r).toDouble(), (path[i].y + (path[i+1].y - path[i].y) * r).toDouble())
                
                val sIdx = maxOf(0, i - window)
                val eIdx = minOf(path.size - 1, i + window)
                if (eIdx > sIdx) {
                    val dx = (path[eIdx].x - path[sIdx].x).toDouble()
                    val dy = (path[eIdx].y - path[sIdx].y).toDouble()
                    val l = sqrt(dx*dx + dy*dy)
                    if (l > 1e-5) return Pair(p, Point(-dy/l, dx/l))
                }
                val dx = (path[i+1].x - path[i].x).toDouble()
                val dy = (path[i+1].y - path[i].y).toDouble()
                val l = sqrt(dx*dx + dy*dy)
                return Pair(p, Point(-dy/l, dx/l))
            }
            acc += d
        }
        return Pair(null, null)
    }

    private fun measureWidth(mask: Mat, c: Point, n: Point): Double {
        var w1 = 0.0; var w2 = 0.0
        for (i in 1..250) {
            val px = (c.x + n.x * i).toInt(); val py = (c.y + n.y * i).toInt()
            if (px !in 0 until mask.cols() || py !in 0 until mask.rows()) break
            if (mask.get(py, px)[0] == 0.0) { w1 = i.toDouble(); break }
        }
        for (i in 1..250) {
            val px = (c.x - n.x * i).toInt(); val py = (c.y - n.y * i).toInt()
            if (px !in 0 until mask.cols() || py !in 0 until mask.rows()) break
            if (mask.get(py, px)[0] == 0.0) { w2 = i.toDouble(); break }
        }
        return w1 + w2
    }

    private fun calculateGrade(d: Double) = when {
        d > 15.0 -> "A"
        d > 12.0 -> "B"
        d > 10.0 -> "C"
        d > 8.0 -> "D"
        d > 5.0 -> "E"
        else -> "F"
    }

    private fun dist(p1: PointF, p2: PointF) = sqrt(((p1.x - p2.x) * (p1.x - p2.x) + (p1.y - p2.y) * (p1.y - p2.y)).toDouble())

    /**
     * 将 Canvas 3 坐标映射到世界 3D 坐标
     * Canvas 3 是 Z=0 平面的透视映射
     */
    private fun mapCanvas3ToWorld3D(p: PointF, z: Double, poseInfo: AlgorithmProcessor.PoseInfo): Point3 {
        val xWorld = (p.x - AlgorithmConfig.PADDING_PX) / AlgorithmConfig.MM_TO_PX_IN_CANVAS_3
        val yWorld = (p.y - AlgorithmConfig.PADDING_PX) / AlgorithmConfig.MM_TO_PX_IN_CANVAS_3
        return Point3(xWorld, yWorld, z)
    }

    /**
     * 世界坐标 -> 相机坐标系
     * Pc = R * Pw + t
     */
    private fun transformWorldToCamera(pw: Point3, poseInfo: AlgorithmProcessor.PoseInfo): Point3 {
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

    private fun dist3D(p1: Point3, p2: Point3) = 
        sqrt((p1.x - p2.x) * (p1.x - p2.x) + (p1.y - p2.y) * (p1.y - p2.y) + (p1.z - p2.z) * (p1.z - p2.z))
}
