#!/usr/bin/env bash
# emulator-me-throttled-1947 — #1947: Pers > Personal opened from a Ship APK on an emulator with a THROTTLED network (the workflow boots it with
# -netspeed / -netdelay). Screenshots at 20 s, 60 s and 120 s after the open; the page must never be drawn without its stylesheet
# (the unstyled page is default font, light text on dark grey, no space background), and the page's resource log is kept.
# Usage: emulator-me-throttled-1947.sh ME_APK OUT_DIR LABEL. Evidence only plus the pixel verdict of the last shot; exit 1 on a failed install.
set -uo pipefail
ME_APK="$1"; OUT="$2"; L="${3:-run}"; mkdir -p "$OUT"
T="timeout 120"; ME=com.diegonmarcos.cloudme; HERE="$(cd "$(dirname "$0")" && pwd)"
$T adb wait-for-device
$T adb uninstall "$ME" >/dev/null 2>&1 || true
$T adb install -r -g "$ME_APK" || { echo "install failed"; exit 1; }
$T adb shell settings put global window_animation_scale 0
$T adb shell locksettings set-disabled true; $T adb shell settings put system screen_off_timeout 1800000
$T adb shell cmd uimode night yes
$T adb shell wm size 1080x2400; $T adb shell wm density 420
$T adb shell svc power stayon true; $T adb shell input keyevent KEYCODE_WAKEUP; $T adb shell wm dismiss-keyguard
launcher() { $T adb shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER "$1" | tail -1 | tr -d '\r'; }
$T adb logcat -c
$T adb shell am start -W -n "$(launcher "$ME")" -f 0x14000000 --es shortcut_action "section:pers" >/dev/null
t0=$(date +%s)
for at in 20 60 120; do
    while [ $(( $(date +%s) - t0 )) -lt $at ]; do sleep 2; done
    $T adb exec-out screencap -p > "$OUT/throttled-$L-${at}s.png"; echo "shot throttled-$L-${at}s"
done
$T adb logcat -d > "$OUT/logcat-$L.txt"
grep -E "CloudMeWeb|chromium.*(ERR_|Console)" "$OUT/logcat-$L.txt" | cut -c1-240 > "$OUT/web-$L.txt" || true
pip install --quiet pillow >/dev/null 2>&1 || true
python3 "$HERE/render_check.py" pixels "$OUT/throttled-$L-120s.png" | tee -a "$OUT/verdict-$L.txt" || true
