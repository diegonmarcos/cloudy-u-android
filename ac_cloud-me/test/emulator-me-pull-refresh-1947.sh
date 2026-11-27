#!/usr/bin/env bash
# emulator-me-pull-refresh-1947 — #1947: Pers > Profiles > Personal (declared https://diegonmarcos.github.io/mySocials/) from a Ship APK on an emulator.
#  a. the page draws styled, and its main frame came from the NETWORK (CloudMeWeb MAINFRAME_SOURCE source=network);
#  b. a swipe down from the page's top is a hard refresh: MAINFRAME_OK appears again, the page's LnktreeCache files were deleted and refetched (fetchedAt moves forward);
#  c. scrolled down, swipes up and down in the middle of the page reload NOTHING.
# Screenshots before / after the refresh go to OUT. Usage: emulator-me-pull-refresh-1947.sh ME_APK OUT_DIR. Exit 1 on a failed assertion.
set -uo pipefail
ME_APK="$1"; OUT="$2"; mkdir -p "$OUT"
T="timeout 120"; ME=com.diegonmarcos.cloudme; rc=0; HERE="$(cd "$(dirname "$0")" && pwd)"
fail() { echo "FAIL $*" | tee -a "$OUT/verdict.txt"; rc=1; }
ok() { echo "PASS $*" | tee -a "$OUT/verdict.txt"; }
$T adb wait-for-device
$T adb install -r -g "$ME_APK" || { echo "install failed"; exit 1; }
$T adb shell settings put global window_animation_scale 0
$T adb shell locksettings set-disabled true; $T adb shell settings put system screen_off_timeout 1800000
$T adb shell cmd uimode night yes
$T adb shell wm size 1080x2400; $T adb shell wm density 420
for op in GET_USAGE_STATS SYSTEM_ALERT_WINDOW MANAGE_EXTERNAL_STORAGE REQUEST_INSTALL_PACKAGES; do $T adb shell appops set "$ME" "$op" allow; done
launcher() { $T adb shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER "$1" | tail -1 | tr -d '\r'; }
$T adb shell svc power stayon true; $T adb shell input keyevent KEYCODE_WAKEUP; $T adb shell wm dismiss-keyguard
shot() { $T adb exec-out screencap -p > "$OUT/$1.png"; echo "shot $1"; }
n() { $T adb logcat -d -s CloudMeWeb:V | grep -cE "$1" || true; }
okc() { n "MAINFRAME_OK page=personal "; }
srcc() { n "MAINFRAME_SOURCE page=personal source=network"; }
waitok() {  # WANT: seconds until MAINFRAME_OK page=personal count reaches WANT (max 60)
    local t=0; while [ $t -lt 60 ] && [ "$(okc)" -lt "$1" ]; do sleep 3; t=$((t+3)); done; echo "waited ${t}s for MAINFRAME_OK #$1"; }
# the page's cache meta (needs root: the emulator image is google_apis)
$T adb root >/dev/null 2>&1 || true; sleep 3
fetched() {  # fetchedAt of the page's main HTML entry (the meta whose url is the bare origin)
    $T adb shell "cd /data/data/$ME/cache/lnktree-cache/personal 2>/dev/null && for f in *.meta; do if grep -qE '^url=https.?://diegonmarcos.github.io/mySocials/?\$' \$f; then grep '^fetchedAt=' \$f; fi; done" | tr -d '\r' | head -1 | cut -d= -f2; }

$T adb logcat -c
$T adb shell am start -W -n "$(launcher "$ME")" -f 0x14000000 --es shortcut_action "section:pers" >/dev/null
waitok 1; sleep 8
shot pull-refresh-before
python3 -c 1 && pip install --quiet pillow >/dev/null 2>&1 || true
python3 "$HERE/render_check.py" pixels "$OUT/pull-refresh-before.png" | tee -a "$OUT/verdict.txt" || rc=1   # (a) styled
[ "$(srcc)" -ge 1 ] && ok "a2: the Personal main frame came from the network" || fail "a2: no MAINFRAME_SOURCE source=network for personal"
$T adb logcat -d -s CloudMeWeb:V | grep MAINFRAME_SOURCE | tail -3 | tee -a "$OUT/verdict.txt"
$T adb shell "ls -la /data/data/$ME/cache/lnktree-cache/personal 2>&1 | head -20" | tee -a "$OUT/cachedir.txt"
ok0=$(okc); src0=$(srcc); f0=$(fetched); echo "before: ok=$ok0 network-sources=$src0 fetchedAt=$f0" | tee -a "$OUT/verdict.txt"
dump() { $T adb shell "cd /data/data/$ME/cache/lnktree-cache/personal 2>/dev/null && for f in *.meta; do echo \$f; cat \$f; wc -c \${f%.meta}.body; done" | tr -d '\r' >> "$OUT/cachedir.txt"; }
echo "== before the pull" >> "$OUT/cachedir.txt"; dump
T0=$($T adb shell date +%s%3N | tr -d '\r')

# (b) pull down from the page's top
$T adb shell input swipe 540 700 540 1900 500
waitok $((ok0+1)); sleep 6
shot pull-refresh-after
python3 "$HERE/render_check.py" pixels "$OUT/pull-refresh-after.png" | tee -a "$OUT/verdict.txt" || rc=1
[ "$(okc)" -gt "$ok0" ] && ok "b1: MAINFRAME_OK again after the pull ($ok0 -> $(okc))" || fail "b1: no fresh main-frame load after the pull"
echo "b2 (info): main-frame sources from the network $src0 -> $(srcc) (a reload may be answered by the page's own service worker)" | tee -a "$OUT/verdict.txt"
sleep 15; f1=$(fetched); echo "after: fetchedAt=$f1" | tee -a "$OUT/verdict.txt"
echo "== after the pull" >> "$OUT/cachedir.txt"; dump
# the entry was written AFTER the swipe: the old files were deleted by the hard refresh and the page was fetched again
[ -n "$f1" ] && [ "$f1" -gt "$T0" ] && ok "b3: the page's cache files were deleted and refetched (before ${f0:-none}; swipe at $T0; entry fetchedAt $f1)" || fail "b3: no entry written after the pull (before ${f0:-none}; swipe at $T0; now ${f1:-none})"

# (c) scroll down, then swipe up and down mid-page: NO reload
ok1=$(okc)
for _ in 1 2 3; do $T adb shell input swipe 540 1900 540 500 400; sleep 1; done
$T adb shell input swipe 540 1500 540 900 400; sleep 1
$T adb shell input swipe 540 900 540 1500 400; sleep 1
$T adb shell input swipe 540 1000 540 1600 400; sleep 1
shot pull-refresh-midpage
sleep 10
[ "$(okc)" -eq "$ok1" ] && ok "c: no reload while scrolling mid-page ($ok1 MAINFRAME_OK)" || fail "c: a mid-page swipe reloaded the page ($ok1 -> $(okc))"
$T adb logcat -d > "$OUT/logcat.txt"; $T adb logcat -d -s CloudMeWeb:V > "$OUT/cloudmeweb.log"
echo "rc=$rc" | tee -a "$OUT/verdict.txt"
exit $rc
