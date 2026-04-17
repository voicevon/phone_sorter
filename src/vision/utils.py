import cv2
import numpy as np
from typing import Tuple, Optional

def get_perspective_matrix(src_points: np.ndarray, marker_distance_mm: float) -> Tuple[np.ndarray, Tuple[int, int]]:
    """计算透视变换矩阵"""
    # 定义目标点（正视图，保持500mm距离）
    target_width = int(marker_distance_mm * 2)  # 放大2倍以提高精度
    target_height = int(target_width * 0.75)  # 3:4比例

    dst_points = np.array([
        [target_width * 0.25, target_height * 0.25],  # 左上
        [target_width * 0.75, target_height * 0.25],  # 右上
        [target_width * 0.75, target_height * 0.75],  # 右下
        [target_width * 0.25, target_height * 0.75]   # 左下
    ], dtype=np.float32)

    matrix = cv2.getPerspectiveTransform(src_points, dst_points)
    return matrix, (target_width, target_height)

def apply_perspective(image: np.ndarray, matrix: np.ndarray, size: Tuple[int, int]) -> np.ndarray:
    """应用透视变换"""
    return cv2.warpPerspective(image, matrix, size)

def draw_marker_info(image: np.ndarray, markers: list):
    """在图像上绘制标记信息"""
    for marker in markers:
        corners = marker['corners'].astype(int)
        cv2.polylines(image, [corners], True, (0, 255, 0), 2)
        center = marker['center'].astype(int)
        cv2.circle(image, tuple(center), 5, (0, 0, 255), -1)
        cv2.putText(image, f"ID:{marker['id']}", tuple(center + 10),
                   cv2.FONT_HERSHEY_SIMPLEX, 0.5, (255, 255, 255), 2)
