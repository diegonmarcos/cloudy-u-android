#!/usr/bin/env bash
# #1668 ONE clock: the SuperApp (the Home bar stopwatch, #1646) draws libs:clock-tools, the fleet's only clock, BY REFERENCE. Runs the lib's own check over all
# three carriers (Cloud Calc, Cloud Tasks, the SuperApp) and its mutations:
#   K1 the lib is whole (core rules with their PIT gate, the Android half, classified gui, receiver not exported)
#   K2 every carrier maps the lib by reference, compiles it and declares notifications + exact_alarm
#   K3 no clock logic outside the lib (no copy of its files, no alarm clock, alarm sound or stopwatch state of its own)
#   K4 Cloud Tasks declares and draws the Clock sub-page; K5 the SuperApp's stopwatch is the lib's; K6 ownership stated
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
LIBTEST="$ROOT/ab_cloud-libs-shared/libs/clock-tools/test/clock-one-lib.sh"
[ -f "$LIBTEST" ] || { echo "ERROR missing $LIBTEST — this tester is unrun, not passing"; exit 1; }
bash "$LIBTEST" "$ROOT"
