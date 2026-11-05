#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #1254-#1259 the Git page's bulk depth commands, Unlisted git folders,     ║
# ║ filter row + Last commit + persisted sort, Activity Logs, and the bottom ║
# ║ nav's constant slot. Gradle proves it compiles; this proves the shape.   ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#   D1 depth commands are DATA: ui.sync.git.clone_modes declares the five clone modes (#1491), depth_actions offers them (kind clone_mode) and keep_depth in both scopes; the page
#      dispatches on Declarations.DEPTH_* kinds only, and every declared kind has a handler and every handler a kind.
#   D2 ONE CLONE PER REPOSITORY: the clone path plans through BulkDepth.planClones over every existing clone (registry,
#      store scan, unlisted scan) with the registry canonical (renamed_from), and re-checks the destination.
#   D3 keep_depth never re-clones and never writes the remote: the wire verb gates (clean / upstream / unpushed) before a
#      shallow fetch, then checkout -B, reflog expire, gc; the coordinator gates with BulkDepth.keepSkip first.
#   D4 bounded, cancel-safe, refreshed: a Semaphore of the declared workers, cancelDepthRun, NonCancellable refresh.
#   U1 Unlisted git folders: declared roots/bounds, statuses through the ONE reader (so StatusCache), scanned only from
#      pull-to-refresh and check-all.
#   F1 filters: declared ids == RepoFilters.HANDLED both ways; selection and sort persisted; LAST_COMMIT column labelled; last-commit key canonical both sides, first-open fill, failures logged.
#   A1 Activity Logs: the strings say Activity Logs; no History section at the page's bottom; a top button opens it.
#   N1 the bottom-nav host keeps a constant slot (no viewport resize on collapse/hide) and publishes LocalNavBottomClearance.
set -uo pipefail

ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
APP="$ROOT/ac_cloud-drive"
BJ="$APP/build.json"
SRC="$APP/app/src/main/java/com/diegonmarcos/clouddrive"
COORD="$SRC/sync/GitSyncCoordinator.kt"
LIBS="$ROOT/ab_cloud-libs-shared/libs"
PAGE="$SRC/sync/GitReposScreen.kt"
WIRE="$SRC/sync/TermuxGitWire.kt"
FILT="$SRC/sync/RepoFilters.kt"
STR="$APP/app/src/main/res/values/strings.xml"
HOST="$ROOT/ab_cloud-libs-shared/libs/ui-nav-bars/src/main/kotlin/com/diegonmarcos/superapp/navbarbottom/NavBarBottom.kt"
TESTS="$APP/app/src/test/java/com/diegonmarcos/clouddrive"

FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }

