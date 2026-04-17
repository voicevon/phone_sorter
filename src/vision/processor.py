import cv2
import numpy as np
from typing import Dict, Optional, Tuple
from .constants import MARKER_DISTANCE_MM
from .detector import Detector
from .measurer import Measurer
from .grader import Grader
from .utils import get_perspective_matrix, apply_perspective

class AsparagusProcessor:
    def __init__(self):
        self.detector = Detector()
        self.grader = Grader()

    def process(self, image: np.ndarray, debug: bool = False) -> Dict:
        result = {'success': False, 'error': None, 'debug_image': None}
        
        try:
            # 1. Detect Markers
            markers = self.detector.detect_markers(image)
            if len(markers) < 2:
                result['error'] = "Missing ARUCO markers"
                return result

            # 2. Perspective Transform
            markers.sort(key=lambda m: m['center'][0])
            left_m, right_m = markers[0], markers[1]
            
            pixel_dist = np.linalg.norm(right_m['center'] - left_m['center'])
            ratio = MARKER_DISTANCE_MM / pixel_dist
            
            src_points = np.array([
                left_m['corners'][0], right_m['corners'][1],
                right_m['corners'][2], left_m['corners'][3]
            ], dtype=np.float32)
            
            matrix, size = get_perspective_matrix(src_points, MARKER_DISTANCE_MM)
            warped = apply_perspective(image, matrix, size)
            
            # 3. Extract Contour & Measure
            contour = self.detector.extract_contour(warped)
            if contour is None:
                result['error'] = "Asparagus not found"
                return result
            
            measurer = Measurer(ratio)
            purple_pos = measurer.detect_purple_root(warped, contour)
            if not purple_pos:
                result['error'] = "Purple root not detected"
                return result
            
            # Mask for diameter measurement
            mask = np.zeros(warped.shape[:2], dtype=np.uint8)
            cv2.drawContours(mask, [contour], -1, 255, -1)
            
            diameters = measurer.measure_diameters(mask, purple_pos)
            if not diameters:
                result['error'] = "Diameter measurement failed"
                return result
            
            avg_dia = np.mean(diameters)
            length = measurer.measure_effective_length(contour, purple_pos)
            grade = self.grader.get_grade(avg_dia)
            
            result.update({
                'success': True,
                'grade': grade,
                'diameter': round(avg_dia, 2),
                'diameters': [round(d, 2) for d in diameters],
                'length': round(length, 2),
                'purple_root_position': purple_pos
            })

            if debug:
                result['debug_image'] = self._create_debug_image(warped, contour, purple_pos, ratio)
            
        except Exception as e:
            result['error'] = str(e)
            
        return result

    def _create_debug_image(self, warped: np.ndarray, contour: np.ndarray, 
                           purple_pos: Tuple[int, int], ratio: float) -> np.ndarray:
        """创建包含测量信息的可视化图像"""
        from .constants import DIAMETER_MEASUREMENT_POSITIONS
        debug_img = warped.copy()
        
        # 绘制轮廓
        cv2.drawContours(debug_img, [contour], -1, (0, 255, 0), 2)
        
        # 绘制紫根位置
        cv2.circle(debug_img, purple_pos, 5, (0, 0, 255), -1)
        cv2.putText(debug_img, "Purple Root", (purple_pos[0] + 10, purple_pos[1]),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 0, 255), 1)
        
        # 绘制直径测量线
        for pos_mm in DIAMETER_MEASUREMENT_POSITIONS:
            offset = int(pos_mm / ratio)
            measure_y = purple_pos[1] - offset
            if 0 <= measure_y < debug_img.shape[0]:
                cv2.line(debug_img, (0, measure_y), (debug_img.shape[1], measure_y), (255, 255, 0), 1)
                
        return debug_img
