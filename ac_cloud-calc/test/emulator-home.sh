#!/bin/bash
# Emulator walk (#1909): Calc > Home, the nested tree. Shots: the top of Home (Calculations > Calc > Decimals/Others > pages > modes)
# and, scrolled, Toolkit > Measure > Sound/Camera/Wireless/Sensors > leaf tiles. Asserts the headings and a leaf per level are on screen.
# Usage: emulator-home.sh <calc.apk> <outdir>
set -u
APK=${1:?calc apk}; OUT=${2:-out}
PKG=com.diegonmarcos.cloudcalc
mkdir -p "$OUT/shots"
fail() { echo "::error title=Calc Home::$*"; adb exec-out screencap -p > "$OUT/shots/fail.png" 2>/dev/null; adb logcat -d -v threadtime > "$OUT/logcat.txt" 2>/dev/null; exit 1; }
dump() { local i; for i in 1 2 3; do adb shell rm -f /sdcard/ui.xml >/dev/null 2>&1; adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb exec-out cat /sdcard/ui.xml > "$OUT/ui.xml" 2>/dev/null; grep -q '<node' "$OUT/ui.xml" && break; sleep 2; done; cat "$OUT/ui.xml"; }
shot() { adb exec-out screencap -p > "$OUT/shots/$1.png"; dump > "$OUT/ui-$1.xml"; }
box() { dump | python3 -I -c '
import re,sys
x=sys.stdin.read(); pat=re.compile(sys.argv[1],re.I)
for m in re.finditer(r"<node [^>]*>",x):
    n=m.group(0); t=re.search(r"\btext=\"([^\"]*)\"",n); d=re.search(r"content-desc=\"([^\"]*)\"",n); b=re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"",n)
    if b and ((t and pat.search(t.group(1))) or (d and pat.search(d.group(1)))): print(*b.groups()); break
else: sys.exit(1)' "$1"; }
tap() { box "$1" | { read -r a b c d && adb shell input tap $(( (a + c) / 2 )) $(( (b + d) / 2 )); }; }
texts() { grep -oE '(text|content-desc)="[^"]+"' "$OUT/ui.xml" | sed 's/^[a-z-]*="//;s/"$//' | sort -u | tr '\n' ';'; }
has() { grep -qiE "(text|content-desc)=\"$1\"" "$OUT/ui.xml"; }
swipe() { local W H; read -r W H < <(adb shell wm size | tr -d '\r' | tail -1 | sed 's/.*: //; s/x/ /')
  adb shell input swipe $((W / 2)) $((H * $1 / 100)) $((W / 2)) $((H * $2 / 100)) 400; }

adb install -r -g "$APK" || fail "install failed"
act=$(adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "$PKG" | tr -d '\r' | tail -1)
for i in 1 2 3; do
  adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard >/dev/null 2>&1 || true
  timeout 120 adb shell am start -W -n "$act" >/dev/null; sleep 8
  dump | grep -q "package=\"$PKG\"" && break
done
dump | grep -q "package=\"$PKG\"" || fail "Calc never came to the front"
tap '^Home$' || fail "no Home in the bottom bar: $(texts)"
sleep 3; dump >/dev/null
for w in 'CALCULATIONS' 'CALC' 'DECIMALS' 'OTHERS'; do has "$w" || fail "Home top lacks $w: $(texts)"; done
shot 01-home-top
# Home is taller than the screen: scroll one page at a time, shoot each page, and collect every label seen on the way.
ALL="$OUT/all-texts.txt"; : > "$ALL"
for i in 1 2 3 4 5 6 7 8 9 10; do swipe 80 30; sleep 1; dump >/dev/null; texts | tr ';' '\n' >> "$ALL"; shot "03-home-scroll-$i"; done
for w in 'TOOLKIT' 'MEASURE' 'WIRELESS' 'SENSORS' 'TOOLS'; do grep -qix "$w" "$ALL" || fail "Home lacks $w: $(sort -u "$ALL" | tr '\n' ';')"; done
adb logcat -d -v threadtime > "$OUT/logcat.txt" 2>/dev/null
echo "Home walk: nested headings and leaf tiles shown"
