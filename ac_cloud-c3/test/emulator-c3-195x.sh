#!/usr/bin/env bash
# #1953 #1954 #1955 Cloud C3 on an emulator: the screenshots the design lists per ticket, each page opened through the launcher's
# `shortcut_action` extra (page:<id>), a ui dump beside each shot, scrolled shots taken with a swipe up on the body.
# Usage: emulator-c3-195x.sh <apk> <out-dir> <ticket: 1953|1954|1955|1968|1969|1971|1972|all>. Run by .github/workflows/c3-195x-emulator.yml.
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
adb shell settings put global hide_error_dialogs 1; adb shell svc power stayon true; adb shell input keyevent 82 || true
adb install -r -g "$APK" >/dev/null || { echo "FAIL  install"; exit 1; }
adb logcat -c || true

shot() { # a system "isn't responding" dialog is dismissed (Wait) and the shot retaken, so the dump is the app's, not the dialog's
  sleep "${2:-4}"
  for _try in 1 2 3; do
    adb exec-out screencap -p > "$OUT/$1.png" 2>/dev/null; adb exec-out uiautomator dump /dev/tty > "$OUT/$1.xml" 2>/dev/null || true
    grep -q "isn't responding" "$OUT/$1.xml" 2>/dev/null || break
    tap_text "$1" "Wait"; sleep 3
  done
}
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
  for _pass in 1 2; do   # a slow cold start leaves the launcher in the shot: open the page once more before judging
    open_page "$page"; sleep 2
    [ "$scr" -gt 0 ] && scroll "$scr"
    shot "c3-$tk-$name" "$((wait + (_pass - 1) * 6))"
    miss=0; [ "$labels" = - ] || for l in ${labels//,/ }; do has "c3-$tk-$name" "${l//_/ }" || miss=1; done
    [ $miss = 0 ] && break
  done
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
1968 category-row all_topology 8 0 Capital,Hardtech
1968 wiki-modernsc pj-wiki-modernsc_topology 6 3 Repos,lib-chronos
1968 wiki-projects pj-wiki-projects_topology 6 3 Repos,lib-chronos
1969 home-top home 8 0 Down_containers,Refresh,Containers
1969 home-pages home 6 2 declared
1969 home-pages-low home 6 5 containers
1969 infra-projects-table projects 8 0 Category,Project,search_the_projects
1971 projects-mode all_topology 8 0 Projects,VM,All
1971 vm-all vm-all_topology 8 0 VM,oci-apps,oci-analytics,oci-mail
1971 vm-oci-apps vm-oci-apps_topology 8 0 VM,oci-apps
1971 vm-unknown vm-unknown_topology 8 0 Unknown_host,not_a_declared_fleet_host
1971 vm-table vm-all_table 8 0 Status,Auth,VM,Project,Type
1972 infra-strip providers 8 0 Infra_Cloud,Infra_Bare,Services_API,Providers
1972 infra-gadgets-alias control 8 0 Infra_Bare,Gadgets
1972 c3-api-top api_endpoints 8 0 C3_API,Public:,Mesh:
1972 c3-api-mcp api_endpoints 6 12 API_route,tree
1972 c3-api-alias api_mcp 8 0 -
1954 orch-strip orch_home 8 0 Pipeline,Registries,CI/CD,Home,Workflows,Deploy_history
1954 home-flow orch_home 8 0 SOURCES,WORKFLOWS,BUILDS,REGISTRIES,DEPLOYS,RUNNING
1954 home-lanes orch_home 6 3 Pipelines
1954 gh-releases-table orch_gh_releases 8 0 Table,Graph,GH_Releases
1954 release-alias orch_release 8 0 Table,Graph
1954 workflows orch_workflows 8 0 Dagu_DAGs
1954 deploys orch_deploys 8 0 -
1954 service-alias service 8 0 Status,Auth,VM,Project,Type
1955 mail-strip mail_tables 8 0 Tables,Topology-Inbound,Topology-Outbound,Map:,Path:_Inbound
1955 mail-map-alias mail_map 8 0 Tables,Path:_Inbound
1955 mail-tables-map mail_tables 6 1 Path:_Outbound
SHOTS

hswipe() { # <y percent> <times> <dir: left|right>: swipe a horizontally scrolling row (the chip strips, the wide Projects table)
  local s; s="$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1)"; local w="${s%x*}" h="${s#*x}"
  for _ in $(seq 1 "$2"); do
    if [ "$3" = left ]; then adb shell input swipe $((w * 85 / 100)) $((h * $1 / 100)) $((w * 10 / 100)) $((h * $1 / 100)) 400; else adb shell input swipe $((w * 10 / 100)) $((h * $1 / 100)) $((w * 85 / 100)) $((h * $1 / 100)) 400; fi; sleep 1
  done
}
chk() { # <shot> <labels, _ = space>
  for l in ${2//,/ }; do has "$1" "${l//_/ }" || bad "$1: label '${l//_/ }' missing"; done
}
if [ "$T" = all ] || [ "$T" = 1969 ]; then   # the wide table: its right-hand columns after a swipe
  open_page projects; sleep 4; hswipe 30 3 left; shot c3-1969-infra-projects-table-right 4
  chk c3-1969-infra-projects-table-right "no_containers,GitHub"
fi
if [ "$T" = all ] || [ "$T" = 1969 ]; then   # the search field narrows the table to the Wiki projects
  open_page projects; shot c3-1969-infra-projects-wiki-pre 8
  tap_text c3-1969-infra-projects-wiki-pre "search the projects"; sleep 1; adb shell input text ModernSc; sleep 1; adb shell input keyevent 66 </dev/null; shot c3-1969-infra-projects-wiki 4
  chk c3-1969-infra-projects-wiki "ModernSc,1/26_projects"
  rm -f "$OUT/c3-1969-infra-projects-wiki-pre.png"
fi
if [ "$T" = all ] || [ "$T" = 1971 ]; then   # the VM row: the devices after the VMs, Unknown host last
  open_page vm-all_topology; sleep 6; hswipe 27 3 left; shot c3-1971-vm-row-devices 4
  chk c3-1971-vm-row-devices "gcp-proxy,SurfacePro8,SamsungA37,SamsungS21,Unknown_host"
fi
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

# #1955 A every Network page in Table, then Graph (the switch is remembered per page); canvas text is not in the UI dump, so the checks
# read the toolbar, the sheets and the canvas content-description ("nodes:... edges:...").
tap_edit() { # <shot>: tap the first text field (the Graph search)
  local xy; xy="$(python3 - "$OUT/$1.xml" <<'PY'
import re, sys
x = open(sys.argv[1], errors="ignore").read()
m = re.search(r'class="android.widget.EditText"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x)
if m: print((int(m.group(1)) + int(m.group(3))) // 2, (int(m.group(2)) + int(m.group(4))) // 2)
PY
)"
  [ -n "$xy" ] && adb shell input tap $xy
}
tap_last() { # <shot> <text>: tap the LAST node whose text starts with <text> (a search hit chip, not the field that holds the query)
  local xy; xy="$(python3 - "$OUT/$1.xml" "$2" <<'PY'
import re, sys
x = open(sys.argv[1], errors="ignore").read()
ms = re.findall(r'text="%s[^"]*"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' % re.escape(sys.argv[2]), x)
if ms: m = ms[-1]; print((int(m[0]) + int(m[2])) // 2, (int(m[1]) + int(m[3])) // 2)
PY
)"
  [ -n "$xy" ] && adb shell input tap $xy
}
if [ "$T" = all ] || [ "$T" = 1955 ]; then
  for pg in net_wg net_auth net_dns net_network net_security net_mesh; do
    n="${pg#net_}"
    open_page "$pg"; sleep 2; shot "c3-1955-net-$n-table" 8
    for l in Table Graph; do has "c3-1955-net-$n-table" "text=\"$l\"" || bad "c3-1955-net-$n-table: switch '$l' missing"; done
    tap_text "c3-1955-net-$n-table" "Graph"; shot "c3-1955-net-$n-graph" 6
    has "c3-1955-net-$n-graph" 'content-desc="nodes:' || bad "c3-1955-net-$n-graph: no canvas drawn"
    for l in Fit +all Legend Re-read; do has "c3-1955-net-$n-graph" "text=\"$l\"" || bad "c3-1955-net-$n-graph: toolbar '$l' missing"; done
    has "c3-1955-net-$n-graph" "ROOT CAUSE" && cp "$OUT/c3-1955-net-$n-graph.png" "$OUT/c3-1955-net-$n-root-banner.png"
  done
  # WireGuard: search the hub, open its sheet, then one of its edges
  hide_kb() { # the soft keyboard covers the sheet's edge rows: BACK closes it, but only when it is up (BACK on a page would leave it)
    adb shell dumpsys input_method 2>/dev/null | grep -q "mInputShown=true" && { adb shell input keyevent 4; sleep 2; }
  }
  open_page net_wg; sleep 2; shot c3-1955-net-wg-pre 5
  tap_edit c3-1955-net-wg-pre; sleep 1; adb shell input text gcp-proxy; shot c3-1955-net-wg-search 3
  tap_last c3-1955-net-wg-search "gcp-proxy"; shot c3-1955-net-wg-node-sheet 3
  has c3-1955-net-wg-node-sheet "Open in Table" || bad "c3-1955-net-wg-node-sheet: no node sheet"
  hide_kb; shot c3-1955-net-wg-node-sheet-kbdown 2
  tap_text c3-1955-net-wg-node-sheet-kbdown "  -"; shot c3-1955-net-wg-edge-sheet 3
  has c3-1955-net-wg-edge-sheet "Highlight path" || bad "c3-1955-net-wg-edge-sheet: no edge sheet"
  # DNS: expand every cluster, then Fit all (the minimap shows on a tall world)
  open_page net_dns; sleep 2; shot c3-1955-net-dns-pre 5
  tap_text c3-1955-net-dns-pre "+all"; shot c3-1955-net-dns-expanded 4
  tap_text c3-1955-net-dns-expanded "Fit"; shot c3-1955-net-dns-fit 4
  # Auth: a search is active
  open_page net_auth; sleep 2; shot c3-1955-net-auth-pre 5
  tap_edit c3-1955-net-auth-pre; sleep 1; adb shell input text two; shot c3-1955-net-auth-search 3
  # Routes: one Caddy group opened from its search hit
  open_page net_network; sleep 2; shot c3-1955-net-network-pre 5
  tap_edit c3-1955-net-network-pre; sleep 1; adb shell input text Caddy; shot c3-1955-net-network-search 3
  tap_last c3-1955-net-network-search "Caddy"; shot c3-1955-net-network-sheet 3
  tap_text c3-1955-net-network-sheet "Expand"; shot c3-1955-net-network-group-expanded 4
  # the legend
  open_page net_mesh; sleep 2; shot c3-1955-net-mesh-pre 5
  tap_text c3-1955-net-mesh-pre "Legend"; shot c3-1955-net-legend 3
  has c3-1955-net-legend "ROOT = root cause" || bad "c3-1955-net-legend: legend not open"
  rm -f "$OUT"/c3-1955-net-*-pre.png
fi

# #1954 the GH Releases Graph (the former Release page) behind the Table | Graph switch, and the Containers Table's deployed-state columns
if [ "$T" = all ] || [ "$T" = 1954 ]; then
  open_page orch_gh_releases; sleep 2; shot c3-1954-gh-releases-pre 8
  tap_text c3-1954-gh-releases-pre "Graph"; shot c3-1954-gh-releases-graph 8
  chk c3-1954-gh-releases-graph "Table,Graph,Grouped_by"
  rm -f "$OUT/c3-1954-gh-releases-pre.png"
  open_page all_table; sleep 6; hswipe 45 10 left; shot c3-1954-containers-drift 4
  chk c3-1954-containers-drift "Live_state,Drift"
fi

adb logcat -d -t 3000 > "$OUT/logcat.txt" 2>/dev/null || true
grep -A15 "FATAL EXCEPTION" "$OUT/logcat.txt" | grep -B2 -A15 "Process: $PKG" | head -80 > "$OUT/crash.txt" || true
[ -s "$OUT/crash.txt" ] && bad "a crash in logcat (crash.txt)"
ls -la "$OUT"
exit $fail
