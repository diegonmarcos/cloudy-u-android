#!/bin/bash
# #1934 Emulator WALK of the unified repo control sheet: opens Drive > Git, taps the fixture repository's row, then taps EVERY control the declaration
# (ac_cloud-drive/build.json ui.git_control_sheet, the per-repo tabs) names, once, on that fixture, and records per control: tapped or not, the screen text
# that followed and a screenshot (out/walk/<id>.png, out/walk.tsv). A destructive control opens its confirm and the walk CANCELS it; the hosting-platform commands
# (Archive Remote, Delete Remote) are never confirmed: they are verified against a local mock by HostingApiTest. A control that cannot be found fails the walk.
#   1. open the page: NO check runs by itself, the row says "not checked yet" (the persisted-cache contract);
#   2. pull to refresh (= Light check all): within 15 s the three icons of the row must be terminal
#      (local clean, server in_sync, sync never) and "last check: ... / 1 of 1 done" must show;
#   3. none of the three icons may say "reading" or "not_checked" after that.
# Usage: emulator-git-status.sh <drive.apk> <outdir>
set -u
APK=${1:?drive apk}; OUT=${2:-out}
PKG=com.diegonmarcos.clouddrive
NAME=cloudy-fixture
BJ=$(dirname "$0")/../build.json
mkdir -p "$OUT"
fail() { echo "::error title=Drive Git status::$*"; adb exec-out screencap -p > "$OUT/fail.png" 2>/dev/null; adb logcat -d -v threadtime > "$OUT/logcat.txt" 2>/dev/null; exit 1; }

# ── the fixture: a real repository with one commit, an origin/main tracking ref and a FETCH_HEAD ──
FIX=$(mktemp -d)/$NAME
git init -q -b main "$FIX"
# the in-process engine (JGit) resolves an upstream through the remote's fetch refspec, so origin is declared, not only its tracking ref
( cd "$FIX" && echo one > a.txt && git add a.txt && git -c user.name=t -c user.email=t@t commit -q -m one \
  && git remote add origin https://example.invalid/diegonmarcos/$NAME.git && git config branch.main.remote origin && git config branch.main.merge refs/heads/main \
  && git update-ref refs/remotes/origin/main HEAD && echo x > .git/FETCH_HEAD )
cp -r "$FIX" "$(dirname "$FIX")/$NAME-2"; cp -r "$FIX" "$(dirname "$FIX")/$NAME-3"   # #1934 three repositories for the all-repos sheet
tar -C "$(dirname "$FIX")" -cf "$OUT/fixture.tar" "$NAME" "$NAME-2" "$NAME-3"

adb uninstall "$PKG" >/dev/null 2>&1 || true   # a retry starts from a clean app, as the first attempt did
adb install -r -g "$APK" || fail "install failed"
adb shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow || true
act=$(adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "$PKG" | tr -d '\r' | tail -1)
timeout 120 adb shell am start -W -n "$act" >/dev/null; sleep 8
adb shell am force-stop "$PKG"

DIR=/data/data/$PKG/files
adb exec-in "run-as $PKG sh -c 'mkdir -p files/fixtures files/git-sync && tar -xf - -C files/fixtures'" < "$OUT/fixture.tar" || fail "fixture copy failed"
adb shell "run-as $PKG sh -c 'cat > files/git-sync/repos.json'" <<JSON
[{"id":"fixture-1","name":"$NAME","path":"$DIR/fixtures/$NAME","remoteUrl":"https://example.invalid/diegonmarcos/$NAME.git"},{"id":"fixture-2","name":"$NAME-2","path":"$DIR/fixtures/$NAME-2","remoteUrl":"https://example.invalid/diegonmarcos/$NAME-2.git"},{"id":"fixture-3","name":"$NAME-3","path":"$DIR/fixtures/$NAME-3","remoteUrl":"https://example.invalid/diegonmarcos/$NAME-3.git"}]
JSON
adb shell "run-as $PKG ls -a files/fixtures/$NAME/.git" > "$OUT/fixture-ls.txt" 2>&1 || fail "fixture is not readable by the app"

