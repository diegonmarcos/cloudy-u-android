#!/usr/bin/env bash
# #1975 field parity: the three Trip blocks MOVED from Cloud Me > Pers > Achieves > Geo (frozen in test/data/geo-trip-blocks-1975.json) to Cloud Tasks'
# Files > RoadMap (app/src/main/assets/trip-roadmap.json). Every label, subtitle, icon, accent, body and target is held; only the targets change grammar
# (page:X -> cloudme:page:X, extapp:cloud-tasks -> tasks:tasks/calendar), and the blocks are gone from Cloud Me's geo.json. Every check has a mutation that must turn it red.
# Usage: test-tasks-trip-parity.sh   (JUNIT_OUT=<file> also writes a JUnit XML)
set -uo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export ROOT="$(cd "$here/../.." && pwd)" JUNIT_OUT="${JUNIT_OUT:-}"
exec python3 -I - <<'PY'
import copy, json, os, sys
from xml.sax.saxutils import quoteattr

ROOT, JUNIT = os.environ["ROOT"], os.environ.get("JUNIT_OUT", "")
def rd(p):
    with open(os.path.join(ROOT, p), encoding="utf-8") as h: return h.read()
FILES = {"old": "ac_cloud-tasks/test/data/geo-trip-blocks-1975.json", "new": "ac_cloud-tasks/app/src/main/assets/trip-roadmap.json",
         "geo": "ac_cloud-me/data/ui/pers/geo/geo-summary.json", "kt": "ac_cloud-tasks/app/src/main/java/com/diegonmarcos/cloudagenda/ui/FilesPages.kt",
         "app": "ac_cloud-tasks/app/src/main/java/com/diegonmarcos/cloudagenda/ui/TasksApp.kt"}
def load(): return {k: rd(p) for k, p in FILES.items()}

def conv(t):
    if t == "extapp:cloud-tasks": return "tasks:tasks/calendar"
    if t.startswith("page:"): return "cloudme:" + t
    return t

def flat(blocks, convert=True):
    """every field of the blocks as path -> value, targets converted to the new grammar"""
    out = {}
    def walk(o, p):
        if isinstance(o, dict):
            for k, v in o.items(): walk(v, p + "/" + k)
        elif isinstance(o, list):
            for i, v in enumerate(o): walk(v, p + "/" + str(i))
        else: out[p] = conv(o) if convert and p.endswith("target") and isinstance(o, str) else o
    walk(blocks, ""); return out

def t_parity(f):
    old, new = flat(json.loads(f["old"])), flat(json.loads(f["new"])["blocks"])
    bad = ["missing or changed %s: %r -> %r" % (k, v, new.get(k)) for k, v in old.items() if new.get(k) != v]
    bad += ["extra %s" % k for k in new if k not in old]
    if len(old) < 30: bad.append("the frozen old blocks hold only %d fields (tester out of date)" % len(old))
    return bad

def t_targets(f):
    new = json.loads(f["new"]); bad = []
    for k, v in flat(new["blocks"], False).items():
        if k.endswith("target") and not (v.startswith("cloudme:page:") or v.startswith("tasks:") or v.startswith("extapp:")): bad.append("%s is %r, not a Cloud Me deep link / own page / app" % (k, v))
        if v.startswith("page:"): bad.append("%s keeps the bare Cloud Me target %r" % (k, v))
    cal = [l for b in new["blocks"] for l in b.get("links", []) if l["label"] == "Calendar"]
    if not cal or cal[0]["target"] != "tasks:tasks/calendar": bad.append("Calendar does not open Cloud Tasks' own Calendar")
    for a in ("cloudme", "cloud-nav", "cloud-myterminal"):
        if a not in new.get("apps", {}): bad.append("apps has no package for " + a)
    return bad

