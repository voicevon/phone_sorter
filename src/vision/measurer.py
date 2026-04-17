import cv2
import numpy as np
from typing import Tuple, List, Optional
from .constants import DIAMETER_MEASUREMENT_POSITIONS

class Measurer:
    def __init__(self, pixel_to_mm_ratio: float):
        self.pixel_to_mm_ratio = pixel_to_mm_ratio

    def detect_purple_root(self, image: np.ndarray, contour: np.ndarray) -> Optional[Tuple[int, int]]:
        """检测紫根位置（绿色到白色交界处）"""
        hsv = cv2.cvtColor(image, cv2.COLOR_BGR2HSV)
        
        # 定义紫色范围
        lower_purple = np.array([120, 50, 50])
        upper_purple = np.array([160, 255, 255])
        
        purple_mask = cv2.inRange(hsv, lower_purple, upper_purple)
        roi_mask = np.zeros_like(purple_mask)
        cv2.drawContours(roi_mask, [contour], -1, 255, -1)
        purple_in_contour = cv2.bitwise_and(purple_mask, roi_mask)
        
        purple_points = np.column_stack(np.where(purple_in_contour > 0))
        
        if len(purple_points) == 0:
            return self._detect_by_gradient(hsv, contour)

        purple_center = np.mean(purple_points, axis=0)
        return (int(purple_center[1]), int(purple_center[0]))

    def _detect_by_gradient(self, hsv: np.ndarray, contour: np.ndarray) -> Optional[Tuple[int, int]]:
        """使用颜色梯度方法检测紫根"""
        x, y, w, h = cv2.boundingRect(contour)
        center_x = x + w // 2
        max_change = 0
        best_pos = None
        prev_sat = None

        for py in range(y, y + h):
            if cv2.pointPolygonTest(contour, (center_x, py), False) < 0:
                continue
            
            sat = int(hsv[py, center_x][1])
            if prev_sat is not None:
                change = prev_sat - sat
                if change > max_change:
                    max_change = change
                    best_pos = (center_x, py)
            prev_sat = sat
        
        return best_pos or (center_x, y + h // 2)

    def measure_diameters(self, mask: np.ndarray, purple_root_pos: Tuple[int, int]) -> List[float]:
        """在指定位置测量直径"""
        diameters = []
        for pos_mm in DIAMETER_MEASUREMENT_POSITIONS:
            offset = int(pos_mm / self.pixel_to_mm_ratio)
            measure_y = purple_root_pos[1] - offset
            
            if 0 <= measure_y < mask.shape[0]:
                row = mask[measure_y, :]
                indices = np.where(row > 0)[0]
                if len(indices) > 0:
                    dia_px = indices[-1] - indices[0]
                    diameters.append(dia_px * self.pixel_to_mm_ratio)
        
        return diameters

    def measure_effective_length(self, contour: np.ndarray, purple_root_pos: Tuple[int, int]) -> float:
        """从头部到紫根的有效长度"""
        _, y, _, _ = cv2.boundingRect(contour)
        length_px = purple_root_pos[1] - y
        return length_px * self.pixel_to_mm_ratio
