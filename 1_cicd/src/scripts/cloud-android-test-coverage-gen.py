#!/usr/bin/env python3
# ╔══════════════════════════════════════════════════════════════════╗
# ║ cloud-android-test-coverage-gen — derive the test-coverage       ║
# ║ inventory from the tree                                          ║
# ║                                                                  ║
# ║ Reads  : 1_cicd/src/cicd/ship-*.yml, each app's build.json and   ║
# ║          test tree, 1_cicd/src/data/test-coverage.json (notes)   ║
# ║ Writes : 1_cicd/dist/data/test-coverage.json                     ║
# ║                                                                  ║
# ║ Run it directly:  ./build.sh test-coverage                       ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# 24 of the 28 ship workflows executed no test of any kind, so for 24
# applications the pipeline's only question was "did Gradle exit 0" — and an
# application with zero testers produced the same green tick as one with fifty
# passing ones. A gap nobody can see is a gap nobody schedules.
#
# DERIVED, not written down. A hand-maintained inventory is a list that is true
# on the day it is written and silently wrong a week later — this repository has
# had three of those (a workflow watching nothing real, a dist artefact with no
# source, a tester counting participants). Everything countable is counted from
# the tree here; the only authored half is the one that cannot be derived,
# "what would the cheapest meaningful first test be", which lives in
# 1_cicd/src/data/test-coverage.json.
#
# REFUSES if an application has no note, or a note names something that is no
# longer here — so adding a ship workflow forces an answer about its coverage,
# and deleting one cannot leave a stale entry behind.
#
# A FILE OF ITS OWN, not a heredoc inside the workflow engine, because the
# inputs it counts are the WHOLE TREE: adding one shell tester under any app
# changes this artefact, and its author has no reason to be thinking about
# 1_cicd at all. Three commits in one day landed a tester, left this file
# stale, and turned main red on `generated files are up to date`. The advice
# that gate used to print — run ./build.sh workflow — purges and rebuilds
# 1_cicd/dist wholesale and races every other agent working in the tree (it
# emptied 55 files under 1_cicd/dist/cicd on 2026-09-30). So the narrow path
# has to EXIST before the gate can name it, and this is that path: one
# derivation, two callers (the engine, and ./build.sh test-coverage).
#
# REFUSES TO COLLAPSE. It writes nothing when it counted no applications at
# all, because a deriver whose inputs moved or vanished otherwise emits a
# valid-looking empty artefact and the emptiness reads as a fact about the
# repository. cloud-infra's pre-commit hook did exactly that and took 78
# services to 0 in one commit.

import glob, json, os, re, sys

root = sys.argv[1]
notes_path = os.path.join(root, "1_cicd/src/data/test-coverage.json")
notes = json.load(open(notes_path))["apps"]
apps, bad, seen = {}, [], set()

for wf in sorted(glob.glob(os.path.join(root, "1_cicd/src/cicd/ship-*.yml"))):
    name = os.path.basename(wf)
    text = open(wf).read()
    m = re.search(r"^  WORK_DIR:\s*(\S+)\s*$", text, re.M)
    key = m.group(1) if m else name
    seen.add(key)
    if key not in notes:
        bad.append("%s: no entry in 1_cicd/src/data/test-coverage.json for %r --"
                   " every ship workflow owes an answer about what it does not test" % (name, key))
        continue

    d = os.path.join(root, key)
    bj = os.path.join(d, "build.json")
    cfg = json.load(open(bj)) if os.path.isfile(bj) else {}
    tests = cfg.get("tests") or {}
    tdir = os.path.join(d, (tests.get("shell") or {}).get("dir") or "test")

    units = 0
    for dirpath, _, files in os.walk(d):
        # #1682 P3 step 5: the SuperApp's owner-only suites live in src/owner/test (compiled only while it is the ADB owner); they count too
        if os.sep + "src" + os.sep + "test" + os.sep in dirpath + os.sep or os.sep + "src" + os.sep + "owner" + os.sep + "test" + os.sep in dirpath + os.sep:
            units += sum(1 for f in files if f.endswith((".kt", ".java")))

    unit = tests.get("unit") or {}
    apps[key] = {
        "workflow": name,
        "shell_testers": len(glob.glob(os.path.join(tdir, "test-*.sh"))),
        "unit_test_sources": units,
        "unit_task_declared": bool(unit.get("task")) and unit.get("enabled") is not False,
        "ship_runs_testers": "cloud-android-test-engine.sh" in text,
        "ship_has_published_nothing_guard":
            "cloud-android-publish-guard.sh" in text and "id: publish" in text,
        "first_test": notes[key],
    }

for k in notes:
    if k not in seen:
        bad.append("1_cicd/src/data/test-coverage.json: %r has no ship workflow --"
                   " delete the entry rather than leaving a note about nothing" % k)

# Before writing anything: a run that counted nothing found no ship workflows,
# which means it was pointed at the wrong root -- not that the repository lost
# its applications. Fail loudly and leave the committed artefact alone.
if not apps:
    print("  no ship workflow found under %s/1_cicd/src/cicd/ship-*.yml --"
          " refusing to overwrite the inventory with an empty one" % root, file=sys.stderr)
    sys.exit(1)

covered = sum(1 for a in apps.values() if a["shell_testers"] or a["unit_task_declared"])
out = {
    "_doc": "GENERATED by cloud-android-test-coverage-gen.py from the tree"
            " plus 1_cicd/src/data/test-coverage.json. Do not edit.",
    "applications": len(apps),
    "with_executed_tests": covered,
    "with_no_tests_at_all": len(apps) - covered,
    "apps": apps,
}
dest = os.path.join(root, "1_cicd/dist/data/test-coverage.json")
os.makedirs(os.path.dirname(dest), exist_ok=True)
open(dest, "w").write(json.dumps(out, indent=2, sort_keys=True) + "\n")
print("  %d applications, %d with tests, %d with none"
      % (len(apps), covered, len(apps) - covered))

for b in bad:
    print("  " + b, file=sys.stderr)
sys.exit(1 if bad else 0)
