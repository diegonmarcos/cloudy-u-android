#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ THE EXPLORER'S INLINE SEARCH. The search icon no longer opens a page: a  ║
# ║ compact bar (the Git page's GitSearchField, 40dp) expands over the       ║
# ║ listing, which stays and filters live; ONE row of four declared scope    ║
# ║ chips sits under it; back or the x collapses it in the same folder.      ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#   S1 declaration: ui.files.search declares folders_here, folders_root, all_here, all_root in that order with
#      their labels and flags; default_scope is folders_here and names a declared scope; search_height_dp is 40.
#   S2 screen: the default chip comes from the declaration (searchDecl.initialScope), no scope id is spelled in
#      Kotlin; the bar is GitSearchField (no OutlinedTextField, no SearchPanel page); back and the x both call
#      closeSearch, which never navigates.
#   S3 recursive walk: runScoped cancels the previous walk first, waits the declared debounce, walks on
#      Dispatchers.IO and streams hits; ExplorerSearch.walk stops at the results, scanned and depth bounds.
#   S4 scope filtering: filterHere and walk keep folders only when the scope says so; startOf picks the root
#      for a from-root scope.
#   M  mutations: each check is turned red by a one-line mutation of the real file.
set -uo pipefail
ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
APP="$ROOT/ac_cloud-drive"
SRC="$APP/app/src/main/java/com/diegonmarcos/clouddrive"
BJ="$APP/build.json"
SCREEN="$SRC/files/FilesScreen.kt"
CTRL="$SRC/files/FilesController.kt"
XS="$SRC/files/ExplorerSearch.kt"
FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }

s1() { python3 - "$1" <<'PY'
import json, sys
s = json.load(open(sys.argv[1]))["ui"]["files"].get("search") or {}
want = [("folders_here", "Only Folders Here", False, "here", True), ("folders_root", "Only Folders Root", True, "root", True),
        ("all_here", "All From Here", True, "here", False), ("all_root", "All From Root", True, "root", False)]
got = [(c.get("id"), c.get("label"), c.get("recursive"), c.get("from"), c.get("folders_only")) for c in s.get("scopes", [])]
bad = 0
if got != want: print("    scopes are not the four declared ones:", got); bad = 1
if s.get("default_scope") != "folders_here": print("    default_scope is not folders_here:", s.get("default_scope")); bad = 1
if s.get("default_scope") not in [c[0] for c in got]: print("    default_scope names no declared scope"); bad = 1
if s.get("search_height_dp") != 40: print("    search_height_dp is not 40"); bad = 1
for k in ("result_ceiling", "scan_ceiling", "depth_ceiling", "debounce_ms"):
    if not isinstance(s.get(k), int) or s[k] <= 0: print("    bound missing:", k); bad = 1
sys.exit(bad)
PY
}
s2() {
  local bad=0
  grep -qF 'searchDecl.initialScope?.id' "$1" || { echo "    the default chip is not the declared initialScope"; bad=1; }
  grep -qE '"(folders_here|folders_root|all_here|all_root)"' "$1" "$SRC/Declarations.kt" && { echo "    a scope id is spelled in Kotlin"; bad=1; }
  local bar; bar=$(sed -n '/^private fun ExplorerSearchBar(/,/^}/p' "$1")
  grep -qF 'GitSearchField(query, onQuery, decl.placeholder' <<<"$bar" || { echo "    the bar is not GitSearchField"; bad=1; }
  grep -qF 'OutlinedTextField' <<<"$bar" && { echo "    the bar uses OutlinedTextField"; bad=1; }
  grep -qF 'DriveTags.FILES_SEARCH_CLOSE' <<<"$bar" || { echo "    no close x"; bad=1; }
  grep -qF 'decl.scopes.forEach' <<<"$bar" || { echo "    the chips are not the declared scopes"; bad=1; }
  grep -qE 'fun SearchPanel\(' "$1" && { echo "    the old search page survives"; bad=1; }
  grep -qF 'remember(pane, listings, controller.liveFilter)' "$1" || { echo "    the pane rows are not rebuilt when the live filter changes"; bad=1; }
  grep -qF 'searching -> closeSearch()' "$1" || { echo "    back does not collapse the bar"; bad=1; }
  local close; close=$(grep -F 'fun closeSearch()' "$1")
  [ -n "$close" ] || { echo "    no closeSearch"; bad=1; }
  grep -qE 'controller\.(open|back)\(' <<<"$close" && { echo "    collapsing navigates away from the folder"; bad=1; }
  return $bad
}
s3() {
  local bad=0
  local run; run=$(sed -n '/fun runScoped(/,/^    }/p' "$1")
  grep -qF 'cancelScoped()' <<<"$(head -2 <<<"$run")" || { echo "    runScoped does not cancel the previous walk first"; bad=1; }
  grep -qF 'Dispatchers.IO' <<<"$run" || { echo "    the walk is not off the main thread"; bad=1; }
  grep -qF 'delay(decl.debounceMs)' <<<"$run" || { echo "    no declared debounce"; bad=1; }
  grep -qF 'hits = it.hits + e' <<<"$run" || { echo "    hits are not streamed"; bad=1; }
  for t in '++found >= bounds.results' '++scanned >= bounds.scanned' 'depth < bounds.depth' 'if (cancelled()) return Stop.CANCELLED'; do
    grep -qF -- "$t" "$2" || { echo "    the walk lacks: $t"; bad=1; }
  done
  return $bad
}
s4() {
  local bad=0
  local here; here=$(sed -n '/fun filterHere(/,/^    }/p' "$1")
  grep -qF '(!scope.foldersOnly || e.isDirectory) && matches(e.name, n)' <<<"$here" || { echo "    filterHere does not keep folders only for a folders scope"; bad=1; }
  grep -qF '(!scope.foldersOnly || isDir) && matches(c.name, n)' "$1" || { echo "    walk does not keep folders only for a folders scope"; bad=1; }
  grep -qF 'if (scope.fromRoot) storageRoot(here, roots) else here' "$1" || { echo "    startOf does not pick the root for a from-root scope"; bad=1; }
  grep -qF 'f.locationKey == pane.location.key && !f.scope.recursive' "$2" || { echo "    the in-place filter is not limited to the non-recursive scope of the shown folder"; bad=1; }
  return $bad
}

