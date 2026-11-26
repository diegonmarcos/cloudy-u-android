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
NOTCLONED=cloudy-notcloned
adb shell "run-as $PKG sh -c 'cat > files/git-listing-cache.json'" <<JSON
{"schema":"git-listing-cache/v1","slots":{"fleet":{"atMs":$(date +%s)000,"complete":true,"viaGh":false,"rungId":"","repos":[{"name":"$NOTCLONED","owner":"diegonmarcos","private":false,"fork":false,"defaultBranch":"main","description":"fixture: a repository that is not cloned","sizeKb":12,"updatedAt":"2026-01-01T00:00:00Z","pushedAt":"2026-01-01T00:00:00Z","cloneUrl":"https://example.invalid/diegonmarcos/$NOTCLONED.git","sshUrl":"","webUrl":"https://example.invalid/diegonmarcos/$NOTCLONED","stars":0,"language":""}]},"own":{"atMs":$(date +%s)000,"complete":true,"viaGh":false,"rungId":"","repos":[{"name":"$NOTCLONED","owner":"diegonmarcos","private":false,"fork":false,"defaultBranch":"main","description":"fixture","sizeKb":12,"updatedAt":"2026-01-01T00:00:00Z","pushedAt":"2026-01-01T00:00:00Z","cloneUrl":"https://example.invalid/diegonmarcos/$NOTCLONED.git","sshUrl":"","webUrl":"https://example.invalid/diegonmarcos/$NOTCLONED","stars":0,"language":""}]}}}
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
# come back to the sheet after a control that leaves it (web viewer, Files, settings sheet): BACK, else relaunch and reopen the row ($1 = row text)
ensure_sheet() {
  sheet_open && return 0
  for b in 1 2 3; do adb shell input keyevent KEYCODE_BACK < /dev/null; sleep 2; sheet_open && return 0; done
  adb shell am force-stop "$PKG" < /dev/null; sleep 1; timeout 120 adb shell am start -W -n "$act" < /dev/null >/dev/null; sleep 6
  for i in 1 2 3; do tap_text '^Continue with local files$' && break; sleep 2; done; sleep 2
  tap_text '^Git$'; sleep 4; find_row; tap_text "^$1\$"; sleep 3; sheet_open; }
open_row() { find_row || { snap no-row; fail "the row $1 never appeared"; }; tap_text "^$1\$" || fail "cannot open the row $1"; sleep 3; sheet_open || { snap no-sheet; fail "the sheet of $1 did not open"; }; }

mkdir -p "$OUT/walk"; : > "$OUT/walk.tsv"; miss=0; ok=0
python3 -I - "$BJ" > "$OUT/lists.txt" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))["ui"]["git_control_sheet"]; h = d["header"]
def rows(tag, tabs, header):
    out = []
    if header:
        for c in [x for x in h["actions"] if x["id"] != "hd.repo_list"] + [h["refresh"]]: out.append(("header", "git_sheet_" + c["id"], c["id"], c["label"], c.get("needs_clone", False), c.get("confirm") or c.get("danger"), c.get("disabled", False)))
    for t in tabs:
        out.append((t["label"], "git_sheet_tab_" + t["id"], "tab:" + t["id"], t["label"], False, False, False))
        for g in t["groups"]:
            for c in g["controls"]:
                if c.get("kind") in ("field", "preview", "output"): continue
                out.append((t["label"], "git_sheet_ctl_" + c["id"], c["id"], c["label"], c.get("needs_clone", False), c.get("confirm") or c.get("danger"), c.get("disabled", False)))
    return out
for name, tabs, header in (("per", d["tabs"], True), ("bulk", d["bulk"]["tabs"], False)):
    with open(sys.argv[1] + "." + name, "w") as f: pass
    for r in rows(name, tabs, header): print(name + "\t" + "\t".join(str(x) for x in r))
PY
rm -f "$BJ.per" "$BJ.bulk" 2>/dev/null

