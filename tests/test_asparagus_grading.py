"""
芦笋分级算法测试脚本
用于测试和验证计算机视觉算法的各个功能
"""

import cv2
import numpy as np
import sys
import os

# 添加cv目录到路径
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from asparagus_grading import AsparagusGradingAlgorithm


def create_test_image_with_markers():
    """
    创建一个包含ARUCO标记的测试图像
    """
    # 创建空白图像
    width, height = 1280, 720
    image = np.ones((height, width, 3), dtype=np.uint8) * 255

    # 创建ARUCO标记
    aruco_dict = cv2.aruco.getPredefinedDictionary(cv2.aruco.DICT_4X4_50)
    marker_size = 100

    # 生成标记ID 10
    marker_img_10 = cv2.aruco.generateImageMarker(aruco_dict, 10, marker_size)
    marker_img_10 = cv2.cvtColor(marker_img_10, cv2.COLOR_GRAY2BGR)

    # 生成标记ID 42
    marker_img_42 = cv2.aruco.generateImageMarker(aruco_dict, 42, marker_size)
    marker_img_42 = cv2.cvtColor(marker_img_42, cv2.COLOR_GRAY2BGR)

    # 将标记放置在图像中（模拟500mm距离）
    # 假设100像素对应100mm，则500mm对应500像素
    marker1_x = 200
    marker2_x = 700
    marker_y = 300

    # 放置标记1
    image[marker_y:marker_y+marker_size, marker1_x:marker1_x+marker_size] = marker_img_10

    # 放置标记2
    image[marker_y:marker_y+marker_size, marker2_x:marker2_x+marker_size] = marker_img_42

    # 绘制模拟的芦笋（绿色到白色渐变）
    asparagus_start_x = 300
    asparagus_end_x = 600
    asparagus_y = 100
    asparagus_width = 40
    asparagus_length = 400

    # 绘制芦笋主体（绿色渐变到白色）
    for i in range(asparagus_length):
        y = asparagus_y + i
        # 绿色到白色的渐变
        green_ratio = max(0, 1 - i / asparagus_length)
        color = (
            int(255 * (1 - green_ratio)),  # B
            int(255 * (1 - green_ratio * 0.5)),  # G
            int(255 * (1 - green_ratio))  # R
        )

        # 在中间位置绘制芦笋
        center_x = (asparagus_start_x + asparagus_end_x) // 2
        x_start = center_x - asparagus_width // 2
        x_end = center_x + asparagus_width // 2
        image[y:y+1, x_start:x_end] = color

    # 绘制紫根（绿色到白色交界处）- 在芦笋中间位置
    purple_root_y = asparagus_y + asparagus_length // 2  # y=300
    purple_color = (150, 50, 150)  # 紫色 (BGR)
    center_x = (asparagus_start_x + asparagus_end_x) // 2
    x_start = center_x - asparagus_width // 2
    x_end = center_x + asparagus_width // 2
    # 绘制更明显的紫根区域
    for py in range(purple_root_y - 5, purple_root_y + 5):
        if 0 <= py < image.shape[0]:
            image[py:py+1, x_start:x_end] = purple_color

    return image


def test_aruco_detection():
    """测试ARUCO标记检测"""
    print("=" * 60)
    print("测试1: ARUCO标记检测")
    print("=" * 60)

    # 创建测试图像
    image = create_test_image_with_markers()

    # 保存测试图像
    cv2.imwrite(r"d:\Software\phone_sorter\temp\test_image.jpg", image)
    print("测试图像已保存到: temp/test_image.jpg")

    # 初始化算法
    algorithm = AsparagusGradingAlgorithm()

    # 检测ARUCO标记
    success, markers = algorithm.detect_aruco_markers(image)

    if success:
        print(f"✓ 成功检测到 {len(markers)} 个ARUCO标记")
        for marker in markers:
            print(f"  - 标记ID: {marker['id']}, 中心位置: {marker['center']}")
    else:
        print("✗ 未能检测到ARUCO标记")

    print()
    return success


def test_perspective_transform():
    """测试透视变换"""
    print("=" * 60)
    print("测试2: 透视变换")
    print("=" * 60)

    # 创建测试图像
    image = create_test_image_with_markers()

    # 初始化算法
    algorithm = AsparagusGradingAlgorithm()

    # 检测ARUCO标记
    success, markers = algorithm.detect_aruco_markers(image)
    if not success:
        print("✗ 未能检测到ARUCO标记，跳过透视变换测试")
        return False

    # 计算透视变换矩阵
    success, matrix = algorithm.calculate_perspective_transform(image)
    if success:
        print("✓ 成功计算透视变换矩阵")
        print(f"  - 像素到毫米比例: {algorithm.pixel_to_mm_ratio:.4f} mm/pixel")
        print(f"  - 目标尺寸: {algorithm.target_size}")

        # 应用透视变换
        warped = algorithm.apply_perspective_transform(image)
        if warped is not None:
            cv2.imwrite(r"d:\Software\phone_sorter\temp\warped_image.jpg", warped)
            print("✓ 成功应用透视变换")
            print("  变换后的图像已保存到: temp/warped_image.jpg")
        else:
            print("✗ 未能应用透视变换")
            return False
    else:
        print("✗ 未能计算透视变换矩阵")
        return False

    print()
    return True


