package com.example.asparagusclassifier.bluetooth

/**
 * 芦笋分级结果到分拣物理槽位映射器
 */
object SorterTargetMapper {
    /**
     * 将等级映射为分拣机 Target ID (1-8)
     */
    fun mapGradeToTargetId(grade: String): Int {
        return when (grade.uppercase()) {
            "A" -> 1  // 特大级 -> 槽位 1 (M0 电机剔除)
            "B" -> 2  // 大级   -> 槽位 2 (M1 电机剔除)
            "C" -> 3  // 中级   -> 槽位 3 (M2 电机剔除)
            "D" -> 4  // 小级   -> 槽位 4 (M3 电机剔除)
            "E" -> 5  // 特小级 -> 槽位 5 (M4 电机剔除)
            "F" -> 8  // 不合格/次品 -> 槽位 8 (M7 电机剔除，用作次品收集)
            else -> 0 // 哨兵值：直接通过，不剔除
        }
    }
}
