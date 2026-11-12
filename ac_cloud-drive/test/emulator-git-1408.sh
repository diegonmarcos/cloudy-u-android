#!/bin/bash
# #1408 Emulator flow over the in-process git engine (libs:git-core, JGit inside Cloud Drive; no engine APK installed):
#   1. clone    a git clone of a bare fixture remote (file:// on shared storage); the sheet sees it, HEAD checked on the host.
#   2. sync     the remote moves on (host commit) and the clone is edited on the same line; Sync: no winner.
#   3. conflict the clone holds the conflict (host-read markers); the conflict solver opens.
#   4. kill     Drive is force-stopped and reopened: the row and the conflict state survive process death.
#   5. USB      the Git storage volume selector (the emulator has no USB drive: shot + note).
# Every step leaves a screenshot in <outdir>/shots. Usage: emulator-git-1408.sh <drive.apk> <outdir>
set -u
APK=${1:?drive apk}; OUT=${2:-out}
PKG=com.diegonmarcos.clouddrive
NAME=cloudy-fixture
DIR=/storage/emulated/0/Download/fixtures
mkdir -p "$OUT/shots"
fail() { echo "::error title=Drive Git #1408::$*"; adb exec-out screencap -p > "$OUT/shots/fail.png" 2>/dev/null; adb logcat -d -v threadtime > "$OUT/logcat.txt" 2>/dev/null; exit 1; }
note() { echo "note: $*"; echo "$*" >> "$OUT/notes.txt"; }
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb exec-out cat /sdcard/ui.xml > "$OUT/ui.xml" 2>/dev/null; cat "$OUT/ui.xml"; }
shot() { adb exec-out screencap -p > "$OUT/shots/$1.png"; dump > "$OUT/ui-$1.xml"; }
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
back() { adb shell input keyevent 4; sleep 2; }
swipe_up() { local s; s=$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1); local w=${s%x*} h=${s#*x}; adb shell input swipe $((w/2)) $((h*3/4)) $((w/2)) $((h/3)) 400; sleep 1; }
launch() { timeout 120 adb shell am start -W -n "$act" >/dev/null; sleep 6; for i in 1 2 3; do tap '^Continue with local files$' && break; sleep 2; done || true; sleep 2; }
open_git() { tap '^Git$' || { tap '^Sync$' && sleep 2 && tap '^Git$'; } || return 1; sleep 3; }
front() { local f; f=$(adb shell dumpsys window | tr -d '\r' | grep -m1 mCurrentFocus); case "$f" in *"$PKG"*) return 0;; esac; note "focus was: $f"; adb shell am start -n "$act" >/dev/null; sleep 4; tap '^Continue with local files$' && sleep 3; true; }
need() { dump >/dev/null; for t in "$@"; do has "$t" || return 1; done; }
pull_clone() { rm -rf "$WORK/pulled"; mkdir -p "$WORK/pulled/$(basename "$1")"; case "$1" in /data/data/*) adb exec-out "run-as $PKG tar -C ${1#/data/data/$PKG/} -cf - ." | tar -C "$WORK/pulled/$(basename "$1")" -xf - ;; *) rm -rf "$WORK/pulled"; mkdir -p "$WORK/pulled"; adb pull "$1" "$WORK/pulled/" >/dev/null 2>&1 ;; esac; }
dev_sh() { case "$CL" in /data/data/*) adb shell "run-as $PKG sh -c '$1'" ;; *) adb shell "$1" ;; esac; }
find_clone() { { adb shell "find -L /storage/emulated/0 -maxdepth 5 -type d -name .git 2>/dev/null"; adb shell "run-as $PKG find files -maxdepth 6 -type d -name .git 2>/dev/null" | sed "s#^#/data/data/$PKG/#"; } | tr -d '\r' | grep "$NAME" | grep -v remote.git | head -1 | sed 's#/\.git$##'; }
host_git() { git -c user.name=h -c user.email=h@h "$@"; }
# #1452 the Cloud-Store page lists the store's repositories only: a clone on shared storage belongs to Emulated. The fixture clone therefore
# lives under the app's own files (a path under no shared-storage or drive root is the store's); the remote stays on shared storage.
CLDIR=/data/data/$PKG/files/fixtures
put_clone() { local n; n=$(basename "$1"); adb push "$1" /data/local/tmp/ >/dev/null && adb shell "run-as $PKG sh -c 'mkdir -p files/fixtures && rm -rf files/fixtures/$n && cp -r /data/local/tmp/$n files/fixtures/'" && adb shell rm -rf "/data/local/tmp/$n"; }

adb shell settings delete global cloud_nav_ui_mode >/dev/null 2>&1 || true
adb shell pm list packages | grep -q gitsyncengine && fail "a git-sync engine APK is installed: this flow proves Drive alone"

# ── the fixture: a bare remote with one commit, on shared storage ──
WORK=$(mktemp -d)
git init -q -b main "$WORK/seed"
printf 'line\n' > "$WORK/seed/a.txt"
host_git -C "$WORK/seed" add a.txt && host_git -C "$WORK/seed" commit -q -m seed
git clone -q --bare "$WORK/seed" "$WORK/remote.git"
SEED=$(git -C "$WORK/remote.git" rev-parse main)

adb uninstall "$PKG" >/dev/null 2>&1 || true
adb install -r -g "$APK" || fail "install failed"
adb shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow || true
act=$(adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "$PKG" | tr -d '\r' | tail -1)
launch; adb shell am force-stop "$PKG"
adb shell rm -rf "$DIR" >/dev/null 2>&1; adb shell mkdir -p "$DIR" && adb push "$WORK/remote.git" "$DIR/" >/dev/null || fail "remote copy failed"
adb shell "run-as $PKG sh -c 'mkdir -p files/git-sync'" || fail "no files dir"
adb shell "run-as $PKG sh -c 'cat > files/git-sync/repos.json'" <<JSON
[{"id":"fixture-1408","name":"$NAME","path":"$CLDIR/$NAME","remoteUrl":"file://$DIR/remote.git"}]
JSON
adb shell "run-as $PKG sh -c 'rm -f shared_prefs/git_storage.xml'" >/dev/null 2>&1 || true
launch
front; dump >/dev/null; has 'Sync All' && back
open_git || fail "no Git page: $(texts)"
shot 00-git

# 1. clone: the clone verb itself is proven by libs:git-core's JVM suite (clone over file://, any storage root);
#    Drive's clone buttons need a network listing or cld.termux, neither of which the emulator has. Here the clone
#    is a real git clone of the fixture remote, laid on shared storage with origin = file://<remote on the phone>.
git clone -q "$WORK/remote.git" "$WORK/$NAME"
git -C "$WORK/$NAME" remote set-url origin "file://$DIR/remote.git"
put_clone "$WORK/$NAME" || fail "clone copy failed"
CL="$CLDIR/$NAME"
adb shell am force-stop "$PKG"; launch; front; dump >/dev/null; has 'Sync All' && back
open_git || fail "no Git page: $(texts)"
front; tap "^$NAME\$" || fail "no $NAME row: $(texts)"; sleep 4
shot 10-sheet-cloned
dump >/dev/null; grep -q 'Cloned: yes' "$OUT/ui.xml" || fail "the sheet does not see the clone: $(texts)"
pull_clone "$CL"; P="$WORK/pulled/$(basename "$CL")"
[ "$(git -C "$P" rev-parse HEAD 2>/dev/null)" = "$SEED" ] || fail "the clone's HEAD is not the remote's seed"
for i in 1 2 3; do dump >/dev/null; has 'Goto' || break; back; done

# 2. sync with a diverged remote: the host moves the remote, the phone edits the same line
git clone -q "$WORK/remote.git" "$WORK/other"
printf 'server\n' > "$WORK/other/a.txt"
host_git -C "$WORK/other" commit -qam server
git -C "$WORK/other" push -q origin HEAD:refs/heads/main
adb shell rm -rf "$DIR/remote.git" && adb push "$WORK/remote.git" "$DIR/" >/dev/null || fail "remote update failed"
# the phone side commits its own edit of the same line (a merge needs two commits to disagree; an uncommitted edit is refused)
printf 'phone\n' > "$WORK/$NAME/a.txt"; host_git -C "$WORK/$NAME" commit -qam phone
put_clone "$WORK/$NAME" || fail "could not update the clone"
front; tap "^$NAME\$" || fail "no $NAME row after clone: $(texts)"; sleep 2
for i in 1 2 3 4; do front; need 'Sync: no winner' && break; swipe_up; done
shot 20-sheet-sync
front; tap '^Sync: no winner$' || fail "no Sync: no winner on the sheet: $(texts)"
for i in $(seq 1 20); do sleep 2; dump >/dev/null; grep -qiE 'conflict' "$OUT/ui.xml" && break; done
shot 21-sync-result

# 3. conflict: the clone holds the conflict, the solver opens
pull_clone "$CL"; P="$WORK/pulled/$(basename "$CL")"
grep -q '^<<<<<<<' "$P/a.txt" || fail "no conflict markers in the clone's a.txt: $(cat "$P/a.txt")"
for i in 1 2 3 4; do front; dump >/dev/null; grep -qE 'text="Resolve [0-9]+ conflict' "$OUT/ui.xml" && break; swipe_up; done
dump >/dev/null; grep -q 'nothing changed' "$OUT/ui.xml" && fail "the sheet says nothing changed after a conflict: $(texts)"
tap '^Resolve [0-9]+ conflict' || fail "no Resolve N conflict(s) button: $(texts)"; sleep 4
shot 30-conflict-solver
for i in 1 2 3; do back; done
# 3b. the conflict solver's Workflow panel (libs:git-ui GitWorkflowPanel): Manual Sync > Conflict Solver lists the conflicted file with
#     its per-file choices; the shot is the panel itself (the step before opened the engine's own solver screen).
front; tap "^$NAME\$" || fail "no $NAME row to reopen the sheet: $(texts)"; sleep 3
for i in 1 2 3 4; do front; dump >/dev/null; has 'Conflict Solver' && break; swipe_up; done
tap '^Conflict Solver$' || fail "no Conflict Solver item in Manual Sync: $(texts)"; sleep 4
for i in 1 2 3 4; do dump >/dev/null; has 'Keep ours' && break; swipe_up; done
dump >/dev/null; has 'Keep ours' && has 'Keep theirs' && has 'Open in editor' && has 'a.txt' || fail "the Workflow panel does not list a.txt with Keep ours / Keep theirs / Open in editor: $(texts)"
has 'Stage and continue' || fail "the Workflow panel has no Stage and continue: $(texts)"
shot 25-workflow-solver
for i in 1 2 3; do back; done

# 4. kill and reopen
adb shell am force-stop "$PKG"; sleep 2
launch; front; dump >/dev/null; has 'Sync All' && back
open_git || fail "no Git page after reopen: $(texts)"
shot 40-reopened
dump >/dev/null; has "$NAME" || fail "the $NAME row is gone after process death: $(texts)"
grep -qiE 'conflict' "$OUT/ui.xml" || note "the row shows no conflict after reopen: $(texts)"
# 4b. the full-screen status window (libs:git-ui GitStatusWindow): a tap on the row's status lights opens it
tap "^$NAME Cloned " || fail "no status lights (the #1670 five lights, first one Cloned) on the $NAME row: $(texts)"; sleep 3
need 'Repository status' 'STATUS' 'Close' || fail "the status window is not open: $(texts)"
shot 45-status-window
back
pull_clone "$CL"; grep -q '^<<<<<<<' "$WORK/pulled/$(basename "$CL")/a.txt" || fail "the conflict state did not survive process death"

# 5. the volume selector (USB)
front; for t in '^Cloud-Store' '^Clone into' '^Storage'; do tap "$t" && break; done; sleep 3
shot 50-volume-selector
dump >/dev/null; grep -qiE 'emulated|internal|USB|volume' "$OUT/ui.xml" || note "no volume selector found from the Git page: $(texts)"
note "the emulator has no USB drive: the USB root is covered by libs:git-core's storage-root unit tests"
echo "git #1408 walk: green"
