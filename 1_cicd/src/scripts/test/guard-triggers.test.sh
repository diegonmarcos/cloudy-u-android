#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════╗
# ║ guard-triggers.test — prove the #763 trigger gates FAIL when they must ║
# ╚══════════════════════════════════════════════════════════════════════╝
#
# Three gates, each watched going red on a fixture before it is trusted green:
#   inject    refuses an unclassified guard, a double classification, a name
#             with no workflow, and a filter stacked on a hand-written paths:;
#             writes the declared paths plus the workflow's own source, and is
#             idempotent.
#   coverage  a guard that reads a file its paths do not match is a GAP; a guard
#             step that fails is a FAIL; a correct declaration is COVERED.
#   fanout    GitHub's push-filter semantics (* stops at /, ** does not, the
#             last matching pattern decides, ! excludes, tags-only never fires
#             for a branch push), on which every before/after number rests.
#
# python3 (+PyYAML), strace, coreutils; no network, no build.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
GT="$ROOT/1_cicd/src/scripts/cloud-android-guard-triggers.py"
FO="$ROOT/1_cicd/src/scripts/cloud_android_ci_fanout.py"
for f in "$GT" "$FO"; do
    [ -f "$f" ] || { echo "ERROR missing source: $f — this test is unrun, not passing"; exit 1; }
done
command -v strace >/dev/null || { echo "ERROR strace not installed — the coverage half is unrun, not passing"; exit 1; }
python3 -c 'import yaml' 2>/dev/null || { echo "ERROR PyYAML missing — this test is unrun, not passing"; exit 1; }
export PYTHONDONTWRITEBYTECODE=1

FAILURES=0
ok()   { printf 'ok     %s\n' "$1"; }
fail() { printf 'FAIL   %s\n' "$1"; FAILURES=$((FAILURES + 1)); }
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
T="$WORK/t"

# fixture <guard run line> <filtered json for guard-a, or empty to leave it unclassified>
fixture() {
    rm -rf "$T"; mkdir -p "$T/1_cicd/src/cicd" "$T/1_cicd/src/data" "$T/data" "$T/other"
    echo payload > "$T/data/in.txt"; echo other > "$T/other/x.txt"
    cat > "$T/1_cicd/src/cicd/guard-a.yml" <<EOF
name: a
on:
  push:
    branches: [main]
  workflow_dispatch:
jobs:
  a:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: read
        run: $1
EOF
    cat > "$T/1_cicd/src/cicd/guard-b.yml" <<'EOF'
name: b
on:
  push:
    branches: [main]
    paths:
      - "other/**"
jobs: {}
EOF
    printf 'name: c\non:\n  workflow_dispatch:\njobs: {}\n' > "$T/1_cicd/src/cicd/guard-c.yml"
    if [ -n "$2" ]; then
        printf '{"_doc":"x","unfiltered":{},"filtered":{"guard-a.yml":%s}}\n' "$2" > "$T/1_cicd/src/data/guard-triggers.json"
    else
        printf '{"_doc":"x","unfiltered":{},"filtered":{}}\n' > "$T/1_cicd/src/data/guard-triggers.json"
    fi
}

# expect <label> <want rc> <want substring> <cmd...>
expect() {
    local label="$1" want="$2" sub="$3"; shift 3
    local out rc
    out="$("$@" 2>&1)"; rc=$?
    if [ "$rc" -eq "$want" ] && grep -qF -- "$sub" <<<"$out"; then ok "$label"
    else fail "$label (rc=$rc, wanted $want and '$sub')"; printf '%s\n' "$out" | tail -6; fi
}

# ── inject ────────────────────────────────────────────────────────────────
fixture "cat data/in.txt" ''
expect "inject refuses a push guard named in neither list" 1 "guard-a.yml: runs on every push and is not in" python3 "$GT" inject "$T"

fixture "cat data/in.txt" '{"paths":["data/**"]}'
expect "inject writes a filtered guard" 0 "guard triggers: guard-a.yml" python3 "$GT" inject "$T"
if grep -qF -- '      - "data/**"' "$T/1_cicd/src/cicd/guard-a.yml" \
   && grep -qF -- '      - "1_cicd/src/cicd/guard-a.yml"' "$T/1_cicd/src/cicd/guard-a.yml"; then
    ok "the injected paths are the declared ones plus the workflow's own source"
