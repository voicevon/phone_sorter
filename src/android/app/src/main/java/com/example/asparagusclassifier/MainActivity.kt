package com.example.asparagusclassifier

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.view.TextureView
import android.graphics.Matrix
import android.text.*
import android.text.style.*
import android.graphics.Color
import android.view.View
import android.view.Surface
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
import androidx.lifecycle.ViewModelProvider
import com.example.asparagusclassifier.ui.DiagnosticDialog
import com.example.asparagusclassifier.data.VisionRepository

class MainActivity : AppCompatActivity(), CameraManager.OnSizeInfoListener {
    
    private lateinit var cameraManager: CameraManager
    private lateinit var textureView: TextureView
    private lateinit var overlayView: OverlayView
    private lateinit var btnCapture: Button
    private lateinit var cbAuto: CheckBox
    private lateinit var tvResult: TextView
    private lateinit var layoutResult: View
    private lateinit var btnCloseResult: android.widget.ImageButton
    private lateinit var tvHUDPose: TextView
    private lateinit var tvHUDStatus: TextView
    private lateinit var viewModel: MainViewModel
    private lateinit var visionRepository: VisionRepository
    private lateinit var ttsManager: com.example.asparagusclassifier.util.TtsManager
    
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastPoseWorldText: CharSequence = ""
    private var lastSignalTime = 0L // 上次检测到信号的时间
    private val POSE_PERSIST_MS = 1000L // 位姿保留 1 秒，防止闪烁
    
    private val realtimePoseHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val realtimePoseRunnable = object : java.lang.Runnable {
        override fun run() {
            if (viewModel.isRealtimePoseActive.value == true) {
                updateRealtimePose()
            }
            realtimePoseHandler.postDelayed(this, 300) // 约 3 FPS，平衡性能与发热
        }
    }

