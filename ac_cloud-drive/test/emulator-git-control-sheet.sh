#!/bin/bash
# #1934 Emulator WALK of the unified repo control sheet: opens Drive > Git, taps the fixture repository's row, then taps EVERY control the declaration
# (ac_cloud-drive/build.json ui.git_control_sheet, the per-repo tabs) names, once, on that fixture, and records per control: tapped or not, the screen text
# that followed and a screenshot (out/walk/<id>.png, out/walk.tsv). A destructive control opens its confirm and the walk CANCELS it; the hosting-platform commands
# (Archive Remote, Delete Remote) are never confirmed: they are verified against a local mock by HostingApiTest. A control that cannot be found fails the walk.
#   1. open the page: NO check runs by itself, the row says "not checked yet" (the persisted-cache contract);
#   2. pull to refresh (= Light check all): within 15 s the three icons of the row must be terminal
#      (local clean, server in_sync, sync never) and "last check: ... / 1 of 1 done" must show;
#   3. none of the three icons may say "reading" or "not_checked" after that.
# Usage: emulator-git-control-sheet.sh <drive.apk> <outdir> [cloned|notcloned|all-repos|bulk-all|bulk-selected|bulk-cloned|all]   (one phase per CI job, each with its own emulator;
# WALK_BUDGET = seconds the phase may spend on controls, default 2400: a control reached after that is recorded NOT RUN (time budget) and fails the phase)
set -u
APK=${1:?drive apk}; OUT=${2:-out}; PHASE=${3:-all}; BUDGET=${WALK_BUDGET:-2400}; T0=$(date +%s)
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
# the bulk phases run Stash on a dirty working copy: -2 and -3 carry a tracked change (-1 stays clean, it is the per-repo walk's fixture)
echo dirty > "$(dirname "$FIX")/$NAME-2/a.txt"; echo dirty > "$(dirname "$FIX")/$NAME-3/a.txt"
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
NOTCLONED=cloudy-notcloned
adb shell "run-as $PKG sh -c 'cat > files/git-listing-cache.json'" <<JSON
{"schema":"git-listing-cache/v1","slots":{"fleet":{"atMs":$(date +%s)000,"complete":true,"viaGh":false,"rungId":"","repos":[{"name":"$NOTCLONED","owner":"diegonmarcos","private":false,"fork":false,"defaultBranch":"main","description":"fixture: a repository that is not cloned","sizeKb":12,"updatedAt":"2026-01-01T00:00:00Z","pushedAt":"2026-01-01T00:00:00Z","cloneUrl":"https://example.invalid/diegonmarcos/$NOTCLONED.git","sshUrl":"","webUrl":"https://example.invalid/diegonmarcos/$NOTCLONED","stars":0,"language":""}]},"own":{"atMs":$(date +%s)000,"complete":true,"viaGh":false,"rungId":"","repos":[{"name":"$NOTCLONED","owner":"diegonmarcos","private":false,"fork":false,"defaultBranch":"main","description":"fixture","sizeKb":12,"updatedAt":"2026-01-01T00:00:00Z","pushedAt":"2026-01-01T00:00:00Z","cloneUrl":"https://example.invalid/diegonmarcos/$NOTCLONED.git","sshUrl":"","webUrl":"https://example.invalid/diegonmarcos/$NOTCLONED","stars":0,"language":""}]}}}
JSON
adb shell "run-as $PKG ls -a files/fixtures/$NAME/.git" > "$OUT/fixture-ls.txt" 2>&1 || fail "fixture is not readable by the app"

snap() { mkdir -p "$OUT/dumps"; cp "$OUT/ui.xml" "$OUT/dumps/$1.xml" 2>/dev/null; }
dump() { adb shell rm -f /sdcard/ui.xml </dev/null; adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb exec-out cat /sdcard/ui.xml > "$OUT/ui.xml" 2>/dev/null; cat "$OUT/ui.xml"; }
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
    if dump | grep -q "text=\"${1:-$NAME}\""; then return 0; fi
    adb shell input swipe $((W/2)) $((H*70/100)) $((W/2)) $((H*30/100)) 300 < /dev/null; sleep 1
  done; return 1; }
snap() { mkdir -p "$OUT/dumps"; cp "$OUT/ui.xml" "$OUT/dumps/$1.xml" 2>/dev/null; }

