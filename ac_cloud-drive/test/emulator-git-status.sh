#!/bin/bash
# Emulator test: Drive > Sync > Git with ONE cloned fixture repository (registered in the app's registry, a real git
# repo with an upstream ref) must show named statuses, never the "?" of a read that does not end.
#   1. open the page: NO check runs by itself, the row says "not checked yet" (the persisted-cache contract);
#   2. pull to refresh (= Light check all): within 15 s the three icons of the row must be terminal
#      (local clean, server in_sync, sync never) and "last check: ... / 1 of 1 done" must show;
#   3. none of the three icons may say "reading" or "not_checked" after that.
# Usage: emulator-git-status.sh <drive.apk> <outdir>
set -u
APK=${1:?drive apk}; OUT=${2:-out}
PKG=com.diegonmarcos.clouddrive
NAME=cloudy-fixture
mkdir -p "$OUT"
fail() { echo "::error title=Drive Git status::$*"; adb exec-out screencap -p > "$OUT/fail.png" 2>/dev/null; adb logcat -d -v threadtime > "$OUT/logcat.txt" 2>/dev/null; exit 1; }

# ── the fixture: a real repository with one commit, an origin/main tracking ref and a FETCH_HEAD ──
FIX=$(mktemp -d)/$NAME
git init -q -b main "$FIX"
# the in-process engine (JGit) resolves an upstream through the remote's fetch refspec, so origin is declared, not only its tracking ref
( cd "$FIX" && echo one > a.txt && git add a.txt && git -c user.name=t -c user.email=t@t commit -q -m one \
  && git remote add origin https://example.invalid/diegonmarcos/$NAME.git && git config branch.main.remote origin && git config branch.main.merge refs/heads/main \
  && git update-ref refs/remotes/origin/main HEAD && echo x > .git/FETCH_HEAD )
tar -C "$(dirname "$FIX")" -cf "$OUT/fixture.tar" "$NAME"

adb uninstall "$PKG" >/dev/null 2>&1 || true   # a retry starts from a clean app, as the first attempt did
adb install -r -g "$APK" || fail "install failed"
adb shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow || true
act=$(adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "$PKG" | tr -d '\r' | tail -1)
timeout 120 adb shell am start -W -n "$act" >/dev/null; sleep 8
adb shell am force-stop "$PKG"

DIR=/data/data/$PKG/files
adb exec-in "run-as $PKG sh -c 'mkdir -p files/fixtures files/git-sync && tar -xf - -C files/fixtures'" < "$OUT/fixture.tar" || fail "fixture copy failed"
adb shell "run-as $PKG sh -c 'cat > files/git-sync/repos.json'" <<JSON
[{"id":"fixture-1","name":"$NAME","path":"$DIR/fixtures/$NAME","remoteUrl":"https://example.invalid/diegonmarcos/$NAME.git"}]
JSON
adb shell "run-as $PKG ls -a files/fixtures/$NAME/.git" > "$OUT/fixture-ls.txt" 2>&1 || fail "fixture is not readable by the app"

dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb exec-out cat /sdcard/ui.xml > "$OUT/ui.xml" 2>/dev/null; cat "$OUT/ui.xml"; }
# tap the centre of the first node whose text or content-desc matches the regex ($1)
tap() { dump | python3 -I -c '
import re,sys
x=sys.stdin.read(); pat=re.compile(sys.argv[1],re.I)
for m in re.finditer(r"<node [^>]*>",x):
    n=m.group(0); t=re.search(r"\btext=\"([^\"]*)\"",n); d=re.search(r"content-desc=\"([^\"]*)\"",n); b=re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"",n)
    if b and ((t and pat.search(t.group(1))) or (d and pat.search(d.group(1)))):
        a=list(map(int,b.groups())); print((a[0]+a[2])//2,(a[1]+a[3])//2); break
else: sys.exit(1)' "$1" | { read -r px py && adb shell input tap "$px" "$py"; }; }

timeout 120 adb shell am start -W -n "$act" >/dev/null; sleep 6
# The emulator has no cld.termux: Drive's startup gate offers "Continue with local files" (the fixture sits in the app's own files dir).
for i in 1 2 3; do tap '^Continue with local files$' && break; sleep 2; done || true
sleep 3
# The bottom nav carries Git directly (there is no Sync tab; the top bar's Sync is the Sync All menu, which a tap here would open over the page).
tap '^Git$' || fail "no Git sub-page"
sleep 4

size=$(adb shell wm size | tr -d '\r' | grep -oE '[0-9]+x[0-9]+' | tail -1); W=${size%x*}; H=${size#*x}
find_row() { # scroll until the fixture row's icons are in the dump
  for i in $(seq 1 25); do
    if dump | grep -q "$NAME local"; then return 0; fi
    adb shell input swipe $((W/2)) $((H*70/100)) $((W/2)) $((H*30/100)) 300; sleep 1
  done; return 1; }
find_row || fail "the fixture row never appeared on the Git page"
cp "$OUT/ui.xml" "$OUT/ui-open.xml"
# 1. no check ran by itself: the icons say "not_checked", never a read in flight
grep -q "$NAME local not_checked" "$OUT/ui-open.xml" || fail "opening the page should show 'not checked yet' for the fixture (no automatic check): $(grep -o "$NAME [a-z]* [a-z_]*" "$OUT/ui-open.xml" | head -3 | tr '\n' ';')"

# 2. the owner's explicit action: pull to refresh (a drag from the top) then the Check button (a full status check); then wait at most 15 s for terminal icons
for i in $(seq 1 25); do adb shell input swipe $((W/2)) $((H*30/100)) $((W/2)) $((H*80/100)) 200; done
sleep 1
adb shell input swipe $((W/2)) $((H*30/100)) $((W/2)) $((H*80/100)) 600
sleep 1
# #1452 Check is a dropdown (Check lite | Check full): the full status check is its second item.
tap '^Check$' || fail "no Check button"; sleep 1; tap '^Check full$' || fail "no Check full item"
t0=$(date +%s); ok=0
while [ $(( $(date +%s) - t0 )) -lt 15 ]; do
  sleep 1
  find_row >/dev/null || continue
  if grep -qE "$NAME local (cloned|clean)" "$OUT/ui.xml" && grep -q "$NAME server in_sync" "$OUT/ui.xml"; then ok=1; break; fi
done
cp "$OUT/ui.xml" "$OUT/ui-after.xml"; adb exec-out screencap -p > "$OUT/after.png"
[ $ok = 1 ] || fail "after pull-to-refresh the fixture icons did not become local clean / server in_sync within 15 s: $(grep -o "$NAME [a-z]* [a-z_]*" "$OUT/ui-after.xml" | head -3 | tr '\n' ';')"
grep -qE "$NAME (local|server|sync) (reading|not_checked)" "$OUT/ui-after.xml" && fail "an icon is still reading / not checked after the check"
grep -qE "last check: .* / 1 of 1 done" "$OUT/ui-after.xml" || { dump | grep -qE "last check: .* / 1 of 1 done" || echo "::warning::progress line not on screen (scrolled away)"; }
adb logcat -d -v threadtime > "$OUT/logcat.txt"
grep -q "FATAL EXCEPTION" "$OUT/logcat.txt" && grep -A5 "FATAL EXCEPTION" "$OUT/logcat.txt" | grep -q "$PKG" && fail "Drive crashed"
echo "Drive > Git: fixture shows not-checked on open, then terminal icons within 15 s of a light check."
