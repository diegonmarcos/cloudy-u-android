#!/bin/sh
# #1853 Google Takeout import into the one store (maps.db v6), checked on the sources; the JVM suites (TakeoutParityTest, TakeoutImportDbTest, ...) and
# the emulator test (TakeoutImportTest) prove the behaviour, this pins the design so a later edit cannot quietly undo it:
#   K1 schema: maps.db is v6, the upgrade and the fresh install both run the v6 statements, `source` columns on fixes / segments / rollups, every Google table exists.
#   K2 streaming: the parsers read through JsonPull (one record at a time); nothing in the takeout package loads a whole file (readText / readBytes / readLines).
#   K3 field parity: FieldSpec lists all three formats, each with a synthetic fixture and a parity test; every format is mapped by a parser.
#   K4 one store: the import opens no database of its own and keeps no preferences (no new store to declare in fleet-config).
#   K5 tracked wins: views hide an imported row only under a lower-priority overlap; a tracked rebuild never deletes imports; the tracked engine reads tracked fixes only.
#   K6 UI + rule 16: the Import tab with both SAF pickers, progress, cancel and summary, and every earlier Day tab is still there.
#   K8 foreground service (#1853): the import runs in TakeoutImportService (dataSync, onTimeout(startId, fgsType), DataSyncBudget, progress notification with Cancel), declared with its permission; the emulator test asserts the service path.
#   K7 the checks bite: each planted regression (a whole-file read, a dropped tracked guard, a lost tab, a lost source column) fails.
set -eu
HERE="$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)"
ROOT="$(CDPATH='' cd -- "$HERE/.." && pwd)"
REPO="$(CDPATH='' cd -- "$ROOT/.." && pwd)"
python3 - "$REPO" <<'PY'
import os, re, sys, shutil, tempfile
repo = sys.argv[1]
M = "ab_cloud-libs-shared/libs/maps/"
TK = M + "src/main/java/com/diegonmarcos/cloudnav/maps/takeout/"
MAPS = M + "src/main/java/com/diegonmarcos/cloudnav/maps/"
NAV = "ac_cloud-nav/app/src/main/java/com/diegonmarcos/cloudnav/tracker/"
TESTS = M + "src/test/java/com/diegonmarcos/cloudnav/maps/takeout/"
FIX = M + "src/test/resources/takeout/"
FILES = [TK + f for f in ["TakeoutJson.kt", "TakeoutParsers.kt", "TakeoutSchema.kt", "TakeoutImporter.kt", "TakeoutRebuild.kt", "TakeoutAndroid.kt", "TakeoutFieldSpec.kt"]] + \
        [MAPS + "MapsDb.kt", MAPS + "TimelineSync.kt", NAV + "DayScreen.kt", NAV + "DayFragment.kt", NAV + "TakeoutImportScreen.kt", NAV + "TakeoutImportService.kt", "ac_cloud-nav/app/src/main/AndroidManifest.xml", "ac_cloud-nav/app/src/androidTest/java/com/diegonmarcos/cloudnav/TakeoutImportTest.kt"] + \
        [TESTS + f for f in ["TakeoutParityTest.kt", "TakeoutImportDbTest.kt", "TakeoutParsersTest.kt", "TakeoutDeriveTest.kt", "TakeoutJsonTest.kt"]] + \
        [FIX + f for f in ["records/Records.json", "semantic/2026/2026_MARCH.json", "timeline/Timeline.json", "android/location-history.json"]]
fails = []
def ok(c, good, bad):
    print(("  PASS: " + good) if c else ("  FAIL: " + bad))
    if not c: fails.append(bad)
def load(top):
    return {f: open(os.path.join(top, f), errors="ignore").read() for f in FILES if os.path.exists(os.path.join(top, f))}
