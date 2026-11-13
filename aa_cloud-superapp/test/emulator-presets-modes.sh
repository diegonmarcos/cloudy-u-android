#!/usr/bin/env bash
# #1720 Emulator proof of Quick actions > Presets:
#   bash aa_cloud-superapp/test/emulator-presets-modes.sh <superapp.apk> <out dir>
# Opens page:config/presets and photographs: the page with the ADB shell status line first (the emulator has no ADB owner, so the
# line must say the shell is down, never "up"), a mode on / switch / off cycle through the page's own tiles and its Off button
# (the ADB prompt is answered "Continue without ADB": the launcher half of a mode applies, the shell steps are listed as skipped),
# the notification shade with the "Presets" badge, and the Notify page. Fails when the status line is missing or claims the shell
# is up, when two modes show as on at once, when Off leaves a mode on, or on a crash.
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
adb shell appops set "$PKG" REQUEST_INSTALL_PACKAGES allow || true
adb shell cmd package set-home-activity "$PKG/.HomeActivity" || true
adb shell cmd role add-role-holder android.app.role.HOME "$PKG" 0 || true
adb shell pm disable-user --user 0 com.google.android.apps.nexuslauncher >/dev/null 2>&1 || true
adb shell settings put global hide_error_dialogs 1 || true
adb shell settings delete global cloud_nav_ui_mode || true
H=$(adb shell wm size | tr -d '\r' | sed -n 's/.*: *[0-9]*x\([0-9]*\).*/\1/p' | tail -1); H=${H:-2400}
W=$(adb shell wm size | tr -d '\r' | sed -n 's/.*: *\([0-9]*\)x[0-9]*.*/\1/p' | tail -1); W=${W:-1080}

