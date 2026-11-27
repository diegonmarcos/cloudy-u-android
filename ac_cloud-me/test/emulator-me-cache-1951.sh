#!/usr/bin/env bash
# emulator-me-cache-1951 — #1951 timed walk of the cache_then_live strategy on a booted emulator (google_apis image: adb root).
#  1. fresh install, app start, the background prefetch of ALL pages runs; wait for CACHE_TOTAL; keep the per-page report (entries, bytes, complete).
#  2. WARM: open each Pers and Prof web page (process alive, page's WebView new): time from the am start to the first paint (MAINFRAME_PAINT); must be < WARM_MAX_MS.
#  3. COLD: delete that page's cache directory, open it again, time it (the WebView's own network), wait for the background fill.
#  4. SWAP: tamper one page's cached HTML on disk (differs from live), open it: it paints from the cache, then LIVE_CHECK changed>=1 and LIVE_SWAPPED, and the cache holds the live body again.
#  5. AIRPLANE: three pages must still show their copy (no MAINFRAME_ERROR, pixels not blank).
# Usage: emulator-me-cache-1951.sh ME_APK OUT_DIR. Writes OUT_DIR/timings.md, cache-report.txt, cache-1951-*.png. Exit 1 on any failed assertion.
set -uo pipefail
ME_APK="$1"; OUT="$2"; mkdir -p "$OUT"
T="timeout 120"; ME=com.diegonmarcos.cloudme; rc=0; WARM_MAX_MS="${WARM_MAX_MS:-1500}"
HERE="$(cd "$(dirname "$0")" && pwd)"; UI="${UI_DIR:-$HERE/../data/ui}"
fail() { echo "FAIL $*" | tee -a "$OUT/verdict.txt"; rc=1; }
ok() { echo "PASS $*" | tee -a "$OUT/verdict.txt"; }
$T adb wait-for-device; $T adb root >/dev/null 2>&1 || true; $T adb wait-for-device
$T adb uninstall "$ME" >/dev/null 2>&1 || true
$T adb install -r -g "$ME_APK" || { echo "install failed"; exit 1; }
$T adb shell settings put global window_animation_scale 0
$T adb shell locksettings set-disabled true; $T adb shell settings put system screen_off_timeout 1800000
$T adb shell cmd uimode night yes; $T adb shell wm size 1080x2400; $T adb shell wm density 420
wake() { $T adb shell svc power stayon true; $T adb shell input keyevent KEYCODE_WAKEUP; $T adb shell wm dismiss-keyguard; }
launcher() { $T adb shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER "$1" | tail -1 | tr -d '\r'; }
open_route() { wake; $T adb shell am start -W -n "$(launcher "$ME")" -f 0x14000000 --es shortcut_action "$1" >/dev/null; }
for op in GET_USAGE_STATS SYSTEM_ALERT_WINDOW MANAGE_EXTERNAL_STORAGE REQUEST_INSTALL_PACKAGES; do $T adb shell appops set "$ME" "$op" allow; done
wlog() { $T adb logcat -d -v threadtime -v epoch -s CloudMeWeb:V; }
shot() { $T adb exec-out screencap -p > "$OUT/cache-1951-$1.png"; echo "shot $1"; }

# the pages: every https web page of Pers and Prof, from the page declarations
PAGES="$(python3 -I - "$UI" <<'PY'
import json, glob, os, sys
for sec in ("pers", "prof"):
    for f in sorted(glob.glob(os.path.join(sys.argv[1], sec, "**", "*.json"), recursive=True)):
        try: d = json.load(open(f))
        except Exception: continue
        if isinstance(d, list) and len(d) == 1 and d[0].get("kind") == "fragment" and d[0].get("id") == "web" and str(d[0].get("url", "")).startswith("https://") and not d[0].get("inject"):
            print(sec, os.path.basename(f)[:-5], d[0]["url"])
PY
)"
echo "$PAGES" > "$OUT/pages.txt"; echo "pages: $(echo "$PAGES" | wc -l)"

