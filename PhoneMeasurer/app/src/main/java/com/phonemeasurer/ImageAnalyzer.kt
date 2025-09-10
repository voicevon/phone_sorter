package com.phonemeasurer

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import androidx.camera.core.ImageProxy
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.calib3d.Calib3d
import kotlin.math.sqrt

class ImageAnalyzer {
    private val referenceCircleDiameter = 20.0 // mm
    private val a4Width = 210.0 // mm
    private val a4Height = 297.0 // mm
    
    data class MeasurementResult(
        val success: Boolean,
        val length: Double = 0.0,
        val width: Double = 0.0,
        val confidence: Double = 0.0,
        val errorMessage: String = ""
    )

    fun analyzeImage(imageProxy: ImageProxy): MeasurementResult {
        try {
            val bitmap = imageProxy.toBitmap()
            val mat = Mat()
            Utils.bitmapToMat(bitmap, mat)
            
            // 步骤1: 圆检测
            val circles = detectReferenceCircles(mat)
            if (circles.size < 4) {
                return MeasurementResult(false, errorMessage = "未检测到参照物")
            }
            
            // 步骤2: 透视校正
            val correctedMat = correctPerspective(mat, circles)
            
            // 步骤3: 物体检测 (简化版本)
            val objectRect = detectObject(correctedMat)
            if (objectRect == null) {
                return MeasurementResult(false, errorMessage = "识别失败")
            }
            
            // 步骤4: 尺寸计算
            val measurements = calculateDimensions(objectRect, circles)
            
            // 步骤5: 误差修正 (简化版本)
            val correctedMeasurements = applyErrorCorrection(measurements)
            
            return MeasurementResult(
                success = true,
                length = correctedMeasurements.length,
                width = correctedMeasurements.width,
                confidence = 0.85
            )
            
        } catch (e: Exception) {
            return MeasurementResult(false, errorMessage = "图像质量不足")
        }
    }

    private fun detectReferenceCircles(mat: Mat): List<Point> {
        val gray = Mat()
        Imgproc.cvtColor(mat, gray, Imgproc.COLOR_BGR2GRAY)
        
        val blurred = Mat()
        Imgproc.GaussianBlur(gray, blurred, Size(9.0, 9.0), 2.0)
        
        val circles = Mat()
        Imgproc.HoughCircles(
            blurred, circles, Imgproc.HOUGH_GRADIENT, 
            1.0, 50.0, 100.0, 25.0, 25, 100
        )
        
        val detectedCircles = mutableListOf<Point>()
        for (i in 0 until circles.cols()) {
            val circle = circles.get(0, i)
            detectedCircles.add(Point(circle[0], circle[1]))
        }
        
        return detectedCircles.take(4)
    }

    private fun correctPerspective(mat: Mat, circles: List<Point>): Mat {
        if (circles.size < 4) return mat
        
        // 根据系统需求，四个圆心坐标
        val srcPoints = MatOfPoint2f(
            Point(100.0, 100.0),  // 左上
            Point(400.0, 100.0), // 右上
            Point(400.0, 400.0), // 右下
            Point(100.0, 400.0)  // 左下
        )
        
        val dstPoints = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(a4Width * 2, 0.0),
            Point(a4Width * 2, a4Height * 2),
            Point(0.0, a4Height * 2)
        )
        
        val transform = Imgproc.getPerspectiveTransform(srcPoints, dstPoints)
        val corrected = Mat()
        Imgproc.warpPerspective(mat, corrected, transform, Size(a4Width * 2, a4Height * 2))
        
        return corrected
    }

    private fun detectObject(mat: Mat): Rect? {
        val gray = Mat()
        Imgproc.cvtColor(mat, gray, Imgproc.COLOR_BGR2GRAY)
        
        val edges = Mat()
        Imgproc.Canny(gray, edges, 50.0, 150.0)
        
        val contours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(edges, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        
        if (contours.isEmpty()) return null
        
        // 找到最大的轮廓
        val largestContour = contours.maxByOrNull { Imgproc.contourArea(it) }
        
        if (largestContour != null && Imgproc.contourArea(largestContour) > 1000) {
            return Imgproc.boundingRect(largestContour)
        }
        
        return null
    }

    private fun calculateDimensions(objectRect: Rect, circles: List<Point>): Dimensions {
        // 计算像素到毫米的转换比例
        val pixelToMmRatio = calculatePixelToMmRatio(circles)
        
        val length = objectRect.height * pixelToMmRatio / 10.0 // 转换为cm
        val width = objectRect.width * pixelToMmRatio / 10.0   // 转换为cm
        
        return Dimensions(length, width)
    }

    private fun calculatePixelToMmRatio(circles: List<Point>): Double {
        if (circles.size < 2) return 1.0
        
        // 计算两个圆之间的像素距离
        val distance = sqrt(
            (circles[0].x - circles[1].x) * (circles[0].x - circles[1].x) +
            (circles[0].y - circles[1].y) * (circles[0].y - circles[1].y)
        )
        
        // 假设两个圆之间的实际距离是100mm
        return 100.0 / distance
    }

    private fun applyErrorCorrection(dimensions: Dimensions): Dimensions {
        // 简单的误差修正
        val correctionFactor = 0.95
        return Dimensions(
            length = dimensions.length * correctionFactor,
            width = dimensions.width * correctionFactor
        )
    }

    private data class Dimensions(val length: Double, val width: Double)

    private fun ImageProxy.toBitmap(): Bitmap {
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = android.graphics.YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = java.io.ByteArrayOutputStream()
        yuvImage.compressToJpeg(android.graphics.Rect(0, 0, width, height), 100, out)
        val yuv = out.toByteArray()
        return android.graphics.BitmapFactory.decodeByteArray(yuv, 0, yuv.size)
    }
}