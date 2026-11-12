#!/usr/bin/env python3
"""#1668 ONE clock: libs:clock-tools is the fleet's only clock implementation and every carrier links it.

Run by each consumer's shell tester (ac_cloud-calc/test/test-calc-clock-lib.sh, ac_cloud-tasks/test/test-tasks-clock.sh,
aa_cloud-superapp/test/test-superapp-clock-lib.sh), so every ship of a consumer checks every consumer.

  K1 the lib is whole: core/ (plain JVM rules with its PIT block) and the Android half (ClockEngine, ClockService,
     ClockReceiver, ClockBootReceiver, RingActivity, the screens, #1667's WorldTable / Sun), classified `gui` in
     1_cicd/src/data/lib-classes.json, and its manifest declares the clock's components (receiver not exported)
  K2 each consumer maps libs:clock-tools and libs:clock-tools-core to the lib's dirs in build.json::modules, depends on
     libs:clock-tools, compiles it in app/build.gradle, and declares notifications + exact_alarm in perms.needs
  K3 no clock logic outside the lib: in a consumer's app sources no file of the lib's names, no package
     com.diegonmarcos.cloudcalc.clock, no AlarmManager alarm clock (setAlarmClock), no alarm ringtone / tone of its own,
     no stopwatch state of its own (an elapsed-time stopwatch field), no stopwatch formatter that does not hand off
     to ClockLogic; Cloud Calc's old clock/ module is gone
  K4 Cloud Tasks declares the Clock sub-page in its Tasks section (build.json::ui) and draws libs:clock-tools' FullClock
  K5 the SuperApp's stopwatch is the lib's: the Home bar readout reads rememberStopwatch, the date dropdown drives
     ClockEngine.stopwatch
  K6 ALARM OWNERSHIP is stated where it is decided (the lib's build.gradle): one store per app, nothing exported

USAGE  clock-one-lib.py ROOT   (ROOT = the monorepo root)
EXIT   0 ok, 1 a finding
"""
import glob
import json
import os
import re
import sys

ROOT = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else ".")
LIB = os.path.join(ROOT, "ab_cloud-libs-shared", "libs", "clock-tools")
CONSUMERS = ["ac_cloud-calc", "ac_cloud-tasks", "aa_cloud-superapp"]
bad = []


def read(*p):
    path = os.path.join(*p)
    return open(path, encoding="utf-8", errors="replace").read() if os.path.isfile(path) else ""


def code(text):
    """Comment lines dropped: prose may name what code must not do."""
    return "\n".join(l for l in text.split("\n") if not re.match(r"\s*(\*|//|/\*|#)", l))


# K1
ksrc = os.path.join(LIB, "src", "main", "java", "com", "diegonmarcos", "clocktools")
core = os.path.join(LIB, "core", "src", "main", "kotlin", "com", "diegonmarcos", "clocktools")
for f in ("ClockEngine.kt", "ClockService.kt", "ClockReceiver.kt", "RingActivity.kt", "ClockHost.kt", "ui/ClockScreens.kt", "ui/WorldClock.kt"):
    if not os.path.isfile(os.path.join(ksrc, f)):
        bad.append("K1 libs:clock-tools lacks %s" % f)
for f in ("ClockLogic.kt", "ClockModel.kt", "ClockConfig.kt", "Sun.kt", "WorldTable.kt"):
    if not os.path.isfile(os.path.join(core, f)):
        bad.append("K1 libs:clock-tools/core lacks %s" % f)
if "pitest {" not in read(LIB, "core", "build.gradle") or "mutationThreshold" not in read(LIB, "core", "build.gradle"):
    bad.append("K1 libs:clock-tools/core/build.gradle has no PIT mutation gate")
if not glob.glob(os.path.join(LIB, "core", "src", "test", "kotlin", "**", "*Test.kt"), recursive=True):
    bad.append("K1 libs:clock-tools/core has no test suite")
classes = json.loads(read(ROOT, "1_cicd", "src", "data", "lib-classes.json") or "{}").get("libs", {})
if classes.get("clock-tools", {}).get("class") != "gui":
    bad.append("K1 clock-tools must be classified gui in 1_cicd/src/data/lib-classes.json")
man = read(LIB, "src", "main", "AndroidManifest.xml")
rx = re.search(r'<receiver\s[^>]*android:name="com\.diegonmarcos\.clocktools\.ClockReceiver"[^>]*>', man, re.S)
if not rx or 'android:exported="false"' not in rx.group(0):
    bad.append("K1 the lib's ClockReceiver must be declared and not exported (another app could fire or dismiss an alarm)")
for comp in ("ClockService", "ClockBootReceiver", "RingActivity"):
    if 'android:name="com.diegonmarcos.clocktools.%s"' % comp not in man:
        bad.append("K1 the lib's manifest does not declare %s" % comp)

# K2 / K3
LIB_FILES = {"ClockLogic.kt", "ClockModel.kt", "ClockEngine.kt", "ClockService.kt", "ClockReceiver.kt", "RingActivity.kt",
             "ClockScreens.kt", "WorldTable.kt", "Sun.kt", "ClockConfig.kt"}
