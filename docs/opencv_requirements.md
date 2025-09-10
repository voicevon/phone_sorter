# OpenCV图像处理需求规格

## OpenCV核心算法需求

### 1. 颜色分割算法

#### HSV阈值设置
- **白色区域**：
  - H: [0, 180] (色相无限制)
  - S: [0, 30] (低饱和度)
  - V: [200, 255] (高亮度)

- **紫色区域**：
  - H: [125, 165] (紫色色相范围)
  - S: [50, 255] (中高饱和度)
  - V: [50, 255] (亮度适中)

- **绿色区域**：
  - H: [35, 85] (绿色色相范围)
  - S: [50, 255] (中高饱和度)
  - V: [50, 255] (亮度适中)

#### 颜色分割流程
```kotlin
// 1. RGB转HSV
Imgproc.cvtColor(src, hsv, Imgproc.COLOR_RGB2HSV)

// 2. 创建掩码
Core.inRange(hsv, lowerBound, upperBound, mask)

// 3. 形态学操作
Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel)
Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, kernel)
```

### 2. 轮廓检测算法

#### Canny边缘检测参数
- **低阈值**：50
- **高阈值**：150
- **核大小**：3×3

#### 轮廓筛选条件
- **最小面积**：1000像素
- **长宽比**：>3:1 (芦笋形状特征)
- **轮廓近似**：使用approxPolyDP，epsilon=0.02×周长

#### 主轮廓确定
```kotlin
// 1. 查找轮廓
val contours = ArrayList<MatOfPoint>()
Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

// 2. 筛选最大轮廓
val maxContour = contours.maxBy { Imgproc.contourArea(it) }

// 3. 获取边界矩形
val boundingRect = Imgproc.boundingRect(maxContour)
```

### 3. 几何测量算法

#### 长度计算
- **方法**：计算轮廓两端点距离
- **端点确定**：轮廓最上端和最下端点
- **精度**：亚像素级计算

#### 粗度计算
- **方法**：计算轮廓最大宽度
- **测量位置**：沿长度方向每10%位置测量
- **结果**：取平均值作为最终粗度

#### 像素-实际尺寸转换
```kotlin
// 参照物已知尺寸计算比例
val pixelToCmRatio = knownRefSizeCm / refObjectPixels
val actualLength = measuredPixels * pixelToCmRatio
```

### 4. 透视校正算法

#### 参照物检测
- **形状**：圆形或方形标准物体
- **检测方法**：
  - 圆形：HoughCircles
  - 方形：findContours + approxPolyDP

#### 梯形校正步骤
1. **检测参照物四个角点**
2. **计算透视变换矩阵**
3. **应用透视变换**
4. **验证校正效果**

### 5. 图像预处理

#### 降噪处理
- **高斯滤波**：核大小5×5，σ=1.5
- **中值滤波**：核大小3×3（去除椒盐噪声）

#### 图像增强
- **对比度增强**：CLAHE算法
- **亮度调整**：自适应直方图均衡化

### 6. 异常处理算法

#### 光照异常检测
- **亮度检测**：计算图像平均亮度
- **阈值范围**：80-220（0-255范围）
- **异常提示**：过亮或过暗时提示用户

#### 轮廓异常处理
- **无轮廓**：提示重新拍摄
- **多轮廓**：提示选择主轮廓
- **轮廓不完整**：提示调整拍摄角度

## OpenCV性能优化

### 内存优化
- **Mat复用**：避免频繁创建临时Mat对象
- **ROI处理**：只对感兴趣区域进行处理
- **及时释放**：处理完成后立即释放Mat资源

### 计算优化
- **多线程**：使用AsyncTask处理图像
- **缓存策略**：缓存中间结果避免重复计算
- **算法选择**：优先选择时间复杂度低的算法

## OpenCV测试验证

### 测试图像集
- **标准图像**：10张均匀光照下的芦笋图像
- **复杂图像**：10张自然光照条件下的图像
- **边界图像**：10张极端条件下的测试图像

### 验证指标
- **颜色分割准确率**：>85%
- **轮廓检测成功率**：>90%
- **长度测量误差**：<5%
- **粗度测量误差**：<10%

### 测试工具
- **OpenCV Manager**：Android测试工具
- **Image Watch**：Visual Studio调试插件
- **自定义测试**：批量处理验证脚本

---

*OpenCV需求版本：v1.0*
*更新日期：2024年*