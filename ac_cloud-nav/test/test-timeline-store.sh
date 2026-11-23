#!/bin/sh
# #1851 ONE store for Tracker and Explored, and every existing page and field kept (rule 16).
#   S1 field parity: every label / tab / tag the Explored and Tracker pages drew before the redesign is still in their sources.
#   S2 one store: the pages read fixes, steps, sleep and segments only through maps.db (MapsDb / TimelineSync / StepsFromStore); no page
#      opens the engine's own database or the legacy points table.
#   S3 one step answer: Explored > Day and Tracker > Steps both answer through StepsFromStore.
#   S4 each stop and move opens its detail (Explored move rows, Tracker timeline rows).
#   S5 the checks bite: a planted removal of a field, a planted page read of EngineDb, a planted second step source each fail.
set -eu
HERE="$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)"
ROOT="$(CDPATH='' cd -- "$HERE/.." && pwd)"
python3 - "$ROOT" <<'PY'
import os, re, sys, tempfile, shutil
root = sys.argv[1]
T = "app/src/main/java/com/diegonmarcos/cloudnav/tracker/"
FILES = ["DayScreen.kt", "DayFragment.kt", "TrackerScreens.kt", "TrackerFragments.kt", "StepsRepository.kt", "TrackerDebugApiProvider.kt", "StepsFromStore.kt", "DayView.kt"]
fails = []
def ok(c, good, bad):
    print(("  PASS: " + good) if c else ("  FAIL: " + bad))
    if not c: fails.append(bad)
def load(top): return {f: open(os.path.join(top, T, f), errors="ignore").read() for f in FILES if os.path.exists(os.path.join(top, T, f))}
FIELDS = {
  "DayScreen.kt": ["Total km", "Total steps", "Time active", "Time stopped", "Map day", "Visits", "Geo code", "Accuracy", "Address", "Arrival",
                   "Departure", "Time spent", "Arrived by", "Visits today", "Data source", "Open in map", "Copy geo code", "Copy full address",
                   "Edit place", "Split", "Delete", "Edit mode", "Add visit", "Transport mode", 'DAY("Day"', 'TRIPS("Trips"', 'PLACES("Places"',
                   'CITIES("Cities"', 'WORLD("World"', 'INSIGHTS("Insights"'],
  "TrackerScreens.kt": ["Timeline", "Steps per day", "Sleep per day", "Grant access", "TIMELINE", "SLEEP_CHART"],
}
def check(top):
    src = load(top); res = {}
    miss = [f"{k}: {t}" for k, ts in FIELDS.items() for t in ts if t not in src.get(k, "")]
    res["s1"] = miss
    page_files = ["DayScreen.kt", "DayFragment.kt", "TrackerScreens.kt", "TrackerFragments.kt"]
    bad = [f for f in page_files if re.search(r"EngineDb|FROM points|tracker_engine", src.get(f, ""))]
    bad += [f for f in ["StepsRepository.kt"] if re.search(r"EngineDb\.get\(ctx\)\.fixes|FROM points", src.get(f, ""))]
    res["s2"] = bad
    res["s2b"] = "MapsDb.get(ctx).fixesBetween" in src.get("StepsRepository.kt", "") and "TimelineSync.view" in src.get("DayFragment.kt", "")
    res["s3"] = "StepsFromStore.read" in src.get("DayFragment.kt", "") and "StepsFromStore.read" in src.get("TrackerDebugApiProvider.kt", "")
    res["s4"] = "DayTags.detail(i)" in src.get("DayScreen.kt", "") and src.get("DayScreen.kt", "").count("DayTags.detail(i)") >= 2 and "rowDetail" in src.get("TrackerScreens.kt", "")
    return res
print("== S1-S4 on the real sources ==")
r = check(root)
ok(not r["s1"], "every Explored and Tracker field is still there", "fields gone: " + ", ".join(r["s1"]))
ok(not r["s2"] and r["s2b"], "pages read only the one store (maps.db fixes, TimelineSync.view)", "a page reads another store: " + ", ".join(r["s2"]))
ok(r["s3"], "Explored > Day and Tracker > Steps answer through StepsFromStore", "the step answer is not shared")
ok(r["s4"], "stops and moves open their detail", "a stop or move has no detail")
print("== S5: the checks bite ==")
tmp = tempfile.mkdtemp()
try:
    for f in FILES:
        d = os.path.join(tmp, T); os.makedirs(d, exist_ok=True)
        if os.path.exists(os.path.join(root, T, f)): shutil.copy(os.path.join(root, T, f), os.path.join(d, f))
    def mut(f, a, b):
        p = os.path.join(tmp, T, f); s = open(p).read(); open(p, "w").write(s.replace(a, b)); return s
    def restore(f, s): open(os.path.join(tmp, T, f), "w").write(s)
    s = mut("DayScreen.kt", "Time stopped", "Time idle"); ok(bool(check(tmp)["s1"]), "a renamed field fails S1", "S1 missed a renamed field"); restore("DayScreen.kt", s)
    s = mut("DayFragment.kt", "TimelineSync.view", "EngineDb.get(app).fixes(0,0); TimelineSync.view"); ok(bool(check(tmp)["s2"]), "a page reading EngineDb fails S2", "S2 missed an EngineDb read"); restore("DayFragment.kt", s)
    s = mut("TrackerDebugApiProvider.kt", "StepsFromStore.read", "StepsOther.read"); ok(not check(tmp)["s3"], "a second step source fails S3", "S3 missed a second step source"); restore("TrackerDebugApiProvider.kt", s)
    ok(not check(tmp)["s1"] and check(tmp)["s3"], "the pristine copy passes again", "the pristine copy fails")
finally:
    shutil.rmtree(tmp)
print(f"\n{'FAIL' if fails else 'PASS'}: test-timeline-store ({len(fails)} failing)")
sys.exit(1 if fails else 0)
PY