# ── robust targeting: the sheet's controls carry their declared id as a testTag exposed as resource-id (testTagsAsResourceId) ──
# tap_rid <resource-id> prints "enabled" | "disabled" on stdout and taps the node; returns 1 when the node is not on screen
tap_rid() { dump | python3 -I -c '
import re,sys
x=sys.stdin.read(); rid=sys.argv[1]; hit=None
for m in re.finditer(r"<node [^>]*>",x):
    n=m.group(0)
    if re.search(r"resource-id=\"%s\"" % re.escape(rid), n):
        b=re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"",n); a=list(map(int,b.groups()))
        hit=((a[0]+a[2])//2,(a[1]+a[3])//2,"disabled" if "enabled=\"false\"" in n else "enabled")
if hit: print(*hit)
else: sys.exit(1)' "$1" | { read -r px py st && { [ "$st" = enabled ] && adb shell input tap "$px" "$py" < /dev/null; echo "$st"; }; }; }
tap_text() { dump | python3 -I -c '
import re,sys
x=sys.stdin.read(); pat=re.compile(sys.argv[1]); hit=None
for m in re.finditer(r"<node [^>]*>",x):
    n=m.group(0); t=re.search(r"\btext=\"([^\"]*)\"",n); d=re.search(r"content-desc=\"([^\"]*)\"",n); b=re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"",n)
    if b and ((t and pat.search(t.group(1))) or (d and pat.search(d.group(1)))):
        a=list(map(int,b.groups())); hit=((a[0]+a[2])//2,(a[1]+a[3])//2)
if hit: print(*hit)
else: sys.exit(1)' "$1" | { read -r px py && adb shell input tap "$px" "$py" < /dev/null; }; }
has_rid() { dump | grep -q "resource-id=\"$1\""; }
# scroll ONLY inside the tab body (never a drag on the sheet): swipe within the body node's visible bounds
scroll_body() { dump | python3 -I -c '
import re,sys
x=sys.stdin.read(); H=int(sys.argv[2]); best=None
for m in re.finditer(r"<node [^>]*>",x):
    n=m.group(0)
    if re.search(r"resource-id=\"(git_sheet_scroll|git_bulk_scroll)\"",n):
        b=re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"",n); best=list(map(int,b.groups()))
if not best: sys.exit(1)
top=max(best[1],0); bot=min(best[3],H); cx=(best[0]+best[2])//2
print(cx, top+(bot-top)*3//4, top+(bot-top)//4)' x "$H" | { read -r cx y1 y2 && adb shell input swipe "$cx" "$y1" "$cx" "$y2" 300 < /dev/null; }; }

sheet_open() { has_rid git_sheet_tab_overview; }
# every step leaves evidence in $OUT/close: the node tapped (bounds), the uiautomator dump, the window list and the top activity
close_diag() { mkdir -p "$OUT/close"; dump > "$OUT/close/$1.xml"; adb shell dumpsys window windows < /dev/null 2>/dev/null | grep -E "Window #|mCurrentFocus|mFocusedApp" > "$OUT/close/$1.windows.txt"; adb shell dumpsys activity top < /dev/null 2>/dev/null | grep -E "ACTIVITY|Added Fragments|mResumed|ViewRootImpl" | head -20 > "$OUT/close/$1.top.txt"; adb exec-out screencap -p > "$OUT/close/$1.png"; }
close_sheet() {
  dump | grep -q 'text="Confirm"' && { tap_text '^Cancel$'; sleep 1; }   # a confirm dialog is its own window (no resource-ids): cancel it first
  close_diag "$1-before"
  grep -o '<node [^>]*git_sheet_hd.close[^>]*>' "$OUT/close/$1-before.xml" | grep -o 'bounds="[^"]*"\|enabled="[^"]*"\|clickable="[^"]*"' | tr '\n' ' ' > "$OUT/close/$1-close-node.txt"
  for b in 1 2 3; do tap_rid git_sheet_hd.close >/dev/null; sleep 2; close_diag "$1-tap$b"; dump | grep -q 'resource-id="git_sheet_tab_overview"' || return 0; done
  for b in 1 2; do adb shell input keyevent KEYCODE_BACK < /dev/null; sleep 2; close_diag "$1-back$b"; dump | grep -q 'resource-id="git_sheet_tab_overview"' || return 0; done; }
# come back to the sheet after a control that leaves it (web viewer, Files, settings sheet): BACK, else relaunch and reopen the row ($1 = row text)
ensure_sheet() {
  sheet_open && return 0
  for b in 1 2 3; do adb shell input keyevent KEYCODE_BACK < /dev/null; sleep 2; sheet_open && return 0; done
  adb shell am force-stop "$PKG" < /dev/null; sleep 1; timeout 120 adb shell am start -W -n "$act" < /dev/null >/dev/null; sleep 6
  for i in 1 2 3; do tap_text '^Continue with local files$' && break; sleep 2; done; sleep 2
  tap_text '^Git$'; sleep 4; find_row "$1"; tap_text "^$1\$"; sleep 3; sheet_open; }
open_row() { find_row "$1" || { snap no-row; fail "the row $1 never appeared"; }; tap_text "^$1\$" || fail "cannot open the row $1"; sleep 3; sheet_open || { sleep 4; sheet_open || { find_row "$1"; tap_text "^$1\$"; sleep 4; }; }; sheet_open || { snap no-sheet; fail "the sheet of $1 did not open"; }; }

mkdir -p "$OUT/walk"; : > "$OUT/walk.tsv"; miss=0; ok=0
python3 -I - "$BJ" > "$OUT/lists.txt" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))["ui"]["git_control_sheet"]; h = d["header"]
def rows(tag, tabs, header):
    out = []
    if header:
        for c in [x for x in h["actions"] if x["id"] != "hd.repo_list"] + [h["refresh"]]: out.append(("header", "git_sheet_" + c["id"], c["id"], c["label"], c.get("needs_clone", False), c.get("confirm") or c.get("danger"), c.get("disabled", False), "control"))
    for t in tabs:
        out.append((t["label"], "git_sheet_tab_" + t["id"], "tab:" + t["id"], t["label"], False, False, False, "control"))
        for g in t["groups"]:
            for c in g["controls"]:
                if c.get("kind") in ("field", "preview", "output"):
                    # a readout: verified on screen (fields by their own row, preview / output by their group)
                    out.append((t["label"], "git_sheet_ctl_" + c["id"] if c["kind"] == "field" else "git_sheet_group_" + g["id"], c["id"], c["label"], False, False, False, "readout"))
                    continue
                rid = "git_sheet_interval_" + str(c["values"][0]) if c.get("kind") == "select" and c.get("values") else "git_sheet_ctl_" + c["id"]
                out.append((t["label"], rid, c["id"], c["label"], c.get("needs_clone", False), c.get("confirm") or c.get("danger"), c.get("disabled", False), "control"))
    return out
for name, tabs, header in (("per", d["tabs"], True), ("bulk", d["bulk"]["tabs"], False)):
    with open(sys.argv[1] + "." + name, "w") as f: pass
    for r in rows(name, tabs, header): print(name + "\t" + "\t".join(str(x) for x in r))
PY
rm -f "$BJ.per" "$BJ.bulk" 2>/dev/null

# walk <per|bulk> <cloned: yes|no> <row name for recovery>
# One uiautomator dump per control (probe reads it): the node's bounds and enabled flag, the open tab, and the reason text, all from the same dump.
probe() { python3 -I -c '
import re,sys
x=open(sys.argv[1]).read(); rid=sys.argv[2]; hit=None
for m in re.finditer(r"<node [^>]*>",x):
    n=m.group(0)
    if re.search(r"resource-id=\"%s\"" % re.escape(rid), n):
        b=re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"",n); a=list(map(int,b.groups()))
        hit=((a[0]+a[2])//2,(a[1]+a[3])//2,"disabled" if "enabled=\"false\"" in n else "enabled")
if hit: print(*hit)
else: sys.exit(1)' "$OUT/ui.xml" "$1"; }
# readout <rid> <label>: the texts inside the node's bounds except its label, joined by " | "; exit 1 = not on screen, 2 = nothing but the label
readout() { python3 -I -c '
import re,sys
x=open(sys.argv[1]).read(); rid=sys.argv[2]; label=sys.argv[3]; box=None; nodes=[]
for m in re.finditer(r"<node [^>]*>",x):
    n=m.group(0); b=re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"",n); t=re.search(r"\btext=\"([^\"]*)\"",n)
    if not b: continue
    a=list(map(int,b.groups()))
    if re.search(r"resource-id=\"%s\"" % re.escape(rid), n): box=a
    elif t and t.group(1): nodes.append((a,t.group(1)))
if not box: sys.exit(1)
v=[t for a,t in nodes if a[0]>=box[0] and a[1]>=box[1] and a[2]<=box[2] and a[3]<=box[3] and t!=label]
lab=any(a[0]>=box[0] and a[1]>=box[1] and a[2]<=box[2] and a[3]<=box[3] and t==label for a,t in nodes)
print(" | ".join(v))
sys.exit(0 if (v or lab) else 2)' "$OUT/ui.xml" "$1" "$2"; }
# the value the fixture makes deterministic, as an extended regex ("" = any non-empty text)
expect() { case "$1:$2" in
  per:yes:ov.cloned) echo '^yes$';; per:no:ov.cloned) echo '^no$';;
  per:yes:ov.branch) echo '^main$';; per:yes:ov.detached) echo '^no$';; per:yes:ov.unpushed) echo '^0$';; per:yes:ov.divergence) echo '^\+0/-0$';;
  per:yes:ov.meta.name) echo '^cloudy-fixture$';; per:no:ov.meta.name) echo '^cloudy-notcloned$';;
  per:*:ov.meta.owner) echo '^diegonmarcos$';;
  per:yes:ov.meta.remote_url) echo 'example\.invalid/diegonmarcos/cloudy-fixture';; per:no:ov.meta.remote_url) echo 'example\.invalid/diegonmarcos/cloudy-notcloned';;
  bulk:*:bo.count.*) echo '^[0-9]+$';;
  *) echo '';; esac; }
