#!/usr/bin/env bash
# #1734 Emulator proof of the day timelines card in its two places:
#   bash aa_cloud-superapp/test/emulator-day-timelines.sh <superapp.apk> <out dir>
# 1) Notify > persistent badges: the Weather badge, expanded, carries the card.
# 2) Launcher home: tap the top-centre date/time, the Calendar dropdown shows the card right after the Agenda.
# Screenshots: notify-weather-badge-*.png, calendar-dropdown-*.png. Fails on a missing card text or a crash.
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
adb shell wm size 1080x2340 || true; adb shell wm density 420 || true
W=1080; H=2340

dump() { local i; for i in 1 2 3; do adb shell rm -f /sdcard/ui.xml >/dev/null 2>&1; adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb shell cat /sdcard/ui.xml > "$OUT/ui.xml" 2>/dev/null; grep -q '<node' "$OUT/ui.xml" && return 0; sleep 2; done; }
cap() { adb shell screencap -p /sdcard/s.png; adb pull /sdcard/s.png "$OUT/$1.png" >/dev/null; dump; cp "$OUT/ui.xml" "$OUT/$1.xml"; }
up() { adb shell input swipe $((W/2)) $((H*70/100)) $((W/2)) $((H*30/100)) 400; sleep 2; }

# ---- 1) Notify page, Weather badge expanded
wake; adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null; sleep 12
adb shell am start -n "$PKG/.MainActivity" --es shortcut_action "page:communication/notify-badges" >/dev/null; sleep 10
n=0; found=0
for i in 1 2 3 4 5 6; do
  cap "notify-weather-badge-$i"
  if grep -q "Temperature: " "$OUT/ui.xml"; then found=1; break; fi
  up; n=$((n+1))
done
[ "$found" = 1 ] || { echo "::error::the Weather badge's day timelines card (status line 'Temperature: ...') is not on the Notify page"; fail=1; }
sleep 6; cap notify-weather-badge-loaded      # the forecast refresh has landed by now

# ---- 2) Launcher home, date dropdown
adb shell input keyevent KEYCODE_HOME; sleep 5
opened=0
for fy in 28 32 36 24 40; do
  adb shell input tap $((W/2)) $((H*fy/1000)); sleep 5; dump
  if grep -q "Agenda" "$OUT/ui.xml"; then opened=1; break; fi
  adb shell input keyevent KEYCODE_BACK; sleep 2
done
[ "$opened" = 1 ] || echo "::warning::the date dropdown did not open from the taps tried (see calendar-dropdown screenshots)"
cap calendar-dropdown-top
# scroll INSIDE the popup (a swipe that starts outside it dismisses it): Agenda, then the card right after it
inswipe() { adb shell input swipe $((W/2)) $((H*58/100)) $((W/2)) $((H*14/100)) 500; sleep 2; }
inswipe; cap calendar-dropdown-agenda
inswipe; sleep 6; cap calendar-dropdown-card
grep -q "Temperature: " "$OUT/ui.xml" || grep -q "Day timelines" "$OUT/ui.xml" || { echo "::error::the card is not in the calendar dropdown"; fail=1; }
# the Agenda view (tables alone): the card sits right under the Agenda table
adb shell input tap $((W*66/100)) $((H*94/1000)); sleep 4; cap calendar-dropdown-agenda-view
inswipe; sleep 3; cap calendar-dropdown-agenda-view-card

adb logcat -d -s AndroidRuntime:E > "$OUT/crashes.txt" 2>&1 || true
grep -A1 "FATAL EXCEPTION" "$OUT/crashes.txt" | grep -q "Process: $PKG" && { echo "::error::the app crashed"; fail=1; }
exit $fail
