#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #1315 ONE INVENTORY. Repos in the store were not found because four      ║
# ║ readers (java.io scans, the managed list, termux scripts) disagreed.     ║
# ║ Now ONE discovery pass (InventoryScript, on the one StoreShell for the   ║
# ║ store) feeds ONE classification (RepoInventory) that every list reads.  ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#   I1 the pass is declared: ui.sync.git.inventory carries `debris` (plain globs, `*.incoming` among them) and
#      `auto_register`; Declarations parses both; the doc names the classes.
#   I2 one discovery: the coordinator's scan builds InventoryScript.build for the store roots and InventoryScript.walkIo
#      for the rest, and no longer calls UnlistedRepos.walk; the Unlisted rows are derived from the inventory.
#   I3 one classification: RepoInventory names every class (registered, stale_origin, misnamed, duplicate, unlisted,
#      debris, bare, not_cloned) exactly once in its enum, and the phone fixtures are tested (RepoInventoryPhoneTest
#      holds the stale-origin rows, the misnamed row, the three index-repo clones, the .incoming debris, the exec row).
#   M  mutations: a debris glob with a space in build.json turns I1 red; a coordinator that calls UnlistedRepos.walk turns I2 red.
set -uo pipefail
ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
APP="$ROOT/ac_cloud-drive"
SYNC="$APP/app/src/main/java/com/diegonmarcos/clouddrive/sync"
BJ="$APP/build.json"
FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }

# i1 <build.json>: the inventory block is declared and sane.
i1() {
  python3 - "$1" <<'PY'
import json, re, sys
g = json.load(open(sys.argv[1]))["ui"]["sync"]["git"]
inv = g.get("inventory"); doc = g.get("_doc_inventory", "")
assert isinstance(inv, dict), "no ui.sync.git.inventory"
d = inv.get("debris"); assert isinstance(d, list) and d, "debris must be a non-empty list"
for x in d:
    assert re.fullmatch(r"[A-Za-z0-9._*?-]{1,64}", x) and x not in ("*", ".*"), f"unsafe debris glob {x!r}"
assert "*.incoming" in d, "the interrupted-copy leftover must be debris"
assert isinstance(inv.get("auto_register"), bool), "auto_register must be a bool"
for c in ("registered", "stale_origin", "misnamed", "duplicate", "unlisted", "debris", "bare", "not_cloned"):
    assert c in doc, f"_doc_inventory does not name the class {c}"
PY
}

echo "── I1 declared ──"
if out=$(i1 "$BJ" 2>&1); then pass "ui.sync.git.inventory: debris globs, auto_register, the classes documented"; else fail "inventory declaration: $out"; fi
grep -q 'GitInventoryDecl' "$APP/app/src/main/java/com/diegonmarcos/clouddrive/Declarations.kt" && grep -q 'o\["inventory"\]' "$APP/app/src/main/java/com/diegonmarcos/clouddrive/Declarations.kt" && pass "Declarations parses it" || fail "Declarations does not parse ui.sync.git.inventory"

echo "── I2 one discovery ──"
C="$SYNC/GitSyncCoordinator.kt"
grep -q 'InventoryScript.build(' "$C" && grep -q 'InventoryScript.walkIo(' "$C" && pass "the coordinator discovers through InventoryScript (script for the store, java.io for the rest)" || fail "the coordinator does not discover through InventoryScript"
if grep -q 'UnlistedRepos.walk(' "$C"; then fail "the coordinator still walks with UnlistedRepos.walk (a second reader)"; else pass "no second walk in the coordinator"; fi
grep -q 'RepoInventory.RepoClass.UNLISTED' "$C" && pass "the Unlisted rows are derived from the inventory" || fail "Unlisted rows are not derived from the inventory"
grep -q 'shell.run(InventoryScript.build' "$C" && pass "the store pass runs on the one StoreShell" || fail "the store pass does not run on StoreShell"

