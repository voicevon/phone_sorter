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
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

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
            val text = "直径: 0.0 mm\n长度: 0.0 mm"
            tvResult.text = text
            tvResult.visibility = View.VISIBLE
        }
    }

    private var lastBitmap: Bitmap? = null
    private var lastSensorRotation: Int = 0

    private fun captureAndProcess() {
        Log.i("MainActivity", "开始分析按鈕被点击")

        // 立即禁用按鈕，防止重复点击
        btnCapture.isEnabled = false
        btnCapture.text = "分析中..."
        tvResult.text = "正在分析，请稍候..."
        tvResult.visibility = View.VISIBLE

        val bitmap = textureView.getBitmap()
        if (bitmap == null) {
            Log.e("MainActivity", "无法获取图像")
            tvResult.text = "错误: 无法获取图像"
            btnCapture.isEnabled = true
            btnCapture.text = "开始分析"
            return
        }

        // 在后台线程执行适宽转 + OpenCV 分析，不阻塞主线程
        diskExecutor.execute {
            // 1. 获取预览图
            // 注意：TextureView.getBitmap() 已包含 setTransform 应用的旋转，方向与用户所见一致
            val correctedBitmap = bitmap 
            Log.i("MainActivity", "Captured Bitmap 尺寸: ${correctedBitmap.width}x${correctedBitmap.height}")

            // 2. 运行算法
            val t0 = System.currentTimeMillis()
            val result = AlgorithmProcessor.processImage(correctedBitmap)
            val elapsed = System.currentTimeMillis() - t0
            Log.i("MainActivity", "算法耗时: ${elapsed}ms")

            // 3. 更新 UI 必须切回主线程
            runOnUiThread {
                lastBitmap = correctedBitmap
                lastSensorRotation = 0 // 已在位图级对齐，无需矩阵再次旋转
                if (!result.success) {
                    Log.w("MainActivity", "识别失败，正在保存调试图片...")
                    saveDebugImage(correctedBitmap)
                }
                displayResult(result)
                // 处理完成后恢复按鈕
                btnCapture.isEnabled = true
                btnCapture.text = "开始分析"
            }
        }
    }

    private fun displayResult(result: AlgorithmResult) {
        val text = "直径: ${result.diameter} mm\n长度: ${result.length} mm"
        
        tvResult.text = text
        tvResult.visibility = View.VISIBLE
        
        // 显示 ArUco 标记（四边形）
        if (result.arucoCorners != null && result.arucoIds != null && lastBitmap != null) {
            val markers = result.arucoCorners.zip(result.arucoIds).map { (corners, id) ->
                com.example.asparagusclassifier.ui.ArucoMarker(corners, id)
            }
            Log.d(TAG, "设置标记: Bitmap=${lastBitmap!!.width}x${lastBitmap!!.height}, 传感器旋转=${lastSensorRotation}°")

            // 传入传感器旋转角度，让 OverlayView 知道如何将 bitmap 坐标旋转到屏幕方向
            overlayView.setArucoMarkers(markers, lastBitmap!!.width, lastBitmap!!.height, lastSensorRotation)
            overlayView.visibility = View.VISIBLE
        }
        
        // 显示芦笋区域
        if (result.asparagusContour != null) {
            overlayView.setAsparagusContour(result.asparagusContour)
            // 设置紫根/尾部标记
            overlayView.setAsparagusTail(result.tailPoint)
            // 设置直径测量线 (支持多条)
            overlayView.setDiameterLines(result.diameterLine)
            // 设置轴线路径
            overlayView.setAxisPath(result.axisPath)
            
            // 同时也设置矩形作为备份或调试信息（可选，OverlayView 现在优先画轮廓）
            result.asparagusRect?.let { overlayView.setAsparagusRect(it) }
            overlayView.visibility = View.VISIBLE
        } else if (result.asparagusRect != null) {
            // 回退到矩形
            overlayView.setAsparagusRect(result.asparagusRect)
            overlayView.visibility = View.VISIBLE
        }
    }
    
    
    private val diskExecutor = Executors.newSingleThreadExecutor()

    private fun saveDebugImage(bitmap: Bitmap) {
        diskExecutor.execute {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "ASPARAGUS_DEBUG_$timeStamp.png"
            
            try {
                val resolver = contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Asparagus_Debug")
                    }
                }

                val imageUri: Uri? = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                
                imageUri?.let { uri ->
                    val outputStream: OutputStream? = resolver.openOutputStream(uri)
                    outputStream?.use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    Log.i("MainActivity", "调试图片已保存至: Downloads/Asparagus_Debug/$fileName")
                    
                    runOnUiThread {
                        android.widget.Toast.makeText(this, "未检测到芦笋，截图已存入外部存储", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "保存调试文件失败: ${e.message}")
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        cameraManager.release()
    }
}