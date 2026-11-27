#!/usr/bin/env bash
# emulator-me-repos-1973 — #1973 on a booted emulator: Proj > Repos from the Ship APK. The Proj strip must read Home | Repos | Unix ... (Repos second); the page opens in List
# (child repos indented under parents), then the Table chip and the Canvas chip are tapped (found in the uiautomator dump of the WebView) and each view is screenshotted.
# Usage: emulator-me-repos-1973.sh ME_APK OUT_DIR. Exit 1 on any failed assertion.
set -uo pipefail
ME_APK="$1"; OUT="$2"; mkdir -p "$OUT"
T="timeout 120"; ME=com.diegonmarcos.cloudme; rc=0
$T adb wait-for-device
$T adb install -r -g "$ME_APK" || { echo "install failed"; exit 1; }
$T adb shell settings put global window_animation_scale 0
$T adb shell locksettings set-disabled true; $T adb shell settings put system screen_off_timeout 1800000
$T adb shell cmd uimode night yes
$T adb shell wm size 1080x2400; $T adb shell wm density 420
wake() { $T adb shell svc power stayon true; $T adb shell input keyevent KEYCODE_WAKEUP; $T adb shell wm dismiss-keyguard; }
launcher() { $T adb shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER "$1" | tail -1 | tr -d '\r'; }
open_page() { wake; $T adb shell am start -W -n "$(launcher "$ME")" -f 0x14000000 --es shortcut_action "$1" >/dev/null; }
dump() { rm -f "$OUT/$1.xml"; $T adb shell rm -f /sdcard/ui.xml
    for _ in 1 2 3; do $T adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; $T adb pull /sdcard/ui.xml "$OUT/$1.xml" >/dev/null 2>&1 && break; sleep 2; done; }
shot() { sleep "${2:-6}"; $T adb exec-out screencap -p > "$OUT/$1.png"; dump "$1"; echo "shot $1"; }
tap_text() {  # TEXT SHOT: tap the centre of the first node whose text or content-desc is TEXT in the dump of SHOT
    local xy; xy=$(python3 -I - "$OUT/$2.xml" "$1" <<'PY'
import re, sys, xml.etree.ElementTree as ET
try: nodes = list(ET.parse(sys.argv[1]).getroot().iter("node"))
except Exception: sys.exit(1)
for n in nodes:
    if (n.get("text") or n.get("content-desc") or "") == sys.argv[2]:
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds") or "")
        if m: a = list(map(int, m.groups())); print((a[0] + a[2]) // 2, (a[1] + a[3]) // 2); sys.exit(0)
sys.exit(1)
PY
    ) || { echo "FAIL no node '$1' in $2.xml" | tee -a "$OUT/verdict.txt"; return 1; }
    $T adb shell input tap $xy; echo "tapped $1 at $xy"
}
for op in GET_USAGE_STATS SYSTEM_ALERT_WINDOW MANAGE_EXTERNAL_STORAGE REQUEST_INSTALL_PACKAGES; do $T adb shell appops set "$ME" "$op" allow; done
open_page "page:configs/general"; sleep 12
open_page "page:proj/pj-repos"; sleep 20   # warm-up: the first WebView of a cold start paints late
dump warm; sleep 5   # the first uiautomator dump switches the WebView accessibility tree on; the second one carries its nodes
shot repos-1973-list 6
for want in "Table" "Canvas" "List"; do grep -q "text=\"$want\"\|content-desc=\"$want\"" "$OUT/repos-1973-list.xml" && echo "PASS chip $want present" | tee -a "$OUT/verdict.txt" || { echo "FAIL chip $want missing" | tee -a "$OUT/verdict.txt"; rc=1; }; done
python3 -I - "$OUT/repos-1973-list.xml" <<'PY' | tee -a "$OUT/verdict.txt" || rc=1
import re, sys, xml.etree.ElementTree as ET
nodes = list(ET.parse(sys.argv[1]).getroot().iter("node")); pos = {}
for n in nodes:
    t = (n.get("text") or "").strip(); m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds") or "")
    if t in ("Home", "Repos", "Unix") and m and 200 < int(m.group(2)) < 360: pos.setdefault(t, int(m.group(1)))   # the Proj strip row, left edge
ok = "Home" in pos and "Repos" in pos and "Unix" in pos and pos["Home"] < pos["Repos"] < pos["Unix"]
print("%s Proj strip order Home | Repos | Unix: x=%s" % ("PASS" if ok else "FAIL", pos)); sys.exit(0 if ok else 1)
PY
$T adb shell input swipe 540 1700 540 900 400; shot repos-1973-list-scrolled 3
$T adb shell input swipe 540 900 540 1700 400; sleep 1; dump repos-1973-list
tap_text "Table" repos-1973-list && shot repos-1973-table 8 || rc=1
$T adb shell input swipe 900 1200 200 1200 400; shot repos-1973-table-right 3
dump repos-1973-table; tap_text "Canvas" repos-1973-table && shot repos-1973-canvas 8 || rc=1
dump repos-1973-canvas; tap_text "List" repos-1973-canvas && shot repos-1973-list-again 5 || rc=1
$T adb logcat -d > "$OUT/logcat.txt"
echo "rc=$rc" | tee -a "$OUT/verdict.txt"
exit $rc
