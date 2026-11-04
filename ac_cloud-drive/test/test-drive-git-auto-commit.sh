#!/usr/bin/env bash
# #1419 AUTO COMMIT wiring (the message rule and the writer fallback are pinned by app/src/test/.../AutoCommitTest.kt).
# A1 the repo sheet's Manual Sync row declares auto_commit; the top Sync menu declares "Auto Commit All" (compound auto_commit, Manual Sync).
# A2 the per-repo chip runs the compound verb; the top-bar dispatch narrows Auto Commit All to the dirty clones (AutoCommit.targets);
#    RepoCompound runs AutoCommitDrive.run for it, and the run never pushes.
# A3 Configs ▸ Git (the setup part) draws AutoCommitSection with the rule, the sample, Rule | OpenRouter model and a picker.
# A4 the model writer reads no OpenRouter key (decisions-use guard D5): no revealAiKey, it goes through the fleet gateway.
# Each assertion is proven red by its own mutation.
set -uo pipefail
ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
APP="$ROOT/ac_cloud-drive"
SYNC="$APP/app/src/main/java/com/diegonmarcos/clouddrive/sync"
FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT

a1() { python3 - "$1" <<'PY'
import json, sys
g = json.load(open(sys.argv[1]))["ui"]["sync"]["git"]
ms = [x for x in g["repo_menu"]["groups"] if x["id"] == "manual_sync"]
bad = 0
if not ms or "auto_commit" not in [i["id"] for i in ms[0]["items"]]: print("    Manual Sync has no auto_commit"); bad = 1
sync = [x for x in g["top_bar"]["groups"] if x["id"] == "sync"]
it = [i for i in (sync[0]["items"] if sync else []) if i.get("op") == "auto_commit"]
if len(it) != 1 or it[0].get("kind") != "compound" or it[0].get("section") != "Manual Sync" or it[0].get("label") != "Auto Commit All": print("    the Sync menu has no Auto Commit All"); bad = 1
sys.exit(bad)
PY
}
a2() { python3 - "$1" "$2" "$3" <<'PY'
import re, sys
scr, comp, drv = (open(p).read() for p in sys.argv[1:4])
bad = 0
if not re.search(r"RepoActions\.STASH_POP, RepoActions\.AUTO_COMMIT -> coordinator\.runCompound\(repo, id\)", scr): print("    the repo chip does not run auto_commit"); bad = 1
if not re.search(r'item\.op == RepoActions\.AUTO_COMMIT\) AutoCommit\.targets\(scopeTargets\)', scr): print("    Auto Commit All is not narrowed to the dirty clones"); bad = 1
if "id == RepoActions.AUTO_COMMIT -> AutoCommitDrive.run(ctx, dir, name, email)" not in comp: print("    RepoCompound does not run AutoCommitDrive"); bad = 1
body = drv[drv.find("fun run("):]
if "e.stageAll()" not in body or "AutoCommit.write(" not in body or "e.commit(w.message" not in body: print("    the run does not stage, write and commit"); bad = 1
if re.search(r"\.push\(|force_push", body): print("    the run pushes"); bad = 1
sys.exit(bad)
PY
}
a3() { python3 - "$1" "$2" <<'PY'
import sys
scr, ui = (open(p).read() for p in sys.argv[1:3])
bad = 0
if 'if (setup) item(key = "auto_commit") { AutoCommitSection() }' not in scr: print("    Configs ▸ Git does not draw the section"); bad = 1
for need in ("AutoCommit.message(AutoCommit.SAMPLE)", "AutoCommit.Writer.values()", "AutoCommitDrive.setWriter", "DropdownMenu", "AutoCommitDrive.setModel"):
    if need not in ui: print("    the section lacks", need); bad = 1
sys.exit(bad)
PY
}
a4() { python3 - "$1" "$2" <<'PY'
import sys
drv, core = (open(p).read() for p in sys.argv[1:3])
bad = 0
if "revealAiKey" in drv or "revealAiKey" in core: print("    Auto Commit reads the OpenRouter key"); bad = 1
if "AiGatewayBackend(" not in drv: print("    the model writer does not use the fleet gateway"); bad = 1
sys.exit(bad)
PY
}

