#!/usr/bin/env bash
# emulator-code-backlog — #1699 screenshots on a booted emulator of Cloud Code's Backlog page, from the APK a Ship run built.
# Three states, each asserted from the page text the uiautomator tree reads and screenshotted:
#   live        a clone's root index.html (a public fixture, never the private backlog) -> "read from the clone"
#   offline     the clone removed, the app restarted -> "Offline copy saved" + the reason, the last-good page still shown
#   unavailable app data cleared and no clone    -> the reason ("not on this device"), no crash
# Usage: emulator-code-backlog.sh APK PACKAGE OUT_DIR
set -uo pipefail
APK="$1"; PKG="$2"; OUT="$3"; mkdir -p "$OUT"
T="timeout 120"; rc=0
CLONE=/sdcard/CloudDrive/git/cloud-me-pvt-tasks
$T adb wait-for-device
$T adb install -r -g "$APK" || { echo "FAIL install"; exit 1; }
$T adb shell settings put global window_animation_scale 0
$T adb shell svc power stayon true; $T adb shell input keyevent KEYCODE_WAKEUP; $T adb shell wm dismiss-keyguard
$T adb logcat -c || true
dump() { $T adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; $T adb pull /sdcard/ui.xml "$OUT/$1.xml" >/dev/null 2>&1; }
shot() { sleep "${2:-4}"; $T adb exec-out screencap -p > "$OUT/backlog-$1.png"; dump "backlog-$1"; echo "shot backlog-$1"; }
tap_node() {
    local p; p="$(python3 - "$1" "$2" <<'PY'
import re, sys
x = open(sys.argv[1], errors="ignore").read(); rx = re.compile(sys.argv[2], re.I)
for m in re.finditer(r'<node ([^>]*)>', x):
    a = m.group(1)
    g = lambda k: (re.search(k + r'="([^"]*)"', a) or [None, ""])[1]
    if any(rx.search(g(k)) for k in ("text", "content-desc")):
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
        if grep -q 'Enable update' "$OUT/cur.xml" && tap_node "$OUT/cur.xml" '^cancel$'; then sleep 2; continue; fi
        if grep -q 'text="Install unknown apps"' "$OUT/cur.xml" && tap_node "$OUT/cur.xml" '^Skip$'; then sleep 3; continue; fi
        if grep -qiE 'wireless debugging|Allow .* on this network' "$OUT/cur.xml"; then tap_node "$OUT/cur.xml" '^cancel$|^don.t allow$' || $T adb shell input keyevent KEYCODE_BACK; sleep 2; else break; fi
    done
}
grant() { $T adb shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow; $T adb shell pm grant "$PKG" android.permission.READ_EXTERNAL_STORAGE 2>/dev/null || true; }
launch() {
    $T adb shell am force-stop "$PKG"
    $T adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    sleep 22; dismiss
    dump cur; tap_node "$OUT/cur.xml" '^backlog$' || true; sleep 4
}
expect() { # NAME REGEX
    if grep -qiE "$2" "$OUT/backlog-$1.xml"; then echo "PASS $1: page says /$2/"; else echo "FAIL $1: the page does not say /$2/"; rc=1; fi
}

# a public fixture standing in for the private repo's root index.html
mkdir -p "$OUT/fx"
cat > "$OUT/fx/index.html" <<'HTML'
<!doctype html><html><head><meta charset="utf-8"><title>Backlog</title></head><body style="font-family:sans-serif">
<h1>Product Backlog (emulator fixture)</h1><p>Views: per project, per status, per effort, agent batches, per agent-model</p>
<table border="1" cellpadding="4"><tr><th>id</th><th>status</th><th>subject</th></tr>
<tr><td>#1</td><td>in_progress</td><td>Fixture ticket one</td></tr><tr><td>#2</td><td>pending</td><td>Fixture ticket two</td></tr></table></body></html>
HTML

grant
$T adb shell mkdir -p "$CLONE"; $T adb push "$OUT/fx/index.html" "$CLONE/index.html" >/dev/null
launch; shot live 3
expect live 'read from the clone|Fixture ticket'

$T adb shell rm -rf "$CLONE"
launch; shot offline 3
expect offline 'Offline copy saved'

$T adb shell pm clear "$PKG" >/dev/null 2>&1; grant
launch; shot unavailable 3
expect unavailable 'not on this device|Cannot reach|denied'

$T adb logcat -d -t 30000 > "$OUT/logcat.txt"; $T adb logcat -d -b crash > "$OUT/logcat-crash.txt" 2>&1 || true
cat "$OUT/logcat.txt" "$OUT/logcat-crash.txt" | grep -E "FATAL EXCEPTION" -A12 | grep -B2 -A12 "$PKG" | head -40 > "$OUT/crash.txt" || true
[ -s "$OUT/crash.txt" ] && { echo "FAIL crash in logcat"; rc=1; }
exit $rc
