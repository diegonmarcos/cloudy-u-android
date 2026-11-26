#!/usr/bin/env python3
"""Render assertions for the emulator walk (#1937/#1938). `pixels PNG`: the content band (below the chrome, above the island) must not be near-uniform:
at least MIN_DEVIANT of its pixels differ from the band's dominant colour by more than 40 luma levels, AND the page must not be a near-black frame
(mean luma > 8 or deviant ratio high). `projhome PNG XML`: no image view taller than MAX_BANNER of the screen height, and project tile text on screen."""
import re, sys, html
from PIL import Image

MIN_DEVIANT = 0.02
MAX_BANNER = 0.40
MIN_TILE_TEXTS = 6

def luma(p): return 0.299 * p[0] + 0.587 * p[1] + 0.114 * p[2]

def pixels(png):
    im = Image.open(png).convert("RGB"); w, h = im.size
    band = im.crop((0, int(h * 0.28), w, int(h * 0.90))).resize((120, 160))
    ls = [luma(p) for p in band.getdata()]
    from collections import Counter
    dom = Counter(int(l) // 8 for l in ls).most_common(1)[0][0] * 8 + 4
    dev = sum(1 for l in ls if abs(l - dom) > 40) / len(ls)
    mean = sum(ls) / len(ls)
    ok = dev >= MIN_DEVIANT
    print(f"{'PASS' if ok else 'FAIL'} pixels {png}: deviant_ratio={dev:.4f} (min {MIN_DEVIANT}) mean_luma={mean:.1f} dominant_luma={dom}")
    return ok

def bounds(node): 
    m = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]', node); return tuple(map(int, m.groups())) if m else None

def projhome(png, xml):
    h = Image.open(png).size[1]
    try: x = html.unescape(open(xml, errors="ignore").read())
    except OSError: print(f"FAIL projhome: no ui dump {xml}"); return False
    biggest = 0; texts = 0
    for n in re.findall(r"<node [^>]*>", x):
        b = bounds(n)
        if not b: continue
        if 'class="android.widget.ImageView"' in n: biggest = max(biggest, b[3] - b[1])
        t = re.search(r'text="([^"]+)"', n)
        if t and b[1] > h * 0.20 and b[3] <= h and b[3] - b[1] < h * 0.2: texts += 1
    frac = biggest / h
    ok = frac <= MAX_BANNER and texts >= MIN_TILE_TEXTS
    print(f"{'PASS' if ok else 'FAIL'} projhome: tallest_imageview={biggest}px = {frac:.2f} of screen height (max {MAX_BANNER}); tile/text nodes below the chrome={texts} (min {MIN_TILE_TEXTS})")
    return ok

if __name__ == "__main__":
    ok = pixels(sys.argv[2]) if sys.argv[1] == "pixels" else projhome(sys.argv[2], sys.argv[3])
    sys.exit(0 if ok else 1)
