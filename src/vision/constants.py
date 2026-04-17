import cv2

# ARUCO标记参数
ARUCO_DICT = cv2.aruco.DICT_4X4_50
MARKER_IDS = [10, 13, 40, 42]
MARKER_DISTANCE_MM = 50.0  # 标记中心参考距离50mm

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
