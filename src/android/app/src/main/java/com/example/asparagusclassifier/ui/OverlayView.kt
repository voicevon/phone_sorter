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
    
    // 蓝色点划线画笔用于轴线
    private val axisPaint = Paint().apply {
        color = Color.BLUE
        style = Paint.Style.STROKE
        strokeWidth = 6f
        pathEffect = DashPathEffect(floatArrayOf(20f, 10f, 5f, 10f), 0f)
    }
    
    private val textBackgroundPaint = Paint().apply {
        color = Color.argb(180, 0, 0, 0)
        style = Paint.Style.FILL
    }
    
    private var asparagusRect: Rect? = null
    private var asparagusContour: List<PointF>? = null
    private var tailPoint: PointF? = null
    private var axisPath: List<PointF>? = null
    private var diameterLine: List<PointF>? = null
    private var arucoMarkers: List<ArucoMarker>? = null
    private var bitmapWidth: Int = 0
    private var bitmapHeight: Int = 0
    // TextureView 在屏幕上的实际显示区域（相对于 OverlayView 自身的坐标）
    private var displayRect: RectF? = null
    
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

    fun setAxisPath(path: List<PointF>?) {
        axisPath = path
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
        axisPath = null
        diameterLine = null
        invalidate()
    }

    /**
     * 设置 TextureView 在屏幕上的实际显示区域，这样叠加层才能将
     * Bitmap 坐标准确映射到 TextureView 的屏幕位置上。
     * @param left   TextureView 左边相对于 OverlayView 的 X 坐标
     * @param top    TextureView 顶边相对于 OverlayView 的 Y 坐标
     * @param right  TextureView 右边
     * @param bottom TextureView 底边
     */
    fun setDisplayRect(left: Float, top: Float, right: Float, bottom: Float) {
        displayRect = RectF(left, top, right, bottom)
        updateMatrix()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateMatrix()
    }

    private fun updateMatrix() {
        if (bitmapWidth > 0 && bitmapHeight > 0 && width > 0 && height > 0) {
            val bitmapRect = RectF(0f, 0f, bitmapWidth.toFloat(), bitmapHeight.toFloat())

            // 如果外部传入了 TextureView 的精确区域，就直接映射到该区域；
            // 否则降级为 CENTER 模式展示（保持宽高比）
            val targetRect = displayRect ?: RectF(0f, 0f, width.toFloat(), height.toFloat())

            coordinateMatrix.reset()
            // CENTER 模式：保持宽高比，居中映射，不拉伸变形
            coordinateMatrix.setRectToRect(bitmapRect, targetRect, Matrix.ScaleToFit.CENTER)

            Log.d("OverlayView", "Matrix update: View=${width}x${height}, Bitmap=${bitmapWidth}x${bitmapHeight}, Target=$targetRect")
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
        
        // 绘制轴线（蓝色点划线）
        if (axisPath != null && axisPath!!.size >= 2) {
            val path = Path()
            val points = axisPath!!
            val p0 = floatArrayOf(points[0].x, points[0].y)
            coordinateMatrix.mapPoints(p0)
            path.moveTo(p0[0], p0[1])
            val buffer = FloatArray(2)
            for (i in 1 until points.size) {
                buffer[0] = points[i].x
                buffer[1] = points[i].y
                coordinateMatrix.mapPoints(buffer)
                path.lineTo(buffer[0], buffer[1])
            }
            canvas.drawPath(path, axisPaint)
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