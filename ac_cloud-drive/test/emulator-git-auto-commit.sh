#!/bin/bash
# #1419 Emulator flow: Auto Commit on a dirty fixture clone.
#   1. Sync (top menu) lists Manual Sync ▸ Auto Commit All.
#   2. the fixture's sheet: Manual Sync has Auto Commit; a tap commits; Logs shows the result; the fixture pulled back
#      to the host has a new HEAD whose message is the rule's (subject "auto: N files (+a −d, size) in F folders").
#   3. Configs ▸ Git: the Auto Commit section (rule, sample, Rule | OpenRouter model, the picker).
# The clone lives on shared storage: git runs in-process in Cloud Drive (#1408 libs:git-core), with Drive's own all-files access.
# Every step leaves a screenshot in <outdir>/shots. Usage: emulator-git-auto-commit.sh <drive.apk> <outdir>
set -u
APK=${1:?drive apk}; OUT=${2:-out}
PKG=com.diegonmarcos.clouddrive
NAME=cloudy-fixture
mkdir -p "$OUT/shots"
fail() { echo "::error title=Drive Auto Commit::$*"; adb exec-out screencap -p > "$OUT/shots/fail.png" 2>/dev/null; adb logcat -d -v threadtime > "$OUT/logcat.txt" 2>/dev/null; exit 1; }
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
# Drive in front: whatever took focus is noted, then Drive is brought back (singleTask, its state kept)
front() { local f; f=$(adb shell dumpsys window | tr -d '\r' | grep -m1 mCurrentFocus); case "$f" in *"$PKG"*) return 0;; esac; note "focus was: $f"; adb shell am start -n "$act" >/dev/null; sleep 4; tap '^Continue with local files$' && sleep 3; true; }
need() { dump >/dev/null; for t in "$@"; do has "$t" || return 1; done; }

adb shell settings delete global cloud_nav_ui_mode >/dev/null 2>&1 || true

# ── the fixture: one clone with a commit, then dirty in two folders (a modified file, two new ones) ──
WORK=$(mktemp -d); FIX=$WORK/$NAME
git init -q -b main "$FIX"
( cd "$FIX" && echo one > a.txt && git add a.txt && git -c user.name=t -c user.email=t@t commit -q -m one \
  && git config branch.main.remote origin && git config branch.main.merge refs/heads/main \
  && git update-ref refs/remotes/origin/main HEAD && echo x > .git/FETCH_HEAD \
  && printf 'one\ntwo\n' > a.txt && mkdir -p docs/notes && printf 'n1\nn2\nn3\n' > docs/notes/new.md && echo b > b.txt )
BASE=$(git -C "$FIX" rev-parse HEAD)
tar -C "$WORK" -cf "$OUT/fixture.tar" "$NAME"

adb uninstall "$PKG" >/dev/null 2>&1 || true
adb install -r -g "$APK" || fail "install failed"
adb shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow || true
act=$(adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "$PKG" | tr -d '\r' | tail -1)
launch; adb shell am force-stop "$PKG"
# #1452 the Cloud-Store page lists the store's repositories only (a clone on shared storage belongs to Emulated): the fixture lives under the app's own files.
DIR=/data/data/$PKG/files
adb shell "run-as $PKG sh -c 'rm -rf files/fixtures; mkdir -p files/fixtures'" >/dev/null 2>&1
adb push "$FIX" /data/local/tmp/ >/dev/null && adb shell "run-as $PKG sh -c 'cp -r /data/local/tmp/$NAME files/fixtures/'" && adb shell rm -rf "/data/local/tmp/$NAME" || fail "fixture copy failed"
adb shell "run-as $PKG sh -c 'mkdir -p files/git-sync'" || fail "no files dir"
adb shell "run-as $PKG sh -c 'cat > files/git-sync/repos.json'" <<JSON
[{"id":"fixture-1","name":"$NAME","path":"$DIR/fixtures/$NAME","remoteUrl":"https://example.invalid/diegonmarcos/$NAME.git"}]
JSON
adb shell "run-as $PKG sh -c 'rm -f shared_prefs/git_storage.xml'" >/dev/null 2>&1 || true
launch
front; dump >/dev/null; has 'Sync All' && back
open_git || fail "no Git page: $(texts)"
shot 00-git

