package com.example.asparagusclassifier.algorithm

import android.graphics.PointF
import org.opencv.core.Mat
import org.opencv.core.Point
import kotlin.math.sqrt

/**
 * 直径采样器
 * 负责在轴线上进行多点采样，并应用基于 3D 位姿的深度补偿
 */
object DiameterSampler {
    private const val TAG = "DiameterSampler"

    /**
     * 采样结果封装
     */
    data class SampleResult(
        val diameterMm: Double,
        val diameterLines: List<List<PointF>>
    )

    /**
     * 执行多点采样分析
     */
    fun samples(
        mask: Mat,
        orientedAxis: List<PointF>,
        pixelsPerMm: Double,
        poseInfo: PoseEstimator.PoseInfo?
    ): SampleResult {
        val diameterSamples = mutableListOf<Double>()
        val diameterLines = mutableListOf<List<PointF>>()

        if (orientedAxis.size < 2) return SampleResult(0.0, emptyList())

        for (offsetMm in AlgorithmConfig.SAMPLING_OFFSETS_MM) {
            val offsetPx = offsetMm * pixelsPerMm
            val (sampleP, normalV) = findPointAndNormalAtDistance(orientedAxis, offsetPx)
            
            if (sampleP != null && normalV != null) {
                val widthPx = measureWidth(mask, sampleP, normalV)
                
                val sampleDiameterMm = if (poseInfo != null) {
                    computeCompensatedDiameter(sampleP, widthPx, poseInfo)
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
        
        val finalDiameter = if (diameterSamples.isNotEmpty()) diameterSamples.average() else 0.0
        return SampleResult(finalDiameter, diameterLines)
    }

    /**
     * 在路径上根据距离寻找点及其法线方向
     */
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

    /**
     * 沿法线方向测量像素宽度
     */
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

    /**
     * 应用 3D 位姿深度补偿计算物理直径
     * D_mm = D_px * (depth / focal_length)
     */
    private fun computeCompensatedDiameter(sampleP: Point, widthPx: Double, poseInfo: PoseEstimator.PoseInfo): Double {
        val samplePF = PointF(sampleP.x.toFloat(), sampleP.y.toFloat())
        // 假设芦笋中心轴高度约为 10mm (根据物理传送带支架估算)
        val pWorld3D = PoseEstimator.mapCanvas3ToWorld3D(samplePF, 10.0)
        val pCam = PoseEstimator.transformWorldToCamera(pWorld3D, poseInfo)
        val depth = sqrt(pCam.x * pCam.x + pCam.y * pCam.y + pCam.z * pCam.z)
        
        // 提取焦距
        val fx = poseInfo.cameraMatrix.get(0, 0)[0]
        val fy = poseInfo.cameraMatrix.get(1, 1)[0]
        val fAvg = (fx + fy) / 2.0
        
        return (widthPx / fAvg) * depth
    }

    private fun dist(p1: PointF, p2: PointF) = sqrt(((p1.x - p2.x) * (p1.x - p2.x) + (p1.y - p2.y) * (p1.y - p2.y)).toDouble())
}
