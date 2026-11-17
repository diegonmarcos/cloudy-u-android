#!/usr/bin/env bash
# emulator-canvas-screens (#1780): runs INSIDE the android-emulator-runner script step of .github/workflows/canvas-screens-emulator.yml.
# Installs Cloud Canvas (x86_64) and photographs:
#   draw-drawing   Draw with strokes and shapes painted by real touch swipes
#   draw-layers    Draw with the layers panel open and a second layer added
#   play-instruments  Play with the piano
#   play-mixer     Play with the mixer filled by the demo beat
# usage: emulator-canvas-screens.sh <canvas.apk> <outdir>
set -u
APK="$1"; OUT="$2"
mkdir -p "$OUT"
aapt2=$(ls -d "$ANDROID_HOME"/build-tools/*/aapt2 | sort -V | tail -1)
PKG=$("$aapt2" dump packagename "$APK")
adb install -r -g "$APK" || exit 2
ACT=$(adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "$PKG" | tr -d '\r' | tail -1)
echo "canvas=$PKG ($ACT)"
adb shell settings put global hide_error_dialogs 1 || true
adb shell settings put global window_animation_scale 0; adb shell settings put global transition_animation_scale 0
adb shell settings put system screen_off_timeout 2147483647; adb shell svc power stayon true
adb shell locksettings set-disabled true || true
awake() { adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard; adb shell input keyevent 82; }
shot() { awake; sleep "${2:-5}"; adb exec-out screencap -p > "$OUT/$1.png"; echo "captured $1"; }
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml 2>/dev/null; }
# tap the centre of the first node whose text or content-desc equals $1
tap_text() {
  dump > "$OUT/ui.xml"
  xy=$(python3 -I - "$1" "$OUT/ui.xml" <<'PY'
import re, sys
want, path = sys.argv[1], sys.argv[2]
x = open(path, encoding="utf-8", errors="replace").read()
for m in re.finditer(r'<node [^>]*>', x):
    n = m.group(0)
    t = re.search(r' text="([^"]*)"', n); c = re.search(r' content-desc="([^"]*)"', n)
    if (t and t.group(1) == want) or (c and c.group(1) == want):
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        if b:
            a = list(map(int, b.groups())); print((a[0] + a[2]) // 2, (a[1] + a[3]) // 2); break
PY
)
  if [ -n "$xy" ]; then adb shell input tap $xy; echo "tapped $1 at $xy"; else echo "no node '$1'"; fi
}
start() { adb shell am force-stop "$PKG"; adb shell am start -n "$ACT" "$@" >/dev/null; sleep 6; }
sz=$(adb shell wm size | tr -d '\r' | sed -n 's/.*: \([0-9]*\)x\([0-9]*\)$/\1 \2/p' | tail -1); W=${sz% *}; H=${sz#* }

# Draw: strokes with the brush in two colours, a rectangle and an ellipse, by touch
start --es section draw
sw() { adb shell input swipe "$@"; }
x0=$((W/5)); x1=$((W*4/5)); y0=$((H*38/100))
sw $x0 $y0 $((W/2)) $((y0+300)) 500; sw $((W/2)) $((y0+300)) $x1 $y0 500
sw $x0 $((y0+420)) $x1 $((y0+420)) 400
tap_text "Rect"; sw $((W/4)) $((y0+520)) $((W*3/4)) $((y0+760)) 400
tap_text "Ellipse"; sw $((W/3)) $((y0+60)) $((W*2/3)) $((y0+240)) 400
tap_text "Marker"; sw $x0 $((y0+900)) $x1 $((y0+900)) 400
shot draw-drawing 4
dump > "$OUT/draw-drawing.xml"
# Layers: add a layer, draw on it, open the panel
tap_text "Add layer" || true
tap_text "Layers"; sleep 1; tap_text "Add layer"; sleep 1
tap_text "Brush"; sw $x0 $((y0+120)) $x1 $((y0+500)) 500
shot draw-layers 4
dump > "$OUT/draw-layers.xml"
# Play
start --es section play --es view instruments
shot play-instruments 5
dump > "$OUT/play-instruments.xml"
start --es section play --es view mixer --es demo true
shot play-mixer 5
dump > "$OUT/play-mixer.xml"
timeout 60 adb logcat -d > "$OUT/logcat.txt" || true
ls -l "$OUT"