walk() {
  local phase=$1 cloned=$2 row=$3 curtab=overview t1=$(date +%s)
  exec 3< <(grep -P "^$phase\t" "$OUT/lists.txt" | cut -f2-)
  while IFS=$'\t' read -r -u 3 tab rid id label needs confirm disabled kind; do
    if [ $(( $(date +%s) - t1 )) -gt "$BUDGET" ]; then miss=$((miss+1)); printf '%s:%s\t%s\t%s\tNOT RUN (time budget)\t-\n' "$phase" "$cloned" "$id" "$label" >> "$OUT/walk.tsv"; continue; fi
    case "$id" in tab:*)
      curtab=${id#tab:}
      ensure_sheet "$row" || fail "cannot get the sheet back before $id"
      for k in 1 2; do tap_rid "$rid" >/dev/null && break; sleep 1; done; sleep 1
      adb exec-out screencap -p > "$OUT/walk/$phase-$cloned-${id#tab:}.png"; dump >/dev/null; snap "$phase-$cloned-tab-${id#tab:}"
      printf '%s:%s\t%s\ttab\tshown\twalk/%s-%s-%s.png\n' "$phase" "$cloned" "$id" "$phase" "$cloned" "${id#tab:}" >> "$OUT/walk.tsv"; continue;; esac
    if [ "$kind" = readout ]; then
      v=""; found=0
      for n in 0 1 2 3 4 5 6; do
        dump >/dev/null
        v=$(readout "$rid" "$label"); rc=$?
        [ $rc -ne 1 ] && { found=1; break; }
        scroll_body || break; sleep 1
      done
      wantv=$(expect "$phase:$cloned" "$id")
      if [ $found = 0 ]; then miss=$((miss+1)); printf '%s:%s\t%s\t%s\tNOT FOUND\t-\n' "$phase" "$cloned" "$id" "$label" >> "$OUT/walk.tsv"; snap "notfound-$phase-$cloned-$id"
      elif [ $rc -ne 0 ]; then miss=$((miss+1)); printf '%s:%s\t%s\t%s\tEMPTY\t-\n' "$phase" "$cloned" "$id" "$label" >> "$OUT/walk.tsv"
      elif [ -n "$wantv" ] && ! printf "%s" "$v" | grep -Eq "$wantv"; then miss=$((miss+1)); printf '%s:%s\t%s\t%s\tVALUE MISMATCH (got "%s", want %s)\t-\n' "$phase" "$cloned" "$id" "$label" "$v" "$wantv" >> "$OUT/walk.tsv"
      else ok=$((ok+1)); printf '%s:%s\t%s\t%s\tshown (value ok)\t%s\n' "$phase" "$cloned" "$id" "$label" "$v" >> "$OUT/walk.tsv"; fi
      continue
    fi
    [ "$disabled" = True ] && { printf '%s:%s\t%s\t%s\tgreyed (declared disabled)\t-\n' "$phase" "$cloned" "$id" "$label" >> "$OUT/walk.tsv"; continue; }
    dump >/dev/null
    # a recovery (web viewer, relaunch) lands on the Overview tab: go back to the tab this control lives on
    if ! grep -q 'resource-id="git_sheet_tab_overview"' "$OUT/ui.xml"; then
      ensure_sheet "$row" || fail "cannot get the sheet back before $id"
      [ "$tab" != header ] && { tap_rid "git_sheet_tab_$curtab" >/dev/null; sleep 1; }; dump >/dev/null
    fi
    # conditional groups (shown_when / hidden_when): put the option model in a state that shows them
    case "$id" in sy.strategy.*|sy.dirty.*) tap_rid git_sheet_tab_sync >/dev/null; sleep 1; tap_rid git_sheet_ctl_sy.direction.pull >/dev/null; sleep 1; dump >/dev/null;;
      sy.winner.*) tap_rid git_sheet_tab_sync >/dev/null; sleep 1; tap_rid git_sheet_ctl_sy.direction.pull >/dev/null; sleep 1; tap_rid git_sheet_ctl_sy.strategy.merge >/dev/null; sleep 1; dump >/dev/null;; esac
    st=""; found=0
    for n in 0 1 2 3 4 5 6; do
      if pos=$(probe "$rid"); then found=1; set -- $pos; st=$3; [ "$st" = enabled ] && adb shell input tap "$1" "$2" < /dev/null; break; fi
      scroll_body || break; sleep 1; dump >/dev/null
    done
    if [ $found = 0 ]; then miss=$((miss+1)); printf '%s:%s\t%s\t%s\tNOT FOUND\t-\n' "$phase" "$cloned" "$id" "$label" >> "$OUT/walk.tsv"; snap "notfound-$phase-$cloned-$id"; continue; fi
    sleep 1; adb exec-out screencap -p > "$OUT/walk/$phase-$cloned-$id.png"
    if [ "$cloned" = no ] && [ "$needs" = True ]; then
      # not cloned: the control must be greyed, and the declared reason must be on screen
      if [ "$st" = disabled ] && { grep -q 'Needs a local clone' "$OUT/ui.xml" || { scroll_body; sleep 1; dump | grep -q 'Needs a local clone'; }; }; then ok=$((ok+1)); printf '%s:%s\t%s\t%s\tgreyed with its reason\twalk/%s-%s-%s.png\n' "$phase" "$cloned" "$id" "$label" "$phase" "$cloned" "$id" >> "$OUT/walk.tsv"
      else miss=$((miss+1)); printf '%s:%s\t%s\t%s\tNOT GREYED WITH A REASON\t-\n' "$phase" "$cloned" "$id" "$label" >> "$OUT/walk.tsv"; fi
      continue
    fi
    if [ "$st" = disabled ]; then ok=$((ok+1)); printf '%s:%s\t%s\t%s\tgreyed (disabled by state)\t-\n' "$phase" "$cloned" "$id" "$label" >> "$OUT/walk.tsv"; continue; fi
    case "$id" in ov.goto.web|ov.goto.folder)
      # these LEAVE the sheet: the destination must be a different screen
      if sheet_open; then miss=$((miss+1)); printf '%s:%s\t%s\t%s\tNOT OPENED\t-\n' "$phase" "$cloned" "$id" "$label" >> "$OUT/walk.tsv"; continue; fi;;
    esac
    # a destructive control opens its confirm: it is CANCELLED, never confirmed
    if dump | grep -q 'text="Confirm"'; then tap_text '^Cancel$' || adb shell input keyevent KEYCODE_BACK < /dev/null; sleep 1; fi
    # #1934 a bulk control may open a window over the sheet (Last report, Logs, the conflict solver): BACK closes it; if the sheet went with it, reopen it on the same tab
    if [ "$phase" = bulk ] && ! has_rid git_bulk_scope_bar; then
      for b in 1 2 3; do adb shell input keyevent KEYCODE_BACK < /dev/null; sleep 2; has_rid git_bulk_scope_bar && break; done
      has_rid git_bulk_scope_bar || { open_bulk; tap_rid "git_sheet_tab_$curtab" >/dev/null; sleep 1; }
    fi
    ok=$((ok+1)); printf '%s:%s\t%s\t%s\ttapped\twalk/%s-%s-%s.png\n' "$phase" "$cloned" "$id" "$label" "$phase" "$cloned" "$id" >> "$OUT/walk.tsv"
  done
  exec 3<&-
}

