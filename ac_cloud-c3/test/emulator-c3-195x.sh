#!/usr/bin/env bash
# #1953 #1954 #1955 Cloud C3 on an emulator: the screenshots the design lists per ticket, each page opened through the launcher's
# `shortcut_action` extra (page:<id>), a ui dump beside each shot, scrolled shots taken with a swipe up on the body.
# Usage: emulator-c3-195x.sh <apk> <out-dir> <ticket: 1953|1954|1955|1968|1969|1971|1972|1982|all>. Run by .github/workflows/c3-195x-emulator.yml.
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
dec() { local s="${1//_/ }"; printf "%s" "${s//+/_}"; }   # a label: _ = space, + = a literal underscore
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
    miss=0; [ "$labels" = - ] || for l in ${labels//,/ }; do has "c3-$tk-$name" "$(dec "$l")" || miss=1; done
    [ $miss = 0 ] && break
  done
  [ "$labels" = - ] && continue
  for l in ${labels//,/ }; do has "c3-$tk-$name" "$(dec "$l")" || bad "c3-$tk-$name: label '$(dec "$l")' missing"; done
done 3<<'SHOTS'
1953 all-topology-top all_topology 8 0 Topology,Table,Status,VM,Type,Apps_/_DB_/_MCP_/_API
1953 cloudme-services pj-unix-cloudme_topology 8 0 Apps_/_DB_/_MCP_/_API,API_(1)
1953 cloudme-repos pj-unix-cloudme_topology 6 3 Pvt
1953 cloudy pj-unix-cloudy_topology 8 0 Apps_/_DB_/_MCP_/_API
1953 cg-omniverse pj-cg-omniverse_topology 8 0 Repos
1953 capital-cb pj-capital-cb_topology 8 0 Repos,Capital-+++
1953 wiki-modernsc pj-wiki-modernsc_topology 8 0 Repos,lib-chronos
1953 42-algo pj-lecole42-algo_topology 8 0 Repos,lecole-42-+++
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
1954 workflows orch_workflows 8 0 -
1954 flow-alias orch_flow 8 0 Pipeline,Registries
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
  for l in ${2//,/ }; do has "$1" "$(dec "$l")" || bad "$1: label '$(dec "$l")' missing"; done
}
ypct() { # <shot> <text>: the vertical centre (percent of the screen height) of the first node whose text starts with <text>
  local s h; s="$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1)"; h="${s#*x}"
  python3 - "$OUT/$1.xml" "$2" "$h" <<'PY'
import re, sys
x = open(sys.argv[1], errors="ignore").read()
m = re.search(r'text="%s[^"]*"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' % re.escape(sys.argv[2]), x)
print(((int(m.group(2)) + int(m.group(4))) // 2) * 100 // int(sys.argv[3]) if m else 20)
PY
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
  open_page vm-all_topology; sleep 6; shot c3-1971-vm-row-pre 3; hswipe "$(ypct c3-1971-vm-row-pre VM)" 3 left; shot c3-1971-vm-row-devices 4; rm -f "$OUT/c3-1971-vm-row-pre.png"
  chk c3-1971-vm-row-devices "gcp-proxy,SurfacePro8,SamsungA37,SamsungS21,Unknown_host"
fi
# #1982 the Projects row and the VM row, both always drawn, combined as AND: All/All, Unix only, oci-apps only, Unix + oci-apps, then both rows scrolled on the narrow screen
if [ "$T" = all ] || [ "$T" = 1982 ]; then
  open_page all_topology; sleep 6; shot c3-1982-all-all 4
  chk c3-1982-all-all "Projects,VM,All,Unix,CG,Capital,Hardtech,Wiki,oci-apps,Topology,Table,Status,Type"
  has c3-1982-all-all 'text="Projects"' && bad "c3-1982-all-all: the old Projects | VM toggle is still there"
  tap_text c3-1982-all-all "Unix"; shot c3-1982-unix-only 4
  chk c3-1982-unix-only "Unix,SuperApp,Cloud-me,Cloudy"
  tap_text c3-1982-unix-only "oci-apps"; shot c3-1982-unix-oci-apps 4
  chk c3-1982-unix-oci-apps "Unix,SuperApp,oci-apps"
  tap_text c3-1982-unix-oci-apps "All"; shot c3-1982-oci-apps-only 4
  chk c3-1982-oci-apps-only "oci-apps,Projects,VM"
  py="$(ypct c3-1982-oci-apps-only Projects)"; vy="$(ypct c3-1982-oci-apps-only VM)"
  hswipe "$py" 3 left; hswipe "$vy" 3 left; shot c3-1982-narrow-scrolled 3
  chk c3-1982-narrow-scrolled "Projects,VM,Unknown_host"
fi
# #1968 the category row has no Lib chip (Lib is Wiki); the old lib_topology id above opens through the alias
if [ "$T" = all ] || [ "$T" = 1968 ]; then
  [ -f "$OUT/c3-1968-category-row.xml" ] && has c3-1968-category-row 'text="Lib"' && bad "c3-1968-category-row: a Lib chip is still there"
fi

# the Cloudy Table filtered to Type=Db shows the LFS repo
if [ "$T" = all ] || [ "$T" = 1953 ]; then
  open_page pj-unix-cloudy_table; shot c3-1953-cloudy-table-pre 8
  tap_text c3-1953-cloudy-table-pre "Db" ; shot c3-1953-cloudy-table-db 4
  hswipe 50 2 left; shot c3-1953-cloudy-table-db-right 3   # the Name column sits right of Project / Cat1
  found=0; has c3-1953-cloudy-table-db-right cloudy_db_LFS && found=1
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
    for l in Fit +all Legend; do has "c3-1955-net-$n-graph" "text=\"$l\"" || bad "c3-1955-net-$n-graph: toolbar '$l' missing"; done
    has "c3-1955-net-$n-graph" 'content-desc="Re-read' || bad "c3-1955-net-$n-graph: toolbar Re-read missing"
    has "c3-1955-net-$n-graph" "ROOT CAUSE" && cp "$OUT/c3-1955-net-$n-graph.png" "$OUT/c3-1955-net-$n-root-banner.png"
    # #1955 A rev2: the opening view, then 2x on one node (search it, Zoom 2x in its sheet, close the sheet)
    cp "$OUT/c3-1955-net-$n-graph.png" "$OUT/c3-1955-graph-rev2-$n-fit.png"; cp "$OUT/c3-1955-net-$n-graph.xml" "$OUT/c3-1955-graph-rev2-$n-fit.xml"
    case "$n" in wg|mesh) q=gcp-proxy ;; auth) q=Authelia ;; dns) q=auth ;; network) q=Caddy ;; security) q=cloudy-c3-api ;; esac
    tap_edit "c3-1955-net-$n-graph"; sleep 1; adb shell input text "$q"; shot "c3-1955-graph-rev2-$n-hits" 3
    tap_last "c3-1955-graph-rev2-$n-hits" "$q"; shot "c3-1955-graph-rev2-$n-sheet" 3
    tap_text "c3-1955-graph-rev2-$n-sheet" "Zoom 2x"; shot "c3-1955-graph-rev2-$n-zoomed" 2
    tap_text "c3-1955-graph-rev2-$n-zoomed" " close"; shot "c3-1955-graph-rev2-$n-zoom" 3
    has "c3-1955-graph-rev2-$n-zoom" 'content-desc="nodes:' || bad "c3-1955-graph-rev2-$n-zoom: no canvas drawn"
    rm -f "$OUT/c3-1955-graph-rev2-$n-hits.png" "$OUT/c3-1955-graph-rev2-$n-zoomed.png"
    # clear the query (the later flows type into the same field) and drop the keyboard
    tap_edit "c3-1955-graph-rev2-$n-zoom"; sleep 1; adb shell input keyevent 123; adb shell input keyevent $(printf '67 %.0s' $(seq 1 24))
    adb shell dumpsys input_method 2>/dev/null | grep -q "mInputShown=true" && { adb shell input keyevent 4; sleep 1; }
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
  # rev2: the sheet opens in its 132 dp peek; "more" expands it to the edge rows
  tap_text c3-1955-net-wg-node-sheet-kbdown "  more"; shot c3-1955-net-wg-node-sheet-more 2
  tap_text c3-1955-net-wg-node-sheet-more "  -"; shot c3-1955-net-wg-edge-sheet 3
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
  # #1955 C the Health debugger: every tab, then X-ray C3. The emulator has no token: the device-side state and the not-served routes must be said, not hidden.
  open_page ops_health_health; sleep 2; shot c3-1955-health-overview 8
  chk c3-1955-health-overview "Probe_now,Overview,Lights,Containers,VMs,Network_/_Mesh,This_phone,C3_token"
  has c3-1955-health-overview "ROOT CAUSE" && bad "c3-1955-health-overview: a root cause is blamed on a token-less device"
  for t in Lights Containers VMs "Network / Mesh" "More layers" Profile Cause; do
    n="$(printf '%s' "$t" | tr 'A-Z /' 'a-z--' | tr -s -)"
    tap_text c3-1955-health-overview "$t"; shot "c3-1955-health-$n" 4
  done
  chk c3-1955-health-more-layers "route_not_served_yet"
  chk c3-1955-health-lights "public,private"
  open_page ops_health_tests; sleep 2; shot c3-1955-health-tests-open 8
  chk c3-1955-health-tests-open "Engines,C3_full,VMs,Services,Suites"
  tap_text c3-1955-health-tests-open "C3 full"; shot c3-1955-health-xray-full 4
  chk c3-1955-health-xray-full "C3_full,VMs,Services,Suites,X-ray"
  for t in VMs Services Suites; do
    n="$(printf '%s' "$t" | tr 'A-Z' 'a-z')"
    tap_text c3-1955-health-xray-full "$t"; shot "c3-1955-health-xray-$n" 4
  done
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

