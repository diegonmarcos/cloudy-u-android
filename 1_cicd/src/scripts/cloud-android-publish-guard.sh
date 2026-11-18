#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════╗
# ║ cloud-android-publish-guard — a green run that shipped nothing   ║
# ║ is the worst possible outcome, so it must not be green           ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# The owner's only signal that work reached his phone is a green run followed by
# the application behaving differently. When a run goes green having published
# nothing he installs, sees no change, and reports the FEATURE as broken — and
# the next agent hunts a phantom bug in Kotlin for hours. That has happened
# repeatedly: a sidebar "fixed" three times over (#189, #211, #244); a
# containers push green with Build and Deploy skipped (#176); a ship green with
# the post-hook never run (#45); a stale workspace whose CHANGED_DIRS mismatch
# deployed nothing, green.
#
# This guard existed for ONE application (ship-cloud-superapp, written after the
# 2026-09-05 update deadlock) and was never generalised, so the other 27 ship
# workflows could each report success having published nothing. It is now the
# single implementation all of them call — two copies of one idea is how #228
# and #209 happened, and it is not being repeated here.
#
#   cloud-android-publish-guard.sh <app> <outcome> <gate-skip> <selected> <event> [feeds]
#
#     outcome    steps.publish.outcome — what the publish step actually did
#     gate-skip  steps.gate.outputs.skip — the PUBLISH GATE's own answer to
#                "did this application's source move?". Read from the gate, not
#                re-derived: a second answer to that question would drift from
#                the first, and the first is what decided whether to build.
#     selected   the publish step's own `if:` expression, rendered by GitHub to
#                true/false. The workflow generator writes it here verbatim from
#                that step, so there is exactly one copy of it in the file.
#     event      github.event_name
#     feeds      OPTIONAL (default true). Does THIS matrix leg feed the release at all?
#                The generator renders it from the ABI term of the publish condition
#                (`matrix.abi == 'arm64-v8a'`) and only for a workflow that has one. A leg that
#                never feeds the release is deselected by construction, on a dispatch as on a
#                push, so the dispatch-deadlock branch below must not fire on it (#1783): the
#                x86_64 leg of ship-cloud-myterminal went red on a dispatch whose arm64 leg
#                published. A leg that DOES feed the release keeps the strict behaviour.
#
# WHY `selected` IS DATA HERE AND NOT A CONDITION ON THIS STEP.
# The obvious generalisation — put the publish step's `if:` on the guard too —
# is wrong, and wrong in the way that matters: the guard then cannot run in
# precisely the case it was written for, a publish step SKIPPED BY ITS OWN
# CONDITION. It would be a guard that is structurally unable to fire on its
# motivating bug, which is the fourth entry in this repository's list of guards
# that could never fire. So the step runs on everything but a cancellation, and
# the condition arrives as a fact to judge rather than as permission to look.
#
# WHAT IS NOT A FAILURE, AND WHY EACH ONE MATTERS
#   * THE PUBLISH GATE SKIPPED THIS APPLICATION because its own source did not
#     change. That is the gate working. A guard that reddened every unrelated
#     push would be turned off within a day.
#   * A MATRIX LEG THAT NEVER FEEDS THE RELEASE, e.g. ship-cloud-ide's x86_64
#     leg, which distributes through GHCR `latest-x86_64` and would fail
#     gh-release outright because no unsuffixed apk is in dist/. Deselected on a
#     push and correct — reported, not failed.
#   * CANCELLED. `cancel-in-progress` is on for pull requests only (main runs finish; agents push
#     concurrently and the pending run is replaced), so a cancellation is neither a pass nor a
#     fail.
#   * A FOREIGN TESTER DOWNGRADE. Verified, not inherited: a downgraded tester
#     leaves cloud-android-test-engine.sh exiting 0, the publish step's `if:` is
#     satisfied and the publish RUNS. Downgrading is precisely what lets the
#     publish happen, so "published nothing because a foreign gate was
#     downgraded" is unreachable and this guard cannot fire on it.
#
# WHAT IS A FAILURE
#   * selected, and the publish did not succeed. The gate said these bytes are
#     new and nothing carries them.
#   * DESELECTED BY A workflow_dispatch INPUT. This is the 2026-09-05 deadlock
#     itself: a dispatch with create_release=false built an APK, uploaded it as
#     a workflow artifact nobody installs, cancelled the push run that would
#     have published, and exited green. Deliberate or not, the run shipped
#     nothing while the source had moved, and it must say so in the only
#     language a pipeline has.
#
# FAILS CLOSED. An empty outcome means no step in this job carries `id: publish`
# (or it was misspelt) and the guard is asserting about a step that does not
# exist; an unrenderable `selected` means the same about the condition. Both
# refuse rather than pass.
set -eu