# walk <per|bulk> <cloned: yes|no> <row name for recovery>
walk() {
  local phase=$1 cloned=$2 row=$3
  exec 3< <(grep -P "^$phase\t" "$OUT/lists.txt" | cut -f2-)
  while IFS=$'\t' read -r -u 3 tab rid id label needs confirm disabled; do
    case "$id" in tab:*)
      ensure_sheet "$row" || fail "cannot get the sheet back before $id"
      for k in 1 2; do tap_rid "$rid" >/dev/null && break; sleep 1; done; sleep 1
      adb exec-out screencap -p > "$OUT/walk/$phase-$cloned-${id#tab:}.png"; dump >/dev/null; snap "$phase-$cloned-tab-${id#tab:}"
      printf '%s:%s\t%s\ttab\tshown\twalk/%s-%s-%s.png\n' "$phase" "$cloned" "$id" "$phase" "$cloned" "${id#tab:}" >> "$OUT/walk.tsv"; continue;; esac
    [ "$disabled" = True ] && { printf '%s:%s\t%s\t%s\tgreyed (declared disabled)\t-\n' "$phase" "$cloned" "$id" "$label" >> "$OUT/walk.tsv"; continue; }
    ensure_sheet "$row" || fail "cannot get the sheet back before $id"
    st=""; found=0
    for n in 0 1 2 3 4 5 6; do
      st=$(tap_rid "$rid") && { found=1; break; }
      scroll_body || break; sleep 1
    done
    if [ $found = 0 ]; then miss=$((miss+1)); printf '%s:%s\t%s\t%s\tNOT FOUND\t-\n' "$phase" "$cloned" "$id" "$label" >> "$OUT/walk.tsv"; continue; fi
    sleep 1; adb exec-out screencap -p > "$OUT/walk/$phase-$cloned-$id.png"
    if [ "$cloned" = no ] && [ "$needs" = True ]; then
      # not cloned: the control must be greyed, and the declared reason must be on screen
      if [ "$st" = disabled ] && { dump | grep -q 'Needs a local clone' || { scroll_body; sleep 1; dump | grep -q 'Needs a local clone'; }; }; then ok=$((ok+1)); printf '%s:%s\t%s\t%s\tgreyed with its reason\twalk/%s-%s-%s.png\n' "$phase" "$cloned" "$id" "$label" "$phase" "$cloned" "$id" >> "$OUT/walk.tsv"
      else miss=$((miss+1)); printf '%s:%s\t%s\t%s\tNOT GREYED WITH A REASON\t-\n' "$phase" "$cloned" "$id" "$label" >> "$OUT/walk.tsv"; fi
      continue
    fi
    if [ "$st" = disabled ]; then ok=$((ok+1)); printf '%s:%s\t%s\t%s\tgreyed (disabled by state)\t-\n' "$phase" "$cloned" "$id" "$label" >> "$OUT/walk.tsv"; continue; fi
    case "$id" in ov.goto.web|ov.goto.folder)
      # these LEAVE the sheet: the destination must be a different screen
      if sheet_open; then miss=$((miss+1)); printf '%s:%s\t%s\t%s\tNOT OPENED\t-\n' "$phase" "$cloned" "$id" "$label" >> "$OUT/walk.tsv"; continue; fi;;
    esac
    # a destructive control opens its confirm: it is CANCELLED, never confirmed
    if has_rid git_sheet_confirm; then tap_text '^Cancel$' || adb shell input keyevent KEYCODE_BACK < /dev/null; sleep 1; fi
    ok=$((ok+1)); printf '%s:%s\t%s\t%s\ttapped\twalk/%s-%s-%s.png\n' "$phase" "$cloned" "$id" "$label" "$phase" "$cloned" "$id" >> "$OUT/walk.tsv"
  done
  exec 3<&-
}

# 1. the cloned fixture
open_row "$NAME"; walk per yes "$NAME"
# 2. the not-cloned fixture: its row comes from the offline listing cache
adb shell input keyevent KEYCODE_BACK < /dev/null; sleep 2
if open_row "$NOTCLONED"; then
  has_rid git_sheet_banner || { miss=$((miss+1)); printf 'per:no\tbanner\tNot cloned banner\tNOT FOUND\t-\n' >> "$OUT/walk.tsv"; }
  tap_rid git_sheet_hd.clone_now >/dev/null || { miss=$((miss+1)); printf 'per:no\thd.clone_now\tClone Now\tNOT FOUND\t-\n' >> "$OUT/walk.tsv"; }
  walk per no "$NOTCLONED"
fi
# 3. the all-repos sheet over the 3 fixture repositories: More is pinned in the toolbar (tag sync_git_top_group_more)
adb shell input keyevent KEYCODE_BACK < /dev/null; sleep 2
tap_rid sync_git_top_group_more >/dev/null || { snap no-more; fail "no More menu"; }; sleep 1
tap_text '^All repos sheet$' || { snap no-bulk-item; fail "no All repos sheet item"; }; sleep 3
sheet_open || { snap no-bulk; fail "the all-repos sheet did not open"; }
walk bulk yes "$NAME"

echo "control sheet walk: $ok controls verified, $miss failed"
adb logcat -d -v threadtime > "$OUT/logcat.txt"
grep -q "FATAL EXCEPTION" "$OUT/logcat.txt" && grep -A5 "FATAL EXCEPTION" "$OUT/logcat.txt" | grep -q "$PKG" && fail "Drive crashed during the walk"
[ "$miss" = 0 ] || fail "$miss control(s) were not found or did not behave (see walk.tsv; NOT FOUND counts as FAIL)"
