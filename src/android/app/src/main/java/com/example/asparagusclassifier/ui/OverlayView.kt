package com.example.asparagusclassifier.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.util.Log

data class ArucoMarker(
    val corners: Array<PointF>,
    val id: Int
)

/**
 * 芦笋分析结果渲染层 (重构版)
 * 采用分层渲染逻辑，方便后期维护与诊断开关
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    
    // --- 画笔定义 (保持原样以维持视觉一致性) ---
    private val greenPaint = Paint().apply { color = Color.GREEN; style = Paint.Style.STROKE; strokeWidth = 5f }
    private val greenFillPaint = Paint().apply { color = Color.argb(128, 0, 255, 0); style = Paint.Style.FILL }
    private val redPaint = Paint().apply { color = Color.RED; style = Paint.Style.STROKE; strokeWidth = 12f; strokeCap = Paint.Cap.ROUND }
    private val redFillPaint = Paint().apply { color = Color.argb(80, 255, 0, 0); style = Paint.Style.FILL }
    private val textPaint = Paint().apply { color = Color.RED; textSize = 50f; style = Paint.Style.FILL; strokeWidth = 3f }
    private val measurementPaint = Paint().apply { color = Color.BLUE; style = Paint.Style.STROKE; strokeWidth = 5f }
    private val tailMarkerPaint = Paint().apply { color = Color.YELLOW; style = Paint.Style.STROKE; strokeWidth = 8f }
    private val axisPaint = Paint().apply { color = Color.BLUE; style = Paint.Style.STROKE; strokeWidth = 6f; pathEffect = DashPathEffect(floatArrayOf(20f, 10f, 5f, 10f), 0f) }
    private val axisXPaint = Paint().apply { color = Color.RED; strokeWidth = 10f; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val axisYPaint = Paint().apply { color = Color.GREEN; strokeWidth = 10f; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val axisZPaint = Paint().apply { color = Color.BLUE; strokeWidth = 10f; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val baselinePaint = Paint().apply { color = Color.RED; style = Paint.Style.STROKE; strokeWidth = 3f; alpha = 160; pathEffect = DashPathEffect(floatArrayOf(15f, 15f), 0f) }

    // --- 数据存储 ---
    private var asparagusContour: List<PointF>? = null
    private var tailPoint: PointF? = null
    private var axisPath: List<PointF>? = null
    private var diameterLines: List<List<PointF>>? = null
    private var baselineOverall: List<PointF>? = null
    private var baselineHead: List<PointF>? = null
    private var baselineTail: List<PointF>? = null
    private var arucoMarkers: List<ArucoMarker>? = null
    private var axis3DPoints: List<PointF>? = null
    private var markerAxes: Map<Int, List<PointF>>? = null
    private var bitmapWidth: Int = 0
    private var bitmapHeight: Int = 0
    private var backgroundBitmap: Bitmap? = null
    private val coordinateMatrix = Matrix()

    // --- 外部接口 ---
    fun setAsparagusContour(contour: List<PointF>) { asparagusContour = contour; invalidate() }
    fun setAsparagusTail(point: PointF?) { tailPoint = point; invalidate() }
    fun setDiameterLines(lines: List<List<PointF>>?) { diameterLines = lines; invalidate() }
    fun setAxisPath(path: List<PointF>?) { axisPath = path; invalidate() }
    fun setBaselines(overall: List<PointF>?, head: List<PointF>?, tail: List<PointF>?) {
        baselineOverall = overall; baselineHead = head; baselineTail = tail; invalidate()
    }
    fun setArucoMarkers(markers: List<ArucoMarker>, bitmapW: Int, bitmapH: Int, sensorRot: Int = 0) {
        arucoMarkers = markers; bitmapWidth = bitmapW; bitmapHeight = bitmapH; updateMatrix(); invalidate()
    }
    fun setAxis3D(points: List<PointF>?) { axis3DPoints = points; invalidate() }
    fun setMarkerAxes(axes: Map<Int, List<PointF>>?) { markerAxes = axes; invalidate() }
    fun setBackgroundBitmap(bitmap: Bitmap?) { backgroundBitmap = bitmap; invalidate() }
    
    fun setDisplayRect() {
        updateMatrix(); invalidate()
    }

    fun clearMarkers() {
        arucoMarkers = null; asparagusContour = null; tailPoint = null; axisPath = null
        diameterLines = null; baselineOverall = null; baselineHead = null; baselineTail = null
        axis3DPoints = null; markerAxes = null; backgroundBitmap = null
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateMatrix()
    }

    private fun updateMatrix() {
        if (bitmapWidth > 0 && bitmapHeight > 0 && width > 0 && height > 0) {
            val bitmapRect = RectF(0f, 0f, bitmapWidth.toFloat(), bitmapHeight.toFloat())
            val viewRect = RectF(0f, 0f, width.toFloat(), height.toFloat())
            coordinateMatrix.reset()
            coordinateMatrix.setRectToRect(bitmapRect, viewRect, Matrix.ScaleToFit.CENTER)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // 层级 1: 背景
        backgroundBitmap?.let { canvas.drawBitmap(it, coordinateMatrix, null) }

        // 层级 2: ArUco 标记与位姿
        drawArucoMarkers(canvas)
        drawGlobalAxis(canvas)
        drawMarkerAxes(canvas)

        // 层级 3: 芦笋轮廓与测量
        drawAsparagusContour(canvas)
        drawAxisPath(canvas)
        drawDiameterLines(canvas)
        drawTailPoint(canvas)

        // 层级 4: 拟合基准线 (诊断)
        drawLineSegment(baselineOverall, canvas, baselinePaint)
        drawLineSegment(baselineHead, canvas, baselinePaint)
        drawLineSegment(baselineTail, canvas, baselinePaint)
    }

    private fun drawArucoMarkers(canvas: Canvas) {
        arucoMarkers?.forEach { marker ->
            val src = FloatArray(8) { i -> if (i % 2 == 0) marker.corners[i/2].x else marker.corners[i/2].y }
            val dst = FloatArray(8)
            coordinateMatrix.mapPoints(dst, src)
            val path = Path().apply {
                moveTo(dst[0], dst[1])
                lineTo(dst[2], dst[3]); lineTo(dst[4], dst[5]); lineTo(dst[6], dst[7]); close()
            }
            canvas.drawPath(path, redFillPaint)
            canvas.drawPath(path, redPaint)
        }
    }

    private fun drawGlobalAxis(canvas: Canvas) {
        axis3DPoints?.let { pts ->
            if (pts.size >= 4) {
                val p0 = mapPoint(pts[0]); val pX = mapPoint(pts[1])
                val pY = mapPoint(pts[2]); val pZ = mapPoint(pts[3])
                canvas.drawLine(p0.x, p0.y, pX.x, pX.y, axisXPaint)
                canvas.drawLine(p0.x, p0.y, pY.x, pY.y, axisYPaint)
                canvas.drawLine(p0.x, p0.y, pZ.x, pZ.y, axisZPaint)
            }
        }
    }

    private fun drawMarkerAxes(canvas: Canvas) {
        markerAxes?.forEach { (id, pts) ->
            if (pts.size >= 4) {
                val p0 = mapPoint(pts[0]); val pX = mapPoint(pts[1])
                val pY = mapPoint(pts[2]); val pZ = mapPoint(pts[3])
                canvas.drawLine(p0.x, p0.y, pX.x, pX.y, Paint(axisXPaint).apply { strokeWidth = 5f })
                canvas.drawLine(p0.x, p0.y, pY.x, pY.y, Paint(axisYPaint).apply { strokeWidth = 5f })
                canvas.drawLine(p0.x, p0.y, pZ.x, pZ.y, Paint(axisZPaint).apply { strokeWidth = 5f })
                canvas.drawText("ID:$id", p0.x + 10, p0.y - 10, textPaint.apply { textSize = 30f })
            }
        }
    }

    private fun drawAsparagusContour(canvas: Canvas) {
        asparagusContour?.takeIf { it.isNotEmpty() }?.let { contour ->
            val path = Path()
            val p0 = mapPoint(contour[0])
            path.moveTo(p0.x, p0.y)
            for (i in 1 until contour.size) {
                val p = mapPoint(contour[i])
                path.lineTo(p.x, p.y)
            }
            path.close()
            canvas.drawPath(path, greenFillPaint)
            canvas.drawPath(path, greenPaint)
        }
    }

    private fun drawAxisPath(canvas: Canvas) {
        axisPath?.takeIf { it.size >= 2 }?.let { points ->
            val path = Path()
            val p0 = mapPoint(points[0])
            path.moveTo(p0.x, p0.y)
            for (i in 1 until points.size) {
                val p = mapPoint(points[i])
                path.lineTo(p.x, p.y)
            }
            canvas.drawPath(path, axisPaint)
        }
    }

    private fun drawDiameterLines(canvas: Canvas) {
        diameterLines?.forEach { line ->
            if (line.size >= 2) {
                val p1 = mapPoint(line[0]); val p2 = mapPoint(line[1])
                canvas.drawLine(p1.x, p1.y, p2.x, p2.y, measurementPaint)
            }
        }
    }

    private fun drawTailPoint(canvas: Canvas) {
        tailPoint?.let {
            val p = mapPoint(it)
            canvas.drawCircle(p.x, p.y, 25f, tailMarkerPaint)
        }
    }

    private fun drawLineSegment(points: List<PointF>?, canvas: Canvas, paint: Paint) {
        if (points != null && points.size >= 2) {
            val p1 = mapPoint(points[0]); val p2 = mapPoint(points[1])
            canvas.drawLine(p1.x, p1.y, p2.x, p2.y, paint)
        }
    }

    private fun mapPoint(p: PointF): PointF {
        val pts = floatArrayOf(p.x, p.y)
        coordinateMatrix.mapPoints(pts)
        return PointF(pts[0], pts[1])
    }
}