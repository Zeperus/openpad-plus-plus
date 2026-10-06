#!/usr/bin/env python3
"""Builds the launcher icon resources from the owner-supplied artwork (openpad-icon-original.png).

The artwork is NOT redrawn or altered except for removing the white backdrop outside the rounded square:
 1. white is flood-filled from the image border (so white inside the artwork, e.g. the page-curl highlight, stays),
 2. anti-aliased edge pixels are un-mixed from white into real alpha (no white fringe),
 3. the result is placed on an Android adaptive-icon canvas.

Outputs (relative to the repo root):
  design/icon/openpad-icon-transparent.png            full artwork, transparent corners
  app/src/main/res/drawable-nodpi/ic_launcher_foreground.png   432x432, artwork scaled to the 72dp visible window
  app/src/main/res/drawable-nodpi/ic_launcher_monochrome.png   432x432, note silhouette (themed icons)
  app/src/main/res/values/ic_launcher_background.xml          the artwork's own near-black
"""
import sys
from pathlib import Path
from PIL import Image, ImageChops, ImageDraw, ImageFilter

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
src = Image.open(HERE / "openpad-icon-original.png").convert("RGB")
W, H = src.size

# 1. flood-fill the white backdrop from every border point that is white-ish
marker = (255, 0, 255)
work = src.copy()
seeds = [(x, y) for x in range(0, W, 40) for y in (0, H - 1)] + [(x, y) for y in range(0, H, 40) for x in (0, W - 1)]
for seed in seeds:
    r, g, b = work.getpixel(seed)
    if min(r, g, b) > 200:
        ImageDraw.floodfill(work, seed, marker, thresh=60)
bg = Image.new("L", (W, H), 0)
bg.paste(255, mask=Image.eval(ImageChops.difference(work, Image.new("RGB", (W, H), marker)).convert("L"), lambda v: 255 if v == 0 else 0))

# 2. anti-aliased rim: pixels next to the backdrop that are mixtures of the dark body and white
dark = src.getpixel((W // 8, H // 8))  # the artwork's own near-black
dark_l = sum(dark) / 3
rim = ImageChops.subtract(bg.filter(ImageFilter.MaxFilter(9)), bg)
out = Image.new("RGBA", (W, H))
sp, bp, rp, op = src.load(), bg.load(), rim.load(), out.load()
for y in range(H):
    for x in range(W):
        if bp[x, y]:
            op[x, y] = (0, 0, 0, 0)
        elif rp[x, y]:
            l = sum(sp[x, y]) / 3
            a = max(0.0, min(1.0, (255 - l) / (255 - dark_l)))
            op[x, y] = (*dark, round(a * 255))
        else:
            op[x, y] = (*sp[x, y], 255)
out.save(HERE / "openpad-icon-transparent.png", optimize=True)

# 3. adaptive icon layers: the whole artwork fills the 72dp visible window of the 108dp canvas
CANVAS = 432  # 108dp at xxxhdpi
visible = round(CANVAS * 72 / 108)
off = (CANVAS - visible) // 2
fg = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
fg.alpha_composite(out.resize((visible, visible), Image.LANCZOS), (off, off))
res = ROOT / "app/src/main/res"
(res / "drawable-nodpi").mkdir(parents=True, exist_ok=True)
fg.save(res / "drawable-nodpi/ic_launcher_foreground.png", optimize=True)

# themed (monochrome) layer: the yellow note as a solid shape, the blue "++" cut out
mono = Image.new("RGBA", (W, H), (0, 0, 0, 0))
mp = mono.load()
for y in range(H):
    for x in range(W):
        r, g, b = sp[x, y]
        if bp[x, y] or rp[x, y]:
            continue
        note_like = (r + g + b) / 3 > 70 and not (b > r + 60 and b > 140)
        if note_like:
            mp[x, y] = (255, 255, 255, 255)
mono = mono.filter(ImageFilter.GaussianBlur(0.8))
mono_canvas = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
mono_canvas.alpha_composite(mono.resize((visible, visible), Image.LANCZOS), (off, off))
mono_canvas.save(res / "drawable-nodpi/ic_launcher_monochrome.png", optimize=True)

(res / "values/ic_launcher_background.xml").write_text(
    '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
    f'    <color name="ic_launcher_background">#{dark[0]:02X}{dark[1]:02X}{dark[2]:02X}</color>\n</resources>\n'
)
print("done; body colour", dark, "visible", visible, "offset", off)