def test_contour_extraction():
    """测试轮廓提取"""
    print("=" * 60)
    print("测试3: 芦笋轮廓提取")
    print("=" * 60)

    # 创建测试图像
    image = create_test_image_with_markers()

    # 初始化算法
    algorithm = AsparagusGradingAlgorithm()

    # 检测ARUCO标记
    success, markers = algorithm.detect_aruco_markers(image)
    if not success:
        print("✗ 未能检测到ARUCO标记，跳过轮廓提取测试")
        return False

    # 计算透视变换
    success, matrix = algorithm.calculate_perspective_transform(image)
    if not success:
        print("✗ 未能计算透视变换矩阵，跳过轮廓提取测试")
        return False

    # 应用透视变换
    warped = algorithm.apply_perspective_transform(image)
    if warped is None:
        print("✗ 未能应用透视变换，跳过轮廓提取测试")
        return False

    # 提取轮廓
    success, contour = algorithm.extract_asparagus_contour(warped)
    if success:
        print("✓ 成功提取芦笋轮廓")
        print(f"  - 轮廓面积: {cv2.contourArea(contour):.2f} 像素")

        # 绘制轮廓
        vis = warped.copy()
        cv2.drawContours(vis, [contour], -1, (0, 255, 0), 2)
        cv2.imwrite(r"d:\Software\phone_sorter\temp\contour_image.jpg", vis)
        print("  轮廓图像已保存到: temp/contour_image.jpg")
    else:
        print("✗ 未能提取芦笋轮廓")

    print()
    return success


def test_purple_root_detection():
    """测试紫根检测"""
    print("=" * 60)
    print("测试4: 紫根位置检测")
    print("=" * 60)

    # 创建测试图像
    image = create_test_image_with_markers()

    # 初始化算法
    algorithm = AsparagusGradingAlgorithm()

    # 检测ARUCO标记
    success, markers = algorithm.detect_aruco_markers(image)
    if not success:
        print("✗ 未能检测到ARUCO标记，跳过紫根检测测试")
        return False

    # 计算透视变换
    success, matrix = algorithm.calculate_perspective_transform(image)
    if not success:
        print("✗ 未能计算透视变换矩阵，跳过紫根检测测试")
        return False

    # 应用透视变换
    warped = algorithm.apply_perspective_transform(image)
    if warped is None:
        print("✗ 未能应用透视变换，跳过紫根检测测试")
        return False

    # 提取轮廓
    success, contour = algorithm.extract_asparagus_contour(warped)
    if not success:
        print("✗ 未能提取芦笋轮廓，跳过紫根检测测试")
        return False

    # 检测紫根
    success, purple_pos = algorithm.detect_purple_root(warped, contour)
    if success:
        print("✓ 成功检测紫根位置")
        print(f"  - 紫根位置: {purple_pos}")

        # 绘制紫根位置
        vis = warped.copy()
        cv2.circle(vis, purple_pos, 5, (255, 0, 0), -1)
        cv2.imwrite(r"d:\Software\phone_sorter\temp\purple_root_image.jpg", vis)
        print("  紫根位置图像已保存到: temp/purple_root_image.jpg")
    else:
        print("✗ 未能检测紫根位置")

    print()
    return success


def test_diameter_measurement():
    """测试直径测量"""
    print("=" * 60)
    print("测试5: 直径测量")
    print("=" * 60)

    # 创建测试图像
    image = create_test_image_with_markers()

    # 初始化算法
    algorithm = AsparagusGradingAlgorithm()

    # 检测ARUCO标记
    success, markers = algorithm.detect_aruco_markers(image)
    if not success:
        print("✗ 未能检测到ARUCO标记，跳过直径测量测试")
        return False

    # 计算透视变换
    success, matrix = algorithm.calculate_perspective_transform(image)
    if not success:
        print("✗ 未能计算透视变换矩阵，跳过直径测量测试")
        return False

    # 应用透视变换
    warped = algorithm.apply_perspective_transform(image)
    if warped is None:
        print("✗ 未能应用透视变换，跳过直径测量测试")
        return False

    # 提取轮廓
    success, contour = algorithm.extract_asparagus_contour(warped)
    if not success:
        print("✗ 未能提取芦笋轮廓，跳过直径测量测试")
        return False

    # 检测紫根
    success, purple_pos = algorithm.detect_purple_root(warped, contour)
    if not success:
        print("✗ 未能检测紫根位置，跳过直径测量测试")
        return False

    # 测量直径
    success, diameters, avg_diameter = algorithm.measure_diameters(warped, contour, purple_pos)
    if success:
        print("✓ 成功测量直径")
        print(f"  - 测量位置: {algorithm.DIAMETER_MEASUREMENT_POSITIONS} mm")
        print(f"  - 直径值: {[f'{d:.2f}' for d in diameters]} mm")
        print(f"  - 平均直径: {avg_diameter:.2f} mm")
        print(f"  - 精度要求: ±{algorithm.DIAMETER_PRECISION} mm")
    else:
        print("✗ 未能测量直径")

    print()
    return success