d1() { python3 - "$1" "$2" "$SRC/Declarations.kt" <<'PY'
import json, re, sys
g = json.load(open(sys.argv[1]))["ui"]["sync"]["git"]
page = open(sys.argv[2]).read()
decl = open(sys.argv[3]).read()
acts = g.get("depth_actions") or []
kinds = {(a["kind"], a["scope"]) for a in acts}
bad = 0
# #1491 the five declared clone modes, in the owner's order, with the git command each stands for; both scopes offer exactly these.
want = [("full", 0, False, False, ""), ("depth3", 3, False, False, ""), ("bare_main", 0, True, True, ""), ("bare_blobless", 0, True, False, "blob:none"), ("bare_treeless", 0, True, False, "tree:0")]
got = [(m["id"], m.get("depth", 0), bool(m.get("bare")), bool(m.get("single_branch")), m.get("filter", "")) for m in g.get("clone_modes") or []]
if got != want: print("    clone_modes is %s, not the five declared modes %s" % (got, want)); bad = 1
for s in ("all", "repo"):
    if [a.get("mode") for a in acts if a["kind"] == "clone_mode" and a["scope"] == s] != [w[0] for w in want]: print("    depth_actions scope %s does not offer exactly the five clone modes" % s); bad = 1
need = {("keep_depth", "all"), ("keep_depth", "repo")}
if not need <= kinds: print("    depth_actions lacks", sorted(need - kinds)); bad = 1
if any(a["kind"] in ("clone_missing", "clone_bare", "clone_bare_nohist") for a in acts): print("    an old clone kind is still declared (#1491 replaced them)"); bad = 1
if any(a.get("depth") != 1 for a in acts if a["kind"] == "keep_depth"): print("    every declared keep_depth command is depth 1"); bad = 1
# #1282 declared kinds <-> Declarations constants <-> page handlers, both ways.
consts = dict((v, k) for k, v in re.findall(r'const val (DEPTH_[A-Z_]+) = "([a-z_]+)"', decl) if not k.startswith("DEPTH_SCOPE"))
known = set(re.search(r"val DEPTH_KINDS = setOf\(([^)]*)\)", decl).group(1).replace(" ", "").split(","))
declared = {a["kind"] for a in acts}
for k in declared:
    c = consts.get(k)
    if not c or c not in known: print("    declared kind has no Declarations constant in DEPTH_KINDS:", k); bad = 1
    elif not re.search(r"Declarations\." + c + r" ->", page): print("    the page has no bulk handler for", k); bad = 1
    elif not re.search(r"it\.kind == Declarations\." + c + r"\b", page): print("    the page has no per-repository handler for", k); bad = 1
for c in known:
    kind = next((k for k, v in consts.items() if v == c), None)
    if kind not in declared: print("    handled kind is not declared:", c); bad = 1
if re.search(r'"(clone_(all|repo)_(full|depth3|bare_main|bare_blobless|bare_treeless)|keep_depth1_all|keep_repo_depth1)"', page): print("    a declared id is typed into the page"); bad = 1
sys.exit(bad)
PY
}
# #1315 the existing clones come from THE ONE INVENTORY (every tree the pass found, whatever its class), never a scan of their own.
d2() { for t in 'BulkDepth.planClones(' 'existingClones(list)' 'Declarations.repoSot.canonical(it)' 'inv.rows.filter { it.isClone }' 'already cloned at'; do grep -qF "$t" "$1" || { echo "    coordinator lacks $t"; return 1; }; done; grep -qF 'LocalRepos.scan(' "$1" && { echo "    coordinator still scans with LocalRepos.scan"; return 1; }; return 0; }
d3() {
    python3 - "$1" <<'PY' || return 1
import re, sys
s = open(sys.argv[1]).read()
m = re.search(r'"keep_depth" ->.*?\?: \(null to "bad depth"\)', s, re.S)
if not m: print("    no keep_depth verb"); sys.exit(1)
b = m.group(0)
order = ["status --porcelain", "@{u}", "rev-list --count", "fetch -q --depth", "checkout -q -B", "reflog expire", "gc --prune=now"]
pos = [b.find(t) for t in order]
if -1 in pos or pos != sorted(pos): print("    keep_depth is not gate -> shallow fetch -> reset -> expire -> gc:", pos); sys.exit(1)
if re.search(r"\bclone\b|\bpush\b", b): print("    keep_depth clones or pushes"); sys.exit(1)
PY
    grep -qF 'BulkDepth.keepSkip(' "$2" || { echo "    the coordinator does not gate with keepSkip"; return 1; }
}
# #1491 C1: the clone modes run in the engine the lib has: JGit for every mode without a filter, the git binary (cld.termux) for a partial-clone filter.
c1() { # $1 JGitEngine.kt  $2 StoreGit.kt  $3 coordinator
    grep -qF 'require(filter.isBlank())' "$1" || { echo "    JGit does not refuse a partial-clone filter"; return 1; }
    grep -qF 'setBranchesToClone' "$1" || { echo "    JGit has no single-branch clone"; return 1; }
    grep -qF 'fun cloneModeScript(' "$2" && grep -qF 'ls-remote --symref' "$2" || { echo "    StoreGit has no clone-mode script with the default-branch read"; return 1; }
    grep -qF 'mode?.needsGitBinary == true' "$3" || { echo "    the coordinator does not route a filter mode to the git binary"; return 1; }
}
# #1315 the bound, the cancel and the never-cancelled finaliser are the ONE executor's (sync/jobs/GitJobs); the coordinator declares the workers and re-reads after.
d4() { for t in 'workers = action.workers' 'fun cancelDepthRun()' 'jobs.cancel(' 'refresh(force = true).join()'; do grep -qF "$t" "$1" || { echo "    coordinator lacks $t"; return 1; }; done; J="$(dirname "$1")/jobs/GitJobs.kt"; for t in 'Semaphore(spec.workers' 'withContext(NonCancellable)'; do grep -qF "$t" "$J" || { echo "    GitJobs lacks $t"; return 1; }; done; }
u1() {
    python3 -c 'import json,sys; u=json.load(open(sys.argv[1]))["ui"]["sync"]["git"]["unlisted"]; assert u["roots"] and u["max_depth"]>0 and u["timeout_seconds"]>0' "$BJ" || { echo "    unlisted not declared"; return 1; }
    grep -qF 'UnlistedRepos.statusKey(it.path)' "$1" && grep -qF 'statusReader.request(found.map' "$1" || { echo "    unlisted statuses bypass the one reader"; return 1; }
    grep -qF 'coordinator.scanUnlisted()' "$2" || { echo "    pull-to-refresh does not scan"; return 1; }
    [ "$(grep -c 'scanUnlisted(' "$1")" -ge 3 ] || { echo "    check-all does not scan"; return 1; }
}
f1() { python3 - "$BJ" "$1" "$2" <<'PY'
import json, re, sys
filters = json.load(open(sys.argv[1]))["ui"]["sync"]["git"]["filters"]
src = open(sys.argv[2]).read()
page = open(sys.argv[3]).read()
bad = 0
# #1315 an option's meaning is DECLARED (predicates), never a Kind name in RepoFilters
preds = ("kinds", "cloned", "lfs", "private", "hub", "archived", "level")
for f in filters:
    for o in f["options"]:
        if o["id"] != "all" and f.get("default", "all") == "all" and not any(k in o for k in preds): print("    option without a predicate:", f["id"], o["id"]); bad = 1
if re.search(r'"(dirty|clean|ahead|behind|conflict)"\s*->', src): print("    RepoFilters names a Kind"); bad = 1
if "val HANDLED" in src: print("    RepoFilters still carries a hard-coded HANDLED set"); bad = 1
for t in ("coordinator.setSort(", "coordinator.setFilter(", "RepoFilters.apply("):
    if t not in page: print("    page lacks", t); bad = 1
if "var sortColumn by remember" in page: print("    the sort is not persisted"); bad = 1
# #1256 Last commit: one key both sides, first-open fill, pushed_at fill, failures logged.
for t in ("lastCommits[LastCommits.keyOf(", "coordinator.fillRemoteStatsOnce("):
    if t not in page: print("    page lacks", t); bad = 1
import os
coord = open(os.path.join(os.path.dirname(sys.argv[3]), "GitSyncCoordinator.kt")).read()
for t in ("LastCommits.keyOf(r.path)", "LastCommits.needsFirstFill(", "\"last commit "):
    if t not in coord: print("    coordinator lacks", t); bad = 1
sys.exit(bad)
PY
}
a1() {
    grep -qF '<string name="sync_history">Activity Logs</string>' "$STR" && grep -qF '<string name="sync_history_section">Activity Logs</string>' "$STR" || { echo "    strings still say History"; return 1; }
    ! grep -qE 'SectionHeader\(stringResource\(R\.string\.sync_history_section\)' "$1" || { echo "    the History section is still at the bottom"; return 1; }
    grep -qF '"open_logs" -> { logsTab = ' "$1" || { echo "    no top-bar item opens Logs (Activity Logs is its App tab, #1356)"; return 1; }
    python3 -c 'import json,sys; g=json.load(open(sys.argv[1]))["ui"]["sync"]["git"]["top_bar"]; assert any(i["kind"]=="open_logs" for grp in g["groups"] for i in grp["items"])' "$BJ" || { echo "    Activity Logs is not a declared top-bar item"; return 1; }
}
n1() { for t in 'heightIn(min = slot)' 'LocalNavBottomClearance provides slot' 'navBottomSlotShouldMeasure(collapse.collapsed, collapse.hidden)'; do grep -qF "$t" "$1" || { echo "    host lacks $t"; return 1; }; done; }

