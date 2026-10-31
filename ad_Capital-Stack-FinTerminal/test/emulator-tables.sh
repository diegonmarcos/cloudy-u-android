#!/usr/bin/env bash
# emulator-tables.sh <apk> <out-dir> — the CI emulator walk of the FinTerm tables (#1374): installs the APK, clears the adb-only global
# cloud_nav_ui_mode (so the declared Clean chrome shows, not the Classic mode other walks pin), opens each table dashboard through the
# `dashboard` intent extra and screencaps it, swipes the table sideways (later columns) and shoots, then scrolls and shoots again. Runs inside reactivecircus/android-emulator-runner (CI only).
set -u
APK="$1"; OUT="$2"; mkdir -p "$OUT"
PKG=com.diegonmarcos.capitalfinterm
DASHES="yields_matrix bonds_market eq_dm_us eq_em world_equity_indices commodities mp_60_40 mp_70_30_DM_EM market_monitor"
adb install -r -g "$APK" || exit 1
adb shell settings delete global cloud_nav_ui_mode || true
echo "cloud_nav_ui_mode=$(adb shell settings get global cloud_nav_ui_mode)" | tee "$OUT/nav-mode.txt"
read -r W H < <(adb shell wm size | awk -F'[ x]' '/Physical/{print $3, $4}')
W=${W:-1080}; H=${H:-2400}
for d in $DASHES; do
  adb shell am start -S -W -n "$PKG/.MainActivity" --es dashboard "$d" >/dev/null
  sleep 30
  adb exec-out screencap -p > "$OUT/apk-$d-1.png"
  # sideways: the table's later columns (a swipe right-to-left across the first table), then back
  adb shell input swipe $((W * 9 / 10)) $((H / 2)) $((W / 10)) $((H / 2)) 600
  sleep 3
  adb exec-out screencap -p > "$OUT/apk-$d-side.png"
  adb shell input swipe $((W / 10)) $((H / 2)) $((W * 9 / 10)) $((H / 2)) 600
  sleep 2
  for i in 2 3 4; do
    adb shell input swipe $((W / 2)) $((H * 4 / 5)) $((W / 2)) $((H / 4)) 500
    sleep 4
    adb exec-out screencap -p > "$OUT/apk-$d-$i.png"
  done
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && adb pull /sdcard/ui.xml "$OUT/ui-$d.xml" >/dev/null 2>&1 || true
done
adb logcat -d -t 2000 > "$OUT/logcat.txt" 2>/dev/null || true
ls -la "$OUT"
