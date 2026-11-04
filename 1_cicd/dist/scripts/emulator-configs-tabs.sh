# ─── GENERATED: do not edit — edit 1_cicd/src/scripts/emulator-configs-tabs.sh ───
#!/usr/bin/env bash
# emulator-configs-tabs — #1443 screenshots on a booted emulator of one app's Configs screen: open Configs from the app's
# own entry (the first row's three dots; Cloud C3's top bar menu), then each of the tabs General | Perms | About, taken
# from the APK a Ship run built. Exit 1 when a tab is not on the screen (the strip must read General, Perms, About).
# Usage: emulator-configs-tabs.sh APK PACKAGE NAME OUT_DIR
set -uo pipefail
APK="$1"; PKG="$2"; NAME="$3"; OUT="$4"; mkdir -p "$OUT"
T="timeout 120"; rc=0
$T adb wait-for-device
$T adb install -r -g "$APK" || { echo "FAIL install"; exit 1; }
$T adb shell settings put global window_animation_scale 0
$T adb shell svc power stayon true; $T adb shell input keyevent KEYCODE_WAKEUP; $T adb shell wm dismiss-keyguard
$T adb logcat -c || true

dump() { $T adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; $T adb pull /sdcard/ui.xml "$OUT/$1.xml" >/dev/null 2>&1; }
shot() { sleep "${2:-4}"; $T adb exec-out screencap -p > "$OUT/$NAME-$1.png"; dump "$NAME-$1"; echo "shot $NAME-$1"; }
# tap_node XML REGEX — tap the centre of the first node whose text, content-desc or resource-id matches REGEX
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

# a system dialog over the app (Cloud Account's wireless-debugging probe): Cancel it; a start gate: continue
dismiss() {
    for _ in 1 2 3; do
        dump cur
        # Cloud Drive's start gate (no terminal installed): continue with local files
        if grep -q 'Continue with local files' "$OUT/cur.xml"; then tap_node "$OUT/cur.xml" '^Continue with local files$'; sleep 6; continue; fi
        if grep -qiE 'wireless debugging|Allow .* on this network' "$OUT/cur.xml"; then tap_node "$OUT/cur.xml" '^cancel$|^don.t allow$' || $T adb shell input keyevent KEYCODE_BACK; sleep 2; else break; fi
    done
}
$T adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 20
dismiss
# first start can show a start-up permission walk (a Settings page of another package): bring the app back on top
dump cur
if ! grep -q "package=\"$PKG\"" "$OUT/cur.xml"; then
    $T adb shell input keyevent KEYCODE_BACK; sleep 2
    $T adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 10
    dismiss
fi
shot start 2

dump cur
if tap_node "$OUT/cur.xml" 'configs'; then :; elif tap_node "$OUT/cur.xml" 'more options|settings'; then sleep 2; dump cur; tap_node "$OUT/cur.xml" '^configs$' || true; fi
dismiss
shot configs-open 6
for tab in General Perms About; do
    dismiss
    if tap_node "$OUT/cur.xml" "^$tab\$"; then shot "tab-$tab" 5; else echo "FAIL $NAME: no $tab tab on screen"; rc=1; fi
done
# the strip must read General, Perms, About in that order (first x of each label on the Configs screen)
python3 - "$OUT/$NAME-configs-open.xml" <<'PY' || rc=1
import re, sys
x = open(sys.argv[1], errors="ignore").read()
pos = {}
for lab in ("General", "Perms", "About"):
    m = re.search(r'text="%s"[^>]*bounds="\[(\d+),' % lab, x)
    pos[lab] = int(m.group(1)) if m else None
print("tab x positions:", pos)
v = [pos[k] for k in ("General", "Perms", "About")]
sys.exit(0 if None not in v and v == sorted(v) else 1)
PY
[ $? -eq 0 ] || { echo "FAIL $NAME: General | Perms | About not in that order on the Configs strip"; rc=1; }
$T adb logcat -d -t 3000 > "$OUT/$NAME-logcat.txt"
grep -E "FATAL EXCEPTION" -A12 "$OUT/$NAME-logcat.txt" | grep -B2 -A12 "$PKG" | head -40 > "$OUT/$NAME-crash.txt" || true
[ -s "$OUT/$NAME-crash.txt" ] && { echo "FAIL $NAME: crash in logcat"; rc=1; }
exit $rc