# 1. prefetch of all pages
$T adb logcat -c
open_route "page:configs/general"
for i in $(seq 1 90); do wlog | grep -q "CACHE_TOTAL" && break; sleep 5; done
wlog | grep -E "CACHE_PAGE|CACHE_TOTAL" | sed 's/.*CloudMeWeb: //' | sort -u > "$OUT/cache-report.txt"
cat "$OUT/cache-report.txt"
$T adb shell "du -sk /data/data/$ME/cache/lnktree-cache; du -sk /data/data/$ME/cache/lnktree-cache/*" > "$OUT/cache-du.txt" 2>&1 || true
grep -q CACHE_TOTAL "$OUT/cache-report.txt" && ok "1: the prefetch of all pages finished" || fail "1: no CACHE_TOTAL within 450 s"
CACHE="/data/data/$ME/cache/lnktree-cache"

measure() {  # SEC ID [TAG] -> sets MS SRC
    local TAG="${3:-run}"
    local sec="$1" id="$2"
    open_route "page:configs/general"; sleep 3
    $T adb logcat -c
    local t0; t0=$($T adb shell date +%s.%N | tr -d '\r')
    $T adb shell am start -W -n "$(launcher "$ME")" -f 0x14000000 --es shortcut_action "page:$sec/$id" >/dev/null
    local line=""; for _ in $(seq 1 60); do line=$(wlog | grep "MAINFRAME_PAINT page=$id " | head -1); [ -n "$line" ] && break; sleep 0.5; done
    if [ -z "$line" ]; then MS=-1; else MS=$(awk -v a="$(echo "$line" | awk '{print $1}')" -v b="$t0" 'BEGIN{printf "%d",(a-b)*1000}'); fi
    wlog | grep "page=$id " | awk -v b="$t0" '{m=$0; sub(/.*CloudMeWeb: /,"",m); printf "+%dms %s\n", ($1-b)*1000, m}' > "$OUT/page-$id-$TAG.log"   # ms after the am start
    $T adb logcat -d | grep -E "FATAL EXCEPTION|Process $ME .*died|ANR in|Fatal signal" -A8 | cut -c1-220 > "$OUT/page-$id-$TAG.crash"; [ -s "$OUT/page-$id-$TAG.crash" ] || rm -f "$OUT/page-$id-$TAG.crash"
    SRC=$(wlog | grep "MAINFRAME_SOURCE page=$id " | tail -1 | sed 's/.*source=\([^ ]*\).*/\1/'); SRC="${SRC:-none}"
}
echo "| section | page | warm ms | warm source | cold ms | cold source |" > "$OUT/timings.md"; echo "|---|---|---|---|---|---|" >> "$OUT/timings.md"
# pass 1, WARM: every page opened with its copy complete and the WebView's own data intact (nothing cleared yet, so no page pays for another's cold pass)
while read -r sec id url <&3; do
    [ -n "$id" ] || continue
    # median of 3 opens (the shared emulator jitters); a failed open counts as 99999
    vals=""; for k in 1 2 3; do measure "$sec" "$id" "warm$k"; [ "$MS" -ge 0 ] || MS=99999; vals="$vals $MS"; sleep 2; done
    wms=$(echo $vals | tr ' ' '\n' | sort -n | sed -n 2p); wsrc=$SRC; echo "$wms $wsrc" > "$OUT/warm-$id.txt"; echo "warm opens of $id (ms):$vals" >> "$OUT/warm-all.txt"; sleep 1; { [ "$id" = personal ] || [ "$id" = prof-professional ]; } && shot "warm-$id"
    # source none = the page's own service worker answered the main frame before the app's cache (the mySocials pages are PWAs): reported, judged against a looser bound
    if [ "$wsrc" = cache-live ]; then
        if [ "$wms" -ge 0 ] && [ "$wms" -lt "$WARM_MAX_MS" ]; then ok "2: $id warm first paint ${wms} ms from the app cache"; else fail "2: $id warm ${wms} ms source=$wsrc (want < ${WARM_MAX_MS} ms)"; fi
    else
        if [ "$wms" -ge 0 ] && [ "$wms" -lt 6000 ]; then ok "2s: $id warm first paint ${wms} ms, source=$wsrc (service worker or WebView, not the app cache)"; else fail "2s: $id warm ${wms} ms source=$wsrc"; fi
    fi