else fail "the injected block lacks the declared path or the workflow's own source"; cat "$T/1_cicd/src/cicd/guard-a.yml"; fi
hit_data="$(python3 "$FO" --wf "$T/1_cicd/src/cicd" --names --files data/in.txt)"
hit_readme="$(python3 "$FO" --wf "$T/1_cicd/src/cicd" --names --files README.md)"
if grep -qx guard-a.yml <<<"$hit_data" && ! grep -qx guard-a.yml <<<"$hit_readme"; then
    ok "the injected filter is valid YAML GitHub-wise: data/ starts the guard, README.md does not"
else fail "the injected filter does not parse or does not select"; fi
before="$(cat "$T/1_cicd/src/cicd/guard-a.yml")"
python3 "$GT" inject "$T" >/dev/null 2>&1
[ "$before" = "$(cat "$T/1_cicd/src/cicd/guard-a.yml")" ] && ok "inject is idempotent" || fail "a second inject changed the file"

fixture "cat data/in.txt" '{"paths":["data/**"]}'
python3 - "$T/1_cicd/src/data/guard-triggers.json" <<'EOF'
import json,sys; p=sys.argv[1]; d=json.load(open(p)); d["unfiltered"]["guard-a.yml"]="x"; json.dump(d,open(p,"w"))
EOF
expect "inject refuses a guard named in both lists" 1 "named under both" python3 "$GT" inject "$T"

fixture "cat data/in.txt" '{"paths":["data/**"]}'
python3 - "$T/1_cicd/src/data/guard-triggers.json" <<'EOF'
import json,sys; p=sys.argv[1]; d=json.load(open(p)); d["unfiltered"]["gone.yml"]="x"; json.dump(d,open(p,"w"))
EOF
expect "inject refuses a declared workflow that does not exist" 1 "gone.yml: named in" python3 "$GT" inject "$T"

fixture "cat data/in.txt" '{"paths":["data/**"]}'
python3 - "$T/1_cicd/src/data/guard-triggers.json" <<'EOF'
import json,sys; p=sys.argv[1]; d=json.load(open(p)); d["filtered"]["guard-b.yml"]={"paths":["x/**"]}; json.dump(d,open(p,"w"))
EOF
expect "inject refuses a filter stacked on a hand-written paths:" 1 "guard-b.yml: declared \`filtered\` and also carries" python3 "$GT" inject "$T"

# ── coverage ──────────────────────────────────────────────────────────────
fixture "cat data/in.txt" '{"paths":["data/**"]}'
expect "coverage passes a declaration that covers every read" 0 "COVERED guard-a.yml" python3 "$GT" coverage "$T"

fixture "cat data/in.txt" '{"paths":["other/**"]}'
expect "coverage names the read a too-narrow declaration misses" 1 "read data/in.txt — no declared path matches it" python3 "$GT" coverage "$T"

fixture "cat data/in.txt > /dev/null; bash -c 'cat other/x.txt'" '{"paths":["data/**"]}'
expect "coverage sees a read made by a child process" 1 "read other/x.txt" python3 "$GT" coverage "$T"

# #796: a child that cd's and opens a RELATIVE path is resolved where the kernel
# resolved it (strace -y), not joined onto the repo root. Run 37121207754
# reported `read build.sh — no declared path matches it` for a tester that ran
# `cd ac_cloud-termux && bash ./build.sh`, a file inside its declared */build.sh.
# The same-named file at the repo root is what made the old join look like a
# real read: without it the misresolved path simply did not exist and the case
# passed vacuously.
fixture "cd data && cat in.txt" '{"paths":["data/**"]}'
printf 'decoy\n' > "$T/in.txt"
expect "coverage resolves a cwd-relative read where the child opened it" 0 "COVERED guard-a.yml" python3 "$GT" coverage "$T"

fixture "cat data/in.txt; exit 3" '{"paths":["data/**"]}'
expect "coverage refuses to vouch for a guard step that failed" 1 "exited 3" python3 "$GT" coverage "$T"

