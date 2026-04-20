package com.example.asparagusclassifier.algorithm

import android.graphics.PointF
import android.graphics.Rect

/**
 * 算法执行结果数据类
 */
data class AlgorithmResult(
    val success: Boolean,
    val grade: String = "F",
    val diameter: Double = 0.0,
    val rawDiameter: Double = 0.0,
    val length: Double = 0.0,
    val purpleRootPosition: String = "未检测到",
    val asparagusRect: Rect? = null,
    val asparagusContour: List<PointF>? = null, // 芦笋轮廓点
    val tailPoint: PointF? = null, // 芦笋头尾标记（紫根位置）
    val axisPath: List<PointF>? = null, // 曲线轴线路径
    val diameterLine: List<List<PointF>>? = null, // 多条直径测量线 (每条线2个点)
    val arucoCorners: List<Array<PointF>>? = null, // 每个标记的4个角点
    val arucoIds: List<Int>? = null,
    val processedBitmap: android.graphics.Bitmap? = null, // 去畸变后的反馈图
    val error: String? = null
)
