#!/bin/bash
# Emulator walk (#1423): Calc > Time & Date > World Clock. Shots: the world map with the declared cities pinned, the map after a
# tap on Paris (Paris added to the list, pinned), the same tap again (removed), and the city list searched for "par".
# Usage: emulator-worldclock.sh <calc.apk> <outdir>
set -u
APK=${1:?calc apk}; OUT=${2:-out}
PKG=com.diegonmarcos.cloudcalc
mkdir -p "$OUT/shots"
fail() { echo "::error title=Calc World clock::$*"; adb exec-out screencap -p > "$OUT/shots/fail.png" 2>/dev/null; adb logcat -d -v threadtime > "$OUT/logcat.txt" 2>/dev/null; exit 1; }
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
# tap the map at a longitude/latitude (equirectangular, as WorldClockData.x/y)
tapmap() { box '^World map$' | { read -r a b c d && python3 -I -c 'import sys;a,b,c,d,lon,lat=map(float,sys.argv[1:]);print(int(a+(lon+180)/360*(c-a)),int(b+(90-lat)/180*(d-b)))' $a $b $c $d "$1" "$2"; } | { read -r x y && adb shell input tap $x $y; }; }

adb install -r -g "$APK" || fail "install failed"
act=$(adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "$PKG" | tr -d '\r' | tail -1)
for i in 1 2 3; do
  adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard >/dev/null 2>&1 || true
  timeout 120 adb shell am start -W -n "$act" >/dev/null; sleep 8
  dump | grep -q "package=\"$PKG\"" && break
done
dump | grep -q "package=\"$PKG\"" || fail "Calc never came to the front"
shot 00-start
# Calc > Others row > Time & Date > World Clock (#1409 two-row nav); a Clock tab, where one is declared, also leads there.
tap '^Calc$' || true; sleep 3
if tap '^Time (&|&amp;) Date$'; then sleep 3; tap '^World [Cc]lock$' || fail "no World Clock in Time & Date: $(texts)"
else tap '^Clock$' && sleep 3 && { tap '^World clock$' || true; } || fail "no Time & Date page nor Clock tab: $(texts)"; fi
sleep 3
box '^World map$' >/dev/null || fail "no world map: $(texts)"
shot 01-map-pins
dump >/dev/null; grep -q 'text="Paris"' "$OUT/ui.xml" && fail "Paris already listed before the tap"
tapmap 2.35 48.85; sleep 3; shot 02-map-paris-added
grep -q 'text="Paris"' "$OUT/ui.xml" || fail "a tap on Paris did not add it: $(texts)"
tapmap 2.35 48.85; sleep 3; shot 03-map-paris-removed
grep -q 'text="Paris"' "$OUT/ui.xml" && fail "a second tap on Paris did not remove it"
tap '^Add city$' || { adb shell input swipe 500 1500 500 300 300; sleep 1; tap '^Add city$'; } || fail "no Add city button: $(texts)"
sleep 2; shot 04-city-list
grep -q 'Washington' "$OUT/ui.xml" || grep -q '★' "$OUT/ui.xml" || fail "the city list shows no capital: $(texts)"
adb shell input text par; sleep 2
adb shell dumpsys input_method | grep -q 'mInputShown=true' && { adb shell input keyevent 4; sleep 1; }
shot 05-city-list-par
grep -q 'Paris' "$OUT/ui.xml" || fail "searching par does not find Paris: $(texts)"
adb logcat -d -v threadtime > "$OUT/logcat.txt" 2>/dev/null
echo "World clock walk: map, pins, tap add/remove and city search all shown"
