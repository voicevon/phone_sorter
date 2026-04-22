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
    private lateinit var tts: TextToSpeech
    
    private var currentViewMode = 2 // 1: Raw, 2: Corrected, 3: Analysis
    private var currentCalibration: com.example.asparagusclassifier.algorithm.CalibrationData? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastPoseWorldText: CharSequence = "Cam Pos (World):\nX:--  Y:--  Z:--"
    
    private var isRealtimePoseActive = true
    private val realtimePoseHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val realtimePoseRunnable = object : java.lang.Runnable {
        override fun run() {
            if (isRealtimePoseActive) {
                updateRealtimePose()
            }
            realtimePoseHandler.postDelayed(this, 300) // 约 3 FPS，平衡性能与发热
        }
    }

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
            if (isChecked) {
                handler.post(autoCaptureRunnable)
            } else {
                handler.removeCallbacks(autoCaptureRunnable)
            }
        }
        
        findViewById<Button>(R.id.btnShowDiag).setOnClickListener {
            lastResult?.let { showDiagnosticDialog(it) }
        }
        
        // 权限检查和启动逻辑已移至 onResume
    }

    override fun onResume() {
        super.onResume()
        Log.i(TAG, "Activity Resumed, 尝试恢复相机")
        checkCameraPermission()
        isRealtimePoseActive = true
        realtimePoseHandler.post(realtimePoseRunnable)
    }

    override fun onStop() {
        super.onStop()
        Log.i(TAG, "Activity Stopped, 释放相机资源")
        isRealtimePoseActive = false
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
    
    private fun dismissResultView() {
        if (layoutResult.visibility == View.VISIBLE) {
            layoutResult.visibility = View.GONE
            overlayView.clearMarkers()
            overlayView.visibility = View.GONE
            Log.d(TAG, "用户点击屏幕，收起分析结果")
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
            // Compute the actual preview rectangle relative to the OverlayView
            // Since they are constrained together, the offset is 0,0
            // 核心修复：直接使用回调给出的 previewWidth/Height 设置 OverlayView 渲染边界
            // 不再直接读取 textureView.width/height，因为布局过程可能是异步延迟的
            overlayView.setDisplayRect(0f, 0f, previewWidth.toFloat(), previewHeight.toFloat())
            
            // 更新本会话的标定数据
            this.currentCalibration = calibration
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
    private var lastResult: AlgorithmResult? = null // 保存最近一次分析结果用于诊断图
    private var lastSensorRotation: Int = 0

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        // 根据初始 currentViewMode 设置勾选状态
        val initialId = when(currentViewMode) {
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
                currentViewMode = 1
                android.widget.Toast.makeText(this, "切换至：原始视图 (C1)", android.widget.Toast.LENGTH_SHORT).show()
                true
            }
            R.id.menu_view_corrected -> {
                item.isChecked = true
                currentViewMode = 2
                android.widget.Toast.makeText(this, "切换至：去畸变视图 (C2)", android.widget.Toast.LENGTH_SHORT).show()
                true
            }
            R.id.menu_view_analysis -> {
                item.isChecked = true
                currentViewMode = 3
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
            // 2. 检查内参兼容性并根据需要弹出警告
            val cal = currentCalibration
            if ((cal == null || !cal.isValid()) && !warnedThisSession) {
                runOnUiThread {
                    showCompatibilityWarning { 
                        // 用户确认后继续
                        executeAnalysisPipeline(bitmap)
                    }
                }
            } else {
                executeAnalysisPipeline(bitmap)
            }
        }
    }

    private var warnedThisSession = false

    private fun showCompatibilityWarning(onContinue: () -> Unit) {
        android.app.AlertDialog.Builder(this)
            .setTitle("机型兼容性提示")
            .setMessage("检测到非标设备（当前型号: ${android.os.Build.MODEL}）。\n\n" +
                    "由于硬件驱动限制，无法读取相机校准参数。系统已自动切换至“原始兼容模式”。\n\n" +
                    "⚠️ 注意：由于缺乏光学纠偏，测量直径和粗度时可能会产生 10%-15% 的误差。建议仅作为参考。")
            .setCancelable(false)
            .setPositiveButton("已阅并继续") { _, _ ->
                warnedThisSession = true
                onContinue()
            }
            .setNegativeButton("退出程序") { _, _ ->
                finish()
            }
            .show()
    }

    private fun executeAnalysisPipeline(bitmap: Bitmap) {
        diskExecutor.execute {
            // 1. 获取预览图
            val correctedBitmap = bitmap 
            Log.i("MainActivity", "Captured Bitmap 尺寸: ${correctedBitmap.width}x${correctedBitmap.height}")

            // 2. 运行算法
            val t0 = System.currentTimeMillis()
            // 传入当前选择的视图模式及标定上下文
            val result = AlgorithmProcessor.processImage(correctedBitmap, currentCalibration, currentViewMode)
            val elapsed = System.currentTimeMillis() - t0
            Log.i("MainActivity", "算法总耗时: ${elapsed}ms")

            // 3. 保存调试图片
            saveDebugBitmap(correctedBitmap, "aruco_debug")

            // 4. 更新 UI
            runOnUiThread {
                val finalDisplayBitmap = result.processedBitmap ?: correctedBitmap
                lastBitmap = finalDisplayBitmap
                lastSensorRotation = 0
                
                if (!result.success) {
                    Log.e("MainActivity", "识别失败: ${result.error}")
                }
                lastResult = result
                displayResult(result)
                // 恢复按钮状态
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
    
    
    private val diskExecutor = Executors.newSingleThreadExecutor()

    
    private fun showCameraParamsDialog() {
        val cal = currentCalibration
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
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        cameraManager.release()
    }
    /**
     * 弹出诊断对话框，并列展示头、中、尾拉直后的对比图
     */
    private fun showDiagnosticDialog(result: AlgorithmResult) {
        val strips = result.diagStrips ?: return
        if (strips.isEmpty()) return
        
        val context = this
        val dialog = android.app.AlertDialog.Builder(context, android.R.style.Theme_Material_NoActionBar_Fullscreen).create()
        
        val root = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.BLACK)
            setPadding(32, 64, 32, 32)
        }
        
        // 标题栏
        val header = android.widget.RelativeLayout(context).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, 48) }
        }
        val title = android.widget.TextView(context).apply {
            text = "直线度深度诊断 (拉直对比图)"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
        }
        val close = android.widget.Button(context).apply {
            text = "关闭诊断"
            setBackgroundColor(android.graphics.Color.DKGRAY)
            setTextColor(android.graphics.Color.WHITE)
            setOnClickListener { dialog.dismiss() }
        }
        header.addView(title)
        val lpClose = android.widget.RelativeLayout.LayoutParams(-2, -2).apply { addRule(android.widget.RelativeLayout.ALIGN_PARENT_END) }
        header.addView(close, lpClose)
        root.addView(header)
        
        // 增加文本分析板块
        val analysisScroll = android.widget.ScrollView(context).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(-1, 0, 1f)
        }
        val analysisContent = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(0, 0, 0, 40)
        }

        // Section 1: 直线度分析
        val straightnessHeader = android.widget.TextView(context).apply {
            text = "一、直线度深度分析 (Straightness)"
            setTextColor(android.graphics.Color.CYAN)
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 20, 0, 10)
        }
        val straightnessText = android.widget.TextView(context).apply {
            val overall = String.format("%.2f", result.straightnessOverall)
            val head = String.format("%.2f", result.straightnessHead)
            val tail = String.format("%.2f", result.straightnessTail)
            text = "• 整体 RMSE: ${overall} mm\n• 头部 RMSE: ${head} mm\n• 尾部 RMSE: ${tail} mm"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 15f
            setPadding(20, 0, 0, 20)
        }
        analysisContent.addView(straightnessHeader)
        analysisContent.addView(straightnessText)

        // Section 2: 3D 位姿分析
        val poseHeader = android.widget.TextView(context).apply {
            text = "二、3D 空间位姿报告 (Pose 3D)"
            setTextColor(android.graphics.Color.GREEN)
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 20, 0, 10)
        }
        val poseText = android.widget.TextView(context).apply {
            val cam = result.cameraPosWorld?.let { String.format("X:%.1f, Y:%.1f, Z:%.1f", it[0], it[1], it[2]) } ?: "未知"
            val head3d = result.headPosWorld?.let { String.format("X:%.1f, Y:%.1f, Z:%.1f", it[0], it[1], it[2]) } ?: "未知"
            val tail3d = result.tailPosWorld?.let { String.format("X:%.1f, Y:%.1f, Z:%.1f", it[0], it[1], it[2]) } ?: "未知"
            
            text = "• 相机位置: ($cam) mm\n• 芦笋头部: ($head3d) mm\n• 芦笋尾部: ($tail3d) mm\n" +
                   "• 镜头高度: ${String.format("%.1f", result.poseDistanceMm)} mm\n" +
                   "• 相机倾角: ${String.format("%.1f", result.tiltAngle)}°"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 15f
            setPadding(20, 0, 0, 30)
        }
        analysisContent.addView(poseHeader)
        analysisContent.addView(poseText)

        // Section 3: 图形对比
        val stripsHeader = android.widget.TextView(context).apply {
            text = "三、局部拉直切片对比"
            setTextColor(android.graphics.Color.YELLOW)
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 20, 0, 20)
        }
        analysisContent.addView(stripsHeader)

        // 水平滚动容器 (原有的诊断图逻辑)
        val imageScroll = android.widget.HorizontalScrollView(context).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2)
        }
        val imageContainer = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
        }
        
        val labels = listOf("头段", "整体", "尾段")
        strips.forEachIndexed { index, bitmap ->
            val itemWrapper = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(10, 0, 10, 0)
                gravity = android.view.Gravity.CENTER_HORIZONTAL
            }
            val img = android.widget.ImageView(context).apply {
                setImageBitmap(bitmap)
                adjustViewBounds = true
                layoutParams = android.widget.LinearLayout.LayoutParams(350, -2)
            }
            val label = android.widget.TextView(context).apply {
                text = labels.getOrElse(index) { "区域$index" }
                setTextColor(android.graphics.Color.GRAY)
                textSize = 12f
            }
            itemWrapper.addView(img)
            itemWrapper.addView(label)
            imageContainer.addView(itemWrapper)
        }
        imageScroll.addView(imageContainer)
        analysisContent.addView(imageScroll)
        
        analysisScroll.addView(analysisContent)
        root.addView(analysisScroll)
        
        dialog.setContentView(root)
        dialog.show()
    }

    /**
     * 实时位姿更新函数：
     * 从 TextureView 获取当前帧并调用实时优化版算法
     */
    private fun updateRealtimePose() {
        val bitmap = textureView.getBitmap() ?: return
        
        // 使用单线程池执行，避免 UI 卡顿
        diskExecutor.execute {
            val result = AlgorithmProcessor.processRealtimePose(bitmap, currentCalibration)
            
            runOnUiThread {
                if (result.success) {
                    val cam = result.cameraPosWorld
                    if (cam != null) {
                        setColoredPoseText(tvHUDPose, cam[0], cam[1], Math.abs(cam[2]), "Cam Pos (World):")
                        lastPoseWorldText = tvHUDPose.text
                        tvHUDStatus.text = "Status: 已对准 (实时解算), 倾角:%.1f°".format(result.tiltAngle)
                        tvHUDStatus.setTextColor(android.graphics.Color.GREEN)
                    }
                    
                    // 【关键修复】同步去畸变背景以确保对齐
                    overlayView.setBackgroundBitmap(result.canvas2Bitmap)
                    
                    // 【核心修复】同步标记到 OverlayView
                    if (result.arucoCorners != null && result.arucoIds != null) {
                        val markers = result.arucoCorners.zip(result.arucoIds).map { (corners, id) ->
                            com.example.asparagusclassifier.ui.ArucoMarker(corners, id)
                        }
                        // 使用当前位图的尺寸进行坐标映射
                        overlayView.setArucoMarkers(markers, bitmap.width, bitmap.height, 0)
                    }
                    overlayView.setAxis3D(result.axis3DPoints)
                    overlayView.visibility = android.view.View.VISIBLE
                    android.util.Log.d("MainActivity", "Realtime: UI Updated (Markers count: ${result.arucoIds?.size}, Axis: ${result.axis3DPoints?.size})")
                    
                } else {
                    tvHUDPose.text = lastPoseWorldText
                    tvHUDStatus.text = "Status: 信号丢失 (当前为离线历史值)"
                    tvHUDStatus.setTextColor(android.graphics.Color.RED)
                    overlayView.setBackgroundBitmap(null) // 丢失时清空背景
                    overlayView.clearMarkers() // 清除渲染标记
                    overlayView.setAxis3D(null)
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