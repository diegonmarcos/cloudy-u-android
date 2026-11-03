#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #567 push 2 — libs:git-sync is a FULL git manager: every verb the brief  ║
# ║ names exists on the engine, is reached from the screen, is proven by a  ║
# ║ JVM test, and branches stay read-only                                    ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# WHY THIS EXISTS. #871 move 11: JGit and the verbs' behaviour on real repositories moved to
# libs:git-sync-engine (Cloud-Lib-Git-Sync-Engine.apk); their checks (the engine proper is pure JVM,
# branches are read-only in JGit, JGitEngineTest names every verb, the jsch wiring, the session leg)
# moved to ab_cloud-libs-shared/lib-apks/test/test-git-sync-engine.sh. What only a tester over THIS
# app's module can see is the SHAPE of the client the manager screen is written against:
# whether the manager screen actually reaches each verb (an engine verb no button calls is a feature
# that does not exist on the phone), whether the client stays Android-free (an android.* import in
# GitEngine.kt makes the JVM suite untestable and silently mocked), and whether the read-only branch
# rule holds at the source level as well as the API level.
#
#   G1  the brief's verb inventory — status, stage, unstage, commit, push, pull, branches, log, diff,
#       remotes, and the conflict surface's three verbs resolve / markResolved / abortMerge — is DECLARED
#       on GitEngine (a `fun <verb>(` in GitEngine.kt) …
#   G2  … and REACHED from GitSyncScreen.kt (a `.<verb>(` call), both ends;
#       and the conflict LIST itself (status().conflicts, the one snapshot) is
#       rendered by the screen.
#   G3  GitEngine.kt imports nothing from android.* — it is the JVM-tested half.
#   G4  branches are read-only: no create/delete/checkout/rename-branch verb on the client, and no
#       JGit anywhere in the module (the app compiles none).
#   G5  the client's JVM suite exists and covers every verb in G1 by name.
#
# OWN-SOURCE ONLY: everything read here is under a module dir this app's
# build.json declares, so the engine judges the verdict as this app's own.
set -uo pipefail

ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
APP="$ROOT/ac_cloud-drive"
MOD="$(python3 -c 'import json,os,sys; b=json.load(open(sys.argv[1])); print(os.path.normpath(os.path.join(sys.argv[2], b["modules"]["libs:git-sync"]["dir"])))' "$APP/build.json" "$APP")"
ENGINE="$MOD/src/main/java/com/diegonmarcos/cloudlib/gitsync/GitEngine.kt"
SCREEN="$MOD/src/main/java/com/diegonmarcos/cloudlib/gitsync/GitSyncScreen.kt"
TEST="$MOD/src/test/java/com/diegonmarcos/cloudlib/gitsync/GitEngineClientTest.kt"
GRADLE="$MOD/build.gradle"

FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }

for required in "$ENGINE" "$SCREEN" "$TEST" "$GRADLE"; do
    [ -f "$required" ] || { echo "ERROR missing source: $required"; exit 1; }
done

# The inventory the brief mandates (#567): "status, stage/unstage, commit,
# push/pull, branch list, log, diff, remotes, conflict surface".
VERBS="status stage unstage commit push pull branches log diff remotes resolve markResolved abortMerge"

echo "── G1/G2 every mandated verb is declared on the engine AND reached from the screen ──"
for verb in $VERBS; do
    if grep -qE "^\s*fun $verb\(" "$ENGINE"; then pass "engine declares $verb()"; else fail "engine lacks fun $verb("; fi
    if grep -qE "\.$verb\(" "$SCREEN"; then pass "screen reaches .$verb("; else fail "screen never calls .$verb( — a verb no button reaches"; fi
done

if grep -qE "\.conflicts\b" "$SCREEN" && grep -qE "val conflicts: List<GitFileStatus>" "$MOD/../git-core/src/main/java/com/diegonmarcos/cloudlib/gitsync/GitModels.kt"; then
    pass "the screen renders the snapshot's conflict list"
else
    fail "the conflict surface is not rendered: GitSyncScreen.kt must read the snapshot's .conflicts"
fi

echo "── G3 the engine is pure JVM ──"
if grep -qE "^import android\." "$ENGINE"; then
    fail "GitEngine.kt imports android.* — the JVM suite would run against stubs"
else
    pass "GitEngine.kt has no android.* import"
fi

echo "── G4 branches are read-only ──"
if grep -qE "fun (createBranch|deleteBranch|checkoutBranch|renameBranch|branchCreate)\(" "$ENGINE"; then
    fail "the engine grew a branch-writing verb (fleet rule: a manager lists branches, never creates them)"
else
    pass "no branch-writing verb on the engine"
fi
WRITES="$(grep -rnE "\.(branchCreate|branchDelete|branchRename)\(" "$MOD/src/main")"
if [ -z "$WRITES" ]; then pass "no JGit branchCreate/branchDelete/branchRename call in the module"; else fail "branch-writing JGit call:"; printf '%s\n' "$WRITES" | sed 's/^/        /'; fi

echo "── G5 the client's JVM suite names every verb ──"
TESTS="$(grep -cE "^\s*@Test" "$TEST")"
if [ "$TESTS" -gt 0 ]; then pass "GitEngineClientTest has $TESTS @Test methods"; else fail "GitEngineClientTest has no @Test"; fi
for verb in $VERBS; do
    if grep -qE "\.$verb\(" "$TEST"; then pass "test exercises .$verb("; else fail "no test calls .$verb("; fi
done
echo "── G6 the app compiles no JGit ──"
if grep -qE "org\.eclipse\.jgit|com\.jcraft" "$GRADLE"; then fail "libs:git-sync names JGit/jsch itself — libs:git-core carries them (api)"; else pass "libs:git-sync carries no JGit and no jsch"; fi
if grep -rqE "org\.eclipse\.jgit|com\.jcraft" "$MOD/src/main"; then fail "libs:git-sync's source imports JGit/jsch"; else pass "no JGit import in libs:git-sync"; fi

echo
if [ "$FAILURES" -eq 0 ]; then echo "test-drive-git-sync-engine: all checks passed"; else echo "test-drive-git-sync-engine: $FAILURES check(s) FAILED"; exit 1; fi
