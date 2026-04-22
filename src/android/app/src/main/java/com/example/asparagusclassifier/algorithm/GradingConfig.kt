package com.example.asparagusclassifier.algorithm

/**
 * 分级配置 (用户可调)
 */
object GradingConfig {
    // 直径阈值 (单位: mm)
    var thresholdA = 15.0
    var thresholdB = 12.0
    var thresholdC = 10.0
    var thresholdD = 8.0
    var thresholdE = 5.0

    /**
     * 根据直径计算等级
     */
    fun calculateGrade(d: Double): String {
        return when {
            d > thresholdA -> "A"
            d > thresholdB -> "B"
            d > thresholdC -> "C"
            d > thresholdD -> "D"
            d > thresholdE -> "E"
            else -> "F"
        }
    }
}
