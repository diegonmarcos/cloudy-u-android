#!/usr/bin/env bash
# #1718 emulator walk of Cloud C3 Webserver > API: Topology, MCP, Mesh Endpoints, Apps Endpoints, Apps IPC, one screenshot per sub page.
# The section shows ONLY this device: no Public Endpoints page on the second row, and every sub page opens with the device access
# model (the one Cloud Store > Access draws), which must have data. Usage: emulator-api-pages.sh WEBSERVER_APK OUT_DIR
set -u
WS_APK="$1"; OUT="$2"; W=com.diegonmarcos.cloudwebserver
mkdir -p "$OUT"
adb install -r -g "$WS_APK" >"$OUT/install.txt" 2>&1 || { cat "$OUT/install.txt"; exit 1; }
shoot() { adb exec-out screencap -p > "$OUT/$1.png"; }
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml "$OUT/$1.xml" >/dev/null 2>&1; }
tap_exact() {  # tap the node whose text or content-desc is exactly $1 (the LAST match: the second row sits above the bottom nav)
  dump cur
  b=$(grep -oE "(text|content-desc)=\"$1\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" "$OUT/cur.xml" | tail -1 | grep -oE '[0-9]+' | tail -4 | tr '\n' ' ')
  [ -n "$b" ] || return 1
  set -- $b; adb shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 ))
}
tap_page() {  # a page of the second row: the FIRST match (the row is at the top; a card below may carry the same word)
  dump cur
  b=$(grep -oE "(text|content-desc)=\"$1\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" "$OUT/cur.xml" | sed -n 1p | grep -oE '[0-9]+' | tail -4 | tr '\n' ' ')
  [ -n "$b" ] || return 1
  set -- $b; adb shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 ))
}
has() { grep -qi -- "$2" "$OUT/$1.xml"; }   # card titles are drawn in capitals
rc=0; fail() { echo "::error::$*"; rc=1; }
adb shell am force-stop "$W"; adb shell input keyevent KEYCODE_HOME
adb shell am start -W -n "$W/.MainActivity" >/dev/null; sleep 8
# first-run prompts (battery, update check) sit over the nav: dismiss what is there
for t in "Not now" "Later" "Cancel" "Skip"; do tap_exact "$t" && sleep 2; done
tap_exact "API" || fail "no API tab on the bottom nav"; sleep 5
dump api-row
has api-row 'Public Endpoints' && fail "the API section still shows a Public Endpoints page"
read -r WD HT < <(adb shell wm size | tr -d '\r' | grep -oE '[0-9]+x[0-9]+' | tail -1 | tr x ' ')
X=$((WD/2)); Y1=$((HT*3/4)); Y2=$((HT/3))
n=0
# page label, the text the device access model must put on it (_ = space)
while read -r label want <&3; do   # fd 3: adb reads stdin and would eat the page list
  [ -z "$label" ] && continue
  n=$((n+1)); name="$n-$(echo "$label" | tr 'A-Z ' 'a-z-')"
  tap_page "${label//_/ }" || { fail "the second row has no '${label//_/ }' page"; continue; }
  ok=0
  for i in 1 2 3 4 5 6; do sleep 4; dump "$name"; has "$name" "${want//_/ }" && { ok=1; break; }; done
  shoot "$name"
  [ "$ok" = 1 ] || fail "API > ${label//_/ } does not show '${want//_/ }' (the device access model has no data)"
  adb shell input swipe $X $Y1 $X $Y2 300; sleep 1; shoot "$name-more"
  for i in 1 2 3; do adb shell input swipe $X $Y2 $X $Y1 100; done; sleep 1   # back to the top: the second row is there
done 3<<'PAGES'
Topology API_topology
MCP MCP_tools
Mesh_Endpoints Mesh_gateway
Apps_Endpoints API_routes
Apps_IPC IPC_routes_behind_the_mesh_gateway
PAGES
has 4-apps-endpoints ' 0 routes' && fail "API > Apps Endpoints shows an app with 0 routes"
adb logcat -d -t 800 > "$OUT/logcat.txt" 2>/dev/null || true
grep -q "FATAL EXCEPTION.*$W\|Process: $W" "$OUT/logcat.txt" && fail "the app crashed during the walk"
exit $rc
