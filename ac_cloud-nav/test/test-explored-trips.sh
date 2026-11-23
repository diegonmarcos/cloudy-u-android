#!/bin/sh
# #1863 Explored > Trips (cache, calendar, tables, Export/Import) and #1862 Tracker > Timeline (strips), statically, with mutations.
#   E1 cache: the page and the engine / import read Explored through AggregatesStore.explore (ExploredCache: checksum, SCHEMA, live today,
#      digests in maps.db `aggregates`, no database of its own); no page re-runs Aggregates.perDay / Rankings over every day.
#   E2 subpage order: Day, Week, Month | World, Cities, Places, Trips, Insights | Export/Import (groups), each tab drawn.
#   E3 rule 16 field parity: every field the old Places / Cities / World lists, Trips, Insights and Import showed is still drawn, now in
#      the sortable tables (continent first on World, continent + country first on Cities) with tappable last-visit dates.
#   E4 Export/Import: JSON + KMZ export, restore, the unchanged Takeout import, delete duplicates (preview then confirm, also from Insights),
#      delete by dates (preview), delete all (two confirmations), refresh; every delete marks the cache dirty.
#   E5 Timeline: day/night strip, activity strip with legend, transport icons, sleep city, and every old row field (type, times, place,
#      address, coordinates, mode, distance, steps, start / end / duration, source).
#   E6 the JVM tests that hold the logic exist (cache, data ops, segments).
#   E7 the checks bite: a planted mutation of each one fails it.
set -eu
HERE="$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)"
ROOT="$(CDPATH='' cd -- "$HERE/.." && pwd)"
python3 - "$ROOT" <<'PY'
import os, re, sys, tempfile, shutil
root = sys.argv[1]
T = "app/src/main/java/com/diegonmarcos/cloudnav/tracker/"
JT = "app/src/test/java/com/diegonmarcos/cloudnav/tracker/"
FILES = [T + f for f in ["DayScreen.kt", "DayFragment.kt", "AggregatesStore.kt", "ExploredCache.kt", "ExploredStore.kt", "ExploredData.kt", "ExploredViews.kt",
                         "DaySegments.kt", "SegmentStrips.kt", "TrackerScreens.kt", "TrackerRows.kt", "TakeoutImportScreen.kt", "NavTimelineExtra.kt"]] + \
        [JT + f for f in ["ExploredCacheTest.kt", "ExploredDataTest.kt", "DaySegmentsTest.kt"]]
fails = []
def ok(c, good, bad):
    print(("  PASS: " + good) if c else ("  FAIL: " + bad))
    if not c: fails.append(bad)
def load(top): return {f: open(os.path.join(top, f), errors="ignore").read() for f in FILES if os.path.exists(os.path.join(top, f))}
def g(src, f): return src.get(T + f, "")

ORDER = ["DAY", "WEEK", "MONTH", "WORLD", "CITIES", "PLACES", "TRIPS", "INSIGHTS", "IMPORT"]
GROUPS = {"DAY": 0, "WEEK": 0, "MONTH": 0, "WORLD": 1, "CITIES": 1, "PLACES": 1, "TRIPS": 1, "INSIGHTS": 1, "IMPORT": 2}
OLD = {   # the fields the pages drew before #1863 / #1862, and where each lives now
  "DayScreen.kt": ["Set home", "Auto", "Tap for the route map", "No vehicle travel", "Average sleep (7 days)", "Hours slept", "Walking distance",
                   "Total km", "Total steps", "Time active", "Time stopped", "Map day", "ui.rangeLabel"],
  "TakeoutImportScreen.kt": ["Pick zip or JSON file", "Pick folder", "Cancel", "Earlier imports", "Days:", "Visits:", "Activities:", "Places:", "Positions:"],
  "ExploredData.kt": ['>("place", "Place"', '>("city", "City"', '>("country", "Country"', '>("visits", "Visits"', '>("time", "Time"',
                      '>("days", "Days"', '>("continent", "Continent"', '>("km", "km"', '>("steps", "Steps"', '>("last", "Last visit"',
                      '>("cities", "Cities"', '>("places", "Places"'],
  "TrackerScreens.kt": ["r.type", "TrackerFormat.span(r, zone)", "Chip(r.mode", "r.title", "r.address", "TrackerFormat.coords(r.lat, r.lon)", "TrackerFormat.distance(r.distanceM)",
                        "TrackerFormat.n(r.steps)", "rowDetail(i)", '"Start "', '"  ·  End "', "StepsFormat.duration(r.endMs - r.startMs)", '"Source: the one tracker store',
                        "No locations or activities recorded for this day.", "TrackerTags.PREV", "TrackerTags.NEXT"],
}

