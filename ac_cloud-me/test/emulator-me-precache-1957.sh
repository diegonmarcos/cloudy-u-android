#!/usr/bin/env bash
# emulator-me-precache-1957 — #1957 zero-tap precache walk on a booted emulator (google_apis image: adb root).
#  0. REPRO (optional OLD_APK): the previous app opens Personal online; its IMG_CHECK / IMG_BROKEN / SUBRES_* lines are kept (the broken URLs and why).
#  1. fresh install of ME_APK, launch, touch NOTHING; wait for CACHE_TOTAL with every page complete (the startup job, cross-origin media included).
#  2. AGE: one page's copy is aged past ttl_days (its main entry's fetchedAt on disk), the app restarts: only that page is fetched again
#     (CACHE_FETCH action=refetch result=replaced), every other page is action=skip.
#  3. AIRPLANE: Personal opens with 0 broken images (IMG_CHECK from inside the WebView) and its WebGL banner; three other pages open offline.
# Usage: emulator-me-precache-1957.sh ME_APK OUT_DIR [OLD_APK]. Screenshots OUT_DIR/precache-1957-*.png. Exit 1 on any failed assertion.
set -uo pipefail
ME_APK="$1"; OUT="$2"; OLD_APK="${3:-}"; mkdir -p "$OUT"
T="timeout 120"; ME=com.diegonmarcos.cloudme; rc=0; WAIT_S="${WAIT_S:-2700}"
HERE="$(cd "$(dirname "$0")" && pwd)"; UI="${UI_DIR:-$HERE/../data/ui}"; CACHE="/data/data/$ME/cache/lnktree-cache"
fail() { echo "FAIL $*" | tee -a "$OUT/verdict.txt"; rc=1; }
ok() { echo "PASS $*" | tee -a "$OUT/verdict.txt"; }
$T adb wait-for-device; $T adb root >/dev/null 2>&1 || true; $T adb wait-for-device
$T adb shell settings put global window_animation_scale 0
$T adb shell locksettings set-disabled true; $T adb shell settings put system screen_off_timeout 1800000
$T adb shell cmd uimode night yes; $T adb shell wm size 1080x2400; $T adb shell wm density 420
wake() { $T adb shell svc power stayon true; $T adb shell input keyevent KEYCODE_WAKEUP; $T adb shell wm dismiss-keyguard; }
launcher() { $T adb shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER "$1" | tail -1 | tr -d '\r'; }
launch() { wake; $T adb shell am start -W -n "$(launcher "$ME")" >/dev/null; }
open_route() { wake; $T adb shell am start -W -n "$(launcher "$ME")" -f 0x14000000 --es shortcut_action "$1" >/dev/null; }
perms() { for op in GET_USAGE_STATS SYSTEM_ALERT_WINDOW MANAGE_EXTERNAL_STORAGE REQUEST_INSTALL_PACKAGES; do $T adb shell appops set "$ME" "$op" allow; done; }
# a FILTERED logcat written ON THE DEVICE (a host-side background `adb logcat` reader plus buffer resizing and clearing segfaulted the adb
# client, exit 139); wlog reads it (small: the app's tag, crashes, process deaths) and starts after the last WALK_MARK
LIVE_DEV=/data/local/tmp/cm-live.txt; LIVE="$OUT/logcat-live.txt"
$T adb shell "rm -f $LIVE_DEV; nohup logcat -v threadtime -f $LIVE_DEV -s CloudMeWeb:V AndroidRuntime:E ActivityManager:I WorkManager:V >/dev/null 2>&1 &"
liveall() { $T adb shell cat "$LIVE_DEV" | tr -d '\r'; }
wlog() { liveall | awk '/CloudMeWeb: WALK_MARK/{buf=""; next} {buf=buf $0 "\n"} END{printf "%s", buf}' | grep -a ' CloudMeWeb: ' | sed 's/.*CloudMeWeb: //'; }
clear_log() { $T adb shell log -t CloudMeWeb WALK_MARK; sleep 1; }
shot() { $T adb exec-out screencap -p > "$OUT/precache-1957-$1.png"; echo "shot $1"; }
img_wait() { for _ in $(seq 1 40); do wlog | grep -q "IMG_CHECK page=$1 " && return 0; sleep 2; done; return 1; }
pip install --quiet pillow >/dev/null 2>&1 || true

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

