#!/usr/bin/env bash
# #1737 Emulator proof of Configs > Network (individual icons on the Configs page) and of the NG Firewall hub:
#   bash aa_cloud-superapp/test/emulator-network-nav.sh <superapp.apk> <out dir>
# Opens the Configs main page and photographs the Network group (NG Firewall | Wi-Fi Bluetooth DNS VPN | Firewall OS Perms | Webserver Proxy), taps NG Firewall
# and photographs the hub (Connections / Firewalls / Servers with their icons), opens the Firewall (full screen, own nav Status | Apps | Rules, no
# Back of its own: the shell arrow is the one) and returns. Fails on a missing label or heading, a unified Network tile, an ADB Shell icon, a
# Firewall without its nav, or a crash. OS Perms, Webserver and Proxy leave the app: not tapped.
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

wake; adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null; sleep 12
wake; adb shell am start -n "$PKG/.MainActivity" --es shortcut_action "section:config" >/dev/null; sleep 8

sleep 6; CUR=configs; cap configs-network-group-early; sleep 4; cap configs-network-group
need "NG Firewall" "Wi-Fi" Bluetooth DNS VPN Firewall "OS Perms" Webserver Proxy
has "ADB Shell" && { echo "::error::the ADB Shell icon is back in Network"; fail=1; }
tap "NG Firewall"; sleep 3; CUR=hub; cap ng-firewall-hub
need Connections Firewalls Servers "Wi-Fi" Bluetooth DNS VPN Firewall "OS Perms" Webserver Proxy
tap "Firewall"; sleep 3; CUR=fw; cap firewall-status
need Status Apps Rules
tap "Apps"; CUR=fw-apps; cap firewall-apps; need Status Apps Rules
tap "Rules"; CUR=fw-rules; cap firewall-rules; need Status Apps Rules
read -r SW SH < <(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1 | tr x ' ')
# the ONE Back control: the shell's top-right arrow (the Firewall draws none of its own)
adb shell input tap $((SW * 89 / 100)) $((SH * 9 / 100)); sleep 3; CUR=back; cap hub-after-back
need Connections Firewalls Servers
has Rules && { echo "::error::the Firewall is still shown after Back"; fail=1; }
adb logcat -d -s AndroidRuntime:E > "$OUT/crashes.txt" 2>&1 || true
grep -q "Process: $PKG" "$OUT/crashes.txt" && { echo "::error::the app crashed"; fail=1; }
exit $fail
