#!/usr/bin/env bash
# #1441 Cloud Store on an emulator: the Cloud page's Libs and Lite-ML tabs, each scrolled to its
# end so the build-time lib table (Shared libs on Libs, Machine-learning libs on Lite-ML) is in shot.
# Usage: emulator-store-tabs.sh STORE_APK OUT_DIR
set -u
APK="$1"; OUT="$2"; PKG=com.diegonmarcos.cloudstore
mkdir -p "$OUT"
adb install -r -g "$APK" >"$OUT/install.txt" 2>&1 || { cat "$OUT/install.txt"; exit 1; }
ACT=$(adb shell cmd package resolve-activity --brief "$PKG" | tr -d '\r' | tail -1)
adb shell am start -W -n "$ACT" >/dev/null; sleep 10
# tap the node whose text is $1 (from a uiautomator dump); 0 when found
tap_text() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1
  b=$(grep -oE "text=\"$1\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" "$OUT/ui.xml" | sed -n 1p | grep -oE '[0-9]+' | tail -4 | tr '\n' ' ')
  [ -n "$b" ] || return 1
  set -- $b; adb shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 ))
}
shoot() { adb exec-out screencap -p > "$OUT/$1.png"; }
# the Store asks for wireless debugging at first run: a system dialog over the tabs; refuse it
for i in 1 2 3; do tap_text Cancel && sleep 2 || break; done
read -r W H < <(adb shell wm size | tr -d '\r' | grep -oE '[0-9]+x[0-9]+' | tail -1 | tr x ' ')
X=$((W/2)); Y1=$((H*4/5)); Y2=$((H/4))
shoot start
rc=0
for tab in Libs Lite-ML; do
  if tap_text "$tab"; then
    sleep 4; shoot "$tab-top"
    for i in 1 2 3 4 5 6 7 8; do adb shell input swipe $X $Y1 $X $Y2 250; sleep 0.6; done
    sleep 2; shoot "$tab-end"
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml "$OUT/$tab-end.xml" >/dev/null 2>&1
    grep -oE 'text="[^"]*libs[^"]*"' "$OUT/$tab-end.xml" | sed "s/^/$tab: /"
    for i in 1 2 3 4 5 6 7 8 9; do adb shell input swipe $X $Y2 $X $Y1 100; done
  else
    echo "::error::no $tab tab on screen"; rc=1
  fi
done
adb logcat -d -t 400 > "$OUT/logcat.txt" 2>/dev/null || true
exit $rc
