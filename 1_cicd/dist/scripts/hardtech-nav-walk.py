# ─── GENERATED: do not edit — edit 1_cicd/src/scripts/hardtech-nav-walk.py ───
#!/usr/bin/env python3
"""#1777 emulator walk of the Hardtech apps' navigation (run by 1_cicd/src/cicd/hardtech-nav-screens.yml, adb on the PATH).

usage: hardtech-nav-walk.py cad|biomed PACKAGE
cad    : Models (MCAD, BIM, SolidWorks), Home, Sim2Real (SAP2000, MATLAB, Ansys): one screen each.
biomed : the seven tabs; per tab it waits for the viewer's own state line ('Rendered: ...' / 'Not rendered: ...'), takes the screen and the
         WebGL canvas snapshot (cache/cad-snapshot-<model>.png through run-as), lights one topic and takes the screen again.
Screens go to ./screens/. A tab whose model is not rendered fails the walk (exit 1) so the proof cannot pass on a blank stage.
"""
import re, subprocess, sys, time

OUT = "screens"
kind, pkg = sys.argv[1], sys.argv[2]


def sh(*a): return subprocess.run(["adb", *a], capture_output=True, text=True, timeout=90)


def dump():
    for _ in range(6):
        sh("shell", "uiautomator", "dump", "/sdcard/u.xml")
        x = sh("shell", "cat", "/sdcard/u.xml").stdout
        if "<hierarchy" in x: return x
        time.sleep(2)
    return ""


def nodes(x):
    out = []
    for m in re.finditer(r"<node [^>]*>", x):
        n = m.group(0)
        t = re.search(r' text="([^"]*)"', n); d = re.search(r' content-desc="([^"]*)"', n); b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        if b: out.append(((t.group(1) if t else ""), (d.group(1) if d else ""), tuple(map(int, b.groups()))))
    return out


def find(label, x=None):
    want = label.lower()
    hits = [b for t, d, b in nodes(x or dump()) if t.lower() == want or d.lower() == want]
    return sorted(hits, key=lambda b: -b[1])   # the lowest on screen first (the bottom nav before a heading with the same word)


def tap(label, wait=3, lowest=True):
    hits = find(label)
    if not hits:
        print("::warning::no '%s' on screen" % label); return False
    x1, y1, x2, y2 = hits[0] if lowest else hits[-1]
    sh("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2)); time.sleep(wait); return True


def shot(name, wait=0):
    time.sleep(wait)
    png = b""
    for _ in range(3):
        png = subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True, timeout=60).stdout
        if len(png) > 1000: break
        time.sleep(3)
    open("%s/%s.png" % (OUT, name), "wb").write(png); print("shot", name, len(png))


def texts(): return [t for t, _, _ in nodes(dump()) if t]


def launch():
    sh("shell", "monkey", "-p", pkg, "-c", "android.intent.category.LAUNCHER", "1"); time.sleep(12)


def settle(limit=300):
    """Wait for the viewer's state line; returns it."""
    t0 = time.time()
    while time.time() - t0 < limit:
        for t in texts():
            if t.startswith("Rendered:") or t.startswith("Not rendered:"): return t
        time.sleep(6)
    return "timeout: no viewer state line after %ss" % limit


def snapshot(model, name):   # model = the snapshot's id: body | brain | dna | <id>-topic
    r = subprocess.run(["adb", "exec-out", "run-as", pkg, "cat", "cache/cad-snapshot-%s.png" % model], capture_output=True, timeout=60)
    if len(r.stdout) > 1000: open("%s/%s-canvas.png" % (OUT, name), "wb").write(r.stdout); print("canvas", name, len(r.stdout))


import os
os.makedirs(OUT, exist_ok=True)
launch()
rc = 0
if kind == "cad":
    shot("01-models-mcad")
    for i, p in enumerate(["BIM", "SolidWorks"], 2):
        tap(p); shot("0%d-models-%s" % (i, p.lower()), 1)
    tap("Home"); shot("04-home", 2)
    open("%s/04-texts.txt" % OUT, "w").write("\n".join(texts()))
    tap("Sim2Real"); shot("05-sim2real-sap2000", 2)
    for i, p in enumerate(["MATLAB", "Ansys"], 6):
        tap(p); shot("0%d-sim2real-%s" % (i, p.lower()), 1)
    open("%s/08-texts.txt" % OUT, "w").write("\n".join(texts()))
else:
    tabs = [("Physiology", "body", "Circulation"), ("BioChem", "body", "Circadian cycle"), ("BioMech", "body", "Knee"), ("Anatomy", "body", "Thorax"),
            ("Genetic", "dna", "Base pairing"), ("Neuro", "brain", "Hippocampus"), ("Immuno", "body", "Thymus")]
    for i, (tab, model, topic) in enumerate(tabs, 1):
        n = "%02d-%s" % (i, tab.lower())
        if not tap(tab, 4): rc = 1
        state = settle()
        print(tab, "->", state)
        open("%s/%s-state.txt" % (OUT, n), "w").write(state)
        if not state.startswith("Rendered:"): rc = 1
        shot(n + "-model"); snapshot(model, n)
        found = False
        for _ in range(4):
            if tap(topic, 4, lowest=False): found = True; break
            sh("shell", "input", "swipe", "540", "1800", "540", "700", "500"); time.sleep(2)   # the chips are below the viewer: scroll down to them
        if not found: print("::warning::topic '%s' not found on %s" % (topic, tab))
        for _ in range(4): sh("shell", "input", "swipe", "540", "600", "540", "1800", "400"); time.sleep(1)   # back to the top: the viewer with the lit parts
        time.sleep(6); shot(n + "-topic-" + re.sub(r"[^a-z0-9]+", "-", topic.lower()))
        snapshot(model + "-topic", n + "-topic")
sys.exit(rc)