# 0. reproduction with the previous app (the phone's state): Personal online, the broken images by URL and the request errors behind them
if [ -n "$OLD_APK" ] && [ -f "$OLD_APK" ]; then
    $T adb uninstall "$ME" >/dev/null 2>&1 || true; $T adb install -r -g "$OLD_APK" && perms
    clear_log; open_route "page:pers/personal"; img_wait personal; sleep 4; shot repro-old-personal
    wlog | grep -E "IMG_CHECK|IMG_BROKEN|SUBRES_|MAINFRAME_SOURCE|SW_" | grep "page=personal\|SW_" | tee "$OUT/repro-old-personal.txt"
    $T adb logcat -d | grep -iE "chromium.*(console|Uncaught)|hero3d|img-fallback" | tail -60 > "$OUT/repro-old-console.txt"
fi

# 1. fresh install, launch, touch nothing
$T adb uninstall "$ME" >/dev/null 2>&1 || true
$T adb install -r -g "$ME_APK" || { echo "install failed"; exit 1; }
perms; clear_log
t0=$(date +%s); launch
NP=$(echo "$PAGES" | grep -c .)
for _ in $(seq 1 $((WAIT_S / 10))); do wlog | grep -q "^CACHE_TOTAL" && break; wlog | grep -E "^CACHE_(PROGRESS|FETCH)" | tail -1; liveall | grep -a -E "FATAL EXCEPTION|ANR in|Process com.diegonmarcos.cloudme .*died" | tail -2; liveall > "$LIVE"; sleep 10; done
liveall | grep -a -E "FATAL EXCEPTION|ANR in|has died|OutOfMemory" -A12 | cut -c1-240 > "$OUT/crashes.txt"; [ -s "$OUT/crashes.txt" ] && { echo "app crashes:"; head -40 "$OUT/crashes.txt"; }
el=$(( $(date +%s) - t0 ))
wlog | grep -E "^CACHE_(PAGE|TOTAL|FETCH|HARVEST|VERSION|PROGRESS)" > "$OUT/cache-report.txt"
$T adb shell "du -sk $CACHE; du -sk $CACHE/*" > "$OUT/cache-du.txt" 2>&1 || true
TOT=$(grep "^CACHE_TOTAL" "$OUT/cache-report.txt" | tail -1); echo "after ${el}s: $TOT" | tee "$OUT/total.txt"; cat "$OUT/cache-du.txt"
P=$(echo "$TOT" | sed -n 's/.*pages=\([0-9]*\).*/\1/p'); C=$(echo "$TOT" | sed -n 's/.* complete=\([0-9]*\).*/\1/p')
B=$(echo "$TOT" | sed -n 's/.* bytes=\([0-9]*\) .*/\1/p'); MAXB=$((512 * 1024 * 1024)); [ -n "$B" ] && [ "$B" -lt "$MAXB" ] && ok "1d: the cache is under max_cache_mb: $B bytes" || fail "1d: cache bytes ${B:-?} not under $MAXB"
[ -n "$P" ] && [ "$P" -gt 0 ] && [ "$P" = "$C" ] && ok "1: zero taps, every page complete on disk after ${el}s: $TOT" || fail "1: not every page complete: ${TOT:-no CACHE_TOTAL in ${WAIT_S}s}; incomplete: $(grep '^CACHE_PAGE.*complete=no' "$OUT/cache-report.txt" | tr '\n' ';')"
grep -q "^CACHE_FETCH page=personal action=fill result=complete" "$OUT/cache-report.txt" && ok "1b: Personal filled by the startup job, never opened" || fail "1b: no complete startup fill of Personal"
$T adb shell "for m in $CACHE/personal/*.meta; do sed -n 's/^url=//p' \$m; done" | tr -d '\r' | sed 's/\\:/:/g' > "$OUT/personal-urls.txt"
X=$(grep -vc '^https://diegonmarcos.github.io' "$OUT/personal-urls.txt"); echo "personal: $(wc -l < "$OUT/personal-urls.txt") files, $X cross-origin"
[ "$X" -gt 0 ] && ok "1c: Personal's copy holds $X cross-origin files" || fail "1c: no cross-origin file in Personal's copy"

