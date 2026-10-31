#!/usr/bin/env bash
# Emulator proof of Resources › Battery use per app (BatteryCycles) and of the Shizuku-free battery measurement.
#   bash aa_cloud-superapp/test/emulator-battery-usage.sh <superapp.apk> <out dir>
# Installs the APK, clears the nav-mode override (global cloud_nav_ui_mode), drives one charge cycle with
# `dumpsys battery` (plugged at 90 % -> unplug -> 62 % -> plug in -> unplug), so PowerStateReceiver snapshots
# each edge, then photographs Resources (Cycle and Month) and About › Battery Usage Details. Fails when a cycle
# was not recorded or any screen it shows says "Shizuku". The emulator has no paired adb channel, so the per-app
# rows read "Not paired" and that row opens the ADB Shell page; the per-app mAh needs the channel (phone check).
set -u
APK=$1; OUT=$2; PKG=com.diegonmarcos.superapp
mkdir -p "$OUT"
fail=0
adb wait-for-device
adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard || true
adb install -r -g "$APK" || { echo "::error::install failed"; exit 2; }
adb shell settings delete global cloud_nav_ui_mode || true
adb shell settings put global adb_wifi_enabled 0 || true

dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml > "$OUT/ui.xml" 2>/dev/null; }
# Centre of the first node whose text or content-desc equals $1 (empty when absent).
centre() { python3 - "$OUT/ui.xml" "$1" <<'PY'
import re, sys
xml, want = open(sys.argv[1], encoding="utf-8", errors="ignore").read(), sys.argv[2]
for m in re.finditer(r'<node [^>]*>', xml):
    n = m.group(0)
    t = re.search(r' text="([^"]*)"', n); d = re.search(r' content-desc="([^"]*)"', n)
    if want in ((t.group(1) if t else None), (d.group(1) if d else None)):
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        if b:
            x1, y1, x2, y2 = map(int, b.groups()); print((x1 + x2) // 2, (y1 + y2) // 2); break
PY
}
# Scroll down until $1 is on screen; tap $2 (a collapsed card's heading) first when given and $1 is not found.
find_text() {
  local want=$1 opener=${2:-} i xy
  for i in $(seq 1 14); do
    dump; xy=$(centre "$want"); [ -n "$xy" ] && { echo "$xy"; return 0; }
    if [ -n "$opener" ]; then xy=$(centre "$opener"); [ -n "$xy" ] && { adb shell input tap $xy; sleep 2; opener=""; continue; }; fi
    adb shell input swipe 540 1700 540 900 400; sleep 1.2
  done
  return 1
}
tap_text() { local xy; xy=$(find_text "$@") || return 1; adb shell input tap $xy; sleep 2; }
shot() { adb shell screencap -p /sdcard/s.png; adb pull /sdcard/s.png "$OUT/$1.png" >/dev/null; dump; cp "$OUT/ui.xml" "$OUT/$1.xml"
  if grep -qi shizuku "$OUT/$1.xml"; then echo "::error::$1 shows Shizuku"; fail=1; else echo "$1: no Shizuku text"; fi; }
open_page() { adb shell am start -n "$PKG/.MainActivity" --es shortcut_action "$1" >/dev/null; sleep 7; }

# A launch first: a package that never ran is stopped and its manifest receivers get nothing.
adb shell dumpsys battery set level 90; adb shell dumpsys battery set ac 1
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null; sleep 10
# One cycle: unplug at 90 %, drain to 62 %, plug in (closes it); unplug again (opens the current one).
adb shell dumpsys battery unplug; sleep 6
adb shell dumpsys battery set level 62; sleep 3
adb shell dumpsys battery set ac 1; sleep 6
adb shell dumpsys battery set level 70; sleep 2
adb shell dumpsys battery unplug; sleep 6
adb shell dumpsys battery set level 66; sleep 2

open_page "page:boards/resources"
shot resources-top
if find_text "Battery use per app" "Battery" >/dev/null; then
  adb shell input swipe 540 1500 540 1100 400; sleep 1.5
  shot resources-battery-cycle
  dump; grep -q "90% → 62% · −28%" "$OUT/ui.xml" || { echo "::error::the unplug-to-plug-in cycle (90% → 62%) is not listed"; fail=1; }
  if tap_text "Month"; then shot resources-battery-month; else echo "::error::no Month segment"; fail=1; fi
  # Not paired -> the row opens the ADB Shell page (the one place that pairs). That page is the channel's own and may
  # name Shizuku as a ladder option, so it is checked for the pairing controls instead of the no-Shizuku rule.
  if tap_text "Per-app mAh needs the ADB channel"; then
    sleep 3; adb shell screencap -p /sdcard/s.png; adb pull /sdcard/s.png "$OUT/resources-battery-pair-opens-adb-shell.png" >/dev/null
    dump; cp "$OUT/ui.xml" "$OUT/resources-battery-pair-opens-adb-shell.xml"
    grep -qi "wireless debugging" "$OUT/ui.xml" || { echo "::error::the Not paired row did not open the ADB Shell page"; fail=1; }
    adb shell input keyevent KEYCODE_BACK; sleep 2
  else
    echo "::error::no 'Not paired' row"; fail=1
  fi
else
  echo "::error::Resources has no 'Battery use per app' block"; shot resources-missing; fail=1
fi

# About › Battery & Usage › Battery Usage Details: the exact per-app card is the ADB channel's, not Shizuku's.
open_page "page:config/about"
if tap_text "Battery Usage Details" "Battery & Usage"; then
  find_text "Per-app exact  ·  ADB channel" >/dev/null || { echo "::error::Usage Details has no ADB-channel card"; fail=1; }
  shot about-usage-details
else
  echo "::warning::About › Battery Usage Details not reached"; shot about-missing
fi

adb shell dumpsys battery reset
adb shell settings delete global cloud_nav_ui_mode || true
exit $fail
