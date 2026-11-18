#!/usr/bin/env bash
# emulator-canvas-enhance-engine.sh — #1799 PROOF that the Draw Enhancer runs THROUGH the fleet's decisions engine.
#
# Installs the published Cloud-Lib-Decisions-Engine.apk (contract 2: the image method) and Cloud Canvas (both signed with
# the Cloud key, so Canvas holds the engine's signature permission), seeds the preset dataset, opens the Enhancer, takes
# the drawing, picks the first preset and taps Enhance with NO own key, then requires:
#   E1  the engine served the run: its fleet log line `image com.diegonmarcos.cloudcanvas canvas_enhance ...` is in logcat
#       (the engine answers in its own process; an emulator has no fleet Account, so the answer is ✗ no_key);
#   E2  Canvas surfaced the engine's answer as the Enhancer always did: the no-key sentence is on screen;
#   E3  Configs > AI reads the key line from the engine (no own key, the fleet key is not readable here);
#   E4  Canvas never asked text-tools for the key: no ITextTools bind from Canvas in logcat.
# Evidence (screens, ui dumps, the engine's log lines) lands in <outdir>. A real picture needs the fleet Account's key:
# that is the on-phone check.
#
# usage: emulator-canvas-enhance-engine.sh <engine.apk> <canvas.apk> <enhance-presets.json> <outdir>
set -u
ENGINE_APK="$1"; APK="$2"; DATA="$3"; OUT="$4"
mkdir -p "$OUT"
aapt2=$(ls -d "$ANDROID_HOME"/build-tools/*/aapt2 | sort -V | tail -1)
PKG=$("$aapt2" dump packagename "$APK")
EPKG=$("$aapt2" dump packagename "$ENGINE_APK")
adb install -r -g "$ENGINE_APK" || exit 2
adb install -r -g "$APK" || exit 2
ACT=$(adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "$PKG" | tr -d '\r' | tail -1)
echo "canvas=$PKG ($ACT) engine=$EPKG"
adb shell settings put global hide_error_dialogs 1 || true
adb shell settings put global window_animation_scale 0; adb shell settings put global transition_animation_scale 0
adb shell settings put system screen_off_timeout 2147483647; adb shell svc power stayon true
adb shell locksettings set-disabled true || true

awake() { adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard; adb shell input keyevent 82; }
shot() { awake; sleep "${2:-3}"; adb exec-out screencap -p > "$OUT/$1.png"; echo "captured $1"; }
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml 2>/dev/null; }
# the centre of the first node whose text or content-desc equals (or, with a second arg "sub", contains) $1
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
sz=$(adb shell wm size | tr -d '\r' | sed -n 's/.*: \([0-9]*\)x\([0-9]*\)$/\1 \2/p' | tail -1); W=${sz% *}; H=${sz#* }
# tap $1, scrolling the page up to five times to reach it
tap_text() {
  for _ in 1 2 3 4 5 6; do
    dump > "$OUT/ui.xml"
    xy=$(find_xy "$1")
    if [ -n "$xy" ]; then adb shell input tap $xy; echo "tapped $1 at $xy"; return 0; fi
    adb shell input swipe $((W/2)) $((H*3/4)) $((W/2)) $((H/3)) 400; sleep 1
  done
  echo "no node '$1'"; return 1
}
# wait up to $2 seconds for a node containing $1
wait_text() {
  for _ in $(seq 1 "$2"); do
    dump > "$OUT/ui.xml"
    [ -n "$(find_xy "$1" sub)" ] && return 0
    sleep 1
  done
  return 1
}
start() { adb shell am force-stop "$PKG"; adb shell am start -n "$ACT" "$@" >/dev/null; sleep 6; }

FAILS=0
ok()   { echo "ok     $1"; }
fail() { echo "FAIL   $1"; FAILS=$((FAILS + 1)); }

# the Enhancer's dataset, as the screenshot job seeds it (Cloud Store serves it on a real phone)
KEY="app__${PKG}__enhance-presets"
adb push "$DATA" /data/local/tmp/presets-seed.json >/dev/null
adb shell "run-as $PKG mkdir -p files/fleetdata-lkg; cat /data/local/tmp/presets-seed.json | run-as $PKG sh -c 'cat > files/fleetdata-lkg/$KEY.json'" 2>&1 | tee "$OUT/seed.txt"
PRESET=$(python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1]))["presets"][0]["label"])' "$DATA")
echo "preset=$PRESET"

adb logcat -c
start --es section enhance
shot enhancer-open
tap_text "Use drawing from Draw" || fail "the Enhancer offers the drawing as input"
sleep 1
tap_text "$PRESET" || fail "the first preset ($PRESET) is offered"
sleep 1
shot enhancer-ready
tap_text "Enhance" || fail "the Enhance button is there"
NO_KEY="No OpenRouter key. Add one in Configs > AI, or sign in to the fleet Account."
if wait_text "No OpenRouter key" 90; then ok "E2 the Enhancer shows the engine's no_key answer: $NO_KEY"; else fail "E2 the no-key sentence did not appear within 90 s"; fi
shot enhancer-answer 1
cp "$OUT/ui.xml" "$OUT/enhancer-answer.xml"
adb logcat -d -v brief > "$OUT/logcat.txt"
grep -a 'DecisionsEngine' "$OUT/logcat.txt" > "$OUT/engine-log.txt" || true
if grep -aq "image $PKG canvas_enhance" "$OUT/engine-log.txt"; then
  ok "E1 the engine served the run: $(grep -a "image $PKG canvas_enhance" "$OUT/engine-log.txt" | tail -1 | sed 's/^[^:]*: //')"
else
  fail "E1 no engine log line for $PKG canvas_enhance (the run did not reach the engine)"
fi
if grep -aiE "texttools|ITextTools" "$OUT/logcat.txt" | grep -aq "$PKG"; then fail "E4 Canvas bound text-tools"; else ok "E4 Canvas did not bind text-tools"; fi

start --es configs ai
if wait_text "No key yet (" 20 || wait_text "Using the fleet Account" 5; then
  ok "E3 Configs > AI reads the key line from the engine: $(python3 -I -c 'import re,sys; x=open(sys.argv[1]).read(); m=re.search(r"text=\"((No key yet|Using the fleet)[^\"]*)\"", x); print(m.group(1) if m else "?")' "$OUT/ui.xml")"
else
  fail "E3 Configs > AI shows no key line"
fi
shot ai-configs 1
cp "$OUT/ui.xml" "$OUT/ai-configs.xml"

echo
if [ "$FAILS" -eq 0 ]; then echo "PASS — the Draw Enhancer runs through the decisions engine (#1799)"; else echo "FAIL — $FAILS check(s)"; exit 1; fi
