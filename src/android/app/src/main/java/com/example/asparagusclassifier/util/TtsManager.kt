package com.example.asparagusclassifier.util

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import java.util.Locale

/**
 * 语音播报管理器
 * 具备生命周期感知能力，自动释放资源
 */
class TtsManager(context: Context) : DefaultLifecycleObserver {
    private val TAG = "TtsManager"
    private var tts: TextToSpeech? = null
    private var isInitialized = false

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.CHINESE)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e(TAG, "语言不支持")
                } else {
                    isInitialized = true
                    Log.d(TAG, "TTS 初始化成功")
                }
            } else {
                Log.e(TAG, "TTS 初始化失败")
            }
        }
    }

    /**
     * 播报文本
     */
    fun speak(text: String) {
        if (isInitialized) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        } else {
            Log.w(TAG, "TTS 尚未初始化，忽略播报请求: $text")
        }
    }

    /**
     * 停止播报
     */
    fun stop() {
        tts?.stop()
    }

    /**
     * 生命周期回调：销毁时自动释放资源
     */
    override fun onDestroy(owner: LifecycleOwner) {
        super.onDestroy(owner)
        Log.i(TAG, "Lifecycle onDestroy: 释放 TTS 资源")
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }
}
