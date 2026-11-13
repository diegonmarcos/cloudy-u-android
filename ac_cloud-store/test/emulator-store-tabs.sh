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
# (an ANR prompt can stack on it while the first fleet pass runs: Wait, then Cancel; BACK when the dump fails)
for i in 1 2 3 4 5 6; do
  if tap_text Wait || tap_text Cancel; then sleep 3
  elif ! grep -q 'text="Lite-ML"' "$OUT/ui.xml" 2>/dev/null; then adb shell input keyevent KEYCODE_BACK; sleep 3
  else break; fi
done
read -r W H < <(adb shell wm size | tr -d '\r' | grep -oE '[0-9]+x[0-9]+' | tail -1 | tr x ' ')
X=$((W/2)); Y1=$((H*4/5)); Y2=$((H/4))
shoot start
rc=0
# #1704 the Cloud > Apps list scrolled to its end: the last category headings are in shot and none is Other/Others
for i in $(seq 1 25); do adb shell input swipe $X $Y1 $X $Y2 200; sleep 0.4; done
sleep 2; shoot apps-end
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml "$OUT/apps-end.xml" >/dev/null 2>&1
if grep -qE 'text="Others?"' "$OUT/apps-end.xml"; then echo "::error::the Cloud tab shows an Other(s) group"; rc=1; fi
for i in $(seq 1 25); do adb shell input swipe $X $Y2 $X $Y1 100; done
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
# #1718 Access: its five pages (MCP (device), APIs, IPC, Identity, Android Perms), each in shot, drawn from the device access model
# the Store's own fleet data provider serves. The dataset is synced from the fleet data manifest at run time, so the walk waits for
# it (tapping the page's Sync now) and is red when a model page still has no data, or when a page that left (Apps Mesh, Cloud Perms)
# is back on the strip.
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml "$OUT/$1.xml" >/dev/null 2>&1; }
adb shell am start -W -n "$ACT" --es tab access >/dev/null; sleep 6
dump access-strip
for gone in "Apps Mesh" "Cloud Perms" "Apps MCP-API"; do
  if grep -q "text=\"$gone\"" "$OUT/access-strip.xml"; then echo "::error::Access still shows the $gone page"; rc=1; fi
done
if tap_text 'MCP \(device\)'; then
  for i in $(seq 1 18); do
    sleep 8; dump access-mcp
    grep -q 'device MCP tools' "$OUT/access-mcp.xml" && break
    tap_text 'Sync now' || true
  done
  shoot access-mcp
  grep -q 'device MCP tools' "$OUT/access-mcp.xml" || { echo "::error::Access > MCP (device) has no data: the device access dataset was not served"; rc=1; }
  for i in 1 2 3; do adb shell input swipe $X $Y1 $X $Y2 250; sleep 0.5; done; sleep 1; shoot access-mcp-tools
else
  echo "::error::no MCP (device) page on the Access strip"; rc=1
fi
if tap_text APIs; then
  sleep 4; shoot access-apis; dump access-apis
  grep -q 'API routes' "$OUT/access-apis.xml" || { echo "::error::Access > APIs has no data"; rc=1; }
  if grep -qE 'text="0 routes"' "$OUT/access-apis.xml"; then echo "::error::Access > APIs shows an app with 0 routes"; rc=1; fi
  for i in 1 2; do adb shell input swipe $X $Y1 $X $Y2 250; sleep 0.5; done; sleep 1; shoot access-apis-rows; dump access-apis-rows
  if grep -qE 'text="0 routes"' "$OUT/access-apis-rows.xml"; then echo "::error::Access > APIs shows an app with 0 routes"; rc=1; fi
  b=$(grep -oE 'text="[0-9]+ routes"[^>]*bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' "$OUT/access-apis-rows.xml" | sed -n 1p | grep -oE '[0-9]+' | tail -4 | tr '\n' ' ')
  if [ -n "$b" ]; then set -- $b; adb shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )); sleep 2; shoot access-apis-open; fi
else
  echo "::error::no APIs page on the Access strip"; rc=1
fi
if tap_text IPC; then
  sleep 4; shoot access-ipc-gateway; dump access-ipc
  grep -q 'gateway routes' "$OUT/access-ipc.xml" || { echo "::error::Access > IPC shows no gateway routes"; rc=1; }
  if tap_text 'Endpoint registry'; then sleep 5; shoot access-ipc-registry; tap_text 'Gateway routes' || true; fi
else
  echo "::error::no IPC page on the Access strip"; rc=1
fi
if tap_text Identity; then
  sleep 4; shoot access-identity-direct; dump access-identity
  grep -q 'Signature level' "$OUT/access-identity.xml" || { echo "::error::Access > Identity shows no signature-level card"; rc=1; }
  for i in 1 2; do adb shell input swipe $X $Y1 $X $Y2 250; sleep 0.5; done; sleep 1; shoot access-identity-direct-apps
  for i in 1 2 3; do adb shell input swipe $X $Y2 $X $Y1 100; done
  if tap_text 'Linux identity'; then sleep 5; shoot access-identity-uid; tap_text 'Direct access' || true; fi
else
  echo "::error::no Identity page on the Access strip"; rc=1
fi
if tap_text 'Android Perms'; then
  sleep 5; shoot access-android; dump access-android
  grep -q 'OS reach' "$OUT/access-android.xml" || { echo "::error::Access > Android Perms shows no OS reach card"; rc=1; }
else
  echo "::error::no Android Perms page on the Access strip"; rc=1
fi
adb logcat -d -t 400 > "$OUT/logcat.txt" 2>/dev/null || true
exit $rc
