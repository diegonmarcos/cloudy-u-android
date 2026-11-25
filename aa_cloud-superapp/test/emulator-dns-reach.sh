#!/usr/bin/env bash
# #1899 Emulator proof of Configs > Network > DNS > Test reachability by IP:
#   bash aa_cloud-superapp/test/emulator-dns-reach.sh <superapp.apk> <out dir> <build.json>
# Opens page:config/dns, reads the whole page (every heading and button the page had before must still be there), checks the
# read-out under Test lookup names every declared target before a run, taps "Test reachability by IP", waits for the run and
# prints the read-out: the verdict, the two paths compared, the name lookups and one row per target, check and path with
# reachable, latency, path and error. The emulator has no VPN, so the normal path must say so and the direct run is bound to
# the emulator's own network. Fails on a missing heading or button, a target that is not shown, a row count that does not
# match the declaration, a row without its fields, or a crash. Which targets answer depends on the runner's network, so the
# verdict itself is printed, not asserted.
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
readout() { grep -F -- "$1" "$OUT/page.txt" | awk '{ print length($0) "\t" $0 }' | sort -rn | head -1 | cut -f2- | sed 's/ | /\n/g'; }

# The declaration the page must show: "Label ip" per target, and how many rows a run draws.
DECL="$(python3 - "$BJ" <<'PY'
import json, sys
r = json.load(open(sys.argv[1]))['ui']['dns']['reachability']
print(len(r['targets']) * len(r['checks']))
for t in r['targets']: print('%s %s' % (t['label'], t['ip']))
PY
)"
PER_PATH=$(printf '%s\n' "$DECL" | head -1)
TARGETS=$(printf '%s\n' "$DECL" | tail -n +2)
[ "${PER_PATH:-0}" -ge 1 ] && [ -n "$TARGETS" ] || { echo "::error::build.json declares no reachability target"; exit 2; }

wake; adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null; sleep 12
prompts
wake; adb shell am start -n "$PKG/.MainActivity" --es shortcut_action "page:config/dns" >/dev/null; sleep 8
prompts
cap dns-1-top
page_text; cp "$OUT/page.txt" "$OUT/dns-1-page.txt"
# nothing the page had is gone: every heading and button, by its exact text
for w in "Android Private DNS" "Apply to Android" "Fleet DNS" "Status" "Test lookup" "Bridges" "DNS servers" "Scan bridges and servers"; do
  grep -qxF -- "$w" "$OUT/page.txt" || { echo "::error::'$w' is missing on the DNS page"; fail=1; }
done
grep -qxF "Test reachability by IP" "$OUT/page.txt" || { echo "::error::no 'Test reachability by IP' button on the DNS page"; fail=1; }
first=$(printf '%s\n' "$TARGETS" | head -1)
plan="$(readout "$first")"
printf '%s\n' "$TARGETS" | while IFS= read -r t; do
  case "$plan" in *"$t"*) ;; *) echo "::error::the read-out does not name the declared target '$t' before a run"; echo x >> "$OUT/missing.txt";; esac
done
[ -s "$OUT/missing.txt" ] && fail=1

top
tap "Test reachability by IP"
up # the read-out sits under the button: bring it on screen
# the run: every check in parallel, each bounded by the declared timeout, then the name lookups
done_run=0
for i in 1 2 3 4 5 6 7 8 9 10 11 12; do
  sleep 5; dump; texts > "$OUT/now.txt"
  if grep -qF "Name lookups (Android's resolver)" "$OUT/now.txt"; then done_run=1; break; fi
done
[ "$done_run" = 1 ] || { echo "::error::the reachability run did not finish within 60 s (still: $(grep -F 'Connecting by IP' "$OUT/now.txt" | head -1))"; fail=1; }
cap dns-2-reach-verdict; up; cap dns-3-reach-rows; up; cap dns-4-reach-rows-2
page_text; cp "$OUT/page.txt" "$OUT/dns-2-page.txt"
readout "Name lookups (Android's resolver)" > "$OUT/reach.txt"
echo "──── the reachability read-out on the emulator ────"
cat "$OUT/reach.txt"
echo "───────────────────────────────────────────────────"
R="$OUT/reach.txt"
[ -s "$R" ] || { echo "::error::no reachability read-out on the page after the run"; fail=1; }
head -1 "$R" > "$OUT/verdict.txt"
grep -qE '^(✓|✗) ' "$OUT/verdict.txt" || { echo "::error::the read-out does not start with a verdict line: $(cat "$OUT/verdict.txt")"; fail=1; }
grep -q '^Paths: ' "$R" || { echo "::error::no 'Paths:' line comparing the two paths"; fail=1; }
grep -qF 'normal path (no VPN up): ' "$R" || { echo "::error::the normal path does not say that no VPN is up on the emulator"; fail=1; }
printf '%s\n' "$TARGETS" > "$OUT/targets.txt"
while IFS= read -r t; do
  grep -qxF -- "$t" "$R" || { echo "::error::no rows for the declared target '$t'"; fail=1; }
done < "$OUT/targets.txt"
rows=$(grep -c 'reachable: ' "$R" || true)
normal=$(grep -c 'path: normal path (no VPN up)$' "$R" || true)
direct=$(grep -c 'path: direct, bound to ' "$R" || true)
echo "rows=$rows normal=$normal direct=$direct declared per path=$PER_PATH"
[ "$normal" -eq "$PER_PATH" ] || { echo "::error::$normal rows on the normal path, the declaration asks for $PER_PATH"; fail=1; }
if grep -qF 'direct: not run (no non-VPN network)' "$R"; then
  echo "::error::the direct run found no network to bind to on the emulator"; fail=1
else
  [ "$direct" -eq "$PER_PATH" ] || { echo "::error::$direct rows on the direct path, the declaration asks for $PER_PATH"; fail=1; }
fi
[ "$rows" -eq $((normal + direct)) ] || { echo "::error::$rows result rows for $((normal + direct)) path rows"; fail=1; }
full=$(grep -cE 'reachable: (yes|NO) · latency: [0-9]+ ms · carried by: .+' "$R" || true)
[ "$full" -eq "$rows" ] || { echo "::error::$full of $rows rows carry reachable, latency and the carrying path"; fail=1; }
unreach=$(grep -c 'reachable: NO ' "$R" || true)
errs=$(grep -c '^ *error: .\+' "$R" || true)
[ "$errs" -eq "$unreach" ] || { echo "::error::$unreach unreachable rows but $errs error lines"; fail=1; }
# the page below the new section is still there after the run
for w in "Test lookup" "Bridges" "DNS servers" "Scan bridges and servers"; do
  grep -qxF -- "$w" "$OUT/page.txt" || { echo "::error::'$w' is missing on the DNS page after the run"; fail=1; }
done

adb logcat -d -s AndroidRuntime:E > "$OUT/crashes.txt" 2>&1 || true
# only the SuperApp's own process counts (the emulator's system UI may fall over when its stock launcher is disabled)
grep -A1 "FATAL EXCEPTION" "$OUT/crashes.txt" > "$OUT/fatal.txt" || true
grep -q "Process: $PKG" "$OUT/fatal.txt" && { echo "::error::the app crashed"; fail=1; }
exit $fail