want() { [ "$PHASE" = all ] || [ "$PHASE" = "$1" ]; }
# the header's readouts and its not-cloned banner / Clone Now, read from ONE dump of the freshly opened sheet (no extra taps)
header_checks() {
  local phase=$1 cloned=$2 row=$3 v wantv ok1 id label
  dump >/dev/null
  nt() { grep -o "<node [^>]*resource-id=\"$1\"[^>]*>" "$OUT/ui.xml" | head -1 | grep -o ' text="[^"]*"' | head -1 | sed 's/^ text="//; s/"$//'; }
  rec() { # id label ok(0/1) value
    if [ "$3" = 0 ]; then ok=$((ok+1)); printf 'per:%s\t%s\t%s\tshown (value ok)\t%s\n' "$cloned" "$1" "$2" "$4" >> "$OUT/walk.tsv"
    else miss=$((miss+1)); printf 'per:%s\t%s\t%s\tVALUE MISMATCH (got "%s")\t-\n' "$cloned" "$1" "$2" "$4" >> "$OUT/walk.tsv"; fi; }
  local cs sm; cs=$(nt git_sheet_clone_state); sm=$(grep -o ' text="[^"]*diegonmarcos · [^"]*"' "$OUT/ui.xml" | head -1 | sed 's/^ text="//; s/"$//')
  if [ "$cloned" = yes ]; then wcs='^Cloned$'; wsm='^diegonmarcos · '; else wcs='^Not Cloned$'; wsm='^diegonmarcos · '; fi   # the branch is read by the first check, so the summary opens as owner · — · —
  grep -q " text=\"$row\"" "$OUT/ui.xml"; rec hd.name Name $? "$row"
  v=$(nt git_sheet_visibility); [ -n "$v" ]; rec hd.visibility Visibility $? "$v"
  printf '%s' "$cs" | grep -Eq "$wcs"; rec hd.clone_state "Clone state" $? "$cs"
  printf '%s' "$sm" | grep -Eq "$wsm"; rec hd.summary "Owner · branch · size" $? "$sm"
  if [ "$cloned" = no ]; then
    has_rid git_sheet_banner; rec hd.not_cloned "Not cloned banner" $? "banner on screen"
    # Clone Now: present and enabled (the walk taps it once in this phase; the clone target is unreachable, so nothing is created)
    has_rid git_sheet_hd.clone_now; rec hd.clone_now "Clone Now" $? "on screen"
  else
    ! has_rid git_sheet_banner; rec hd.not_cloned "Not cloned banner" $? "absent on a cloned repo"
    ! has_rid git_sheet_hd.clone_now; rec hd.clone_now "Clone Now" $? "absent on a cloned repo"
  fi
}
# Repo list closes the sheet (back to the repository list), Close dismisses it: both must leave the sheet gone
closers() {
  local phase=$1 cloned=$2 row=$3 id rid label
  for spec in "hd.repo_list|git_sheet_hd.repo_list|Repo list" "hd.close|git_sheet_hd.close|Close"; do
    IFS='|' read -r id rid label <<< "$spec"
    sheet_open || open_row "$row"
    tap_rid "$rid" >/dev/null; sleep 2
    sheet_open && { sleep 3; }
    if sheet_open; then miss=$((miss+1)); printf '%s:%s\t%s\t%s\tNOT CLOSED\t-\n' "$phase" "$cloned" "$id" "$label" >> "$OUT/walk.tsv"; close_sheet "closer-$id"
    else ok=$((ok+1)); printf '%s:%s\t%s\t%s\ttapped (sheet closed)\t-\n' "$phase" "$cloned" "$id" "$label" >> "$OUT/walk.tsv"; fi
  done
}
# 1. the cloned fixture
if want cloned; then open_row "$NAME"; header_checks per yes "$NAME"; walk per yes "$NAME"; closers per yes "$NAME"; close_sheet cloned; fi
# 2. the not-cloned fixture: its row comes from the offline listing cache
if want notcloned; then
  open_row "$NOTCLONED"
  has_rid git_sheet_banner || { miss=$((miss+1)); printf 'per:no\tbanner\tNot cloned banner\tNOT FOUND\t-\n' >> "$OUT/walk.tsv"; }
  header_checks per no "$NOTCLONED"
  tap_rid git_sheet_hd.clone_now >/dev/null || { miss=$((miss+1)); printf 'per:no\thd.clone_now\tClone Now\tNOT FOUND\t-\n' >> "$OUT/walk.tsv"; }
  walk per no "$NOTCLONED"; closers per no "$NOTCLONED"; close_sheet notcloned