def test_length_calculation():
    """测试长度计算"""
    print("=" * 60)
    print("测试6: 有效长度计算")
    print("=" * 60)

    # 创建测试图像
    image = create_test_image_with_markers()

    # 初始化算法
    algorithm = AsparagusGradingAlgorithm()

    # 检测ARUCO标记
    success, markers = algorithm.detect_aruco_markers(image)
    if not success:
        print("✗ 未能检测到ARUCO标记，跳过长度计算测试")
        return False

    # 计算透视变换
    success, matrix = algorithm.calculate_perspective_transform(image)
    if not success:
        print("✗ 未能计算透视变换矩阵，跳过长度计算测试")
        return False

    # 应用透视变换
    warped = algorithm.apply_perspective_transform(image)
    if warped is None:
        print("✗ 未能应用透视变换，跳过长度计算测试")
        return False

    # 提取轮廓
    success, contour = algorithm.extract_asparagus_contour(warped)
    if not success:
        print("✗ 未能提取芦笋轮廓，跳过长度计算测试")
        return False

    # 检测紫根
    success, purple_pos = algorithm.detect_purple_root(warped, contour)
    if not success:
        print("✗ 未能检测紫根位置，跳过长度计算测试")
        return False

    # 计算有效长度
    success, length = algorithm.calculate_effective_length(contour, purple_pos)
    if success:
        print("✓ 成功计算有效长度")
        print(f"  - 有效长度: {length:.2f} mm")
        print(f"  - 精度要求: ±{algorithm.LENGTH_PRECISION} mm")
    else:
        print("✗ 未能计算有效长度")

    print()
    return success


def test_grading():
    """测试分级算法"""
    print("=" * 60)
    print("测试7: 分级算法")
    print("=" * 60)

    # 初始化算法
    algorithm = AsparagusGradingAlgorithm()

    # 测试不同直径的分级
    test_diameters = [1.6, 1.3, 1.1, 0.9, 0.7, 0.4]

    print("测试不同直径的分级结果:")
    for diameter in test_diameters:
        grade = algorithm.grade_asparagus(diameter)
        print(f"  - 直径 {diameter:.1f} mm -> 等级: {grade}")

    print("\n分级阈值:")
    for grade, threshold in algorithm.GRADE_THRESHOLDS.items():
        print(f"  - {grade}级: > {threshold} mm")

    print()
    return True


def test_full_pipeline():
    """测试完整流程"""
    print("=" * 60)
    print("测试8: 完整处理流程")
    print("=" * 60)

    # 创建测试图像
    image = create_test_image_with_markers()

    # 初始化算法
    algorithm = AsparagusGradingAlgorithm()

    # 执行完整流程
    result = algorithm.process_image(image)

    if result['success']:
        print("✓ 完整流程执行成功")
        print(f"  - 等级: {result['grade']}")
        print(f"  - 平均直径: {result['diameter']} mm")
        print(f"  - 各点直径: {result['diameters']} mm")
        print(f"  - 有效长度: {result['length']} mm")
        print(f"  - 紫根位置: {result['purple_root_position']}")

        # 获取可视化结果
        vis = algorithm.get_visualization(image)
        if vis is not None:
            cv2.imwrite(r"d:\Software\phone_sorter\temp\visualization.jpg", vis)
            print("\n可视化图像已保存到: temp/visualization.jpg")
    else:
        print("✗ 完整流程执行失败")
        print(f"  - 错误信息: {result['error']}")

    print()
    return result['success']


def main():
    """主测试函数"""
    print("\n")
    print("*" * 60)
    print("芦笋分级算法测试")
    print("*" * 60)
    print()

    # 确保temp目录存在
    os.makedirs(r"d:\Software\phone_sorter\temp", exist_ok=True)

    # 运行所有测试
    tests = [
        ("ARUCO标记检测", test_aruco_detection),
        ("透视变换", test_perspective_transform),
        ("芦笋轮廓提取", test_contour_extraction),
        ("紫根位置检测", test_purple_root_detection),
        ("直径测量", test_diameter_measurement),
        ("有效长度计算", test_length_calculation),
        ("分级算法", test_grading),
        ("完整处理流程", test_full_pipeline)
    ]

    results = []
    for test_name, test_func in tests:
        try:
            success = test_func()
            results.append((test_name, success))
        except Exception as e:
            print(f"✗ 测试 '{test_name}' 发生异常: {e}")
            results.append((test_name, False))
            print()

    # 打印测试总结
    print("=" * 60)
    print("测试总结")
    print("=" * 60)
    for test_name, success in results:
        status = "✓ 通过" if success else "✗ 失败"
        print(f"{status} - {test_name}")

    passed = sum(1 for _, success in results if success)
    total = len(results)
    print(f"\n总计: {passed}/{total} 测试通过")

    if passed == total:
        print("\n🎉 所有测试通过！")
    else:
        print(f"\n⚠️  {total - passed} 个测试失败")

    print()


if __name__ == "__main__":
    main()
