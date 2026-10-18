"""ÇDTP ArUco marker'larını yazdırılabilir A4 PDF olarak üretir (DICT_4X4_50).

Kullanım:
    python tools/generate_markers.py                  # markers/cdtp_aruco_markers.pdf
    python tools/generate_markers.py --floor-mm 120   # zemin marker'larını 12 cm yap

Yazdırırken ölçek %100 ("Gerçek boyut") olmalı. Basılınca kenar uzunluğunu cetvelle kontrol edin.
Gereken: pip install opencv-contrib-python reportlab
"""
import argparse
import os
import sys
import tempfile

import cv2
import numpy as np
from reportlab.lib.pagesizes import A4
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.pdfgen import canvas

ROLES = {
    0: ("ROBOT", "Robotun üstüne, yatay ve kameraya bakacak şekilde. OK yönü = robotun ÖNÜ."),
    1: ("İLAÇ KUTUSU", "Kutunun üst yüzüne."),
    10: ("ZEMİN 10 (0,0)", "Kameraya yakın SOL köşe."),
    11: ("ZEMİN 11 (W,0)", "Kameraya yakın SAĞ köşe."),
    12: ("ZEMİN 12 (W,H)", "Kameradan uzak SAĞ köşe."),
    13: ("ZEMİN 13 (0,H)", "Kameradan uzak SOL köşe."),
    20: ("ŞARJ İSTASYONU", "İstasyonun üstüne, yatay."),
}


def register_font():
    for path in (r"C:\Windows\Fonts\arial.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"):
        if os.path.exists(path):
            pdfmetrics.registerFont(TTFont("TR", path))
            return "TR"
    return "Helvetica"


def marker_png(dictionary, marker_id, px, out_dir):
    img = cv2.aruco.generateImageMarker(dictionary, marker_id, px)
    path = os.path.join(out_dir, f"m{marker_id}.png")
    cv2.imwrite(path, img)
    return path, img


def self_check(dictionary, images):
    """Üretilen her marker'ı OpenCV ile geri okuyup ID'yi doğrular."""
    det = cv2.aruco.ArucoDetector(dictionary, cv2.aruco.DetectorParameters())
    for mid, img in images.items():
        canvas_img = np.full((img.shape[0] + 200, img.shape[1] + 200), 255, np.uint8)
        canvas_img[100:-100, 100:-100] = img
        _, ids, _ = det.detectMarkers(canvas_img)
        found = [] if ids is None else ids.flatten().tolist()
        if found != [mid]:
            raise SystemExit(f"Marker {mid} geri okunamadı: {found}")
    print(f"Kendi kendine test: {len(images)} marker doğru okundu.")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=os.path.join(os.path.dirname(__file__), "..", "markers", "cdtp_aruco_markers.pdf"))
    ap.add_argument("--robot-mm", type=float, default=80)
    ap.add_argument("--box-mm", type=float, default=60)
    ap.add_argument("--floor-mm", type=float, default=100)
    ap.add_argument("--station-mm", type=float, default=100)
    a = ap.parse_args()

    sizes = {0: a.robot_mm, 1: a.box_mm, 10: a.floor_mm, 11: a.floor_mm, 12: a.floor_mm, 13: a.floor_mm, 20: a.station_mm}
    dictionary = cv2.aruco.getPredefinedDictionary(cv2.aruco.DICT_4X4_50)
    font = register_font()
    os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)

    pw, ph = A4
    c = canvas.Canvas(a.out, pagesize=A4)
    c.setTitle("ÇDTP ArUco marker'ları (DICT_4X4_50)")
    images = {}
    with tempfile.TemporaryDirectory() as tmp:
        for mid, (title, note) in ROLES.items():
            side = sizes[mid] * mm
            path, img = marker_png(dictionary, mid, 600, tmp)
            images[mid] = img
            x = (pw - side) / 2
            y = (ph - side) / 2
            c.setFont(font, 22)
            c.drawCentredString(pw / 2, ph - 40 * mm, f"ID {mid} · {title}")
            c.setFont(font, 11)
            c.drawCentredString(pw / 2, ph - 48 * mm, note)
            c.drawCentredString(pw / 2, ph - 55 * mm, f"Siyah kare kenarı: {sizes[mid]:.0f} mm  ·  DICT_4X4_50  ·  ölçek %100 yazdırın")
            # Beyaz sessiz bölge (en az 1 hücre) zaten sayfa beyazı; kesim çizgisi:
            q = side * 0.25
            c.setDash(3, 3)
            c.setLineWidth(0.4)
            c.rect(x - q, y - q, side + 2 * q, side + 2 * q)
            c.setDash()
            c.drawImage(path, x, y, side, side)
            if mid == 0:
                # robotun ön yönü = marker'ın üst kenarı
                c.setLineWidth(3)
                ax = pw / 2
                ay = y + side + q + 3 * mm
                c.line(ax, ay, ax, ay + 14 * mm)
                c.line(ax, ay + 14 * mm, ax - 4 * mm, ay + 9 * mm)
                c.line(ax, ay + 14 * mm, ax + 4 * mm, ay + 9 * mm)
                c.setFont(font, 14)
                c.drawString(ax + 7 * mm, ay + 5 * mm, "ROBOTUN ÖNÜ")
            c.setFont(font, 9)
            c.drawCentredString(pw / 2, 20 * mm, "ÇDTP · İki Tekerlekli Ev İçi Hasta Destek Robotu · Modül D")
            c.showPage()
    c.save()
    self_check(dictionary, images)
    print("Yazıldı:", os.path.abspath(a.out))


if __name__ == "__main__":
    sys.exit(main())