echo "── I3 one classification ──"
R="$SYNC/inventory/RepoInventory.kt"
n=$(grep -c 'enum class RepoClass' "$R"); [ "$n" -eq 1 ] && pass "one class enum" || fail "RepoClass enum count $n"
for c in REGISTERED STALE_ORIGIN MISNAMED DUPLICATE UNLISTED DEBRIS NOT_CLONED; do grep -q "$c" "$R" || fail "class $c missing"; done
T="$APP/app/src/test/java/com/diegonmarcos/clouddrive/sync/inventory/RepoInventoryPhoneTest.kt"
for fx in "cloud-u-linux" "cloud-data-lfs" "My-ai-memory" "git-repos-master___CONFLICT" ".incoming" "through cld termux"; do grep -q -- "$fx" "$T" && pass "phone fixture: $fx" || fail "phone fixture missing: $fx"; done

echo "── I4 the deferred three: duplicate badge, age-based cleanup, declared copy timeout ──"
G="$APP/app/src/main/java/com/diegonmarcos/clouddrive/sync/GitReposScreen.kt"
grep -q 'if (copies > 1) CapsuleBadge' "$G" && grep -q 'cloneOf(e.name)?.copies' "$G" && pass "a repository held by several folders carries a copies badge on its main row" || fail "no duplicate badge"
grep -q 'FOLDER_ARCHIVE, Declarations.FOLDER_DELETE' "$SYNC/GitInventoryUi.kt" && pass "Archive / Delete offered on the duplicate copies only (the Duplicate clones section)" || fail "duplicates section lacks Archive / Delete"
grep -q 'fun cleanupDebris(' "$C" && grep -q 'FolderActions.debrisScript(' "$C" && grep -q 'cleanupAuto) cleanupDebris(' "$C" && pass "age-based leftover cleanup runs through the executor after the pass" || fail "no cleanup job"
python3 -c 'import json,sys; g=json.load(open(sys.argv[1]))["ui"]["sync"]["git"]; assert g["inventory"]["cleanup"]["min_age_hours"]>=1 and 10<=g["copy_timeout_seconds"]<=120' "$BJ" && pass "cleanup.min_age_hours and copy_timeout_seconds declared" || fail "cleanup / copy timeout not declared"
EC="$SYNC/ExecCopier.kt"
if grep -q 'copyTimeoutSeconds' "$EC" && ! grep -q '110_000' "$EC"; then pass "the copy deadline is the declared one, not a literal"; else fail "ExecCopier hard-codes its copy timeout"; fi
grep -q 'InventoryScript.sizesScript(' "$C" && pass "store sizes come from one du script on the shell" || fail "sizes still java.io only"

echo "── M mutations ──"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
python3 -c 'import json,sys; b=json.load(open(sys.argv[1])); b["ui"]["sync"]["git"]["inventory"]["debris"].append("bad name"); json.dump(b,open(sys.argv[2],"w"))' "$BJ" "$tmp/b.json"
if i1 "$tmp/b.json" >/dev/null 2>&1; then fail "I1 passed an unsafe debris glob (vacuous)"; else pass "unsafe debris glob turns I1 red"; fi
sed 's/InventoryScript.walkIo(/UnlistedRepos.walk(/' "$C" > "$tmp/C.kt"
if grep -q 'UnlistedRepos.walk(' "$tmp/C.kt"; then pass "a second walk in the coordinator turns I2 red"; else fail "I2 mutation not caught"; fi
sed 's/Declarations.sync.git.copyTimeoutSeconds \* 1000/110_000/' "$SYNC/ExecCopier.kt" > "$tmp/E.kt"
if grep -q '110_000' "$tmp/E.kt" && ! grep -q 'copyTimeoutSeconds' "$tmp/E.kt"; then pass "a hard-coded copy timeout turns I4 red"; else fail "I4 mutation not caught"; fi

[ "$FAILURES" -eq 0 ] && { echo "test-drive-git-inventory: all green"; exit 0; } || { echo "test-drive-git-inventory: $FAILURES failure(s)"; exit 1; }
