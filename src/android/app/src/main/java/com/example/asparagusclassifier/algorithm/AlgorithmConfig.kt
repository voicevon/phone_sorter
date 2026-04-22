package com.example.asparagusclassifier.algorithm

import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.MatOfPoint3f
import org.opencv.core.Point3

/**
 * 算法全局配置
 * 包含所有视觉参数、物理参数和阈值
 */
object AlgorithmConfig {
    // ArUco 标记 ID 配置
    const val ID_TL = 10
    const val ID_TR = 13
    const val ID_BL = 40
    const val ID_BR = 42

    // 物理尺寸基准 (单位: mm)
    const val BOARD_WIDTH_MM = 167.0
    const val BOARD_HEIGHT_MM = 250.0
    const val MARKER_SIZE_MM = 30.0 
    
    /**
     * ArUco 标记点的 3D 物理坐标 (16 点模型)
     * 顺序: P0(BL), P1(BR), P2(TR), P3(TL)
     */
    val BOARD_OBJECT_POINTS_16 = MatOfPoint3f(*run {
        val s = MARKER_SIZE_MM / 2.0
        val hw = BOARD_WIDTH_MM / 2.0
        val hh = BOARD_HEIGHT_MM / 2.0
        arrayOf(
            // BL (ID 40) - 中心在 (-hw, -hh)
            Point3(-hw - s, -hh - s, 0.0), Point3(-hw + s, -hh - s, 0.0), 
            Point3(-hw + s, -hh + s, 0.0), Point3(-hw - s, -hh + s, 0.0),
            // BR (ID 42) - 中心在 (hw, -hh)
            Point3(hw - s, -hh - s, 0.0), Point3(hw + s, -hh - s, 0.0), 
            Point3(hw + s, -hh + s, 0.0), Point3(hw - s, -hh + s, 0.0),
            // TR (ID 13) - 中心在 (hw, hh)
            Point3(hw - s, hh - s, 0.0), Point3(hw + s, hh - s, 0.0), 
            Point3(hw + s, hh + s, 0.0), Point3(hw - s, hh + s, 0.0),
            // TL (ID 10) - 中心在 (-hw, hh)
            Point3(-hw - s, hh - s, 0.0), Point3(-hw + s, hh - s, 0.0), 
            Point3(-hw + s, hh + s, 0.0), Point3(-hw - s, hh + s, 0.0)
        )
    })

    /**
     * ArUco 标记点的 3D 物理坐标 (用于 4 点快速 solvePnP)
     */
    val BOARD_OBJECT_POINTS = MatOfPoint3f(
        Point3(-BOARD_WIDTH_MM / 2.0, -BOARD_HEIGHT_MM / 2.0, 0.0),  // BL
        Point3(BOARD_WIDTH_MM / 2.0, -BOARD_HEIGHT_MM / 2.0, 0.0),   // BR
        Point3(BOARD_WIDTH_MM / 2.0, BOARD_HEIGHT_MM / 2.0, 0.0),    // TR
        Point3(-BOARD_WIDTH_MM / 2.0, BOARD_HEIGHT_MM / 2.0, 0.0)    // TL
    )
    // 图像预处理参数
    const val SCAN_MAX_SIDE = 1600 
    
    // HSV 颜色分割阈值
    val LOWER_GREEN = Scalar(25.0, 30.0, 30.0)
    val UPPER_GREEN = Scalar(95.0, 255.0, 255.0)
    val LOWER_PURPLE = Scalar(110.0, 30.0, 30.0)
    val UPPER_PURPLE = Scalar(170.0, 255.0, 255.0)

    // 测量参数
    const val MIN_CONTOUR_AREA = 500.0
    const val DIAMETER_CORRECTION_MM = 1.5 
    val SAMPLING_OFFSETS_MM = listOf(10.0, 15.0, 20.0) 

    const val MAX_RATIO_ERROR = 0.15 
    
    // 质量判定阈值
    const val MAX_STRAIGHTNESS_RMSE_MM = 5.0 // RMSE 超过 5mm 判定为不合格
    const val SCATTERED_HEAD_THRESHOLD = 1.4 // 轮廓复杂度阈值，超过则认为“开花”
}
