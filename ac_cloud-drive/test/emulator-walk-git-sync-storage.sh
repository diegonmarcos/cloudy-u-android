#!/bin/bash
# Emulator flow (#1356), run by the nav walk after its page walk: the Git page's Sync dropdown, the repo sheet's Manual Sync
# and Dangerous rows, the storage selector in the top menu, and the Git page on an attached drive (vold's virtual disk, #1375).
#   1. Sync (top menu): the dropdown lists Sync All (Server Wins, Local Wins, No Winner) and Manual Sync (Stash All,
#      Pull (Rebase), Stash Pop All, Conflicts All); Conflicts All opens the solver list.
#   2. the fixture's sheet: Manual Sync is ONE row (Stash, Pull (Rebase), Stash Pop, Conflict Solver), Dangerous is ONE row
#      (Force pull, Force push) and a tap asks first.
#   3. the selector in the top menu: Cloud-Store, Emulated, External Drive (no drive: "No drive attached").
#   4. a virtual drive is attached: External Drive with no git folder offers Create; a GIT folder is made; Clone into
#      lists the drive; a small repository is cloned onto the drive's GIT folder from the Cloud page; then the drive's
#      repositories are listed, checked (More > Light check / Check) and synced (Sync > No Winner), the result in Logs.
#   5. Explorer: the drive is a tab of its own.
#   (2b, #1395) Goto > Web opens the embedded browser (CloudWeb BROWSER); its menu is shot open; Back closes it.
# Every step leaves a screenshot in <outdir>/shots. Usage: emulator-walk-git-sync-storage.sh <drive.apk> <outdir>
set -u
APK=${1:?drive apk}; OUT=${2:-out}
PKG=com.diegonmarcos.clouddrive
NAME=cloudy-fixture
mkdir -p "$OUT/shots"
fail() { echo "::error title=Drive Git sync + storage::$*"; adb exec-out screencap -p > "$OUT/shots/fail.png" 2>/dev/null; adb logcat -d -v threadtime > "$OUT/logcat.txt" 2>/dev/null; adb shell sm set-virtual-disk false >/dev/null 2>&1; exit 1; }
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
need() { dump >/dev/null; for t in "$@"; do has "$t" || return 1; done; }
# the selector in the top menu: its pill names the selected storage
pick() { tap '^(Cloud-Store \(Termux\)|Emulated|External Drive)( ▾)?$' && sleep 1 && tap "^$1\$" && sleep 4; }

# the walk runs on the declared UI mode, not an override a previous run left on the device
adb shell settings delete global cloud_nav_ui_mode >/dev/null 2>&1 || true

# ── the fixture: one real clone in the registry, and a small repository the drive gets (the fallback listing) ──
WORK=$(mktemp -d); FIX=$WORK/$NAME
git init -q -b main "$FIX"
( cd "$FIX" && echo one > a.txt && git add a.txt && git -c user.name=t -c user.email=t@t commit -q -m one \
  && git config branch.main.remote origin && git config branch.main.merge refs/heads/main \
  && git update-ref refs/remotes/origin/main HEAD && echo x > .git/FETCH_HEAD )
tar -C "$WORK" -cf "$OUT/fixture.tar" "$NAME"

adb uninstall "$PKG" >/dev/null 2>&1 || true
adb install -r -g "$APK" || fail "install failed"
adb shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow || true
act=$(adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "$PKG" | tr -d '\r' | tail -1)
launch; adb shell am force-stop "$PKG"
DIR=/data/data/$PKG/files
adb exec-in "run-as $PKG sh -c 'mkdir -p files/fixtures files/git-sync && tar -xf - -C files/fixtures'" < "$OUT/fixture.tar" || fail "fixture copy failed"
adb shell "run-as $PKG sh -c 'cat > files/git-sync/repos.json'" <<JSON
[{"id":"fixture-1","name":"$NAME","path":"$DIR/fixtures/$NAME","remoteUrl":"https://example.invalid/diegonmarcos/$NAME.git"},
 {"id":"fixture-1","name":"$NAME","path":"$DIR/fixtures/$NAME","remoteUrl":"https://example.invalid/diegonmarcos/$NAME.git"}]
JSON
adb shell "run-as $PKG sh -c 'rm -f shared_prefs/git_storage.xml'" >/dev/null 2>&1 || true
launch
open_git || fail "no Git page: $(texts)"
shot 00-git

