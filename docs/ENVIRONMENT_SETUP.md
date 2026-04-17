# 环境配置说明 (Environment Setup)

> [!IMPORTANT]
> 本文档记录了针对当前主机的特定路径，以便开发环境快速恢复。如有路径变更，请及时更新。

## 项目核心路径

| 组件 | 绝对路径 | 说明 |
| :--- | :--- | :--- |
| **项目根目录** | `d:\Software\antigravity\phone_sorter` | 包含源码、文档及测试脚本的整体项目目录 |
| **Android 项目目录** | `d:\Software\antigravity\phone_sorter\src\android` | 包含 Gradle 配置和 Android 源码 |
| **Android SDK** | `C:\Users\feng\AppData\Local\Android\Sdk` | Android 开发工具包 |
| **ADB 工具** | `C:\Users\feng\AppData\Local\Android\Sdk\platform-tools\adb.exe` | Android 调试桥 |
| **Java (JBR)** | `C:\Program Files\Android\Android Studio\jbr` | Android Studio 内置 JDK |

## 系统环境变量 (已生效)

当前系统已配置以下环境变量，无需额外操作：

1. **`JAVA_HOME`**: `C:\Program Files\Android\Android Studio\jbr`
2. **`ANDROID_HOME`**: `C:\Users\feng\AppData\Local\Android\Sdk`
3. **`Path`**: 包含 `C:\Users\feng\AppData\Local\Android\Sdk\platform-tools`。

## 编译与部署指令

### Android 应用
在 `d:\Software\antigravity\phone_sorter\src\android` 目录下运行：
- **编译**: `./gradlew assembleDebug`
- **安装**: `./gradlew installDebug`

## 硬件运行要求

### 背景颜色选择 (Background Color)
基于 `AlgorithmProcessor.kt` 中的 HSV 阈值设置，分拣合背景必须满足以下要求：

*   **推荐颜色**:
    *   **哑光黑色 (Matte Black)**: 最佳选择。`V < 50` 自动被算法滤除，对比度最高。
    *   **哑光白色 (Matte White)**: 可选。`S < 50` 自动被算法滤除。
    *   **哑光红色 (Matte Red)**: `H < 30` 或 `H > 160`，完美避开绿色和紫色检测区。
*   **⚠️ 严禁颜色**:
    *   **蓝色 (Blue)**: 算法中紫根检测起始点为 `H=120`（纯蓝），使用蓝色背景将导致紫根检测严重误判。

## 已解决的阻塞点

1. **OpenCV 缺失**: `src/android/opencv` 仅包含部分类定义，编译 Aruco 时需核实 AAR 完整性。
2. **SDK 侦测**: Gradle 默认不自动识别 SDK，需在 `local.properties` 中指定。
3. **ArUco 坐标对齐 (Overlay Scaling)**: Android `TextureView` 默认使用 `CenterCrop` 策略。因此 `OverlayView` 的坐标映射矩阵必须同样使用 `CenterCrop` 逻辑，以防止坐标偏移。