echo "── S1 declaration ──"
s1 "$BJ" && pass "four scopes declared in order, default folders_here, 40dp, bounded" || fail "ui.files.search is not the declared shape"
echo "── S2 screen ──"
s2 "$SCREEN" && pass "declared default chip, GitSearchField bar, rows follow the live filter, x and back collapse in place" || fail "the inline bar is not wired as declared"
echo "── S3 recursive walk ──"
s3 "$CTRL" "$XS" && pass "cancel-first, debounced, Dispatchers.IO, streamed, bounded" || fail "the recursive walk is not safe"
echo "── S4 scope filtering ──"
s4 "$XS" "$CTRL" && pass "folders-only and from-root honoured in filter, walk and start" || fail "scope filtering is wrong"

echo "── M mutations ──"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
mut() { # <name> <check-fn> <args with MUT for the mutated file...>
  local name=$1; shift; local fn=$1; shift
  if "$fn" "$@" >/dev/null 2>&1; then fail "mutation survived: $name"; else pass "mutation caught: $name"; fi
}
sed 's/"default_scope": "folders_here"/"default_scope": "all_root"/' "$BJ" > "$tmp/b1.json"; mut "default_scope all_root" s1 "$tmp/b1.json"
python3 - "$BJ" "$tmp/b2.json" <<'PY'
import json, sys
d = json.load(open(sys.argv[1])); d["ui"]["files"]["search"]["scopes"][1]["folders_only"] = False; json.dump(d, open(sys.argv[2], "w"))
PY
mut "folders_root lists files" s1 "$tmp/b2.json"
sed 's/searchDecl.initialScope?.id ?: ""/"folders_here"/g' "$SCREEN" > "$tmp/s1.kt"; mut "hardcoded default chip" s2 "$tmp/s1.kt"
sed 's/GitSearchField(query, onQuery, decl.placeholder/OutlinedTextField(query, onQuery, decl.placeholder/' "$SCREEN" > "$tmp/s2.kt"; mut "bar is an OutlinedTextField" s2 "$tmp/s2.kt"
sed 's/remember(pane, listings, controller.liveFilter)/remember(pane, listings)/' "$SCREEN" > "$tmp/s4.kt"; mut "rows cached past the live filter" s2 "$tmp/s4.kt"
sed 's/fun closeSearch() { searching = false;/fun closeSearch() { controller.back(ui.active); searching = false;/' "$SCREEN" > "$tmp/s3.kt"; mut "collapse navigates" s2 "$tmp/s3.kt"
sed 's/scope.launch(Dispatchers.IO)/scope.launch(Dispatchers.Main)/' "$CTRL" > "$tmp/c1.kt"; mut "walk on Main" s3 "$tmp/c1.kt" "$XS"
sed 's/if (++found >= bounds.results)/if (false)/' "$XS" > "$tmp/x1.kt"; mut "no result ceiling" s3 "$CTRL" "$tmp/x1.kt"
sed 's/(!scope.foldersOnly || e.isDirectory) \&\& matches/matches/' "$XS" > "$tmp/x2.kt"; mut "folders_here lists files" s4 "$tmp/x2.kt" "$CTRL"
sed 's/if (scope.fromRoot) storageRoot(here, roots) else here/here/' "$XS" > "$tmp/x3.kt"; mut "from-root starts here" s4 "$tmp/x3.kt" "$CTRL"
s1 "$BJ" >/dev/null && s2 "$SCREEN" >/dev/null && s3 "$CTRL" "$XS" >/dev/null && s4 "$XS" "$CTRL" >/dev/null && pass "unmutated tree is still green" || fail "the unmutated tree is red"

echo
[ "$FAILURES" -eq 0 ] && echo "test-drive-explorer-search: all green" || { echo "test-drive-explorer-search: $FAILURES failure(s)"; exit 1; }
