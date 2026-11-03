#!/usr/bin/env bash
# #1431 #1437 Cloud C3 on an emulator: one screenshot (+ ui dump) per Infra page, opened through the launcher's
# `shortcut_action` extra (page:<id>), and the labels of the three declared rows and of each page checked on screen.
# Usage: emulator-infra-pages.sh <apk> <out-dir>. Run by .github/workflows/test-c3-infra-emulator.yml.
# Exit 1 when the app does not start, a row/page label or a declared Cloud Run service is missing, or it crashes.
set -u
APK="$1"; OUT="$2"
PKG=com.diegonmarcos.cloudc3
mkdir -p "$OUT"
fail=0
bad() { echo "FAIL  $*"; fail=1; }

adb wait-for-device
for _ in $(seq 1 60); do [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ] && break; sleep 2; done
adb shell settings put global window_animation_scale 0; adb shell settings put global transition_animation_scale 0
adb shell svc power stayon true; adb shell input keyevent 82 || true
adb install -r -g "$APK" >/dev/null || { echo "FAIL  install"; exit 1; }
adb logcat -c || true

shot() { sleep "${2:-4}"; adb exec-out screencap -p > "$OUT/$1.png" 2>/dev/null; adb exec-out uiautomator dump /dev/tty > "$OUT/$1.xml" 2>/dev/null || true; }
has() { grep -q -- "$2" "$OUT/$1.xml"; }
open_page() { adb shell am start -W -n "$PKG/.MainActivity" --es shortcut_action "page:$1" >/dev/null 2>&1; }

adb shell am start -W -n "$PKG/.MainActivity" >/dev/null 2>&1; shot 00-first-start 6
# page id, wait, labels the screen must show (row names and page names; _ = space)
while read -r page wait labels; do
  [ -z "$page" ] && continue
  open_page "$page"; shot "infra-$page" "$wait"
  for l in ${labels//,/ }; do has "infra-$page" "${l//_/ }" || bad "infra-$page: label '${l//_/ }' missing"; done
done <<'PAGES'
providers 6 Bare,Personal,Domains,Providers,VMs,Containers-as-Service,Containers-Node-Python,Storage
machines 4 VMs
serverless 4 Containers-as-Service,Cloud_Run
node_python 4 Containers-Node-Python
storage 4 Storage
desktop 4 Desktop,Mobile,Gadgets
mobile 4 Mobile
gadgets 4 Gadgets
domains 4 Private,Public
domains_public 4 Private,Public
PAGES
# the declared Cloud Run services are the registry's (fleet_slice.json serverless), never typed here
has infra-serverless "agy" || bad "infra-serverless: service agy not listed"
has infra-serverless "scrappers-api" || bad "infra-serverless: service scrappers-api not listed"

adb logcat -d -t 3000 > "$OUT/logcat.txt" 2>/dev/null || true
grep -E "FATAL EXCEPTION|AndroidRuntime" -A15 "$OUT/logcat.txt" | grep -B2 -A15 "$PKG" | head -80 > "$OUT/crash.txt" || true
[ -s "$OUT/crash.txt" ] && bad "a crash in logcat (crash.txt)"
ls -la "$OUT"
exit $fail