APP="${1:-}"
OUTCOME="${2:-}"
GATE_SKIP="${3:-}"
SELECTED="${4:-}"
EVENT="${5:-}"
FEEDS="${6:-true}"

[ -n "$APP" ] || {
    echo "usage: $(basename "$0") <app> <outcome> <gate-skip> <selected> <event> [feeds]" >&2
    exit 2; }

# Written to the run's front page as well as the step log. 400 lines into a step
# is where a fact goes to be missed, and this one is the fact the owner needs.
_summary() { [ -z "${GITHUB_STEP_SUMMARY:-}" ] || printf '%s\n' "$*" >>"$GITHUB_STEP_SUMMARY"; }

_red() {  # _red <title> <detail> <summary-line>
    echo "::error title=$1::[$APP] $2"
    _summary "$3"
    exit 1
}

# ── the subject must exist before anything is said about it ───────────────
[ -n "$OUTCOME" ] || _red "Publish guard has no subject" \
  "steps.publish.outcome came back EMPTY — no step in this job carries \`id: publish\`, or the id is misspelt. This guard would be asserting about a step that does not exist, which is a guard that can never fire." \
  "PUBLISH-GUARD-BROKEN [$APP] — no step carries \`id: publish\`; the guard has no subject."

case "$FEEDS" in
    true|false) ;;
    *) _red "Publish guard cannot tell whether this leg feeds the release" \
         "the feeds argument rendered as '$FEEDS', which is neither true nor false. Treating an unreadable value as 'does not feed' would switch the dispatch-deadlock check off, so the guard stops instead." \
         "PUBLISH-GUARD-BROKEN [$APP] — the feeds argument rendered as '$FEEDS'." ;;
esac

case "$SELECTED" in
    true|false) ;;
    *) _red "Publish guard cannot tell whether this leg was selected" \
         "the publish step's condition rendered as '$SELECTED', which is neither true nor false. Without it this guard cannot tell 'correctly not selected' from 'selected and published nothing', and guessing either way is worse than stopping." \
         "PUBLISH-GUARD-BROKEN [$APP] — the publish condition rendered as '$SELECTED'." ;;
esac

if [ "$OUTCOME" = "success" ]; then
    echo "publish [$APP]: ok — the release carries this run's asset"
    exit 0
fi

if [ "$OUTCOME" = "cancelled" ]; then
    # Not a verdict in either direction. Said out loud so nobody reads the
    # absence of a red mark as proof the APK shipped.
    echo "::warning title=Publish was cancelled::[$APP] the publish step was cancelled, so this run neither published nor proved it could. What is on the release is the PREVIOUS build."
    _summary "PUBLISH-CANCELLED [$APP] — nothing published, and cancellation is not a failure. Push again."
    exit 0
fi

if [ "$GATE_SKIP" = "true" ]; then
    echo "publish [$APP]: correctly not selected — the publish gate found this application's source unchanged, so the release asset was deliberately left untouched and no phone sees an update."
    exit 0
fi

if [ "$SELECTED" = "false" ] && [ "$EVENT" = "workflow_dispatch" ] && [ "$FEEDS" = "true" ]; then
    _red "Dispatched with publishing turned off" \
      "the publish gate found this application's source CHANGED, and this manual run then turned publishing off, so it built an APK nobody can install and cancelled nothing into place. This is the 2026-09-05 deadlock exactly. Re-dispatch with the publish input on, or push." \
      "PUBLISHED-NOTHING [$APP] — dispatched with publishing turned off while the source had moved."
fi

if [ "$SELECTED" = "false" ]; then
    # A leg the workflow deliberately does not publish from — an ABI variant
    # that distributes through GHCR rather than the release. Named on the front
    # page anyway: "this leg ships nothing" is a fact, not an absence.
    echo "publish [$APP]: this leg is not the one that feeds the release (the publish step's own condition deselected it); its artifact goes out through GHCR."
    _summary "PUBLISH-NOT-THIS-LEG [$APP] — this matrix leg does not attach to the release by construction."
    exit 0
fi

_red "Nothing was published" \
  "this application WAS selected for publishing — the publish gate found its source changed — and the publish step did not succeed (outcome=$OUTCOME). Devices are still being served the PREVIOUS asset, so this run built something nobody can install." \
  "PUBLISHED-NOTHING [$APP] — publish step outcome=$OUTCOME. The phone still has the previous build."
