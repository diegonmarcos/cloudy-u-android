#!/usr/bin/env python3
"""png_stats.py FILE [x0 y0 x1 y1 as fractions] -> prints "black=<fraction> distinct=<n>" for an 8-bit RGB/RGBA PNG (screencap). Exit 3 when the region is >= 98% black."""
import struct, sys, zlib

def load(path):
    d = open(path, "rb").read()
    assert d[:8] == b"\x89PNG\r\n\x1a\n"
    pos, idat, w = 8, b"", 0
    while pos < len(d):
        n, t = struct.unpack(">I4s", d[pos:pos + 8]); body = d[pos + 8:pos + 8 + n]; pos += 12 + n
        if t == b"IHDR": w, h, bd, ct, _, _, il = struct.unpack(">IIBBBBB", body)
        elif t == b"IDAT": idat += body
    assert bd == 8 and il == 0 and ct in (2, 6), "unsupported png"
    bpp = 3 if ct == 2 else 4
    raw = zlib.decompress(idat); stride = w * bpp; rows = []; prev = bytearray(stride); i = 0
    for _ in range(h):
        f = raw[i]; line = bytearray(raw[i + 1:i + 1 + stride]); i += 1 + stride
        for x in range(stride):
            a = line[x - bpp] if x >= bpp else 0; b = prev[x]; c = prev[x - bpp] if x >= bpp else 0
            if f == 1: line[x] = (line[x] + a) & 255
            elif f == 2: line[x] = (line[x] + b) & 255
            elif f == 3: line[x] = (line[x] + (a + b) // 2) & 255
            elif f == 4:
                p = a + b - c; pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[x] = (line[x] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        rows.append(line); prev = line
    return w, h, bpp, rows

def main():
    w, h, bpp, rows = load(sys.argv[1])
    fx0, fy0, fx1, fy1 = (float(v) for v in sys.argv[2:6]) if len(sys.argv) >= 6 else (0, 0, 1, 1)
    black = total = 0; colours = set()
    for y in range(int(h * fy0), int(h * fy1), 4):
        r = rows[y]
        for x in range(int(w * fx0), int(w * fx1), 4):
            px = r[x * bpp:x * bpp + 3]; total += 1
            if px[0] + px[1] + px[2] <= 24: black += 1
            colours.add((px[0] >> 4, px[1] >> 4, px[2] >> 4))
    frac = black / max(total, 1)
    print("black=%.3f distinct=%d" % (frac, len(colours)))
    sys.exit(3 if frac >= 0.98 else 0)

main()