snap() { mkdir -p "$OUT/dumps"; cp "$OUT/ui.xml" "$OUT/dumps/$1.xml" 2>/dev/null; }
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb exec-out cat /sdcard/ui.xml > "$OUT/ui.xml" 2>/dev/null; cat "$OUT/ui.xml"; }
# tap the centre of the first node whose text or content-desc matches the regex ($1)
tap() { dump | python3 -I -c '
import re,sys
x=sys.stdin.read(); pat=re.compile(sys.argv[1],re.I); hit=None
for m in re.finditer(r"<node [^>]*>",x):
    n=m.group(0); t=re.search(r"\btext=\"([^\"]*)\"",n); d=re.search(r"content-desc=\"([^\"]*)\"",n); b=re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"",n)
    if b and ((t and pat.search(t.group(1))) or (d and pat.search(d.group(1)))):
        a=list(map(int,b.groups())); hit=((a[0]+a[2])//2,(a[1]+a[3])//2)   # the LAST match: the open sheet is drawn over the page (its Sync / Clone tabs, not the top bar buttons)
if hit: print(*hit)
else: sys.exit(1)' "$1" | { read -r px py && adb shell input tap "$px" "$py"; }; }

timeout 120 adb shell am start -W -n "$act" >/dev/null; sleep 6
# The emulator has no cld.termux: Drive's startup gate offers "Continue with local files" (the fixture sits in the app's own files dir).
for i in 1 2 3; do tap '^Continue with local files$' && break; sleep 2; done || true
sleep 3
# The bottom nav carries Git directly (there is no Sync tab; the top bar's Sync is the Sync All menu, which a tap here would open over the page).
tap '^Git$' || fail "no Git sub-page"
sleep 4

size=$(adb shell wm size | tr -d '\r' | grep -oE '[0-9]+x[0-9]+' | tail -1); W=${size%x*}; H=${size#*x}

find_row() {
  for i in $(seq 1 25); do
    if dump | grep -q "$NAME"; then return 0; fi
    adb shell input swipe $((W/2)) $((H*70/100)) $((W/2)) $((H*30/100)) 300; sleep 1
  done; return 1; }
size=$(adb shell wm size | tr -d '\r' | grep -oE '[0-9]+x[0-9]+' | tail -1); W=${size%x*}; H=${size#*x}
find_row || fail "the fixture row never appeared on the Git page"
tap "^${NAME}\$" || fail "cannot open the fixture row"
sleep 3
dump | grep -q 'content-desc="Drag handle"' || fail "the control sheet did not open"


# tap the first clickable node to the right of the node matching ($1), on the same row (the top bar's overflow button carries no text)
tap_after() { dump | python3 -I -c '
import re,sys
x=sys.stdin.read(); pat=re.compile(sys.argv[1],re.I); nodes=[]
for m in re.finditer(r"<node [^>]*>",x):
    n=m.group(0); b=re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"",n); t=re.search(r"\btext=\"([^\"]*)\"",n)
    if b: nodes.append((list(map(int,b.groups())),t.group(1) if t else "",'clickable="true"' in n))
for a,t,c in nodes:
    if pat.search(t):
        cy=(a[1]+a[3])//2
        # the toolbar pills carry no clickable flag in the dump: the first box right of the match, on its row, at least a finger wide (the gear is the one after it)
        cand=sorted([b for b,t2,c2 in nodes if b[0]>=a[2] and b[1]<=cy<=b[3] and b[2]-b[0]>=20 and b[3]-b[1]<=60], key=lambda b:b[0])
        if cand: print((cand[0][0]+cand[0][2])//2,cy); sys.exit(0)
sys.exit(1)' "$1" | { read -r px py && adb shell input tap "$px" "$py" < /dev/null; }; }

# a control may leave the sheet (Folder opens Files, Settings opens its own sheet): come back to the sheet before the next tap
ensure_sheet() {
  dump | grep -q 'content-desc="Drag handle"' && return 0
  for b in 1 2 3; do adb shell input keyevent KEYCODE_BACK < /dev/null; sleep 2; dump | grep -q 'content-desc="Drag handle"' && return 0; done
  # Folder lands in Drive Files, which BACK does not leave: relaunch the app on the Git page (the fixture is in its files dir)
  adb shell am force-stop "$PKG" < /dev/null; sleep 1; timeout 120 adb shell am start -W -n "$act" < /dev/null >/dev/null; sleep 6
  for i in 1 2 3; do tap '^Continue with local files$' && break; sleep 2; done; sleep 2
  tap '^Git$'; sleep 4
  if [ "$1" = bulk ]; then tap_after '^Logs$' && sleep 1 && tap '^All repos sheet$'; else tap "^${NAME}\$"; fi
  sleep 3; dump | grep -q 'content-desc="Drag handle"'; }
mkdir -p "$OUT/walk"; : > "$OUT/walk.tsv"
# the walk list: tab label, control id, control label, danger/confirm flag (from the declaration itself)
python3 -I - "$BJ" per > "$OUT/walk-list.tsv" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))["ui"]["git_control_sheet"]
h = d["header"]
bulk = sys.argv[2] == "bulk"
if not bulk:
    for c in [x for x in h["actions"] if x["id"] != "hd.repo_list"] + [h["refresh"]]: print("\t".join(["header", c["id"], c["label"], "0"]))
for t in (d["bulk"]["tabs"] if bulk else d["tabs"]):
    print("\t".join([t["label"], "tab:" + t["id"], t["label"], "0"]))
    for g in t["groups"]:
        for c in g["controls"]:
            if c.get("kind") in ("field", "preview", "output") or c.get("disabled"): continue
            print("\t".join([t["label"], c["id"], c["label"], "1" if (c.get("confirm") or c.get("danger")) else "0"]))
PY
ok=0; miss=0
while IFS=$'\t' read -r -u 3 tab id label danger; do
  case "$id" in tab:Manage|tab:manage) adb shell input swipe $((W*80/100)) $((H*62/100)) $((W*20/100)) $((H*62/100)) 200 < /dev/null; sleep 1;; esac
  case "$id" in tab:*) for r in 1 2 3 4 5 6; do dump | grep -q 'text="Workflow"' && break; adb shell input swipe $((W/2)) $((H*40/100)) $((W/2)) $((H*80/100)) 250 < /dev/null; sleep 1; done;; esac
  case "$id" in tab:*) tap "^$(printf '%s' "$label" | sed 's/[][\.*^$/]/\\&/g')$" ; sleep 1; adb exec-out screencap -p > "$OUT/walk/${id#tab:}.png"; dump >/dev/null; snap "tab-${id#tab:}"; printf '%s\t%s\ttab\tshown\t%s\n' "$tab" "$id" "walk/${id#tab:}.png" >> "$OUT/walk.tsv"; continue;; esac
  pat="^$(printf '%s' "$label" | sed 's/[][\.*^$/]/\\&/g')$"
  found=0
  for s in 1 2 3; do tap "$pat" && { found=1; break; }; adb shell input swipe $((W/2)) $((H*70/100)) $((W/2)) $((H*35/100)) 250; sleep 1; done
  if [ $found = 0 ]; then miss=$((miss+1)); printf '%s\t%s\t%s\tNOT FOUND\t-\n' "$tab" "$id" "$label" >> "$OUT/walk.tsv"; continue; fi
  sleep 2; adb exec-out screencap -p > "$OUT/walk/$id.png"
  case "$id" in ov.goto.web|ov.goto.folder|mg.tools.settings)
    # these controls LEAVE the sheet (in-app web viewer, Drive Files, the settings sheet): the destination must differ from the sheet's own screen
    if dump | grep -q 'content-desc="Drag handle"' && dump | grep -q 'text="Goto &amp; Quick Actions"' && [ "$id" != mg.tools.settings ]; then miss=$((miss+1)); printf '%s\t%s\t%s\tNOT OPENED\t-\n' "$tab" "$id" "$label" >> "$OUT/walk.tsv"; continue; fi;;
  esac
  after=$(dump | grep -oE 'text="[^"]{3,80}"' | head -40 | tr '\n' ' ')
  # a confirm dialog is never confirmed
  if dump | grep -q 'git_sheet_confirm\|Confirm'; then tap '^Cancel$' || adb shell input keyevent KEYCODE_BACK; fi
  ensure_sheet per || fail "cannot get the sheet back after $id"
  ok=$((ok+1)); printf '%s\t%s\t%s\ttapped\t%s\n' "$tab" "$id" "$label" "walk/$id.png" >> "$OUT/walk.tsv"