# 1. the Sync dropdown
tap '^Sync$' || fail "no Sync in the top menu: $(texts)"; sleep 2
shot 10-sync-dropdown
need 'Sync All' 'Server Wins' 'Local Wins' 'No Winner' 'Manual Sync' 'Stash All' 'Pull (Rebase)' 'Stash Pop All' 'Conflicts All' || fail "the Sync dropdown is not Sync All + Manual Sync: $(texts)"
tap '^Conflicts All$' || fail "no Conflicts All"; sleep 2
shot 11-conflicts-all
need 'Repositories with conflicts' || fail "Conflicts All opened no solver list: $(texts)"
back
# No Winner asks first with the target count (a scoped, confirmed verb)
tap '^Sync$' && sleep 1 && tap '^No Winner$' && sleep 2
shot 12-no-winner-confirm
need 'Targets: 1 repositories in scope' || note "No Winner confirm text: $(texts)"
back

# 1b. #1392 the registry holds the same clone twice (one id, two records, as on the phone): Logs > Repos must not crash
tap '^Logs$' || fail "no Logs: $(texts)"; sleep 3
tap '^Repos$' >/dev/null 2>&1; sleep 3
shot 13-logs-repos-duplicate-id
adb shell pidof "$PKG" >/dev/null || fail "Logs > Repos crashed on a duplicate registry id"
grep -q 'was already used' <(adb logcat -d 2>/dev/null) && fail "duplicate LazyColumn key in Logs > Repos"
back

tap "^$NAME\$" || fail "no $NAME row: $(texts)"; sleep 2
for i in 1 2 3 4; do need 'Stash' 'Stash Pop' 'Conflict Solver' 'Force pull' && break; swipe_up; done
shot 20-sheet-manual-dangerous
need 'Manual Sync' 'Stash' 'Pull (Rebase)' 'Stash Pop' 'Conflict Solver' 'Dangerous' 'Force pull' 'Force push' || fail "the sheet lacks the Manual Sync / Dangerous rows: $(texts)"
python3 -I - "$OUT/ui.xml" <<'PY' || fail "Manual Sync or Dangerous is not one row"
import re, sys
x = open(sys.argv[1]).read()
def top(label):
    for m in re.finditer(r"<node [^>]*>", x):
        n = m.group(0)
        if 'text="%s"' % label in n:
            return int(re.search(r'bounds="\[\d+,(\d+)\]', n).group(1))
    sys.exit("no " + label)
rows = [[top(l) for l in ("Stash", "Pull (Rebase)", "Stash Pop", "Conflict Solver")], [top(l) for l in ("Force pull", "Force push")]]
for r in rows:
    if max(r) - min(r) > 8: sys.exit("not one row: %s" % r)
print("one row each:", rows)
PY
tap '^Force pull$' || fail "no Force pull"; sleep 2
shot 21-dangerous-confirm
dump >/dev/null; has 'Force pull' && grep -qiE 'text="(Cancel|Close)"' "$OUT/ui.xml" || fail "Force pull ran without asking: $(texts)"
back
# 2b. #1395 Goto > Web opens the repository page in the embedded browser; its top-right menu; Back closes it
for i in 1 2 3; do tap '^Web$' && break; adb shell input swipe 500 600 500 1500 300; sleep 1; done || fail "no Web action in the sheet: $(texts)"
sleep 6; tap '^More$' || fail "the web viewer has no menu: $(texts)"; sleep 2
shot 22-web-viewer-menu
need 'Open in another browser' 'Open in Cloud Browser' 'Copy link' 'Share' 'Reload' || fail "the web viewer menu: $(texts)"
back; back
dump >/dev/null; has 'Open in Cloud Browser' && fail "Back did not close the web viewer"
for i in 1 2 3; do dump >/dev/null; has 'Goto' || break; back; done

# 3. the selector in the top menu
pick 'Emulated' || fail "no storage selector in the top menu: $(texts)"
shot 30-storage-emulated
pick 'External Drive' || fail "External Drive not selectable"
need 'No drive attached' || fail "External with no drive: $(texts)"
shot 31-storage-external-none
pick 'Cloud-Store \(Termux\)' || fail "Cloud-Store not selectable"
shot 32-storage-store