echo "── D1 declared ──";   d1 "$BJ" "$PAGE" && pass "depth commands declared in both scopes, dispatched by kind" || fail "depth commands not data"
echo "── D2 one clone ──";  d2 "$COORD" && pass "clones plan over every existing clone with renames folded in" || fail "one-clone rule missing"
echo "── D3 in place ──";   d3 "$WIRE" "$COORD" && pass "keep_depth gates, shallow-fetches, resets, expires, gcs; never clones or pushes" || fail "keep_depth unsafe"
echo "── C1 clone engines ──"; c1 "$LIBS/git-core/src/main/java/com/diegonmarcos/cloudlib/gitcore/JGitEngine.kt" "$SRC/sync/StoreGit.kt" "$COORD" && pass "JGit for the plain modes, the git binary in cld.termux for a filter" || fail "clone engines"
echo "── D4 bounded ──";    d4 "$COORD" && pass "bounded workers, cancel, cache refreshed after" || fail "depth runs unbounded"
echo "── U1 unlisted ──";   u1 "$COORD" "$PAGE" && pass "declared roots, one reader, scanned on refresh / check-all" || fail "unlisted wiring"
echo "── F1 filters ──";    f1 "$FILT" "$PAGE" && pass "filters declared == handled, sort and filters persisted" || fail "filters / sort"
grep -qF 'RepoColumn.LAST_COMMIT -> R.string.repo_col_last_commit' "$APP/../ab_cloud-libs-shared/libs/git-ui/src/main/java/com/diegonmarcos/cloudlib/gitui/RepoTableUi.kt" && pass "Last commit column labelled" || fail "Last commit column"
echo "── A1 activity ──";   a1 "$PAGE" && pass "Activity Logs renamed and moved to a top button" || fail "Activity Logs"
echo "── N1 nav slot ──";   n1 "$HOST" && pass "the bottom-nav slot keeps the expanded height" || fail "the bar slot resizes"
echo "── C1 cache on reopen ──"
c1() { python3 - "$1" "$2" "$3" <<'PY2'
import re, sys
coord, reader, page = (open(f).read() for f in sys.argv[1:4])
bad = 0
i = coord.find("val cached = runCatching { statusCache.load() }"); j = coord.find("statusReader.statuses.collect")
if i < 0 or j < 0 or i > j: print("    the cache is not loaded synchronously before the collector"); bad = 1
if "runCatching { registry.load() }.getOrNull()?.let { repos.value = it }" not in coord: print("    the registry is not loaded before the first frame"); bad = 1
if "if (!ran.get())" not in reader: print("    a read cancelled before it ran leaks READING and its claim"); bad = 1
if "copy(readAtMs = 0)" in reader: print("    invalidate zeroes readAtMs again (drops it from StatusCache)"); bad = 1
if re.search(r"glances\[GitSyncCoordinator\.statusKey\(", page): print("    a row looks its glance up without the fallback"); bad = 1
sys.exit(bad)
PY2
}
c1 "$COORD" "$SRC/sync/GitStatusReader.kt" "$PAGE" && pass "cache + registry loaded before the first frame; no READING / claim leak; stable keys" || fail "cache on reopen"
grep -q 'the phone repro' "$TESTS/sync/GitStatusCacheReopenTest.kt" && pass "JVM reopen end-to-end test present" || fail "no reopen test"
sed 's/if (!ran.get())/if (false)/' "$SRC/sync/GitStatusReader.kt" > "${TMPDIR:-/tmp}/c1r.$$.kt"; c1 "$COORD" "${TMPDIR:-/tmp}/c1r.$$.kt" "$PAGE" >/dev/null && fail "C1 passed a leaking reader (vacuous)" || pass "leak cleanup removed → C1 RED"; rm -f "${TMPDIR:-/tmp}/c1r.$$.kt"

