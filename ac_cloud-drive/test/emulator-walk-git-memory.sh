#!/bin/bash
# Emulator flow (#1356), run by the nav walk after its page walk: the Git page keeps its exact state.
#   1. Git: tap the cloned fixture repository; its options sheet opens (the Folder chip shows);
#   2. Folder: the app moves to Explorer on the repository's folder;
#   3. Git in the bottom nav: the SAME repository's sheet is open again;
#   4. the process is killed in the background and the app relaunched: on Git the same sheet is open (SavedStateHandle).
# Every step leaves a screenshot in <outdir>/shots. Usage: emulator-walk-git-memory.sh <drive.apk> <outdir>
set -u
APK=${1:?drive apk}; OUT=${2:-out}
PKG=com.diegonmarcos.clouddrive
NAME=cloudy-fixture
mkdir -p "$OUT/shots"
fail() { echo "::error title=Drive Git page memory::$*"; adb exec-out screencap -p > "$OUT/shots/fail.png" 2>/dev/null; adb logcat -d -v threadtime > "$OUT/logcat.txt" 2>/dev/null; exit 1; }
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb exec-out cat /sdcard/ui.xml > "$OUT/ui.xml" 2>/dev/null; cat "$OUT/ui.xml"; }
shot() { adb exec-out screencap -p > "$OUT/shots/$1.png"; dump > "$OUT/ui-$1.xml"; }
# centre of the first node whose text or content-desc matches the regex ($1)
where() { dump | python3 -I -c '
import re,sys
x=sys.stdin.read(); pat=re.compile(sys.argv[1],re.I)
for m in re.finditer(r"<node [^>]*>",x):
    n=m.group(0); t=re.search(r"\btext=\"([^\"]*)\"",n); d=re.search(r"content-desc=\"([^\"]*)\"",n); b=re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"",n)
    if b and ((t and pat.search(t.group(1))) or (d and pat.search(d.group(1)))):
        a=list(map(int,b.groups())); print((a[0]+a[2])//2,(a[1]+a[3])//2); break
else: sys.exit(1)' "$1"; }
tap() { where "$1" | { read -r px py && adb shell input tap "$px" "$py"; }; }
has() { grep -q "text=\"$1\"" "$OUT/ui.xml"; }
texts() { grep -oE 'text="[^"]+"' "$OUT/ui.xml" | sed 's/text="//;s/"$//' | sort -u | tr '\n' ';'; }
launch() { timeout 120 adb shell am start -W -n "$act" >/dev/null; sleep 6; for i in 1 2 3; do tap '^Continue with local files$' && break; sleep 2; done || true; sleep 2; }
# the Git page: its bottom-nav section, else Sync then its Git sub-page
open_git() { tap '^Git$' || { tap '^Sync$' && sleep 2 && tap '^Git$'; } || return 1; sleep 3; }
sheet_open() { dump >/dev/null; has Folder && has "$NAME"; }

# ── the fixture: one real cloned repository in the registry (emulator-git-status.sh's) ──
FIX=$(mktemp -d)/$NAME
git init -q -b main "$FIX"
( cd "$FIX" && echo one > a.txt && git add a.txt && git -c user.name=t -c user.email=t@t commit -q -m one \
  && git config branch.main.remote origin && git config branch.main.merge refs/heads/main \
  && git update-ref refs/remotes/origin/main HEAD && echo x > .git/FETCH_HEAD )
tar -C "$(dirname "$FIX")" -cf "$OUT/fixture.tar" "$NAME"

# the page walk before this flow may have let the app update itself to a newer build: start clean on the APK under test
adb uninstall "$PKG" >/dev/null 2>&1 || true
adb install -r -g "$APK" || fail "install failed"
adb shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow || true
act=$(adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "$PKG" | tr -d '\r' | tail -1)
launch; adb shell am force-stop "$PKG"
DIR=/data/data/$PKG/files
adb exec-in "run-as $PKG sh -c 'mkdir -p files/fixtures files/git-sync && tar -xf - -C files/fixtures'" < "$OUT/fixture.tar" || fail "fixture copy failed"
adb shell "run-as $PKG sh -c 'cat > files/git-sync/repos.json'" <<JSON
[{"id":"fixture-1","name":"$NAME","path":"$DIR/fixtures/$NAME","remoteUrl":"https://example.invalid/diegonmarcos/$NAME.git"}]
JSON
launch

# 1. the sheet
open_git || fail "no Git page: $(texts)"
shot 00-git
tap "^$NAME\$" || fail "no $NAME row: $(texts)"
sleep 2; shot 01-sheet-open
sheet_open || fail "tapping $NAME opened no options sheet: $(texts)"

# 2. Folder -> Explorer
tap '^Folder$' || fail "no Folder chip"
sleep 3; shot 02-explorer-folder
dump >/dev/null; has Folder && fail "Folder did not leave the Git page"

# 3. back to Git from the bottom nav: the same sheet
open_git || fail "no Git in the bottom nav: $(texts)"
sleep 2; shot 03-git-again
sheet_open || fail "back on Git, $NAME's sheet is not open: $(texts)"

# 4. process death in the background, relaunch: the same sheet
adb shell input keyevent KEYCODE_HOME; sleep 2
adb shell am kill "$PKG"; sleep 2
adb shell pidof "$PKG" >/dev/null && echo "note: am kill left the process alive"
# the relaunch passes the startup gate the same way the first launch did (no terminal on the emulator)
launch
shot 04-after-process-death
if ! sheet_open; then
    echo "note: the relaunch did not land on Git; opening it"
    open_git || fail "no Git after the relaunch: $(texts)"
    sleep 2; shot 05-git-after-process-death
    sheet_open || fail "after a process death, $NAME's sheet is not open: $(texts)"
fi

adb logcat -d -v threadtime > "$OUT/logcat.txt"
grep -A5 "FATAL EXCEPTION" "$OUT/logcat.txt" | grep -q "$PKG" && fail "Drive crashed"
echo "Drive > Git: repo sheet -> Folder -> Git again: same sheet; same after a process death."