def check(top):
    s = load(top); r = {}
    db = s.get(MAPS + "MapsDb.kt", ""); sc = s.get(TK + "TakeoutSchema.kt", "")
    r["k1"] = ("DB_VERSION = 6" in db and re.search(r"oldVersion < 6\)[^\n]*MIGRATE_V6", db) is not None and "createFixes(db)\n        createStore(db)\n        for (q in TakeoutSchema.MIGRATE_V6)" in db
               and all(t in sc for t in ["ALTER TABLE fixes ADD COLUMN source", "ALTER TABLE timeline_segments ADD COLUMN source", "ALTER TABLE day_rollups ADD COLUMN source", "ALTER TABLE timeline_segments ADD COLUMN prio"])
               and all(("TkTable." + t) in sc for t in ["VISITS", "CANDIDATES", "ACTIVITIES", "SIGNALS", "PROFILE"]) and '"raw_json"' in sc)
    pk = [s.get(TK + f, "") for f in ["TakeoutJson.kt", "TakeoutParsers.kt", "TakeoutImporter.kt", "TakeoutSchema.kt", "TakeoutRebuild.kt"]]
    r["k2"] = [m for t in pk for m in re.findall(r"\.readText\(|\.readBytes\(|\.readLines\(|readAllBytes\(", t)]
    r["k2b"] = "JsonPull(" in s.get(TK + "TakeoutParsers.kt", "") and "readArray" in s.get(TK + "TakeoutParsers.kt", "")
    spec = s.get(TK + "TakeoutFieldSpec.kt", "")
    r["k3"] = all(re.search(r"val %s: List<String> = listOf\(" % n, spec) and spec.count('"') > 300 for n in ["RECORDS", "SEMANTIC", "TIMELINE"]) \
              and all(TESTS + f in s for f in ["TakeoutParityTest.kt", "TakeoutImportDbTest.kt"]) and all((FIX + f) in s for f in ["records/Records.json", "semantic/2026/2026_MARCH.json", "timeline/Timeline.json", "android/location-history.json"]) \
              and all(("TakeoutFormat." + n) in s.get(TK + "TakeoutParsers.kt", "") for n in ["RECORDS", "SEMANTIC", "TIMELINE"]) and "FieldSpec." in s.get(TESTS + "TakeoutParityTest.kt", "") and "mute" in s.get(TESTS + "TakeoutParityTest.kt", "").lower()
    r["k4"] = [f for f in FILES if f.startswith(TK) and re.search(r"openOrCreateDatabase|getSharedPreferences|SQLiteOpenHelper|DataStore", s.get(f, ""))]
    r["k5"] = ("t.prio < s.prio" in sc and "source = 'tracked'" in db and "trackedFixesBetween(wf, nowMs)" in s.get(MAPS + "TimelineSync.kt", "") and "WHERE source = 'tracked'" in sc.replace("fixes WHERE source", "fixes WHERE source"))
    ds = s.get(NAV + "DayScreen.kt", ""); dfr = s.get(NAV + "DayFragment.kt", ""); ui = s.get(NAV + "TakeoutImportScreen.kt", "")
    r["k6"] = ('IMPORT("Import")' in ds and "ActivityResultContracts.OpenDocument()" in dfr and "ActivityResultContracts.OpenDocumentTree()" in dfr and "ImportController.cancel()" in dfr
               and all(t in ui for t in ["ImportTags.FILE", "ImportTags.FOLDER", "ImportTags.CANCEL", "ImportTags.SUMMARY", "Days:", "Visits:", "Activities:", "Places:"]))
    r["k6b"] = [t for t in ['DAY("Day")', 'TRIPS("Trips")', 'PLACES("Places")', 'CITIES("Cities")', 'WORLD("World")', 'INSIGHTS("Insights")'] if t not in ds]
    sv = s.get(NAV + "TakeoutImportService.kt", ""); mf = s.get("ac_cloud-nav/app/src/main/AndroidManifest.xml", ""); et = s.get("ac_cloud-nav/app/src/androidTest/java/com/diegonmarcos/cloudnav/TakeoutImportTest.kt", "")
    r["k8"] = (re.search(r"override fun onTimeout\(startId: Int, fgsType: Int\)", sv) is not None and "FOREGROUND_SERVICE_TYPE_DATA_SYNC" in sv and "DataSyncBudget.mayHold(" in sv and "DataSyncBudget.begin(" in sv and "DataSyncBudget.end(" in sv
               and "ACTION_CANCEL" in sv and 'addAction(0, "Cancel"' in sv and "ImportController.runSync(" in sv and "setProgress(" in sv
               and 'android:name=".tracker.TakeoutImportService"' in mf and 'android:foregroundServiceType="dataSync"' in mf and "android.permission.FOREGROUND_SERVICE_DATA_SYNC" in mf
               and "TakeoutImportService.start(" in dfr and "ImportController.start(" not in dfr and "TakeoutImportService.started" in et)
    return r