def check(top):
    s = load(top); r = {}
    df, ag, ca, ds, dv, dt = g(s, "DayFragment.kt"), g(s, "AggregatesStore.kt"), g(s, "ExploredCache.kt"), g(s, "DayScreen.kt"), g(s, "ExploredViews.kt"), g(s, "ExploredData.kt")
    st, ts, seg, nx = g(s, "ExploredStore.kt"), g(s, "TrackerScreens.kt"), g(s, "DaySegments.kt"), g(s, "NavTimelineExtra.kt")
    # E1
    r["e1"] = ("AggregatesStore.explore(" in df and not re.search(r"Aggregates\.perDay\(|Rankings\.(places|cities|countries)\(", df)
               and re.search(r"fun rebuild\(.*?\n(.*\n){0,6}?\s*explore\(", ag) is not None and "ExploredCache.refresh(" in ag
               and all(t in ca for t in ["const val SCHEMA", "fun checksum(", "fun isLive(", "MessageDigest", "old.sum == sum", "old.version == SCHEMA", "if (isLive(d, nowMs, zone))"])
               and 'SELECT k, json FROM aggregates WHERE kind = ?' in st and not re.search(r"openOrCreateDatabase|SQLiteOpenHelper|getSharedPreferences", st + ca)
               and "AggregatesStore.rebuild(" in nx)
    # E2
    m = re.search(r"enum class DayTab\(val label: String, val group: Int\) \{(.*?)\n\}", ds, re.S)
    found = re.findall(r"(\w+)\(\"([^\"]+)\", (\d)\)", m.group(1)) if m else []
    r["e2"] = ([f[0] for f in found] == ORDER and all(GROUPS[f[0]] == int(f[2]) for f in found) and dict((f[0], f[1]) for f in found).get("IMPORT") == "Export/Import"
               and all(("DayTab.%s ->" % t) in ds for t in ORDER) and ".group != t.group" in ds)
    # E3
    miss = [f"{k}: {t}" for k, ts_ in OLD.items() for t in ts_ if t not in g(s, k)]
    wc = re.search(r"val WORLD: List<TableCol<WorldRow>> = listOf\(\s*TableCol<WorldRow>\(\"(\w+)\"", dt); cc = re.findall(r"TableCol<CityRow>\(\"(\w+)\"", dt.split("val CITIES")[1]) if "val CITIES" in dt else []
    r["e3"] = miss
    r["e3b"] = (wc is not None and wc.group(1) == "continent" and cc[:2] == ["continent", "country"]
                and "{ it.lastVisit }, actions.onOpenDay" in ds and ds.count("{ it.lastVisit }, actions.onOpenDay") == 2 and "clickable(enabled = date != null) { date?.let(onOpenDay) }" in dv
                and all(("SortableTable(\"%s\"" % t) in ds for t in ["places", "cities", "world"]))
    # E4
    r["e4"] = (all(t in dv for t in ["ExploredTags.EXPORT_JSON", "ExploredTags.EXPORT_KMZ", "ExploredTags.RESTORE", "ExploredTags.DEDUPE_OK", "ExploredTags.DELETE_RANGE_OK",
                                       "ExploredTags.DELETE_ALL_OK1", "ExploredTags.DELETE_ALL_OK2", 'typed.trim() == "DELETE"', "ExploredTags.REFRESH", "ImportTabView(a, before ="])
               and "actions.data.onDedupePreview()" in ds and "doc.kml" in dt and "<LineString>" in dt and "<Point>" in dt
               and st.count("ExploredCache.markDirty(") >= 3 and "MapsDbDigestBackend(app).clear(); AggregatesStore.rebuild(" in st and "ExploredCache.markDirty(" in df)
    # E5
    r["e5"] = (all(t in ts for t in ["DayNightStrip(", "ActivityStrip(", "StateLegend(", "DayIcons.mode(mode)", '"Slept in "', "DaySegments.blocksOfRows(", "DaySegments.daylight("])
               and all(t in g(s, "SegmentStrips.kt") for t in ["Brush.horizontalGradient", "DayIcons.mode(b.mode)", "StripTags.sleepCity(i)"])
               and "fun gradient(" in seg and "fun sleepCities(" in seg and "fun modeIcon(" in seg)
    # E6
    jt = "".join(s.get(JT + f, "") for f in ["ExploredCacheTest.kt", "ExploredDataTest.kt", "DaySegmentsTest.kt"])
    r["e6"] = all(t in jt for t in ["`a second open recomputes nothing", "an import recomputes only the dates it touches", "equal a full recompute",
                                     "export JSON round-trips", "KMZ is a zip", "never remove a unique record", "delete by dates removes only that range",
                                     "refresh (clear then rebuild) equals a full recompute", "tables sort by any column", "cover the 24 h without overlap", "(mutation)"])
    return r

