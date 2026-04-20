package com.example.asparagusclassifier.util

import android.os.Build
import android.util.Log
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.util.*

/**
 * 异常机型上报工具
 * 将设备型号及内参异常状态上报至指定 MQTT Broker
 */
object MqttReporter {
    private const val TAG = "MqttReporter"
    private const val BROKER_URL = "tcp://voicevon.vicp.io:1883"
    private const val CLIENT_ID_PREFIX = "Asparagus_"
    private const val USERNAME = "von"
    private const val PASSWORD = "von1970"
    
    // 动态生成 Topic: phone_sorter/${brand}/${type}/0/result
    private val manufacturer = Build.MANUFACTURER.replace(" ", "_")
    private val model = Build.MODEL.replace(" ", "_")
    private val TOPIC = "phone_sorter/$manufacturer/$model/0/result"

    private var mqttClient: MqttClient? = null

    fun reportInvalidIntrinsic(intrinsic: FloatArray?, sensorWidth: Int, sensorHeight: Int) {
        Thread {
            try {
                if (mqttClient == null || !mqttClient!!.isConnected) {
                    connect()
                }

                val payload = JSONObject().apply {
                    put("event", "InvalidIntrinsicDetected")
                    put("brand", Build.MANUFACTURER)
                    put("model", Build.MODEL)
                    put("sensorSize", "${sensorWidth}x$sensorHeight")
                    put("intrinsic", intrinsic?.joinToString(",") ?: "null")
                    put("timestamp", System.currentTimeMillis())
                }

                val message = MqttMessage(payload.toString().toByteArray())
                message.qos = 1
                mqttClient?.publish(TOPIC, message)
                Log.i(TAG, "已成功上报异常设备信息至主题: $TOPIC")
                
            } catch (e: Exception) {
                Log.e(TAG, "MQTT 上报失败: ${e.message}")
            }
        }.start()
    }

    private fun connect() {
        try {
            val clientId = CLIENT_ID_PREFIX + UUID.randomUUID().toString().substring(0, 8)
            mqttClient = MqttClient(BROKER_URL, clientId, MemoryPersistence())
            
            val options = MqttConnectOptions().apply {
                userName = USERNAME
                password = PASSWORD.toCharArray()
                isCleanSession = true
                connectionTimeout = 10
                keepAliveInterval = 60
            }

            mqttClient?.connect(options)
            Log.i(TAG, "MQTT 已连接至 $BROKER_URL")
        } catch (e: Exception) {
            Log.e(TAG, "MQTT 连接失败: ${e.message}")
            throw e
        }
    }
}
