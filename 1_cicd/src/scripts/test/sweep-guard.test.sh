#!/usr/bin/env bash
# sweep-guard.test — prove the anti-sweep guard goes red on every shape it
# claims to catch, stays green on honest commits, and that every rule in the
# guard is killed by a planted mutation of the guard itself. Works in throwaway
# repos, never this tree.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
GUARD_SRC="$ROOT/1_cicd/src/scripts/cloud-android-sweep-guard.py"
FAILURES=0
ok()   { printf 'ok     %s\n' "$1"; }
fail() { printf 'FAIL   %s\n' "$1"; FAILURES=$((FAILURES + 1)); }
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

lines() { # lines <prefix> <from> <to>
  awk -v p="$1" -v a="$2" -v b="$3" 'BEGIN{for(i=a;i<=b;i++) print p " line " i}'
}

# build_repo <dir>: a base with three owners.
#   #1101 owns a.txt (300 lines)   #1102 owns b.txt (300 lines)   no-tag "Restore" owns nothing alone
#   gen/favourites_catalog.json is owned by #1101 (an exempt, regenerated artefact)
build_repo() {
  local g="$1"; rm -rf "$g"; git init -q -b main "$g"
  git -C "$g" config user.name t; git -C "$g" config user.email t@t
  mkdir -p "$g/gen"
  lines A 1 300 > "$g/a.txt"; lines B 1 300 > "$g/b.txt"
  git -C "$g" add -A; git -C "$g" commit -q -m "#1101 add a and b"
  lines C 1 300 > "$g/c.txt"; git -C "$g" add -A; git -C "$g" commit -q -m "#1102 add c"
  lines F 1 300 > "$g/gen/favourites_catalog.json"; git -C "$g" add -A; git -C "$g" commit -q -m "#1101 add the catalogue"
}

# case <guard> <expected exit> <label> <setup commands run in the repo, last commit is judged>
run_case() {
  local guard="$1" want="$2" label="$3" setup="$4" g="$WORK/repo"
  build_repo "$g"
  base=$(git -C "$g" rev-parse HEAD)
  ( cd "$g" && eval "$setup" ) >/dev/null 2>&1
  ( cd "$g" && python3 "$guard" --range "$base..HEAD" >"$WORK/out" 2>&1 ); rc=$?
  if [ "$rc" = "$want" ]; then return 0; fi
  echo "  case '$label': expected exit $want, got $rc" >> "$WORK/failed"
  return 1
}

CM='git commit -q -a -m'
cases() { # cases <guard> -> prints nothing, appends to $WORK/failed
  local G="$1"
  # --- must FAIL (exit 1)
  run_case "$G" 1 "sweep: another ticket's file wiped, 250 lines" \
    "sed -i '1,250d' a.txt; $CM '#1102 unrelated tidy'"
  run_case "$G" 1 "sweep: exactly 200 lines is over the line" \
    "sed -i '1,200d' a.txt; $CM '#1102 unrelated tidy'"
  run_case "$G" 1 "sweep: spread over two foreign files, 100 + 100" \
    "sed -i '1,100d' a.txt; sed -i '1,100d' c.txt; git add -A; $CM '#1109 tidy'"
  run_case "$G" 1 "sweep: a deleted file counts" \
    "git rm -q b.txt; $CM '#1102 drop b'"
  run_case "$G" 1 "sweep: an untagged commit has no own ticket" \
    "sed -i '1,250d' a.txt; $CM 'tidy up'"
  run_case "$G" 1 "sweep: the tag must be in the SUBJECT, a body mention does not count" \
    "sed -i '1,250d' a.txt; git commit -q -a -m '#1102 tidy' -m 'also touches #1101'"
  run_case "$G" 1 "sweep: a revert-of marker naming no commit is no marker" \
    "sed -i '1,250d' a.txt; git commit -q -a -m '#1102 tidy' -m 'revert-of:deadbeefdeadbeef'"
  run_case "$G" 1 "sweep: ownership looks through an untagged restore commit" \
    "git checkout -q HEAD~3 -- a.txt 2>/dev/null; sed -i '1,5d' a.txt; $CM 'Restore a'; sed -i '1,250d' a.txt; $CM '#1102 tidy'"
  run_case "$G" 1 "sweep: buried under a clean tip, every commit of the range is judged" \
    "sed -i '1,250d' a.txt; $CM '#1102 tidy'; echo x >> b.txt; $CM '#1102 note'"
  # --- must PASS (exit 0)
  run_case "$G" 0 "own file: the owning ticket may delete its own lines" \
    "sed -i '1,250d' a.txt; $CM '#1101 shrink a'"
  run_case "$G" 0 "shared tag: a subject naming both tickets may edit both" \
    "sed -i '1,250d' a.txt; $CM '#1102 #1101 rework a'"
  run_case "$G" 0 "199 lines is under the limit" \
    "sed -i '1,199d' a.txt; $CM '#1102 trim'"
  run_case "$G" 0 "100 foreign lines in two files is under the limit" \
    "sed -i '1,50d' a.txt; sed -i '1,50d' c.txt; git add -A; $CM '#1109 tidy'"
  run_case "$G" 0 "explicit revert-of marker naming a real commit" \
    "sed -i '1,250d' a.txt; git commit -q -a -m '#1102 back out a' -m \"revert-of:\$(git rev-parse HEAD~2)\""
  run_case "$G" 0 "a regenerated catalogue is exempt" \
    "sed -i '1,250d' gen/favourites_catalog.json; $CM '#1102 regenerate'"
  run_case "$G" 0 "additions are never a sweep" \
    "lines Z 1 500 >> a.txt; $CM '#1102 append'"
  run_case "$G" 0 "a pure rename is not a deletion" \
    "git mv a.txt a2.txt; $CM '#1102 rename a'"
}

