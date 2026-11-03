#!/usr/bin/env bash
# #1289 THE PER-REPOSITORY MENU of the Git page is data (build.json ui.sync.git.repo_menu).
# G1 groups in the owner's order; every action drawn before #1289 sits in exactly one group;
#    Configs-Auth holds no clone/depth item; Manual Sync's Dangerous subgroup is marked and is the destructive ops;
#    AutoSync defaults to 15 minutes (WorkManager's floor). Each assertion is proven red by a mutation.
# G2 the page renders the declared groups (no hard-coded group list) and GitSyncWorker ticks at AutoSyncPlan.tickMinutes.
set -uo pipefail
ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
APP="$ROOT/ac_cloud-drive"
BJ="$APP/build.json"
SRC="$APP/app/src/main/java/com/diegonmarcos/clouddrive"
FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }

g1() { python3 - "$1" <<'PY'
import json, sys
g = json.load(open(sys.argv[1]))["ui"]["sync"]["git"]
m = g.get("repo_menu") or {}
groups = m.get("groups") or []
bad = 0
order = ["goto", "clone", "sync", "manual_sync", "workflow", "repo_clean", "others", "configs_auth", "configs_autosync"]
if [x.get("id") for x in groups] != order: print("    group order", [x.get("id") for x in groups]); bad = 1
def ids(x): return [i["id"] for i in x.get("items", [])] + [i for s in x.get("subgroups", []) for i in ids(s)]
allids = [i for x in groups for i in ids(x)]
if len(allids) != len(set(allids)): print("    an id sits in two groups"); bad = 1
previous = ["sync_no_winner", "sync_remote_wins", "sync_local_wins", "fetch", "pull", "push", "commit", "stats", "checkout_main", "branches",
            "unpushed", "open_folder", "web", "history", "runs", "actions", "prs", "clean_1", "clean_5", "clean_10", "force_pull", "force_push",
            "remote", "metadata", "sync_now", "hooks", "size", "settings", "clone_full", "clone_depth1", "clone_oauth", "clone_ssh"]
previous += [a["id"] for a in g.get("depth_actions", []) if a.get("scope") == "repo"]
for i in previous + [o["id"] for o in g.get("ops", [])]:
    if allids.count(i) != 1: print("    lost or doubled:", i); bad = 1
by = {x["id"]: x for x in groups}
depth = {a["id"] for a in g.get("depth_actions", [])} | {"clone_full", "clone_depth1"}
auth = ids(by.get("configs_auth", {}))
if not auth or any(i in depth or "depth" in i or "bare" in i for i in auth): print("    Configs-Auth holds a clone/depth item:", auth); bad = 1
ms = by.get("manual_sync", {})
subs = ms.get("subgroups", [])
dest = sorted(o["id"] for o in g.get("ops", []) if o.get("destructive"))
if ms.get("layout") != "row" or len(subs) != 1 or not subs[0].get("danger") or sorted(ids(subs[0])) != dest: print("    Manual Sync / Dangerous subgroup"); bad = 1
# #1356 Manual Sync: the Sync dropdown's four actions, icons on ONE row; Dangerous also one row, red, confirmed (RepoActions.route)
if [i["id"] for i in ms.get("items", [])] != ["stash", "pull_rebase", "stash_pop", "auto_commit", "solve_conflicts"]: print("    Manual Sync is not Stash, Pull (Rebase), Stash Pop, Auto Commit, Conflict Solver"); bad = 1
if subs and subs[0].get("layout") != "row": print("    Dangerous is not one row"); bad = 1
if any(not i.get("icon") for i in ms.get("items", []) + (subs[0].get("items", []) if subs else [])): print("    a row action has no icon"); bad = 1
for w in ("list_dirty", "list_conflicts"):
    if w not in ids(by.get("workflow", {})): print("    workflow lacks", w); bad = 1
if ids(by.get("workflow", {}))[:1] != ["unpushed"]: print("    Commits Unpushed is not Workflow's first item"); bad = 1
a = m.get("autosync", {})
if a.get("default_minutes") != 15 or a.get("min_minutes", 15) < 15 or any(o < 15 for o in a.get("options_minutes", [])): print("    autosync defaults"); bad = 1
for x in groups:
    for i in x.get("items", []) + [j for s in x.get("subgroups", []) for j in s.get("items", [])]:
        if not i.get("label") or not (i.get("i18n") or {}).get("es"): print("    unlabelled:", i.get("id")); bad = 1
sys.exit(bad)
PY
}
g2() {
    grep -qF 'page.repoMenu.groups.forEach' "$1" || { echo "    the menu is not drawn from the declaration"; return 1; }
    ! grep -qE 'RepoActions\.GROUPS|enum class RepoGroup' "$1" "$2" || { echo "    a hard-coded group list is back"; return 1; }
    grep -qF 'AutoSyncPlan.tickMinutes(BuildConfig.GIT_SYNC_INTERVAL_MINUTES, opted)' "$3" || { echo "    the worker does not tick at the AutoSync period"; return 1; }
}

