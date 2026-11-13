#!/usr/bin/env bash
# #1713 emulator walk of Cloud C3 Webserver > ADB Shell: the fleet second row (Status, Shell, Perms, Server, All), one screenshot per sub page
# and a check that the row carries the five labels in order. Usage: emulator-adb-subpages.sh WEBSERVER_APK OUT_DIR
set -u
WS_APK="$1"; OUT="$2"; W=com.diegonmarcos.cloudwebserver
mkdir -p "$OUT"
adb install -r -g "$WS_APK" >"$OUT/install.txt" 2>&1 || { cat "$OUT/install.txt"; exit 1; }
shoot() { adb exec-out screencap -p > "$OUT/$1.png"; }
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml "$OUT/$1.xml" >/dev/null 2>&1; }
tap_exact() {  # tap the node whose text or content-desc is exactly $1 (the LAST match: the second row sits above the bottom nav)
  dump cur
  b=$(grep -oE "(text|content-desc)=\"$1\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" "$OUT/cur.xml" | tail -1 | grep -oE '[0-9]+' | tail -4 | tr '\n' ' ')
  [ -n "$b" ] || return 1
  set -- $b; adb shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 ))
}
rc=0; fail() { echo "::error::$*"; rc=1; }
adb shell am force-stop "$W"; adb shell input keyevent KEYCODE_HOME
adb shell am start -W -n "$W/.MainActivity" >/dev/null; sleep 8
tap_exact "ADB Shell" || fail "no ADB Shell tab on the bottom nav"; sleep 4
n=0
for p in Status Shell Perms Server All; do
  n=$((n+1)); tap_exact "$p" || fail "the second row has no '$p' page"; sleep 4
  shoot "$n-$(echo "$p" | tr 'A-Z' 'a-z')"; dump "$n-$(echo "$p" | tr 'A-Z' 'a-z')"
done
# the five labels in the order Status, Shell, Perms, Server, All on one row (x increasing)
python3 - "$OUT/1-status.xml" <<'PY' || fail "the second row is not Status, Shell, Perms, Server, All in that order"
import re, sys
x = open(sys.argv[1], encoding="utf-8").read()
pos = {}
for m in re.finditer(r'(?:text|content-desc)="(Status|Shell|Perms|Server|All)"[^>]*bounds="\[(\d+),(\d+)\]', x):
    pos.setdefault(m.group(1), int(m.group(2)))
order = ["Status", "Shell", "Perms", "Server", "All"]
sys.exit(0 if all(k in pos for k in order) and [pos[k] for k in order] == sorted(pos[k] for k in order) else 1)
PY
adb logcat -d -t 800 > "$OUT/logcat.txt" 2>/dev/null || true
grep -q "FATAL EXCEPTION.*$W\|Process: $W" "$OUT/logcat.txt" && fail "the app crashed during the walk"
exit $rc