echo "── JVM ──"
for t in sync/BulkDepthTest.kt sync/UnlistedReposTest.kt sync/RepoFiltersTest.kt ui/GitPageFrameTest.kt; do grep -q '@Test' "$TESTS/$t" && pass "JVM $t" || fail "missing $t"; done

echo "── M mutation-proof ──"
TMP="$(mktemp -d)"; trap 'rm -rf "${TMP:?}"' EXIT
sed 's/status --porcelain/status/' "$WIRE" > "$TMP/w.kt"; d3 "$TMP/w.kt" "$COORD" >/dev/null && fail "D3 passed an ungated wire (vacuous)" || pass "gate removed → D3 RED"
sed 's/require(filter.isBlank())/true/' "$LIBS/git-core/src/main/java/com/diegonmarcos/cloudlib/gitcore/JGitEngine.kt" > "$TMP/je.kt"; c1 "$TMP/je.kt" "$SRC/sync/StoreGit.kt" "$COORD" >/dev/null && fail "C1 passed a JGit that clones a filter mode silently (vacuous)" || pass "JGit ignores the filter → C1 RED"
python3 -c 'import json,sys; b=json.load(open(sys.argv[1])); b["ui"]["sync"]["git"]["clone_modes"][3]["filter"]=""; json.dump(b,open(sys.argv[2],"w"))' "$BJ" "$TMP/b0.json"
BJ0="$BJ"; BJ="$TMP/b0.json"; d1 "$BJ" "$PAGE" >/dev/null && fail "D1 passed a Bare Blobless without its filter (vacuous)" || pass "Bare Blobless without filter → D1 RED"; BJ="$BJ0"
sed 's/BulkDepth.planClones(/listOf(/' "$COORD" > "$TMP/c.kt"; d2 "$TMP/c.kt" >/dev/null && fail "D2 passed without the plan (vacuous)" || pass "one-clone plan removed → D2 RED"
python3 -c 'import json,sys; b=json.load(open(sys.argv[1])); b["ui"]["sync"]["git"]["filters"].append({"id":"bogus","options":[{"id":"all"},{"id":"meaningless","label":"?"}]}); json.dump(b,open(sys.argv[2],"w"))' "$BJ" "$TMP/b.json"
BJ0="$BJ"; BJ="$TMP/b.json"; f1 "$FILT" "$PAGE" >/dev/null && fail "F1 passed an unhandled filter (vacuous)" || pass "undeclared filter meaning → F1 RED"; BJ="$BJ0"
sed 's/heightIn(min = slot)/fillMaxWidth()/' "$HOST" > "$TMP/h.kt"; n1 "$TMP/h.kt" >/dev/null && fail "N1 passed a resizing slot (vacuous)" || pass "constant slot removed → N1 RED"
d1 "$BJ" "$PAGE" >/dev/null && d2 "$COORD" >/dev/null && d3 "$WIRE" "$COORD" >/dev/null && f1 "$FILT" "$PAGE" >/dev/null && n1 "$HOST" >/dev/null && pass "unmutated tree is still green" || fail "the unmutated tree is red"

echo
if [ "$FAILURES" -eq 0 ]; then echo "test-drive-git-depth: all checks passed"; else echo "test-drive-git-depth: $FAILURES check(s) FAILED"; exit 1; fi
