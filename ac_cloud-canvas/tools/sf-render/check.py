#!/usr/bin/env python3
"""Cloud Canvas (#1809): checks the CI offline renders of tools/sf-render (32-bit float stereo WAVs).

For every take:
  C1 the take is not silent (peak above -40 dBFS) and is finite
  C2 the peak is below -1 dBFS (the master limiter's ceiling is -1.5 dBFS)
  C3 no discontinuity: no sample-to-sample step larger than DISC times the take's own 99.9th-percentile step and above -30 dBFS
     (a click: a voice cut, a buffer seam or a hard clip), and no run of 3+ samples pinned at the same |value| above -3 dBFS (clipping)
  C4 the pre-limiter peak render.cpp measured is printed (the gain staging: the limiter should rarely work)
Exit 1 on any failure. Pure standard library.
usage: check.py <dir with *.wav and *.json>
       check.py --plant <one.wav> <dir>   writes three planted faults (click, clip, overshoot) the check must turn red on
"""
import array
import glob
import json
import math
import os
import struct
import sys

DISC = 8.0


def selftest(src, dst):
    """Writes planted-fault copies of one render: a 5 ms dropout (a click), a hard clip and a +2 dB overshoot. check.py must fail each."""
    b = bytearray(open(src, "rb").read())
    n = struct.unpack_from("<I", b, 40)[0] // 4
    x = array.array("f"); x.frombytes(bytes(b[44:44 + n * 4]))
    os.makedirs(dst, exist_ok=True)
    def save(name, y):
        open(os.path.join(dst, name + ".wav"), "wb").write(bytes(b[:44]) + y.tobytes())
    loud = max(range(0, n, 2), key=lambda i: abs(x[i]))
    y = array.array("f", x); 
    for i in range(loud, min(n, loud + 480)): y[i] = 0.0
    save("planted-click", y)
    y = array.array("f", (max(-0.95, min(0.95, v * 40.0)) for v in x)); save("planted-clip", y)
    k = 10 ** (2 / 20) / max(abs(v) for v in x)
    y = array.array("f", (v * k for v in x)); save("planted-overshoot", y)


if len(sys.argv) == 4 and sys.argv[1] == "--plant":
    selftest(sys.argv[2], sys.argv[3]); sys.exit(0)

fails = 0
rows = []
for path in sorted(glob.glob(os.path.join(sys.argv[1], "*.wav"))):
    name = os.path.basename(path)[:-4]
    b = open(path, "rb").read()
    assert b[:4] == b"RIFF" and b[8:12] == b"WAVE", name
    fmt = struct.unpack_from("<HHIIHH", b, 20)
    assert fmt[0] == 3 and fmt[1] == 2 and fmt[5] == 32, "%s is not 32-bit float stereo" % name
    data = array.array("f")
    data.frombytes(b[44:44 + struct.unpack_from("<I", b, 40)[0]])
    if sys.byteorder != "little": data.byteswap()
    rate = fmt[2]
    peak = max((abs(x) for x in data), default=0.0)
    db = 20 * math.log10(peak) if peak > 0 else -999.0
    bad = []
    if not all(math.isfinite(x) for x in data): bad.append("C1 non-finite samples")
    if db < -40: bad.append("C1 silent (peak %.1f dBFS)" % db)
    if db >= -1.0: bad.append("C2 peak %.2f dBFS is not below -1 dBFS" % db)
    worst = 0.0
    for ch in (0, 1):
        x = data[ch::2]
        steps = sorted(abs(x[i + 1] - x[i]) for i in range(0, len(x) - 1, 7))
        p999 = steps[int(len(steps) * 0.999)] if steps else 0.0
        lim = max(p999 * DISC, 10 ** (-30 / 20))
        for i in range(len(x) - 1):
            s = abs(x[i + 1] - x[i])
            worst = max(worst, s / p999 if p999 > 0 else 0.0)
            if s > lim:
                bad.append("C3 discontinuity on channel %d at %.4f s (step %.4f, %.1fx the take's p99.9 step)" % (ch, i / rate, s, s / max(p999, 1e-9)))
                break
        run = 0
        for i in range(1, len(x)):
            run = run + 1 if abs(x[i]) > 10 ** (-3 / 20) and abs(x[i]) == abs(x[i - 1]) else 0
            if run >= 2:
                bad.append("C3 clipped run on channel %d at %.4f s" % (ch, i / rate)); break
    meta = {}
    if os.path.isfile(path[:-4] + ".json"): meta = json.load(open(path[:-4] + ".json"))
    raw = meta.get("raw_peak")
    rawdb = 20 * math.log10(raw) if raw else float("nan")
    rows.append("%-24s peak %7.2f dBFS  pre-limiter %7.2f dBFS  limiter min gain %.3f  largest step %.1fx p99.9  %s"
                % (name, db, rawdb, meta.get("limiter_min_gain", float("nan")), worst, "PASS" if not bad else "FAIL"))
    for m in bad: rows.append("    " + m)
    fails += 1 if bad else 0
print("\n".join(rows))
if not rows: print("FAIL: no renders found"); sys.exit(1)
print("%d take(s), %d failed" % (len(rows) - sum(1 for r in rows if r.startswith("    ")), fails))
sys.exit(1 if fails else 0)
