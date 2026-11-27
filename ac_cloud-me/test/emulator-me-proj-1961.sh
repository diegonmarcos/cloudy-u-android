#!/usr/bin/env bash
# emulator-me-proj-1961 — #1961 on a booted emulator: Proj > CG > Omniverse (a site entry, README tabs from CG-galaxy) and Proj > Unix > Cloud Me (a GitHub entry)
# and Proj > Wiki > ModernSc (a lib-chronos folder: its trio is lib-chronos/1.Modern-science/README.*) are opened from the Ship APK. The tab row must read README.html, GH Repo Page, README.canvas, README.md (then Site for CG), README.html must be the open tab
# on a fresh install, and the README.html page is screenshotted top to bottom (its sections: metadata, URLs, graphics, workflows, hooks, tree, explorer, ...).
# Usage: emulator-me-proj-1961.sh ME_APK OUT_DIR. Exit 1 on any failed assertion.
set -uo pipefail
ME_APK="$1"; OUT="$2"; mkdir -p "$OUT"
T="timeout 120"; ME=com.diegonmarcos.cloudme; rc=0
HERE="$(cd "$(dirname "$0")" && pwd)"
$T adb wait-for-device
$T adb install -r -g "$ME_APK" || { echo "install failed"; exit 1; }
$T adb shell settings put global window_animation_scale 0
$T adb shell locksettings set-disabled true; $T adb shell settings put system screen_off_timeout 1800000
$T adb shell cmd uimode night yes
$T adb shell wm size 1080x2400; $T adb shell wm density 420
wake() { $T adb shell svc power stayon true; $T adb shell input keyevent KEYCODE_WAKEUP; $T adb shell wm dismiss-keyguard; }
launcher() { $T adb shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER "$1" | tail -1 | tr -d '\r'; }
open_page() { wake; $T adb shell am start -W -n "$(launcher "$ME")" -f 0x14000000 --es shortcut_action "$1" >/dev/null; }
shot() { sleep "${2:-6}"; $T adb exec-out screencap -p > "$OUT/$1.png"; rm -f "$OUT/$1.xml"; $T adb shell rm -f /sdcard/ui.xml
    for _ in 1 2 3; do $T adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; $T adb pull /sdcard/ui.xml "$OUT/$1.xml" >/dev/null 2>&1 && break; sleep 2; done; echo "shot $1"; }
for op in GET_USAGE_STATS SYSTEM_ALERT_WINDOW MANAGE_EXTERNAL_STORAGE REQUEST_INSTALL_PACKAGES; do $T adb shell appops set "$ME" "$op" allow; done
open_page "page:configs/general"; sleep 12
for i in 1 2 3 4 5 6; do case "$($T adb shell dumpsys window 2>/dev/null | grep -m1 mCurrentFocus || true)" in *"$ME"*) break ;; esac; $T adb shell input keyevent KEYCODE_BACK; sleep 2; done
tabs() {  # SHOT WANT...: the chip labels of the selector, left to right, must equal WANT; the row scrolls, so it is dumped again scrolled to its end and merged
    local s="$1"; shift
    $T adb shell input swipe 1000 590 80 590 300; shot "$s-tabs-end" 2; $T adb shell input swipe 80 590 1000 590 300; sleep 1
    python3 -I - "$OUT/$s.xml" "$OUT/$s-tabs-end.xml" "$@" <<'PY' | tee -a "$OUT/verdict.txt"
import re, sys, xml.etree.ElementTree as ET
want = sys.argv[3:]; known = {"README.html", "GH Repo Page", "README.canvas", "README.md", "Site"}
def row(f):
  try: nodes = list(ET.parse(f).getroot().iter("node"))
  except Exception as e: print("FAIL tabs %s: no ui dump (%s)" % (f, e)); sys.exit(1)
  got = []
  for n in nodes:
    t = n.get("text") or ""
    if t in known and not (n.get("bounds") or "").startswith("[0,0][0,0]"):
        m = re.match(r"\[(\d+),(\d+)\]", n.get("bounds") or ""); got.append((int(m.group(2)) if m else 0, int(m.group(1)) if m else 0, t))
  top = min((g[0] for g in got), default=0); return [t for y, x, t in sorted(got, key=lambda g: (g[0], g[1])) if abs(y - top) < 40]
a, b = row(sys.argv[1]), row(sys.argv[2])
k = next((i for i in range(len(a)) if a[i:] == b[:len(a) - i]), len(a)); labels = a[:k] + b   # a's prefix, then the scrolled row (overlap merged)
ok = labels == want
print("%s tabs %s: %s (want %s)" % ("PASS" if ok else "FAIL", sys.argv[1].rsplit("/", 1)[-1], labels, want)); sys.exit(0 if ok else 1)
PY
}
open_page "page:proj/pj-cg-omniverse"; sleep 20   # warm-up: the first WebView of a cold start paints late
for p in "cg-omniverse:pj-cg-omniverse:site" "unix-cloudme:pj-unix-cloudme:" "wiki-modernsc:pj-wiki-modernsc:"; do
    id="${p%%:*}"; rest="${p#*:}"; page="${rest%%:*}"; site="${rest#*:}"
    open_page "page:proj/$page"; shot "proj-1961-$id-readme-html" 20
    if [ -n "$site" ]; then tabs "proj-1961-$id-readme-html" "README.html" "GH Repo Page" "README.canvas" "README.md" "Site" || rc=1
    else tabs "proj-1961-$id-readme-html" "README.html" "GH Repo Page" "README.canvas" "README.md" || rc=1; fi
    for k in 1 2 3 4 5 6; do $T adb shell input swipe 540 1500 540 600 400; shot "proj-1961-$id-readme-html-s$k" 3; done
done
$T adb logcat -d > "$OUT/logcat.txt"
pip install --quiet pillow >/dev/null 2>&1 || true
for id in cg-omniverse unix-cloudme wiki-modernsc; do python3 "$HERE/render_check.py" pixels "$OUT/proj-1961-$id-readme-html.png" | tee -a "$OUT/verdict.txt" || rc=1; done
echo "rc=$rc" | tee -a "$OUT/verdict.txt"
exit $rc