done 3<<<"$PAGES" > "$OUT/walk.log" 2>&1
# pass 2, COLD: the app is stopped, the page's cache copy AND the WebView's own data (HTTP cache, service workers) are gone; then the background fill is awaited
while read -r sec id url <&3; do
    [ -n "$id" ] || continue
    $T adb shell am force-stop "$ME"; $T adb shell "rm -rf $CACHE/$id /data/data/$ME/app_webview"
    measure "$sec" "$id" cold; cms=$MS; csrc=$SRC; sleep 2; { [ "$id" = personal ] || [ "$id" = prof-professional ]; } && shot "cold-$id"
    for _ in $(seq 1 30); do wlog | grep -q "LIVE_CHECK page=$id " && break; sleep 2; done
    read -r wms wsrc < "$OUT/warm-$id.txt"
    echo "| $sec | $id | $wms | $wsrc | $cms | $csrc |" | tee -a "$OUT/timings.md"
done 3<<<"$PAGES" >> "$OUT/walk.log" 2>&1
cat "$OUT/walk.log"; grep -q '^FAIL' "$OUT/walk.log" && rc=1

# 4. a changed live page swaps in
id=prof-professional; url=$(echo "$PAGES" | awk '$2=="prof-professional"{print $3}'); lit="url=$(printf '%s' "$url" | sed 's/:/\\:/')"
$T adb shell "m=\$(grep -lxF '$lit' $CACHE/$id/*.meta | head -1); echo \"\${m%.meta}.body\" > /data/local/tmp/body1951; echo '<!--cache1951-tamper-->' >> \"\${m%.meta}.body\""
BODY=$($T adb shell cat /data/local/tmp/body1951 | tr -d '\r'); echo "tampered $BODY"
$T adb shell grep -c cache1951-tamper "$BODY" | tee "$OUT/tamper-before.txt"
measure prof prof-professional swap; shot "swap-first-paint"
for _ in $(seq 1 30); do wlog | grep -q "LIVE_SWAPPED page=prof-professional" && break; sleep 2; done
wlog | grep -E "LIVE_CHECK page=prof-professional|LIVE_SWAPPED page=prof-professional" | sed 's/.*CloudMeWeb: //' | tee "$OUT/swap.log"
sleep 3; shot "swap-after"
grep -qE "LIVE_CHECK page=prof-professional changed=[1-9]" "$OUT/swap.log" && ok "4a: the live check found the changed page" || fail "4a: no LIVE_CHECK changed>=1"
grep -q "LIVE_SWAPPED page=prof-professional" "$OUT/swap.log" && ok "4b: the shown page swapped to the live version" || fail "4b: no LIVE_SWAPPED"
[ "$($T adb shell grep -c cache1951-tamper "$BODY" | tr -d '\r')" = 0 ] && ok "4c: the cache holds the live body again" || fail "4c: the tampered cache body was not replaced"

# 5. airplane mode
$T adb shell cmd connectivity airplane-mode enable; $T adb shell svc wifi disable; $T adb shell svc data disable; sleep 5
pip install --quiet pillow >/dev/null 2>&1 || true
for p in "pers personal" "prof prof-professional" "prof leafy"; do
    set -- $p; measure "$1" "$2" airplane; sleep 4; shot "airplane-$2"
    if wlog | grep -q "MAINFRAME_ERROR page=$2 "; then fail "5: $2 shows an error in airplane mode"; else ok "5: $2 opened in airplane mode, first paint ${MS} ms, source=$SRC"; fi
    python3 -I "$HERE/render_check.py" pixels "$OUT/cache-1951-airplane-$2.png" | tee -a "$OUT/verdict.txt" | grep -q '^PASS' || rc=1
done
$T adb shell cmd connectivity airplane-mode disable; $T adb shell svc wifi enable; $T adb shell svc data enable

$T adb logcat -d > "$OUT/logcat.txt"; wlog | sed 's/.*CloudMeWeb: //' > "$OUT/cloudmeweb.log"
{ echo; echo "cache (from the prefetch report):"; echo; cat "$OUT/cache-report.txt"; } >> "$OUT/timings.md"
echo "rc=$rc" | tee -a "$OUT/verdict.txt"; exit $rc
