# PhoneMeasurer - 手机测量应用

## 项目简介
基于Android的物体尺寸测量应用，使用OpenCV和CameraX实现通过手机摄像头测量物体实际尺寸。

## 功能特性
- 📸 实时相机预览
- 📏 自动物体尺寸测量
- 🎯 基于A4纸参照物的精确计算
- ⚡ 一键拍摄，快速测量
- 📊 测量结果本地存储

## 技术栈
- **Android**: CameraX, Kotlin
- **图像处理**: OpenCV 4.8.1
- **UI**: Material Design
- **存储**: JSON格式数据存储

## 系统需求
- Android 7.0 (API 24) 及以上
- ARM架构处理器
- 2GB RAM以上
- 500万像素以上相机

## 测量原理
1. **圆检测**: 检测A4纸上的4个参照圆
2. **透视校正**: 基于圆心坐标进行透视变换
3. **物体检测**: 自动识别待测物体轮廓
4. **尺寸计算**: 根据参照圆实际尺寸计算物体实际尺寸
5. **误差修正**: 应用误差修正算法提高精度

## 使用说明
1. 将A4纸和参照物平放在桌面
2. 打开应用，将相机对准参照物
3. 确保4个参照圆完整可见
4. 将待测物体放在A4纸上
5. 点击"拍照测量"按钮
6. 查看测量结果

## 参照物规格
- A4纸尺寸: 210mm × 297mm
- 参照圆直径: 20mm
- 参照圆位置: 四圆心坐标(100,100), (400,100), (400,400), (100,400)

## 精度范围
- 测量精度: ±0.1cm
- 允许误差: ±0.5cm
- 拍摄距离: 20cm-100cm
- 倾斜角度: ±30度

## 异常处理
- 图像质量不足 → 提示重新拍摄
- 识别失败 → 提示重新拍摄
- 参照物缺失 → 提示确保完整可见

## 开发环境
- Android Studio Hedgehog
- Kotlin 1.8.10
- Gradle 8.1.0
- OpenCV 4.8.1

## 构建命令
```bash
./gradlew assembleDebug
```

## 安装运行
1. 启用USB调试模式
2. 连接Android设备
3. 运行: `./gradlew installDebug`

## 项目结构
```
PhoneMeasurer/
├── app/
│   ├── src/main/java/com/phonemeasurer/
│   │   ├── MainActivity.kt      # 主界面
│   │   └── ImageAnalyzer.kt     # 图像分析
│   ├── src/main/res/            # 资源文件
│   └── build.gradle            # 应用配置
├── build.gradle                # 项目配置
└── settings.gradle             # 模块配置
```