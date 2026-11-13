#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════╗
# ║ lib-classes.test — prove the lib classification guard FAILS on each   ║
# ║ rule it holds, and passes on the real tree                            ║
# ╚══════════════════════════════════════════════════════════════════════╝
#
# #763. Copies what the guard reads (every git-tracked gradle script, patch and
# JSON — the closure's inputs — plus the shared libs' sources and layouts),
# requires a pass, then breaks one rule at a time on the copy, proves the
# break landed, and requires a FAIL naming the rule.
#
# python3, git and coreutils only; no network, no build.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
GUARD="$ROOT/1_cicd/src/scripts/cloud-android-lib-classes.py"
DATA=1_cicd/src/data/lib-classes.json
WRITER_BJ=ac_cloud-keyboard/build.json
for f in "$GUARD" "$ROOT/$DATA" "$ROOT/$WRITER_BJ"; do
    [ -f "$f" ] || { echo "ERROR missing source: $f — this test is unrun, not passing"; exit 1; }
done
export PYTHONDONTWRITEBYTECODE=1
# The L4 expectations are written against the baseline the data file holds
# TODAY, never a number: the baseline moves with every engine migration and
# every honest "+N" the owner records, and a literal here went stale the
# first time it did (27 -> 29 on 2026-10-06 left this test red on main).
BASELINE="$(python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['backlog_baseline'])" "$ROOT/$DATA")"

FAILURES=0
ok()   { printf 'ok     %s\n' "$1"; }
fail() { printf 'FAIL   %s\n' "$1"; FAILURES=$((FAILURES + 1)); }
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

stage() {
    rm -rf "$WORK/t"; mkdir -p "$WORK/t"
    (cd "$ROOT" && { git ls-files -z -- '*.gradle' '*.gradle.kts' '*.patch' '*.json' \
        'ab_cloud-libs-shared/libs/*.kt' 'ab_cloud-libs-shared/libs/*.java' 'ab_cloud-libs-shared/libs/*/res/layout*' \
        | grep -zvxF -- "$DATA"; printf '%s\0' "$DATA"; } | xargs -0 cp --parents -t "$WORK/t")
}

# mutate <label> <file> <python-edit-of-s> <expected-substring>
mutate() {
    stage
    local before after out rc
    before="$(cat "$WORK/t/$2")"
    python3 -c "import re,json,sys; p=sys.argv[1]; s=open(p).read(); $3; open(p,'w').write(s)" "$WORK/t/$2"
    after="$(cat "$WORK/t/$2")"
    if [ "$before" = "$after" ]; then fail "$1: the mutation did not land — the fixture moved"; return; fi
    out="$(python3 "$GUARD" "$WORK/t")"; rc=$?
    if [ "$rc" -eq 1 ] && grep -qF -- "$4" <<<"$out"; then ok "$1 goes red ($4)"
    else fail "$1 stayed green or named the wrong thing (rc=$rc)"; printf '%s\n' "$out" | tail -4; fi
}
J() { printf "d=json.loads(s); %s; s=json.dumps(d)" "$1"; }

stage
out="$(python3 "$GUARD" "$WORK/t")"; rc=$?
if [ "$rc" -eq 0 ]; then ok "unbroken copy passes ($(tail -1 <<<"$out"))"
else fail "unbroken copy is red (rc=$rc)"; printf '%s\n' "$out" | grep FAIL; fi

mutate "a lib loses its class"                  "$DATA" "$(J "del d['libs']['webserver']")" \
    "L1 webserver: a shared lib with no class"
mutate "a class names a lib that is not there"  "$DATA" "$(J "d['libs']['gone']={'class':'gui','why':'x'}")" \
    "L1 gone: classified in"
mutate "a lib 13 apps compile is called an engine" "$DATA" "$(J "d['libs']['updater']['class']='engine'")" \
    "L2 updater: an engine compiled into"
mutate "a View library is called a contract"    "$DATA" "$(J "d['libs']['bottomnav'].update({'class':'contract','budget_lines':9999})")" \
    "L2 bottomnav: classified contract but renders UI"
mutate "logic is called gui to leave the backlog" "$DATA" "$(J "d['libs']['webserver']['class']='gui'")" \
    "L2 webserver: classified gui but renders nothing"
mutate "the mesh outgrows its budget"           "$DATA" "$(J "d['libs']['devtools']['budget_lines']=10")" \
    "L3 devtools:"
mutate "an app newly compiles in a logic lib"   "$WRITER_BJ" \
    "$(J "d['modules']['libs:webserver']={'dir':'../ab_cloud-libs-shared/libs/webserver'}")" \
    "L4 backlog $((BASELINE + 1)) > baseline $BASELINE"
mutate "a migration lands without lowering the baseline" "$DATA" "$(J "d['backlog_baseline']+=1")" \
    "L4 backlog $BASELINE < baseline $((BASELINE + 1))"

echo
[ "$FAILURES" -eq 0 ] && echo "lib-classes.test: OK" || { echo "lib-classes.test: $FAILURES FAILED"; exit 1; }