# 2. age one page past ttl_days; restart; only it is refetched
AGED=$(echo "$PAGES" | awk '$2!="personal"{print $2; exit}'); AURL=$(echo "$PAGES" | awk -v a="$AGED" '$2==a{print $3}')
lit="url=$(printf '%s' "$AURL" | sed 's/:/\\:/')"
old=$(( ($(date +%s) - 8 * 86400) * 1000 ))
$T adb shell "m=\$(grep -lxF '$lit' $CACHE/$AGED/*.meta | head -1); sed -i 's/^fetchedAt=.*/fetchedAt=$old/' \"\$m\"; grep fetchedAt \"\$m\"" | tee "$OUT/aged.txt"
$T adb shell am force-stop "$ME"; clear_log; launch
for _ in $(seq 1 60); do wlog | grep -q "^CACHE_TOTAL" && break; sleep 5; done
wlog | grep -E "^CACHE_FETCH|^CACHE_TOTAL" | tee "$OUT/restart-fetch.txt"
grep -q "^CACHE_FETCH page=$AGED action=refetch result=replaced" "$OUT/restart-fetch.txt" && ok "2a: the aged page $AGED was refetched and replaced" || fail "2a: $AGED not refetched+replaced"
NS=$(grep -c "^CACHE_FETCH .*action=skip" "$OUT/restart-fetch.txt"); NO=$(grep "^CACHE_FETCH" "$OUT/restart-fetch.txt" | grep -v "page=$AGED " | grep -vc "action=skip")
[ "$NO" = 0 ] && [ "$NS" -gt 0 ] && ok "2b: every other page ($NS) was skipped (fresh, complete)" || fail "2b: $NO other page(s) were fetched again"

# 3. airplane mode: Personal with every image, then three other pages
$T adb shell cmd connectivity airplane-mode enable; $T adb shell svc wifi disable; $T adb shell svc data disable; sleep 5
clear_log; open_route "page:pers/personal"; img_wait personal; sleep 3; shot airplane-personal
wlog | grep -E "page=personal |SW_REQUEST" > "$OUT/airplane-personal.txt"; L=$(grep "IMG_CHECK page=personal " "$OUT/airplane-personal.txt" | tail -1); echo "$L"
grep -E "IMG_BROKEN|SUBRES_" "$OUT/airplane-personal.txt" | head -40
echo "$L" | grep -qE 'images_broken="[0-9]+ 0 ' && ok "3a: Personal offline, 0 broken images: $L" || fail "3a: Personal offline has broken images: $L"
echo "$L" | grep -q "webgl=true" && echo "$L" | grep -qE "hero=[1-9][0-9]*x[1-9]" && ok "3b: the WebGL banner canvas is drawn ($L)" || fail "3b: WebGL banner missing: $L"
grep -q "MAINFRAME_SOURCE page=personal source=cache" "$OUT/airplane-personal.txt" && ok "3c: Personal's main frame came from the app cache" || fail "3c: Personal's main frame not from the cache"
python3 -I "$HERE/render_check.py" pixels "$OUT/precache-1957-airplane-personal.png" | tee -a "$OUT/verdict.txt" | grep -q '^PASS' || rc=1
for id in $(echo "$PAGES" | awk '$2!="personal"{print $2}' | head -3); do
    sec=$(echo "$PAGES" | awk -v a="$id" '$2==a{print $1}')
    clear_log; open_route "page:$sec/$id"; img_wait "$id"; sleep 2; shot "airplane-$id"
    wlog | grep "page=$id " > "$OUT/airplane-$id.txt"
    if grep -q "MAINFRAME_ERROR page=$id " "$OUT/airplane-$id.txt"; then fail "3d: $id shows an error offline"; else ok "3d: $id opened offline: $(grep IMG_CHECK "$OUT/airplane-$id.txt" | tail -1)"; fi
    python3 -I "$HERE/render_check.py" pixels "$OUT/precache-1957-airplane-$id.png" | tee -a "$OUT/verdict.txt" | grep -q '^PASS' || rc=1
done
$T adb shell cmd connectivity airplane-mode disable; $T adb shell svc wifi enable; $T adb shell svc data enable
liveall > "$LIVE"
echo "rc=$rc" | tee -a "$OUT/verdict.txt"; exit $rc
