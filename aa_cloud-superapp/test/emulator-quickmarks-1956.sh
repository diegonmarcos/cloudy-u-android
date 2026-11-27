#!/usr/bin/env bash
# #1956 Emulator shots of Phone > Quickmarks: the folders with their Cloud page icons, and each changed folder opened (Lnktree, Me-Pers, Me-Prj, Media Center, AI, Drive, Code).
#   bash aa_cloud-superapp/test/emulator-quickmarks-1956.sh <superapp.apk> <out dir>
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
adb shell settings delete global cloud_nav_ui_mode || true

dump() { local i; for i in 1 2 3; do adb shell rm -f /sdcard/ui.xml >/dev/null 2>&1; adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb shell cat /sdcard/ui.xml > "$OUT/ui.xml" 2>/dev/null; grep -q '<node' "$OUT/ui.xml" && return 0; sleep 2; done; }
cap() { adb shell screencap -p /sdcard/s.png; adb pull /sdcard/s.png "$OUT/$1.png" >/dev/null; dump; cp "$OUT/ui.xml" "$OUT/$1.xml"; }
# Centre of the LAST node whose text or content-desc equals $1 (the bar sits below the page, so the last match is the bar item).
centre() { python3 - "$OUT/ui.xml" "$1" <<'PY'
import re, sys
xml, want = open(sys.argv[1], encoding="utf-8", errors="ignore").read(), sys.argv[2]
hit = None
for m in re.finditer(r'<node [^>]*>', xml):
    n = m.group(0)
    t = re.search(r' text="([^"]*)"', n); d = re.search(r' content-desc="([^"]*)"', n)
    if want in ((t.group(1) if t else None), (d.group(1) if d else None)):
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        if b:
            x1, y1, x2, y2 = map(int, b.groups()); hit = ((x1 + x2) // 2, (y1 + y2) // 2)
if hit: print(*hit)
PY
}
has() { grep -q "text=\"$1\"\|content-desc=\"$1\"" "$OUT/ui.xml"; }
tap() { dump; local xy; xy=$(centre "$1"); if [ -z "$xy" ]; then echo "::error::no '$1' on screen"; fail=1; return 1; fi; adb shell input tap $xy; sleep 4; }
need() { local w; for w in "$@"; do has "$w" || { echo "::error::'$w' missing on $CUR"; fail=1; }; done; }
swipe_up() { read -r W H < <(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1 | tr x ' '); adb shell input swipe $((W/2)) $((H*75/100)) $((W/2)) $((H*25/100)) 400; sleep 2; }
wake; adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null; sleep 12
walk() { # walk <section> <prefix> <steps>
  wake; adb shell am start -n "$PKG/.MainActivity" --es shortcut_action "section:$1" >/dev/null; sleep 10
  local i; CUR=$2; cap "$2-00"
  for i in $(seq 1 "$3"); do swipe_up; cap "$2-$(printf %02d "$i")"; done
}
CUR=phone; wake; adb shell am start -n "$PKG/.MainActivity" --es shortcut_action "section:phone" >/dev/null; sleep 12; cap qm-00
for i in $(seq 1 6); do swipe_up; cap "qm-$(printf %02d "$i")"; done
for l in Lnktree Me-Pers Me-Prj "Media Center" AI Drive Code "Jobs & Business"; do
  wake; adb shell am start -n "$PKG/.MainActivity" --es shortcut_action "section:phone" >/dev/null; sleep 8
  for k in 1 2 3 4 5 6 7 8; do dump; if has "$l"; then break; fi; swipe_up; done
  if has "$l"; then tap "$l"; cap "qm-folder-$(echo "$l" | tr -d ' ')"; adb shell input keyevent KEYCODE_BACK; sleep 2; else echo "::warning::no '$l' folder found"; fail=1; fi
done
adb logcat -d -s AndroidRuntime:E > "$OUT/crashes.txt" 2>&1 || true
grep -q "Process: $PKG" "$OUT/crashes.txt" && { echo "::error::the app crashed"; fail=1; }
exit $fail
