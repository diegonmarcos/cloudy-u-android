#!/usr/bin/env bash
# emulator-me-geo-1981 - #1981 on a booted emulator, from the Ship APK: Pers > Achieves > Geo is a container with the subpages Summary | Globe | Atlas | Collections | Stats | Timeline.
# Each subpage is opened and screenshotted (geo-1981-<page>.png): Summary (Long Stay with time buckets, Short Stay Places table), Globe (MyTrips Home), Atlas (continent switcher, the layers
# Countries | Cities | NomadMania regions, legend with counts and %), Collections (the MyTrips cards), Stats, Timeline.
# Collections (#1981): the page is asked, over the WebView DevTools socket, for its image state (test/collections-probe-1981.expr.js through webview-eval-1980.js collections): 16 cards, every card and
# overlay image loaded, none broken, none a placeholder, none from another origin, the photo credit shown in each overlay; shots geo-1981-collections-fixed*.png.
# Usage: emulator-me-geo-1981.sh ME_APK OUT_DIR WEBVIEW_EVAL_JS. Exit 1 on any failed assertion.
set -uo pipefail
ME_APK="$1"; OUT="$2"; EVAL="${3:-}"; mkdir -p "$OUT"
T="timeout 120"; ME=com.diegonmarcos.cloudme; rc=0; PORT=9222
$T adb wait-for-device; $T adb root >/dev/null 2>&1 || true; $T adb wait-for-device
$T adb uninstall "$ME" >/dev/null 2>&1 || true
$T adb install -r -g "$ME_APK" || { echo "install failed"; exit 1; }
$T adb shell settings put global window_animation_scale 0
$T adb shell locksettings set-disabled true; $T adb shell settings put system screen_off_timeout 1800000
$T adb shell cmd uimode night yes
$T adb shell touch /data/local/tmp/cloudme-webview-debug      # arms WebDebug1980 (debuggable build + marker): the page WebView answers on its DevTools socket
$T adb shell wm size 1080x2400; $T adb shell wm density 420
wake() { $T adb shell svc power stayon true; $T adb shell input keyevent KEYCODE_WAKEUP; $T adb shell wm dismiss-keyguard; }
launcher() { $T adb shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER "$1" | tail -1 | tr -d '\r'; }
open_page() { wake; $T adb shell am start -W -n "$(launcher "$ME")" -f 0x14000000 --es shortcut_action "$1" >/dev/null; }
dump() { rm -f "$OUT/$1.xml"; $T adb shell rm -f /sdcard/ui.xml
    for _ in 1 2 3; do $T adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; $T adb pull /sdcard/ui.xml "$OUT/$1.xml" >/dev/null 2>&1 && break; sleep 2; done; }
