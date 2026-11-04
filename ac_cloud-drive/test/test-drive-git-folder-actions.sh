#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #1288 the Git page's per-folder commands (Rename / Archive / Delete) and ║
# ║ the folder tables' header parity. Gradle proves it compiles; the JVM     ║
# ║ suite (FolderActionsTest) proves the rules; this proves the shape.       ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#   K1 kinds are DATA: ui.sync.git.folder_actions kinds == Declarations FOLDER_KINDS == the coordinator's handlers, both ways.
#   R1 the runner is the depth runner: runFolderAction goes through startDepth + bounded, logs to Activity Logs; no folder
#      command touches a remote (no push / gh / api delete); scripts never glob.
#   C1 rename never overwrites: the coordinator names through FolderActions.renameTarget (___CONFLICT), the move refuses an
#      existing destination.
#   H1 header parity: Parentless and Unlisted draw FolderRepoTable, which uses the main RepoTableHeader / RepoCells /
#      RepoStatusCell; the one header iterates RepoTable.COLUMNS; no other column header exists.
set -uo pipefail

ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
APP="$ROOT/ac_cloud-drive"
BJ="$APP/build.json"
SRC="$APP/app/src/main/java/com/diegonmarcos/clouddrive"
COORD="$SRC/sync/GitSyncCoordinator.kt"
DECL="$SRC/Declarations.kt"
TABLE="$SRC/sync/FolderTableUi.kt"
RULES="$SRC/sync/FolderActions.kt"
TESTS="$APP/app/src/test/java/com/diegonmarcos/clouddrive"

FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }

