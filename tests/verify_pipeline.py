import cv2
import numpy as np
import os
import sys

# Ensure src is in the path
sys.path.append(os.path.abspath('.'))

from src.vision import AsparagusProcessor

def test_modular_pipeline():
    print("Testing Modular Vision Pipeline...")
    
    # Create a dummy image for testing (black image with 2 ARUCO markers simulated if possible)
    # Since we don't have a real image, we'll just check if the processor can be initialized
    # and if it returns an error for a blank image (which is expected).
    
    try:
        processor = AsparagusProcessor()
        print("[✓] Processer initialized successfully")
        
        dummy_img = np.zeros((480, 640, 3), dtype=np.uint8)
        result = processor.process(dummy_img)
        
        # Expected to fail due to missing markers
        if not result['success'] and "Missing ARUCO markers" in result['error']:
            print("[✓] Pipeline handles missing markers correctly")
        else:
            print("[!] Unexpected result:", result)
            return False
            
        return True
    except Exception as e:
        print(f"[!] Test failed with error: {e}")
        return False

if __name__ == "__main__":
    if test_modular_pipeline():
        print("\nVerification successful!")
        sys.exit(0)
    else:
        print("\nVerification failed!")
        sys.exit(1)
