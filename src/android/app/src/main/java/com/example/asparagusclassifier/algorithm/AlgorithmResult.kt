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
    val executionTimeMs: Long = 0,
    val straightnessOverall: Double = 0.0,
    val straightnessHead: Double = 0.0,
    val straightnessTail: Double = 0.0,
    val baselineOverall: List<PointF>? = null, // 拟合基准线
    val baselineHead: List<PointF>? = null,
    val baselineTail: List<PointF>? = null,
    val diagStrips: List<android.graphics.Bitmap>? = null, // 头、中、尾三段诊断切片图
    val processedBitmap: android.graphics.Bitmap? = null, // 当前视图位图 (C1/C2/C3)
    val canvas1Bitmap: android.graphics.Bitmap? = null, // 原始
    val canvas2Bitmap: android.graphics.Bitmap? = null, // 去畸变
    val canvas3Bitmap: android.graphics.Bitmap? = null, // 标准
    val viewMode: Int = 3, // 1: Raw, 2: Undistorted, 3: Analysis
    
    // V2 3D 位姿诊断字段
    val poseDistanceMm: Double = 0.0, // 相机到标定板中心的垂直距离
    val tiltAngle: Double = 0.0,      // 相机相对于标定板的倾斜角 (Degree)
    
    val cameraPosWorld: DoubleArray? = null, // [X, Y, Z] in mm
    val headPosWorld: DoubleArray? = null,
    val tailPosWorld: DoubleArray? = null,
    
    val axis3DPoints: List<android.graphics.PointF>? = null, // [Origin, X, Y, Z] projected points
    
    val error: String? = null
)