OWN_LOGIC = [
    (re.compile(r"package com\.diegonmarcos\.cloudcalc\.clock\b|import com\.diegonmarcos\.cloudcalc\.clock\."), "the old com.diegonmarcos.cloudcalc.clock package"),
    (re.compile(r"\bsetAlarmClock\("), "an AlarmManager alarm clock (setAlarmClock)"),
    (re.compile(r"RingtoneManager\.TYPE_ALARM|ToneGenerator\(\s*AudioManager\.STREAM_ALARM"), "an alarm sound of its own"),
    (re.compile(r"\bstopwatch\w*(Start|Accumulated|Elapsed)\w*\s*(\+?=|:)\s*[^=]", re.I), "a stopwatch state of its own"),
    (re.compile(r"elapsedRealtime\(\)\s*-\s*\w*stopwatch", re.I), "a stopwatch clock of its own"),
]
FORMATTER = re.compile(r"fun\s+(format\w*Stopwatch\w*|formatStopwatch\w*|stopwatchText|countdown)\s*\([^)]*\)[^\n]*")
for app in CONSUMERS:
    adir = os.path.join(ROOT, app)
    if not os.path.isdir(adir):
        bad.append("K2 %s is not in the tree — a consumer this check cannot reach is not checked" % app)
        continue
    try:
        bj = json.load(open(os.path.join(adir, "build.json"), encoding="utf-8"))
    except (OSError, ValueError) as e:
        bad.append("K2 %s/build.json unreadable: %s" % (app, e))
        continue
    mods = bj.get("modules", {})
    for name, rel in (("libs:clock-tools", "ab_cloud-libs-shared/libs/clock-tools"), ("libs:clock-tools-core", "ab_cloud-libs-shared/libs/clock-tools/core")):
        d = (mods.get(name) or {}).get("dir", "")
        if os.path.normpath(os.path.join(app, d)) != os.path.normpath(rel):
            bad.append("K2 %s build.json::modules must map %s to ../%s (by reference), found %r" % (app, name, rel, d))
    if "libs:clock-tools" not in (mods.get("app") or {}).get("depends_on", []):
        bad.append("K2 %s build.json::modules.app.depends_on lacks libs:clock-tools" % app)
    if "project(':libs:clock-tools')" not in code(read(adir, "app", "build.gradle")):
        bad.append("K2 %s/app/build.gradle does not compile libs:clock-tools" % app)
    needs = set((bj.get("perms") or {}).get("needs", []))
    for p in ("notifications", "exact_alarm"):
        if p not in needs:
            bad.append("K2 %s perms.needs lacks %s — the lib's manifest asks for it" % (app, p))
    for path in glob.glob(os.path.join(adir, "app", "src", "main", "**", "*.kt"), recursive=True):
        rel = os.path.relpath(path, ROOT)
        if os.path.basename(path) in LIB_FILES:
            bad.append("K3 %s: a file named like the lib's — the clock lives in libs:clock-tools alone" % rel)
        text = code(read(path))
        for rx_, what in OWN_LOGIC:
            m = rx_.search(text)
            if m:
                bad.append("K3 %s has %s (%s) — use libs:clock-tools" % (rel, what, m.group(0).strip()))
        for m in FORMATTER.finditer(text):
            if "ClockLogic." not in m.group(0):
                bad.append("K3 %s formats a stopwatch / countdown itself (%s) — hand off to ClockLogic" % (rel, m.group(0).strip()[:80]))
if os.path.isdir(os.path.join(ROOT, "ac_cloud-calc", "clock")):
    bad.append("K3 ac_cloud-calc/clock/ is back — the clock rules are libs:clock-tools/core")

# K4
tasks = json.loads(read(ROOT, "ac_cloud-tasks", "build.json") or "{}")
sec = next((s for s in tasks.get("ui", {}).get("sections", []) if s.get("id") == "tasks"), {})
if "clock" not in [p.get("id") for p in sec.get("pages", [])]:
    bad.append("K4 Cloud Tasks' Tasks section does not declare the Clock sub-page (build.json::ui.sections[tasks].pages)")
tapp = code(read(ROOT, "ac_cloud-tasks", "app", "src", "main", "java", "com", "diegonmarcos", "cloudagenda", "ui", "TasksApp.kt"))
if not re.search(r"Ids\.PAGE_CLOCK\s*->\s*[\w.]*FullClock\(", tapp):
    bad.append("K4 Cloud Tasks does not draw libs:clock-tools' FullClock on Ids.PAGE_CLOCK")

# K5
sa = os.path.join(ROOT, "aa_cloud-superapp", "app", "src", "main", "java", "com", "diegonmarcos", "superapp")
strip = code(read(sa, "launcher", "LauncherStatusStrip.kt"))
pop = code(read(sa, "cal", "TasksPopup.kt"))
if "rememberStopwatch()" not in strip:
    bad.append("K5 the SuperApp's Home bar readout does not read the lib's stopwatch (rememberStopwatch)")
if not re.search(r'ClockEngine\.stopwatch\(ctx, "toggle"\)', pop) or not re.search(r'ClockEngine\.stopwatch\(ctx, "reset"\)', pop):
    bad.append("K5 the SuperApp's date dropdown does not drive the lib's stopwatch (ClockEngine.stopwatch toggle / reset)")

# K6
if "ALARM OWNERSHIP" not in read(LIB, "build.gradle"):
    bad.append("K6 the lib's build.gradle no longer states who owns ringing (one store per app)")

for b in bad:
    print("  FAIL  " + b)
print("  PASS  K1-K6 one clock" if not bad else "  %d finding(s)" % len(bad))
sys.exit(1 if bad else 0)
