"""Generates the LumiDeck wallpaper: flat blobs and thin lines, Pixel style, in the logo's blues.

Writes an Android vector drawable and a PNG preview.
usage: python scripts/wallpaper.py android/app/src/main/res/drawable/wallpaper.xml img/wallpaper.png
"""
import sys
from PIL import Image, ImageDraw

W, H = 1920, 1080

BASE = "#CFE0EC"
# (colour, closed outline points); drawn in order, back to front.
BLOBS = [
    ("#ABC8DD", [(250, -120), (1450, -120), (1600, 230), (1380, 560), (1520, 880), (1260, 1200), (160, 1200), (230, 720), (110, 360)]),
    ("#DCE8F1", [(-120, -120), (330, -120), (250, 150), (210, 390), (130, 560), (-120, 640)]),
    ("#E9F1F7", [(500, -120), (1400, -120), (1390, 110), (1260, 290), (1060, 320), (860, 250), (650, 210), (520, 90)]),
    ("#6A99BA", [(1520, -120), (2040, -120), (2040, 1000), (1860, 990), (1660, 760), (1430, 660), (1390, 520), (1470, 330), (1570, 140)]),
    ("#82ADC8", [(520, 660), (640, 590), (790, 650), (870, 830), (1010, 910), (1070, 1200), (440, 1200), (470, 970), (500, 810)]),
    ("#E9F1F7", [(1230, 1200), (1360, 1010), (1510, 890), (1650, 780), (1820, 745), (2040, 790), (2040, 1200)]),
    ("#C3D8EA", [(-120, 600), (60, 620), (130, 800), (260, 1000), (380, 1200), (-120, 1200)]),
]
LINE = "#2F4E63"
LINE_WIDTH = 2.5
# Open curves; the first and last points are only there to shape the ends.
LINES = [
    [(-60, 560), (-20, 520), (300, 500), (560, 380), (700, 130), (760, -40), (780, -80)],
    [(1560, -80), (1500, -30), (1330, 230), (950, 390), (520, 500), (410, 780), (520, 1120), (560, 1200)],
    [(820, 1220), (860, 1180), (980, 1010), (1260, 1040), (1600, 790), (1820, 540), (1980, 470), (2040, 460)],
]


def beziers(points, closed):
    """Catmull-Rom through the points, as cubic Bézier segments (p0, c1, c2, p1)."""
    n = len(points)
    rng = range(n) if closed else range(1, n - 2)
    for i in rng:
        p_prev, p0, p1, p_next = (points[(i + k) % n] for k in (-1, 0, 1, 2))
        c1 = (p0[0] + (p1[0] - p_prev[0]) / 6, p0[1] + (p1[1] - p_prev[1]) / 6)
        c2 = (p1[0] - (p_next[0] - p0[0]) / 6, p1[1] - (p_next[1] - p0[1]) / 6)
        yield p0, c1, c2, p1


def fmt(p):
    return f"{p[0]:.0f},{p[1]:.0f}"


def path_data(points, closed):
    segs = list(beziers(points, closed))
    d = f"M{fmt(segs[0][0])}" + "".join(f"C{fmt(c1)} {fmt(c2)} {fmt(p1)}" for _, c1, c2, p1 in segs)
    return d + ("Z" if closed else "")


def sample(points, closed, steps=24):
    out = []
    for p0, c1, c2, p1 in beziers(points, closed):
        for s in range(steps):
            t = s / steps
            mt = 1 - t
            out.append(tuple(mt**3 * a + 3 * mt * mt * t * b + 3 * mt * t * t * c + t**3 * d for a, b, c, d in zip(p0, c1, c2, p1)))
    return out


def write_vector(path):
    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        "<!-- Generated: the LumiDeck wallpaper (preview in img/wallpaper.png). -->",
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        f'    android:width="{W}dp"',
        f'    android:height="{H}dp"',
        f'    android:viewportWidth="{W}"',
        f'    android:viewportHeight="{H}">',
        f'    <path android:fillColor="{BASE}" android:pathData="M0,0h{W}v{H}h-{W}z" />',
    ]
    for colour, pts in BLOBS:
        lines.append(f'    <path android:fillColor="{colour}" android:pathData="{path_data(pts, True)}" />')
    for pts in LINES:
        lines.append(
            f'    <path android:strokeColor="{LINE}" android:strokeWidth="{LINE_WIDTH}" android:strokeLineCap="round" '
            f'android:pathData="{path_data(pts, False)}" />'
        )
    lines.append("</vector>")
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")


def write_preview(path, scale=2):
    img = Image.new("RGB", (W * scale, H * scale), BASE)
    draw = ImageDraw.Draw(img)
    for colour, pts in BLOBS:
        draw.polygon([(x * scale, y * scale) for x, y in sample(pts, True)], fill=colour)
    for pts in LINES:
        draw.line([(x * scale, y * scale) for x, y in sample(pts, False)], fill=LINE, width=round(LINE_WIDTH * scale), joint="curve")
    img.resize((W, H), Image.LANCZOS).save(path, optimize=True)


if __name__ == "__main__":
    write_vector(sys.argv[1])
    write_preview(sys.argv[2])