print("== E1-E6 on the real sources ==")
r = check(root)
ok(r["e1"], "Explored reads through the cache (checksum, schema, live today, maps.db aggregates)", "the cache is bypassed or incomplete")
ok(r["e2"], "subpages: Day, Week, Month | World, Cities, Places, Trips, Insights | Export/Import", "the subpage order or groups are wrong")
ok(not r["e3"] and r["e3b"], "every old field is drawn; World continent first, Cities continent + country first, last visit opens the day", "fields or table rules lost: " + ", ".join(r["e3"]))
ok(r["e4"], "Export/Import: JSON, KMZ, restore, import, duplicates, delete by dates, delete all twice, refresh; deletes mark the cache dirty", "an Export/Import action is missing")
ok(r["e5"], "Timeline: day/night strip, activity strip, legend, transport icons, sleep city", "a Timeline strip part is missing")
ok(r["e6"], "the JVM tests for cache, data ops and segments are present", "a JVM test is missing")
print("== E7: the checks bite ==")
tmp = tempfile.mkdtemp()
try:
    for f in FILES:
        if os.path.exists(os.path.join(root, f)):
            d = os.path.join(tmp, os.path.dirname(f)); os.makedirs(d, exist_ok=True); shutil.copy(os.path.join(root, f), os.path.join(tmp, f))
    def mut(f, a, b):
        p = os.path.join(tmp, f); s = open(p).read(); assert a in s, (f, a); open(p, "w").write(s.replace(a, b, 1)); return s
    def restore(f, s): open(os.path.join(tmp, f), "w").write(s)
    cases = [
        (T + "DayFragment.kt", "AggregatesStore.explore(app, store, first, today, now, zone)", "Aggregates.perDay(store, emptyList(), now, zone).let { AggregatesStore.explore(app, store, first, today, now, zone) }", "e1", "a page re-running perDay fails E1"),
        (T + "ExploredCache.kt", "old.sum == sum", "true", "e1", "a cache that ignores the checksum fails E1"),
        (T + "ExploredStore.kt", "SELECT k, json FROM aggregates WHERE kind = ?", "SELECT k, json FROM digests WHERE kind = ?", "e1", "digests outside maps.db aggregates fail E1"),
        (T + "DayScreen.kt", 'WEEK("Week", 0), MONTH("Month", 0)', 'MONTH("Month", 0), WEEK("Week", 0)', "e2", "a swapped subpage fails E2"),
        (T + "DayScreen.kt", 'IMPORT("Export/Import", 2)', 'IMPORT("Import", 2)', "e2", "the old Import label fails E2"),
        (T + "DayScreen.kt", "Walking distance", "Walk", "e3", "a lost Insights field fails E3"),
        (T + "ExploredData.kt", 'TableCol<PlaceTotal>("visits", "Visits"', 'TableCol<PlaceTotal>("count", "Count"', "e3", "a lost Places column fails E3"),
        (T + "TrackerScreens.kt", "TrackerFormat.coords(r.lat, r.lon)", "\"\"", "e3", "lost Timeline coordinates fail E3"),
        (T + "ExploredData.kt", 'TableCol<WorldRow>("continent", "Continent", false, 1.1f, { it.continent }, { it.continent }),\n        TableCol<WorldRow>("country", "Country", false, 1.3f',
         'TableCol<WorldRow>("country", "Country", false, 1.3f', "e3b", "World without continent first fails E3"),
        (T + "ExploredViews.kt", 'typed.trim() == "DELETE"', "true", "e4", "delete all on one confirmation fails E4"),
        (T + "DayScreen.kt", "actions.data.onDedupePreview()", "{}", "e4", "Insights without the duplicates link fails E4"),
        (T + "ExploredStore.kt", "MapsDbDigestBackend(app).clear(); AggregatesStore.rebuild", "AggregatesStore.rebuild", "e4", "a refresh that keeps the cache fails E4"),
        (T + "TrackerScreens.kt", "ActivityStrip(blocks, from, to)", "", "e5", "a Timeline without the activity strip fails E5"),
        (T + "SegmentStrips.kt", "DayIcons.mode(b.mode)", "0", "e5", "a strip without transport icons fails E5"),
        (JT + "ExploredCacheTest.kt", "`a second open recomputes nothing", "`opens", "e6", "a missing cache JVM test fails E6"),
    ]
    for f, a, b, key, label in cases:
        s = mut(f, a, b); res = check(tmp)[key]
        ok((bool(res) if key == "e3" else not res), label, "E7 missed: " + label); restore(f, s)
    rr = check(tmp)
    ok(all(rr[k] for k in ["e1", "e2", "e3b", "e4", "e5", "e6"]) and not rr["e3"], "the pristine copy passes again", "the pristine copy fails")
finally:
    shutil.rmtree(tmp)
print(f"\n{'FAIL' if fails else 'PASS'}: test-explored-trips ({len(fails)} failing)")
sys.exit(1 if fails else 0)
PY