echo "── G declaration ──"
g1 "$BJ" && pass "groups, placement, Configs-Auth, Dangerous, AutoSync 15" || fail "repo_menu declaration"
g2 "$SRC/sync/RepoTableUi.kt" "$SRC/sync/RepoActions.kt" "$SRC/GitSyncWorker.kt" && pass "page draws the declared groups; worker ticks at AutoSync" || fail "page/worker wiring"
grep -q '@Test' "$APP/app/src/test/java/com/diegonmarcos/clouddrive/sync/RepoMenuTest.kt" && grep -q '@Test' "$APP/app/src/test/java/com/diegonmarcos/clouddrive/sync/RepoWorkflowTest.kt" && pass "JVM RepoMenuTest + RepoWorkflowTest present" || fail "missing JVM tests"

echo "── M mutation-proof ──"
TMP="$(mktemp -d)"; trap 'rm -rf "${TMP:?}"' EXIT
mut() { python3 -c "import json,sys; b=json.load(open(sys.argv[1])); m=b['ui']['sync']['git']['repo_menu']; exec(sys.argv[3]); json.dump(b,open(sys.argv[2],'w'))" "$BJ" "$TMP/b.json" "$1"; }
mut "g={x['id']:x for x in m['groups']}; g['configs_auth']['items'].append(g['clone']['items'].pop(0))"; g1 "$TMP/b.json" >/dev/null && fail "G1 passed a clone item in Configs-Auth (vacuous)" || pass "clone item in Configs-Auth → G1 RED"
mut "g={x['id']:x for x in m['groups']}; g['goto']['items'].pop(0)"; g1 "$TMP/b.json" >/dev/null && fail "G1 passed a lost action (vacuous)" || pass "lost action → G1 RED"
mut "m['groups'].reverse()"; g1 "$TMP/b.json" >/dev/null && fail "G1 passed a reordered menu (vacuous)" || pass "reordered → G1 RED"
mut "m['autosync']['default_minutes']=5"; g1 "$TMP/b.json" >/dev/null && fail "G1 passed a 5-minute default (vacuous)" || pass "autosync default 5 → G1 RED"
mut "g={x['id']:x for x in m['groups']}; g['manual_sync']['subgroups'][0]['danger']=False"; g1 "$TMP/b.json" >/dev/null && fail "G1 passed an unmarked Dangerous (vacuous)" || pass "unmarked Dangerous → G1 RED"
mut "g={x['id']:x for x in m['groups']}; g['manual_sync']['layout']='lines'"; g1 "$TMP/b.json" >/dev/null && fail "G1 passed Manual Sync one per line (vacuous)" || pass "Manual Sync back to one item per line → G1 RED"
mut "g={x['id']:x for x in m['groups']}; g['manual_sync']['subgroups'][0]['layout']='lines'"; g1 "$TMP/b.json" >/dev/null && fail "G1 passed Dangerous one per line (vacuous)" || pass "Dangerous back to one per line → G1 RED"
sed 's/page.repoMenu.groups.forEach/RepoActions.GROUPS.forEach/' "$SRC/sync/RepoTableUi.kt" > "$TMP/u.kt"; g2 "$TMP/u.kt" "$SRC/sync/RepoActions.kt" "$SRC/GitSyncWorker.kt" >/dev/null && fail "G2 passed a hard-coded menu (vacuous)" || pass "hard-coded groups → G2 RED"
g1 "$BJ" >/dev/null && pass "unmutated tree is still green" || fail "the unmutated tree is red"

echo
if [ "$FAILURES" -eq 0 ]; then echo "test-drive-git-menu: all checks passed"; else echo "test-drive-git-menu: $FAILURES check(s) FAILED"; exit 1; fi
