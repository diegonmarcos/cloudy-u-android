#!/usr/bin/env bash
# #1668 ONE clock (clock-one-lib.py K1-K6) against the tree, then each mutation must turn it red for the right reason.
# Called by every consumer's shell tester with the monorepo root; exits non-zero on any failure.
set -uo pipefail
ROOT="$(cd "${1:?usage: clock-one-lib.sh ROOT}" && pwd)"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHECK="$HERE/clock-one-lib.py"
FAILURES=0
echo "── #1668 one clock: K1-K6 against the tree ──"
if python3 "$CHECK" "$ROOT"; then :; else FAILURES=$((FAILURES + 1)); fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
# The slice of the tree the check reads: the lib, the class map, and each consumer's declaration, gradle and main sources.
SLICE="$WORK/base"
mkdir -p "$SLICE/1_cicd/src/data" "$SLICE/ab_cloud-libs-shared/libs"
cp "$ROOT/1_cicd/src/data/lib-classes.json" "$SLICE/1_cicd/src/data/"
cp -r "$ROOT/ab_cloud-libs-shared/libs/clock-tools" "$SLICE/ab_cloud-libs-shared/libs/"
for app in ac_cloud-calc ac_cloud-tasks aa_cloud-superapp; do
    [ -d "$ROOT/$app" ] || continue
    mkdir -p "$SLICE/$app/app/src"
    cp "$ROOT/$app/build.json" "$SLICE/$app/"
    cp "$ROOT/$app/app/build.gradle" "$SLICE/$app/app/"
    python3 - "$ROOT/$app/app/src/main" "$SLICE/$app/app/src/main" <<'PY'
import os, shutil, sys
src, dst = sys.argv[1], sys.argv[2]
for d, _, fs in os.walk(src):
    for f in fs:
        if f.endswith(".kt"):
            out = os.path.join(dst, os.path.relpath(d, src))
            os.makedirs(out, exist_ok=True)
            shutil.copy2(os.path.join(d, f), out)
PY
done
if ! python3 "$CHECK" "$SLICE" >/dev/null; then
    echo "  FAIL  the slice copy is red before any mutation — the mutations below would prove nothing"; FAILURES=$((FAILURES + 1))
fi

