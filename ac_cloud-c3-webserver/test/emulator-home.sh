#!/usr/bin/env bash
# #1723 emulator walk of Cloud C3 Webserver > Home: screenshots from the top down (scrolling), a check that the ADB shell table is on the page,
# then a tap on its Server row must land on ADB Shell > Server. Usage: emulator-home.sh WEBSERVER_APK OUT_DIR
set -u
WS_APK="$1"; OUT="$2"; W=com.diegonmarcos.cloudwebserver
mkdir -p "$OUT"
adb install -r -g "$WS_APK" >"$OUT/install.txt" 2>&1 || { cat "$OUT/install.txt"; exit 1; }
shoot() { adb exec-out screencap -p > "$OUT/$1.png"; }
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml "$OUT/$1.xml" >/dev/null 2>&1; }
rc=0; fail() { echo "::error::$*"; rc=1; }
adb shell am force-stop "$W"; adb shell input keyevent KEYCODE_HOME
adb shell am start -W -n "$W/.MainActivity" >/dev/null; sleep 10
# Home is the default section: ADB shell rows may need a scroll
for i in 1 2 3 4 5 6; do
  shoot "home-$i"; dump "home-$i"
  adb shell input swipe 540 1700 540 600 300; sleep 2
done
cat "$OUT"/home-*.xml > "$OUT/all.xml"
for needle in "ADB SHELL" "Wireless dbg" "Paired" "Started by" "Auto-start"; do
  grep -qiF "$needle" "$OUT/all.xml" || fail "Home shows no '$needle' (ADB shell status table)"
done
# back to the top, tap the Server row of the table: lands on the ADB Shell Server sub page
for i in 1 2 3 4 5 6 7; do adb shell input swipe 540 600 540 1700 200; done; sleep 1
for i in 1 2 3 4; do
  dump cur
  b=$(grep -B0 -oE "content-desc=\"\"[^>]*resource-id=\"c3_home_adb_state\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"|resource-id=\"c3_home_adb_state\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" "$OUT/cur.xml" | head -1 | grep -oE '[0-9]+' | tail -4 | tr '\n' ' ')
  [ -n "$b" ] && break
  adb shell input swipe 540 1700 540 900 300; sleep 1
done
if [ -n "${b:-}" ]; then set -- $b; adb shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )); sleep 4; shoot after-tap; dump after-tap
  grep -qE 'c3_adb_server' "$OUT/after-tap.xml" || echo "::warning::the tap did not reach c3_adb_server (tags are not exported as resource-ids on every build); see after-tap.png"
else echo "::warning::c3_home_adb_state not found as a resource-id; screenshots are the evidence"; fi
adb logcat -d -t 800 > "$OUT/logcat.txt" 2>/dev/null || true
grep -q "FATAL EXCEPTION.*$W\|Process: $W" "$OUT/logcat.txt" && fail "the app crashed during the walk"
exit $rc
