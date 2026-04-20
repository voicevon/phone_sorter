package com.example.asparagusclassifier.algorithm

import org.opencv.core.Point
import org.opencv.core.Scalar

/**
 * 算法全局配置
 * 包含所有视觉参数、物理参数和阈值
 */
object AlgorithmConfig {
    // ArUco 标记 ID 配置
    const val ID_TL = 10
    const val ID_TR = 13
    const val ID_BR = 40
    const val ID_BL = 42

    // 物理尺寸基准 (单位: mm)
    const val BOARD_WIDTH_MM = 167.0
    const val BOARD_HEIGHT_MM = 250.0
    const val MM_TO_PX = 10.0 // 标准化分辨率: 10px/mm

    // 标准化画布尺寸
    const val TARGET_WIDTH = 2000
    const val TARGET_HEIGHT = 3000

    // 标准化坐标 (中心点) - 相对于 TARGET_WIDTH/HEIGHT
    val TARGET_TL = Point(150.0, 250.0)
    val TARGET_TR = Point(150.0 + BOARD_WIDTH_MM * MM_TO_PX, 250.0)
    val TARGET_BR = Point(150.0 + BOARD_WIDTH_MM * MM_TO_PX, 250.0 + BOARD_HEIGHT_MM * MM_TO_PX)
    val TARGET_BL = Point(150.0, 250.0 + BOARD_HEIGHT_MM * MM_TO_PX)

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
    val SAMPLING_OFFSETS_MM = listOf(5.0, 10.0, 15.0) // 采样位置（距根部）

    // 标定校验阈值
    const val MAX_RATIO_ERROR = 0.15 // 最大允许透视变形误差 (15%)
}
