package com.example.asparagusclassifier.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager as AndroidCameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.graphics.Matrix
import android.hardware.camera2.CaptureRequest
import android.graphics.RectF

class CameraManager(private val context: Context, private val textureView: TextureView) {
    
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var cameraSizes: Array<Size>? = null
    private var sensorOrientation: Int = 0
    private var onSizeInfoListener: OnSizeInfoListener? = null
    
    interface OnSizeInfoListener {
        fun onSizeInfoReceived(cameraWidth: Int, cameraHeight: Int, cameraRatio: Float, 
                              previewWidth: Int, previewHeight: Int, previewRatio: Float,
                              calibration: com.example.asparagusclassifier.algorithm.CalibrationData?)
    }
    
    fun startCamera() {
        Log.i("CameraManager", "启动相机监听器...")
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                Log.d("DiagCamera", "onSurfaceTextureAvailable: 尺寸=${width}x${height}")
                surfaceTexture = surface
                openCamera(width, height)
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                configureTransform(width, height)
            }
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                Log.d("DiagCamera", "onSurfaceTextureDestroyed")
                release()
                return true
            }
        }
        
        // 如果 Surface 已经可用（例如从后台返回），直接尝试打开
        if (textureView.isAvailable) {
            surfaceTexture = textureView.surfaceTexture
            openCamera(textureView.width, textureView.height)
        }
    }
    
    fun setOnSizeInfoListener(listener: OnSizeInfoListener) {
        onSizeInfoListener = listener
    }
    
    private var currentPreviewSize: Size? = null
    private var lastCalibration: com.example.asparagusclassifier.algorithm.CalibrationData? = null

    /** 返回相机传感器相对于设备自然方向的旋转角度（通常为 90 或 270）*/
    fun getSensorOrientation(): Int = sensorOrientation

    private fun openCamera(width: Int, height: Int) {
        if (cameraDevice != null) {
            Log.d("CameraManager", "相机已在运行中，跳过打开")
            return
        }
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as AndroidCameraManager
        try {
            val cameraId = manager.cameraIdList[0]
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) as StreamConfigurationMap
            sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            
            // 1. 优先尝试读取官方预设的标定参数
            try {
                val distortion = characteristics.get(CameraCharacteristics.LENS_DISTORTION)
                val intrinsic = characteristics.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
                val pixelArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                
                if (distortion != null && intrinsic != null && pixelArraySize != null && (intrinsic[0] != 0f || intrinsic[1] != 0f)) {
                    lastCalibration = com.example.asparagusclassifier.algorithm.CalibrationData(
                        intrinsic, distortion, pixelArraySize.width, pixelArraySize.height
                    )
                    Log.i("CameraManager", "读取到原始标定参数，传感器分辨率: ${pixelArraySize.width}x${pixelArraySize.height}")
                } else {
                    // 2. 兜底方案：如果官方数据缺失，利用传感器规格进行启发式计算
                    lastCalibration = estimateCalibrationFallback(characteristics)
                    Log.w("CameraManager", "官方参数缺失，已切换至“物理规格估算”模式")
                }
            } catch (e: Exception) {
                Log.e("CameraManager", "标定初始化失败，尝试兜底: ${e.message}")
                lastCalibration = estimateCalibrationFallback(characteristics)
            }
            
            val outputSizes = map.getOutputSizes(SurfaceTexture::class.java)
            val bestSize = chooseOptimalSize(outputSizes, width, height)
            currentPreviewSize = bestSize
            
            val swappedDimensions = sensorOrientation == 90 || sensorOrientation == 270
            val targetRatio = if (swappedDimensions) {
                bestSize.height.toFloat() / bestSize.width.toFloat()
            } else {
                bestSize.width.toFloat() / bestSize.height.toFloat()
            }
            
            adjustTextureViewAspectRatio(targetRatio)
            configureTransform(width, height)
            
            surfaceTexture?.setDefaultBufferSize(bestSize.width, bestSize.height)
            
            Log.i("CameraManager", "配置完成: 传感器=${sensorOrientation}, 最佳尺寸=${bestSize.width}x${bestSize.height}")

            Log.d("DiagCamera", "正在打开相机: ID=$cameraId, 传感器角度=$sensorOrientation")
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    Log.d("DiagCamera", "CameraDevice 已打开: $camera")
                    cameraDevice = camera
                    createCaptureSession()
                }
                override fun onDisconnected(camera: CameraDevice) { 
                    Log.w("DiagCamera", "CameraDevice 断开连接")
                    camera.close(); cameraDevice = null 
                }
                override fun onError(camera: CameraDevice, error: Int) { 
                    Log.e("DiagCamera", "CameraDevice 错误: $error")
                    camera.close(); cameraDevice = null 
                }
            }, null)
            
        } catch (e: Exception) {
            Log.e("CameraManager", "打开相机失败: ${e.message}")
        }
    }

    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        val previewSize = currentPreviewSize ?: return
        val rotation = textureView.display?.rotation ?: Surface.ROTATION_0
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()
        
        Log.d("DiagCamera", "configureTransform: view=${viewWidth}x${viewHeight}, preview=${previewSize.width}x${previewSize.height}, sensor=$sensorOrientation, displayRotation=$rotation")
        
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = Math.max(
                viewHeight.toFloat() / previewSize.height,
                viewWidth.toFloat() / previewSize.width
            )
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180f, centerX, centerY)
        }
        
        textureView.setTransform(matrix)
    }

    private fun chooseOptimalSize(choices: Array<Size>, width: Int, height: Int): Size {
        // 统一使用 landscape 比例（长边/短边），避免竖屏与横屏尺寸混用导致比较错误
        val maxDim = maxOf(width, height).toFloat()
        val minDim = minOf(width, height).toFloat()
        val targetRatio = if (minDim > 0) maxDim / minDim else 1.77f
        return choices.filter { it.width <= 1920 && it.height <= 1080 }
            .minByOrNull { c ->
                val cRatio = maxOf(c.width, c.height).toFloat() / minOf(c.width, c.height).toFloat()
                Math.abs(cRatio - targetRatio)
            } ?: choices[0]
    }

    private fun adjustTextureViewAspectRatio(targetRatio: Float) {
        textureView.post {
            val parent = textureView.parent as? android.view.View ?: return@post
            val pWidth = parent.width
            val pHeight = parent.height
            if (pWidth == 0 || pHeight == 0) return@post

            val nWidth: Int
            val nHeight: Int
            if (pWidth < pHeight * targetRatio) {
                nWidth = pWidth
                nHeight = (pWidth / targetRatio).toInt()
            } else {
                nHeight = pHeight
                nWidth = (pHeight * targetRatio).toInt()
            }
            
            val params = textureView.layoutParams
            params.width = nWidth
            params.height = nHeight
            textureView.layoutParams = params
            textureView.requestLayout()
            
            onSizeInfoListener?.onSizeInfoReceived(currentPreviewSize?.width ?: 0, 
                currentPreviewSize?.height ?: 0, 1.77f, nWidth, nHeight, targetRatio, lastCalibration)
        }
    }

    private fun createCaptureSession() {
        Log.d("DiagCamera", "开始创建 CaptureSession...")
        try {
            val surface = Surface(surfaceTexture)
            Log.d("DiagCamera", "创建预览 Surface: $surface, 有效性=${surface.isValid}")
            val builder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder?.addTarget(surface)
            Log.d("DiagCamera", "Session 配置中...")
            cameraDevice?.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    Log.d("DiagCamera", "CaptureSession 已配置成功: $session")
                    captureSession = session
                    builder?.let { 
                        it.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        session.setRepeatingRequest(it.build(), captureCallback, null) 
                        Log.d("DiagCamera", "RepeatingRequest 已发送")
                    }
                }
                override fun onConfigureFailed(p0: CameraCaptureSession) {
                    Log.e("DiagCamera", "CaptureSession 配置失败")
                }
            }, null)
        } catch (e: Exception) {
            Log.e("CameraManager", "会话创建失败: ${e.message}")
        }
    }

    /**
     * 逐帧回调：确保多镜头手机在镜头切换（如广角/超广角切换）时，标定参数能够实时同步。
     */
    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: android.hardware.camera2.TotalCaptureResult) {
            val intrinsic = result.get(android.hardware.camera2.CaptureResult.LENS_INTRINSIC_CALIBRATION)
            val distortion = result.get(android.hardware.camera2.CaptureResult.LENS_DISTORTION)
            
            // 只有当参数有效且发生变化时才更新，减少 UI 抖动
            if (intrinsic != null && (intrinsic[0] != 0f || intrinsic[1] != 0f)) {
                val currentIds = lastCalibration
                // 注意：TotalCaptureResult 通常不带 SENSOR_INFO_PIXEL_ARRAY_SIZE，我们保持原有分辨率设定
                if (currentIds == null || !intrinsic.contentEquals(currentIds.intrinsic)) {
                    Log.i("CameraManager", "检测到镜头参数动态变化 (多镜头同步成功)")
                    lastCalibration = com.example.asparagusclassifier.algorithm.CalibrationData(
                        intrinsic, distortion ?: FloatArray(5), 
                        currentIds?.sensorWidth ?: 1920, currentIds?.sensorHeight ?: 1080
                    )
                    // 同步到 UI 状态
                    onSizeInfoListener?.onSizeInfoReceived(
                        currentIds?.sensorWidth ?: 1920, currentIds?.sensorHeight ?: 1080, 1.77f,
                        textureView.width, textureView.height, 1.77f, lastCalibration
                    )
                }
            }
        }
    }

    /**
     * 物理规格估算：针对不公开内参的劣质/非标驱动，利用传感器物理尺寸和焦距反向推导 fx, fy。
     */
    private fun estimateCalibrationFallback(characteristics: CameraCharacteristics): com.example.asparagusclassifier.algorithm.CalibrationData {
        val pixelArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        
        val w = pixelArraySize?.width ?: 1920
        val h = pixelArraySize?.height ?: 1080
        
        if (sensorSize != null && focalLengths != null && focalLengths.isNotEmpty()) {
            val f_mm = focalLengths[0]
            val fx = f_mm * w / sensorSize.width
            val fy = f_mm * h / sensorSize.height
            val cx = w / 2f
            val cy = h / 2f
            Log.w("CameraManager", "执行物理推导：f=${f_mm}mm, fx=${fx}, cx=${cx}")
            
            return com.example.asparagusclassifier.algorithm.CalibrationData(
                floatArrayOf(fx, fy, cx, cy, 0f), 
                floatArrayOf(0f, 0f, 0f, 0f, 0f), // 估算模式不提供畸变校正
                w, h
            )
        }
        
        // 最终垫底（不标定模式）
        return com.example.asparagusclassifier.algorithm.CalibrationData(
            floatArrayOf(0f, 0f, 0f, 0f, 0f), floatArrayOf(0f, 0f, 0f, 0f, 0f), w, h
        )
    }

    fun release() {
        captureSession?.close()
        cameraDevice?.close()
        captureSession = null
        cameraDevice = null
    }
}