# 1. the Sync menu: Manual Sync ▸ Auto Commit All
front; tap '^Sync$' || fail "no Sync in the top menu: $(texts)"; sleep 2
shot 10-sync-menu-auto-commit-all
need 'Manual Sync' 'Auto Commit All' || fail "the Sync menu has no Auto Commit All: $(texts)"
back

# 2. the sheet: Manual Sync ▸ Auto Commit, tapped
front; tap "^$NAME\$" || fail "no $NAME row: $(texts)"; sleep 2
for i in 1 2 3 4; do front; need 'Auto Commit' && break; swipe_up; done
shot 20-sheet-auto-commit
need 'Manual Sync' 'Auto Commit' 'Stash' || fail "the sheet's Manual Sync has no Auto Commit: $(texts)"
front; tap '^Auto Commit$' || fail "Auto Commit not tappable"
for i in $(seq 1 20); do sleep 2; dump >/dev/null; grep -qi 'auto commit: [0-9a-f]\{5,\}' "$OUT/ui.xml" && break; done
shot 21-sheet-auto-commit-result
for i in 1 2 3; do dump >/dev/null; has 'Goto' || break; back; done
front; tap '^Logs$' && sleep 3 && { tap '^App$' >/dev/null 2>&1; sleep 3; }
shot 30-logs-auto-commit
grep -qi 'auto commit' "$OUT/ui.xml" || note "Logs shows no auto commit line: $(texts)"
back

# the commit itself, read on the host from the pulled-back clone
mkdir -p "$WORK/after" && adb exec-out "run-as $PKG tar -C files/fixtures -cf - $NAME" | tar -C "$WORK/after" -xf - || fail "fixture pull failed"
A="$WORK/after/$NAME"
git -C "$A" log -1 --format=%B > "$OUT/commit-message.txt" 2>/dev/null || fail "no git log in the pulled clone"
cat "$OUT/commit-message.txt"
[ "$(git -C "$A" rev-parse HEAD)" != "$BASE" ] || fail "Auto Commit made no commit"
[ "$(git -C "$A" rev-parse HEAD~1)" = "$BASE" ] || fail "the new commit is not on top of the fixture's"
head -1 "$OUT/commit-message.txt" | grep -qE '^auto: 3 files \(\+5 −0, [0-9.]+ [KMG]?B\) in 2 folders$' || fail "subject is not the rule's: $(head -1 "$OUT/commit-message.txt")"
grep -qx 'docs/notes — 1 file (+3 −0, 9 B)' "$OUT/commit-message.txt" || fail "no folder line for docs/notes"
grep -qx 'a.txt (+1 −0)' "$OUT/commit-message.txt" || fail "no file line for a.txt"
[ -z "$(git -C "$A" status --porcelain)" ] || fail "the tree is still dirty after Auto Commit"

# 3. Configs ▸ Git ▸ Auto Commit
front; tap '^Configs$' || fail "no Configs in the top bar: $(texts)"; sleep 3
for i in 1 2 3; do tap '^Git$' && break; sleep 2; done; sleep 3
for i in 1 2 3 4 5 6; do need 'Message writer' && break; swipe_up; done
shot 40-configs-git-auto-commit
need 'Auto Commit' 'Sample message' 'Message writer' 'Rule (default)' 'OpenRouter model' || fail "Configs ▸ Git has no Auto Commit section: $(texts)"
front; tap '^OpenRouter model$' || fail "no OpenRouter model chip"; sleep 4
for i in 1 2; do need 'Message writer' && break; swipe_up; done
shot 41-configs-git-auto-commit-model
dump >/dev/null; grep -q 'text="Model: ' "$OUT/ui.xml" || fail "no model picker: $(texts)"
tap '^Model: ' && sleep 4 && shot 42-configs-model-picker && back
tap '^Rule \(default\)$' || true
echo "auto commit walk: green"
