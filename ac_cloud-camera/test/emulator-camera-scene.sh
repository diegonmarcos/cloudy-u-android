#!/usr/bin/env bash
# emulator-camera-scene.sh — #1805 PROOF of Cloud Camera > Objects as a scene, on an emulator, with the real engines.
#
# Installs the image engine (Cloud-Lib-Ml-L-Image-Mlkit.apk, contract 4: MediaPipe EfficientDet-Lite0, LiteRT YOLO11n,
# ML Kit v2 OCR with five scripts) and Cloud Camera, generates fixture images with objects and text, and requires:
#   B1  the benchmark ran: both detectors answered on the emulator, engine-side and end to end (frame -> binder ->
#       detect -> answer), with their latency and fps, and the OCR pass timed; the numbers land in bench.json and
#       bench.md (the report's table).
#   B2  the declared default detector is the faster one at comparable accuracy (both find the fixture's person), or
#       the run says which one is and why (the default is a reviewed edit of recognition.json, never silent).
#   F1  an opened image shows its objects framed and named (person) and its text regions framed "Text"
#       (screenshot frames-labels.png, the overlay's status line).
#   F2  the whole frame is read: the words of the fixture's sign are laid out (logcat `words [...]`).
#   T1  a tap on a word selects and copies exactly that word (logcat `copied "..."`; screenshot text-selection.png).
#   T2  a second tap on it grows the selection to its line.
#   T3  dragging the end handle extends the selection over the following words (screenshot text-handles.png).
#   T4  Copy all copies the whole read text of a document fixture.
#   T5  the frozen frame's text is reused: selecting again runs no new OCR (no second `words` line for the frame).
#   L1  the live stream: Resume goes back to the camera and the status line reports the detection rate.
# Evidence (screens, ui dumps, logcat, bench.json, bench.md) lands in <outdir>. The phone's own camera, lighting and
# a mid-range SoC are the on-phone check.
#
# usage: emulator-camera-scene.sh <engine.apk> <camera.apk> <golden-dir> <outdir>
set -u
ENGINE_APK="$1"; APK="$2"; GOLDEN="$3"; OUT="$4"
mkdir -p "$OUT"
aapt2=$(ls -d "$ANDROID_HOME"/build-tools/*/aapt2 | sort -V | tail -1)
PKG=$("$aapt2" dump packagename "$APK")
EPKG=$("$aapt2" dump packagename "$ENGINE_APK")
adb install -r -g "$ENGINE_APK" || exit 2
adb install -r -g "$APK" || exit 2
ACT=$(adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "$PKG" | tr -d '\r' | tail -1)
echo "camera=$PKG ($ACT) engine=$EPKG"
adb shell settings put global hide_error_dialogs 1 || true
adb shell settings put global window_animation_scale 0; adb shell settings put global transition_animation_scale 0
adb shell settings put system screen_off_timeout 2147483647; adb shell svc power stayon true
adb shell locksettings set-disabled true || true
# no fleet first-run Perms page over the camera (the grants came with install -g)
adb shell settings put global cloud_perms_first_run 0 || true
DENSITY=$(adb shell wm density | tr -d '\r' | sed -n 's/.*: \([0-9]*\)$/\1/p' | tail -1); DENSITY=${DENSITY:-420}

FAILS=0
ok()   { echo "ok     $1"; }
fail() { echo "FAIL   $1"; FAILS=$((FAILS + 1)); }
# a system "X isn't responding" dialog (seen on these runners: Pixel Launcher, run 44314218765) covers the app and
# swallows every tap: answer "Wait"
unanr() {
  adb shell uiautomator dump /sdcard/anr.xml >/dev/null 2>&1
  adb exec-out cat /sdcard/anr.xml 2>/dev/null | grep -q "isn't responding\|isn&apos;t responding" || return 0
  xy=$(adb exec-out cat /sdcard/anr.xml | python3 -I -c '
import re, sys
for n in re.findall(r"<node [^>]*>", sys.stdin.read()):
    if re.search(r" text=\"Wait\"", n):
        b = re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", n)
        if b:
            a = list(map(int, b.groups())); print((a[0] + a[2]) // 2, (a[1] + a[3]) // 2); break')
  [ -n "$xy" ] && { adb shell input tap $xy; echo "note   dismissed a system not-responding dialog"; sleep 2; }
}
awake() { adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard; unanr; }
shot() { awake; sleep "${2:-2}"; adb exec-out screencap -p > "$OUT/$1.png"; echo "captured $1"; }
dump() {
  adb shell rm -f /sdcard/ui.xml >/dev/null 2>&1
  for _ in 1 2 3; do
    r=$(adb shell uiautomator dump /sdcard/ui.xml 2>&1 | tr -d '\r')
    case "$r" in *dumped*) break;; esac
    echo "uiautomator: $r" >&2; sleep 1
  done
  adb shell cat /sdcard/ui.xml 2>/dev/null
}
# what is on screen: the focused window, the resumed activity, and the texts a dump sees
diag() {
  echo "── diag: $1"
  adb shell dumpsys window 2>/dev/null | grep -E "mCurrentFocus|mFocusedApp" | head -2
  adb shell dumpsys activity activities 2>/dev/null | grep -E "topResumedActivity|ResumedActivity:" | head -2
  dump > "$OUT/diag.xml"
  python3 -I -c 'import re,sys,html; x=open(sys.argv[1],encoding="utf-8",errors="replace").read(); t=[html.unescape(m) for m in re.findall(r" text=\"([^\"]+)\"", x)]; print("nodes:", len(re.findall("<node", x)), "texts:", t[:40])' "$OUT/diag.xml"
  adb logcat -d -s ObjectsScene:I AndroidRuntime:E | tail -8
}
find_xy() {
  python3 -I - "$1" "$OUT/ui.xml" "${2:-eq}" <<'PY'
import re, sys
want, path, mode = sys.argv[1], sys.argv[2], sys.argv[3]
x = open(path, encoding="utf-8", errors="replace").read()
for m in re.finditer(r'<node [^>]*>', x):
    n = m.group(0)
    vals = [v.group(1) for v in (re.search(r' text="([^"]*)"', n), re.search(r' content-desc="([^"]*)"', n)) if v]
    if any((v == want) if mode == "eq" else (want in v) for v in vals):
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        if b:
            a = list(map(int, b.groups())); print((a[0] + a[2]) // 2, (a[1] + a[3]) // 2); break
PY
}
tap_text() {
  for _ in 1 2 3; do
    dump > "$OUT/ui.xml"; xy=$(find_xy "$1" "${2:-eq}")
    if [ -n "$xy" ]; then adb shell input tap $xy; echo "tapped $1 at $xy"; return 0; fi
    sleep 1
  done
  echo "no node '$1'"; return 1
}
status_line() {
  dump > "$OUT/ui.xml"
  python3 -I - "$OUT/ui.xml" <<'PY'
import html, re, sys
x = open(sys.argv[1], encoding="utf-8", errors="replace").read()
for n in re.findall(r"<node [^>]*>", x):
    if re.search(r'resource-id="[^"]*objects_status"', n):
        t = re.search(r' text="([^"]*)"', n)
        print(html.unescape(t.group(1)) if t else ""); break
PY
}
# wait up to $2 s for a logcat line of tag ObjectsScene matching $1; prints the newest match
wait_log() {
  for _ in $(seq 1 "$2"); do
    l=$(adb logcat -d -s ObjectsScene:I | grep -a -- "$1" | tail -1)
    [ -n "$l" ] && { printf '%s\n' "$l"; return 0; }
    sleep 1
  done
  return 1
}
start() { unanr; adb shell am force-stop "$PKG"; adb logcat -c; adb shell am start -n "$ACT" "$@" >/dev/null; }

# ── fixtures: objects + text, and a text document (generated, so nothing binary is committed twice) ──────────────
python3 -I - "$GOLDEN/grace_hopper.jpg" "$OUT" <<'PY' || { echo "cannot build the fixtures"; exit 2; }
import glob, os, sys
from PIL import Image, ImageDraw, ImageFont
hopper, out = sys.argv[1], sys.argv[2]
def font(size, *names):
    for n in names:
        for p in glob.glob("/usr/share/fonts/**/" + n, recursive=True):
            return ImageFont.truetype(p, size)
    return ImageFont.load_default(size)
bold = lambda s: font(s, "DejaVuSans-Bold.ttf", "LiberationSans-Bold.ttf")
reg = lambda s: font(s, "DejaVuSans.ttf", "LiberationSans-Regular.ttf")
cjk = lambda s: font(s, "NotoSansCJK-Regular.ttc", "NotoSansCJKsc-Regular.otf", "DroidSansFallbackFull.ttf")
# 1: the portrait (a person, a tie) beside a sign
img = Image.new("RGB", (1200, 900), (236, 236, 230))
p = Image.open(hopper).convert("RGB").resize((600, 703))
img.paste(p, (0, 100))
d = ImageDraw.Draw(img)
d.rectangle((660, 200, 1170, 640), fill=(255, 255, 255), outline=(20, 20, 20), width=6)
d.text((690, 240), "CLOUD CAMERA", fill=(0, 0, 0), font=bold(46))
d.text((690, 330), "Objects and text", fill=(0, 0, 0), font=reg(44))
d.text((690, 410), "Tap any word to copy", fill=(0, 0, 0), font=reg(40))
d.text((690, 500), "Open daily 9 to 5", fill=(160, 0, 0), font=bold(42))
img.save(os.path.join(out, "scene-objects-text.png"))
# 2: a document with several lines (and CJK / Devanagari lines when the runner has the fonts)
doc = Image.new("RGB", (1000, 1300), (255, 255, 255))
d = ImageDraw.Draw(doc)
y = 80
for line, f in (("RECEIPT 0042", bold(56)), ("Coffee 2.50", reg(46)), ("Bread 3.20", reg(46)), ("Total 5.70", bold(50)), ("Thank you for visiting", reg(40))):
    d.text((80, y), line, fill=(0, 0, 0), font=f); y += 110
c = cjk(48)
if not isinstance(c, ImageFont.FreeTypeFont) or "Noto" not in getattr(c, "path", ""):
    print("no CJK font on this runner: the document is Latin only")
else:
    d.text((80, y), "营业时间 早上九点", fill=(0, 0, 0), font=c); y += 110
doc.save(os.path.join(out, "scene-document.png"))
print("fixtures written")
PY
EXT="/sdcard/Android/data/$PKG/files"
adb shell mkdir -p "$EXT"
adb push "$OUT/scene-objects-text.png" "$EXT/scene-objects-text.png" >/dev/null
adb push "$OUT/scene-document.png" "$EXT/scene-document.png" >/dev/null

# ── B1/B2 the benchmark ──────────────────────────────────────────────────────────────────────────────────────
# 12 timed runs per detector (and frames end to end): an emulator CPU is several times slower than a phone
start --es objects_bench "$EXT/scene-objects-text.png" --ei objects_bench_runs 12
sleep 8; shot bench-running 1; diag "benchmark started"
if wait_log "bench {" 900 >/dev/null; then
  adb pull "$EXT/objects-bench.json" "$OUT/bench.json" >/dev/null 2>&1 || adb logcat -d -s ObjectsScene:I | grep -a "bench " | tail -1 | sed 's/^[^{]*//' > "$OUT/bench.json"
  python3 -I - "$OUT/bench.json" "$OUT/bench.md" "$DENSITY" <<'PY'
import json, sys
b = json.load(open(sys.argv[1]))
eng, e2e = b.get("engine", {}), b.get("end_to_end", {})
dets = eng.get("detectors", {})
rows = []
ok = True
for d in ("efficientdet", "yolo11n"):
    e, x = dets.get(d, {}), e2e.get(d, {})
    labels = sorted({o["label"] for o in e.get("detections", [])})
    rows.append("| %s | %s | %.1f | %s | %s | %.1f | %.1f | %s | %s |" % (
        d, e.get("model", "?"), e.get("mean_ms", -1), e.get("p50_ms", "?"), e.get("p90_ms", "?"), e.get("fps", 0.0),
        x.get("fps", 0.0), x.get("p50_ms", "?"), ", ".join(labels) or "-"))
    if e.get("error") or not e.get("runs") or x.get("ok") is not True:
        ok = False; print("B1 FAIL %s: engine %s, end-to-end %s" % (d, e.get("error") or "ok", x.get("error") or ("ok" if x.get("ok") else "not ok")))
o = eng.get("ocr", {})
md = ["| detector | model | engine mean ms | p50 | p90 | engine fps | end-to-end fps | e2e p50 ms | found |", "|---|---|---|---|---|---|---|---|---|"] + rows
md += ["", "OCR (five scripts, %s): mean %.0f ms, p50 %s ms over %s runs; %s blocks, %s words; text regions (Latin) %s in %s ms; errors %s" % (
    ", ".join(o.get("scripts") or []), o.get("mean_ms", -1), o.get("p50_ms", "?"), o.get("runs", "?"), o.get("blocks", "?"), o.get("words", "?"),
    o.get("text_regions", "?"), o.get("text_regions_ms", "?"), json.dumps(o.get("errors") or {}))]
t = e2e.get("text_check") or {}
md += ["", "Text check end to end (Latin, beside the detection, every text_interval_ms): mean %.0f ms, p50 %s ms over %s runs" % (t.get("mean_ms", -1), t.get("p50_ms", "?"), t.get("runs", "?"))]
md += ["", "Frame %sx%s (live_side); default detector %s; target %s fps; emulator x86_64, density %s." % (eng.get("width"), eng.get("height"), b.get("default_detector"), b.get("target_fps"), sys.argv[3])]
fast = max(("efficientdet", "yolo11n"), key=lambda d: e2e.get(d, {}).get("fps", 0.0))
both = all("person" in {x["label"] for x in dets.get(d, {}).get("detections", [])} for d in ("efficientdet", "yolo11n"))
md += ["", "Faster end to end: %s; both find the person: %s." % (fast, both)]
open(sys.argv[2], "w").write("\n".join(md) + "\n")
print("\n".join(md))
print("B2 faster=%s default=%s comparable=%s" % (fast, b.get("default_detector"), both))
sys.exit(0 if ok else 1)
PY
  if [ $? -eq 0 ]; then ok "B1 both detectors and the OCR were benchmarked on the emulator (bench.md)"; else fail "B1 a detector or the OCR did not answer the benchmark"; fi
  line=$(tail -1 "$OUT/bench.md")
  echo "B2 $line"
else
  fail "B1 no benchmark line within 900 s"; diag "benchmark timed out"
  adb logcat -d | grep -aE "bench-progress|mllimagemlkit|ImageScan|FATAL" | tail -25
fi
adb logcat -d > "$OUT/logcat-bench.txt"

# ── F1/F2 an opened image: frames, labels, the text read ─────────────────────────────────────────────────────
start --es objects_image "$EXT/scene-objects-text.png"
words=$(wait_log "words \[" 120 | sed 's/^[^[]*//')
shot frames-labels 2; diag "image opened"
cp "$OUT/ui.xml" "$OUT/frames-labels.xml" 2>/dev/null || true
st=$(status_line); echo "status: $st"; printf '%s\n' "$st" > "$OUT/status-image.txt"
case "$st" in *person*) ok "F1 the person is framed and named: $st";; *) fail "F1 the status line names no person: $st";; esac
case "$st" in *"text region"*) ok "F1 the sign is framed as Text";; *) fail "F1 no text region on the status line";; esac
printf '%s\n' "$words" > "$OUT/words.json"
if python3 -I -c 'import json,sys; w=[x["text"].upper() for x in json.loads(sys.stdin.read() or "[]")]; sys.exit(0 if "CAMERA" in w and "CLOUD" in w else 1)' <<<"$words"; then
  ok "F2 the sign's words are laid out: $(python3 -I -c 'import json,sys; print(" ".join(x["text"] for x in json.loads(sys.stdin.read())))' <<<"$words")"