# #1983 Ops > Health > Tests: every engine as a card, the mail card opened (history, reasons: no token on this emulator), X-ray C3 full fed by the engines
if [ "$T" = all ] || [ "$T" = 1983 ]; then
  open_page ops_health_tests; sleep 2; shot c3-1983-tests-list 8
  chk c3-1983-tests-list "Engines,C3_full,cloud-mail-health-full,cloud-health-full-daily,cloud-url-health-report,Run"
  tap_text c3-1983-tests-list "cloud-mail-health-full"; shot c3-1983-mail-card 4
  chk c3-1983-mail-card "HISTORY,no_verdict"
  open_page ops_health_tests; sleep 2; scroll 3; shot c3-1983-tests-list-bottom 4
  chk c3-1983-tests-list-bottom "cloud-sec-network-report,cloud-sec-data-report,cloud-analytics-daily,no_trigger"
  open_page ops_health_tests; sleep 2; tap_text c3-1983-tests-list "C3 full"; shot c3-1983-xray-c3-full 4
  chk c3-1983-xray-c3-full "fed_by,X-ray"
fi

adb logcat -d -t 3000 > "$OUT/logcat.txt" 2>/dev/null || true
grep -A15 "FATAL EXCEPTION" "$OUT/logcat.txt" | grep -B2 -A15 "Process: $PKG" | head -80 > "$OUT/crash.txt" || true
[ -s "$OUT/crash.txt" ] && bad "a crash in logcat (crash.txt)"
ls -la "$OUT"
exit $fail
