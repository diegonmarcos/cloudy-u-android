#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ the published-nothing guard must exist on EVERY ship workflow,   ║
# ║ and it must be able to go red                                    ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# WHY THIS EXISTS.
# The guard lived on ship-cloud-superapp ALONE for four days, so 27 ship
# workflows could report success having published nothing — the failure the
# owner pays for most often, because his only signal that work reached his phone
# is a green run plus the application behaving differently. When the run is
# green and nothing changed he reports the FEATURE as broken and the next agent
# hunts a phantom bug in Kotlin (#189/#211/#244, #176, #45).
#
# This repository has also shipped four guards that could never fire: an
# assertion comparing an expression to itself, a pipeline status read through
# `tail`, `case $OUT in *"[NEW]"*)` matching one character, and four testers
# passing because ripgrep was absent. A guard nobody has watched go red is
# decoration, so this asserts BOTH polarities of the engine and the presence of
# the wiring in every generated workflow.
#
# It reads .github/workflows — the files GitHub actually runs — not the sources,
# because the whole point is that the guard is live, and a source that never
# reached the generated copy is exactly the drift 'generated files are up to
# date' exists to catch and this test must not duplicate.
set -euo pipefail
cd "$(dirname "$0")/../../../.."

GUARD=1_cicd/src/scripts/cloud-android-publish-guard.sh
pass=0; fail=0
ok()  { pass=$((pass+1)); }
bad() { fail=$((fail+1)); echo "  FAIL: $*"; }

# ── 1. the engine, every polarity ─────────────────────────────────────────
#   args: <app> <outcome> <gate-skip> <selected> <event>
# `sh "$GUARD" ...` read directly and never through a pipe: this repository has
# read a pipeline's exit status through `tail` and shipped the false green.
_rc() { sh "$GUARD" "$@" >/dev/null 2>&1; }

_rc app success false true  push  && ok || bad "a successful publish must be green"
_rc app cancelled false true push && ok || bad "cancelled is neither a pass nor a fail — it must not fail the run"

# The gate's own answer: this application's source did not move, so the release
# asset was deliberately left alone. Green, or every unrelated push turns red
# and the guard is disabled within a day.
_rc app skipped true false push && ok || bad "a publish the GATE skipped is correct and must stay green"
_rc app skipped true true  push && ok || bad "gate-skipped must be green even if the condition still reads true"

# A matrix leg that never feeds the release (ship-cloud-ide's x86_64).
_rc app skipped false false push && ok || bad "a leg the workflow deselects on a push publishes through GHCR and must stay green"

# THE 2026-09-05 DEADLOCK. The source moved and a manual run turned publishing
# off, so an APK was built that nobody can install.
_rc app skipped false false workflow_dispatch \
  && bad "a dispatch that turned publishing off while the source had moved must be RED" || ok

# #1783: a leg that never feeds the release is deselected by construction, on a dispatch too.
_rc app skipped false false workflow_dispatch false && ok || bad "a dispatch must not redden a leg that never feeds the release (feeds=false)"
_rc app skipped false false workflow_dispatch true \
  && bad "a leg that feeds the release and was deselected by the dispatch input must stay RED (feeds=true)" || ok
_rc app skipped false false workflow_dispatch \
  && bad "the default of the feeds argument must stay strict (the 2026-09-05 deadlock)" || ok
_rc app skipped false true workflow_dispatch false \
  && bad "feeds=false must not excuse a SELECTED leg whose publish was skipped" || ok
_rc app failure false true push false \
  && bad "feeds=false must not excuse a FAILED publish" || ok
_rc app skipped false false workflow_dispatch maybe \
  && bad "an unreadable feeds value must be refused, not read as 'does not feed'" || ok

# Selected, and it did not happen.
_rc app skipped false true push && bad "a SKIPPED publish step with the source changed must be RED" || ok
_rc app failure false true push && bad "a FAILED publish step must be RED" || ok

# Fails closed in both directions it can be blinded.
_rc app ""      false true  push && bad "an empty outcome means no step carries \`id: publish\` — refuse" || ok
_rc app skipped false ""    push && bad "an unrenderable publish condition must be refused, not guessed" || ok
_rc app skipped false maybe push && bad "a condition that is neither true nor false must be refused" || ok
_rc ""  success false true  push && bad "a missing app name must be refused, not defaulted" || ok

# The reason has to reach the run's front page, not only the step log.
SUM="$(mktemp)"
GITHUB_STEP_SUMMARY="$SUM" sh "$GUARD" app skipped false true push >/dev/null 2>&1 || true
grep -q 'PUBLISHED-NOTHING \[app\]' "$SUM" \
  && ok || bad "the failure must be written to \$GITHUB_STEP_SUMMARY"