done 3< "$OUT/walk-list.tsv"

# ── the all-repos sheet: More ▸ All repos sheet, the same walk over its own controls ──
go_git_page() {
  for i in 1 2 3; do dump | grep -q 'text="Logs"' && ! dump | grep -q 'content-desc="Drag handle"' && return 0; adb shell input keyevent KEYCODE_BACK < /dev/null; sleep 2; done
  adb shell am force-stop "$PKG" < /dev/null; sleep 1; timeout 120 adb shell am start -W -n "$act" < /dev/null >/dev/null; sleep 6
  for i in 1 2 3; do tap '^Continue with local files$' && break; sleep 2; done; sleep 2; tap '^Git$'; sleep 4; dump | grep -q 'text="Logs"'; }
go_git_page || { snap no-git-page; fail "cannot get back to the Git page"; }
snap git-page
tap_after '^Logs$' || { snap no-more; fail "no More menu"; }; sleep 1; snap more-open; tap '^All repos sheet$' || { snap no-bulk-item; fail "no All repos sheet item"; }; sleep 3
dump | grep -q 'content-desc="Drag handle"' || fail "the all-repos sheet did not open"
python3 -I - "$BJ" bulk > "$OUT/walk-list-bulk.tsv" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))["ui"]["git_control_sheet"]
h = d["header"]
bulk = sys.argv[2] == "bulk"
if not bulk:
    for c in [x for x in h["actions"] if x["id"] != "hd.repo_list"] + [h["refresh"]]: print("\t".join(["header", c["id"], c["label"], "0"]))
