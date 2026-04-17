# Phone Sorter (芦笋分级手机应用)

这是一个纯粹的 Android 应用程序，旨在利用手机摄像头实现芦笋的自动识别与分级。

## 项目声明

*   **独立性**：本项目是一个独立的 Android 开发项目，与 ESP32 控制系统没有任何关系。
*   **核心功能**：通过手机摄像头采集图像，利用内置算法识别芦笋并根据预设标准进行分级。
*   **项目根目录**：`d:\Software\antigravity\phone_sorter`

## 环境配置

有关本地开发环境的详细配置（包括 Android SDK、JDK 路径及编译指令），请参考：
*   [环境配置说明 (ENVIRONMENT_SETUP.md)](docs/ENVIRONMENT_SETUP.md)

## 主要组件

*   `src/android/`：原生 Android 应用程序源码。
*   `src/vision/`：配套的计算机视觉算法模块。
*   `docs/`：项目相关技术文档。

---
*由 Antigravity 辅助维护*