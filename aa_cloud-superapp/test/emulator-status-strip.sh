#!/usr/bin/env bash
# #1721 Emulator proof of the home status strip and the Privacy Dashboard:
#   bash aa_cloud-superapp/test/emulator-status-strip.sh <superapp.apk> <out dir>
# Photographs the home screen's top strip (right column aligned to the network line and the date line; WebS / ADB / SSHd dots filled or
# outline, never red/green), then taps the Mic / Camera / Location row and photographs the Privacy Dashboard (no ADB owner on the emulator:
# the dashboard must say the Cloud C3 Webserver ADB shell is the owner and offer "Open Webserver > ADB Shell"; the old wording must be gone).
set -u
APK=$1; OUT=$2; PKG=com.diegonmarcos.superapp
mkdir -p "$OUT"; fail=0
adb wait-for-device
wake() { adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard >/dev/null 2>&1 || true; }
adb shell svc power stayon true || true
adb shell settings put system screen_off_timeout 2147483647 || true
adb shell locksettings set-disabled true >/dev/null 2>&1 || true
wake
adb install -r -g "$APK" || { echo "::error::install failed"; exit 2; }
adb shell appops set "$PKG" SYSTEM_ALERT_WINDOW deny || true
adb shell cmd package set-home-activity "$PKG/.HomeActivity" || true
adb shell cmd role add-role-holder android.app.role.HOME "$PKG" 0 || true
adb shell pm disable-user --user 0 com.google.android.apps.nexuslauncher >/dev/null 2>&1 || true
adb shell settings put global hide_error_dialogs 1 || true

dump() { local i; for i in 1 2 3; do adb shell rm -f /sdcard/ui.xml >/dev/null 2>&1; adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb shell cat /sdcard/ui.xml > "$OUT/ui.xml" 2>/dev/null; grep -q '<node' "$OUT/ui.xml" && return 0; sleep 2; done; }
cap() { adb shell screencap -p /sdcard/s.png; adb pull /sdcard/s.png "$OUT/$1.png" >/dev/null; dump; cp "$OUT/ui.xml" "$OUT/$1.xml"; }
# Bounds "x1 y1 x2 y2" of the first node whose text / content-desc contains $1.
bounds() { python3 - "$OUT/ui.xml" "$1" <<'PY'
import re, sys
xml, want = open(sys.argv[1], encoding="utf-8", errors="ignore").read(), sys.argv[2]
for m in re.finditer(r'<node [^>]*>', xml):
    n = m.group(0)
    t = re.search(r' text="([^"]*)"', n); d = re.search(r' content-desc="([^"]*)"', n)
    if any(v and want in v.group(1) for v in (t, d)):
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        if b: print(*b.groups()); break
PY
}

wake; adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null; sleep 14
adb shell input keyevent KEYCODE_HOME; sleep 4
cap strip-home
# Alignment (pixels): the resources line (R / S / C / F) vs the network labels (5G / WiFi), and the access row vs the date.
python3 - "$OUT/ui.xml" <<'PY' | tee "$OUT/alignment.txt"
import re, sys
xml = open(sys.argv[1], encoding="utf-8", errors="ignore").read()
def find(word):
    for m in re.finditer(r'<node [^>]*>', xml):
        n = m.group(0); t = re.search(r' (?:text|content-desc)="([^"]*)"', n)
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        if t and b and word in t.group(1): x1, y1, x2, y2 = map(int, b.groups()); return (y1 + y2) / 2
    return None
for w in ("Microphone", "Camera", "Location"): print(w, find(w))
PY
grep -q "Microphone" "$OUT/ui.xml" || { echo "::error::the Mic / Camera / Location row is not on the strip"; fail=1; }

xy=$(bounds "Microphone"); if [ -n "$xy" ]; then set -- $xy; adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 )); sleep 8; else fail=1; fi
cap privacy-dashboard
grep -q "Privacy dashboard" "$OUT/ui.xml" || { echo "::error::the Privacy dashboard did not open"; fail=1; }
grep -q "armed for the SuperApp\|SuperApp's ADB shell" "$OUT/ui.xml" && { echo "::error::the old in-process ADB wording is still shown"; fail=1; }
if grep -q "Permission history unavailable" "$OUT/ui.xml"; then
  grep -q "Open Webserver &gt; ADB Shell" "$OUT/ui.xml" || { echo "::error::no 'Open Webserver > ADB Shell' button on the unavailable dashboard"; fail=1; }
fi
adb logcat -d -s AndroidRuntime:E > "$OUT/crashes.txt" 2>&1 || true
grep -q "FATAL EXCEPTION" "$OUT/crashes.txt" && { echo "::error::the app crashed"; fail=1; }
exit $fail