rm -f "$SUM"

# ── 2. the wiring, on every ship workflow GitHub runs ─────────────────────
# A ship workflow that gates a publish owes a guard on it, and the guard must be
# handed that publish step's OWN condition — one copy in the file, so the answer
# to "was this leg selected?" cannot drift away from the step it describes.
for wf in .github/workflows/ship-*.yml; do
    name="$(basename "$wf")"
    gated=$(grep -c 'cloud-android-publish-gate.sh check' "$wf" || true)
    marks=$(grep -c '^        id: publish$' "$wf" || true)
    # NOT `grep -c cloud-android-publish-guard.sh`: the block's own comment
    # names the script too, so that counted the prose beside the call and
    # reported two guards per guard. Count the INVOCATION.
    guards=$(grep -c '^        run: sh .*cloud-android-publish-guard\.sh ' "$wf" || true)

    if [ "$gated" -gt 0 ] && [ "$marks" -eq 0 ]; then
        bad "$name gates a publish but no step carries \`id: publish\`"
        continue
    fi
    [ "$marks" -eq 0 ] && continue

    if [ "$guards" -ne "$marks" ]; then
        bad "$name has $marks \`id: publish\` step(s) but $guards guard(s)"
        continue
    fi
    ok

    # The guard must NOT be conditioned on the publish step's `if:`. That is the
    # generalisation that looks right and cannot fire: a publish skipped by its
    # own condition would skip the guard too, and that skip is the entire bug.
    if grep -q "^        if: \${{ !cancelled() && (" "$wf"; then
        bad "$name: the guard is gated on the publish condition, so it cannot fire on a skipped publish"
        continue
    fi
    ok

    python3 - "$wf" <<'PY' || bad "$name: the guard is not handed its publish step's \`if:\` verbatim"
import sys
lines = open(sys.argv[1]).read().split("\n")
want = []
for i, l in enumerate(lines):
    if l != "        id: publish":
        continue
    s = i
    while not lines[s].startswith("      - "):
        s -= 1
    e = s + 1
    while e < len(lines) and (lines[e].strip() == "" or lines[e].startswith("        ")):
        e += 1
    cond = "true"
    for j in range(s + 1, e):
        if lines[j].startswith("        if:"):
            v = lines[j].split("if:", 1)[1].strip()
            if v.startswith("${{") and v.endswith("}}"):
                v = v[3:-2].strip()
            cond = v
            break
    want.append(cond)
got = []
for l in lines:
    if l.startswith("        run: sh ") and "cloud-android-publish-guard.sh " in l:
        parts = l.split('"')
        # ... "<app>" "<outcome>" "<gate-skip>" "<selected>" "<event>"
        got.append(parts[7].replace("${{", "").replace("}}", "").strip())
sys.exit(0 if got == want and all(g for g in got) else 1)
PY
    ok
done

# ── 2b. no status function may reach the guard's arguments ───────────────
# `success()` and friends are legal ONLY inside an `if:`. Handed to the guard as
# an argument they make GitHub refuse the whole workflow — which it reports by
# listing the run under the FILENAME instead of the workflow name, so six ship
# workflows can stop existing and the run list still looks populated. That
# happened on 2026-09-10. The generator refuses at build time; this asserts it
# on the files GitHub actually reads.
for wf in .github/workflows/ship-*.yml; do
    grep -q '^        run: sh .*cloud-android-publish-guard\.sh ' "$wf" || continue
    if grep -qE '\b(success|failure|cancelled|always)\(\)' \
         <<<"$(grep '^        run: sh .*cloud-android-publish-guard\.sh ' "$wf")"; then
        bad "$(basename "$wf"): a status function is passed to the guard as an argument — GitHub will not parse this workflow"
    else
        ok
    fi
done

# ── 3. the guard is REACHED — it is not sitting after a step that exits ────
# A guard placed before its publish step would read an outcome that does not
# exist yet and pass on an empty string it is meant to refuse.
for wf in .github/workflows/ship-*.yml; do
    grep -q '^        id: publish$' "$wf" || continue
    p=$(grep -n '^        id: publish$' "$wf" | head -1 | cut -d: -f1)
    g=$(grep -n '^        run: sh .*cloud-android-publish-guard\.sh ' "$wf" | head -1 | cut -d: -f1)
    [ "$g" -gt "$p" ] && ok || bad "$(basename "$wf"): the guard runs BEFORE the publish step it reads"
done

echo "── publish guard: $pass passed, $fail failed ──"
[ "$fail" -eq 0 ]
