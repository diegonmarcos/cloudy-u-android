#!/usr/bin/env bash
# emulator-me-render-1937 — #1937/#1938 real-render proof on a booted emulator: Pers, Proj Home and Lnktree are opened from the Ship APK,
# screenshotted, and judged from the PIXELS (not near-uniform black) and the ui dump (on Proj Home a banner takes a bounded part of the screen
# height and the project tiles are on screen). Usage: emulator-me-render-1937.sh ME_APK OUT_DIR. Exit 1 on any failed assertion.
set -uo pipefail
ME_APK="$1"; OUT="$2"; mkdir -p "$OUT"
T="timeout 120"; ME=com.diegonmarcos.cloudme; rc=0
HERE="$(cd "$(dirname "$0")" && pwd)"

$T adb wait-for-device
$T adb install -r -g "$ME_APK" || { echo "install failed"; exit 1; }
$T adb shell settings put global window_animation_scale 0
$T adb shell locksettings set-disabled true; $T adb shell settings put system screen_off_timeout 1800000
$T adb shell cmd uimode night yes
# a phone, not the emulator default (320x640 at 160 dpi): portrait 1080x2400 at 420 dpi, as the owner's device
$T adb shell wm size 1080x2400; $T adb shell wm density 420
wake() { $T adb shell svc power stayon true; $T adb shell input keyevent KEYCODE_WAKEUP; $T adb shell wm dismiss-keyguard; }
launcher() { $T adb shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER "$1" | tail -1 | tr -d '\r'; }
open_page() { wake; $T adb shell am start -W -n "$(launcher "$ME")" -f 0x14000000 --es shortcut_action "$1" >/dev/null; }
shot() { sleep "${2:-6}"; $T adb exec-out screencap -p > "$OUT/$1.png"; rm -f "$OUT/$1.xml"; $T adb shell rm -f /sdcard/ui.xml
    for _ in 1 2 3; do $T adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; $T adb pull /sdcard/ui.xml "$OUT/$1.xml" >/dev/null 2>&1 && break; sleep 2; done; echo "shot $1"; case "$($T adb shell dumpsys window 2>/dev/null | grep -m1 mCurrentFocus || true)" in *"$ME"*) ;; *) echo "FAIL focus $1: Cloud Me is not the focused app" | tee -a "$OUT/verdict.txt"; rc=1 ;; esac; }

for op in GET_USAGE_STATS SYSTEM_ALERT_WINDOW MANAGE_EXTERNAL_STORAGE REQUEST_INSTALL_PACKAGES; do $T adb shell appops set "$ME" "$op" allow; done
open_page "page:configs/general"; sleep 12
for i in 1 2 3 4 5 6; do
    case "$($T adb shell dumpsys window 2>/dev/null | grep -m1 mCurrentFocus || true)" in *"$ME"*) break ;; esac
    $T adb shell input keyevent KEYCODE_BACK; sleep 2
done

# network reachability of the emulator itself (a page that cannot load must be told from a page that loads black)
{ $T adb shell ping -c 2 -W 3 diegonmarcos.github.io; $T adb shell ping -c 2 -W 3 linktree.diegonmarcos.com; } > "$OUT/ping.txt" 2>&1 || true
waitweb() {  # PAGE_ID: seconds until the page's main frame reports OK or an error (max 30)
    local t=0; while [ $t -lt 30 ]; do
        $T adb logcat -d -s CloudMeWeb:V | grep -qE "MAINFRAME_(OK|ERROR|HTTP) page=$1 " && break; sleep 6; t=$((t+6)); done
    echo "web $1 main-frame verdict after ${t}s: $($T adb logcat -d -s CloudMeWeb:V | grep -E "MAINFRAME_.* page=$1 " | tail -1 | cut -c1-200)" | tee -a "$OUT/web.txt"
}
$T adb logcat -c
open_page "section:pers";                 shot me-pers-early 12;      waitweb personal; shot me-pers 4
open_page "page:proj/pj-home";            shot me-proj-home 10
open_page "page:lnktree/lt-lnktree-site"; shot me-lnktree-early 12;   waitweb lt-lnktree-site; shot me-lnktree 4
open_page "page:proj/pj-unix-cloudme";    shot me-proj-cloudme 8
$T adb logcat -d -s CloudMeWeb:V > "$OUT/cloudmeweb.log"
# a SHORT pane (the page area of a smaller phone): the landpage shows its hero only when ALL of it fits the pane (IntersectionObserver 0.95), so it must be revealed
$T adb shell wm size 1080x1500
$T adb logcat -c
open_page "section:pers";                 shot me-pers-short-early 12; waitweb personal; shot me-pers-short 4
$T adb logcat -d > "$OUT/logcat.txt"
$T adb shell wm size | tr -d '\r' | tee "$OUT/wm-size.txt"

pip install --quiet pillow >/dev/null 2>&1 || true
for s in me-pers me-proj-home me-lnktree; do
    python3 "$HERE/render_check.py" pixels "$OUT/$s.png" | tee -a "$OUT/verdict.txt" || rc=1
done
python3 "$HERE/render_check.py" pixels "$OUT/me-pers-short.png" | tee -a "$OUT/verdict.txt" || rc=1
python3 "$HERE/render_check.py" projhome "$OUT/me-proj-home.png" "$OUT/me-proj-home.xml" | tee -a "$OUT/verdict.txt" || rc=1
echo "rc=$rc" | tee -a "$OUT/verdict.txt"
exit $rc
