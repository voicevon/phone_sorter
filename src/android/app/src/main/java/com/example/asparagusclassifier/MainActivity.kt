package com.example.asparagusclassifier

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.asparagusclassifier.camera.CameraManager
import com.example.asparagusclassifier.algorithm.AlgorithmProcessor
import com.example.asparagusclassifier.algorithm.AlgorithmResult
import com.example.asparagusclassifier.ui.OverlayView
import org.opencv.android.OpenCVLoader
import android.util.Log
import android.view.Menu
import android.view.MenuItem

class MainActivity : AppCompatActivity(), CameraManager.OnSizeInfoListener {
    
    private lateinit var cameraManager: CameraManager
    private lateinit var textureView: TextureView
    private lateinit var overlayView: OverlayView
    private lateinit var btnCapture: Button
    private lateinit var tvResult: TextView
    
    
    private val CAMERA_PERMISSION_CODE = 100
    private val TAG = "AsparagusClassifier"
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        if (!OpenCVLoader.initDebug()) {
            Log.e(TAG, "OpenCV 初始化失败！")
        } else {
            Log.d(TAG, "OpenCV 初始化成功")
        }
        
        setContentView(R.layout.activity_main)
        
        textureView = findViewById(R.id.textureView)
        overlayView = findViewById(R.id.overlayView)
        cameraManager = CameraManager(this, textureView)
        
        btnCapture = findViewById(R.id.btnCapture)
        tvResult = findViewById(R.id.tvResult)
        
        cameraManager.setOnSizeInfoListener(this)
        
        btnCapture.setOnClickListener {
            captureAndProcess()
        }
        
        checkCameraPermission()
    }
    
    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE)
        } else {
            // startCamera 内部会等待 Surface 可用
            cameraManager.startCamera()
        }
    }
    
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                cameraManager.startCamera()
            }
        }
    }
    
    override fun onSizeInfoReceived(cameraWidth: Int, cameraHeight: Int, cameraRatio: Float, 
                                   previewWidth: Int, previewHeight: Int, previewRatio: Float) {
        // UI 更新必须在主线程
        runOnUiThread {
            Log.d("DiagCamera", "TextureView 状态: Available=${textureView.isAvailable}, HardwareAccelerated=${textureView.isHardwareAccelerated}")
            val text = "版本: V3.1 - 深度诊断版\n" +
                       "状态: 算法就绪\n" +
                       "相机输出: ${cameraWidth}x${cameraHeight}\n" +
                       "实际预览: ${textureView.width}x${textureView.height}\n" +
                       "比例: ${"%.2f".format(previewRatio)}"
            
            tvResult.text = text
            tvResult.visibility = View.VISIBLE
        }
    }
    
    private var lastBitmap: Bitmap? = null
    
    private fun captureAndProcess() {
        Log.i("MainActivity", "开始分析按钮被点击")
        
        // 显示 Toast 提示
        android.widget.Toast.makeText(this, "正在分析，请稍候...", android.widget.Toast.LENGTH_SHORT).show()
        
        // 更新文本显示
        tvResult.text = "正在分析，请稍候..."
        tvResult.visibility = View.VISIBLE
        
        val bitmap = textureView.getBitmap()
        if (bitmap != null) {
            lastBitmap = bitmap
            Log.i("MainActivity", "Bitmap尺寸: ${bitmap.width}x${bitmap.height}")
            Log.i("MainActivity", "TextureView尺寸: ${textureView.width}x${textureView.height}")
            Log.i("MainActivity", "OverlayView尺寸: ${overlayView.width}x${overlayView.height}")
            
            Log.i("MainActivity", "OverlayView尺寸: ${overlayView.width}x${overlayView.height}")
            
            val result = AlgorithmProcessor.processImage(bitmap)
            displayResult(result)
        } else {
            Log.e("MainActivity", "无法获取图像")
            tvResult.text = "错误: 无法获取图像"
            android.widget.Toast.makeText(this, "错误: 无法获取图像", android.widget.Toast.LENGTH_LONG).show()
        }
    }
    
    
    private fun displayResult(result: AlgorithmResult) {
        val modeText = "模式: 芦笋分级\n等级: ${result.grade}\n直径: ${result.diameter} mm\n长度: ${result.length} mm"
    
        val text = "$modeText\n" +
                   "ArUco 标记: ${result.arucoIds?.size ?: 0} 个\n" +
                   "Bitmap: ${lastBitmap?.width}x${lastBitmap?.height}\n" +
                   "TextureView: ${textureView.width}x${textureView.height}\n" +
                   "结果: ${if (result.success) "识别成功" else "识别失败: ${result.error}"}"
        
        tvResult.text = text
        tvResult.visibility = View.VISIBLE
        
        // 显示 ArUco 标记（四边形）- 使用 TextureView 尺寸而非 bitmap 尺寸
        if (result.arucoCorners != null && result.arucoIds != null && lastBitmap != null) {
            val markers = result.arucoCorners.zip(result.arucoIds).map { (corners, id) ->
                com.example.asparagusclassifier.ui.ArucoMarker(corners, id)
            }
            Log.d(TAG, "设置标记: Bitmap尺寸=${lastBitmap!!.width}x${lastBitmap!!.height}, View尺寸=${overlayView.width}x${overlayView.height}")
            // 关键修改：使用 Bitmap 的实际尺寸，由 OverlayView 负责映射到 View 尺寸
            overlayView.setArucoMarkers(markers, lastBitmap!!.width, lastBitmap!!.height)
            overlayView.visibility = View.VISIBLE
            
            Log.i("MainActivity", "设置标记完成: 使用Bitmap尺寸 ${lastBitmap!!.width}x${lastBitmap!!.height}")
        }
        
        // 显示芦笋区域
        if (result.asparagusContour != null) {
            overlayView.setAsparagusContour(result.asparagusContour)
            // 设置紫根/尾部标记
            overlayView.setAsparagusTail(result.tailPoint)
            // 设置直径测量线
            overlayView.setDiameterLine(result.diameterLine)
            
            // 同时也设置矩形作为备份或调试信息（可选，OverlayView 现在优先画轮廓）
            result.asparagusRect?.let { overlayView.setAsparagusRect(it) }
            overlayView.visibility = View.VISIBLE
        } else if (result.asparagusRect != null) {
            // 回退到矩形
            overlayView.setAsparagusRect(result.asparagusRect)
            overlayView.visibility = View.VISIBLE
        }
    }
    

    override fun onDestroy() {
        super.onDestroy()
        cameraManager.release()
    }
}