import cv2
from reportlab.lib.pagesizes import A4
from reportlab.lib.units import inch
from reportlab.pdfgen import canvas
import numpy as np

def generate_aruco_marker(marker_id, marker_size=4):
    aruco_dict = cv2.aruco.getPredefinedDictionary(cv2.aruco.DICT_4X4_50)
    marker = cv2.aruco.generateImageMarker(aruco_dict, marker_id, 200)
    return marker

def save_marker_as_png(marker, filename):
    cv2.imwrite(filename, marker)

def create_pdf_with_markers(output_pdf, marker_size=3*inch):
    c = canvas.Canvas(output_pdf, pagesize=A4)
    width, height = A4
    
    marker_ids = [10, 42]
    markers = []
    
    for marker_id in marker_ids:
        marker = generate_aruco_marker(marker_id)
        temp_filename = f"temp/marker_{marker_id}.png"
        save_marker_as_png(marker, temp_filename)
        markers.append(temp_filename)
    
    center_x = width / 2
    center_y = height / 2
    
    c.drawImage(markers[0], center_x - marker_size - 0.5*inch, center_y - marker_size/2, 
                width=marker_size, height=marker_size)
    
    c.drawImage(markers[1], center_x + 0.5*inch, center_y - marker_size/2, 
                width=marker_size, height=marker_size)
    
    c.save()
    
    for marker_file in markers:
        import os
        if os.path.exists(marker_file):
            os.remove(marker_file)

if __name__ == "__main__":
    create_pdf_with_markers("temp/aruco_markers.pdf")
    print("PDF generated successfully: temp/aruco_markers.pdf")
