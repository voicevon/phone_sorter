import cv2
from reportlab.lib.pagesizes import A4
from reportlab.lib.units import mm
from reportlab.pdfgen import canvas
import numpy as np
import os

def generate_aruco_marker(marker_id, pixel_size=500):
    aruco_dict = cv2.aruco.getPredefinedDictionary(cv2.aruco.DICT_4X4_50)
    marker = cv2.aruco.generateImageMarker(aruco_dict, marker_id, pixel_size)
    return marker

def save_marker_as_png(marker, filename):
    cv2.imwrite(filename, marker)

def create_pdf_with_markers(output_pdf, marker_size_mm=25.0, center_dist_w=170.0, center_dist_h=250.0):
    if not os.path.exists("temp"):
        os.makedirs("temp")
        
    c = canvas.Canvas(output_pdf, pagesize=A4)
    page_width, page_height = A4 # in points
    
    # IDs: 10 (TL), 13 (TR), 40 (BL), 42 (BR)
    marker_ids = [10, 13, 40, 42]
    markers_temp_files = []
    
    for marker_id in marker_ids:
        marker = generate_aruco_marker(marker_id)
        temp_filename = f"temp/marker_{marker_id}.png"
        save_marker_as_png(marker, temp_filename)
        markers_temp_files.append(temp_filename)
    
    # Coordinates in mm converted to points
    s = marker_size_mm * mm
    pw_mm, ph_mm = 210.0, 297.0
    
    # Calculate center positions in mm
    # Horizontal centering: (210 - 170) / 2 = 20mm
    # Vertical centering: (297 - 250) / 2 = 23.5mm
    offset_x = (pw_mm - center_dist_w) / 2.0
    offset_y = (ph_mm - center_dist_h) / 2.0
    
    # Marker positions (bottom-left corner of the image)
    # BL: (offset_x - s/2, offset_y - s/2)
    # BR: (offset_x + center_dist_w - s/2, offset_y - s/2)
    # TL: (offset_x - s/2, offset_y + center_dist_h - s/2)
    # TR: (offset_x + center_dist_w - s/2, offset_y + center_dist_h - s/2)
    
    pos_bl = ((offset_x - marker_size_mm/2.0) * mm, (offset_y - marker_size_mm/2.0) * mm)
    pos_br = ((offset_x + center_dist_w - marker_size_mm/2.0) * mm, (offset_y - marker_size_mm/2.0) * mm)
    pos_tl = ((offset_x - marker_size_mm/2.0) * mm, (offset_y + center_dist_h - marker_size_mm/2.0) * mm)
    pos_tr = ((offset_x + center_dist_w - marker_size_mm/2.0) * mm, (offset_y + center_dist_h - marker_size_mm/2.0) * mm)
    
    # Draw markers
    # ID顺序: 10 (TL), 13 (TR), 40 (BL), 42 (BR)
    c.drawImage(markers_temp_files[2], pos_bl[0], pos_bl[1], width=s, height=s)
    c.drawImage(markers_temp_files[3], pos_br[0], pos_br[1], width=s, height=s)
    c.drawImage(markers_temp_files[0], pos_tl[0], pos_tl[1], width=s, height=s)
    c.drawImage(markers_temp_files[1], pos_tr[0], pos_tr[1], width=s, height=s)
    
    c.showPage()
    c.save()
    
    # Cleanup
    for f in markers_temp_files:
        if os.path.exists(f):
            os.remove(f)
    print(f"PDF generated successfully: {output_pdf}")

if __name__ == "__main__":
    output_path = "docs/resources/aruco_final_10_13_40_42.pdf"
    create_pdf_with_markers(output_path, marker_size_mm=25.0, center_dist_w=170.0, center_dist_h=250.0)