echo "── A wiring ──"
a1 "$APP/build.json" && pass "A1 declared in Manual Sync and the Sync menu" || fail "A1 declarations"
a2 "$SYNC/GitReposScreen.kt" "$SYNC/RepoCompound.kt" "$SYNC/AutoCommitDrive.kt" && pass "A2 per-repo + all-repos dispatch" || fail "A2 dispatch"
a3 "$SYNC/GitReposScreen.kt" "$SYNC/AutoCommitUi.kt" && pass "A3 Configs ▸ Git ▸ Auto Commit" || fail "A3 Configs section"
a4 "$SYNC/AutoCommitDrive.kt" "$APP/../ab_cloud-libs-shared/libs/git-repos/src/main/java/com/diegonmarcos/cloudlib/gitrepos/AutoCommit.kt" && pass "A4 no key read, fleet gateway" || fail "A4 key"

echo "── M mutations ──"
mutf() { # file, python replace (a, b), runner
  cp "$1" "$TMP/m"; python3 -c "import sys;s=open(sys.argv[1]).read();assert sys.argv[2] in s;open(sys.argv[1],'w').write(s.replace(sys.argv[2],sys.argv[3],1))" "$TMP/m" "$2" "$3" || { fail "mutation anchor missing: $2"; return 1; }; }
mutf "$APP/build.json" '{"id": "auto_commit", "label": "Auto Commit"' '{"id": "auto_commitx", "label": "Auto Commit"' && { a1 "$TMP/m" >/dev/null && fail "A1 passed without the repo chip (vacuous)" || pass "repo chip dropped → A1 RED"; }
mutf "$APP/build.json" '"label": "Auto Commit All"' '"label": "Commit"' && { a1 "$TMP/m" >/dev/null && fail "A1 passed a relabelled menu item (vacuous)" || pass "Auto Commit All relabelled → A1 RED"; }
mutf "$SYNC/GitReposScreen.kt" 'RepoActions.STASH_POP, RepoActions.AUTO_COMMIT -> coordinator' 'RepoActions.STASH_POP -> coordinator' && { a2 "$TMP/m" "$SYNC/RepoCompound.kt" "$SYNC/AutoCommitDrive.kt" >/dev/null && fail "A2 passed an unhandled chip (vacuous)" || pass "chip unhandled → A2 RED"; }
mutf "$SYNC/GitReposScreen.kt" 'item.op == RepoActions.AUTO_COMMIT) AutoCommit.targets(scopeTargets)' 'item.op == RepoActions.AUTO_COMMIT) (scopeTargets)' && { a2 "$TMP/m" "$SYNC/RepoCompound.kt" "$SYNC/AutoCommitDrive.kt" >/dev/null && fail "A2 passed all clones (vacuous)" || pass "all clones, not dirty → A2 RED"; }
mutf "$SYNC/AutoCommitDrive.kt" 'val c = e.commit(w.message, name, email)' 'val c = e.commit(w.message, name, email); e.push()' && { a2 "$SYNC/GitReposScreen.kt" "$SYNC/RepoCompound.kt" "$TMP/m" >/dev/null && fail "A2 passed a pushing run (vacuous)" || pass "run pushes → A2 RED"; }
mutf "$SYNC/GitReposScreen.kt" 'if (setup) item(key = "auto_commit") { AutoCommitSection() }' '' && { a3 "$TMP/m" "$SYNC/AutoCommitUi.kt" >/dev/null && fail "A3 passed without the section (vacuous)" || pass "section dropped → A3 RED"; }
mutf "$SYNC/AutoCommitUi.kt" 'AutoCommit.message(AutoCommit.SAMPLE)' '""' && { a3 "$SYNC/GitReposScreen.kt" "$TMP/m" >/dev/null && fail "A3 passed without the sample (vacuous)" || pass "no sample → A3 RED"; }
mutf "$SYNC/AutoCommitDrive.kt" 'fun online(' 'fun k() = TextToolsClient(x).revealAiKey("openrouter")
    fun online(' && { a4 "$TMP/m" "$APP/../ab_cloud-libs-shared/libs/git-repos/src/main/java/com/diegonmarcos/cloudlib/gitrepos/AutoCommit.kt" >/dev/null && fail "A4 passed a key read (vacuous)" || pass "key read → A4 RED"; }

[ "$FAILURES" -eq 0 ] && echo "test-drive-git-auto-commit: all green" || { echo "test-drive-git-auto-commit: $FAILURES failure(s)"; exit 1; }
