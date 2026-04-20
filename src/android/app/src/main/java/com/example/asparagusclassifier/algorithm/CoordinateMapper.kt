package com.example.asparagusclassifier.algorithm

import android.graphics.PointF
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.imgproc.Imgproc

/**
 * 坐标映射器
 * 负责在原始图像、缩放后的工作空间以及透视变换后的标准化空间之间进行坐标转换
 */
class CoordinateMapper(
    private val scaleFactor: Double,
    private val perspectiveMat: Mat? = null
) {
    private val invScale = 1.0 / scaleFactor
    private var invPerspectiveMat: Mat? = null

    init {
        perspectiveMat?.let {
            invPerspectiveMat = Mat()
            Core.invert(it, invPerspectiveMat)
        }
    }

    /**
     * 将标准化空间 (Warped Space) 的点映射回物理工作空间 (Canvas 2)
     */
    fun mapWarpedToWork(p: PointF): PointF {
        if (invPerspectiveMat == null) return p
        val src = MatOfPoint2f(Point(p.x.toDouble(), p.y.toDouble()))
        val dst = MatOfPoint2f()
        Core.perspectiveTransform(src, dst, invPerspectiveMat)
        val res = dst.toArray()[0]
        src.release()
        dst.release()
        return PointF(res.x.toFloat(), res.y.toFloat())
    }

    /**
     * 将标准化空间 (Warped Space) 的点映射回原始图像坐标系
     */
    fun mapWarpedToOriginal(p: PointF): PointF {
        val workPoint = mapWarpedToWork(p)
        return PointF((workPoint.x * invScale).toFloat(), (workPoint.y * invScale).toFloat())
    }

    /**
     * 将缩放后的工作空间 (Work Space) 的点映射回原始图像坐标系
     */
    fun mapWorkToOriginal(p: PointF): PointF {
        return PointF((p.x * invScale).toFloat(), (p.y * invScale).toFloat())
    }

    /**
     * 将原始图像坐标系的点映射回标准化空间 (Warped Space)
     */
    fun mapOriginalToWarped(p: PointF): PointF {
        val workPoint = Point(p.x * scaleFactor, p.y * scaleFactor)
        
        return if (perspectiveMat != null) {
            val src = MatOfPoint2f(workPoint)
            val dst = MatOfPoint2f()
            Core.perspectiveTransform(src, dst, perspectiveMat)
            val res = dst.toArray()[0]
            src.release()
            dst.release()
            PointF(res.x.toFloat(), res.y.toFloat())
        } else {
            PointF(workPoint.x.toFloat(), workPoint.y.toFloat())
        }
    }

    fun release() {
        invPerspectiveMat?.release()
    }
}
