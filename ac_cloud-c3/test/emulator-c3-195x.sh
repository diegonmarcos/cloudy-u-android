#!/usr/bin/env bash
# #1953 #1954 #1955 Cloud C3 on an emulator: the screenshots the design lists per ticket, each page opened through the launcher's
# `shortcut_action` extra (page:<id>), a ui dump beside each shot, scrolled shots taken with a swipe up on the body.
# Usage: emulator-c3-195x.sh <apk> <out-dir> <ticket: 1953|1954|1955|all>. Run by .github/workflows/c3-195x-emulator.yml.
# Exit 1 when the app does not start, a declared label is missing, or it crashes.
set -u
APK="$1"; OUT="$2"; T="${3:-all}"
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
open_page() { adb shell am start -W -n "$PKG/.MainActivity" --es shortcut_action "page:$1" </dev/null >/dev/null 2>&1; }
scroll() { # <times>
  local s; s="$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1)"; local w="${s%x*}" h="${s#*x}"
  for _ in $(seq 1 "$1"); do adb shell input swipe $((w / 2)) $((h * 8 / 10)) $((w / 2)) $((h * 3 / 10)) 500; sleep 1; done
}
tap_text() { # <shot> <text>: tap the first node whose text starts with <text>
  local xy; xy="$(python3 - "$OUT/$1.xml" "$2" <<'PY'
import re, sys
x = open(sys.argv[1], errors="ignore").read()
m = re.search(r'text="%s[^"]*"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' % re.escape(sys.argv[2]), x)
if m: print((int(m.group(1)) + int(m.group(3))) // 2, (int(m.group(2)) + int(m.group(4))) // 2)
PY
)"
  [ -n "$xy" ] && adb shell input tap $xy
}

adb shell am start -W -n "$PKG/.MainActivity" </dev/null >/dev/null 2>&1; shot 00-first-start 6

# <ticket> <shot name> <page id> <wait> <scrolls> <labels that must be on screen, _ = space, - = none>
while read -r tk name page wait scr labels; do
  [ -z "$tk" ] && continue
  [ "$T" = all ] || [ "$T" = "$tk" ] || continue
  open_page "$page"; sleep 2
  [ "$scr" -gt 0 ] && scroll "$scr"
  shot "c3-$tk-$name" "$wait"
  [ "$labels" = - ] && continue
  for l in ${labels//,/ }; do has "c3-$tk-$name" "${l//_/ }" || bad "c3-$tk-$name: label '${l//_/ }' missing"; done
done <<'SHOTS'
1953 all-topology-top all_topology 8 0 Topology,Table,Status,VM,Type,Apps_/_DB_/_MCP_/_API
1953 cloudme-services pj-unix-cloudme_topology 8 0 Apps_/_DB_/_MCP_/_API,Public,Private
1953 cloudme-repos pj-unix-cloudme_topology 6 4 Repos
1953 cloudy pj-unix-cloudy_topology 8 0 Apps_/_DB_/_MCP_/_API
1953 cg-omniverse pj-cg-omniverse_topology 8 0 Repos
1953 capital-cb pj-capital-cb_topology 8 0 Repos,Capital-___
1953 wiki-modernsc pj-wiki-modernsc_topology 8 0 Repos,lib-chronos
1953 42-algo pj-lecole42-algo_topology 8 0 Repos,lecole-42-___
1953 lib lib_topology 8 0 Repos
SHOTS

# the Cloudy Table filtered to Type=Db shows the LFS repo
if [ "$T" = all ] || [ "$T" = 1953 ]; then
  open_page pj-unix-cloudy_table; shot c3-1953-cloudy-table-pre 8
  tap_text c3-1953-cloudy-table-pre "Db" ; shot c3-1953-cloudy-table-db 4
  has c3-1953-cloudy-table-db cloudy_db_LFS || { scroll 3; shot c3-1953-cloudy-table-db-2 3; has c3-1953-cloudy-table-db-2 cloudy_db_LFS || bad "cloudy table Type=Db: cloudy_db_LFS missing"; }
  rm -f "$OUT/c3-1953-cloudy-table-pre.png"
fi

adb logcat -d -t 3000 > "$OUT/logcat.txt" 2>/dev/null || true
grep -E "FATAL EXCEPTION|AndroidRuntime" -A15 "$OUT/logcat.txt" | grep -B2 -A15 "$PKG" | head -80 > "$OUT/crash.txt" || true
[ -s "$OUT/crash.txt" ] && bad "a crash in logcat (crash.txt)"
ls -la "$OUT"
exit $fail