mutate() {  # name, file (relative to ROOT), python expression over s (or NEW:<text> to create the file), expected fragment
    local name="$1" rel="$2" expr="$3" want="$4" copy="$WORK/$1"
    cp -r "$SLICE" "$copy"
    mkdir -p "$(dirname "$copy/$rel")"
    if ! python3 - "$copy/$rel" "$expr" <<'PY'
import os, sys
p, expr = sys.argv[1], sys.argv[2]
s = open(p, encoding="utf-8").read() if os.path.isfile(p) else ""
t = expr[4:] if expr.startswith("NEW:") else eval(expr)
if t == s:
    sys.exit(1)
open(p, "w", encoding="utf-8").write(t)
PY
    then echo "  VOID  MUT $name: the edit did not land — the mutation targets text that moved"; FAILURES=$((FAILURES + 1)); return; fi
    local out
    out="$(python3 "$CHECK" "$copy" 2>&1)"
    if [ $? -eq 0 ]; then echo "  FAIL  MUT $name: the check passed a broken tree"; FAILURES=$((FAILURES + 1))
    elif [[ "$out" != *"$want"* ]]; then echo "  FAIL  MUT $name: red for the wrong reason: $out"; FAILURES=$((FAILURES + 1))
    else echo "  PASS  MUT $name"; fi
    rm -rf "$copy"
}
CJ=ac_cloud-calc/app/src/main/java/com/diegonmarcos/cloudcalc
SJ=aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp
mutate calc-own-engine "$CJ/clock/ClockEngine.kt" 'NEW:package com.diegonmarcos.cloudcalc.clock
object ClockEngine' "K3 $CJ/clock/ClockEngine.kt: a file named like the lib's"
mutate calc-old-package "$CJ/Logic.kt" 's + "\nimport com.diegonmarcos.cloudcalc.clock.ClockLogic\n"' "the old com.diegonmarcos.cloudcalc.clock package"
mutate calc-alarm-clock "$CJ/Logic.kt" 's + "\nprivate fun x(am: android.app.AlarmManager, i: android.app.AlarmManager.AlarmClockInfo, p: android.app.PendingIntent) = am.setAlarmClock(i, p)\n"' "an AlarmManager alarm clock"
mutate tasks-unlinked ac_cloud-tasks/app/build.gradle 's.replace("implementation project(\x27:libs:clock-tools\x27)", "")' "K2 ac_cloud-tasks/app/build.gradle does not compile libs:clock-tools"
mutate tasks-copy-not-reference ac_cloud-tasks/build.json 's.replace("\"dir\": \"../ab_cloud-libs-shared/libs/clock-tools\"", "\"dir\": \"clock-tools\"")' "K2 ac_cloud-tasks build.json::modules must map libs:clock-tools"
mutate superapp-no-exact aa_cloud-superapp/build.json 's.replace("\"exact_alarm\",", "", 1)' "K2 aa_cloud-superapp perms.needs lacks exact_alarm"
mutate superapp-own-stopwatch "$SJ/cal/TasksPopup.kt" 's + "\nprivate var stopwatchAccumulatedMs: Long = 0L\n"' "a stopwatch state of its own"
mutate superapp-own-format "$SJ/cal/TasksPopup.kt" 's.replace("internal fun formatStopwatch(ms: Long): String = ClockLogic.stopwatchTenthsText(ms)", "internal fun formatStopwatch(ms: Long): String = \"%d\".format(ms / 1000)")' "formats a stopwatch / countdown itself"
mutate superapp-readout-own "$SJ/launcher/LauncherStatusStrip.kt" 's.replace("rememberStopwatch()", "Stopwatch()")' "K5 the SuperApp's Home bar readout does not read the lib's stopwatch"
mutate superapp-dropdown-own "$SJ/cal/TasksPopup.kt" 's.replace("ClockEngine.stopwatch(ctx, \"toggle\")", "Unit")' "K5 the SuperApp's date dropdown does not drive"
mutate tasks-no-clock-page ac_cloud-tasks/build.json 's.replace("\"id\": \"clock\",\n            \"label\": \"Clock\"", "\"id\": \"clocks\",\n            \"label\": \"Clock\"")' "K4 Cloud Tasks' Tasks section does not declare the Clock sub-page"
mutate tasks-not-drawn ac_cloud-tasks/app/src/main/java/com/diegonmarcos/cloudagenda/ui/TasksApp.kt 's.replace("clocktools.ui.FullClock(", "clocktools.ui.WorldClockScreen(")' "K4 Cloud Tasks does not draw libs:clock-tools' FullClock"
mutate lib-receiver-exported ab_cloud-libs-shared/libs/clock-tools/src/main/AndroidManifest.xml 's.replace("android:name=\"com.diegonmarcos.clocktools.ClockReceiver\"\n            android:exported=\"false\"", "android:name=\"com.diegonmarcos.clocktools.ClockReceiver\"\n            android:exported=\"true\"")' "K1 the lib's ClockReceiver must be declared and not exported"
mutate lib-no-pit ab_cloud-libs-shared/libs/clock-tools/core/build.gradle 's.replace("pitest {", "pitestX {")' "K1 libs:clock-tools/core/build.gradle has no PIT mutation gate"
mutate lib-unclassified 1_cicd/src/data/lib-classes.json 's.replace("\"clock-tools\":", "\"clock-toolz\":")' "K1 clock-tools must be classified gui"
mutate ownership-unstated ab_cloud-libs-shared/libs/clock-tools/build.gradle 's.replace("ALARM OWNERSHIP", "ALARMS")' "K6 the lib's build.gradle no longer states who owns ringing"

echo "── #1668 one clock + mutations: $FAILURES failure(s) ──"
exit $(( FAILURES > 0 ? 1 : 0 ))