fi

# 4. #1934 THE BULK PHASES: the top-nav menu's bulk sheet, one phase per scope (All Repos | Selected Ones | Cloned Ones) over the 3 cloned fixtures + the not-cloned listing.
# Each phase checks the scope's count, the greyed controls and their reason lines, then RUNS Stash (a destructive-class command: its confirm must name the scope and the count)
# and Test Auth, reads the per-repository rows and the summary from the sheet and verifies the FIXTURE FILES (a.txt of -2 / -3) with values. Hosting commands are never confirmed.
nodetext() { dump | python3 -I -c '
import re,sys
x=sys.stdin.read()
for m in re.finditer(r"<node [^>]*>",x):
    n=m.group(0)
    if re.search(r"resource-id=\"%s\"" % re.escape(sys.argv[1]), n):
        t=re.search(r"\btext=\"([^\"]*)\"",n); print(t.group(1) if t else ""); sys.exit(0)
sys.exit(1)' "$1"; }
brec() { # <scope> <id> <label> <0=ok> <value>
  if [ "$4" = 0 ]; then ok=$((ok+1)); printf 'bulk-%s\t%s\t%s\tverified (value ok)\t%s\n' "$1" "$2" "$3" "$5" >> "$OUT/walk.tsv"
  else miss=$((miss+1)); printf 'bulk-%s\t%s\t%s\tVALUE MISMATCH (got "%s")\t-\n' "$1" "$2" "$3" "$5" >> "$OUT/walk.tsv"; fi; }
bshot() { adb exec-out screencap -p > "$OUT/walk/drive-bulk-1934-$1-$2.png" 2>/dev/null; }
fixfile() { adb shell "run-as $PKG cat files/fixtures/$1/a.txt" < /dev/null 2>/dev/null | tr -d '\r\n'; }
find_ctl() { # <rid>: scroll the body until the control is on screen; prints "x y state"
  local n pos; for n in 0 1 2 3 4 5 6 7; do dump >/dev/null; pos=$(probe "$1") && { echo "$pos"; return 0; }; scroll_body || return 1; sleep 1; done; return 1; }
tap_ctl() { local pos; pos=$(find_ctl "$1") || return 1; set -- $pos; [ "$3" = enabled ] && adb shell input tap "$1" "$2" < /dev/null; echo "$3"; }
open_bulk() {
  tap_rid sync_git_top_group_more >/dev/null || { snap no-more; fail "no More menu"; }; sleep 1
  for i in 1 2 3 4 5; do tap_text '^All repos sheet$' && break; adb shell input swipe $((W*2/3)) $((H*70/100)) $((W*2/3)) $((H*30/100)) 300 < /dev/null; sleep 1; done
  sleep 3; has_rid git_bulk_scope_bar || { snap no-bulk; fail "the bulk sheet did not open"; }; }
close_bulk() { for b in 1 2 3; do has_rid git_bulk_scope_bar || return 0; adb shell input keyevent KEYCODE_BACK < /dev/null; sleep 2; done; }
wait_summary() { local n t; for n in $(seq 1 45); do t=$(nodetext git_bulk_summary) && printf '%s' "$t" | grep -q '^Summary ' && { echo "$t"; return 0; }; sleep 2; done; echo "${t:-no summary}"; return 1; }
bulk_phase() {
  local S=$1 label cnt wantcnt t st sum rows
  case $S in all) label="All Repos";; selected) label="Selected Ones";; cloned) label="Cloned Ones";; esac
  adb shell "run-as $PKG sh -c 'echo dirty > files/fixtures/$NAME-2/a.txt; echo dirty > files/fixtures/$NAME-3/a.txt'" < /dev/null
  open_bulk; bshot "$S" open
  tap_ctl git_bulk_scope_$S >/dev/null; sleep 1
  if [ $S = selected ]; then
    # the multi-select FIRST, before anything scrolls the body (the pick boxes sit at its top; a drag down would close the sheet, so the walk never scrolls up)
    cnt=$(nodetext git_bulk_scope_count); [ "$cnt" = "0 repos" ]; brec "$S" "scope.count" "$label count (nothing picked)" $? "$cnt (want 0 repos)"
    tap_ctl git_bulk_pick_$NAME-2 >/dev/null; sleep 1
    cnt=$(nodetext git_bulk_scope_count); [ "$cnt" = "1 repos" ]; brec "$S" "scope.pick" "pick one repo" $? "$cnt (want 1 repos)"
    bshot "$S" picked
    tap_ctl git_bulk_pick_$NAME-2 >/dev/null; sleep 1
    cnt=$(nodetext git_bulk_scope_count); [ "$cnt" = "0 repos" ]; brec "$S" "scope.unpick" "unpick it again" $? "$cnt (want 0 repos)"
    # nothing picked: every run control is greyed and says why
    tap_ctl git_sheet_tab_sync >/dev/null; sleep 1
    st=$(find_ctl git_sheet_ctl_bo.sync.run | awk '{print $3}'); [ "$st" = disabled ]; brec "$S" "bo.sync.run" "Sync selected greyed" $? "$st"
    dump | grep -q 'No repository is selected'; brec "$S" "reason.no_selection" "reason line (no selection)" $? "on screen"
    bshot "$S" nopick
    # reopen the sheet (the scope and the picks live in the page, the scroll does not) and pick -2 for the runs
    close_bulk; open_bulk
    tap_ctl git_bulk_pick_$NAME-2 >/dev/null; sleep 1
    cnt=$(nodetext git_bulk_scope_count); [ "$cnt" = "1 repos" ]; brec "$S" "scope.repick" "pick -2 for the runs" $? "$cnt (want 1 repos)"
  else
    # the Overview's aggregated counts are the oracle for the scope's repository count
    tap_ctl git_sheet_tab_overview >/dev/null; sleep 1
    dump >/dev/null; total=$(readout git_sheet_ctl_bo.count.total Repos); clonedn=$(readout git_sheet_ctl_bo.count.cloned Cloned)
    cnt=$(nodetext git_bulk_scope_count)
    case $S in all) wantcnt="$total repos";; cloned) wantcnt="$clonedn repos";; esac
    [ "$cnt" = "$wantcnt" ]; brec "$S" "scope.count" "$label count" $? "$cnt (want $wantcnt)"
    [ "$clonedn" = 3 ]; brec "$S" "scope.fixtures" "cloned fixtures" $? "cloned=$clonedn total=$total"
  fi
  if [ $S = cloned ]; then
    tap_ctl git_sheet_tab_clone >/dev/null; sleep 1
    st=$(find_ctl git_sheet_ctl_bo.clone.run | awk '{print $3}'); [ "$st" = disabled ]; brec "$S" "bo.clone.run" "Clone selected greyed" $? "$st"
    dump | grep -q 'already cloned'; brec "$S" "reason.all_cloned" "reason line (all cloned)" $? "on screen"
    bshot "$S" clone-greyed
  fi
  # Stash over the scope: the confirm names the scope and the count
  tap_ctl git_sheet_tab_sync >/dev/null; sleep 1
  case $S in all) wantn=$total;; cloned) wantn=3;; selected) wantn=1;; esac
  tap_ctl git_sheet_ctl_sy.rec.stash >/dev/null; sleep 2
  d=$(dump); printf '%s' "$d" | grep -q "text=\"Scope: $label · $wantn repositories\""; brec "$S" "confirm.scope" "confirm names scope and count" $? "Scope: $label · $wantn repositories"
  bshot "$S" stash-confirm
  tap_text '^Confirm$' || fail "no confirm button"
  sum=$(wait_summary); echo "$sum" | grep -q "^Summary $wantn repos: "; brec "$S" "run.summary" "summary line" $? "$sum"
  bshot "$S" stash-summary
  tap_ctl git_sheet_tab_workflow >/dev/null; sleep 1
  for n in $NAME $NAME-2 $NAME-3 $NOTCLONED; do
    t=$(find_ctl git_bulk_row_$n >/dev/null && nodetext git_bulk_row_$n) || t=""
    inscope=0
    case $S in all) inscope=1;; cloned) [ $n != $NOTCLONED ] && inscope=1;; selected) [ $n = $NAME-2 ] && inscope=1;; esac
    if [ $inscope = 0 ]; then [ -z "$t" ]; brec "$S" "row.$n" "$n outside the scope has no row" $? "${t:-none}"
    elif [ $n = $NOTCLONED ]; then printf '%s' "$t" | grep -q 'failed: needs a local clone'; brec "$S" "row.$n" "not-cloned row failed with its reason" $? "$t"
    elif [ $n = $NAME-2 ]; then printf '%s' "$t" | grep -q "^$n  ok"; brec "$S" "row.$n" "dirty fixture stashed: ok" $? "$t"
    else printf '%s' "$t" | grep -Eq "^$n  (ok|failed|skipped)"; brec "$S" "row.$n" "$n reached a terminal state" $? "$t"; fi
  done
  bshot "$S" rows
  # the fixture files, with values: Stash put the tracked change away in every repo of the scope, and only there
  v2=$(fixfile $NAME-2); [ "$v2" = one ]; brec "$S" "fixture.$NAME-2" "$NAME-2 a.txt after Stash" $? "$v2 (want one)"
  v3=$(fixfile $NAME-3); if [ $S = selected ]; then [ "$v3" = dirty ]; brec "$S" "fixture.$NAME-3" "$NAME-3 untouched outside the scope" $? "$v3 (want dirty)"; else [ "$v3" = one ]; brec "$S" "fixture.$NAME-3" "$NAME-3 a.txt after Stash" $? "$v3 (want one)"; fi
  # Test Auth over the scope: a fetch dry-run against the unreachable fixture remote; every cloned repo of the scope answers failed with the error text, not-cloned ones never run
  tap_ctl git_sheet_tab_manage >/dev/null; sleep 1
  tap_ctl git_sheet_ctl_mg.auth.test >/dev/null; sleep 2
  sum=$(wait_summary); echo "$sum" | grep -q "^Summary $wantn repos: "; brec "$S" "auth.summary" "Test Auth summary" $? "$sum"
  tap_ctl git_sheet_tab_workflow >/dev/null; sleep 1
  t=$(find_ctl git_bulk_row_$NAME-2 >/dev/null && nodetext git_bulk_row_$NAME-2); printf '%s' "$t" | grep -Eq "^$NAME-2  failed: .+"; brec "$S" "auth.row.$NAME-2" "unreachable remote: failed with the error" $? "$t"
  bshot "$S" auth-rows
  # a hosting command is only ever confirmed against the mock (HostingApiTest): here its confirm names the scope and count, and is CANCELLED
  tap_ctl git_sheet_tab_manage >/dev/null; sleep 1
  tap_ctl git_sheet_ctl_mg.life.delete_remote >/dev/null; sleep 2
  d=$(dump); printf '%s' "$d" | grep -q "text=\"Scope: $label · $wantn repositories\""; brec "$S" "confirm.delete_remote" "Delete Remote confirm names scope and count" $? "cancelled, never confirmed"
  bshot "$S" delete-confirm
  tap_text '^Cancel$' || adb shell input keyevent KEYCODE_BACK < /dev/null; sleep 1
  close_bulk
}
# 3. the all-repos sheet over the 3 fixture repositories: More is pinned in the toolbar (tag sync_git_top_group_more)
if want all-repos; then
  tap_rid sync_git_top_group_more >/dev/null || { snap no-more; fail "no More menu"; }; sleep 1
  # the More menu is a long scrolling dropdown: the item sits below the fold
  for i in 1 2 3 4 5; do tap_text '^All repos sheet$' && break; adb shell input swipe $((W*2/3)) $((H*70/100)) $((W*2/3)) $((H*30/100)) 300 < /dev/null; sleep 1; done
  sleep 3; sheet_open || { snap no-bulk; fail "the all-repos sheet did not open"; }
  walk bulk yes "$NAME"
fi
for sc in all selected cloned; do want bulk-$sc && bulk_phase $sc; done

echo "control sheet walk: $ok controls verified, $miss failed" | tee "$OUT/summary.txt"
adb logcat -d -v threadtime > "$OUT/logcat.txt"
grep -q "FATAL EXCEPTION" "$OUT/logcat.txt" && grep -A5 "FATAL EXCEPTION" "$OUT/logcat.txt" | grep -q "$PKG" && fail "Drive crashed during the walk"
[ "$miss" = 0 ] || fail "$miss control(s) were not found or did not behave (see walk.tsv; NOT FOUND counts as FAIL)"
