#!/usr/bin/env bash
# #1964 Cloud Tasks: opens on Home, the island has Clock and no Library, Library stays reachable (Home page + old routes),
# and the Clock's subpages equal Cloud Calc's Tools > Clock. Four tests, each with a mutation that must turn it red.
# Usage: test-tasks-1964-nav.sh            checks the tree, then runs every mutation on a scratch copy
#   JUNIT_OUT=<file>   also writes a JUnit XML (the CI job reads the verdict from it, never from this script's exit code)
set -uo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export ROOT="$(cd "$here/../.." && pwd)" JUNIT_OUT="${JUNIT_OUT:-}"
exec python3 -I - <<'PY'
import json, os, re, shutil, sys, tempfile
from xml.sax.saxutils import quoteattr

ROOT, JUNIT = os.environ["ROOT"], os.environ.get("JUNIT_OUT", "")
FILES = {
    "tasks": "ac_cloud-tasks/build.json",
    "nav": "ac_cloud-tasks/app/src/main/java/com/diegonmarcos/cloudagenda/ui/Nav.kt",
    "app": "ac_cloud-tasks/app/src/main/java/com/diegonmarcos/cloudagenda/ui/TasksApp.kt",
    "calc": "ac_cloud-calc/build.json",
    "subpages": "ab_cloud-libs-shared/libs/clock-tools/core/src/main/kotlin/com/diegonmarcos/clocktools/ClockSubpages.kt",
    "full": "ab_cloud-libs-shared/libs/clock-tools/src/main/java/com/diegonmarcos/clocktools/ui/WorldClock.kt",
    "icons": "ab_cloud-libs-shared/libs/ui-icons/src/main/kotlin/com/diegonmarcos/superapp/uiicons/UiIcons.kt",
}

def load(root):
    d = {}
    for k, p in FILES.items():
        with open(os.path.join(root, p), encoding="utf-8") as h: d[k] = h.read()
    return d

def code(t): return re.sub(r"//[^\n]*|/\*.*?\*/", "", t, flags=re.S)

# each test returns a list of findings; empty = pass
def t_start_is_home(f):
    ui = json.loads(f["tasks"])["ui"]; bad = []
    if ui.get("default_section") != "home": bad.append("default_section is %r, not home" % ui.get("default_section"))
    if ui.get("default_section") not in ui.get("bottom_nav", []): bad.append("default_section is not on the island")
    if "nav.default()" not in code(f["app"]): bad.append("the shell does not start from nav.default()")
    return bad

def t_nav_has_clock_no_library(f):
    ui = json.loads(f["tasks"])["ui"]; bad = []
    bar = ui.get("bottom_nav", []); secs = {s["id"]: s for s in ui["sections"]}
    if bar != ["inboxes", "backlogs", "home", "tasks", "clock"]: bad.append("island is %s" % bar)
    if "library" in bar: bad.append("Library is still on the island")
    c = secs.get("clock", {})
    if c.get("label") != "Clock" or c.get("icon") != "clock": bad.append("Clock section is %r" % {k: c.get(k) for k in ("label", "icon")})
    if '"clock" to ' not in f["icons"]: bad.append("icon 'clock' is not in the declared icon set (libs:ui-icons)")
    return bad

def t_library_reachable(f):
    ui = json.loads(f["tasks"])["ui"]; bad = []
    secs = {s["id"]: s for s in ui["sections"]}
    home = [p["id"] for p in secs.get("home", {}).get("pages", [])]
    if "library" not in home: bad.append("Home has no Library page (%s)" % home)
    if "library" in secs: bad.append("a library section is still declared (it would be a second copy)")
    al = ui.get("route_aliases", {})
    if al.get("library") != ["home", "library"] or al.get("tasks/clock") != ["clock", "clock"]: bad.append("build.json route_aliases lost an old route")
    nav, app = code(f["nav"]), code(f["app"])
    if not re.search(r'"library"\s+to\s+\(HOME\s+to\s+PAGE_LIBRARY\)', nav): bad.append("Ids.ALIASES does not resolve library")
    if not re.search(r'"tasks/clock"\s+to\s+\(CLOCK\s+to\s+PAGE_CLOCK\)', nav): bad.append("Ids.ALIASES does not resolve tasks/clock")
    if not re.search(r"Ids\.PAGE_LIBRARY\s*->[^\n]*CollectionPage\(Ids\.PAGE_LIBRARY", app): bad.append("Home does not draw the Library collection")
    if "Ids.resolve(" not in app: bad.append("the shell does not route through Ids.resolve")
    return bad