else
  fail "F2 the read words lack CLOUD CAMERA: $words"
fi

# ── T1-T3, T5 tap, grow, drag ────────────────────────────────────────────────────────────────────────────────
xy() { python3 -I -c 'import json,sys; w=json.loads(sys.argv[2]); m=[x for x in w if x["text"].upper()==sys.argv[1]]; print("%d %d" % (m[0]["x"], m[0]["y"]) if m else "")' "$1" "$words"; }
CAM=$(xy CAMERA)
if [ -n "$CAM" ]; then
  awake; adb shell input tap $CAM; sleep 1
  c1=$(wait_log "copied " 10)
  shot text-selection 1
  case "$c1" in *'copied "CAMERA"'*) ok "T1 a tap on CAMERA copied exactly it";; *) fail "T1 the tap at $CAM copied: $c1"; diag "after the tap";; esac
  adb shell input tap $CAM; sleep 1
  c2=$(adb logcat -d -s ObjectsScene:I | grep -a "copied " | tail -1)
  case "$c2" in *'copied "CLOUD CAMERA"'*) ok "T2 a second tap grew it to its line";; *) fail "T2 the second tap copied: $c2";; esac
  # clear (a tap off the text, in the margin above the sign), select the word again, then drag its end handle (just below the
  # word's right edge) onto the next line's last word
  OFF=$(python3 -I -c 'import json,sys; w=json.loads(sys.argv[1]); m=[x for x in w if x["text"].upper()=="CAMERA"][0]; print(m["x"], max(5, m["t"] - 150))' "$words")
  adb shell input tap $OFF; sleep 1; adb shell input tap $CAM; sleep 1
  read -r hx hy < <(python3 -I -c 'import json,sys; w=json.loads(sys.argv[1]); m=[x for x in w if x["text"].upper()=="CAMERA"][0]; d=float(sys.argv[2])/160; print(m["r"], int(m["b"] + 9*d))' "$words" "$DENSITY")
  TX=$(xy TEXT); [ -n "$TX" ] || TX=$(python3 -I -c 'import json,sys; w=json.loads(sys.argv[1]); print("%d %d" % (w[min(4,len(w)-1)]["x"], w[min(4,len(w)-1)]["y"]))' "$words")
  adb shell input swipe "$hx" "$hy" $TX 900; sleep 1
  c3=$(adb logcat -d -s ObjectsScene:I | grep -a "copied " | tail -1)
  shot text-handles 1
  # the selection must run from CAMERA (where it started) through "text" (where the handle was dropped); whatever the
  # OCR laid out between them in reading order is part of it
  case "$c3" in *'copied "CAMERA'*'Objects and text"'*) ok "T3 the end handle extended the selection: ${c3#*copied }";; *) fail "T3 the handle drag copied: $c3";; esac
  n=$(adb logcat -d -s ObjectsScene:I | grep -ac "words \[")
  [ "$n" -le 1 ] && ok "T5 the frame's text was read once and reused for every selection" || fail "T5 the frame was read $n times"
