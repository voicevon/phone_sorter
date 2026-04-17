import sys
import os
sys.path.append(os.path.join(os.path.dirname(__file__), 'cv'))

from asparagus_grading import AsparagusGradingAlgorithm
import cv2

def test_integration():
    print("开始集成测试...")
    
    algorithm = AsparagusGradingAlgorithm()
    
    test_image_path = 'temp/test_asparagus.jpg'
    
    if not os.path.exists(test_image_path):
        print("警告：测试图像不存在，创建模拟图像...")
        test_image = cv2.imread('temp/aruco_markers.pdf')
        if test_image is None:
            print("创建模拟测试图像...")
            test_image = cv2.imread('temp/aruco_markers.pdf')
        
        if test_image is None:
            print("无法创建测试图像，跳过测试")
            return False
    
    try:
        result = algorithm.process_image(test_image)
        
        print("\n=== 测试结果 ===")
        print(f"成功: {result['success']}")
        print(f"等级: {result['grade']}")
        print(f"直径: {result['diameter']} mm")
        print(f"长度: {result['length']} mm")
        print(f"紫根位置: {result['purple_root_position']}")
        
        if result['success']:
            print("\n✓ 集成测试通过")
            return True
        else:
            print("\n✗ 集成测试失败")
            return False
            
    except Exception as e:
        print(f"\n✗ 集成测试出错: {e}")
        return False

if __name__ == "__main__":
    success = test_integration()
    sys.exit(0 if success else 1)