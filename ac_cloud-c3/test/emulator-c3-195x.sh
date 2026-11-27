#!/usr/bin/env bash
# #1953 #1954 #1955 Cloud C3 on an emulator: the screenshots the design lists per ticket, each page opened through the launcher's
# `shortcut_action` extra (page:<id>), a ui dump beside each shot, scrolled shots taken with a swipe up on the body.
# Usage: emulator-c3-195x.sh <apk> <out-dir> <ticket: 1953|1954|1955|1968|1969|1971|all>. Run by .github/workflows/c3-195x-emulator.yml.
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
while read -r tk name page wait scr labels <&3; do
  [ -z "$tk" ] && continue
  [ "$T" = all ] || [ "$T" = "$tk" ] || continue
  open_page "$page"; sleep 2
  [ "$scr" -gt 0 ] && scroll "$scr"
  shot "c3-$tk-$name" "$wait"
  [ "$labels" = - ] && continue
  for l in ${labels//,/ }; do has "c3-$tk-$name" "${l//_/ }" || bad "c3-$tk-$name: label '${l//_/ }' missing"; done
done 3<<'SHOTS'
1953 all-topology-top all_topology 8 0 Topology,Table,Status,VM,Type,Apps_/_DB_/_MCP_/_API
1953 cloudme-services pj-unix-cloudme_topology 8 0 Apps_/_DB_/_MCP_/_API,API_(1)
1953 cloudme-repos pj-unix-cloudme_topology 6 3 Pvt
1953 cloudy pj-unix-cloudy_topology 8 0 Apps_/_DB_/_MCP_/_API
1953 cg-omniverse pj-cg-omniverse_topology 8 0 Repos
1953 capital-cb pj-capital-cb_topology 8 0 Repos,Capital-___
1953 wiki-modernsc pj-wiki-modernsc_topology 8 0 Repos,lib-chronos
1953 42-algo pj-lecole42-algo_topology 8 0 Repos,lecole-42-___
1953 lib lib_topology 8 0 Repos
1968 category-row all_topology 8 0 Unix,CG,Capital,Hardtech,Wiki,Projects
1968 wiki-modernsc pj-wiki-modernsc_topology 8 0 Repos,lib-chronos
1968 wiki-projects pj-wiki-projects_topology 8 0 Repos,lib-chronos
1969 home-top home 8 0 Pages,Infra,Containers
1969 home-pages home 6 2 Orch
1969 home-pages-low home 6 5 Ops
1969 infra-projects-table projects 8 0 Category,Project,Repos,Containers,Live_status
1969 infra-projects-wiki projects 6 1 Wiki
1971 projects-mode all_topology 8 0 Projects,VM,Unix,Wiki
1971 vm-all vm-all_topology 8 0 VM,oci-apps,oci-analytics,oci-mail,gcp-proxy,SurfacePro8,SamsungA37,SamsungS21,Not_deployed
1971 vm-oci-apps vm-oci-apps_topology 8 0 VM,oci-apps
1971 vm-unknown vm-unknown_topology 8 0 Unknown_host,not_a_declared_fleet_host
1971 vm-table vm-all_table 8 0 Status,Auth,VM,Project,Type
1955 mail-strip mail_tables 8 0 Tables,Topology-Inbound,Topology-Outbound,Map:,Path:_Inbound
1955 mail-tables-map mail_tables 6 2 Path:_Outbound
1955 mail-map-alias mail_map 8 0 Tables,Path:_Inbound
SHOTS

# #1968 the category row has no Lib chip (Lib is Wiki); the old lib_topology id above opens through the alias
if [ "$T" = all ] || [ "$T" = 1968 ]; then
  [ -f "$OUT/c3-1968-category-row.xml" ] && has c3-1968-category-row 'text="Lib"' && bad "c3-1968-category-row: a Lib chip is still there"
fi

# the Cloudy Table filtered to Type=Db shows the LFS repo
if [ "$T" = all ] || [ "$T" = 1953 ]; then
  open_page pj-unix-cloudy_table; shot c3-1953-cloudy-table-pre 8
  tap_text c3-1953-cloudy-table-pre "Db" ; shot c3-1953-cloudy-table-db 4
  found=0; has c3-1953-cloudy-table-db cloudy_db_LFS && found=1
  for k in 2 3 4 5 6 7 8; do [ $found = 1 ] && break; scroll 2 </dev/null; shot c3-1953-cloudy-table-db-$k 2; has c3-1953-cloudy-table-db-$k cloudy_db_LFS && found=1; done
  [ $found = 1 ] || bad "cloudy table Type=Db: cloudy_db_LFS missing"
  rm -f "$OUT/c3-1953-cloudy-table-pre.png"
fi

adb logcat -d -t 3000 > "$OUT/logcat.txt" 2>/dev/null || true
grep -E "FATAL EXCEPTION|AndroidRuntime" -A15 "$OUT/logcat.txt" | grep -B2 -A15 "$PKG" | head -80 > "$OUT/crash.txt" || true
[ -s "$OUT/crash.txt" ] && bad "a crash in logcat (crash.txt)"
ls -la "$OUT"
exit $fail
