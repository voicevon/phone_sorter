# 软硬件兼容性与诊断 (COMPATIBILITY_DIAGNOSTICS)

系统在多种 Android 硬件环境下运行，必须具备鲁棒的降级与诊断能力。

## 1. 镜头内参 (Lens Intrinsics) 兜底逻辑
如果设备无法通过 API 返回有效的 `LENS_INTRINSIC_CALIBRATION`（如返回全 0）：
1.  **自动降级**：跳过 Canvas 2 的去畸变处理。
2.  **MQTT 上报**：发送 `InvalidIntrinsic` 标志位及 `Build.MODEL`，供后续云端分析。
3.  **用户反馈**：在 UI 上显示轻量级警告，告知精度可能存在 10%-15% 的偏差。

## 2. 多摄像头动态同步
针对具备多个物理摄像头的手机，系统会实时检测当前激活摄像头的内参：
- **逐帧监控**：从 `CaptureResult` 中提取每一帧的实时内参。
- **物理校准补偿**：针对特定品牌（如华为、小米）的 API 限制，预留了基于传感器物理尺寸的估算公式。

## 3. MQTT 诊断协议
- **服务器**: `voicevon.vicp.io`
- **Topic**: `phone_sorter/${brand}/${model}/result`
- **Payload 包含**：
  - 分级结果 (Grade)
  - 测量原始数据 (Diameter, Length)
  - 硬件诊断信息 (IsUndistorted, FrameProcessingTime)
