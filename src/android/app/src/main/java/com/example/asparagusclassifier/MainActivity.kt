package com.example.asparagusclassifier

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.CheckBox
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
import android.speech.tts.TextToSpeech
import android.text.SpannableString
import android.text.Spannable
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), CameraManager.OnSizeInfoListener {
    
    private lateinit var cameraManager: CameraManager
    private lateinit var textureView: TextureView
    private lateinit var overlayView: OverlayView
    private lateinit var btnCapture: Button
    private lateinit var cbAuto: CheckBox
    private lateinit var tvResult: TextView
    private lateinit var tts: TextToSpeech
    
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val autoCaptureRunnable = object : java.lang.Runnable {
        override fun run() {
            if (cbAuto.isChecked) {
                if (btnCapture.isEnabled) {
                    btnCapture.performClick()
                }
            }
        }
    }
    
    
    private val CAMERA_PERMISSION_CODE = 100
    private val TAG = "AsparagusClassifier"
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        supportActionBar?.let { actionBar ->
            val titleText = "冯氏芦笋工具 (2026年2月)"
            val spannableTitle = SpannableString(titleText)
            val startIdx = titleText.indexOf("(")
            if (startIdx >= 0) {
                spannableTitle.setSpan(RelativeSizeSpan(0.6f), startIdx, titleText.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                spannableTitle.setSpan(StyleSpan(Typeface.NORMAL), startIdx, titleText.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            actionBar.title = spannableTitle
        }
        
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.CHINESE
            }
        }
        
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
        cbAuto = findViewById(R.id.cbAuto)
        tvResult = findViewById(R.id.tvResult)
        
        cameraManager.setOnSizeInfoListener(this)
        
        btnCapture.setOnClickListener {
            captureAndProcess()
        }
        
        cbAuto.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                handler.post(autoCaptureRunnable)
            } else {
                handler.removeCallbacks(autoCaptureRunnable)
            }
        }
        
        // 权限检查和启动逻辑已移至 onResume
    }

    override fun onResume() {
        super.onResume()
        Log.i(TAG, "Activity Resumed, 尝试恢复相机")
        checkCameraPermission()
    }

    override fun onStop() {
        super.onStop()
        Log.i(TAG, "Activity Stopped, 释放相机资源")
        cameraManager.release()
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
            // Compute the actual preview rectangle relative to the OverlayView
            // Since they are constrained together, the offset is 0,0
            overlayView.setDisplayRect(0f, 0f, textureView.width.toFloat(), textureView.height.toFloat())
            // Also pass sensor orientation for proper rotation handling
            val sensorRot = cameraManager.getSensorOrientation()
            // Store for later use when setting markers
            // (we'll use this value in captureAndProcess)
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

            // 3. 保存调试图片 (无论成功失败都保存，用于分析)
            saveDebugBitmap(correctedBitmap, "aruco_debug")

            // 4. 更新 UI 必须切回主线程
            runOnUiThread {
                lastBitmap = correctedBitmap
                lastSensorRotation = 0 // 已在位图级对齐，无需矩阵再次旋转
                if (!result.success) {
                    Log.e("MainActivity", "识别失败: ${result.error}")
                }
                displayResult(result)
                // 处理完成后恢复按鈕
                btnCapture.isEnabled = true
                btnCapture.text = "开始分析"
            }
        }
    }
    
    private fun saveDebugBitmap(bitmap: Bitmap, prefix: String) {
        try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "${prefix}_${timeStamp}.jpg"
            val file = java.io.File(getExternalFilesDir(null), fileName)
            java.io.FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            Log.e(TAG, "已保存分析图像至: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "保存调试图像失败: ${e.message}")
        }
    }

    private fun displayResult(result: AlgorithmResult) {
        val diameterStr = String.format(java.util.Locale.US, "%.1f", result.diameter)
        val rawStr = String.format(java.util.Locale.US, "%.1f", result.rawDiameter)
        val plainText = "直径: $diameterStr mm (raw: $rawStr)\n长度: ${result.length.toInt()} mm"
        
        val spannable = SpannableString(plainText)
        val rawStart = plainText.indexOf("(raw:")
        if (rawStart >= 0) {
            val rawEnd = plainText.indexOf(")", rawStart) + 1
            spannable.setSpan(RelativeSizeSpan(0.6f), rawStart, rawEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(StyleSpan(Typeface.NORMAL), rawStart, rawEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        
        tvResult.text = spannable
        tvResult.visibility = View.VISIBLE
        
        val isZero = result.diameter <= 0.0
        
        if (!isZero && ::tts.isInitialized) {
            val diameterCm = result.diameter / 10.0
            val lengthCm = result.length / 10.0
            val ttsText = "直径 ${String.format(java.util.Locale.US, "%.1f", diameterCm)}，长度 ${lengthCm.toInt()}"
            tts.speak(ttsText, TextToSpeech.QUEUE_FLUSH, null, null)
        }
        
        if (cbAuto.isChecked) {
            val delay = if (isZero) 1000L else 5000L
            handler.removeCallbacks(autoCaptureRunnable)
            handler.postDelayed(autoCaptureRunnable, delay)
        }
        
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

    
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_about -> {
                android.app.AlertDialog.Builder(this)
                    .setTitle("冯氏芦笋工具")
                    .setMessage("山东卷积分公司\n2026年2月")
                    .setNeutralButton("下载校准图纸") { _, _ ->
                        val intent = android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("http://voicevon.vicp.io:7001/nc/index.php/s/pFMFmrWaT9CWpt4/download")
                        )
                        startActivity(intent)
                    }
                    .setPositiveButton("确定", null)
                    .show()
                true
            }
            R.id.action_exit -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(autoCaptureRunnable)
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        cameraManager.release()
    }
}