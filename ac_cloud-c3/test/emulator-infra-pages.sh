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
# Written AS THE APP (run-as, the debug APK): a dir that adb's shell user creates under Android/data/<pkg> is not readable by
# the app on API 30+, so the side-loaded file was never found ("no GitHub token on this phone"). A release APK has no run-as:
# the shell push stays as the fallback.
SIDE="/sdcard/Android/data/$PKG/files/private"
if ! adb shell "run-as $PKG sh -c 'mkdir -p $SIDE && cat > $SIDE/identities.json'" < "$FAKE"; then
  adb shell mkdir -p "$SIDE" || true
  adb push "$FAKE" "$SIDE/identities.json" >/dev/null || true
fi
adb shell "run-as $PKG ls -l $SIDE" 2>/dev/null || true

shot() { sleep "${2:-4}"; adb exec-out screencap -p > "$OUT/$1.png" 2>/dev/null; adb exec-out uiautomator dump /dev/tty > "$OUT/$1.xml" 2>/dev/null || true; }
has() { grep -q -- "$2" "$OUT/$1.xml"; }
# a chip row scrolls (Containers: All, Cloudy, Cloud-me, CG, Capital, Hardtech, Others): when a label is not on screen,
# swipe the row of the nearest earlier label left and add that dump to the page's xml, then look again
has_scrolled() { # <shot> <label> <row label>
  has "$1" "$2" && return 0
  local y; y="$(python3 - "$OUT/$1.xml" "$3" <<'PY'
import re, sys
x = open(sys.argv[1], errors="ignore").read()
m = re.search(r'text="%s"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' % re.escape(sys.argv[2]), x)
if m: print((int(m.group(2)) + int(m.group(4))) // 2)
PY
)"
  [ -n "$y" ] || return 1
  for _ in 1 2 3; do
    adb shell input swipe 900 "$y" 200 "$y" 400; sleep 1
    adb exec-out uiautomator dump /dev/tty >> "$OUT/$1.xml" 2>/dev/null || true
    has "$1" "$2" && return 0
  done
  return 1
}
open_page() { adb shell am start -W -n "$PKG/.MainActivity" --es shortcut_action "page:$1" </dev/null >/dev/null 2>&1; }

adb shell am start -W -n "$PKG/.MainActivity" </dev/null >/dev/null 2>&1; shot 00-first-start 6
# page id, wait, labels the screen must show (row names and page names; _ = space)
while read -r page wait labels; do
  [ -z "$page" ] && continue
  open_page "$page"; shot "infra-$page" "$wait"
  for l in ${labels//,/ }; do has "infra-$page" "${l//_/ }" || bad "infra-$page: label '${l//_/ }' missing"; done
done <<'PAGES'
providers 6 Bare,APIs,Personal,Domains,Providers,VMs
machines 4 VMs
serverless 6 Containers-as-Service,Google_Cloud_Run,Project:,Region:
node_python 4 Containers-Node-Python,GitHub_Pages,GitHub_Actions,Google_Firebase
storage 4 Storage
api_ai 4 AI,OpenRouter,Claude
api_scraper 4 Scraper,Apify,Cloudy_Crawlee
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
  anchor=""  # the nearest earlier label that is on screen: its row is the one to scroll
  for l in ${labels//,/ }; do
    if has_scrolled "c-$page" "${l//_/ }" "${anchor:-${l//_/ }}"; then anchor="${l//_/ }"; else bad "c-$page: label '${l//_/ }' missing"; fi
  done
done <<'CPAGES'
all_topology 6 All,Cloudy,Cloud-me,CG,Capital,Hardtech,Others,Topology,Table
all_table 6 Topology,Table,Container,Cloudy_/
cloudy_topology 5 Topology,Table
cloudy_table 5 Container
capital_topology 5 Capital
capital_table 5 Container
cg_topology 5 Topology,Table,MCPs,APIs,Apps,DBs,Repos
hardtech_table 5 Topology,Table
cloud_me_table 5 Container
agents 5 Agents
CPAGES

adb logcat -d -t 3000 > "$OUT/logcat.txt" 2>/dev/null || true
grep -E "FATAL EXCEPTION|AndroidRuntime" -A15 "$OUT/logcat.txt" | grep -B2 -A15 "$PKG" | head -80 > "$OUT/crash.txt" || true
[ -s "$OUT/crash.txt" ] && bad "a crash in logcat (crash.txt)"
ls -la "$OUT"
exit $fail
