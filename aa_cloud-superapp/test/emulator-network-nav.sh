#!/usr/bin/env bash
# #1719 Emulator proof of Configs > Network and its bottom nav, and of NG Firewall taking over the screen:
#   bash aa_cloud-superapp/test/emulator-network-nav.sh <superapp.apk> <out dir>
# Opens page:config/network, photographs Home (default), Wireless, DNS, VPN, then NG Firewall (full screen: own nav Status | Apps | Rules and a
# Back button, the SuperApp nav absent), its three views, and Back (the Network nav is restored). Fails when a label of the bar is missing,
# NG Firewall lacks its own nav or Back, or Back does not restore the Network bar. OS Perms and Webserver are links out of the app: photographed
# only (the apps they open are not on the emulator).
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
wake; adb shell am start -n "$PKG/.MainActivity" --es shortcut_action "page:config/network" >/dev/null; sleep 8

CUR=home; cap network-home
need Wireless DNS VPN Home "NG Firewall" "OS Perms" Webserver "Wi-Fi" Bluetooth
# the SuperApp's own bottom nav is replaced by the Network nav while this page is open
for w in Notify Boards Cloud Phone; do has "$w" && { echo "::error::the SuperApp bottom nav ($w) is still drawn under the Network nav"; fail=1; }; done
has "ADB Shell" && { echo "::error::the ADB Shell icon is still in Network"; fail=1; }
tap "Wireless"; CUR=wireless; cap network-wireless; need "Wi-Fi" Bluetooth
tap "DNS"; sleep 3; CUR=dns; cap network-dns; need Wireless VPN
tap "VPN"; sleep 3; CUR=vpn; cap network-vpn; need Wireless DNS
tap "Home"; CUR=home2; cap network-home-again; need "Wi-Fi" Bluetooth
tap "OS Perms"; sleep 3; cap network-os-perms-link
adb shell am start -n "$PKG/.MainActivity" --es shortcut_action "page:config/network" >/dev/null; sleep 6
tap "Webserver"; sleep 3; cap network-webserver-link
adb shell am start -n "$PKG/.MainActivity" --es shortcut_action "page:config/network" >/dev/null; sleep 6
tap "NG Firewall"; sleep 3; CUR=ngfw; cap ng-firewall-status
need "‹ Back" Status Apps Rules
for w in Wireless DNS VPN "OS Perms" Webserver; do has "$w" && { echo "::error::SuperApp/Network nav still shown inside NG Firewall ($w)"; fail=1; }; done
tap "Apps"; CUR=ngfw-apps; cap ng-firewall-apps; need "‹ Back" Status Apps Rules
tap "Rules"; CUR=ngfw-rules; cap ng-firewall-rules; need "‹ Back" Status Apps Rules
tap "Status"; CUR=ngfw-status2; cap ng-firewall-status-again
tap "‹ Back"; sleep 2; CUR=back; cap network-after-back
need Wireless DNS VPN Home "NG Firewall" "OS Perms" Webserver
has "‹ Back" && { echo "::error::NG Firewall's Back is still shown after closing it"; fail=1; }
adb logcat -d -s AndroidRuntime:E > "$OUT/crashes.txt" 2>&1 || true
grep -q "Process: $PKG" "$OUT/crashes.txt" && { echo "::error::the app crashed"; fail=1; }
exit $fail
