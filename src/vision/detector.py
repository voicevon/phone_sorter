import cv2
import numpy as np
from typing import Tuple, List, Optional, Dict
from .constants import ARUCO_DICT, MARKER_IDS

class Detector:
    def __init__(self):
        self.aruco_dict = cv2.aruco.getPredefinedDictionary(ARUCO_DICT)
        self.aruco_params = cv2.aruco.DetectorParameters()
        self.detector = cv2.aruco.ArucoDetector(self.aruco_dict, self.aruco_params)

    def detect_markers(self, image: np.ndarray) -> List[Dict]:
        """检测指定ID的ARUCO标记"""
        corners, ids, _ = self.detector.detectMarkers(image)
        
        if ids is None:
            return []

        markers = []
        for i, marker_id in enumerate(ids):
            if marker_id[0] in MARKER_IDS:
                center = np.mean(corners[i][0], axis=0)
                markers.append({
                    'id': int(marker_id[0]),
                    'center': center,
                    'corners': corners[i][0]
                })
        
        return markers

    def extract_contour(self, warped_image: np.ndarray) -> Optional[np.ndarray]:
        """从透视变换后的图像中提取芦笋轮廓"""
        if warped_image is None:
            return None

        gray = cv2.cvtColor(warped_image, cv2.COLOR_BGR2GRAY)
        blurred = cv2.GaussianBlur(gray, (5, 5), 0)
        binary = cv2.adaptiveThreshold(
            blurred, 255,
            cv2.ADAPTIVE_THRESH_GAUSSIAN_C,
            cv2.THRESH_BINARY_INV, 11, 2
        )

        kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (3, 3))
        binary = cv2.morphologyEx(binary, cv2.MORPH_CLOSE, kernel, iterations=2)
        binary = cv2.morphologyEx(binary, cv2.MORPH_OPEN, kernel, iterations=1)

        contours, _ = cv2.findContours(binary, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        if not contours:
            return None

        largest_contour = max(contours, key=cv2.contourArea)
        if cv2.contourArea(largest_contour) < 100:
            return None

        return largest_contour
