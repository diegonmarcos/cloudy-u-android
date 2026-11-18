#!/usr/bin/env bash
# emulator-canvas-screens (#1780): runs INSIDE the android-emulator-runner script step of .github/workflows/canvas-screens-emulator.yml.
# Installs Cloud Canvas (x86_64) and photographs:
#   draw-drawing   Draw with strokes and shapes painted by real touch swipes
#   draw-layers    Draw with the layers panel open and a second layer added
#   play-instruments  Play with the piano
#   play-mixer     Play with the mixer filled by the demo beat
#   enhancer-presets  Draw Enhancer with the preset picker and the expanded prompt panel open (#1782)
#   ai-configs        Configs > AI: key state, model per task, quality, size, cost cap
#   music-placeholder Music Enhancer coming-soon page, its nav entry greyed
# #1787:
#   play-piano        Play > Piano once the sampled grand piano is decoded (the sound line names Salamander Grand Piano)
#   play-drums        Play > Drums, ten pads, the sampled kit (the sound line names MuldjordKit)
#   corner-*          the bottom-right corner of Draw and Play (the old Music Enhancer veil drew a dark block there), cropped when ImageMagick is present
#   board-empty       Draw > Board: the offline Quickdraw whiteboard
#   board-drawn       Board after freehand strokes
#   board-reloaded    Board after a force-stop and relaunch: the strokes come back from app storage (boards.txt holds the stored index)
# #1808 Painter (the Interactive Hexagon Grid; launch extras shape/layout/mode/color are the page's overrides):
#   painter-nav               Painter open; nav-order.txt lists the bottom-nav labels left to right (Draw, Painter, Play, ...)
#   painter-<shape>-2d        hex, square, triangle, octagon in 2D, each with strokes painted by real touch swipes
#   painter-<shape>-3d        the same shape on the 3D Sphere, painted
#   painter-rubber            hex 2D after a Rubber stroke across the painted cells
#   painter-view              hex 2D in View mode
#   painter-reloaded          hex 2D after a force-stop and relaunch: the painting comes back from app storage (painter.txt holds the stored state)
# usage: emulator-canvas-screens.sh <canvas.apk> <enhance-presets.json> <outdir>
set -u
APK="$1"; DATA="$2"; OUT="$3"
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

# the Enhancer's dataset: the client's last-known-good file (key = route path with / as __), written as the app's own user (Cloud Store serves it on a real phone)
KEY="app__${PKG}__enhance-presets"
adb push "$DATA" /data/local/tmp/presets-seed.json >/dev/null
adb shell "run-as $PKG mkdir -p files/fleetdata-lkg; cat /data/local/tmp/presets-seed.json | run-as $PKG sh -c 'cat > files/fleetdata-lkg/$KEY.json'" 2>&1 | tee "$OUT/seed.txt"

