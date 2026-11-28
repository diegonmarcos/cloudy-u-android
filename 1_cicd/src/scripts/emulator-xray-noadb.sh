#!/usr/bin/env bash
# emulator-xray-noadb — #1985 (derived from emulator-xray-perf, #1728) on a booted emulator: open Configs > X-Ray App of one fleet app, scroll to Performance, press Run
# benchmark, wait for it to finish, press Export .md, and pull the report the page keeps in the app's files folder.
# Usage: emulator-xray-perf.sh APK PACKAGE NAME OUT_DIR
# Exit 1 when the Performance section, the button, the finished benchmark or the exported file cannot be found.
set -uo pipefail
APK="$1"; PKG="$2"; NAME="$3"; OUT="$4"; mkdir -p "$OUT"
T="timeout 120"; rc=0
$T adb wait-for-device
$T adb install -r -g "$APK" || { echo "FAIL install"; exit 1; }
$T adb shell settings put global window_animation_scale 0
$T adb shell svc power stayon true; $T adb shell input keyevent KEYCODE_WAKEUP; $T adb shell wm dismiss-keyguard
# benchmarks read shared storage and installed apps: grant what a phone owner would, and leave sample files to hash and walk
$T adb shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow >/dev/null 2>&1 || true
$T adb shell "mkdir -p /sdcard/Download/xray-sample /sdcard/DCIM/Camera; for i in 1 2 3 4 5 6 7 8; do head -c 1048576 /dev/urandom > /sdcard/Download/xray-sample/sample\$i.bin; done; head -c 600000 /dev/urandom > /sdcard/DCIM/Camera/sample.jpg" >/dev/null 2>&1 || true
$T adb logcat -c || true

dump() { $T adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; $T adb pull /sdcard/ui.xml "$OUT/$1.xml" >/dev/null 2>&1; }
shot() { sleep "${2:-3}"; $T adb exec-out screencap -p > "$OUT/$NAME-$1.png"; dump "$NAME-$1"; echo "shot $NAME-$1"; }
tap_node() {
    local p; p="$(python3 - "$1" "$2" <<'PY'
import re, sys
x = open(sys.argv[1], errors="ignore").read(); rx = re.compile(sys.argv[2], re.I)
for m in re.finditer(r'<node ([^>]*)>', x):
    a = m.group(1)
    g = lambda k: (re.search(k + r'="([^"]*)"', a) or [None, ""])[1]
    if any(rx.search(g(k)) for k in ("text", "content-desc", "resource-id")):
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', a)
        if b:
            l, t, r, d = map(int, b.groups())
            if r > l and d > t: print((l + r) // 2, (t + d) // 2); break
PY
)"
    [ -n "$p" ] || return 1
    $T adb shell input tap $p; return 0
}
dismiss() {
    for _ in 1 2 3; do
        dump cur
        if grep -q 'Continue with local files' "$OUT/cur.xml"; then tap_node "$OUT/cur.xml" '^Continue with local files$'; sleep 6; continue; fi
        # Cloud SuperApp's own lock screen: Unlock (no secret is set on a fresh emulator)
        if grep -q 'text="Unlock"' "$OUT/cur.xml" && tap_node "$OUT/cur.xml" '^Unlock$'; then sleep 4; continue; fi
        if grep -q 'text="Get Started"' "$OUT/cur.xml" && tap_node "$OUT/cur.xml" '^Get Started$'; then sleep 5; continue; fi
        if grep -qiE 'access all device logs' "$OUT/cur.xml" && tap_node "$OUT/cur.xml" '^don.t allow$'; then sleep 2; continue; fi
        if grep -q 'text="Install unknown apps"' "$OUT/cur.xml" && tap_node "$OUT/cur.xml" '^Skip$'; then sleep 3; continue; fi
        if grep -qiE 'allowed to install unknown apps' "$OUT/cur.xml"; then tap_node "$OUT/cur.xml" '^cancel$' || $T adb shell input keyevent KEYCODE_BACK; sleep 2; continue; fi
        if grep -qiE 'wireless debugging|Allow .* on this network' "$OUT/cur.xml"; then tap_node "$OUT/cur.xml" '^cancel$|^don.t allow$' || $T adb shell input keyevent KEYCODE_BACK; sleep 2; else break; fi
    done
}

# start the app once (first-run gates, the Application's own initialisers), then open the X-Ray App page by its Configs activity
$T adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 20; dismiss; dismiss
shot start 2
$T adb root >/dev/null 2>&1; sleep 3; $T adb wait-for-device
$T adb shell am start -n "$PKG/com.diegonmarcos.superapp.fleetconfigs.FleetConfigsActivity" --es fleet_configs_page xray >/dev/null 2>&1
sleep 6; dismiss
shot xray-open 4
W="$($T adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1 | cut -dx -f1)"; H="$($T adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1 | cut -dx -f2)"
W="${W:-1080}"; H="${H:-2400}"

# #1985: Configs > X-Ray > Export All with NO adb: answer the ADB prompt with "Export partial", then pull the files.
scroll_to() {
    for i in $(seq 1 70); do
        dump cur
        if grep -qE "text=\"$1" "$OUT/cur.xml"; then return 0; fi
        $T adb shell input swipe $((W / 2)) $((H * 8 / 10)) $((W / 2)) $((H * 2 / 10)) 250; sleep 0.6
    done
    return 1
}
export_one() { # label-regex, scroll-text, tag
    scroll_to "$2" || { echo "FAIL $NAME: no '$2' button"; rc=1; return; }
    tap_node "$OUT/cur.xml" "$1" || { echo "FAIL $NAME: '$1' not tappable"; rc=1; return; }
    sleep 3; dump cur
    if grep -q 'Export partial' "$OUT/cur.xml"; then shot "$3-prompt" 1; tap_node "$OUT/cur.xml" '^Export partial$'; fi
    ok=0
    for i in $(seq 1 40); do sleep 4; dump cur; if grep -qE 'text="Saved ' "$OUT/cur.xml"; then ok=1; break; fi; done
    shot "$3-done" 1
    [ $ok = 1 ] || { echo "FAIL $NAME: $3 never reported Saved"; rc=1; }
    grep -oE 'Saved [^"]*' "$OUT/cur.xml" | head -1 | tee -a "$OUT/$NAME-status.txt"
}
export_one '^Export Markdown for AI' 'Export Markdown for AI' ai
export_one '^Export Markdown file' 'Export Markdown file' verbose
mkdir -p "$OUT/files"
for f in $($T adb shell "ls /sdcard/Download/xray/ 2>/dev/null" | tr -d '\r'); do $T adb pull "/sdcard/Download/xray/$f" "$OUT/files/$f" >/dev/null 2>&1; done
ls -l "$OUT/files" || true
[ -n "$(ls "$OUT"/files/*.ai.md 2>/dev/null)" ] || { echo "FAIL $NAME: no .ai.md exported"; rc=1; }
$T adb logcat -d -t 400 > "$OUT/logcat.txt" 2>/dev/null || true
exit $rc
