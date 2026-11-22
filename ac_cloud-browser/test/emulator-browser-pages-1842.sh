#!/bin/bash
# emulator-browser-pages-1842.sh (#1842) - runs ON a booted emulator (adb) from .github/workflows/test-browser-pages-emulator.yml.
#   $1 Cloud Browser APK   $2 output dir   $3 optional second app APK (Cloud Calc) for the Url-Uri dropdown in another app
#
# Evidence (screenshot + ui dump per check):
#   N1 the island reads Domains, Tabs, Browser, Cloudy, Weby left to right (x of each bottomnav_item_<id>)
#   P1 Domains, Tabs, Browser, Cloudy > WebApps, Cloudy > GH Pages, Cloudy > Local, Cloudy > C3, Weby > Quickmarks, Weby > Bookmarks (all): each page's screenshot
#   C1 C3: a tap on a container icon opens the private / public chooser
#   C2 (#1856) GH Pages lists the owner's pages, Local shows File Explorers then Pages
#   S1 (#1856) the Tools sheet (the icon at the right of the tab row): Nav, Scrapper, Reading, Site Data, Perms, Dev Tools, each tab screenshotted with its rows
#   U1 the Url-Uri dropdown (the path island's dropdown, #1842 amendment 7: navtop1_more on the browser, whose title is the address field, topbar_title (TopFirstRowTags.ISLAND) otherwise): Pages and Tools with their entries, in the browser and in the second app
set -u
APK="$1"; OUT="$2"; APK2="${3:-}"; mkdir -p "$OUT"
BPKG=com.diegonmarcos.cloudbrowser
fails=0
ok()  { echo "PASS  $1"; }
bad() { echo "::error title=browser pages::$1"; echo "FAIL  $1"; fails=$((fails + 1)); }
for _ in $(seq 1 60); do
  [ "$(timeout 10 adb get-state 2>/dev/null | tr -d '\r')" = device ] && [ "$(timeout 10 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && break
  timeout 20 adb reconnect offline >/dev/null 2>&1 || true; sleep 5
done
[ "$(timeout 10 adb get-state 2>/dev/null | tr -d '\r')" = device ] || { bad "the emulator never came online"; exit 2; }
adb install -r -g "$APK" >/dev/null || { bad "install of Cloud Browser failed"; exit 2; }
adb shell settings put global window_animation_scale 0 || true
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml 2>/dev/null > "$OUT/$1.xml" || true; }
shot() { adb exec-out screencap -p > "$OUT/$1.png" 2>/dev/null || true; dump "$1"; }
# tap the node whose resource-id (mode id) or text / content-desc (mode text) matches the regex $1
tap() {
  dump _tap
  if grep -q "install unknown apps" "$OUT/_tap.xml"; then adb shell input keyevent KEYCODE_BACK; adb shell cmd statusbar collapse >/dev/null 2>&1 || true; sleep 2; dump _tap; fi
  local xy; xy=$(python3 - "$OUT/_tap.xml" "$1" "${2:-id}" <<'PY'
import re, sys, xml.etree.ElementTree as ET
try: root = ET.parse(sys.argv[1]).getroot()
except Exception: sys.exit(0)
rx = re.compile(sys.argv[2]); byid = sys.argv[3] == "id"
for n in root.iter("node"):
    if (byid and rx.search(n.get("resource-id", ""))) or (not byid and (rx.search(n.get("text", "")) or rx.search(n.get("content-desc", "")))):
        a, b, c, d = map(int, re.findall(r"\d+", n.get("bounds", "[0,0][0,0]")))
        print((a + c) // 2, (b + d) // 2); break
PY
)
  [ -n "$xy" ] && { adb shell input tap $xy; sleep 3; return 0; }
  return 1
}
has() { grep -q "$1" "$OUT/$2.xml"; }

adb shell am start -W -n "$BPKG/.MainActivity" >/dev/null 2>&1; sleep 8
shot browser-launch

# N1 the island's order, from the x centre of each item
python3 - "$OUT/browser-launch.xml" <<'PY' > "$OUT/island-order.txt"
import re, sys, xml.etree.ElementTree as ET
xs = []
for n in ET.parse(sys.argv[1]).getroot().iter("node"):
    m = re.fullmatch(r"bottomnav_item_(\w+)", n.get("resource-id", ""))
    if m:
        a, b, c, d = map(int, re.findall(r"\d+", n.get("bounds")))
        xs.append(((a + c) // 2, m.group(1)))
print(",".join(i for _, i in sorted(xs)))
PY
[ "$(cat "$OUT/island-order.txt")" = "domains,tabs,browser,cloudy,weby" ] && ok "N1 the island reads Domains, Tabs, Browser, Cloudy, Weby" || bad "N1 island order is '$(cat "$OUT/island-order.txt")'"

# P1 each page
tap 'bottomnav_item_domains' && shot page-domains && ok "P1 Domains" || bad "P1 Domains not reachable"
tap 'bottomnav_item_tabs' && shot page-tabs && ok "P1 Tabs" || bad "P1 Tabs not reachable"
tap 'bottomnav_item_browser' && shot page-browser && ok "P1 Browser" || bad "P1 Browser not reachable"
tap 'bottomnav_item_cloudy' && sleep 5 && shot page-cloudy-webapps
has 'cloudy:webapps\|weby:grid' page-cloudy-webapps && ok "P1 Cloudy > WebApps draws Weby's grid" || bad "P1 Cloudy > WebApps has no Weby grid"
has 'weby:foldertile:WebApps\|WebApps' page-cloudy-webapps && ok "P1 the WebApps section is there" || bad "P1 no WebApps section"
# #1856 the four Cloudy subpages: WebApps (the Cloud rows), GH Pages, Local (File Explorers, Pages), C3
tap '^GH Pages$' text && sleep 6 && shot page-cloudy-gh-pages
has 'cloudy:gh_pages\|weby:grid' page-cloudy-gh-pages && ok "P1 Cloudy > GH Pages draws its grid" || bad "P1 Cloudy > GH Pages has no grid"
has "Repos' Pages\|diegonmarcos.github.io" page-cloudy-gh-pages && ok "C2 GH Pages lists the owner's pages" || bad "C2 GH Pages shows no section"
tap '^Local$' text && sleep 4 && shot page-cloudy-local
has 'cloudy:local\|weby:grid' page-cloudy-local && ok "P1 Cloudy > Local draws its grid" || bad "P1 Cloudy > Local has no grid"
has 'File Explorers' page-cloudy-local && ok "C2 Local shows File Explorers" || bad "C2 Local has no File Explorers group"
has 'cloudy:ws:open' page-cloudy-local && ok "C2 Local keeps the Open Cloud-Webserver row" || bad "C2 Local lost the Open Cloud-Webserver row"
tap '^C3$' text && sleep 4 && shot page-cloudy-c3
has 'cloudy:c3' page-cloudy-c3 && ok "P1 Cloudy > C3 is one icon grid" || bad "P1 C3 grid missing"
tap 'cloudy:c3:tile:' && sleep 2 && shot c3-chooser
# the chooser is a dialog window (no resource ids): its words
has 'Private (mesh)' c3-chooser && has 'Public' c3-chooser && ok "C1 the chooser offers the private and the public URL" || bad "C1 no chooser"
adb shell input keyevent KEYCODE_BACK; sleep 1
tap 'bottomnav_item_weby' && shot page-weby-quickmarks
has 'weby:views\|Quickmarks' page-weby-quickmarks && ok "P1 Weby > Quickmarks" || bad "P1 Weby has no Quickmarks view"
tap 'Bookmarks \(all\)' text && sleep 3 && shot page-weby-bookmarks && ok "P1 Weby > Bookmarks (all)" || bad "P1 Weby has no Bookmarks (all) view"

# S1 (#1856) the Tools sheet: one screenshot per tab, scrolled to its end (the sheet scrolls inside itself), the rows each tab must hold
tap 'bottomnav_item_browser' >/dev/null
sleep 2
# (the sheet is Compose inside the host fragment: its test tags are not resource ids, so the walk reads the icon's description and the tabs and rows by their words)
if tap 'Page-Tools' text; then
  shot tools-open
  has 'Scrapper' tools-open && ok "S1 the Tools sheet opens" || bad "S1 no Tools sheet"
  scroll_sheet() {
    local xy; xy=$(python3 -I - "$OUT/$1.xml" <<'PY'
import re, sys, xml.etree.ElementTree as ET
try: root = ET.parse(sys.argv[1]).getroot()
except Exception: sys.exit(0)
best = None
for n in root.iter("node"):
    if n.get("scrollable") == "true":
        a, b, c, d = map(int, re.findall(r"\d+", n.get("bounds")))
        if best is None or (d - b) > (best[3] - best[1]): best = (a, b, c, d)
if best:
    a, b, c, d = best; h = d - b
    print((a + c) // 2, b + h * 3 // 4, (a + c) // 2, b + h // 4)
PY
)
    [ -n "$xy" ] && adb shell input swipe ${xy% * *} ${xy#* * } 500 && sleep 1
  }
  for spec in "nav|Nav|Zoom" "scrapper|Scrapper|Readable text|Crawl this page|YouTube: download video|Print" "reading|Reading|Reader mode|Find in page" "sitedata|Site Data|Open Local Folder|Clear cookies for this site" "perms|Perms|Every site permission|Block trackers here|Block images here|Block JavaScript here" "dev|Dev Tools|View source|Console and JS REPL"; do
    IFS='|' read -r key label rows <<< "$spec"; rows=${spec#*|*|}
    tap "^$label\$" text && shot tools-$key || bad "S1 tab $label not reachable"
    cp "$OUT/tools-$key.xml" "$OUT/tools-$key-all.xml"; prev=$(md5sum < "$OUT/tools-$key.xml"); last=tools-$key
    for j in 1 2 3 4 5 6; do scroll_sheet "$last"; shot tools-$key-s$j; last=tools-$key-s$j; cat "$OUT/$last.xml" >> "$OUT/tools-$key-all.xml"; cur=$(md5sum < "$OUT/$last.xml"); [ "$cur" = "$prev" ] && break; prev=$cur; done
    has "$label" tools-$key-all && ok "S1 the tab $label is there" || bad "S1 no $label tab"
    IFS='|' read -ra want <<< "$rows"
    for r in "${want[@]}"; do
      has "$r" tools-$key-all && ok "S1 $label holds $r" || bad "S1 $label misses $r"
    done
  done
  adb shell input keyevent KEYCODE_BACK; sleep 1
else
  bad "S1 the tab row's Tools icon (Page-Tools) is not reachable"
fi

# U1 the Url-Uri dropdown, in the browser
tap 'bottomnav_item_browser' >/dev/null
tap 'navtop1_more' && shot dropdown-browser
# the dropdown is long (parents, related pages, Pages, actions, Tools, sections, Configs) and scrolls: swipe INSIDE it (a touch outside
# dismisses it), a half-height step at a time, until two dumps in a row are the same (the end) or 25 swipes; collect the tags of every dump
scroll_dropdown() {
  local xy; xy=$(python3 -I - "$OUT/$1.xml" <<'PY'
import re, sys, xml.etree.ElementTree as ET
try: root = ET.parse(sys.argv[1]).getroot()
except Exception: sys.exit(0)
for n in root.iter("node"):
    if n.get("resource-id", "") == "navtop1_dropdown":
        a, b, c, d = map(int, re.findall(r"\d+", n.get("bounds")))
        h = d - b
        print((a + c) // 2, b + h * 3 // 4, (a + c) // 2, b + h // 4); break
PY
)
  [ -n "$xy" ] && adb shell input swipe ${xy% * *} ${xy#* * } 600 && sleep 1
}
cp "$OUT/dropdown-browser.xml" "$OUT/dropdown-browser-all.xml"
prev=$(md5sum < "$OUT/dropdown-browser.xml"); last=dropdown-browser
for i in $(seq 1 25); do
  scroll_dropdown "$last"; shot dropdown-browser-s$i; last=dropdown-browser-s$i
  cat "$OUT/$last.xml" >> "$OUT/dropdown-browser-all.xml"
  cur=$(md5sum < "$OUT/$last.xml"); [ "$cur" = "$prev" ] && break; prev=$cur
done
cp "$OUT/$last.xml" "$OUT/dropdown-browser-tools.xml"; cp "$OUT/$last.png" "$OUT/dropdown-browser-tools.png" 2>/dev/null || true
for e in section:domains section:tabs section:browser section:cloudy section:weby ai slot_page:search profile configs back up home background force_close restart slot_tool:new_tab; do
  has "urluri_entry_$e" dropdown-browser-all && ok "U1 browser dropdown lists $e" || bad "U1 browser dropdown misses $e"
done
adb shell input keyevent KEYCODE_BACK

# U1 the same dropdown in another app
if [ -n "$APK2" ]; then
  adb install -r -g "$APK2" >/dev/null && { adb shell monkey -p com.diegonmarcos.cloudcalc -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 8; shot app2-launch
    tap '^topbar_title$' && shot dropdown-app2 && has 'urluri_entry_ai' dropdown-app2 && has 'urluri_entry_back' dropdown-app2 && ok "U1 the dropdown is in the second app too" || bad "U1 no Url-Uri dropdown in the second app"; } || bad "install of the second app failed"
fi
[ "$fails" -eq 0 ]
