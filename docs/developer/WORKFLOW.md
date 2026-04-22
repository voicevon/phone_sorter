# 开发者工作流 (WORKFLOW)

本文档定义了芦笋分级系统的标准化开发、构建和调试流程。

## 1. 编译与部署
1.  **构建 APK**：在项目根目录运行 `./gradlew.bat assembleDebug`。
2.  **安装**：使用 `adb install -r app/build/outputs/apk/debug/app-debug.apk`。
3.  **运行**：直接在手机上启动 "Asparagus Sorter"。

## 2. 实时监控与日志
使用以下命令观察视觉管道的核心状态：
```bash
# 查看视觉处理算法的详细日志
adb logcat -s AlgorithmProcessor

# 查看 MQTT 数据上报状态
adb logcat -s MqttReporter
```

## 3. 常见任务
- **调整分级阈值**：修改 `AlgorithmResult.kt` 或视觉核心中的 `GRADING_LOGIC`。
- **更新 UI 叠加层**：在 `OverlayView.kt` 中调整绘制逻辑。
- **验证坐标映射**：切换至“视图 3 (Analysis)”，确认芦笋轮廓是否与物理实物完美贴合。
