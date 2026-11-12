# ─── GENERATED: do not edit — edit 1_cicd/src/scripts/emulator-speedmeter.sh ───
#!/usr/bin/env bash
# emulator-speedmeter — #1446 screenshots on a booted emulator of the SuperApp's one-hand home with the speedmeter in the
# top-left status strip: the home as it opens, then after a tap on the meter (the bounded download test).
# Exit 1 when the meter is not on the screen. Usage: emulator-speedmeter.sh APK OUT_DIR
set -uo pipefail
APK="$1"; OUT="$2"; PKG=com.diegonmarcos.superapp; mkdir -p "$OUT"
T="timeout 120"; rc=0
$T adb wait-for-device
$T adb install -r -g "$APK" || { echo "FAIL install"; exit 1; }
$T adb shell settings put global window_animation_scale 0
$T adb shell svc power stayon true; $T adb shell input keyevent KEYCODE_WAKEUP; $T adb shell wm dismiss-keyguard
$T adb logcat -c || true
dump() { $T adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; $T adb pull /sdcard/ui.xml "$OUT/$1.xml" >/dev/null 2>&1; }
shot() { sleep "${2:-4}"; $T adb exec-out screencap -p > "$OUT/speed-$1.png"; dump "speed-$1"; echo "shot speed-$1"; }
$T adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 25
dump cur
if ! grep -q "package=\"$PKG\"" "$OUT/cur.xml"; then
    $T adb shell input keyevent KEYCODE_BACK; sleep 2
    $T adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 12
fi
# the app's screensaver overlay (neon clock + a single Unlock button) can sit over the home: tap Unlock
for _ in 1 2 3; do
    dump cur
    grep -q 'text="Unlock"' "$OUT/cur.xml" || break
    b="$(python3 - "$OUT/cur.xml" <<'PY'
import re, sys
x = open(sys.argv[1], errors="ignore").read()
m = re.search(r'text="Unlock"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x)
if m: l, t, r, d = map(int, m.groups()); print((l + r) // 2, (t + d) // 2)
PY
)"
    [ -n "$b" ] && $T adb shell input tap $b; sleep 4
done
# the SuperApp's first-run install-source ask ("Install unknown apps": Skip | Allow) can sit over the home: Skip it
for _ in 1 2; do
    dump cur
    grep -q 'text="Install unknown apps"' "$OUT/cur.xml" || break
    b="$(python3 - "$OUT/cur.xml" <<'PY'
import re, sys
x = open(sys.argv[1], errors="ignore").read()
m = re.search(r'text="Skip"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x)
if m: l, t, r, d = map(int, m.groups()); print((l + r) // 2, (t + d) // 2)
PY
)"
    [ -n "$b" ] && $T adb shell input tap $b; sleep 4
done
shot home 3
grep -o 'content-desc="Speed test[^"]*"' "$OUT/speed-home.xml" | head -1 || true
p="$(python3 - "$OUT/speed-home.xml" <<'PY'
import re, sys
x = open(sys.argv[1], errors="ignore").read()
for m in re.finditer(r'<node ([^>]*)>', x):
    a = m.group(1)
    if 'content-desc="Speed test' in a:
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', a)
        l, t, r, d = map(int, b.groups()); print((l + r) // 2, (t + d) // 2); break
PY
)"
if [ -z "$p" ]; then echo "FAIL: no speedmeter in the status strip"; rc=1; else
    $T adb shell input tap $p
    shot testing 2
    shot done 10
fi
$T adb logcat -d -t 3000 > "$OUT/speed-logcat.txt"
grep -E "FATAL EXCEPTION" -A12 "$OUT/speed-logcat.txt" | grep -B2 -A12 "$PKG" | head -40 > "$OUT/speed-crash.txt" || true
[ -s "$OUT/speed-crash.txt" ] && { echo "FAIL: crash in logcat"; rc=1; }
exit $rc
