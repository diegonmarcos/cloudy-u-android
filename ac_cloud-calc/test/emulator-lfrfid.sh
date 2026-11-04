#!/bin/bash
# Emulator walk (#1407): Calc > Measure > LF-RFID-Tags. The emulator has no USB reader and nothing paired, so each page must say
# which hardware it needs. Shots: Read (no-reader help), Editor (T5577 blocks + the confirm dialog of a block write), Library.
# Usage: emulator-lfrfid.sh <calc.apk> <outdir>
set -u
APK=${1:?calc apk}; OUT=${2:-out}
PKG=com.diegonmarcos.cloudcalc
mkdir -p "$OUT/shots"
fail() { echo "::error title=Calc LF-RFID::$*"; adb exec-out screencap -p > "$OUT/shots/fail.png" 2>/dev/null; adb logcat -d -v threadtime > "$OUT/logcat.txt" 2>/dev/null; exit 1; }
dump() { local i; for i in 1 2 3; do adb shell rm -f /sdcard/ui.xml >/dev/null 2>&1; adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb exec-out cat /sdcard/ui.xml > "$OUT/ui.xml" 2>/dev/null; grep -q '<node' "$OUT/ui.xml" && break; sleep 2; done; cat "$OUT/ui.xml"; }
shot() { adb exec-out screencap -p > "$OUT/shots/$1.png"; dump > "$OUT/ui-$1.xml"; }
# bounds "x1 y1 x2 y2" of the first node whose text or content-desc matches the regex ($1)
box() { dump | python3 -I -c '
import re,sys
x=sys.stdin.read(); pat=re.compile(sys.argv[1],re.I)
for m in re.finditer(r"<node [^>]*>",x):
    n=m.group(0); t=re.search(r"\btext=\"([^\"]*)\"",n); d=re.search(r"content-desc=\"([^\"]*)\"",n); b=re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"",n)
    if b and ((t and pat.search(t.group(1))) or (d and pat.search(d.group(1)))): print(*b.groups()); break
else: sys.exit(1)' "$1"; }
tap() { box "$1" | { read -r a b c d && adb shell input tap $(( (a + c) / 2 )) $(( (b + d) / 2 )); }; }
texts() { grep -oE '(text|content-desc)="[^"]+"' "$OUT/ui.xml" | sed 's/^[a-z-]*="//;s/"$//' | sort -u | tr '\n' ';'; }
adb install -r -g "$APK" || fail "install failed"
act=$(adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "$PKG" | tr -d '\r' | tail -1)
for i in 1 2 3; do
  adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard >/dev/null 2>&1 || true
  timeout 120 adb shell am start -W -n "$act" >/dev/null; sleep 8
  dump | grep -q "package=\"$PKG\"" && break
done
dump | grep -q "package=\"$PKG\"" || fail "Calc never came to the front"
shot 00-start
tap '^Measure$' || true; sleep 3
# the tab is the strip's last: swipe that row (found by a visible neighbour) left until it shows; the shot is scaled, the device is not
lftab() { local i xy W; W=$(adb shell wm size | tr -d '\r' | tail -1 | sed 's/.*: //; s/x.*//')
  for i in 1 2 3 4 5; do tap '^LF-RFID-Tags$' && return 0; xy=$(box '^(NFC|Bluetooth|WiFi|Camera|Phone|Sensors)$') || return 1; set -- $xy
    adb shell input swipe $((W * 9 / 10)) $(( ($2 + $4) / 2 )) $((W / 10)) $(( ($2 + $4) / 2 )) 400; sleep 1; done; return 1; }
lftab || fail "no LF-RFID-Tags tab: $(texts)"
sleep 3; tap '^Read$' || true; sleep 2
shot 01-read-no-reader
grep -q 'No reader found' "$OUT/ui.xml" || fail "Read does not explain the hardware: $(texts)"
grep -q 'Proxmark3' "$OUT/ui.xml" || fail "the help names no Proxmark3: $(texts)"
tap '^Editor$' || fail "no Editor mode: $(texts)"
sleep 2; shot 02-editor
grep -q 'T5577 blocks' "$OUT/ui.xml" || fail "the Editor shows no T5577 blocks: $(texts)"
grep -q '00148040' "$OUT/ui.xml" || fail "block 0 is not the EM4100 config: $(texts)"
tap '^Library$' || fail "no Library mode: $(texts)"
sleep 2; shot 03-library
grep -q 'Export JSON' "$OUT/ui.xml" || fail "the Library has no export: $(texts)"
# the empty-library line sits under the no-reader help and the export row: scroll the pane (device pixels) until it shows
for i in 1 2 3; do grep -q 'No saved tags yet' "$OUT/ui.xml" && break; read -r SW SH < <(adb shell wm size | tr -d '\r' | tail -1 | sed 's/.*: //; s/x/ /')
  adb shell input swipe $((SW / 2)) $((SH * 75 / 100)) $((SW / 2)) $((SH * 35 / 100)) 400; sleep 1; dump >/dev/null; done
shot 04-library-empty
grep -q 'No saved tags yet' "$OUT/ui.xml" || fail "an empty library says nothing: $(texts)"
adb logcat -d -v threadtime > "$OUT/logcat.txt" 2>/dev/null
grep -q "FATAL EXCEPTION" "$OUT/logcat.txt" && grep -A3 "FATAL EXCEPTION" "$OUT/logcat.txt" | grep -q cloudcalc && fail "Calc crashed"
echo "LF-RFID walk: Read help, Editor blocks and Library shown"
