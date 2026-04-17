"""
芦笋分级系统 - 计算机视觉算法模块
实现ARUCO标记检测、透视变换、芦笋轮廓提取、尺寸测量和分级算法
"""

import cv2
import numpy as np
from typing import Tuple, List, Optional, Dict


class AsparagusGradingAlgorithm:
    """芦笋分级算法类"""

    # ARUCO标记参数
    ARUCO_DICT = cv2.aruco.DICT_4X4_50
    MARKER_IDS = [10, 13, 40, 42]
    MARKER_DISTANCE_MM = 50.0  # 标记中心距离 50mm

    # 直径测量参数
    DIAMETER_MEASUREMENT_POSITIONS = [10, 15, 20, 25, 30]  # 紫根往上的位置（mm）
    DIAMETER_PRECISION = 0.2  # 直径精度±0.2mm

    # 长度测量参数
    LENGTH_PRECISION = 2.0  # 长度精度±2mm

    # 分级阈值（仅根据直径）
    GRADE_THRESHOLDS = {
        'A': 15.0,   # > 15.0mm
        'B': 12.0,   # 12.0 - 15.0mm
        'C': 10.0,   # 10.0 - 12.0mm
        'D': 8.0,    # 8.0 - 10.0mm
        'E': 5.0     # 5.0 - 8.0mm
    }

    def __init__(self):
        """初始化算法"""
        self.aruco_dict = cv2.aruco.getPredefinedDictionary(self.ARUCO_DICT)
        self.aruco_params = cv2.aruco.DetectorParameters()
        self.detector = cv2.aruco.ArucoDetector(self.aruco_dict, self.aruco_params)

        # 存储检测结果
        self.markers = None
        self.perspective_matrix = None
        self.warped_image = None
        self.contour = None
        self.purple_root_position = None
        self.diameters = None
        self.average_diameter = None
        self.effective_length = None
        self.grade = None

    def detect_aruco_markers(self, image: np.ndarray) -> Tuple[bool, List[Dict]]:
        """
        检测ARUCO标记

        Args:
            image: 输入图像

        Returns:
            (success, markers): 是否成功检测到标记，标记信息列表
        """
        # 检测ARUCO标记
        corners, ids, rejected = self.detector.detectMarkers(image)

        if ids is None or len(ids) < 4:
            return False, []

        # 筛选需要的标记ID
        markers = []
        for i, marker_id in enumerate(ids):
            if marker_id[0] in self.MARKER_IDS:
                center = np.mean(corners[i][0], axis=0)
                markers.append({
                    'id': int(marker_id[0]),
                    'center': center,
                    'corners': corners[i][0]
                })

        # 检查是否检测到两个需要的标记
        if len(markers) != 2:
            return False, []

        self.markers = markers
        return True, markers

    def calculate_perspective_transform(self, image: np.ndarray) -> Tuple[bool, Optional[np.ndarray]]:
        """
        计算透视变换矩阵

        Args:
            image: 输入图像

        Returns:
            (success, matrix): 是否成功计算，透视变换矩阵
        """
        if self.markers is None or len(self.markers) != 2:
            return False, None

        # 获取两个标记
        marker1 = self.markers[0]
        marker2 = self.markers[1]

        # 确定左右顺序
        if marker1['center'][0] < marker2['center'][0]:
            left_marker = marker1
            right_marker = marker2
        else:
            left_marker = marker2
            right_marker = marker1

        # 计算两个标记之间的像素距离
        pixel_distance = np.linalg.norm(right_marker['center'] - left_marker['center'])

        # 计算像素到毫米的比例
        self.pixel_to_mm_ratio = self.MARKER_DISTANCE_MM / pixel_distance

        # 定义源点（使用两个标记的四个角点）
        # 左标记的左上和左下角点，右标记的右上和右下角点
        left_corners = left_marker['corners']
        right_corners = right_marker['corners']

        # 找到每个标记的四个角点
        # 假设角点顺序是：左上、右上、右下、左下
        src_points = np.array([
            left_corners[0],  # 左标记左上
            right_corners[1],  # 右标记右上
            right_corners[2],  # 右标记右下
            left_corners[3]   # 左标记左下
        ], dtype=np.float32)

        # 定义目标点（正视图，保持500mm距离）
        target_width = int(self.MARKER_DISTANCE_MM * 2)  # 放大2倍以提高精度
        target_height = int(target_width * 0.75)  # 3:4比例

        dst_points = np.array([
            [target_width * 0.25, target_height * 0.25],  # 左上
            [target_width * 0.75, target_height * 0.25],  # 右上
            [target_width * 0.75, target_height * 0.75],  # 右下
            [target_width * 0.25, target_height * 0.75]   # 左下
        ], dtype=np.float32)

        # 计算透视变换矩阵
        self.perspective_matrix = cv2.getPerspectiveTransform(src_points, dst_points)
        self.target_size = (target_width, target_height)

        return True, self.perspective_matrix

    def apply_perspective_transform(self, image: np.ndarray) -> Optional[np.ndarray]:
        """
        应用透视变换

        Args:
            image: 输入图像

        Returns:
            变换后的图像
        """
        if self.perspective_matrix is None:
            return None

        self.warped_image = cv2.warpPerspective(
            image,
            self.perspective_matrix,
            self.target_size
        )

        return self.warped_image

    def extract_asparagus_contour(self, image: np.ndarray) -> Tuple[bool, Optional[np.ndarray]]:
        """
        提取芦笋轮廓

        Args:
            image: 输入图像（透视变换后的图像）

        Returns:
            (success, contour): 是否成功提取，轮廓
        """
        if image is None:
            return False, None

        # 转换为灰度图
        gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)

        # 高斯模糊去噪
        blurred = cv2.GaussianBlur(gray, (5, 5), 0)

        # 自适应阈值分割
        binary = cv2.adaptiveThreshold(
            blurred, 255,
            cv2.ADAPTIVE_THRESH_GAUSSIAN_C,
            cv2.THRESH_BINARY_INV, 11, 2
        )

        # 形态学操作
        kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (3, 3))
        binary = cv2.morphologyEx(binary, cv2.MORPH_CLOSE, kernel, iterations=2)
        binary = cv2.morphologyEx(binary, cv2.MORPH_OPEN, kernel, iterations=1)

        # 查找轮廓
        contours, _ = cv2.findContours(binary, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)

        if not contours:
            return False, None

        # 找到最大的轮廓（假设是芦笋）
        largest_contour = max(contours, key=cv2.contourArea)

        # 过滤掉太小的轮廓
        if cv2.contourArea(largest_contour) < 100:
            return False, None

        self.contour = largest_contour
        return True, largest_contour

    def detect_purple_root(self, image: np.ndarray, contour: np.ndarray) -> Tuple[bool, Optional[Tuple[int, int]]]:
        """
        检测紫根位置（绿色到白色交界处）

        Args:
            image: 输入图像
            contour: 芦笋轮廓

        Returns:
            (success, position): 是否成功检测，紫根位置坐标
        """
        if contour is None:
            return False, None

        # 获取轮廓的边界矩形
        x, y, w, h = cv2.boundingRect(contour)

        # 转换为HSV颜色空间
        hsv = cv2.cvtColor(image, cv2.COLOR_BGR2HSV)

        # 定义紫色范围（紫根颜色）
        lower_purple = np.array([120, 50, 50])
        upper_purple = np.array([160, 255, 255])

        # 创建紫色掩码
        purple_mask = cv2.inRange(hsv, lower_purple, upper_purple)

        # 在轮廓区域内查找紫色像素
        roi_mask = np.zeros_like(purple_mask)
        cv2.drawContours(roi_mask, [contour], -1, 255, -1)
        purple_in_contour = cv2.bitwise_and(purple_mask, roi_mask)

        # 找到紫色像素的位置
        purple_points = np.column_stack(np.where(purple_in_contour > 0))

        if len(purple_points) == 0:
            # 如果没有检测到紫色，使用颜色梯度方法
            return self._detect_color_gradient(image, contour)

        # 计算紫色区域的中心
        purple_center = np.mean(purple_points, axis=0)
        position = (int(purple_center[1]), int(purple_center[0]))  # (x, y)

        self.purple_root_position = position
        return True, position

    def _detect_color_gradient(self, image: np.ndarray, contour: np.ndarray) -> Tuple[bool, Optional[Tuple[int, int]]]:
        """
        使用颜色梯度方法检测紫根位置（绿色到白色交界处）

        Args:
            image: 输入图像
            contour: 芦笋轮廓

        Returns:
            (success, position): 是否成功检测，紫根位置坐标
        """
        # 获取轮廓的边界矩形
        x, y, w, h = cv2.boundingRect(contour)

        # 转换为HSV颜色空间
        hsv = cv2.cvtColor(image, cv2.COLOR_BGR2HSV)

        # 沿着轮廓的中线从上到下扫描
        center_x = x + w // 2
        max_saturation_change = 0
        best_position = None
        prev_saturation = None

        for py in range(y, y + h):
            # 检查该点是否在轮廓内
            point = (center_x, py)
            if cv2.pointPolygonTest(contour, point, False) < 0:
                continue

            # 获取该点的颜色
            pixel = hsv[py, center_x]
            saturation = int(pixel[1])  # 饱和度

            # 计算饱和度变化
            if prev_saturation is not None:
                saturation_change = prev_saturation - saturation
                # 寻找饱和度急剧下降的位置（绿色到白色）
                if saturation_change > max_saturation_change:
                    max_saturation_change = saturation_change
                    best_position = point

            prev_saturation = saturation

        # 如果没有找到明显的饱和度变化，使用轮廓的中点
        if best_position is None:
            best_position = (center_x, y + h // 2)

        self.purple_root_position = best_position
        return True, best_position

    def measure_diameters(self, image: np.ndarray, contour: np.ndarray,
                         purple_root_position: Tuple[int, int]) -> Tuple[bool, List[float], float]:
        """
        测量直径（紫根往上10mm、15mm、20mm、25mm、30mm各取一处）

        Args:
            image: 输入图像
            contour: 芦笋轮廓
            purple_root_position: 紫根位置

        Returns:
            (success, diameters, average): 是否成功测量，直径列表，平均直径
        """
        if contour is None or purple_root_position is None:
            return False, [], 0.0

        diameters = []

        for position_mm in self.DIAMETER_MEASUREMENT_POSITIONS:
            # 计算测量点的像素位置（紫根往上）
            offset_pixels = int(position_mm / self.pixel_to_mm_ratio)
            measure_y = purple_root_position[1] - offset_pixels

            # 在该水平线上找到轮廓的左右边界
            left_x, right_x = self._find_contour_boundaries(contour, measure_y, image.shape)

            if left_x is None or right_x is None:
                continue

            # 计算直径（像素）
            diameter_pixels = right_x - left_x

            # 转换为毫米
            diameter_mm = diameter_pixels * self.pixel_to_mm_ratio
            diameters.append(diameter_mm)

        if len(diameters) == 0:
            return False, [], 0.0

        # 计算平均直径
        average_diameter = np.mean(diameters)

        self.diameters = diameters
        self.average_diameter = average_diameter

        return True, diameters, average_diameter

    def _find_contour_boundaries(self, contour: np.ndarray, y: int, image_shape: Tuple[int, int, int]) -> Tuple[Optional[int], Optional[int]]:
        """
        在指定y坐标处找到轮廓的左右边界

        Args:
            contour: 轮廓
            y: y坐标
            image_shape: 图像形状 (height, width, channels)

        Returns:
            (left_x, right_x): 左右边界x坐标
        """
        # 创建掩码，使用实际图像大小
        height, width = image_shape[:2]
        mask = np.zeros((height, width), dtype=np.uint8)
        cv2.drawContours(mask, [contour], -1, 255, -1)

        # 检查y坐标是否在有效范围内
        if y < 0 or y >= height:
            return None, None

        # 获取该行的掩码
        row = mask[y, :]

        # 找到第一个和最后一个255像素
        indices = np.where(row > 0)[0]

        if len(indices) == 0:
            return None, None

        left_x = indices[0]
        right_x = indices[-1]

        return left_x, right_x

    def calculate_effective_length(self, contour: np.ndarray,
                                   purple_root_position: Tuple[int, int]) -> Tuple[bool, float]:
        """
        计算有效长度（从头部到紫根）

        Args:
            contour: 芦笋轮廓
            purple_root_position: 紫根位置

        Returns:
            (success, length): 是否成功计算，有效长度（mm）
        """
        if contour is None or purple_root_position is None:
            return False, 0.0

        # 获取轮廓的边界矩形
        x, y, w, h = cv2.boundingRect(contour)

        # 头部位置（轮廓的最上方）
        head_y = y

        # 计算有效长度（像素）
        length_pixels = purple_root_position[1] - head_y

        # 转换为毫米
        length_mm = length_pixels * self.pixel_to_mm_ratio

        self.effective_length = length_mm

        return True, length_mm

    def grade_asparagus(self, diameter: float) -> str:
        """
        根据直径对芦笋进行分级

        Args:
            diameter: 平均直径（mm）

        Returns:
            等级（A/B/C/D/E）
        """
        if diameter > self.GRADE_THRESHOLDS['A']:
            self.grade = 'A'
            return 'A'
        elif diameter > self.GRADE_THRESHOLDS['B']:
            self.grade = 'B'
            return 'B'
        elif diameter > self.GRADE_THRESHOLDS['C']:
            self.grade = 'C'
            return 'C'
        elif diameter > self.GRADE_THRESHOLDS['D']:
            self.grade = 'D'
            return 'D'
        elif diameter > self.GRADE_THRESHOLDS['E']:
            self.grade = 'E'
            return 'E'
        else:
            self.grade = 'F'  # 低于E级
            return 'F'

    def process_image(self, image: np.ndarray) -> Dict:
        """
        完整的图像处理流程

        Args:
            image: 输入图像

        Returns:
            包含所有结果的字典
        """
        result = {
            'success': False,
            'grade': None,
            'diameter': 0.0,
            'diameters': [],
            'length': 0.0,
            'purple_root_position': None,
            'error': None
        }

        try:
            # 1. 检测ARUCO标记
            success, markers = self.detect_aruco_markers(image)
            if not success:
                result['error'] = 'Failed to detect ARUCO markers'
                return result

            # 2. 计算透视变换矩阵
            success, matrix = self.calculate_perspective_transform(image)
            if not success:
                result['error'] = 'Failed to calculate perspective transform'
                return result

            # 3. 应用透视变换
            warped = self.apply_perspective_transform(image)
            if warped is None:
                result['error'] = 'Failed to apply perspective transform'
                return result

            # 4. 提取芦笋轮廓
            success, contour = self.extract_asparagus_contour(warped)
            if not success:
                result['error'] = 'Failed to extract asparagus contour'
                return result

            # 5. 检测紫根位置
            success, purple_pos = self.detect_purple_root(warped, contour)
            if not success:
                result['error'] = 'Failed to detect purple root position'
                return result

            # 6. 测量直径
            success, diameters, avg_diameter = self.measure_diameters(warped, contour, purple_pos)
            if not success:
                result['error'] = 'Failed to measure diameters'
                return result

            # 7. 计算有效长度
            success, length = self.calculate_effective_length(contour, purple_pos)
            if not success:
                result['error'] = 'Failed to calculate effective length'
                return result

            # 8. 分级
            grade = self.grade_asparagus(avg_diameter)

            # 返回结果
            result['success'] = True
            result['grade'] = grade
            result['diameter'] = round(avg_diameter, 2)
            result['diameters'] = [round(d, 2) for d in diameters]
            result['length'] = round(length, 2)
            result['purple_root_position'] = purple_pos

        except Exception as e:
            result['error'] = str(e)

        return result

    def get_visualization(self, image: np.ndarray) -> Optional[np.ndarray]:
        """
        获取可视化结果

        Args:
            image: 原始图像

        Returns:
            可视化图像
        """
        if self.markers is None:
            return None

        # 复制图像
        vis = image.copy()

        # 绘制ARUCO标记
        for marker in self.markers:
            corners = marker['corners'].astype(int)
            cv2.polylines(vis, [corners], True, (0, 255, 0), 2)
            center = marker['center'].astype(int)
            cv2.circle(vis, tuple(center), 5, (0, 0, 255), -1)
            cv2.putText(vis, f"ID:{marker['id']}", tuple(center + 10),
                       cv2.FONT_HERSHEY_SIMPLEX, 0.5, (255, 255, 255), 2)

        # 如果有变换后的图像，绘制轮廓和测量点
        if self.warped_image is not None and self.contour is not None:
            # 绘制轮廓
            cv2.drawContours(self.warped_image, [self.contour], -1, (0, 255, 0), 2)

            # 绘制紫根位置
            if self.purple_root_position is not None:
                cv2.circle(self.warped_image, self.purple_root_position, 5, (255, 0, 0), -1)
                cv2.putText(self.warped_image, "Purple Root", self.purple_root_position,
                           cv2.FONT_HERSHEY_SIMPLEX, 0.5, (255, 255, 255), 2)

            # 绘制直径测量点
            if self.purple_root_position is not None:
                for position_mm in self.DIAMETER_MEASUREMENT_POSITIONS:
                    offset_pixels = int(position_mm / self.pixel_to_mm_ratio)
                    measure_y = self.purple_root_position[1] - offset_pixels
                    cv2.line(self.warped_image, (0, measure_y),
                            (self.warped_image.shape[1], measure_y), (255, 255, 0), 1)

        return vis
