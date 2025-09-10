# 开发规范文档（简化版 - 纯OpenCV实现）

## 项目架构规范

### 1. 整体架构设计

#### 架构模式
- **MVC架构**：采用Model-View-Controller架构模式
- **简化分层**：
  - UI Layer（界面层）
  - Processing Layer（处理层）
  - Data Layer（数据层）

#### 模块划分
```
app/
├── ui/                       # 界面组件
├── processing/              # 图像处理
├── utils/                   # 工具类
└── models/                  # 数据模型
```

### 2. 编码规范

#### 命名规范
- **类名**：使用PascalCase，如`AsparagusAnalyzer`
- **函数名**：使用camelCase，如`calculateEffectiveLength()`
- **变量名**：使用camelCase，如`asparagusImage`
- **常量名**：使用UPPER_SNAKE_CASE，如`MAX_IMAGE_SIZE`

#### 代码格式
- **缩进**：使用4个空格进行缩进
- **行长**：每行代码不超过120个字符
- **注释**：复杂算法需要详细注释

### 3. OpenCV图像处理规范

#### 核心处理流程
1. **图像预处理**
   - 尺寸调整：保持宽高比缩放至合适尺寸
   - 颜色转换：RGB → HSV色彩空间
   - 降噪处理：高斯滤波去除噪声

2. **颜色分割**
   - 白色识别：HSV阈值 [0,0,200] ~ [180,30,255]
   - 紫色识别：HSV阈值 [125,50,50] ~ [165,255,255]
   - 绿色识别：HSV阈值 [35,50,50] ~ [85,255,255]

3. **轮廓检测**
   - Canny边缘检测（阈值：50,150）
   - findContours查找芦笋主体轮廓
   - approxPolyDP轮廓近似

4. **几何测量**
   - 长度计算：轮廓端点距离
   - 粗度计算：轮廓宽度分析
   - 透视校正：基于参照物的比例计算

#### OpenCV使用最佳实践
- **资源管理**：使用try-with-resources自动释放Mat对象
- **内存优化**：及时释放临时图像对象
- **性能调优**：使用ROI减少处理区域

### 4. 核心算法实现

#### 芦笋检测算法
```kotlin
// 主要步骤
1. 读取图像 → Imgcodecs.imread()
2. 颜色分割 → Core.inRange()
3. 轮廓提取 → Imgproc.findContours()
4. 长度测量 → 计算轮廓端点距离
5. 粗度测量 → 计算轮廓最大宽度
```

#### 颜色分段算法
```kotlin
// HSV阈值设置
val whiteLower = Scalar(0.0, 0.0, 200.0)
val whiteUpper = Scalar(180.0, 30.0, 255.0)
val purpleLower = Scalar(125.0, 50.0, 50.0)
val purpleUpper = Scalar(165.0, 255.0, 255.0)
val greenLower = Scalar(35.0, 50.0, 50.0)
val greenUpper = Scalar(85.0, 255.0, 255.0)
```

### 5. 性能优化

#### 处理优化
- **异步处理**：使用Kotlin协程处理图像
- **缓存策略**：缓存处理结果避免重复计算
- **内存管理**：使用对象池复用Bitmap对象

#### 精度优化
- **亚像素精度**：使用亚像素级轮廓检测
- **多次测量**：取多次测量结果的平均值
- **异常值过滤**：去除明显异常的数据点

### 5. 测试规范

#### 测试策略
- **单元测试**：测试各个处理步骤的正确性
- **集成测试**：测试完整流程的准确性
- **性能测试**：测试不同设备的处理时间

#### 测试数据
- **标准测试集**：50张不同条件下的芦笋图像
- **边界测试**：极端光照和角度条件下的测试
- **精度验证**：与人工测量结果对比验证

### 6. 部署配置

#### 构建要求
- **最低Android版本**：Android 7.0 (API 24)
- **OpenCV版本**：4.5.0以上
- **NDK支持**：包含必要的native库

#### 发布检查清单
- [ ] 所有图像处理功能测试通过
- [ ] 不同设备兼容性测试完成
- [ ] 性能基准测试达标
- [ ] 内存泄漏检查完成

### 7. 技术栈

#### 核心技术
- **开发语言**：Kotlin
- **图像处理**：OpenCV Android SDK
- **相机接口**：Android Camera2 API
- **数据存储**：SharedPreferences + 文件存储

#### 依赖库
```gradle
implementation 'org.opencv:opencv-android:4.5.1.1'
implementation 'androidx.camera:camera-camera2:1.2.3'
implementation 'androidx.camera:camera-lifecycle:1.2.3'
implementation 'androidx.camera:camera-view:1.2.3'
```

---

*开发规范版本：v2.0 - 简化版*
*更新日期：2024年*
*变更说明：去除机器学习相关内容，专注于OpenCV纯计算机视觉实现*