print("== K1-K6 on the real sources ==")
r = check(repo)
ok(r["k1"], "maps.db is v6: upgrade and fresh install run the v6 statements; source / prio columns; every Google table", "the v6 schema is incomplete (DB_VERSION, onUpgrade, onCreate, source columns or tables)")
ok(not r["k2"] and r["k2b"], "the parsers stream through JsonPull and nothing loads a whole file", "a whole-file read in the takeout package: " + ", ".join(r["k2"]))
ok(r["k3"], "FieldSpec lists the three formats, with fixtures and parity tests (mutation included)", "field parity wiring is incomplete (spec, fixtures, tests or a format parser)")
ok(not r["k4"], "the import opens no database or preferences of its own (maps.db only)", "a new store in the takeout package: " + ", ".join(r["k4"]))
ok(r["k5"], "tracked wins: views, tracked rebuild and the tracked engine input", "the tracked-wins rules are incomplete")
ok(r["k6"] and not r["k6b"], "the Import tab with both pickers, progress, cancel, summary; every earlier Day tab is still there", "the import UI or an earlier tab is gone: " + ", ".join(r["k6b"]))
ok(r["k8"], "the import runs in the dataSync foreground service: budget gate, onTimeout, progress + Cancel, manifest and permission, emulator test on the service", "the foreground service wiring is incomplete (TakeoutImportService, onTimeout, DataSyncBudget, Cancel, manifest or test)")
print("== K7: the checks bite ==")
tmp = tempfile.mkdtemp()
try:
    for f in FILES:
        if os.path.exists(os.path.join(repo, f)):
            d = os.path.join(tmp, os.path.dirname(f)); os.makedirs(d, exist_ok=True); shutil.copy(os.path.join(repo, f), os.path.join(tmp, f))
    def mut(f, a, b):
        p = os.path.join(tmp, f); s = open(p).read(); assert a in s, (f, a); open(p, "w").write(s.replace(a, b, 1)); return s
    def restore(f, s): open(os.path.join(tmp, f), "w").write(s)
    s = mut(TK + "TakeoutParsers.kt", "val p = JsonPull(reader)", "val whole = reader.readText(); val p = JsonPull(whole.reader())"); ok(bool(check(tmp)["k2"]), "a whole-file read fails K2", "K2 missed a whole-file read"); restore(TK + "TakeoutParsers.kt", s)
    s = mut(MAPS + "MapsDb.kt", "AND source = 'tracked'", ""); ok(not check(tmp)["k5"], "a tracked rebuild that deletes imports fails K5", "K5 missed a delete of imported rows"); restore(MAPS + "MapsDb.kt", s)
    s = mut(TK + "TakeoutSchema.kt", "t.prio < s.prio", "t.prio <= s.prio"); ok(not check(tmp)["k5"], "a view that lets an import hide itself fails K5", "K5 missed a changed priority rule"); restore(TK + "TakeoutSchema.kt", s)
    s = mut(NAV + "DayScreen.kt", 'INSIGHTS("Insights")', 'INSIGHT("Insights")'); ok(bool(check(tmp)["k6b"]), "a lost Day tab fails K6", "K6 missed a lost tab"); restore(NAV + "DayScreen.kt", s)
    s = mut(MAPS + "MapsDb.kt", "DB_VERSION = 6", "DB_VERSION = 5"); ok(not check(tmp)["k1"], "a database left at v5 fails K1", "K1 missed the version"); restore(MAPS + "MapsDb.kt", s)
    s = mut(TK + "TakeoutSchema.kt", "ALTER TABLE fixes ADD COLUMN source", "ALTER TABLE fixes ADD COLUMN src"); ok(not check(tmp)["k1"], "a lost source column fails K1", "K1 missed the source column"); restore(TK + "TakeoutSchema.kt", s)
    s = mut(TK + "TakeoutImporter.kt", "object TakeoutImporter {", "object TakeoutImporter {\n    val prefs = ctx.getSharedPreferences(\"x\", 0)"); ok(bool(check(tmp)["k4"]), "preferences of its own fail K4", "K4 missed new preferences"); restore(TK + "TakeoutImporter.kt", s)
    s = mut(TK + "TakeoutRebuild.kt", "object TakeoutRebuild {", "object TakeoutRebuild {\n    val db = ctx.openOrCreateDatabase(\"x\", 0, null)"); ok(bool(check(tmp)["k4"]), "a database of its own fails K4", "K4 missed a second database"); restore(TK + "TakeoutRebuild.kt", s)
    s = mut(NAV + "TakeoutImportService.kt", "override fun onTimeout(startId: Int, fgsType: Int)", "fun onTimeoutGone(startId: Int, fgsType: Int)"); ok(not check(tmp)["k8"], "a service without onTimeout fails K8", "K8 missed a lost onTimeout"); restore(NAV + "TakeoutImportService.kt", s)
    s = mut("ac_cloud-nav/app/src/main/AndroidManifest.xml", 'android:foregroundServiceType="dataSync"', ''); ok(not check(tmp)["k8"], "an undeclared service type fails K8", "K8 missed the manifest type"); restore("ac_cloud-nav/app/src/main/AndroidManifest.xml", s)
    s = mut(NAV + "DayFragment.kt", "TakeoutImportService.start(", "ImportController.start("); ok(not check(tmp)["k8"], "an import back on a bare thread fails K8", "K8 missed the thread path"); restore(NAV + "DayFragment.kt", s)
    ok(not check(tmp)["k2"] and check(tmp)["k5"] and check(tmp)["k1"] and check(tmp)["k6"] and check(tmp)["k8"], "the pristine copy passes again", "the pristine copy fails")
finally:
    shutil.rmtree(tmp)
print(f"\n{'FAIL' if fails else 'PASS'}: test-takeout-import ({len(fails)} failing)")
sys.exit(1 if fails else 0)
PY