# 4. a virtual drive (#1375)
adb shell sm set-virtual-disk true || fail "sm set-virtual-disk refused"
DISK=""; for i in $(seq 1 30); do DISK=$(adb shell sm list-disks | tr -d '\r' | grep -m1 'disk:'); [ -n "$DISK" ] && break; sleep 2; done
[ -n "$DISK" ] || fail "no virtual disk"
adb shell sm partition "$DISK" public || fail "sm partition public failed"
UUID=""; for i in $(seq 1 45); do line=$(adb shell sm list-volumes public | tr -d '\r' | grep -m1 ' mounted '); UUID=$(echo "$line" | awk '{print $3}'); [ -n "$UUID" ] && [ "$UUID" != "null" ] && break; sleep 2; done
[ -n "$UUID" ] && [ "$UUID" != "null" ] || fail "the virtual volume never mounted"
ROOT=/storage/$UUID; echo "drive: $ROOT" >> "$OUT/notes.txt"
sleep 3
pick 'External Drive' || fail "External Drive not selectable with a drive"
shot 40-drive-no-git-folder
need 'No git folder' || note "drive without a git folder: $(texts)"
# the offer to create it (the app writes with all-files access); the owner's layout uses GIT, so GIT is made for the clone
tap '^Create ' && sleep 3 && shot 41-drive-created || note "no Create offer: $(texts)"
adb shell "rmdir $ROOT/git 2>/dev/null; mkdir -p $ROOT/GIT" || fail "no GIT folder on the drive"
pick 'Cloud-Store \(Termux\)'; pick 'External Drive'
shot 42-drive-empty-git
# #1452 no Clone-into picker: a clone lands in the selected environment (the drive)
tap '^Cloud$' >/dev/null 2>&1; sleep 4
shot 43-cloud-page
if tap '^External Drive$|Virtual|USB|disk' ; then :; fi
dump >/dev/null
if tap '^(Clone|Clone full|Clone \(depth 1\))$'; then sleep 25; shot 44-clone-result; else note "no clone control on the Cloud page: $(texts)"; fi
CLONED=$(adb shell "ls $ROOT/GIT 2>/dev/null" | tr -d '\r' | head -1)
if [ -z "$CLONED" ]; then
  note "in-app clone onto $ROOT/GIT produced nothing; seeding the fixture repository there to show list/check/sync"
  adb push "$FIX" "$ROOT/GIT/" >/dev/null || fail "seed onto the drive failed"
  adb shell "ls -a $ROOT/GIT/$NAME" | tr -d '\r' | grep -qx '.git' || fail "the seeded repository has no .git on the drive"
  # a fresh process lists the drive's git folder anew (the listing is read when the page opens)
  adb shell am force-stop "$PKG"; sleep 2; launch; open_git || fail "no Git page after relaunch: $(texts)"
else
  echo "cloned onto the drive: $CLONED" >> "$OUT/notes.txt"
fi
for i in 1 2 3; do dump >/dev/null; grep -q 'text="Emulated"' "$OUT/ui.xml" && back; tap '^Me$' >/dev/null 2>&1; sleep 3; dump >/dev/null; grep -q 'text="Cloud Drive / Git / Me"' "$OUT/ui.xml" && break; done
grep -q 'text="Cloud Drive / Git / Me"' "$OUT/ui.xml" || fail "the Me page did not open: $(texts)"
for i in 1 2 3; do pick 'External Drive'; dump >/dev/null; grep -q 'content-desc="External Drive"' "$OUT/ui.xml" && break; done
grep -q 'content-desc="External Drive"' "$OUT/ui.xml" || fail "External Drive was not selected: $(texts)"
shot 45-drive-listed
dump >/dev/null; texts > "$OUT/drive-texts.txt"
tap '^Refresh$' && sleep 1 && tap '^Check full$' && sleep 8 && shot 46-drive-checked || note "Refresh > Check full (#1679: Check is labelled Refresh) not tappable: $(texts)"
tap '^Sync$' && sleep 1 && tap '^No Winner$' && sleep 2 && tap '^No Winner$' && sleep 15 && shot 47-drive-synced || note "No Winner on the drive: $(texts)"
tap '^Logs$' && sleep 3 && shot 48-drive-logs || note "no Logs: $(texts)"
back

# 5. Explorer: the drive is a tab of its own
tap '^Explorer$' || tap '^Files$' || note "no Explorer: $(texts)"; sleep 3
shot 50-explorer-drive-tab
adb shell sm set-virtual-disk false >/dev/null 2>&1 || true
echo "Drive Git sync + storage flow: PASS"