for t in (d["bulk"]["tabs"] if bulk else d["tabs"]):
    print("\t".join([t["label"], "tab:" + t["id"], t["label"], "0"]))
    for g in t["groups"]:
        for c in g["controls"]:
            if c.get("kind") in ("field", "preview", "output") or c.get("disabled"): continue
            print("\t".join([t["label"], c["id"], c["label"], "1" if (c.get("confirm") or c.get("danger")) else "0"]))
PY
while IFS=$'\t' read -r -u 3 tab id label danger; do
  case "$id" in tab:Manage|tab:manage) adb shell input swipe $((W*80/100)) $((H*62/100)) $((W*20/100)) $((H*62/100)) 200 < /dev/null; sleep 1;; esac
  case "$id" in tab:*) tap "^$(printf '%s' "$label" | sed 's/[][\\.*^$/]/\\\\&/g')$"; sleep 1; adb exec-out screencap -p > "$OUT/walk/bulk-${id#tab:}.png"; printf 'bulk:%s\t%s\ttab\tshown\n' "$tab" "$id" >> "$OUT/walk.tsv"; continue;; esac
  pat="^$(printf '%s' "$label" | sed 's/[][\\.*^$/]/\\\\&/g')$"; found=0
  for s in 1 2 3; do tap "$pat" && { found=1; break; }; adb shell input swipe $((W/2)) $((H*70/100)) $((W/2)) $((H*35/100)) 250; sleep 1; done
  if [ $found = 0 ]; then miss=$((miss+1)); printf 'bulk:%s\t%s\t%s\tNOT FOUND\t-\n' "$tab" "$id" "$label" >> "$OUT/walk.tsv"; continue; fi
  sleep 1; adb exec-out screencap -p > "$OUT/walk/bulk-$id.png"
  if dump | grep -q 'text="Confirm"'; then tap '^Cancel$' || adb shell input keyevent KEYCODE_BACK; fi
  ensure_sheet bulk || fail "cannot get the all-repos sheet back after $id"
  ok=$((ok+1)); printf 'bulk:%s\t%s\t%s\ttapped\twalk/bulk-%s.png\n' "$tab" "$id" "$label" "$id" >> "$OUT/walk.tsv"
done 3< "$OUT/walk-list-bulk.tsv"
echo "control sheet walk: $ok controls tapped, $miss not found"
adb logcat -d -v threadtime > "$OUT/logcat.txt"
grep -q "FATAL EXCEPTION" "$OUT/logcat.txt" && grep -A5 "FATAL EXCEPTION" "$OUT/logcat.txt" | grep -q "$PKG" && fail "Drive crashed during the walk"
[ "$miss" = 0 ] || fail "$miss control(s) of the declaration were not found on screen (see walk.tsv)"