# Draw: strokes with the brush in two colours, a rectangle and an ellipse, by touch
start --es section draw
sw() { adb shell input swipe "$@"; }
x0=$((W/5)); x1=$((W*4/5)); y0=$((H*38/100))
sw $x0 $y0 $((W/2)) $((y0+300)) 500; sw $((W/2)) $((y0+300)) $x1 $y0 500
sw $x0 $((y0+420)) $x1 $((y0+420)) 400
adb shell input swipe $((W*9/10)) 315 $((W/10)) 315 250; sleep 1
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
start --es section enhance --es panel prompt
shot enhancer-presets 6
dump > "$OUT/enhancer-presets.xml"
start --es configs ai
shot ai-configs 7
dump > "$OUT/ai-configs.xml"
start --es section music
shot music-placeholder 5
dump > "$OUT/music-placeholder.xml"
# ── #1787 sampled instruments, the corner, Board ──
start --es section play --es view instruments
sleep 10   # the samples decode in the background (about 40 MB of PCM)
shot play-piano 3
dump > "$OUT/play-piano.xml"
tap_text "Drums"; sleep 1
shot play-drums 3
dump > "$OUT/play-drums.xml"
corner() { command -v convert >/dev/null && convert "$OUT/$1.png" -gravity SouthEast -crop "$((W*2/5))x$((H/5))+0+0" +repage "$OUT/corner-$1.png" && echo "cropped corner-$1"; }
corner play-drums; corner draw-drawing
start --es section draw --es page board --es tool draw
sleep 8
shot board-empty 4
dump > "$OUT/board-empty.xml"
# the page opened with the pen (launch extra tool=draw): three strokes
by=$((H*35/100))
sw $((W/5)) $by $((W*4/5)) $((by+200)) 500; sw $((W/5)) $((by+400)) $((W*4/5)) $((by+300)) 500; sw $((W/2)) $((by-100)) $((W/2)) $((by+600)) 500
sleep 3   # the bridge saves 0.8 s after the last change
shot board-drawn 2
start --es section draw --es page board
sleep 8
shot board-reloaded 4
dump > "$OUT/board-reloaded.xml"
adb shell "run-as $PKG sh -c 'ls -l files/boards; cat files/boards/boards.json'" > "$OUT/boards.txt" 2>&1 || true
# ── #1808 Painter ──
pstart() { start --es section painter "$@"; sleep 6; }
py=$((H*42/100))
pstroke() { sw $((W/5)) $py $((W*4/5)) $((py+160)) 600; sw $((W/4)) $((py+260)) $((W*3/4)) $((py+200)) 600; }
pstart --es shape hex --es layout 2d --es mode paint
shot painter-nav 3
dump > "$OUT/painter-nav.xml"
python3 -I - "$OUT/painter-nav.xml" > "$OUT/nav-order.txt" <<'PY'
import re, sys
x = open(sys.argv[1], encoding="utf-8", errors="replace").read()
want = {"Draw", "Painter", "Play", "Draw Enhancer", "Music Enhancer"}
seen = {}
for m in re.finditer(r'<node [^>]*>', x):
    n = m.group(0)
    for attr in ("text", "content-desc"):
        t = re.search(' %s="([^"]*)"' % attr, n)
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        if t and b and t.group(1) in want:
            a = list(map(int, b.groups()))
            if a[1] > seen.get(t.group(1), (0, -1))[1]: seen[t.group(1)] = (a[0], a[1])
print(" | ".join(k for k, _ in sorted(seen.items(), key=lambda kv: kv[1][0])))
PY
echo "nav order: $(cat "$OUT/nav-order.txt")"
colors="'#ef4444' '#22c55e' '#3b82f6' '#a855f7'"
i=0
for shape in hex square triangle octagon; do
  i=$((i+1)); c=$(echo $colors | cut -d' ' -f$i)
  pstart --es shape $shape --es layout 2d --es mode paint --es color "$c"
  pstroke; sleep 1
  shot painter-$shape-2d 2
  pstart --es shape $shape --es layout 3d --es mode paint --es color "'#eab308'"
  pstroke; sleep 1
  shot painter-$shape-3d 2
done
pstart --es shape hex --es layout 2d --es mode rubber
sw $((W/5)) $py $((W*4/5)) $((py+160)) 600; sleep 1
shot painter-rubber 2
pstart --es shape hex --es layout 2d --es mode view
sw $((W/2)) $py $((W/3)) $((py+200)) 600; sleep 1
shot painter-view 2
sleep 2   # the page saves 0.5 s after the last change
pstart --es shape hex --es layout 2d --es mode paint
shot painter-reloaded 3
adb shell "run-as $PKG sh -c 'ls -l files/painter; cat files/painter/painter.json'" > "$OUT/painter.txt" 2>&1 || true
timeout 60 adb logcat -d > "$OUT/logcat.txt" || true
grep -E "MediaCodec|chromium|CloudWeb" "$OUT/logcat.txt" | tail -40 > "$OUT/samples-and-board-log.txt" || true
ls -l "$OUT"
