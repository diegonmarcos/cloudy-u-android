#!/bin/bash
# #1934 RELEASE-APK WALK of the Git TOP NAV: installs the SHIPPED Cloud Drive APK (the release asset / Ship artifact, not a test build), seeds REAL clones of public repositories
# (cloned on the runner from github.com, registered in the app's own store), then walks the new top nav: the old Refresh | Sync | Clone | Logs | More row must be gone, and every
# scope (All Repos | Selected Ones | Cloned Ones) x group tab (Overview | Sync | Clone | Workflow | Manage) is opened and screenshotted (drive-topnav-1934-release-*.png).
# Read-only bulk commands (Fetch status over the real repos) run for real; destructive and hosting commands (Delete Remote) are opened and CANCELLED, never confirmed.
# The list's "List as of" line is seeded 10 days old: the walk records what the page does with it (re-read, or the reason it could not).
# Usage: emulator-drive-topnav-release.sh <drive.apk> <outdir> <repo-name> [<repo-name>...]
set -u
APK=${1:?drive apk}; OUT=${2:-out}; shift 2; REPOS=("$@")
PKG=com.diegonmarcos.clouddrive
mkdir -p "$OUT/walk"; : > "$OUT/walk.tsv"; ok=0; miss=0
fail() { echo "::error title=Drive top nav::$*"; adb exec-out screencap -p > "$OUT/fail.png" 2>/dev/null; adb logcat -d -v threadtime > "$OUT/logcat.txt" 2>/dev/null; exit 1; }
rec() { # <0=ok> <what> <detail>
  if [ "$1" = 0 ]; then ok=$((ok+1)); printf 'PASS\t%s\t%s\n' "$2" "$3" >> "$OUT/walk.tsv"; else miss=$((miss+1)); printf 'FAIL\t%s\t%s\n' "$2" "$3" >> "$OUT/walk.tsv"; fi; }

# real clones on the runner, registered in the app's store (the app reads them with its in-process git engine)
CL=$(mktemp -d)
for r in "${REPOS[@]}"; do git clone -q --depth 5 "https://github.com/diegonmarcos/$r.git" "$CL/$r" || fail "cannot clone $r"; done
tar -C "$CL" -cf "$OUT/real.tar" "${REPOS[@]}"
adb uninstall "$PKG" >/dev/null 2>&1 || true
adb install -r -g "$APK" || fail "install failed"
adb shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow || true
act=$(adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "$PKG" | tr -d '\r' | tail -1)
timeout 120 adb shell am start -W -n "$act" >/dev/null; sleep 8
adb shell am force-stop "$PKG"
DIR=/data/data/$PKG/files
SEEDED=no
if adb exec-in "run-as $PKG sh -c 'mkdir -p files/real files/git-sync && tar -xf - -C files/real'" < "$OUT/real.tar" 2>/dev/null; then
  SEEDED=yes
  json="["; i=0
  for r in "${REPOS[@]}"; do i=$((i+1)); json="$json{\"id\":\"real-$i\",\"name\":\"$r\",\"path\":\"$DIR/real/$r\",\"remoteUrl\":\"https://github.com/diegonmarcos/$r.git\"},"; done
  printf '%s' "${json%,}]" | adb exec-in "run-as $PKG sh -c 'cat > files/git-sync/repos.json'"
  # the cached list is 10 days old (17/11/2026 17:30): the page must not leave it silently
  adb exec-in "run-as $PKG sh -c 'cat > files/git-listing-cache.json'" <<JSON
{"schema":"git-listing-cache/v1","slots":{"own":{"atMs":1763400600000,"complete":true,"viaGh":false,"rungId":"","repos":[]}}}
JSON
fi
echo "real clones seeded in the app store: $SEEDED" | tee "$OUT/seed.txt"

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


