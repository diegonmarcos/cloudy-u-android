#!/usr/bin/env bash
# test-git-connect-one: #1445 the GitHub connect (clone, pull, auto-commit, push with a token) exists ONCE, in libs:git-ui
# GitConnect; no app re-implements it. Data: 1_cicd/src/data/git-connect-one.json (owners, never, legacy).
# Holds that: 1. no file outside the owner libs and the `legacy` list builds a GitHub credential / clones / uses JGit;
# 2. Cloud Me, Cloud Account and libs:account-ui are clean; 3. the legacy list names only files that still match (it
# may only shrink); 4. Configs > Repos reaches GitConnectPanel. Each mutation is planted in a scratch copy and must go RED.
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DECL="1_cicd/src/data/git-connect-one.json"
PAT='GitAuth\.Https\(|GitEngine\.clone\(|org\.eclipse\.jgit|x-access-token'

check() {
python3 - "$1" "$DECL" "$PAT" <<'PY'
import json, os, re, subprocess, sys
R, DECL, PAT = sys.argv[1:4]
d = json.load(open(os.path.join(R, DECL)))
fails = 0
def ok(c, good, bad):
    global fails
    print(("  ok   " if c else "  FAIL ") + (good if c else bad))
    if not c: fails += 1
hits = []
for dp, dn, fn in os.walk(R):
    dn[:] = [x for x in dn if x not in (".git", "z_archive", "build", "node_modules", "test")]
    for f in fn:
        if not f.endswith((".kt", ".java", ".gradle")): continue
        rel = os.path.relpath(os.path.join(dp, f), R)
        if "/src/test/" in rel or rel.startswith("1_cicd/"): continue
        if re.search(PAT, open(os.path.join(dp, f), encoding="utf-8", errors="replace").read()): hits.append(rel)
owned = lambda p: any(p.startswith(o) for o in d["owners"])
foreign = sorted(h for h in hits if not owned(h) and h not in d["legacy"])
ok(not foreign, "no app re-implements the GitHub connect", f"re-implemented outside libs:git-ui: {foreign}")
ok(not [h for h in hits if any(h.startswith(n) for n in d["never"])], "Cloud Me, Cloud Account and libs:account-ui are clean", "an app that must use GitConnect builds its own")
stale = sorted(set(d["legacy"]) - set(hits))
ok(not stale, "every legacy entry still matches (the list only shrinks)", f"legacy entries no longer match, remove them: {stale}")
ru = open(os.path.join(R, "ab_cloud-libs-shared/libs/account-ui/src/main/java/com/diegonmarcos/cloudaccount/configs/RepoConnectorPage.kt")).read()
ok("GitConnectPanel(" in ru and "GitCredentialStore" not in ru and "GitEngine" not in ru, "Configs > Repos hands the declaration to GitConnectPanel", "Configs > Repos does not use GitConnectPanel only")
sys.exit(fails)
PY
}

echo "-- real tree --"
check "$ROOT"; REAL=$?
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
M=0
mutate() {  # mutate <label> <relative new file> <content>
  rm -rf "$TMP/r"; mkdir -p "$TMP/r"
  (cd "$ROOT" && git ls-files -co --exclude-standard | grep -E '\.(kt|java|gradle)$|git-connect-one\.json$' | grep -v '^z_archive/' | tar -cf - -T - 2>/dev/null) | tar -xf - -C "$TMP/r"
  mkdir -p "$TMP/r/$(dirname "$2")"; printf '%s\n' "$3" >> "$TMP/r/$2"
  if check "$TMP/r" >/dev/null 2>&1; then echo "  NOT RED: $1"; M=1; else echo "  red  $1"; fi
}
echo "-- mutations --"
mutate "Cloud Me builds its own GitHub credential" "ac_cloud-me/app/src/main/java/com/diegonmarcos/cloudme/Own.kt" 'val a = GitAuth.Https("x", "y")'
mutate "Cloud Account clones with the engine" "ac_cloud-account/app/src/main/java/com/diegonmarcos/cloudaccount/Own.kt" 'GitEngine.clone(ctx, u, d)'
mutate "a new app uses JGit directly" "ac_cloud-notes/app/src/main/java/x/Own.kt" 'import org.eclipse.jgit.api.Git'
mutate "account-ui names the token user" "ab_cloud-libs-shared/libs/account-ui/src/main/java/x/Own.kt" 'val u = "x-access-token"'
[ "$REAL" -eq 0 ] && [ "$M" -eq 0 ] || { echo "FAIL"; exit 1; }
echo "ok: one GitHub connect; each guard proven red"