dump() { local i; for i in 1 2 3; do adb shell rm -f /sdcard/ui.xml >/dev/null 2>&1; adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb shell cat /sdcard/ui.xml > "$OUT/ui.xml" 2>/dev/null; grep -q '<node' "$OUT/ui.xml" && return 0; sleep 2; done; }
cap() { adb shell screencap -p /sdcard/s.png; adb pull /sdcard/s.png "$OUT/$1.png" >/dev/null; dump; cp "$OUT/ui.xml" "$OUT/$1.xml"; }
# Centre of the first node whose text STARTS WITH $1 and that lies inside the screen (between 8% and 88% of its height).
centre() { python3 - "$OUT/ui.xml" "$1" "$H" <<'PY'
import html, re, sys
xml, want, h = open(sys.argv[1], encoding="utf-8", errors="ignore").read(), sys.argv[2], int(sys.argv[3])
for m in re.finditer(r'<node [^>]*>', xml):
    n = m.group(0)
    t = re.search(r' text="([^"]*)"', n)
    if not t or not html.unescape(t.group(1)).startswith(want): continue
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
    if not b: continue
    x1, y1, x2, y2 = map(int, b.groups()); y = (y1 + y2) // 2
    if h * 0.08 < y < h * 0.88: print((x1 + x2) // 2, y); break
PY
}
# Every text on screen, one per line (entities decoded).
texts() { python3 - "$OUT/ui.xml" <<'PY'
import html, re, sys
for t in re.findall(r' text="([^"]*)"', open(sys.argv[1], encoding="utf-8", errors="ignore").read()):
    if t: print(html.unescape(t))
PY
}
up() { adb shell input swipe $((W / 2)) $((H * 65 / 100)) $((W / 2)) $((H * 35 / 100)) 400; sleep 2; }
down() { adb shell input swipe $((W / 2)) $((H * 35 / 100)) $((W / 2)) $((H * 65 / 100)) 400; sleep 2; }
# Tap the node whose text starts with $1, scrolling the page down to it when it is below the fold.
tap() { local i xy; for i in 1 2 3 4 5 6; do dump; xy=$(centre "$1"); [ -n "$xy" ] && { adb shell input tap $xy; sleep 3; return 0; }; up; done
  echo "::error::no '$1' on screen"; fail=1; return 1; }
# The ADB prompt of a mode (the emulator has no shell): go on without it.
without_adb() { local i xy; for i in 1 2 3 4; do dump; xy=$(centre "Continue without ADB"); [ -n "$xy" ] && { adb shell input tap $xy; sleep 6; return 0; }; sleep 2; done
  echo "::warning::no ADB prompt was shown"; return 0; }
# All the text of the page, top to bottom (the page is longer than the screen): scroll to the top, collect, scroll down, collect.
page_text() { local i; for i in 1 2 3; do down; done; : > "$OUT/page.txt"; for i in 1 2 3 4; do dump; texts >> "$OUT/page.txt"; up; done; for i in 1 2 3; do down; done; sort -u "$OUT/page.txt" -o "$OUT/page.txt"; }
# How many MODE tiles show as on (a tile's node may carry its subtitle after the label, so the match is on the label's head only).
MODES='(Power Mode|Focus Mode|Saving Energy Mode|Super Saving Battery Mode)'
on_count() { grep -cE "^$MODES  ·  (on, tap to turn off|modified)" "$OUT/page.txt"; }
is_on() { grep -qE "^$1  ·  (on, tap to turn off|modified)" "$OUT/page.txt"; }
# A first-run prompt (install source, permissions) sits over the page: answer Skip / Not now until none is left.
prompts() { local i xy w; for i in 1 2 3 4; do dump; xy=""; for w in "Skip" "Not now" "Later"; do xy=$(centre "$w"); [ -n "$xy" ] && break; done; [ -n "$xy" ] || return 0; adb shell input tap $xy; sleep 3; done; }
open_presets() { wake; adb shell am start -n "$PKG/.MainActivity" --es shortcut_action "page:config/presets" >/dev/null; sleep 8; prompts; }

wake; adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null; sleep 12
prompts
open_presets
cap presets-1-page-adb-status-line
page_text; cp "$OUT/page.txt" "$OUT/presets-1-page.txt"
grep -q 'ADB shell: ' "$OUT/page.txt" || { echo "::error::no ADB shell status line on Presets"; fail=1; }
grep -q 'ADB shell: up' "$OUT/page.txt" && { echo "::error::the status line says the shell is up on an emulator with no ADB owner"; fail=1; }
grep -qx 'ADB Shell' "$OUT/page.txt" || { echo "::error::no ADB Shell link next to the status line"; fail=1; }
for w in Sandboxes Themes Modes "Power Mode" "Focus Mode" "Saving Energy Mode" "Super Saving Battery Mode"; do
  grep -q "^$w" "$OUT/page.txt" || { echo "::error::'$w' missing on Presets"; fail=1; }; done
[ "$(on_count)" -le 1 ] || { echo "::error::more than one mode shows as on before any pick"; fail=1; }

tap "Focus Mode"; without_adb; cap presets-2-focus-on
page_text; cp "$OUT/page.txt" "$OUT/presets-2-page.txt"
is_on "Focus Mode" || { echo "::error::Focus Mode is not shown as on after its pick"; fail=1; }
[ "$(on_count)" -eq 1 ] || { echo "::error::$(on_count) modes show as on after picking Focus (must be exactly one)"; fail=1; }
grep -q '^Last change' "$OUT/page.txt" || { echo "::error::the last change (steps and reasons) is not listed"; fail=1; }

tap "Saving Energy Mode"; without_adb; cap presets-3-switched-to-saving-energy
page_text; cp "$OUT/page.txt" "$OUT/presets-3-page.txt"
is_on "Saving Energy Mode" || { echo "::error::Saving Energy Mode is not shown as on after its pick"; fail=1; }
is_on "Focus Mode" && { echo "::error::Focus Mode still shows as on after Saving Energy was picked"; fail=1; }
[ "$(on_count)" -eq 1 ] || { echo "::error::$(on_count) modes show as on after the switch (must be exactly one)"; fail=1; }

# the notification shade: the Presets badge says the mode that is on
adb shell cmd statusbar expand-notifications; sleep 4; cap notify-shade-badge-mode-on
texts > "$OUT/shade-on.txt"
# (the shade folds the Actions group under its summary on some builds, so the words are a warning here; PresetsBadgeTest asserts them)
grep -q 'Presets · Mode: Saving Energy Mode' "$OUT/shade-on.txt" || echo "::warning::the Presets badge text is not readable in the shade dump (folded group?): see notify-shade-badge-mode-on.png"
adb shell cmd statusbar collapse; sleep 2

open_presets
tap "Off"; without_adb; cap presets-4-off
page_text; cp "$OUT/page.txt" "$OUT/presets-4-page.txt"
[ "$(on_count)" -eq 0 ] || { echo "::error::a mode still shows as on after Off"; fail=1; }

adb shell cmd statusbar expand-notifications; sleep 4; cap notify-shade-badge-mode-off
texts > "$OUT/shade-off.txt"
grep -q 'Presets · Mode: none' "$OUT/shade-off.txt" || echo "::warning::the Presets badge text is not readable in the shade dump (folded group?): see notify-shade-badge-mode-off.png"
adb shell cmd statusbar collapse; sleep 2

# the Notify page (Persistent Badges): photographed, the Actions section holds the Presets card
wake; adb shell am start -n "$PKG/.MainActivity" --es shortcut_action "page:communication/notify-badges" >/dev/null; sleep 8
prompts; cap notify-page-top; up; up; cap notify-page-actions; up; cap notify-page-actions-2

adb logcat -d -s AndroidRuntime:E > "$OUT/crashes.txt" 2>&1 || true
# only the SuperApp's own process counts (the emulator's system UI may fall over when its stock launcher is disabled)
grep -A1 "FATAL EXCEPTION" "$OUT/crashes.txt" | grep -q "Process: $PKG" && { echo "::error::the app crashed"; fail=1; }
exit $fail
