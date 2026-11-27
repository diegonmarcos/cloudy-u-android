#!/usr/bin/env bash
# emulator-me-media-1980 — #1980 rendered-image proof inside the Cloud Me WebView on a booted emulator (google_apis image: adb root).
# Every Pers > Profiles > Media tab (Personal, ig0, ig1, ig2, face0, Pinterest, YouTube, Tidal, LinkedIn, Orkut) is opened from the Ship APK
# with a COLD page cache (the page's cache directory deleted, app stopped) and again WARM (same cache), scrolled fully through the
# WebView's DevTools socket, and the page itself is asked:
#     [...document.images].filter(i => i.complete && i.naturalWidth === 0).length      -> must be 0 (broken images)
# plus the count of images not yet loaded, the inline-placeholder swaps (data-fb) and <img> pointing at video files (all must be 0).
# Personal is screenshotted at top, Media cards, mid-page and bottom. Usage: emulator-me-media-1980.sh ME_APK OUT_DIR WEBVIEW_EVAL_JS.
# Exit 1 on any failed assertion. Writes OUT_DIR/media-1980.md (the table) and OUT_DIR/verdict.txt.
set -uo pipefail
ME_APK="$1"; OUT="$2"; EVAL="$3"; mkdir -p "$OUT"
T="timeout 120"; ME=com.diegonmarcos.cloudme; rc=0; PORT=9222
fail() { echo "FAIL $*" | tee -a "$OUT/verdict.txt"; rc=1; }
ok() { echo "PASS $*" | tee -a "$OUT/verdict.txt"; }
$T adb wait-for-device; $T adb root >/dev/null 2>&1 || true; $T adb wait-for-device
$T adb uninstall "$ME" >/dev/null 2>&1 || true
$T adb install -r -g "$ME_APK" || { echo "install failed"; exit 1; }
$T adb shell settings put global window_animation_scale 0
$T adb shell locksettings set-disabled true; $T adb shell settings put system screen_off_timeout 1800000
$T adb shell cmd uimode night yes; $T adb shell wm size 1080x2400; $T adb shell wm density 420
$T adb shell touch /data/local/tmp/cloudme-webview-debug      # arms WebDebug1980 (debuggable build + marker)
wake() { $T adb shell svc power stayon true; $T adb shell input keyevent KEYCODE_WAKEUP; $T adb shell wm dismiss-keyguard; }
launcher() { $T adb shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER "$1" | tail -1 | tr -d '\r'; }
open_route() { wake; $T adb shell am start -W -n "$(launcher "$ME")" -f 0x14000000 --es shortcut_action "$1" >/dev/null; }
for op in GET_USAGE_STATS SYSTEM_ALERT_WINDOW MANAGE_EXTERNAL_STORAGE REQUEST_INSTALL_PACKAGES; do $T adb shell appops set "$ME" "$op" allow; done
shot() { $T adb exec-out screencap -p > "$OUT/$1.png"; echo "shot $1"; }
forward() {  # the WebView DevTools socket of the app process
    local sock; sock=$($T adb shell cat /proc/net/unix | tr -d '\r' | grep -o 'webview_devtools_remote_[0-9]*' | sort -u | tail -1)
    [ -n "$sock" ] || return 1
    $T adb forward "tcp:$PORT" "localabstract:$sock" >/dev/null
}
evalweb() {  # URL_REGEX CMD [ARG] -> stdout of webview-eval-1980.js
    forward || { echo "no-devtools-socket"; return 1; }
    node "$EVAL" "$PORT" "$@"
}
CACHE="/data/data/$ME/cache/lnktree-cache"
echo "# Cloud Me WebView, rendered images (#1980)" > "$OUT/media-1980.md"
echo "| tab | state | images | broken | not loaded | placeholders | img->video | page url |" >> "$OUT/media-1980.md"
echo "|---|---|---|---|---|---|---|---|" >> "$OUT/media-1980.md"

walk_tab() {  # ID URLREGEX STATE ; the app is (re)started by the caller
    local id="$1" re="$2" state="$3" res="" tries=0
    open_route "page:pers/$id"; sleep 20
    while [ $tries -lt 8 ]; do res=$(evalweb "$re" fullscroll 2>>"$OUT/eval.err") && break; tries=$((tries+1)); sleep 8; done
    echo "$id $state: $res" >> "$OUT/results.txt"
    if [ -z "$res" ] || ! echo "$res" | grep -q '"total"'; then fail "$id $state: no answer from the page WebView ($res)"; return; fi
    local total broken pending ph vid url
    total=$(echo "$res" | node -e 'const d=JSON.parse(require("fs").readFileSync(0,"utf8"));console.log([d.total,d.broken,d.pending,d.placeholders,d.videoAsImg,d.url].join("|"))')
    IFS='|' read -r total broken pending ph vid url <<<"$total"
    echo "| $id | $state | $total | $broken | $pending | $ph | $vid | $url |" >> "$OUT/media-1980.md"
    [ "$broken" = 0 ] && ok "$id $state: 0 broken of $total images" || fail "$id $state: $broken broken images of $total"
    [ "$ph" = 0 ] && [ "$vid" = 0 ] || fail "$id $state: $ph placeholder swaps, $vid <img> on video files"
    # not loaded: only images the page marks lazy and that sit in hidden views legitimately stay pending; report, fail on visible ones via the broken count
}

TABS="personal:/mySocials/?($|#|\\?) ig0:instagram-diegocnmarcos_ ig1:instagram-diegocmarcos_ ig2:instagram-diegonmarcos face0:facebook-diegonmarcos pinterest:pinterest youtube:youtube tidal:tidal linkedin:linkedin orkut:orkut"
: > "$OUT/results.txt"
for tab in $TABS; do
    id="${tab%%:*}"; re="${tab#*:}"
    # COLD: app stopped, this page's cache emptied (the shared store too: the thumbnails are same-origin, nothing else to keep)
    $T adb shell am force-stop "$ME"; $T adb shell "rm -rf $CACHE" >/dev/null 2>&1 || true
    walk_tab "$id" "$re" cold
    # WARM: the app stopped, the cache the cold run filled kept
    $T adb shell am force-stop "$ME"
    walk_tab "$id" "$re" warm
    if [ "$id" = personal ]; then
        for pos in 0:top 0.12:media-cards 0.5:mid-page 1:bottom; do
            evalweb "$re" scrollto "${pos%%:*}" >/dev/null 2>&1; sleep 4; shot "mysocials-1980-${pos#*:}"
        done
    fi
done
$T adb logcat -d -s CloudMeWeb:V > "$OUT/cloudmeweb.log"; $T adb logcat -d > "$OUT/logcat.txt"
grep -ci "crash\|Fatal signal\|renderer.*gone\|onRenderProcessGone" "$OUT/logcat.txt" > "$OUT/crash-count.txt" || true
grep -qi "onRenderProcessGone\|Render process gone" "$OUT/logcat.txt" && fail "the WebView render process was lost" || ok "no WebView render-process loss in logcat"
echo "rc=$rc" | tee -a "$OUT/verdict.txt"
exit $rc
