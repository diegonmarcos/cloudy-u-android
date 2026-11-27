#!/usr/bin/env bash
# #1974 Cloud Tasks: Home is the Overview only (Projects and Library are owner-named removals), the Home selection filters the Backlog, the Backlog is
# Tasks' first page, Files (RoadMap | Assets) took its bottom-nav slot. Five tests, each with a mutation that must turn it red.
# Usage: test-tasks-1974-nav.sh     checks the tree, then runs every mutation on an in-memory copy
#   JUNIT_OUT=<file>   also writes a JUnit XML (the CI job reads the verdict from it, never from this script's exit code)
set -uo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export ROOT="$(cd "$here/../.." && pwd)" JUNIT_OUT="${JUNIT_OUT:-}"
exec python3 -I - <<'PY'
import json, os, re, sys
from xml.sax.saxutils import quoteattr

ROOT, JUNIT = os.environ["ROOT"], os.environ.get("JUNIT_OUT", "")
K = "ac_cloud-tasks/app/src/main/java/com/diegonmarcos/cloudagenda/"
FILES = {"tasks": "ac_cloud-tasks/build.json", "nav": K + "ui/Nav.kt", "app": K + "ui/TasksApp.kt", "pages": K + "ui/FilesPages.kt", "data": K + "data/BacklogData.kt", "home": K + "ui/HomePage.kt"}

def load():
    d = {}
    for k, p in FILES.items():
        with open(os.path.join(ROOT, p), encoding="utf-8") as h: d[k] = h.read()
    return d
def code(t): return re.sub(r"//[^\n]*|/\*.*?\*/", "", t, flags=re.S)
def ui(f): return json.loads(f["tasks"])["ui"]
def pages(f, sec): return [p["id"] for s in ui(f)["sections"] if s["id"] == sec for p in s["pages"]]

def t_bottom_order(f):
    u = ui(f); secs = {s["id"]: s for s in u["sections"]}; bad = []
    if u.get("bottom_nav") != ["inboxes", "files", "home", "tasks", "clock"]: bad.append("bottom_nav is %s" % u.get("bottom_nav"))
    if [secs[i]["label"] for i in u.get("bottom_nav", []) if i in secs] != ["Inbox", "Files", "Home", "Tasks", "Clock"]: bad.append("island labels differ")
    if "backlogs" in secs: bad.append("a backlogs section is still declared (Backlog is Tasks' first page)")
    return bad

def t_tasks_pages(f):
    bad = []
    if pages(f, "tasks") != ["backlog", "calendar", "list", "gantt", "kanban", "mindmap"]: bad.append("Tasks pages are %s" % pages(f, "tasks"))
    ids = [p["label"] for s in ui(f)["sections"] if s["id"] == "tasks" for p in s["pages"]][:1]
    if ids != ["Backlog"]: bad.append("the first Tasks tab is %s, not Backlog" % ids)
    app = code(f["app"])
    if not re.search(r"page == Ids\.PAGE_BACKLOG\)[^\n]*BacklogPage\(", app): bad.append("the shell does not draw BacklogPage on Tasks > Backlog")
    return bad

def t_files_pages(f):
    bad = []
    if pages(f, "files") != ["roadmap", "assets"]: bad.append("Files pages are %s" % pages(f, "files"))
    app = code(f["app"])
    if not re.search(r"Ids\.PAGE_ROADMAP\s*->\s*RoadMapPage\(", app) or not re.search(r"Ids\.PAGE_ASSETS\s*->\s*AssetsPage\(", app): bad.append("the shell does not draw RoadMapPage and AssetsPage")
    if "4___ASSETS___" not in f["data"] or "ASSETS_DIR" not in f["data"]: bad.append("Assets does not read the 4___ASSETS___ tree")
    return bad

def t_removals(f):
    u = ui(f); bad = []
    if pages(f, "home") != ["overview"]: bad.append("Home pages are %s" % pages(f, "home"))
    for k in ("projects", "library"):
        if any(p["id"] == k for s in u["sections"] for p in s["pages"]): bad.append("the removed page %s is declared again" % k)
    rem = {k for k in u.get("nav_removed", {}) if not k.startswith("_")}
    if rem != {"projects/projects", "library/library"}: bad.append("nav_removed is %s" % sorted(rem))
    m = u.get("nav_mapping", {})
    if m.get("backlogs/backlogs") != "tasks/backlog": bad.append("nav_mapping backlogs/backlogs -> %s" % m.get("backlogs/backlogs"))
    al = u.get("route_aliases", {})
    if al.get("backlogs") != ["tasks", "backlog"] or al.get("backlogs/backlogs") != ["tasks", "backlog"]: bad.append("route_aliases lost the old Backlog route")
    nav = code(f["nav"])
    if not re.search(r'"backlogs/backlogs"\s+to\s+\(TASKS\s+to\s+PAGE_BACKLOG\)', nav): bad.append("Ids.ALIASES does not resolve backlogs/backlogs")
    return bad

