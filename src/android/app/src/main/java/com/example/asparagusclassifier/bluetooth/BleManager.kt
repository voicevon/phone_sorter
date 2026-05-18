package com.example.asparagusclassifier.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.*

@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {
    companion object {
        private const val TAG = "BleManager"
        
        // BLE Profile UUIDs
        val SERVICE_UUID: UUID = UUID.fromString("1c95d5e3-d8f7-413a-bf3d-7a2e5d7be87e")
        val TARGET_CHAR_UUID: UUID = UUID.fromString("1c95d5e3-d8f7-413a-bf3d-7a2e5d7be87d")
        val STATUS_CHAR_UUID: UUID = UUID.fromString("1c95d5e3-d8f7-413a-bf3d-7a2e5d7be87c")
        val ERROR_CHAR_UUID: UUID = UUID.fromString("2c95d5e3-d8f7-413a-bf3d-7a2e5d7be87e")
        val COMMAND_CHAR_UUID: UUID = UUID.fromString("3c95d5e3-d8f7-413a-bf3d-7a2e5d7be87e")
        
        // BLE Descriptor for Notifications (Client Characteristic Configuration Descriptor - CCCD)
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        DISCOVERED
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var bluetoothGatt: BluetoothGatt? = null
    
    // Connection State Flows
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    // ESP32 Status & Error State Flows
    private val _esp32State = MutableStateFlow(0) // 0=IDLE, 1=HOMING, 2=RUNNING, 3=ERROR
    val esp32State: StateFlow<Int> = _esp32State

    private val _esp32Error = MutableStateFlow(0) // 0=NONE, 1=TIMEOUT, 2=JAM
    val esp32Error: StateFlow<Int> = _esp32Error

    private var isScanning = false
    private val handler = Handler(Looper.getMainLooper())
    private val SCAN_PERIOD = 15000L // Scan for 15 seconds
    
    // Reconnect flag
    private var shouldAutoReconnect = false
    private var targetDevice: BluetoothDevice? = null

    // Target Characteristics Cache
    private var targetChar: BluetoothGattCharacteristic? = null
    private var commandChar: BluetoothGattCharacteristic? = null

    // Scan Callback
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult?) {
            result?.device?.let { device ->
                val deviceName = device.name ?: ""
                Log.d(TAG, "发现蓝牙外设: $deviceName [${device.address}]")
                if (deviceName == "Sorter_Controller" || device.address != null) {
                    Log.i(TAG, "匹配到目标分拣机，正在停止扫描并建立 GATT 连接...")
                    stopScan()
                    connectToDevice(device)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "扫描外设失败，错误码: $errorCode")
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    // GATT Client Callback
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "GATT 状态异常: status = $status, newState = $newState")
                handleDisconnect()
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "GATT 连接建立成功，开始发现服务...")
                    _connectionState.value = ConnectionState.CONNECTED
                    gatt?.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.w(TAG, "GATT 连接已断开")
                    handleDisconnect()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "服务发现失败: status = $status")
                return
            }

            val service = gatt?.getService(SERVICE_UUID)
            if (service != null) {
                Log.i(TAG, "匹配到分拣机 Service: $SERVICE_UUID")
                targetChar = service.getCharacteristic(TARGET_CHAR_UUID)
                commandChar = service.getCharacteristic(COMMAND_CHAR_UUID)
                
                _connectionState.value = ConnectionState.DISCOVERED
                
                // 自动订阅 Notify 状态和错误 (添加小延时以提高稳定度)
                handler.postDelayed({
                    subscribeToNotifications(gatt, service.getCharacteristic(STATUS_CHAR_UUID))
                }, 500)
                
                handler.postDelayed({
                    subscribeToNotifications(gatt, service.getCharacteristic(ERROR_CHAR_UUID))
                }, 1000)
            } else {
                Log.e(TAG, "外设未能提供指定的服务 UUID: $SERVICE_UUID")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?
        ) {
            characteristic?.let { char ->
                val value = char.value
                if (value != null && value.isNotEmpty()) {
                    val code = value[0].toInt()
                    when (char.uuid) {
                        STATUS_CHAR_UUID -> {
                            Log.d(TAG, "ESP32 状态更新通知: $code")
                            _esp32State.value = code
                        }
                        ERROR_CHAR_UUID -> {
                            Log.w(TAG, "ESP32 错误警报通知: $code")
                            _esp32Error.value = code
                        }
                    }
                }
            }
        }
    }

    /**
     * 开始扫描分拣机设备
     */
    fun startScan() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.e(TAG, "蓝牙未启用，无法启动扫描")
            return
        }

        if (isScanning) return
        
        shouldAutoReconnect = true
        _connectionState.value = ConnectionState.CONNECTING
        isScanning = true
        
        val scanner = bluetoothAdapter.bluetoothLeScanner
        val filters = listOf(
            ScanFilter.Builder().setDeviceName("Sorter_Controller").build(),
            ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        Log.i(TAG, "启动低功耗蓝牙扫描，目标: Sorter_Controller...")
        scanner.startScan(filters, settings, scanCallback)

        // 扫描超时保护
        handler.postDelayed({
            if (isScanning && _connectionState.value == ConnectionState.CONNECTING) {
                Log.w(TAG, "扫描超时，未发现目标设备")
                stopScan()
                _connectionState.value = ConnectionState.DISCONNECTED
            }
        }, SCAN_PERIOD)
    }

    /**
     * 停止扫描
     */
    fun stopScan() {
        if (!isScanning) return
        isScanning = false
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        Log.d(TAG, "停止低功耗蓝牙扫描")
    }

    /**
     * 连接到设备
     */
    private fun connectToDevice(device: BluetoothDevice) {
        targetDevice = device
        Log.i(TAG, "正在发起 GATT 物理连接 [${device.address}]")
        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    /**
     * 断开连接
     */
    fun disconnect() {
        shouldAutoReconnect = false
        stopScan()
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        targetDevice = null
        targetChar = null
        commandChar = null
        _connectionState.value = ConnectionState.DISCONNECTED
        Log.i(TAG, "手动断开蓝牙连接并释放资源")
    }

    /**
     * 订阅通知 (Enable Notification CCCD)
     */
    private fun subscribeToNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic?) {
        if (characteristic == null) return
        
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(CCCD_UUID)
        if (descriptor != null) {
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            val success = gatt.writeDescriptor(descriptor)
            Log.d(TAG, "启用特征 Notify 通知 [${characteristic.uuid}]: 发送=$success")
        } else {
            Log.e(TAG, "无法获取特征通知的 CCCD 描述符: ${characteristic.uuid}")
        }
    }

    /**
     * 处理连接断开，触发自动重连
     */
    private fun handleDisconnect() {
        _connectionState.value = ConnectionState.DISCONNECTED
        _esp32State.value = 0
        _esp32Error.value = 0
        targetChar = null
        commandChar = null
        
        bluetoothGatt?.close()
        bluetoothGatt = null

        if (shouldAutoReconnect && targetDevice != null) {
            Log.w(TAG, "连接异常断开，开启 3 秒延迟自动重连...")
            handler.postDelayed({
                if (shouldAutoReconnect && targetDevice != null) {
                    connectToDevice(targetDevice!!)
                }
            }, 3000)
        }
    }

    /**
     * 向 ESP32 下发分拣槽位结果
     */
    fun sendTargetId(targetId: Int): Boolean {
        val gatt = bluetoothGatt ?: return false
        val char = targetChar ?: return false
        if (_connectionState.value != ConnectionState.DISCOVERED) return false

        char.value = byteArrayOf(targetId.toByte())
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val success = gatt.writeCharacteristic(char)
        Log.i(TAG, "下发分拣槽位数据: Target ID = $targetId -> 发送结果 = $success")
        return success
    }

    /**
     * 向 ESP32 下发系统指令
     */
    fun sendCommand(cmd: Int): Boolean {
        val gatt = bluetoothGatt ?: return false
        val char = commandChar ?: return false
        if (_connectionState.value != ConnectionState.DISCOVERED) return false

        char.value = byteArrayOf(cmd.toByte())
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val success = gatt.writeCharacteristic(char)
        Log.w(TAG, "发送系统维护指令: CMD = 0x%02X -> 发送结果 = $success".format(cmd))
        return success
    }
}
