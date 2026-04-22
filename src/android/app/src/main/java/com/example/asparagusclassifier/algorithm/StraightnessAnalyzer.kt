package com.example.asparagusclassifier.algorithm

import android.graphics.PointF
import kotlin.math.sqrt

/**
 * 直线度分析器
 * 专门处理轴线点的直线拟合 (Total Least Squares) 与偏离度计算
 */
object StraightnessAnalyzer {

    /**
     * 计算结果封装
     */
    data class StraightnessResult(
        val rmse: Double,       // 均方根误差 (单位：像素或mm，取决于输入)
        val endpoints: List<PointF> // 拟合基准线的起止点
    )

    /**
     * 计算一组点的直线度 (RMSE) 及其基准拟合线
     * 使用最小二乘法 (Total Least Squares) 拟合 2D 直线
     */
    fun analyze(points: List<PointF>): StraightnessResult {
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
        
        // 求解协方差矩阵的特征向量
        val det = sxx - syy
        val distValue = sqrt(det * det + 4 * sxy * sxy)
        
        val vx: Double
        val vy: Double
        
        if (distValue < 1e-10) {
            // 所有点重合或极度对称，无唯一基准线
            return StraightnessResult(0.0, emptyList())
        } else {
            // 最大特征值对应的特征向量方向即为主轴方向
            val mag = sqrt((det + distValue) * (det + distValue) + 4 * sxy * sxy)
            vx = (det + distValue) / mag
            vy = (2.0 * sxy) / mag
        }
        
        // 直线的法向量 (nx, ny)
        val nx = -vy
        val ny = vx
        
        var sumDistSq = 0.0
        val projections = mutableListOf<Double>()
        points.forEach {
            val dx = it.x - avgX; val dy = it.y - avgY
            // 点到直线的正交距离
            val d = dx * nx + dy * ny
            sumDistSq += d * d
            // 沿直线方向的投影位置，用于生成基准线段
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
}
