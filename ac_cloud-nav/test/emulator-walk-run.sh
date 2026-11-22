#!/usr/bin/env bash
# #1851 orchestration for the walk workflow (emulator-runner runs its script line by line, so the logic lives here):
# the injector (emulator-walk-geo.sh) in the background, TimelineWalkTest in the foreground, evidence into test-out/.
set -u
cd "$(dirname "$0")/.."
mkdir -p test-out
bash test/emulator-walk-geo.sh 20 > test-out/walk-inject.log 2>&1 &
inj=$!
rc=0
NAV_INSTRUMENT_ONLY=1 NAV_INSTRUMENT_ARGS="-Pandroid.testInstrumentationRunnerArguments.class=com.diegonmarcos.cloudnav.tracker.TimelineWalkTest -Pandroid.testInstrumentationRunnerArguments.walk=1" ./build.sh instrument || rc=$?
kill "$inj" 2>/dev/null || true
adb logcat -d > test-out/logcat.txt || true
exit $rc
