import re, subprocess, sys, time
OUT = "screens"
pkg = sys.argv[1]
def sh(*a): return subprocess.run(["adb", *a], capture_output=True, text=True, timeout=90)
def dump():
    for _ in range(6):
        sh("shell", "uiautomator", "dump", "/sdcard/u.xml")
        x = sh("shell", "cat", "/sdcard/u.xml").stdout
        if "<hierarchy" in x: return x
        time.sleep(2)
    return ""
def texts(x): return re.findall(r' (?:text|content-desc)="([^"]+)"', x)
def find(sub, x=None):
    out = []
    for m in re.finditer(r'<node [^>]*>', x or dump()):
        n = m.group(0); t = re.search(r' text="([^"]*)"', n); b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        if t and b and sub in t.group(1): out.append(tuple(map(int, b.groups())))
    return out
def tap_xy(x, y): sh("shell", "input", "tap", str(x), str(y)); time.sleep(2)
def shot(name, wait=0):
    time.sleep(wait)
    png = b""
    for _ in range(3):
        png = subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True, timeout=60).stdout
        if len(png) > 1000: break
        time.sleep(3)
    open("%s/%s.png" % (OUT, name), "wb").write(png); print("shot", name, len(png))
def note(name, x): open("%s/%s.txt" % (OUT, name), "w").write("\n".join(texts(x)))
def scroll_to(sub, tries=14):
    for i in range(tries):
        x = dump(); h = find(sub, x)
        if h: return h
        sh("shell", "input", "swipe", "540", "1700", "540", "800", "500"); time.sleep(1.5)
    print("::warning::never found '%s'" % sub); return []
def settle(limit=300):
    t0 = time.time()
    while time.time() - t0 < limit:
        x = dump(); ts = texts(x)
        if not any(t.startswith("Fetching") or "Loading" in t or "Converting" in t for t in ts): return x
        time.sleep(6)
    print("::warning::still loading after %ss" % limit); return dump()
def back():
    sh("shell", "input", "keyevent", "KEYCODE_BACK"); time.sleep(3)
def home_models():
    sh("shell", "monkey", "-p", pkg, "-c", "android.intent.category.LAUNCHER", "1"); time.sleep(10)

# multitouch (type B) through sendevent: a real two-finger pinch on the emulator's touch screen
def touch_dev():
    out = sh("shell", "getevent", "-lp").stdout
    cur = None; best = None
    for line in out.splitlines():
        m = re.match(r"add device \d+: (\S+)", line)
        if m: cur = m.group(1)
        if "ABS_MT_POSITION_X" in line and cur: best = cur; mx = re.search(r"max (\d+)", line); rng = int(mx.group(1)) if mx else 32767
    return best, rng if best else 32767
def pinch(cx, cy, d0, d1, steps=12):
    dev, rng = touch_dev()
    if not dev: print("::warning::no multitouch device"); return False
    size = sh("shell", "wm", "size").stdout; m = re.search(r"(\d+)x(\d+)", size); W, H = int(m.group(1)), int(m.group(2))
    def sx(v): return int(v * rng / W)
    def sy(v): return int(v * rng / H)
    ev = []
    def e(t, c, v): ev.append("sendevent %s %d %d %d" % (dev, t, c, v))
    for s in range(steps + 1):
        d = d0 + (d1 - d0) * s / steps
        for slot, sgn in ((0, -1), (1, 1)):
            e(3, 47, slot)
            if s == 0: e(3, 57, 100 + slot)
            e(3, 53, sx(cx + sgn * d)); e(3, 54, sy(cy))
        e(0, 0, 0); ev.append("sleep 0.04")
    for slot in (0, 1): e(3, 47, slot); e(3, 57, -1)
    e(0, 0, 0)
    script = "\n".join(ev)
    r = subprocess.run(["adb", "shell", script], capture_output=True, text=True, timeout=120)
    print("pinch", r.returncode, r.stderr[:200]); time.sleep(2); return r.returncode == 0

def open_model(label, key):
    home_models(); shot("%s-00-home" % key)
    h = scroll_to(label)
    if not h: return False
    x1, y1, x2, y2 = h[0]; tap_xy((x1 + x2) // 2, (y1 + y2) // 2); time.sleep(5)
    shot("%s-01-opened-loading", 1) if False else shot("%s-01-opened-loading" % key)
    x = settle(); time.sleep(20)
    shot("%s-02-rendered" % key); note("%s-02-texts" % key, dump()); return True

sh("shell", "monkey", "-p", pkg, "-c", "android.intent.category.LAUNCHER", "1"); time.sleep(12)
shot("00-launch"); note("00-launch-texts", dump())
# the catalogue: scroll to the models table / cards (both new models, with photos on the cards)
scroll_to("DJI S900"); shot("01-catalogue-a"); note("01-catalogue-a-texts", dump())
sh("shell", "input", "swipe", "540", "1700", "540", "900", "500"); shot("01-catalogue-b", 1)
sh("shell", "input", "swipe", "540", "1700", "540", "900", "500"); shot("01-catalogue-c", 1); note("01-catalogue-c-texts", dump())
sh("shell", "input", "swipe", "540", "1700", "540", "900", "500"); shot("01-catalogue-d", 1)
ok_boat = open_model("House Boat", "boat")
ok_drone = open_model("DJI S900", "drone")
if ok_drone:
    x = dump(); note("drone-03-parts-probe", x)
    p = find("Parts", x); print("parts nodes", p)
    shot("drone-03-view-before")
    # rotate: a real one-finger drag across the viewer
    sh("shell", "input", "swipe", "300", "700", "800", "700", "600"); time.sleep(2); shot("drone-04-rotated")
    pinch(540, 700, 60, 300); shot("drone-05-zoomed-in")
    pinch(540, 700, 300, 60); shot("drone-06-zoomed-out")
    if p:
        x1, y1, x2, y2 = p[0]; tap_xy((x1 + x2) // 2, (y1 + y2) // 2); time.sleep(2); shot("drone-07-parts-panel"); note("drone-07-texts", dump())
    for _ in range(3):
        sh("shell", "input", "swipe", "540", "1700", "540", "900", "500"); time.sleep(1)
    shot("drone-08-scrolled"); note("drone-08-texts", dump())
