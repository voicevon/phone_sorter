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
                              previewWidth: Int, previewHeight: Int, previewRatio: Float)
    }
    
    fun startCamera() {
        Log.i("CameraManager", "启动相机流程...")
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                Log.d("DiagCamera", "onSurfaceTextureAvailable: 尺寸=${width}x${height}, Surface=$surface")
                surfaceTexture = surface
                openCamera(width, height)
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                Log.d("DiagCamera", "onSurfaceTextureSizeChanged: 尺寸=${width}x${height}")
                configureTransform(width, height)
            }
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                // 每帧更新太频繁，暂时不打日志
            }
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                Log.d("DiagCamera", "onSurfaceTextureDestroyed")
                release()
                return true
            }
        }
    }
    
    fun setOnSizeInfoListener(listener: OnSizeInfoListener) {
        onSizeInfoListener = listener
    }
    
    private var currentPreviewSize: Size? = null

    private fun openCamera(width: Int, height: Int) {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as AndroidCameraManager
        try {
            val cameraId = manager.cameraIdList[0]
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) as StreamConfigurationMap
            sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            
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
        val targetRatio = if (height > 0) width.toFloat() / height.toFloat() else 1.77f
        return choices.filter { it.width <= 1920 && it.height <= 1080 }
            .minByOrNull { Math.abs((it.height.toFloat() / it.width.toFloat()) - targetRatio) } ?: choices[0]
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
                currentPreviewSize?.height ?: 0, 1.77f, nWidth, nHeight, targetRatio)
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
                        session.setRepeatingRequest(it.build(), null, null) 
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

    fun release() {
        captureSession?.close()
        cameraDevice?.close()
        captureSession = null
        cameraDevice = null
    }
}