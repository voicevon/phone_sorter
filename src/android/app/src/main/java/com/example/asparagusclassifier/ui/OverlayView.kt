package com.example.asparagusclassifier.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.util.Log

data class ArucoMarker(
    val corners: Array<PointF>, // 四个角点
    val id: Int
)

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    
    // 绿色画笔用于芦笋
    private val greenPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    
    private val greenFillPaint = Paint().apply {
        color = Color.argb(128, 0, 255, 0)
        style = Paint.Style.FILL
    }
    
    // 红色画笔用于 ArUco 标记
    private val redPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }
    
    private val textPaint = Paint().apply {
        color = Color.RED
        textSize = 50f
        style = Paint.Style.FILL
        strokeWidth = 3f
    }
    
    // 蓝色画笔用于直径测量线
    private val measurementPaint = Paint().apply {
        color = Color.BLUE
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    
    // 黄色画笔用于尾部标记
    private val tailMarkerPaint = Paint().apply {
        color = Color.YELLOW
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }
    
    private val textBackgroundPaint = Paint().apply {
        color = Color.argb(180, 0, 0, 0)
        style = Paint.Style.FILL
    }
    
    private var asparagusRect: Rect? = null
    private var asparagusContour: List<PointF>? = null
    private var tailPoint: PointF? = null
    private var diameterLine: List<PointF>? = null
    private var arucoMarkers: List<ArucoMarker>? = null
    private var bitmapWidth: Int = 0
    private var bitmapHeight: Int = 0
    
    private val coordinateMatrix = Matrix()

    fun setAsparagusRect(rect: Rect) {
        asparagusRect = rect
        invalidate()
    }

    fun setAsparagusContour(contour: List<PointF>) {
        asparagusContour = contour
        invalidate()
    }

    fun setAsparagusTail(point: PointF?) {
        tailPoint = point
        invalidate()
    }

    fun setDiameterLine(line: List<PointF>?) {
        diameterLine = line
        invalidate()
    }
    
    fun setArucoMarkers(markers: List<ArucoMarker>, bitmapW: Int, bitmapH: Int) {
        arucoMarkers = markers
        bitmapWidth = bitmapW
        bitmapHeight = bitmapH
        updateMatrix()
        invalidate()
    }
    
    fun clearMarkers() {
        arucoMarkers = null
        asparagusRect = null
        asparagusContour = null
        tailPoint = null
        diameterLine = null
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateMatrix()
    }

    private fun updateMatrix() {
        if (bitmapWidth > 0 && bitmapHeight > 0 && width > 0 && height > 0) {
            val viewRect = RectF(0f, 0f, width.toFloat(), height.toFloat())
            val bitmapRect = RectF(0f, 0f, bitmapWidth.toFloat(), bitmapHeight.toFloat())
            
            coordinateMatrix.reset()
            
            val viewAspect = width.toFloat() / height.toFloat()
            val bitmapAspect = bitmapWidth.toFloat() / bitmapHeight.toFloat()
            
            // Check for orientation mismatch (one is landscape, one is portrait)
            // Allow some tolerance for aspect ratios
            val viewIsLandscape = viewAspect > 1.0f
            val bitmapIsLandscape = bitmapAspect > 1.0f
            
            if (viewIsLandscape != bitmapIsLandscape) {
                Log.w("OverlayView", "Orientation Mismatch detected! View=${width}x${height}, Bitmap=${bitmapWidth}x${bitmapHeight}. Applying 90 deg rotation correction.")
                
                // Align centers
                val centerX = width / 2f
                val centerY = height / 2f
                
                // Scale to fit (which might need swapping dimensions effectively)
                // We map BitmapRect to ViewRect, then rotate? 
                // No, if we rotate, the mapping changes.
                
                // Strategy: Map Bitmap to a rotated ViewRect centered at origin?
                // Simpler Strategy: Map Bitmap -> View, then Post Rotate around center.
                coordinateMatrix.setRectToRect(bitmapRect, viewRect, Matrix.ScaleToFit.CENTER)
                coordinateMatrix.postRotate(90f, centerX, centerY)
                
                // After 90 deg rotation, the aspect ratio might be wrong if we just did ScaleToFit.FILL on mismatched rects?
                // Let's use ScaleToFit.CENTER to preserve aspect, then Rotate.
                // But the user said "Red Box is rotated".
                // If we have a mismatch, likely the Bitmap is the UNROTATED source (Portrait) and View is Landscape.
                // So we need to rotate the coordinates 90 degrees to match the Landscape View.
            } else {
                coordinateMatrix.setRectToRect(bitmapRect, viewRect, Matrix.ScaleToFit.FILL)
            }
            
            Log.d("OverlayView", "Matrix update: View=${width}x${height}, Bitmap=${bitmapWidth}x${bitmapHeight}, Matrix=${coordinateMatrix.toShortString()}")
        }
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        

        // 绘制 ArUco 标记（红色四边形 + ID）
        arucoMarkers?.forEach { marker ->
            val path = Path()
            // 映射点
            val srcPoints = FloatArray(marker.corners.size * 2)
            for (i in marker.corners.indices) {
                srcPoints[i * 2] = marker.corners[i].x
                srcPoints[i * 2 + 1] = marker.corners[i].y
            }
            val dstPoints = FloatArray(srcPoints.size)
            coordinateMatrix.mapPoints(dstPoints, srcPoints)

            // 绘制四边形
            path.moveTo(dstPoints[0], dstPoints[1])
            for (i in 1 until marker.corners.size) {
                path.lineTo(dstPoints[i * 2], dstPoints[i * 2 + 1])
            }
            path.close()
            canvas.drawPath(path, redPaint)
            
            // 绘制 ID 文本（在第一个角点上方）
            val idText = "ID: ${marker.id}"
            val textX = dstPoints[0]
            val textY = dstPoints[1] - 10f
            
            // 绘制文本背景
            val textBounds = Rect()
            textPaint.getTextBounds(idText, 0, idText.length, textBounds)
            canvas.drawRect(
                textX,
                textY - textBounds.height() - 10f,
                textX + textBounds.width() + 20f,
                textY + 5f,
                textBackgroundPaint
            )
            
            // 绘制文本
            canvas.drawText(idText, textX + 10f, textY, textPaint)
        }
        
        // 绘制芦笋区域（绿色）
        // 优先绘制轮廓，如果存在
        if (asparagusContour != null && asparagusContour!!.isNotEmpty()) {
            Log.d("OverlayView", "Drawing polygon contour with ${asparagusContour!!.size} points")
            val path = Path()
            val contour = asparagusContour!!
            
            // 映射第一个点
            val firstPoint = floatArrayOf(contour[0].x, contour[0].y)
            coordinateMatrix.mapPoints(firstPoint)
            path.moveTo(firstPoint[0], firstPoint[1])
            
            // 映射其余点
            val pointBuffer = FloatArray(2)
            for (i in 1 until contour.size) {
                pointBuffer[0] = contour[i].x
                pointBuffer[1] = contour[i].y
                coordinateMatrix.mapPoints(pointBuffer)
                path.lineTo(pointBuffer[0], pointBuffer[1])
            }
            path.close()
            
            canvas.drawPath(path, greenFillPaint)
            canvas.drawPath(path, greenPaint)
        } else {
            // 降级显示矩形
            asparagusRect?.let { rect ->
                val rectF = RectF(rect)
                coordinateMatrix.mapRect(rectF)
                
                canvas.drawRect(rectF, greenFillPaint)
                canvas.drawRect(rectF, greenPaint)
                // canvas.drawText("芦笋", rectF.left, rectF.top - 10f, textPaint)
            }
        }
        
        
        // 绘制直径测量线（蓝色）
        if (diameterLine != null && diameterLine!!.size >= 2) {
            val p1 = floatArrayOf(diameterLine!![0].x, diameterLine!![0].y)
            val p2 = floatArrayOf(diameterLine!![1].x, diameterLine!![1].y)
            coordinateMatrix.mapPoints(p1)
            coordinateMatrix.mapPoints(p2)
            
            canvas.drawLine(p1[0], p1[1], p2[0], p2[1], measurementPaint)
        }
        
        // 绘制芦笋头尾标记（紫根位置 - 黄色）
        if (tailPoint != null) {
            val pointBuffer = floatArrayOf(tailPoint!!.x, tailPoint!!.y)
            coordinateMatrix.mapPoints(pointBuffer)
            
            // 绘制黄色空心圆
            val radius = 25f
            canvas.drawCircle(pointBuffer[0], pointBuffer[1], radius, tailMarkerPaint)
        }
    }
}