def t_moved(f):
    labels = [b.get("title") for b in json.loads(f["geo"])]
    return ["Cloud Me geo.json still holds the block %r (it is a MOVE)" % t for t in ("Trip management", "Before you go") if t in labels] + \
           (["Cloud Me geo.json still holds the checklist cards"] if any(b.get("kind") == "cards" and any(i.get("title") == "Documents & visas" for i in b.get("items", [])) for b in json.loads(f["geo"])) else [])

def t_drawn(f):
    bad = []
    if "TripSection(trip, onTarget)" not in f["kt"]: bad.append("RoadMapPage does not draw the trip section")
    if "RoadMapPage(backlog, sel, env.trip" not in f["app"]: bad.append("the shell does not hand the trip roadmap to the RoadMap page")
    return bad

TESTS = [("trip_field_parity", t_parity), ("trip_targets_are_deep_links", t_targets), ("blocks_moved_out_of_cloud_me", t_moved), ("trip_section_is_drawn", t_drawn)]

def jmut(key, fn):
    def m(f):
        d = json.loads(f[key]); fn(d); f = dict(f); f[key] = json.dumps(d, ensure_ascii=False); return f
    return m
def sub(key, a, b):
    def m(f):
        assert a in f[key], "mutation text not found in " + FILES[key]
        f = dict(f); f[key] = f[key].replace(a, b, 1); return f
    return m
def drop_field(d): del d["blocks"][2]["items"][1]["accent"]
def edit_sub(d): d["blocks"][0]["links"][2]["label"] = "Map"
def bare(d): d["blocks"][2]["items"][0]["target"] = "page:buro/wallet"
def cal_ext(d): d["blocks"][0]["links"][1]["target"] = "extapp:cloud-tasks"
def readd(d):
    d.append({"kind": "note", "title": "Before you go", "body": "x"})
MUT = [("m1-field-dropped", "trip_field_parity", jmut("new", drop_field)), ("m2-label-edited", "trip_field_parity", jmut("new", edit_sub)),
       ("m3-bare-cloud-me-target", "trip_targets_are_deep_links", jmut("new", bare)), ("m4-calendar-not-own", "trip_targets_are_deep_links", jmut("new", cal_ext)),
       ("m5-block-back-in-geo", "blocks_moved_out_of_cloud_me", jmut("geo", readd)),
       ("m6-section-not-drawn", "trip_section_is_drawn", sub("kt", "TripSection(trip, onTarget)", "")),
       ("m7-trip-not-passed", "trip_section_is_drawn", sub("app", "RoadMapPage(backlog, sel, env.trip", "RoadMapPage(backlog, sel, TripRoadmap.EMPTY"))]

base = load(); fails = 0; cases = []
for n, fn in TESTS:
    r = fn(base); print(("ok   " if not r else "FAIL ") + n + ("" if not r else ": " + "; ".join(r[:4]))); fails += bool(r); cases.append(("tests", n, "; ".join(r[:4]) or None))
for name, target, mut in MUT:
    f = mut(base); red = [n for n, fn in TESTS if fn(f)]; good = target in red
    msg = None if good else "mutation did not turn %s red (red: %s)" % (target, red)
    print(("ok   " if good else "FAIL ") + name + " turns " + target + " red" + ("" if good else " -- " + msg)); fails += not good; cases.append(("mutations", name, msg))
if JUNIT:
    with open(JUNIT, "w", encoding="utf-8") as h:
        h.write('<?xml version="1.0" encoding="UTF-8"?>\n<testsuite name="tasks-trip-parity" tests="%d" failures="%d">\n' % (len(cases), sum(1 for c in cases if c[2])))
        for cl, n, m in cases: h.write('  <testcase classname=%s name=%s>%s</testcase>\n' % (quoteattr(cl), quoteattr(n), "" if not m else "<failure message=%s/>" % quoteattr(m)))
        h.write("</testsuite>\n")
print("all passed" if not fails else "%d failed" % fails); sys.exit(1 if fails else 0)
PY