size=$(adb shell wm size | tr -d '\r' | grep -oE '[0-9]+x[0-9]+' | tail -1); W=${size%x*}; H=${size#*x}
shot() { adb exec-out screencap -p > "$OUT/walk/drive-topnav-1934-release-$1.png" 2>/dev/null; }
nodetext() { dump | python3 -I -c '
import re,sys
x=sys.stdin.read()
for m in re.finditer(r"<node [^>]*>",x):
    n=m.group(0)
    if re.search(r"resource-id=\"%s\"" % re.escape(sys.argv[1]), n):
        t=re.search(r"\btext=\"([^\"]*)\"",n); print(t.group(1) if t else ""); sys.exit(0)
sys.exit(1)' "$1"; }
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
find_ctl() { local n pos; for n in $(seq 0 24); do dump >/dev/null; pos=$(probe "$1") && { echo "$pos"; return 0; }; scroll_body || return 1; sleep 1; done; return 1; }
tap_ctl() { local pos; pos=$(find_ctl "$1") || { echo missing; return 1; }; set -- $pos; [ "$3" = enabled ] && adb shell input tap "$1" "$2" < /dev/null; echo "$3"; }

timeout 120 adb shell am start -W -n "$act" >/dev/null; sleep 6
for i in 1 2 3; do tap '^Continue with local files$' && break; sleep 2; done || true
sleep 3
tap '^Git$' || fail "no Git sub-page"
sleep 5

# 1. THE OLD ROW IS GONE, the new nav is on the page
dump >/dev/null; shot collapsed
has_rid git_bulk_scope_bar; rec $? "nav.scope_row" "scope chips row on the page"
for t in overview sync clone workflow manage; do has_rid git_sheet_tab_$t; rec $? "nav.tab.$t" "group tab $t"; done
! has_rid sync_git_top_group_more && ! grep -q 'resource-id="sync_git_top_group' "$OUT/ui.xml"; rec $? "old_row.gone" "no Refresh|Sync|Clone|Logs|More group buttons"
! grep -qE 'text="(More|Logs|Refresh)"' "$OUT/ui.xml"; rec $? "old_row.labels_gone" "no More / Logs / Refresh pill in the collapsed nav"
has_rid sync_auto_settings || grep -q 'sync_auto_settings' "$OUT/ui.xml"; rec $? "nav.gear" "the Settings gear is in the nav's scope row"
# the stale list: the line must say how old it is, and why when it could not be re-read
sleep 10; ll=$(nodetext sync_git_list_as_of || true); echo "list line: $ll" | tee -a "$OUT/walk.tsv"
printf '%s' "$ll" | grep -q 'List as of'; rec $? "list.as_of" "the list's age is shown: $ll"
if [ "$SEEDED" = yes ]; then printf '%s' "$ll" | grep -q '17/11/2026' && rec 1 "list.not_silent_when_stale" "still 17/11 with no reason: $ll" || rec 0 "list.advanced_or_explained" "$ll"; fi

# 2. every scope x every group, drawn from the declaration
for sc in all selected cloned; do
  tap_rid git_bulk_scope_$sc >/dev/null; sleep 1
  cnt=$(nodetext git_bulk_scope_count || true); echo "scope $sc: $cnt" >> "$OUT/walk.tsv"
  for tb in overview sync clone workflow manage; do
    tap_rid git_sheet_tab_$tb >/dev/null; sleep 2; dump >/dev/null
    n=$(grep -o 'resource-id="git_sheet_\(ctl\|group\)_[^"]*"' "$OUT/ui.xml" | sort -u | wc -l)
    [ "$n" -ge 1 ]; rec $? "scope.$sc.$tb" "controls/groups on screen: $n ($cnt)"
    shot "$sc-$tb"
  done
done

# 3. read-only bulk command over the real repositories: Fetch status in All Repos
tap_rid git_bulk_scope_all >/dev/null; sleep 1; tap_rid git_sheet_tab_overview >/dev/null; sleep 2
st=$(tap_ctl git_sheet_ctl_bo.fetch_status); echo "fetch_status: $st" >> "$OUT/walk.tsv"
if [ "$SEEDED" = yes ]; then
  [ "$st" = enabled ]; rec $? "bulk.fetch_status.enabled" "Fetch status runs over the real repos ($st)"
  tap_rid git_sheet_tab_workflow >/dev/null; sleep 2
  sum=""; for n in $(seq 1 60); do sum=$(nodetext git_bulk_summary || true); printf '%s' "$sum" | grep -q '^Summary ' && break; sleep 2; done
  printf '%s' "$sum" | grep -q '^Summary '; rec $? "bulk.fetch_status.summary" "$sum"
  rows=0; for r in "${REPOS[@]}"; do t=$(nodetext git_bulk_row_$r || true); echo "row $r: $t" >> "$OUT/walk.tsv"; [ -n "$t" ] && rows=$((rows+1)); done
  [ "$rows" -ge 1 ]; rec $? "bulk.fetch_status.rows" "per-repo rows drawn: $rows of ${#REPOS[@]}"
  shot fetch-status-rows
fi

# 4. destructive and hosting commands are opened and CANCELLED, never confirmed
tap_rid git_sheet_tab_manage >/dev/null; sleep 2
st=$(tap_ctl git_sheet_ctl_mg.life.delete_remote); sleep 2
if [ "$st" = enabled ]; then
  dump | grep -q 'text="Confirm"'; rec $? "confirm.delete_remote" "Delete Remote asks first"
  dump | grep -q 'Scope: All Repos'; rec $? "confirm.names_scope" "the confirm names scope and count"
  shot delete-confirm
  tap_text '^Cancel$' || adb shell input keyevent KEYCODE_BACK < /dev/null; sleep 1
else rec 0 "confirm.delete_remote" "greyed with its reason ($st): never confirmed"; fi
tap_rid git_nav_collapse >/dev/null; sleep 1; shot collapsed-after

adb logcat -d -v threadtime > "$OUT/logcat.txt"
grep -q "FATAL EXCEPTION" "$OUT/logcat.txt" && grep -A5 "FATAL EXCEPTION" "$OUT/logcat.txt" | grep -q "$PKG" && rec 1 "no.crash" "Drive crashed"
echo "release top-nav walk: $ok checks passed, $miss failed" | tee "$OUT/summary.txt"
[ "$miss" = 0 ] || fail "$miss check(s) failed (see walk.tsv)"