k1() { python3 - "$1" "$2" "$3" <<'PY'
import json, re, sys
g = json.load(open(sys.argv[1]))["ui"]["sync"]["git"]
coord = open(sys.argv[2]).read(); decl = open(sys.argv[3]).read()
bad = 0
acts = g.get("folder_actions") or []
declared = {a["kind"] for a in acts}
consts = dict((v, k) for k, v in re.findall(r'const val (FOLDER_[A-Z_]+) = "([a-z_]+)"', decl))
m = re.search(r"val FOLDER_KINDS = setOf\(([^)]*)\)", decl)
known = set(m.group(1).replace(" ", "").split(",")) if m else set()
if {"rename_to_repo", "archive", "delete_local"} - declared: print("    folder_actions lacks", sorted({"rename_to_repo", "archive", "delete_local"} - declared)); bad = 1
body = re.search(r"fun runFolderAction\(.*?\n    }\n", coord, re.S)
body = body.group(0) if body else ""
for k in declared:
    c = consts.get(k)
    if not c or c not in known: print("    declared kind has no constant in FOLDER_KINDS:", k); bad = 1
    elif not re.search(r"Declarations\." + c + r" ->", body): print("    runFolderAction has no handler for", k); bad = 1
for c in known:
    if next((k for k, v in consts.items() if v == c), None) not in declared: print("    handled kind is not declared:", c); bad = 1
handled = set(re.findall(r"Declarations\.(FOLDER_[A-Z_]+) ->", body))
if handled - known: print("    a handler names an undeclared kind:", sorted(handled - known)); bad = 1
if any(not a.get("confirm", True) for a in acts): print("    every folder command asks first"); bad = 1
root = g.get("archive_root", "")
if not root or root.startswith("/") or ".." in root.split("/"): print("    archive_root must be a relative safe path:", root); bad = 1
sys.exit(bad)
PY
}
# #1315 the folder commands run through the depth path onto the ONE executor (depth(run, ...) -> GitJobs), one item per target.
r1() { for t in 'depth(run, plan = { targets.map' 'history.append(SyncEvent(' 'fun asRun()'; do grep -qF "$t" "$1" "$2" || { echo "    lacks $t"; return 1; }; done
    python3 - "$1" "$3" <<'PY' || return 1
import re, sys
c = open(sys.argv[1]).read(); r = open(sys.argv[2]).read()
a = c.find("// ── #1288"); b = c.find("fun requestFolderStatus", a)
seg = c[a:b] if a >= 0 and b > a else ""
if not seg: print("    no #1288 section"); sys.exit(1)
if re.search(r"\bpush\b|GhEngine|deleteRepo|\"DELETE\"", seg): print("    a folder command reaches a remote"); sys.exit(1)
for fn in ("moveScript", "deleteScript"):
    m = re.search(r"fun " + fn + r"\(.*?\n\n", r, re.S)
    if not m or "*" in m.group(0).split("=", 1)[1]: print("    script globs or missing:", fn); sys.exit(1)
if "] || exit 17;" not in r: print("    the move does not refuse an existing destination"); sys.exit(1)
PY
}
# #1315 the only rename is the coordinator's (through FolderActions.renamePlan -> renameTarget); LocalRepos carries no java.io rename.
c1() { grep -qF 'FolderActions.renamePlan(' "$1" && grep -qF 'renameTarget(want, taken)' "$3" && ! grep -qF 'fun rename(' "$2" && grep -qF 'if (gf == null && to.exists()) return GitOpResult(false' "$1" || { echo "    rename does not name through renamePlan/renameTarget / refuse an existing destination"; return 1; }; }
# #1317 E1: java.io is asked about a folder only through goneByIo (never when the store is reached through cld.termux); the conflict probe is the termux one.
e1() { python3 - "$1" <<'PY'
import re, sys
c = open(sys.argv[1]).read()
a = c.find("// ── #1288"); b = c.find("fun requestFolderStatus", a); seg = c[a:b]
bad = [l.strip() for l in seg.splitlines() if "isDirectory" in l and "goneByIo" not in l and "fun deleteGuard" not in l and "val dir" not in l and "GitEngine" not in l]
bad = [l for l in bad if "File(dir, \".git\")" not in l]
if bad: print("    java.io pre-check outside goneByIo:", bad); sys.exit(1)
if seg.count("goneByIo(") < 3: print("    rename/archive/delete must all use goneByIo"); sys.exit(1)
if "takenInExec(" not in seg or "FolderActions.takenScript(" not in seg: print("    the conflict probe does not go through cld.termux"); sys.exit(1)
PY
}
# #1317 N1: names fold through the registry before any matching; the rows show the old name as old; stale origins are fixed (manual kind + auto_fix).
n1() { python3 - "$1" "$2" "$3" "$4" <<'PY'
import json, re, sys
coord, lr, panel, bj = (open(x).read() for x in sys.argv[1:5])
bad = 0
if "names: RegistryNames" not in lr or "names.currentName(raw)" not in lr or "names.carries(" not in lr: print("    LocalRepos does not fold origin names through the registry"); bad = 1
if "RegistryNames.live()" not in coord or "FolderActions.renamePlan(" not in coord or "renameName(originName, names)" not in open(sys.argv[1].replace("GitSyncCoordinator.kt", "FolderActions.kt")).read(): print("    rename target is not the resolved current name"); bad = 1
# #1315 Parentless reads THE ONE INVENTORY (misnamed + stale-origin rows), the auto-fix runs inside the inventory pass.
if "coordinator.inventory" not in panel or "RepoClass.MISNAMED" not in panel or "RepoClass.STALE_ORIGIN" not in panel or "originLabel(" not in panel or "LocalRepos.scan(" in panel: print("    Parentless does not read the inventory / show old origins"); bad = 1
if "autoFixOrigins(stale)" not in coord or "staleOrigin && it.isClone" not in coord: print("    the inventory pass does not auto-fix stale origins"); bad = 1
g = json.loads(bj)["ui"]["sync"]["git"]
if not g.get("stale_origin", {}).get("auto_fix") or "_doc_stale_origin" not in g: print("    stale_origin.auto_fix or its _doc missing"); bad = 1
if "UnlistedRepos.without(" not in coord: print("    lists are not refreshed after a run"); bad = 1
sys.exit(bad)
PY
}
h1() { python3 - "$SRC/sync" <<'PY'
import os, re, sys
d = sys.argv[1]; rd = lambda f: open(os.path.join(d, f)).read()
bad = 0
t = rd("FolderTableUi.kt")
for need in ("RepoTableHeader(", "RepoCells(", "RepoStatusCell(", "FolderTable.rowOf("):
    if need not in t: print("    FolderRepoTable lacks", need); bad = 1
if "FolderRepoTable(" not in rd("ParentlessPanel.kt"): print("    Parentless is not the folder table"); bad = 1
if not re.search(r"fun UnlistedPanel\([^{]*\{[^}]*FolderRepoTable\(", rd("GitDepthUi.kt"), re.S): print("    Unlisted is not the folder table"); bad = 1
ui = rd("RepoTableUi.kt")
# #1356 the header iterates every column, split into the pinned (Status, Name) and the scrolling half
hdr = ui[ui.index("fun RepoTableHeader("):]
if not (re.search(r"RepoTable\.COLUMNS\.forEach", hdr[:2500]) or ("RepoTable.COLUMNS.filter { it in RepoTable.PINNED }, RepoTable.COLUMNS.filter { it !in RepoTable.PINNED }" in hdr[:2500])): print("    the header does not iterate RepoTable.COLUMNS"); bad = 1
for f in os.listdir(d):
    if f.endswith(".kt") and re.search(r"RepoColumn\.entries\.forEach", rd(f)): print("    a second header loop in", f); bad = 1
if "val COLUMNS: List<RepoColumn> get() = RepoTable.COLUMNS" not in rd("RepoTable.kt"): print("    FolderTable.COLUMNS is not RepoTable.COLUMNS"); bad = 1
sys.exit(bad)
PY
}

