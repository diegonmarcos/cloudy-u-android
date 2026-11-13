#!/usr/bin/env bash
# emulator-xray-perf — #1728 on a booted emulator: open Configs > X-Ray App of one fleet app, scroll to Performance, press Run
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
$T adb shell appops set "$PKG" GET_USAGE_STATS allow >/dev/null 2>&1 || true
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

# scroll to the Performance section: Run benchmark must be on screen
found=0
for i in $(seq 1 14); do
    dump cur
    if grep -q 'text="Run benchmark"' "$OUT/cur.xml"; then found=1; break; fi
    $T adb shell input swipe $((W / 2)) $((H * 7 / 10)) $((W / 2)) $((H * 3 / 10)) 400; sleep 1
done
if [ $found = 0 ]; then echo "FAIL $NAME: no Performance section (Run benchmark) on the X-Ray App page"; shot no-performance 1; exit 1; fi
shot performance-before 1
tap_node "$OUT/cur.xml" '^Run benchmark$' || { echo "FAIL $NAME: Run benchmark not tappable"; exit 1; }

# wait for the runner to finish (the status line says "Benchmark done")
done_=0
for i in $(seq 1 60); do
    sleep 5; dump cur
    if grep -q 'Benchmark done' "$OUT/cur.xml"; then done_=1; break; fi
done
if [ $done_ = 0 ]; then echo "FAIL $NAME: the benchmark did not finish in 5 minutes"; rc=1; fi
shot performance-after 2
grep -o 'Benchmark done[^"]*' "$OUT/cur.xml" | head -1 | tee "$OUT/$NAME-benchmark-status.txt"

# Export .md: the page builds the report and keeps a copy in the app's files folder
dump cur
tap_node "$OUT/cur.xml" '^Export \.md$' || { echo "FAIL $NAME: Export .md not found"; rc=1; }
sleep 3; shot performance-exported 1
F="$($T adb shell "ls -t /sdcard/Android/data/$PKG/files/xray-performance-*.md 2>/dev/null | head -1" | tr -d '\r')"
if [ -z "$F" ]; then F="$($T adb shell "run-as $PKG ls -t files/xray-performance-*.md 2>/dev/null | head -1" | tr -d '\r')"; [ -n "$F" ] && $T adb shell "run-as $PKG cat $F" > "$OUT/$NAME.md"; else $T adb pull "$F" "$OUT/$NAME.md" >/dev/null 2>&1; fi
if [ -s "$OUT/$NAME.md" ]; then echo "exported $(wc -c < "$OUT/$NAME.md") bytes to $NAME.md"; else echo "FAIL $NAME: no exported report found"; rc=1; fi
$T adb logcat -d -t 400 > "$OUT/logcat.txt" 2>/dev/null || true
exit $rc
