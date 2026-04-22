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
    const val MARKER_SIZE_MM = 30.0 // 假设 ArUco 标记边长为 30mm
    const val MM_TO_PX_IN_CANVAS_3 = 10.0 // 标准化分辨率: 10px/mm (仅限画布3)

    // ArUco 标记点的 3D 物理坐标 (16 点模型：4 个标记 * 每个标记 4 个角点)
    // 顺序: TL(4点), TR(4点), BR(4点), BL(4点)
    val BOARD_OBJECT_POINTS_16 = MatOfPoint3f(*run {
        val s = MARKER_SIZE_MM / 2.0
        val hw = BOARD_WIDTH_MM / 2.0
        val hh = BOARD_HEIGHT_MM / 2.0
        arrayOf(
            // TL (ID 10)
            Point3(-hw - s, hh + s, 0.0), Point3(-hw + s, hh + s, 0.0), 
            Point3(-hw + s, hh - s, 0.0), Point3(-hw - s, hh - s, 0.0),
            // TR (ID 13)
            Point3(hw - s, hh + s, 0.0), Point3(hw + s, hh + s, 0.0), 
            Point3(hw + s, hh - s, 0.0), Point3(hw - s, hh - s, 0.0),
            // BR (ID 42)
            Point3(hw - s, -hh + s, 0.0), Point3(hw + s, -hh + s, 0.0), 
            Point3(hw + s, -hh - s, 0.0), Point3(hw - s, -hh - s, 0.0),
            // BL (ID 40)
            Point3(-hw - s, -hh + s, 0.0), Point3(-hw + s, -hh + s, 0.0), 
            Point3(-hw + s, -hh - s, 0.0), Point3(-hw - s, -hh - s, 0.0)
        )
    })

    // ArUco 标记点的 3D 物理坐标 (用于 solvePnP)
    // 顺序与 AlgorithmProcessor 中的 srcPoints 保持一致: TL, TR, BR, BL
    val BOARD_OBJECT_POINTS = MatOfPoint3f(
        org.opencv.core.Point3(-BOARD_WIDTH_MM / 2.0, BOARD_HEIGHT_MM / 2.0, 0.0),  // TL
        org.opencv.core.Point3(BOARD_WIDTH_MM / 2.0, BOARD_HEIGHT_MM / 2.0, 0.0),   // TR
        org.opencv.core.Point3(BOARD_WIDTH_MM / 2.0, -BOARD_HEIGHT_MM / 2.0, 0.0),  // BR
        org.opencv.core.Point3(-BOARD_WIDTH_MM / 2.0, -BOARD_HEIGHT_MM / 2.0, 0.0)  // BL
    )

    // 标准化画布配置 (10px/mm)
    const val PADDING_PX = 50.0 // 边缘留白 5mm，防止标记被裁切
    
    const val TARGET_WIDTH = 1770  // (167.0 * 10) + 100
    const val TARGET_HEIGHT = 2600 // (250.0 * 10) + 100

    // 标准化坐标 (映射 4 个标记中心点，保持 5mm 边距)
    val TARGET_TL = Point(PADDING_PX, PADDING_PX)
    val TARGET_TR = Point(TARGET_WIDTH - PADDING_PX, PADDING_PX)
    val TARGET_BR = Point(TARGET_WIDTH - PADDING_PX, TARGET_HEIGHT - PADDING_PX)
    val TARGET_BL = Point(PADDING_PX, TARGET_HEIGHT - PADDING_PX)

    // 图像预处理参数
    const val SCAN_MAX_SIDE = 1600 // 工作分辨率上限
    
    // HSV 颜色分割阈值 (绿色芦笋)
    val LOWER_GREEN = Scalar(25.0, 30.0, 30.0)
    val UPPER_GREEN = Scalar(95.0, 255.0, 255.0)
    
    // HSV 颜色分割阈值 (紫根)
    val LOWER_PURPLE = Scalar(110.0, 30.0, 30.0)
    val UPPER_PURPLE = Scalar(170.0, 255.0, 255.0)

    // 测量参数
    const val MIN_CONTOUR_AREA = 500.0
    const val DIAMETER_CORRECTION_MM = 1.5 // 直径修正值（补偿边缘模糊）
    val SAMPLING_OFFSETS_MM = listOf(10.0, 15.0, 20.0) // 采样位置（距根部距离增加 5mm，即 10, 15, 20mm）

    // 标定校验阈值
    const val MAX_RATIO_ERROR = 0.15 // 最大允许透视变形误差 (15%)
}
