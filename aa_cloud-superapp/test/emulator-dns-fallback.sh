#!/usr/bin/env bash
# #1924 Emulator check: with the mesh resolver unreachable, github.com still resolves through 1.1.1.1.
#   bash aa_cloud-superapp/test/emulator-dns-fallback.sh <superapp.apk> <out dir> <build.json>
# The emulator has no mesh tunnel, so the fleet resolver the page names (a 10.x hub address) cannot be reached. The check picks
# "Private with fallbacks" (fallback Public open), taps Test lookup and asserts the fleet plan line for github.com is an answer
# "via 1.1.1.1" (the preset's own server), never a failure, and that no mesh IP is named as the server that answered. Then it
# picks "Public open" and asserts the Status card lists the real preset servers (1.1.1.1) and no 10.x address under
# "Preset servers in use", plus the Export to Markdown and Copy all buttons.
set -u
APK=$1; OUT=$2; BJ=$3; PKG=com.diegonmarcos.superapp
mkdir -p "$OUT"; rm -f "$OUT/missing.txt"; fail=0
[ -f "$BJ" ] || { echo "::error::no build.json at $BJ"; exit 2; }
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
# Every text on screen, one node per record: a node's own line breaks are kept as " | " so one read-out stays one line.
texts() { python3 - "$OUT/ui.xml" <<'PY'
import html, re, sys
for t in re.findall(r' text="([^"]*)"', open(sys.argv[1], encoding="utf-8", errors="ignore").read()):
    if t: print(html.unescape(t).replace("\r", "").replace("\n", " | "))
PY
}
up() { adb shell input swipe $((W / 2)) $((H * 65 / 100)) $((W / 2)) $((H * 35 / 100)) 400; sleep 2; }
down() { adb shell input swipe $((W / 2)) $((H * 35 / 100)) $((W / 2)) $((H * 65 / 100)) 400; sleep 2; }
top() { local i; for i in 1 2 3 4 5 6 7 8 9 10; do down; done; }
# Tap the node whose text starts with $1, scrolling the page down to it when it is below the fold.
tap() { local i xy; for i in 1 2 3 4 5 6 7 8 9 10 11 12; do dump; xy=$(centre "$1"); [ -n "$xy" ] && { adb shell input tap $xy; sleep 3; return 0; }; up; done
  echo "::error::no '$1' on screen"; fail=1; return 1; }
# All the text of the page, top to bottom (the page is several screens long).
page_text() { local i; top; : > "$OUT/page.txt"; for i in 1 2 3 4 5 6 7 8 9 10 11 12; do dump; texts >> "$OUT/page.txt"; up; done; sort -u "$OUT/page.txt" -o "$OUT/page.txt"; }
# A first-run prompt (install source, permissions) sits over the page: answer Skip / Not now until none is left.
prompts() { local i xy w; for i in 1 2 3 4; do dump; xy=""; for w in "Skip" "Not now" "Later"; do xy=$(centre "$w"); [ -n "$xy" ] && break; done; [ -n "$xy" ] || return 0; adb shell input tap $xy; sleep 3; done; }
# The read-out of the reachability test: the node holding $1, as lines.
wake; adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null; sleep 12
prompts
wake; adb shell am start -n "$PKG/.MainActivity" --es shortcut_action "page:config/dns" >/dev/null; sleep 8
prompts
top; tap "Private with fallbacks"
sleep 4
top; tap "Test lookup"
got=0
for i in 1 2 3 4 5 6 7 8 9 10 11 12; do
  sleep 5; page_text
  grep -qF "fleet plan:" "$OUT/page.txt" && { got=1; break; }
done
cap dns-fb-1
[ "$got" = 1 ] || { echo "::error::no 'fleet plan:' line after Test lookup"; fail=1; }
readout "fleet plan:" > "$OUT/lookup.txt"
echo "──── Test lookup, mesh resolver unreachable ────"; cat "$OUT/lookup.txt"; echo "─────────────────────────────────────────────"
plan=$(grep -F "fleet plan:" "$OUT/lookup.txt" | head -1)
case "$plan" in
  *"via 1.1.1.1"*) echo "ok: github.com answered via 1.1.1.1" ;;
  *) echo "::error::github.com did not resolve through 1.1.1.1: $plan"; fail=1 ;;
esac
case "$plan" in *"FAILED"*|*"via 10."*) echo "::error::the fleet plan failed or named a mesh address: $plan"; fail=1 ;; esac

# Public open: the Status card names its real servers and no mesh address
top; tap "Public open"
sleep 6
page_text; cp "$OUT/page.txt" "$OUT/dns-fb-public.txt"
srv=$(grep -F "Preset servers in use:" "$OUT/page.txt" | head -1)
echo "$srv"
case "$srv" in
  *"1.1.1.1"*) ;;
  *) echo "::error::the Status card does not list 1.1.1.1 for Public open: $srv"; fail=1 ;;
esac
case "$srv" in *"10.0.0."*|*"10.1.0."*) echo "::error::Public open lists a mesh address: $srv"; fail=1 ;; esac
grep -qF "Mesh resolver:" "$OUT/page.txt" || { echo "::error::the Status card has no mesh health verdict"; fail=1; }
for w in "Copy all" "Export to Markdown"; do
  grep -qxF -- "$w" "$OUT/page.txt" || { echo "::error::no '$w' button on the DNS page"; fail=1; }
done

adb logcat -d -s AndroidRuntime:E > "$OUT/crashes.txt" 2>&1 || true
grep -A1 "FATAL EXCEPTION" "$OUT/crashes.txt" > "$OUT/fatal.txt" || true
grep -q "Process: $PKG" "$OUT/fatal.txt" && { echo "::error::the app crashed"; fail=1; }
exit $fail