shot() { sleep "${2:-6}"; $T adb exec-out screencap -p > "$OUT/$1.png"; dump "$1"; echo "shot $1"; }
tap_text() {  # TEXT SHOT: tap the centre of the first node whose text or content-desc is TEXT in the dump of SHOT
    local xy; xy=$(python3 -I - "$OUT/$2.xml" "$1" <<'PY'
import re, sys, xml.etree.ElementTree as ET
try: nodes = list(ET.parse(sys.argv[1]).getroot().iter("node"))
except Exception: sys.exit(1)
for n in nodes:
    if (n.get("text") or n.get("content-desc") or "") == sys.argv[2]:
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds") or "")
        if m: a = list(map(int, m.groups())); print((a[0] + a[2]) // 2, (a[1] + a[3]) // 2); sys.exit(0)
sys.exit(1)
PY
    ) || { echo "FAIL no node '$1' in $2.xml" | tee -a "$OUT/verdict.txt"; return 1; }
    $T adb shell input tap $xy; echo "tapped $1 at $xy"
}
for op in GET_USAGE_STATS SYSTEM_ALERT_WINDOW MANAGE_EXTERNAL_STORAGE REQUEST_INSTALL_PACKAGES; do $T adb shell appops set "$ME" "$op" allow; done
has() { grep -q "text=\"$1\"\|content-desc=\"$1\"" "$OUT/$2.xml"; }
want() { if has "$1" "$2"; then echo "PASS $3: '$1' on screen" | tee -a "$OUT/verdict.txt"; else echo "FAIL $3: '$1' missing in $2.xml" | tee -a "$OUT/verdict.txt"; rc=1; fi; }
unwant() { if has "$1" "$2"; then echo "FAIL $3: '$1' must not be on screen" | tee -a "$OUT/verdict.txt"; rc=1; else echo "PASS $3: no '$1'" | tee -a "$OUT/verdict.txt"; fi; }
forward() {  # the WebView DevTools socket of the app process
    local sock; sock=$($T adb shell cat /proc/net/unix | tr -d '\r' | grep -o 'webview_devtools_remote_[0-9]*' | sort -u | tail -1)
    [ -n "$sock" ] || return 1
    $T adb forward "tcp:$PORT" "localabstract:$sock" >/dev/null
}
swipe_up() { $T adb shell input swipe 70 1600 70 600 600; sleep 2; }
warm() { dump "$1"; sleep 4; dump "$1"; }   # the first dump switches the WebView accessibility tree on
open_page "page:configs/general"; sleep 12
open_page "page:pers/geo-summary"; sleep 14; shot geo-1981-summary 6
for t in Summary Globe Atlas Collections Stats Timeline "Long Stay" "Cities and the time spent in each"; do want "$t" geo-1981-summary "geo subpage strip / long stay"; done
unwant "Places" geo-1981-summary "no Places ledger"; unwant "Languages" geo-1981-summary "no Languages"
sl=0; sh=0; bk=0
for i in 1 2 3 4 5 6; do
    swipe_up; shot "geo-1981-summary-$i" 3; n="geo-1981-summary-$i"
    if [ $sl = 0 ] && has "São Paulo" "$n"; then sl=1; cp "$OUT/$n.png" "$OUT/geo-1981-summary-long-stay.png"; fi
    for b in "More than 1 y" "Up to 1 y" "Up to 3 m" "Up to 1 m"; do has "$b" "$n" && bk=$((bk+1)); done
    if [ $sh = 0 ] && has "Continent / Region" "$n"; then sh=1; cp "$OUT/$n.png" "$OUT/geo-1981-summary-short-stay.png"; for t in "Cities" "NM" "Countries" "Days"; do want "$t" "$n" "places column"; done; fi
done
[ $sl = 1 ] || { echo "FAIL long stay cities never on screen" | tee -a "$OUT/verdict.txt"; rc=1; }
[ $bk -ge 2 ] && echo "PASS long stay time is a bucket text ($bk bucket cells seen)" | tee -a "$OUT/verdict.txt" || { echo "FAIL no Long Stay bucket text seen" | tee -a "$OUT/verdict.txt"; rc=1; }
[ $sh = 1 ] || { echo "FAIL Places columns never on screen" | tee -a "$OUT/verdict.txt"; rc=1; }
open_page "page:pers/geo-globe"; sleep 14; warm geo-1981-globe; shot geo-1981-globe 6
for t in Cities Countries Continents; do want "$t" geo-1981-globe "globe (MyTrips Home)"; done
open_page "page:pers/geo-atlas"; sleep 14; shot geo-1981-atlas-europe 6; warm geo-1981-atlas-europe
for t in Europe Asia Africa "North America" "South America" Oceania Countries Cities "NomadMania regions" "Cultural regions"; do want "$t" geo-1981-atlas-europe "atlas switcher / layers"; done
unwant "World" geo-1981-atlas-europe "atlas: no global view"
tap_text "Cities" geo-1981-atlas-europe && shot geo-1981-atlas-cities 5 || rc=1
warm geo-1981-atlas-cities; tap_text "NomadMania regions" geo-1981-atlas-cities && shot geo-1981-atlas-regions 5 || rc=1
warm geo-1981-atlas-regions; tap_text "Countries" geo-1981-atlas-regions && sleep 2 && tap_text "Asia" geo-1981-atlas-regions && shot geo-1981-atlas-asia 5 || rc=1
warm geo-1981-atlas-asia; tap_text "South America" geo-1981-atlas-asia && shot geo-1981-atlas-south-america 5 || rc=1
open_page "page:pers/geo-collections"; sleep 18; shot geo-1981-collections-fixed 8; warm geo-1981-collections-fixed
for t in "Curated Collections" "Financial Capitals" "Ancient History"; do want "$t" geo-1981-collections-fixed "collections card"; done
swipe_up; shot geo-1981-collections-fixed-2 4; swipe_up; shot geo-1981-collections-fixed-3 4
# the done criterion: zero broken images on the cards and in every overlay, asked of the page itself
res=""; tries=0
if [ -n "$EVAL" ]; then
    while [ $tries -lt 8 ]; do forward && res=$(timeout 200 node "$EVAL" "$PORT" "geo-collections" collections 2>>"$OUT/eval.err") && echo "$res" | grep -q '"cards"' && break; res=""; tries=$((tries+1)); sleep 8; done
fi
echo "$res" > "$OUT/collections-probe.json"
if [ -z "$res" ]; then echo "FAIL collections: no answer from the page WebView" | tee -a "$OUT/verdict.txt"; rc=1
elif echo "$res" | node -e 'const d=JSON.parse(require("fs").readFileSync(0,"utf8"));console.log(d.ok?"OK":"BAD "+d.problems.join("; "));process.exit(d.ok?0:1)' > "$OUT/collections-probe.txt"; then
    echo "PASS collections: zero broken images, same-origin photos, credit in every overlay ($(echo "$res" | node -e 'const d=JSON.parse(require("fs").readFileSync(0,"utf8"));console.log(d.cards+" cards, "+d.overlays.length+" overlays")'))" | tee -a "$OUT/verdict.txt"
else echo "FAIL collections: $(cat "$OUT/collections-probe.txt")" | tee -a "$OUT/verdict.txt"; rc=1; fi
open_page "page:pers/geo-collections"; sleep 14; warm geo-1981-collections-fixed
tap_text "Financial Capitals" geo-1981-collections-fixed && shot geo-1981-collections-fixed-overlay 8 || rc=1
warm geo-1981-collections-fixed-overlay; want "CITIES INCLUDED" geo-1981-collections-fixed-overlay "collections overlay"
if grep -q 'Photo: ' "$OUT/geo-1981-collections-fixed-overlay.xml"; then echo "PASS overlay shows the photo credit" | tee -a "$OUT/verdict.txt"; else echo "FAIL overlay shows no 'Photo: ' credit" | tee -a "$OUT/verdict.txt"; rc=1; fi
open_page "page:pers/geo-stats"; sleep 14; shot geo-1981-stats 6; warm geo-1981-stats
for t in Overview Continents Countries "NomadMania Regions"; do want "$t" geo-1981-stats "stats"; done
open_page "page:pers/geo-timeline"; sleep 14; shot geo-1981-timeline 6; warm geo-1981-timeline
for t in "All Continents" "All Countries" Reset; do want "$t" geo-1981-timeline "timeline filters"; done
$T adb logcat -d > "$OUT/logcat.txt"
grep -q "FATAL EXCEPTION" "$OUT/logcat.txt" && { echo "FAIL crash in logcat" | tee -a "$OUT/verdict.txt"; rc=1; }
echo "rc=$rc" | tee -a "$OUT/verdict.txt"
exit $rc