fixture "cat data/in.txt" '{"paths":["other/**"]}'
python3 - "$T/1_cicd/src/cicd/guard-a.yml" <<'EOF2'
import sys; p=sys.argv[1]; s=open(p).read(); s=s.replace("      - name: read\n", "      - uses: ./.github/actions/x\n      - name: read\n"); open(p,"w").write(s)
EOF2
expect "coverage says, rather than hides, a job it cannot replay" 0 "job a not traced: it depends on ./.github/actions/x" python3 "$GT" coverage "$T"

# ── fanout semantics ──────────────────────────────────────────────────────
mkdir -p "$WORK/wf"
wf() { printf 'on:\n  push:\n%s\njobs: {}\n' "$2" > "$WORK/wf/$1.yml"; }
wf all     '    branches: [main]'
wf star    '    branches: [main]
    paths: ["a/*.txt"]'
wf glob2   '    branches: [main]
    paths: ["a/**"]'
wf neg     '    branches: [main]
    paths: ["a/**", "!a/skip/**"]'
wf ignore  '    branches: [main]
    paths-ignore: ["docs/**"]'
wf tags    '    tags: ["v*"]'
wf other   '    branches: [dev]'
names() { python3 "$FO" --wf "$WORK/wf" --names --files "$@" | grep -v ' runs ('; }
got="$(names a/b/c.txt | tr '\n' ' ')"
[ "$got" = "all.yml glob2.yml ignore.yml neg.yml " ] \
    && ok "* stops at /, ** does not; tags-only and other-branch never fire" || fail "a/b/c.txt fired: $got"
got="$(names a/c.txt | tr '\n' ' ')"
[ "$got" = "all.yml glob2.yml ignore.yml neg.yml star.yml " ] && ok "a/*.txt matches a/c.txt" || fail "a/c.txt fired: $got"
got="$(names a/skip/x | tr '\n' ' ')"
[ "$got" = "all.yml glob2.yml ignore.yml " ] && ok "a later ! pattern excludes what an earlier one matched" || fail "a/skip/x fired: $got"
got="$(names docs/x.md | tr '\n' ' ')"
[ "$got" = "all.yml " ] && ok "paths-ignore skips a change made only of ignored files" || fail "docs/x.md fired: $got"

# GitHub's filter patterns: [] is a character set, and `**/` between separators also matches no directory. A declared path
# written with either used to match nothing here, so the coverage check reported reads that the workflow does fire on.
mkdir -p "$WORK/wf2"
wf2() { printf 'on:\n  push:\n    branches: [main]\n    paths: ["%s"]\njobs: {}\n' "$2" > "$WORK/wf2/$1.yml"; }
wf2 set  'a[acd]_*/**/*.kt'
wf2 deep 'x/**/b.txt'
names2() { python3 "$FO" --wf "$WORK/wf2" --names --files "$@" | grep -v ' runs (' | tr '\n' ' '; }
got="$(names2 ac_app/src/A.kt)";   [ "$got" = "set.yml " ]  && ok "a [] set matches one listed character" || fail "ac_app/src/A.kt fired: $got"
got="$(names2 ac_app/A.kt)";       [ "$got" = "set.yml " ]  && ok "**/ between separators matches no directory" || fail "ac_app/A.kt fired: $got"
got="$(names2 ab_app/src/A.kt)";   [ -z "${got// /}" ]      && ok "a [] set refuses a character it does not list" || fail "ab_app/src/A.kt fired: $got"
got="$(names2 x/b.txt)";           [ "$got" = "deep.yml " ] && ok "x/**/b.txt matches x/b.txt" || fail "x/b.txt fired: $got"
got="$(names2 x/p/q/b.txt)";       [ "$got" = "deep.yml " ] && ok "x/**/b.txt matches x/p/q/b.txt" || fail "x/p/q/b.txt fired: $got"
got="$(names2 xb.txt)";            [ -z "${got// /}" ]      && ok "x/**/b.txt keeps its separators" || fail "xb.txt fired: $got"

echo
if [ "$FAILURES" -eq 0 ]; then echo "all passed"; exit 0; fi
echo "FAIL — $FAILURES assertion(s)"; exit 1