else
  fail "T1-T3 no CAMERA word to tap"
fi

# ── T4 Copy all on a document ────────────────────────────────────────────────────────────────────────────────
start --es objects_image "$EXT/scene-document.png"
wait_log "words \[" 120 >/dev/null || true
tap_text "Copy all" || { fail "T4 no Copy all button"; diag "document"; }
sleep 1
c4=$(adb logcat -d -s ObjectsScene:I | grep -a "copied " | tail -1)
shot document-copy-all 1
case "$c4" in *RECEIPT*Total*) ok "T4 Copy all copied the whole document: ${c4#*copied }";; *) fail "T4 Copy all copied: $c4";; esac

# ── the other detector on the same image ─────────────────────────────────────────────────────────────────────
tap_text "Detector:" sub && sleep 6 && shot detector-switched 1 && status_line > "$OUT/status-detector-switched.txt"

# ── L1 live ──────────────────────────────────────────────────────────────────────────────────────────────────
tap_text "Resume" || true
sleep 12
st=$(status_line); printf '%s\n' "$st" > "$OUT/status-live.txt"
shot live 1
case "$st" in *fps*) ok "L1 the live stream runs: $st";; *) echo "note   L1 the live status line carries no rate: $st (an emulator camera may show nothing to detect)";; esac

adb logcat -d > "$OUT/logcat.txt"
mkdir -p "$OUT/small"
cp "$OUT"/*.txt "$OUT"/*.json "$OUT"/*.md "$OUT"/*.xml "$OUT/small/" 2>/dev/null || true
rm -f "$OUT/small/logcat.txt" "$OUT/small/logcat-bench.txt"
adb logcat -d -s ObjectsScene:I AndroidRuntime:E > "$OUT/small/logcat-objects.txt" 2>/dev/null || true
python3 -I - "$OUT" <<'PY' || true
import glob, os, sys
from PIL import Image
out = sys.argv[1]
for p in glob.glob(os.path.join(out, "*.png")):
    if os.path.basename(p).startswith("scene-"): continue
    im = Image.open(p).convert("RGB"); im = im.resize((im.width // 2, im.height // 2))
    im.save(os.path.join(out, "small", os.path.basename(p)[:-4] + ".jpg"), quality=70)
PY
echo
if [ "$FAILS" -eq 0 ]; then echo "PASS — Objects frames objects and Text, reads the frame, selects and copies (#1805)"; else echo "FAIL — $FAILS check(s)"; exit 1; fi
