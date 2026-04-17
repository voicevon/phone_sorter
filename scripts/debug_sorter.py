import cv2
import sys
import os
import argparse
from typing import Dict

# 确保 src 目录在路径中
sys.path.append(os.path.abspath('.'))

from src.vision import AsparagusProcessor

def debug_image(image_path: str, output_path: str = "debug_result.jpg"):
    if not os.path.exists(image_path):
        print(f"错误: 找不到文件 {image_path}")
        return

    print(f"正在分析图片: {image_path}...")
    image = cv2.imread(image_path)
    if image is None:
        print("错误: 无法读取图片")
        return

    processor = AsparagusProcessor()
    # 启用调试模式以获取调试图像
    result = processor.process(image, debug=True)

    if result['success']:
        print("\n--- 分析结果 ---")
        print(f"等级: {result['grade']}")
        print(f"平均直径: {result['diameter']} mm")
        print(f"测量点直径: {result['diameters']} mm")
        print(f"有效长度: {result['length']} mm")
        print(f"紫根位置: {result['purple_root_position']}")
        
        if result['debug_image'] is not None:
            cv2.imwrite(output_path, result['debug_image'])
            print(f"\n[✓] 调试图像已保存至: {output_path}")
    else:
        print(f"\n[!] 分析失败: {result['error']}")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="芦笋分级系统硬件调试工具")
    parser.get_output_path = "debug_result.jpg"
    parser.add_argument("input", help="输入图片的路径")
    parser.add_argument("--output", default="debug_result.jpg", help="输出调试图片的路径")
    
    args = parser.parse_args()
    debug_image(args.input, args.output)
