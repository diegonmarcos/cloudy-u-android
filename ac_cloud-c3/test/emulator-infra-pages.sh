#!/usr/bin/env bash
# #1431 #1437 Cloud C3 on an emulator: one screenshot (+ ui dump) per Infra page, opened through the launcher's
# `shortcut_action` extra (page:<id>), and the labels of the three declared rows and of each page checked on screen.
# Usage: emulator-infra-pages.sh <apk> <out-dir>. Run by .github/workflows/test-c3-infra-emulator.yml.
# #1454 also: the platform rows (Containers-as-Service, Containers-Node-Python), the private email identities (a FAKE side-loaded
# file, never the owner's), the Containers project pages with their Topology | Table switch, and Agents under Ops.
# Exit 1 when the app does not start, a row/page label is missing, or it crashes.
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
# the owner's real identities are private data (a private repo): the emulator is handed a FAKE side-loaded copy
FAKE="$(mktemp)"; printf '{"emails":[{"address":"fixture.one@example.test","label":"Fixture mailbox","provider":"example"}]}' > "$FAKE"
adb shell mkdir -p "/sdcard/Android/data/$PKG/files/private" || true
adb push "$FAKE" "/sdcard/Android/data/$PKG/files/private/identities.json" >/dev/null || true

shot() { sleep "${2:-4}"; adb exec-out screencap -p > "$OUT/$1.png" 2>/dev/null; adb exec-out uiautomator dump /dev/tty > "$OUT/$1.xml" 2>/dev/null || true; }
has() { grep -q -- "$2" "$OUT/$1.xml"; }
open_page() { adb shell am start -W -n "$PKG/.MainActivity" --es shortcut_action "page:$1" </dev/null >/dev/null 2>&1; }

adb shell am start -W -n "$PKG/.MainActivity" </dev/null >/dev/null 2>&1; shot 00-first-start 6
# page id, wait, labels the screen must show (row names and page names; _ = space)
while read -r page wait labels; do
  [ -z "$page" ] && continue
  open_page "$page"; shot "infra-$page" "$wait"
  for l in ${labels//,/ }; do has "infra-$page" "${l//_/ }" || bad "infra-$page: label '${l//_/ }' missing"; done
done <<'PAGES'
providers 6 Bare,Personal,Domains,Providers,VMs
machines 4 VMs
serverless 6 Containers-as-Service,Google_Cloud_Run,Project:,Region:
node_python 4 Containers-Node-Python,GitHub_Pages,GitHub_Actions,Google_Firebase
storage 4 Storage
desktop 4 Desktop,Mobile,Gadgets
mobile 4 Mobile
gadgets 4 Gadgets
domains 4 Private,Public
domains_public 6 Private,Public,Email_identities,fixture.one@example.test
PAGES
# the platforms page lists PLATFORMS, not containers: a Cloud Run service name from the registry must NOT be there
! has infra-serverless "capital-stack-finterminal-api" || bad "infra-serverless: a container is listed on the platforms page"
# Containers: each project page and its Topology | Table switch, then Agents under Ops (labels are the declared ones)
while read -r page wait labels; do
  [ -z "$page" ] && continue
  open_page "$page"; shot "c-$page" "$wait"
  for l in ${labels//,/ }; do has "c-$page" "${l//_/ }" || bad "c-$page: label '${l//_/ }' missing"; done
done <<'CPAGES'
all_topology 6 All,Cloudy,CG,Capital,Hardtech,Cloud-me,Topology,Table
all_table 6 Topology,Table,Container,Cloudy_/
cloudy_topology 5 Topology,Table
cloudy_table 5 Container
capital_topology 5 Capital
capital_table 5 Container
cg_topology 5 No_container_is_declared_for_CG
hardtech_table 5 Topology,Table
cloud_me_table 5 Container
agents 5 Agents
CPAGES

adb logcat -d -t 3000 > "$OUT/logcat.txt" 2>/dev/null || true
grep -E "FATAL EXCEPTION|AndroidRuntime" -A15 "$OUT/logcat.txt" | grep -B2 -A15 "$PKG" | head -80 > "$OUT/crash.txt" || true
[ -s "$OUT/crash.txt" ] && bad "a crash in logcat (crash.txt)"
ls -la "$OUT"
exit $fail
