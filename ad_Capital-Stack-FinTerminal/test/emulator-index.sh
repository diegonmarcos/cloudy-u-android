#!/usr/bin/env bash
# emulator-index.sh <apk> <out-dir> (#1702) — the CI emulator walk of the EQ index holdings breakdown: EQ > World Indices Equity, the page as it draws,
# then a tap on each index's own row (fallback: its chip) for S&P 500 / Nikkei 225 / FTSE 100, a screenshot of the breakdown after it settled, one scrolled
# to the holdings table, and a uiautomator dump per step. Runs inside reactivecircus/android-emulator-runner (CI only). The emulator has no Cloud Store
# and no mesh: it proves the dataset arrives on its own and the tiles draw.
set -u
APK="$1"; OUT="$2"; mkdir -p "$OUT"
PKG=com.diegonmarcos.capitalfinterm
adb install -r -g "$APK" || exit 1
adb shell settings delete global cloud_nav_ui_mode || true
read -r W H < <(adb shell wm size | awk -F'[ x]' '/Physical/{print $3, $4}')
W=${W:-1080}; H=${H:-2400}
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && adb pull /sdcard/ui.xml "$1" >/dev/null 2>&1 || true; }
# node_xy <xml> <resource-id-or-text-prefix> -> "x y" of the first node whose resource-id equals, else whose text starts with, the key
node_xy() {
  python3 -I - "$1" "$2" <<'PY'
import re, sys
x = open(sys.argv[1], encoding="utf-8").read(); k = sys.argv[2]
best = None
for m in re.finditer(r'<node [^>]*>', x):
    n = m.group(0)
    rid = re.search(r' resource-id="([^"]*)"', n); t = re.search(r' text="([^"]*)"', n)
    hit = (rid and rid.group(1) == k) or (t and t.group(1).startswith(k))
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
    if hit and b:
        a = [int(v) for v in b.groups()]
        print((a[0] + a[2]) // 2, (a[1] + a[3]) // 2); break
PY
}
walk() { # <name> <chip id> <row label>
  adb shell am start -S -W -n "$PKG/.MainActivity" --es dashboard world_equity_indices >/dev/null
  sleep "${FT_WAIT:-25}"
  adb exec-out screencap -p > "$OUT/apk-eq-indices-$1.png"; dump "$OUT/ui-eq-indices-$1.xml"
  XY=$(node_xy "$OUT/ui-eq-indices-$1.xml" "$3")
  [ -n "$XY" ] && echo "$1 row xy=$XY" | tee -a "$OUT/walk.txt" || {
    XY=$(node_xy "$OUT/ui-eq-indices-$1.xml" "$2"); echo "$1 chip xy=${XY:-none}" | tee -a "$OUT/walk.txt"; }
  [ -n "$XY" ] || return 0
  set -- "$1" $XY
  adb shell input tap "$2" "$3"
  sleep "${FT_WAIT:-25}"
  adb exec-out screencap -p > "$OUT/apk-index-$1-1.png"; dump "$OUT/ui-index-$1-1.xml"
  adb shell input swipe $((W / 2)) $((H * 4 / 5)) $((W / 2)) $((H / 4)) 500; sleep 3
  adb exec-out screencap -p > "$OUT/apk-index-$1-2.png"
  adb shell input swipe $((W / 2)) $((H * 4 / 5)) $((W / 2)) $((H / 4)) 500; sleep 3
  adb exec-out screencap -p > "$OUT/apk-index-$1-3.png"
}
walk sp500 index_link:sp500 "S&P 500"
walk nikkei225 index_link:nikkei225 "Nikkei 225"
walk ftse100 index_link:ftse100 "FTSE 100"
adb logcat -d -t 3000 > "$OUT/logcat.txt" 2>/dev/null || true
grep -c "FATAL EXCEPTION" "$OUT/logcat.txt" > "$OUT/crashes.txt" || true
ls -la "$OUT"