    private val autoCaptureRunnable = object : java.lang.Runnable {
        override fun run() {
            if (cbAuto.isChecked && btnCapture.isEnabled) {
                viewModel.requestCapture()
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
        
        // 初始化 TTS 管理器并注册生命周期观察
        ttsManager = com.example.asparagusclassifier.util.TtsManager(this)
        lifecycle.addObserver(ttsManager)
        
        if (!OpenCVLoader.initDebug()) {
            Log.e(TAG, "OpenCV 初始化失败！")
        } else {
            Log.d(TAG, "OpenCV 初始化成功")
        }
        
        setContentView(R.layout.activity_main)

        viewModel = ViewModelProvider(this).get(MainViewModel::class.java)
        visionRepository = VisionRepository(this)
        setupObservers()
        
        textureView = findViewById(R.id.textureView)
        overlayView = findViewById(R.id.overlayView)
        cameraManager = CameraManager(this, textureView)
        
        btnCapture = findViewById(R.id.btnCapture)
        cbAuto = findViewById(R.id.cbAuto)
        tvResult = findViewById(R.id.tvResult)
        layoutResult = findViewById(R.id.layoutResult)
        btnCloseResult = findViewById(R.id.btnCloseResult)
        tvHUDPose = findViewById(R.id.tvHUDPose)
        tvHUDStatus = findViewById(R.id.tvHUDStatus)
        
        btnCloseResult.setOnClickListener {
            dismissResultView()
        }
        
        // 允许通过点击屏幕任何区域（除按钮外）退出结果显示
        findViewById<View>(R.id.main_root).setOnClickListener { dismissResultView() }
        textureView.setOnClickListener { dismissResultView() }
        overlayView.setOnClickListener { dismissResultView() }
        overlayView.isClickable = true // 确保可以拦截点击
        
        cameraManager.setOnSizeInfoListener(this)
        
        btnCapture.setOnClickListener {
            captureAndProcess()
        }
        
        cbAuto.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setAutoCaptureEnabled(isChecked)
        }
        
        findViewById<Button>(R.id.btnShowDiag).setOnClickListener {
            viewModel.lastResult.value?.let { DiagnosticDialog.show(this, it) }
        }
        
        // 权限检查和启动逻辑已移至 onResume
    }

    override fun onResume() {
        super.onResume()
        Log.i(TAG, "Activity Resumed, 尝试恢复相机")
        checkCameraPermission()
        viewModel.setRealtimePoseActive(true)
        realtimePoseHandler.post(realtimePoseRunnable)
    }

    override fun onStop() {
        super.onStop()
        Log.i(TAG, "Activity Stopped, 释放相机资源")
        viewModel.setRealtimePoseActive(false)
        realtimePoseHandler.removeCallbacks(realtimePoseRunnable)
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
    
    private fun setupObservers() {
        viewModel.viewMode.observe(this) { mode ->
            // 这里可以处理一些模式切换时的即时 UI 刷新
        }
        
        viewModel.lastResult.observe(this) { result ->
            result?.let { displayResult(it) }
        }
        
        viewModel.isAutoCaptureEnabled.observe(this) { enabled ->
            handler.removeCallbacks(autoCaptureRunnable)
            if (enabled) {
                handler.postDelayed(autoCaptureRunnable, 1000L)
            }
        }

        viewModel.captureRequest.observe(this) {
            captureAndProcess()
        }
    }

    override fun onSizeInfoReceived(cameraWidth: Int, cameraHeight: Int, cameraRatio: Float,
                                   previewWidth: Int, previewHeight: Int, previewRatio: Float,
                                   calibration: com.example.asparagusclassifier.algorithm.CalibrationData?) {
        // UI 更新必须在主线程
        runOnUiThread {
            val text = "直径: 0.0 mm\n长度: 0.0 mm"
            tvResult.text = text
            tvResult.visibility = View.VISIBLE
            
            overlayView.setDisplayRect(0f, 0f, previewWidth.toFloat(), previewHeight.toFloat())
            
            // 更新 ViewModel 中的标定数据
            viewModel.setCurrentCalibration(calibration)
            Log.i(TAG, "已接收到相机尺寸及标定信息: ${cameraWidth}x${cameraHeight}, 校准存在=${calibration != null}")
            
            // 如果标定无效且尚未上报，则进行上报
            if (calibration == null || !calibration.isValid()) {
                com.example.asparagusclassifier.util.MqttReporter.reportInvalidIntrinsic(
                    calibration?.intrinsic, cameraWidth, cameraHeight
                )
            }
        }
    }

    private var lastBitmap: Bitmap? = null
    private var lastSensorRotation: Int = 0

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        // 根据初始 ViewModel 中的 viewMode 设置勾选状态
        val mode = viewModel.viewMode.value ?: 2
        val initialId = when(mode) {
            1 -> R.id.menu_view_raw
            2 -> R.id.menu_view_corrected
            else -> R.id.menu_view_analysis
        }
        menu?.findItem(initialId)?.isChecked = true
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_view_raw -> {
                item.isChecked = true
                viewModel.setViewMode(1)
                android.widget.Toast.makeText(this, "切换至：原始视图 (C1)", android.widget.Toast.LENGTH_SHORT).show()
                true
            }
            R.id.menu_view_corrected -> {
                item.isChecked = true
                viewModel.setViewMode(2)
                android.widget.Toast.makeText(this, "切换至：去畸变视图 (C2)", android.widget.Toast.LENGTH_SHORT).show()
                true
            }
            R.id.menu_view_analysis -> {
                item.isChecked = true
                viewModel.setViewMode(3)
                android.widget.Toast.makeText(this, "切换至：标准分析视图 (C3)", android.widget.Toast.LENGTH_SHORT).show()
                true
            }
            R.id.action_camera_params -> {
                showCameraParamsDialog()
                true
            }
            R.id.action_about -> {
                android.app.AlertDialog.Builder(this)
                    .setTitle("冯氏芦笋工具")
                    .setMessage("设计师：冯工\n数据架构：三画布诊断系统\n2026年4月")
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

    private fun captureAndProcess() {
        val bitmap = textureView.getBitmap() ?: return
        
        visionRepository.analyzeImage(
            bitmap = bitmap,
            calibration = viewModel.currentCalibration.value,
            viewMode = viewModel.viewMode.value ?: 2,
            onPreAnalysis = {
                runOnUiThread {
                    btnCapture.isEnabled = false
                    btnCapture.text = "分析中..."
                    tvResult.text = "正在分析，请稍候..."
                    tvResult.visibility = View.VISIBLE
                }
            },
            onResult = { result ->
                runOnUiThread {
                    lastBitmap = result.processedBitmap ?: bitmap
                    viewModel.setLastResult(result)
                    btnCapture.isEnabled = true
                    btnCapture.text = "开始分析"
                }
            },
            onShowWarning = { onContinue ->
                runOnUiThread {
                    showCompatibilityWarning(onContinue)
                }
            }
        )
    }

    private fun showCompatibilityWarning(onContinue: () -> Unit) {
        android.app.AlertDialog.Builder(this)
            .setTitle("机型兼容性提示")
            .setMessage("检测到非标设备（当前型号: ${android.os.Build.MODEL}）。\n\n" +
                    "由于硬件驱动限制，无法读取相机校准参数。系统已自动切换至“原始兼容模式”。\n\n" +
                    "⚠️ 注意：由于缺乏光学纠偏，测量直径和粗度时可能会产生 10%-15% 的误差。建议仅作为参考。")
            .setCancelable(false)
            .setPositiveButton("已阅并继续") { _, _ ->
                onContinue()
            }
            .setNegativeButton("退出程序") { _, _ ->
                finish()
            }
            .show()
    }

    private fun displayResult(result: AlgorithmResult) {
        if (!result.success) {
            // 简化报错信息：如果是 Aruco 检测问题，统称为“四角定位不完全”
            val readableError = if (result.error?.contains("标识") == true) "四角定位不完全" else (result.error ?: "未知错误")
            val errorMsg = "分析失败 原因：$readableError"
            
            val spannable = SpannableString(errorMsg)
            spannable.setSpan(android.text.style.ForegroundColorSpan(android.graphics.Color.RED), 
                0, errorMsg.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            tvResult.text = spannable
            layoutResult.visibility = View.VISIBLE
            
            // 失败时也更新标记显示（可能包含拒绝列表等辅助信息）
            updateOverlay(result)
            return
        }

        val diameterStr = String.format(java.util.Locale.US, "%.1f", result.diameter)
        val rawStr = String.format(java.util.Locale.US, "%.1f", result.rawDiameter)
        val timeStr = String.format(java.util.Locale.US, "%.2f", result.executionTimeMs / 1000.0)
        
        val sOverall = String.format(java.util.Locale.US, "%.1f", result.straightnessOverall)
        val sHead = String.format(java.util.Locale.US, "%.1f", result.straightnessHead)
        val sTail = String.format(java.util.Locale.US, "%.1f", result.straightnessTail)

        val plainText = "直径: $diameterStr mm (raw: $rawStr)\n" +
                         "长度: ${result.length.toInt()} mm\n" +
                         "直线度: $sOverall mm (头:$sHead, 尾:$sTail) [耗时: ${timeStr}s]"
        
        val spannable = SpannableString(plainText)
        val rawStart = plainText.indexOf("(raw:")
        if (rawStart >= 0) {
            val rawEnd = plainText.indexOf(")", rawStart) + 1
            spannable.setSpan(RelativeSizeSpan(0.6f), rawStart, rawEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(StyleSpan(Typeface.NORMAL), rawStart, rawEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        
        // 渲染直线度详情 (头尾)
        val sHeadStart = plainText.indexOf("(头:")
        if (sHeadStart >= 0) {
            val sTailEnd = plainText.indexOf(")", sHeadStart) + 1
            spannable.setSpan(RelativeSizeSpan(0.6f), sHeadStart, sTailEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(StyleSpan(Typeface.NORMAL), sHeadStart, sTailEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(android.text.style.ForegroundColorSpan(android.graphics.Color.LTGRAY), sHeadStart, sTailEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        
        // 耗时文本设为灰色并缩小
        val timeStart = plainText.indexOf("[耗时:")
        if (timeStart >= 0) {
             spannable.setSpan(RelativeSizeSpan(0.6f), timeStart, plainText.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
             spannable.setSpan(android.text.style.ForegroundColorSpan(android.graphics.Color.GRAY), timeStart, plainText.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        
        tvResult.text = spannable
        layoutResult.visibility = View.VISIBLE
        
        updateOverlay(result)
        
        val isZero = result.diameter <= 0.0
        
        if (!isZero) {
            val diameterCm = result.diameter / 10.0
            val lengthCm = result.length / 10.0
            val ttsText = "直径 ${String.format(java.util.Locale.US, "%.1f", diameterCm)}，长度 ${lengthCm.toInt()}"
            ttsManager.speak(ttsText)
        }
        
        if (viewModel.isAutoCaptureEnabled.value == true) {
            val delay = if (isZero) 1000L else 5000L
            handler.removeCallbacks(autoCaptureRunnable)
            handler.postDelayed(autoCaptureRunnable, delay)
        }
    }

    private fun updateOverlay(result: AlgorithmResult) {
        overlayView.clearMarkers()
        overlayView.setAxis3D(result.axis3DPoints)
        
        if (lastBitmap == null) return

        // 策略：根据 viewMode 决定显示什么
        when (result.viewMode) {
            1 -> {
                // 原始视图：不显示叠加
                overlayView.visibility = View.INVISIBLE
            }
            2 -> {
                // 去畸变视图：显示 ArUco 标记及投影回物理空间的测量结果
                if (result.arucoCorners != null && result.arucoIds != null) {
                    val markers = result.arucoCorners.zip(result.arucoIds).map { (corners, id) ->
                        com.example.asparagusclassifier.ui.ArucoMarker(corners, id)
                    }
                    overlayView.setArucoMarkers(markers, lastBitmap!!.width, lastBitmap!!.height, 0)
                    overlayView.setBackgroundBitmap(result.processedBitmap)
                    
                    // 显示反向投影后的芦笋特征线
                    if (result.asparagusContour != null) {
                        overlayView.setAsparagusContour(result.asparagusContour)
                        overlayView.setAsparagusTail(result.tailPoint)
                        overlayView.setDiameterLines(result.diameterLine)
                        overlayView.setAxisPath(result.axisPath)
                        overlayView.setBaselines(result.baselineOverall, result.baselineHead, result.baselineTail)
                        overlayView.visibility = View.VISIBLE
                    }
                }
            }
            3 -> {
                // 标准分析视图：显示芦笋轮廓与测量数据
                if (result.asparagusContour != null) {
                    val markers = if (result.arucoCorners != null && result.arucoIds != null) {
                        result.arucoCorners.zip(result.arucoIds).map { (corners, id) ->
                            com.example.asparagusclassifier.ui.ArucoMarker(corners, id)
                        }
                    } else emptyList()
                    
                    overlayView.setArucoMarkers(markers, lastBitmap!!.width, lastBitmap!!.height, 0)
                    overlayView.setBackgroundBitmap(result.processedBitmap)
                    overlayView.setAsparagusContour(result.asparagusContour)
                    overlayView.setAsparagusTail(result.tailPoint)
                    overlayView.setDiameterLines(result.diameterLine)
                    overlayView.setAxisPath(result.axisPath)
                    overlayView.visibility = View.VISIBLE
                }
            }
        }
    }
    
    
    
    private fun showCameraParamsDialog() {
        val cal = viewModel.currentCalibration.value
        val message = if (cal != null && cal.isValid()) {
            """
            模型: ${android.os.Build.MODEL}
            状态: 相机校准数据已加载
            
            【内参矩阵 (Intrinsic)】
            fx (焦距 X): %.3f
            fy (焦距 Y): %.3f
            cx (光心 X): %.3f
            cy (光心 Y): %.3f
            skew (偏斜): %.3f
            
            【畸变参数 (Distortion)】
            ${cal.distortion.joinToString(", ") { String.format("%.5f", it) }}
            
            【参考分辨率】
            宽度: ${cal.sensorWidth} px
            高度: ${cal.sensorHeight} px
            """.trimIndent().format(
                cal.intrinsic[0], cal.intrinsic[1], 
                cal.intrinsic[2], cal.intrinsic[3], cal.intrinsic[4]
            )
        } else {
            "当前设备不支持读取相机标定参数，系统正在以“原始兼容模式”运行。"
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("相机标定诊断")
            .setMessage(message)
            .setPositiveButton("确定", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(autoCaptureRunnable)
        visionRepository.release()
        cameraManager.release()
    }
    private fun dismissResultView() {
        if (layoutResult.visibility == View.VISIBLE) {
            layoutResult.visibility = View.GONE
            overlayView.clearMarkers()
            overlayView.visibility = View.GONE
            Log.d(TAG, "用户点击屏幕，收起分析结果")
        }
    }

    /**
     * 实时位姿更新函数：
     * 从 TextureView 获取当前帧并调用实时优化版算法
     */
    private fun updateRealtimePose() {
        val bitmap = textureView.getBitmap() ?: return
        
        visionRepository.analyzeRealtimePose(bitmap, viewModel.currentCalibration.value) { result ->
            runOnUiThread {
                if (result.success) {
                    val cam = result.cameraPosWorld
                    if (cam != null) {
                        setColoredPoseText(tvHUDPose, cam[0], cam[1], Math.abs(cam[2]), "Cam Pos (World):")
                        lastPoseWorldText = tvHUDPose.text
                        tvHUDStatus.text = "Status: 已对准 (实时解算)"
                        tvHUDStatus.setTextColor(android.graphics.Color.GREEN)
                    }
                    
                    val displayBmp = result.canvas2Bitmap ?: bitmap
                    overlayView.setBackgroundBitmap(displayBmp)
                    
                    if (result.arucoCorners != null && result.arucoIds != null) {
                        val markers = result.arucoCorners.zip(result.arucoIds).map { (corners, id) ->
                            com.example.asparagusclassifier.ui.ArucoMarker(corners, id)
                        }
                        overlayView.setArucoMarkers(markers, displayBmp.width, displayBmp.height, 0)
                    }
                    overlayView.setAxis3D(result.axis3DPoints)
                    overlayView.setMarkerAxes(result.markerAxes)
                    overlayView.visibility = android.view.View.VISIBLE
                    lastSignalTime = System.currentTimeMillis()
                    
                } else {
                    // 信号丢失时，仅更新 HUD，不清除 OverlayView 的任何内容
                    tvHUDPose.text = lastPoseWorldText
                    tvHUDStatus.text = "Status: 信号丢失 (锁定末帧显示)"
                    tvHUDStatus.setTextColor(android.graphics.Color.YELLOW)
                }
            }
        }
    }

    private fun setColoredPoseText(tv: android.widget.TextView, x: Double, y: Double, z: Double, title: String) {
        val xStr = String.format("%.1f", x)
        val yStr = String.format("%.1f", y)
        val zStr = String.format("%.1f", z)
        
        val fullText = "$title\nX:$xStr  Y:$yStr  Z:$zStr"
        val spannable = SpannableStringBuilder(fullText)
        
        val xIdx = fullText.indexOf("X:$xStr")
        val yIdx = fullText.indexOf("Y:$yStr")
        val zIdx = fullText.indexOf("Z:$zStr")
        
        if (xIdx != -1) spannable.setSpan(ForegroundColorSpan(Color.RED), xIdx, xIdx + 2 + xStr.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (yIdx != -1) spannable.setSpan(ForegroundColorSpan(Color.GREEN), yIdx, yIdx + 2 + yStr.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (zIdx != -1) spannable.setSpan(ForegroundColorSpan(Color.BLUE), zIdx, zIdx + 2 + zStr.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        
        tv.text = spannable
    }
}