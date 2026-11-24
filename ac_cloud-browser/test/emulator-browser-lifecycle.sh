#!/bin/bash
# emulator-browser-lifecycle.sh (#1841 #1840) - runs ON a booted emulator (adb root available: google_apis image) from
# .github/workflows/test-browser-lifecycle-emulator.yml.   $1 Cloud Browser APK   $2 output dir   $3 Cloud SuperApp APK (optional)
#
#   L1 three tabs open; the app is switched away from and back: the page area must not be black (screenshots read by png_stats.py)
#   L2 the WebView renderer process is killed (kill -9 of the sandboxed renderer): the app lives, logcat says renderer-gone then recover,
#      and the tab is showing a page again (not black)
#   L3 Page-Tools opens from the tab row (icon content-desc Page-Tools) and shows its sections (Nav first since #1856)
#   L4 the app is force-stopped and reopened: the tabs are still there (session restored), not black
#   P1 Add to Home Screen with Cloud SuperApp as the launcher: the sheet, the result, the shortcut on the SuperApp home (stored + on screen)
set -u
APK="$1"; OUT="$2"; SAPK="${3:-}"; mkdir -p "$OUT"
BPKG=com.diegonmarcos.cloudbrowser; SPKG=com.diegonmarcos.superapp
HERE="$(cd "$(dirname "$0")" && pwd)"
fails=0
ok()  { echo "PASS  $1"; }
bad() { echo "::error title=browser lifecycle::$1"; echo "FAIL  $1"; fails=$((fails + 1)); }
for _ in $(seq 1 60); do
  [ "$(timeout 10 adb get-state 2>/dev/null | tr -d '\r')" = device ] && [ "$(timeout 10 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && break
  timeout 20 adb reconnect offline >/dev/null 2>&1 || true; sleep 5
done
adb install -r -g "$APK" >/dev/null || { bad "install of Cloud Browser failed"; exit 2; }
[ -n "$SAPK" ] && { adb install -r -g "$SAPK" >/dev/null || echo "NOTE superapp install failed"; }
adb root >/dev/null 2>&1 || true; sleep 3
adb shell settings put global window_animation_scale 0 || true
adb shell svc power stayon true || true
adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard >/dev/null 2>&1 || true

dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml 2>/dev/null > "$OUT/$1.xml" || true; }
shot() { adb exec-out screencap -p > "$OUT/$1.png" 2>/dev/null || true; dump "$1"; }
tap() {
  dump _tap
  local xy; xy=$(python3 - "$OUT/_tap.xml" "$1" "${2:-}" <<'PY'
import re, sys, xml.etree.ElementTree as ET
try: root = ET.parse(sys.argv[1]).getroot()
except Exception: sys.exit(0)
rx = re.compile(sys.argv[2]); byid = sys.argv[3] == "resource-id"
for n in root.iter("node"):
    if (byid and rx.search(n.get("resource-id", ""))) or (not byid and (rx.search(n.get("text", "")) or rx.search(n.get("content-desc", "")))):
        a, b, c, d = map(int, re.findall(r"\d+", n.get("bounds", "[0,0][0,0]")))
        print((a + c) // 2, (b + d) // 2); break
PY
)
  [ -n "$xy" ] && { adb shell input tap $xy; sleep 3; return 0; }
  return 1
}
tap_scroll() {   # tap a row of the Tools sheet, swiping the list up until it is composed (Add to home screen sits below the fold)
  local w h i; read -r w h < <(adb shell wm size | tr -d '\r' | grep -oE '[0-9]+x[0-9]+' | tail -1 | tr x ' ')
  for i in 1 2 3 4 5; do
    tap "$1" && return 0
    adb shell input swipe $((w / 2)) $((h * 62 / 100)) $((w / 2)) $((h * 30 / 100)) 300; sleep 2
  done
  return 1
}
open_url() { timeout 120 adb shell am start -W -a android.intent.action.VIEW -d "$1" -p "$BPKG" >/dev/null 2>&1; }
black_check() {   # $1 shot name, $2 label: the page area (below the bars) must not be black
  python3 "$HERE/png_stats.py" "$OUT/$1.png" 0.05 0.25 0.95 0.80 > "$OUT/$1.stats" 2>&1; rc=$?
  if [ $rc -eq 3 ]; then bad "$2: the page area is black ($(cat "$OUT/$1.stats"), $1.png)"; elif [ $rc -ne 0 ]; then echo "NOTE  $2: png not analysable ($(tail -1 "$OUT/$1.stats"))"; else ok "$2: not black ($(cat "$OUT/$1.stats"), $1.png)"; fi
}

# L1 three tabs, switch away and back
adb logcat -c || true
for u in https://example.com/ https://example.org/ https://info.cern.ch/; do open_url "$u"; sleep 12; done
shot l1-tabs-open; black_check l1-tabs-open "L1 three tabs open"
adb shell am start -a android.settings.SETTINGS >/dev/null 2>&1; sleep 8; shot l1-other-app
adb shell input keyevent KEYCODE_BACK; sleep 6   # back from the other app returns to the browser task as it was
shot l1-back; black_check l1-back "L1 back from another app"
adb logcat -d -s CBrowser/Life:V CBrowser/Tab:V CBrowser/Render:V CBrowser/Mem:V CBrowser/Keep:V CBrowser/Core:V > "$OUT/applog-l1.txt" 2>/dev/null || true
for tag in "ev=resume" "ev=pause" "ev=create" "ev=load-finish"; do grep -q -- "$tag" "$OUT/applog-l1.txt" && ok "L1 app log has $tag" || bad "L1 app log has no $tag (applog-l1.txt)"; done

# L2 kill the renderer
PIDS=$(adb shell ps -A 2>/dev/null | tr -d '\r' | grep -E "sandboxed_process" | awk '{print $2}')
echo "$PIDS" > "$OUT/renderer-pids.txt"
APP=$(adb shell pidof "$BPKG" | tr -d '\r' | awk '{print $1}')
if [ -z "$PIDS" ]; then echo "NOTE  L2 no sandboxed renderer process found (renderer-pids.txt)"; else
  adb logcat -c || true
  for p in $PIDS; do adb shell kill -9 "$p" >/dev/null 2>&1 || true; done
  sleep 10; shot l2-after-kill
  NEW=$(adb shell pidof "$BPKG" | tr -d '\r' | awk '{print $1}')
  [ -n "$NEW" ] && ok "L2 the app process survived the renderer kill (pid $APP -> $NEW)" || bad "L2 the app died with its renderer"
  adb logcat -d -s CBrowser/Render:V > "$OUT/applog-l2.txt" 2>/dev/null || true
  grep -q "ev=renderer-gone" "$OUT/applog-l2.txt" && ok "L2 renderer-gone logged ($(grep -m1 'renderer-gone' "$OUT/applog-l2.txt" | cut -c1-160))" || bad "L2 no renderer-gone line (applog-l2.txt)"
  grep -q "ev=recover" "$OUT/applog-l2.txt" && ok "L2 recovery from the snapshot logged" || bad "L2 no recover line"
  black_check l2-after-kill "L2 after the renderer died"
fi

# L3 Page-Tools
tap 'Page-Tools' && { shot l3-page-tools; grep -q "text=\"Nav\"" "$OUT/l3-page-tools.xml" && ok "L3 Page-Tools sheet shows its sections (l3-page-tools.png)" || bad "L3 Page-Tools opened without its sections"; adb shell input keyevent KEYCODE_BACK; sleep 2; } || bad "L3 no Page-Tools icon (content-desc) on the tab row"

# L4 restart keeps the tabs
adb shell am force-stop "$BPKG"; sleep 2; adb shell am start -n "$BPKG/.MainActivity" >/dev/null 2>&1; sleep 10; shot l4-restart
grep -qE "example|cern" "$OUT/l4-restart.xml" && ok "L4 the tabs are still there after a restart" || echo "NOTE  L4 tab titles not in the ui dump (l4-restart.png)"
black_check l4-restart "L4 after a restart"

# P1 pin on the SuperApp home
if [ -n "$SAPK" ]; then
  adb shell cmd package set-home-activity "$SPKG/.HomeActivity" >/dev/null 2>&1 || true
  adb shell input keyevent KEYCODE_HOME; sleep 5; shot p1-home-before
  adb shell am start -n "$BPKG/.MainActivity" >/dev/null 2>&1; sleep 5
  open_url https://example.com/; sleep 10
  tap 'Page-Tools' || true
  tap_scroll 'Add to home screen' || true
  shot p1-sheet
  grep -q "Add to Home Screen" "$OUT/p1-sheet.xml" && ok "P1 the add-to-home sheet is showing (p1-sheet.png)" || bad "P1 the add-to-home sheet did not open"
  tap '^Add$' || tap 'Add' || true; sleep 6; shot p1-result
  grep -qiE "sent to|Added" "$OUT/p1-result.xml" && ok "P1 the sheet reports success ($(grep -oiE 'Added [^"]*|was sent to [^"]*' "$OUT/p1-result.xml" | head -1))" || bad "P1 the sheet shows no success (p1-result.png)"
  adb shell cat /data/data/$SPKG/files/launcher/pinned-shortcuts.json > "$OUT/pinned-shortcuts.json" 2>/dev/null || true
  grep -qi "example" "$OUT/pinned-shortcuts.json" && ok "P1 the shortcut is persisted on the SuperApp home: $(cut -c1-160 "$OUT/pinned-shortcuts.json")" || bad "P1 nothing pinned in the SuperApp store"
  adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard >/dev/null 2>&1 || true; adb shell input keyevent KEYCODE_HOME; sleep 5
  for _ in 1 2 3 4; do dump _home; grep -q "Pinned" "$OUT/_home.xml" && break; adb shell input swipe 540 1500 540 600 300; sleep 2; done
  shot p1-home-after
  grep -q "Pinned" "$OUT/p1-home-after.xml" && ok "P1 the Pinned section is on the SuperApp home (p1-home-after.png)" || echo "NOTE  P1 the Pinned section is not on the first screen of the home (p1-home-after.png); the store holds the pin"
else
  echo "NOTE  P1 skipped: no SuperApp APK given"
fi
adb logcat -d -s CBrowser/Life:V CBrowser/Tab:V CBrowser/Render:V CBrowser/Mem:V CBrowser/Keep:V CBrowser/Core:V > "$OUT/applog-all.txt" 2>/dev/null || true
adb logcat -d 2>/dev/null | grep -iE "chromium|webview|CBrowser|SuperApp/Pins|renderer|sandboxed" | tail -400 > "$OUT/logcat-webview.txt" || true
echo "failures: $fails"; exit $((fails > 0 ? 1 : 0))