echo "── K kinds ──"
k1 "$BJ" "$COORD" "$DECL" && pass "declared folder kinds == FOLDER_KINDS == handlers" || fail "folder kinds drift"
echo "── R runner ──"
r1 "$COORD" "$DECL" "$RULES" && pass "depth runner, Activity Logs, no remote, no glob, refusing move" || fail "runner"
echo "── C rename ──"
c1 "$COORD" "$SRC/sync/LocalRepos.kt" "$RULES" && pass "rename names via ___CONFLICT and never overwrites" || fail "rename conflict"
echo "── E exec path (#1317) ──"
e1 "$COORD" && pass "no java.io pre-check on the cld.termux path; the conflict probe is the termux one" || fail "exec path"
echo "── N registry names (#1317) ──"
n1 "$COORD" "$SRC/sync/LocalRepos.kt" "$SRC/sync/ParentlessPanel.kt" "$BJ" && pass "origin names fold through renamed_from; Fix origin + auto_fix declared; lists refresh" || fail "registry names"
echo "── I self entry and .incoming (#1317) ──"
SYNC="$SRC/sync"
i1() { grep -qF 'rm -rf ${q(into)}; {' "$1" && grep -qF 'rc=\$?; rm -rf ${q(into)}' "$1" && grep -qF '!isIncoming(it)' "$2" && grep -qF 'Kind.DEBRIS' "$3" && grep -qF 'sot.listed' "$4" && grep -qF 'x.strings("renamed_from")' "$5" || { echo "    self entry / .incoming guards missing"; return 1; }; }
# #1315 the listed set (repos + self) is consumed by the ONE inventory classification (sync/inventory/RepoInventory), not the coordinator.
I_ARGS=("$SYNC/ExecCopier.kt" "$SYNC/LocalRepos.kt" "$SYNC/inventory/InventoryScript.kt" "$SYNC/inventory/RepoInventory.kt" "$DECL")
i1 "${I_ARGS[@]}" && pass "stale .incoming cleared first and on failure, never listed; self entry folded into listed" || fail "self/.incoming"
echo "── H header parity ──"
h1 && pass "Parentless/Unlisted use the main header, cells and status icons" || fail "header parity"
echo "── JVM ──"
grep -q 'header' "$TESTS/sync/FolderActionsTest.kt" && grep -q '___CONFLICT3' "$TESTS/sync/FolderActionsTest.kt" && grep -q 'the exec path never pre-checks' "$TESTS/sync/FolderOriginsTest.kt" && pass "JVM FolderActionsTest (conflict, archive, guard, parity)" || fail "missing FolderActionsTest"