: > "$WORK/failed"
cases "$GUARD_SRC"
if [ -s "$WORK/failed" ]; then fail "the guard misbehaves on a stated case"; cat "$WORK/failed"; else ok "every stated case behaves (9 red shapes, 8 green shapes)"; fi

# ---- the guard must also name the owner it blames
build_repo "$WORK/repo"; base=$(git -C "$WORK/repo" rev-parse HEAD)
( cd "$WORK/repo" && sed -i '1,250d' a.txt && git commit -q -a -m '#1102 tidy' && python3 "$GUARD_SRC" --range "$base..HEAD" >"$WORK/out" 2>&1 )
grep -q 'owner #1101 ' "$WORK/out" && grep -q 'a.txt' "$WORK/out" && ok "the report names the owning ticket and the path" || fail "the report does not name owner and path"

# ---- mutations of the guard itself: each must turn at least one case red
mutate() { # mutate <label> <python-re-sub-old> <new>
  local label="$1" old="$2" new="$3" m="$WORK/mut.py"
  python3 - "$GUARD_SRC" "$m" "$old" "$new" <<'PY'
import sys
src=open(sys.argv[1]).read(); old,new=sys.argv[3],sys.argv[4]
if old not in src: sys.exit(3)
open(sys.argv[2],'w').write(src.replace(old,new,1))
PY
  [ $? = 0 ] || { fail "mutation '$label' does not apply (the guard source changed: update the test)"; return; }
  : > "$WORK/failed"; cases "$WORK/mut.py"
  [ -s "$WORK/failed" ] && ok "mutation '$label' is caught" || fail "mutation '$label' survives: no case turned red"
}
mutate "threshold off by one (> instead of >=)"  "if foreign >= threshold"        "if foreign > threshold"
mutate "threshold doubled"                        "THRESHOLD = 200"                 "THRESHOLD = 400"
mutate "marker ignored"                           "if revert_marker_ok(message):"   "if False:"
mutate "marker accepted without a real commit"    "capture_output=True).returncode == 0:" "capture_output=True).returncode >= 0:"
mutate "own ticket never compared"                "if not otags or (own & otags):"  "if not otags:"
mutate "own ticket always matches"                "if not otags or (own & otags):"  "if True:"
mutate "regenerated artefacts not exempt"         "return any(fnmatch.fnmatch(path, g) for g in EXEMPT)" "return False"
mutate "everything exempt"                        "return any(fnmatch.fnmatch(path, g) for g in EXEMPT)" "return True"
mutate "untagged owner treated as an owner"       $'        if t:\n            return sha, t'  $'        if True:\n            return sha, t'
mutate "no look-through of untagged commits"      $'        if t:\n            return sha, t\n'  $'        return sha, t\n'
mutate "subject read from the whole message"      "own = tags_of(subject)"          "own = tags_of(message)"
mutate "renames counted as deletions"             '"show", "-M",'                   '"show", "--no-renames",'
mutate "deleted lines not summed across files"    "foreign += removed"              "foreign = max(foreign, removed)"
mutate "only the tip commit judged"               'git("rev-list", "--reverse", rng).split()' 'git("rev-list", "--reverse", rng).split()[-1:]'

[ "$FAILURES" = 0 ] && echo "sweep-guard.test: all cases pass" || { echo "sweep-guard.test: $FAILURES failure(s)"; exit 1; }
