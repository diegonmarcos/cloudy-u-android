#!/usr/bin/env bash
# #1946 Cloud Store Phone on an emulator where the fleet install-sources dataset is not served (no private repo token on the runner):
# Installed and Declared both list the phone's launchable apps, the failure line is absent, the status never says "All up to date".
# Usage: emulator-store-phone-unserved.sh STORE_APK OUT_DIR
set -u
APK="$1"; OUT="$2"; PKG=com.diegonmarcos.cloudstore
mkdir -p "$OUT"
adb install -r -g "$APK" >"$OUT/install.txt" 2>&1 || { cat "$OUT/install.txt"; exit 1; }
ACT=$(adb shell cmd package resolve-activity --brief "$PKG" | tr -d '\r' | tail -1)
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml "$OUT/$1.xml" >/dev/null 2>&1; }
shoot() { adb exec-out screencap -p > "$OUT/$1.png"; }
tap_text() {
  dump cur
  b=$(grep -oE "(text|content-desc)=\"$1\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" "$OUT/cur.xml" | sed -n 1p | grep -oE '[0-9]+' | tail -4 | tr '\n' ' ')
  [ -n "$b" ] || return 1
  set -- $b; adb shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 ))
}
adb shell am start -W -n "$ACT" --es tab phone >/dev/null; sleep 10
for i in 1 2 3 4 5 6; do   # first-run system dialogs
  if tap_text Wait || tap_text Cancel; then sleep 3
  elif ! grep -qE '(text|content-desc)="(Installed|Declared)"' "$OUT/cur.xml" 2>/dev/null; then adb shell input keyevent KEYCODE_BACK; sleep 3
  else break; fi
done
rc=0
check() {  # name
  sleep 6; dump "$1"; shoot "$1"
  if grep -q 'Could not list apps' "$OUT/$1.xml"; then echo "::error::$1: the list failed (Could not list apps)"; rc=1; fi
  if grep -q 'All up to date' "$OUT/$1.xml"; then echo "::error::$1: the status says All up to date with no check behind it"; rc=1; fi
  if grep -qE 'text="Installed 0 / 0' "$OUT/$1.xml"; then echo "::error::$1: lists no app"; rc=1; fi
}
tap_text Installed || true
check store-1946-installed
tap_text Declared || { echo "::error::no Declared tab"; rc=1; }
check store-1946-declared
read -r W H < <(adb shell wm size | tr -d '\r' | grep -oE '[0-9]+x[0-9]+' | tail -1 | tr x ' ')
adb shell input swipe $((W/2)) $((H*3/5)) $((W/2)) $((H*3/10)) 250; sleep 2; shoot store-1946-declared-scrolled
tap_text Installed || true; sleep 3
adb shell input swipe $((W/2)) $((H*3/5)) $((W/2)) $((H*3/10)) 250; sleep 2; shoot store-1946-installed-scrolled
adb logcat -d -t 400 > "$OUT/logcat.txt" 2>/dev/null || true
exit $rc