echo "── M mutation-proof ──"
TMP="$(mktemp -d)"; trap 'rm -rf "${TMP:?}"' EXIT
python3 -c 'import json,sys; b=json.load(open(sys.argv[1])); b["ui"]["sync"]["git"]["folder_actions"].append({"id":"x","kind":"bogus"}); json.dump(b,open(sys.argv[2],"w"))' "$BJ" "$TMP/b.json"
k1 "$TMP/b.json" "$COORD" "$DECL" >/dev/null && fail "K1 passed an undeclared kind (vacuous)" || pass "bogus kind → K1 RED"
sed 's/Declarations.FOLDER_ARCHIVE -> archiveFolder(t)//' "$COORD" > "$TMP/c.kt"; k1 "$BJ" "$TMP/c.kt" "$DECL" >/dev/null && fail "K1 passed a missing handler (vacuous)" || pass "handler removed → K1 RED"
sed 's/|| exit 17//' "$RULES" > "$TMP/r.kt"; r1 "$COORD" "$DECL" "$TMP/r.kt" >/dev/null && fail "R1 passed a merging move (vacuous)" || pass "refusal removed → R1 RED"
sed 's/FolderActions.renamePlan(/FolderActions.renameTargetX(/' "$COORD" > "$TMP/p.kt"; c1 "$TMP/p.kt" "$SRC/sync/LocalRepos.kt" "$RULES" >/dev/null && fail "C1 passed a coordinator without the plan (vacuous)" || pass "plan removed → C1 RED"
sed 's/FolderActions.goneByIo(g != null) { dir.isDirectory }/!dir.isDirectory/' "$COORD" > "$TMP/e.kt"; e1 "$TMP/e.kt" >/dev/null && fail "E1 passed a java.io pre-check (vacuous)" || pass "java.io pre-check back → E1 RED"
sed 's/FolderActions.takenScript(/io.takenScript(/' "$COORD" > "$TMP/e2.kt"; e1 "$TMP/e2.kt" >/dev/null && fail "E1 passed a conflict check without termux (vacuous)" || pass "termux probe removed → E1 RED"
sed 's/names.currentName(raw)/raw/' "$SRC/sync/LocalRepos.kt" > "$TMP/l.kt"; n1 "$COORD" "$TMP/l.kt" "$SRC/sync/ParentlessPanel.kt" "$BJ" >/dev/null && fail "N1 passed an origin taken as-is (vacuous)" || pass "origin not folded → N1 RED"
sed 's/UnlistedRepos.without(/UnlistedRepos.keepAll(/' "$COORD" > "$TMP/n.kt"; n1 "$TMP/n.kt" "$SRC/sync/LocalRepos.kt" "$SRC/sync/ParentlessPanel.kt" "$BJ" >/dev/null && fail "N1 passed a list that never refreshes (vacuous)" || pass "refresh removed → N1 RED"
python3 -c 'import json,sys; b=json.load(open(sys.argv[1])); del b["ui"]["sync"]["git"]["stale_origin"]; json.dump(b,open(sys.argv[2],"w"))' "$BJ" "$TMP/b2.json"
n1 "$COORD" "$SRC/sync/LocalRepos.kt" "$SRC/sync/ParentlessPanel.kt" "$TMP/b2.json" >/dev/null && fail "N1 passed without stale_origin (vacuous)" || pass "stale_origin removed → N1 RED"
sed 's/rm -rf ${q(into)}; {/{/' "$SYNC/ExecCopier.kt" > "$TMP/x1.kt"; i1 "$TMP/x1.kt" "${I_ARGS[@]:1}" >/dev/null && fail "I1 passed a copy that keeps a stale incoming (vacuous)" || pass "stale incoming not cleared → I1 RED"
sed 's/!isIncoming(it) \&\& //' "$SYNC/LocalRepos.kt" > "$TMP/x2.kt"; i1 "${I_ARGS[0]}" "$TMP/x2.kt" "${I_ARGS[@]:2}" >/dev/null && fail "I1 passed an incoming row (vacuous)" || pass ".incoming listed → I1 RED"
sed 's/sot\.listed/sot.repos/' "$SYNC/inventory/RepoInventory.kt" > "$TMP/x3.kt"; i1 "${I_ARGS[@]:0:3}" "$TMP/x3.kt" "$DECL" >/dev/null && fail "I1 passed without the self entry (vacuous)" || pass "self not folded into listed → I1 RED"
k1 "$BJ" "$COORD" "$DECL" >/dev/null && r1 "$COORD" "$DECL" "$RULES" >/dev/null && h1 >/dev/null && e1 "$COORD" >/dev/null && n1 "$COORD" "$SRC/sync/LocalRepos.kt" "$SRC/sync/ParentlessPanel.kt" "$BJ" >/dev/null && i1 "${I_ARGS[@]}" >/dev/null && pass "unmutated tree is still green" || fail "the unmutated tree is red"

echo
if [ "$FAILURES" -eq 0 ]; then echo "test-drive-git-folder-actions: all checks passed"; else echo "test-drive-git-folder-actions: $FAILURES check(s) FAILED"; exit 1; fi