def t_clock_matches_calc(f):
    bad = []
    modes = json.loads(f["calc"])["ui"]["modes"]
    calc = [m["id"] for m in modes if m.get("tab") == "clock"]
    m = re.search(r"IDS[^=]*=\s*listOf\(([^)]*)\)", code(f["subpages"]))
    lib = re.findall(r'"([\w-]+)"', m.group(1)) if m else []
    if not lib: bad.append("ClockSubpages.IDS not found in the lib")
    if calc != lib: bad.append("Calc's Tools > Clock %s != the lib's ClockSubpages.IDS %s" % (calc, lib))
    full = code(f["full"]); body = full[full.index("fun FullClock"):] if "fun FullClock" in full else ""
    if "ClockSubpages.IDS" not in body: bad.append("FullClock does not read ClockSubpages.IDS")
    if re.search(r'listOf\(\s*"world"', body): bad.append("FullClock declares its own subpage list")
    if not re.search(r"Ids\.CLOCK\s*->\s*[\w.]*FullClock\(", code(f["app"])): bad.append("Tasks' Clock does not draw the lib's FullClock")
    t = json.loads(f["tasks"])
    if t["modules"].get("libs:clock-tools", {}).get("dir") != "../ab_cloud-libs-shared/libs/clock-tools": bad.append("libs:clock-tools is not mapped by reference")
    if "object ClockSubpages" in code(f["app"]) + code(f["nav"]): bad.append("a copy of ClockSubpages lives in Tasks")
    return bad

TESTS = [("start_page_is_home", t_start_is_home), ("nav_has_clock_and_no_library", t_nav_has_clock_no_library),
         ("library_still_reachable", t_library_reachable), ("clock_subpages_match_calc", t_clock_matches_calc)]

# (name, test that must go red, file key, old, new)
MUTATIONS = [
    ("m1-opens-on-tasks", "start_page_is_home", "tasks", '"default_section": "home"', '"default_section": "tasks"'),
    ("m2-library-back-on-island", "nav_has_clock_and_no_library", "tasks", '"bottom_nav": ["inboxes", "backlogs", "home", "tasks", "clock"]', '"bottom_nav": ["inboxes", "backlogs", "home", "tasks", "library"]'),
    ("m3-home-loses-library", "library_still_reachable", "tasks", '''            "label": "Projects",
            "icon": "folder"
          },
          {
            "id": "library",
            "label": "Library",
            "icon": "folder_open"
          }''', '''            "label": "Projects",
            "icon": "folder"
          }'''),
    ("m4-old-library-route-dead", "library_still_reachable", "nav", '"library" to (HOME to PAGE_LIBRARY),', ''),
    ("m5-calc-clock-drifts", "clock_subpages_match_calc", "calc", '"id": "bedtime",\n        "label": "Bedtime",\n        "tab": "clock"', '"id": "bedtime",\n        "label": "Bedtime",\n        "tab": "clockx"'),
    ("m6-lib-list-drifts", "clock_subpages_match_calc", "subpages", ', "bedtime")', ')'),
    ("m7-fullclock-own-list", "clock_subpages_match_calc", "full", 'com.diegonmarcos.clocktools.ClockSubpages.IDS.map { it to labels.getValue(it) }', 'listOf("world", "alarms").map { it to labels.getValue(it) }'),
]

def run(f): return {n: fn(f) for n, fn in TESTS}

base = load(ROOT); results = run(base); fails = 0
cases = []   # (classname, name, failure-or-None)
for n, r in results.items():
    print(("ok   " if not r else "FAIL ") + n + ("" if not r else ": " + "; ".join(r)))
    fails += bool(r); cases.append(("tests", n, "; ".join(r) or None))
for name, target, key, old, new in MUTATIONS:
    f = dict(base)
    if old not in f[key]:
        msg = "mutation text not found in %s (tester out of date)" % FILES[key]; print("FAIL " + name + ": " + msg); fails += 1
        cases.append(("mutations", name, msg)); continue
    f[key] = f[key].replace(old, new, 1)
    res = run(f); red = [n for n, r in res.items() if r]
    good = target in red
    msg = None if good else "mutation did not turn %s red (red: %s)" % (target, red)
    print(("ok   " if good else "FAIL ") + name + " turns " + target + " red" + ("" if good else " -- " + msg))
    fails += not good; cases.append(("mutations", name, msg))
if JUNIT:
    with open(JUNIT, "w", encoding="utf-8") as h:
        h.write('<?xml version="1.0" encoding="UTF-8"?>\n<testsuite name="tasks-1964-nav" tests="%d" failures="%d">\n' % (len(cases), sum(1 for c in cases if c[2])))
        for cl, n, m in cases:
            h.write('  <testcase classname=%s name=%s>%s</testcase>\n' % (quoteattr(cl), quoteattr(n), "" if not m else "<failure message=%s/>" % quoteattr(m)))
        h.write("</testsuite>\n")
print("all passed" if not fails else "%d failed" % fails)
sys.exit(1 if fails else 0)
PY
