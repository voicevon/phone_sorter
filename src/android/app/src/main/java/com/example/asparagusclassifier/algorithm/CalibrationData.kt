package com.example.asparagusclassifier.algorithm

/**
 * 封装相机校准数据，包括内参和畸变参数
 */
data class CalibrationData(
    val intrinsic: FloatArray,
    val distortion: FloatArray,
    val sensorWidth: Int,
    val sensorHeight: Int
) {
    /**
     * 校验内参合法性：如果焦距 (fx, fy) 均为 0，则视为无效
     */
    fun isValid(): Boolean {
        return intrinsic.size >= 2 && (intrinsic[0] != 0f || intrinsic[1] != 0f)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CalibrationData) return false
        if (!intrinsic.contentEquals(other.intrinsic)) return false
        if (!distortion.contentEquals(other.distortion)) return false
        if (sensorWidth != other.sensorWidth) return false
        if (sensorHeight != other.sensorHeight) return false
        return true
    }

    override fun hashCode(): Int {
        var result = intrinsic.contentHashCode()
        result = 31 * result + distortion.contentHashCode()
        result = 31 * result + sensorWidth
        result = 31 * result + sensorHeight
        return result
    }
}
