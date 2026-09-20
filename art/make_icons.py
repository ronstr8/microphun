#!/usr/bin/env python3
"""Rebuilds the launcher icon layers from the mascot art.

    python3 art/make_icons.py

Reads art/microphun-logo-source.png (the logo as drawn, on a flat white
background), lifts the character off that background, and writes the adaptive
icon's foreground and monochrome layers at every density. The background layer
is a flat colour, @color/ic_launcher_background in res/values/colors.xml.

Needs Pillow. Nothing in the Gradle build runs this — it is a one-off to be
re-run when the art changes.
"""

from collections import deque
from pathlib import Path
import sys

from PIL import Image, ImageFilter

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "art" / "microphun-logo-source.png"
CUTOUT = ROOT / "art" / "microphun-logo.png"
RES = ROOT / "app" / "src" / "main" / "res"

DENSITIES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}

# Of the 108dp canvas only the middle 72dp survives every launcher mask, and a
# circular mask clips the corners of even that. 70dp is as large as the mascot
# goes before a shoe crosses the arc; 74 clips it.
CONTENT_DP = 70.0


def is_paper(c):
    """True for the flat white the art was drawn on."""
    r, g, b = c
    return min(r, g, b) >= 238 and max(abs(r - g), abs(g - b), abs(r - b)) <= 8


def cut_out(image):
    """Removes the background by flooding in from the borders.

    A flood rather than a colour key because the mascot's own whites — eyes,
    teeth, highlights — are the same white as the background. They survive
    because its black outline walls them off from the border.
    """
    w, h = image.size
    px = image.load()
    bg = bytearray(w * h)
    queue = deque()

    def seed(x, y):
        if is_paper(px[x, y]) and not bg[y * w + x]:
            bg[y * w + x] = 1
            queue.append((x, y))

    for x in range(w):
        seed(x, 0)
        seed(x, h - 1)
    for y in range(h):
        seed(0, y)
        seed(w - 1, y)

    while queue:
        x, y = queue.popleft()
        for nx, ny in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)):
            if 0 <= nx < w and 0 <= ny < h and not bg[ny * w + nx] and is_paper(px[nx, ny]):
                bg[ny * w + nx] = 1
                queue.append((nx, ny))

    return bytearray(255 if not v else 0 for v in bg)


def largest_and_friends(mask, size, near_pad=60, min_area=80):
    """Keeps the character plus anything close to it, drops stray specks.

    Shading in the corners of some exports leaves islands the flood cannot
    reach; the sparkles beside the mascot's head must survive, so proximity
    rather than size decides.
    """
    w, h = size
    seen = bytearray(w * h)
    comps = []
    for sy in range(h):
        for sx in range(w):
            if mask[sy * w + sx] and not seen[sy * w + sx]:
                queue = deque([(sx, sy)])
                seen[sy * w + sx] = 1
                pts = []
                x0 = y0 = 1 << 30
                x1 = y1 = -1
                while queue:
                    x, y = queue.popleft()
                    pts.append((x, y))
                    x0, x1 = min(x0, x), max(x1, x)
                    y0, y1 = min(y0, y), max(y1, y)
                    for nx, ny in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)):
                        if 0 <= nx < w and 0 <= ny < h and mask[ny * w + nx] and not seen[ny * w + nx]:
                            seen[ny * w + nx] = 1
                            queue.append((nx, ny))
                comps.append((len(pts), (x0, y0, x1, y1), pts))

    comps.sort(key=lambda c: -c[0])
    main = comps[0][1]
    kept = bytearray(w * h)
    for area, box, pts in comps:
        near = (box[0] >= main[0] - near_pad and box[2] <= main[2] + near_pad
                and box[1] >= main[1] - near_pad and box[3] <= main[3] + near_pad)
        if box == main or (area >= min_area and near):
            for x, y in pts:
                kept[y * w + x] = 255
    return kept


def place(art, size):
    """Centres art on a size x size canvas at CONTENT_DP of 108dp."""
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    scale = (CONTENT_DP / 108.0) * size / max(art.width, art.height)
    w, h = round(art.width * scale), round(art.height * scale)
    canvas.alpha_composite(art.resize((w, h), Image.LANCZOS), ((size - w) // 2, (size - h) // 2))
    return canvas


def main():
    if not SOURCE.exists():
        sys.exit(f"missing {SOURCE}")

    image = Image.open(SOURCE).convert("RGB")
    mask = largest_and_friends(cut_out(image), image.size)

    alpha = Image.frombytes("L", image.size, bytes(mask))
    # Pull the edge in a pixel to drop the fringe anti-aliasing left behind,
    # then soften what remains.
    alpha = alpha.filter(ImageFilter.MinFilter(3)).filter(ImageFilter.GaussianBlur(0.6))

    cut = image.convert("RGBA")
    cut.putalpha(alpha)
    cut = cut.crop(alpha.point(lambda v: 255 if v > 8 else 0).getbbox())
    cut.save(CUTOUT)
    print(f"cut out {cut.size[0]}x{cut.size[1]} -> {CUTOUT.relative_to(ROOT)}")

    # Monochrome layer for themed icons: ink, not silhouette. Using how dark the
    # drawing is keeps the face and the mesh; a flat silhouette loses both.
    lum = cut.convert("L").tobytes()
    cut_alpha = cut.split()[-1].tobytes()
    ink = Image.new("RGBA", cut.size, (0, 0, 0, 0))
    ink.putalpha(Image.frombytes("L", cut.size, bytes(
        min(255, int((255 - l) * 1.35)) if a > 110 else 0 for l, a in zip(lum, cut_alpha))))

    for name, size in DENSITIES.items():
        out = RES / f"mipmap-{name}"
        out.mkdir(parents=True, exist_ok=True)
        place(cut, size).save(out / "ic_launcher_foreground.png")
        place(ink, size).save(out / "ic_launcher_monochrome.png")
        print(f"  {name:8s} {size:3d}px  foreground + monochrome")


if __name__ == "__main__":
    main()