def t_selector_filters_backlog(f):
    bad = []; app, pg, data, home = code(f["app"]), code(f["pages"]), code(f["data"]), code(f["home"])
    if not re.search(r"BacklogPage\(backlog,\s*sel,", app): bad.append("the shell does not hand the Home selection to the Backlog page")
    if not re.search(r"val kept = remember\(snap, sel\) \{ Backlog\.filter\(snap\.tickets, sel\) \}", pg): bad.append("the Backlog page does not filter its tickets by the selection")
    if "if (sel.all) tickets else tickets.filter { it.inSelection(sel) }" not in data: bad.append("Backlog.filter is not 'all = everything, else the selected projects'")
    if "fun inSelection(sel: ProjectSelection): Boolean = sel.all || keys.any { it in sel.ids }" not in data: bad.append("a ticket's selection test is not its scope identity (project / family / repos)")
    if "metadata" not in data or '"scope"' not in data or '"names"' not in data: bad.append("the ticket identity is not read from metadata.scope")
    if "Backlog.projectNames(" not in home: bad.append("Home's chips do not include the backlog's projects")
    return bad

TESTS = [("bottom_nav_order", t_bottom_order), ("tasks_pages_order", t_tasks_pages), ("files_pages", t_files_pages), ("owner_named_removals", t_removals), ("selector_filters_backlog", t_selector_filters_backlog)]

def sub(key, old, new):
    return (key, old, new)
MUTATIONS = [
    ("m1-island-reordered", "bottom_nav_order", "tasks", '"bottom_nav": ["inboxes", "files", "home", "tasks", "clock"]', '"bottom_nav": ["files", "inboxes", "home", "tasks", "clock"]'),
    ("m2-backlog-not-first", "tasks_pages_order", "tasks", '''          {
            "id": "backlog",
            "label": "Backlog",
            "icon": "bookmark"
          },
          {
            "id": "calendar",''', '''          {
            "id": "calendar",'''),
    ("m3-backlog-not-drawn", "tasks_pages_order", "app", "BacklogPage(backlog, sel,", "CollectionPage(backlog, sel,"),
    ("m4-files-loses-roadmap", "files_pages", "tasks", '''          {
            "id": "roadmap",
            "label": "RoadMap",
            "icon": "route"
          },
''', ''),
    ("m5-assets-not-drawn", "files_pages", "app", "Ids.PAGE_ASSETS -> AssetsPage(", "Ids.PAGE_ASSETS -> RoadMapPage("),
    ("m6-home-gets-projects-back", "owner_named_removals", "tasks", '''            "id": "overview",
            "label": "Overview",
            "icon": "home"
          }''', '''            "id": "overview",
            "label": "Overview",
            "icon": "home"
          },
          {
            "id": "projects",
            "label": "Projects",
            "icon": "folder"
          }'''),
    ("m7-backlog-alias-dead", "owner_named_removals", "nav", '"backlogs/backlogs" to (TASKS to PAGE_BACKLOG),', ''),
    ("m8-backlog-ignores-selection", "selector_filters_backlog", "pages", "{ Backlog.filter(snap.tickets, sel) }", "{ snap.tickets }"),
    ("m9-all-means-none", "selector_filters_backlog", "data", "if (sel.all) tickets else tickets.filter", "if (false) tickets else tickets.filter"),
    ("m10-selection-not-passed", "selector_filters_backlog", "app", "BacklogPage(backlog, sel,", "BacklogPage(backlog, ProjectSelection.ALL,"),
]

def run(f): return {n: fn(f) for n, fn in TESTS}
base = load(); results = run(base); fails = 0; cases = []
for n, r in results.items():
    print(("ok   " if not r else "FAIL ") + n + ("" if not r else ": " + "; ".join(r))); fails += bool(r); cases.append(("tests", n, "; ".join(r) or None))
for name, target, key, old, new in MUTATIONS:
    f = dict(base)
    if old not in f[key]:
        msg = "mutation text not found in %s (tester out of date)" % FILES[key]; print("FAIL " + name + ": " + msg); fails += 1; cases.append(("mutations", name, msg)); continue
    f[key] = f[key].replace(old, new, 1); red = [n for n, r in run(f).items() if r]; good = target in red
    msg = None if good else "mutation did not turn %s red (red: %s)" % (target, red)
    print(("ok   " if good else "FAIL ") + name + " turns " + target + " red" + ("" if good else " -- " + msg)); fails += not good; cases.append(("mutations", name, msg))
if JUNIT:
    with open(JUNIT, "w", encoding="utf-8") as h:
        h.write('<?xml version="1.0" encoding="UTF-8"?>\n<testsuite name="tasks-1974-nav" tests="%d" failures="%d">\n' % (len(cases), sum(1 for c in cases if c[2])))
        for cl, n, m in cases: h.write('  <testcase classname=%s name=%s>%s</testcase>\n' % (quoteattr(cl), quoteattr(n), "" if not m else "<failure message=%s/>" % quoteattr(m)))
        h.write("</testsuite>\n")
print("all passed" if not fails else "%d failed" % fails); sys.exit(1 if fails else 0)
PY
