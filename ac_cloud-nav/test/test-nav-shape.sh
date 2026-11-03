#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════╗
# ║ #868 cloud-nav's nav is the fleet's: an island, NavDecl and page strips
# ╚══════════════════════════════════════════════════════════════════╝
#
# What this certifies, statically (the nav is data, the bar and the strips are libs:bottomnav's):
#   S1 build.json::ui is ONE declaration: bottom_nav = the five island sections (Configs replaced by Tracker, #1156), default_section routes,
#      Explored carries Trips|Maps, Tracker carries Timeline|Steps|Sleep (#1156) and Configs, no longer in the island, keeps Tracker|APIs|Update|Cache|Layers|About as
#      its pages; the old nav_tabs / default_tab keys are gone.
#   S2 the app compiles libs:bottomnav and bakes UI_BOTTOM_NAV + UI_SECTIONS_B64 + UI_DEFAULT_SECTION.
#   S3 MainActivity (Compose) draws the bar with libs:ui-nav-bars's NavBarBottomHost fed by NavConfig.decl (NavDecl),
#      not a BottomNavigationView, and no layout XML shell remains.
#   S4 the Explored / Tracker hosts (PagedSectionFragment) and Configs draw their strips with NavBarTopSecondRowView, not a TabLayout.
#   S5 no hand-rolled nav widget anywhere in the app or libs:maps (nav-shape-guard N3).
# Each check is then run against planted mutations of the real sources; a mutation that leaves
# every check green fails this tester.
set -eu
APP_DIR="$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)"
export APP_DIR

python3 - <<'PY'
import json, os, re, sys

APP = os.environ["APP_DIR"]
CFG = json.loads(r'''{
 "bottom": [
  "routes",
  "navigation",
  "places",
  "explored",
  "tracker"
 ],
 "default": "routes",
 "pages": {
  "explored": [
   "trips",
   "maps"
  ],
  "tracker": [
   "timeline",
   "steps",
   "sleep"
  ],
  "configs": [
   "general",
   "perms",
   "about",
   "tracker",
   "apis",
   "update",
   "cache",
   "layers",
   "device"
  ]
 },
 "gone_ui_keys": [
  "nav_tabs",
  "default_tab"
 ],
 "gradles": [
  "build.gradle",
  "app/build.gradle"
 ],
 "src_roots": [
  "app/src/main",
  "../ab_cloud-libs-shared/libs/maps/src/main/java/com/diegonmarcos/cloudnav/maps"
 ],
 "must": {
  "app/src/main/java/com/diegonmarcos/cloudnav/MainActivity.kt": [
   "NavBarBottomHost",
   "NavConfig.decl",
   "islandEntries()",
   "ExploredFragment()",
   "TrackerFragment()"
  ],
  "app/src/main/java/com/diegonmarcos/cloudnav/NavConfig.kt": [
   "NavDecl.fromBuildConfig"
  ],
  "app/src/main/java/com/diegonmarcos/cloudnav/configs/ConfigsFragment.kt": [
   "MapsPageStrip.create",
   "NavBarTopSecondRowView"
  ],
  "app/src/main/java/com/diegonmarcos/cloudnav/PagedSectionFragment.kt": [
   "MapsPageStrip.create",
   "NavConfig.decl"
  ],
  "app/src/main/java/com/diegonmarcos/cloudnav/ExploredFragment.kt": [
   "DayFragment()",
   "ExploredMapsFragment()"
  ],
  "app/src/main/java/com/diegonmarcos/cloudnav/TrackerFragment.kt": [
   "TrackerTimelineFragment()",
   "TrackerStepsFragment()",
   "TrackerSleepFragment()"
  ],
  "../ab_cloud-libs-shared/libs/maps/src/main/java/com/diegonmarcos/cloudnav/maps/MapsTimelineTabsFragment.kt": [
   "MapsPageStrip.create"
  ],
  "../ab_cloud-libs-shared/libs/maps/src/main/java/com/diegonmarcos/cloudnav/maps/MapsPageStrip.kt": [
   "NavBarTopSecondRowView"
  ]
 },
 "mustnot": {
  "app/src/main/java/com/diegonmarcos/cloudnav/NavConfig.kt": [
   "UI_NAV_TABS_B64",
   "UI_DEFAULT_TAB"
  ]
 },
 "deleted": [
  "app/src/main/res/layout/activity_main.xml"
 ],
 "mutate_host": "app/src/main/java/com/diegonmarcos/cloudnav/MainActivity.kt",
 "must_token_mutations": [
  [
   "app/src/main/java/com/diegonmarcos/cloudnav/MainActivity.kt",
   "NavBarBottomHost"
  ],
  [
   "app/src/main/java/com/diegonmarcos/cloudnav/configs/ConfigsFragment.kt",
   "MapsPageStrip.create"
  ],
  [
   "../ab_cloud-libs-shared/libs/maps/src/main/java/com/diegonmarcos/cloudnav/maps/MapsPageStrip.kt",
   "NavBarTopSecondRowView"
  ],
  [
   "app/src/main/java/com/diegonmarcos/cloudnav/NavConfig.kt",
   "NavDecl.fromBuildConfig"
  ],
  [
   "app/src/main/java/com/diegonmarcos/cloudnav/PagedSectionFragment.kt",
   "MapsPageStrip.create"
  ],
  [
   "app/src/main/java/com/diegonmarcos/cloudnav/TrackerFragment.kt",
   "TrackerStepsFragment()"
  ]
 ]
}''')
NAV_SHAPE = os.path.join(APP, "..", "1_cicd", "src", "data", "nav-shape.json")
DEFAULT_FORBIDDEN = [
    r"\bBottomNavigationView\b", r"\bNavigationBar\s*[({]", r"\bNavigationRail\s*[({]",
    r"\bBottomAppBar\b", r"\bTabLayout\b", r"\bScrollableTabRow\s*\(", r"\bPrimaryTabRow\s*\(",
    r"(?<![A-Za-z])TabRow\s*\(",
]
try:
    FORBIDDEN = [f["re"] for f in json.load(open(NAV_SHAPE))["forbidden"]]
except (OSError, ValueError, KeyError):
    FORBIDDEN = DEFAULT_FORBIDDEN


def code(text):
    """Kotlin/XML with comments blanked (string literals kept), so prose cannot satisfy a check."""
    out, i, n = [], 0, len(text)
    while i < n:
        if text.startswith('"""', i):
            j = text.find('"""', i + 3); j = n if j < 0 else j + 3
            out.append(text[i:j]); i = j; continue
        c = text[i]
        if c == '"':
            j = i + 1
            while j < n and text[j] not in '"\n':
                j += 2 if text[j] == "\\" else 1
            out.append(text[i:j + 1]); i = j + 1; continue
        if text.startswith("//", i):
            j = text.find("\n", i); i = n if j < 0 else j; continue
        if text.startswith("/*", i):
            j = text.find("*/", i + 2); i = n if j < 0 else j + 2; continue
        if text.startswith("<!--", i):
            j = text.find("-->", i + 4); i = n if j < 0 else j + 3; continue
        out.append(c); i += 1
    return "".join(out)


def load():
    files = {}
    for root in CFG["src_roots"]:
        for d, dirs, names in os.walk(os.path.join(APP, root)):
            dirs[:] = [x for x in dirs if x not in ("build", ".gradle", "test", "androidTest")]
            for nme in names:
                if nme.endswith((".kt", ".java", ".xml")):
                    p = os.path.join(d, nme)
                    files[os.path.relpath(p, APP)] = open(p, encoding="utf-8", errors="replace").read()
    for rel in CFG["gradles"] + ["build.json"]:
        files[rel] = open(os.path.join(APP, rel), encoding="utf-8").read()
    return files


def page_ids(pages):
    for p in pages or []:
        yield p["id"]
        yield from page_ids(p.get("pages"))


def checks(f):
    bad = []
    def need(label, ok):
        if not ok:
            bad.append(label)
    try:
        ui = json.loads(f["build.json"])["ui"]
        mods = json.loads(f["build.json"]).get("modules") or {}
    except (ValueError, KeyError, TypeError):
        return ["build.json is unreadable"]
    secs = {s["id"]: s for s in ui.get("sections") or []}
    bar = ui.get("bottom_nav") or []
    need("N1 ui.bottom_nav is exactly %s" % CFG["bottom"], bar == CFG["bottom"])
    need("N1 ui.bottom_nav holds at most 5 ids", 0 < len(bar) <= 5)
    need("N1 every ui.bottom_nav id is a ui.sections id", all(b in secs for b in bar))
    need("N1 ui.default_section is %r and is in ui.bottom_nav" % CFG["default"],
         ui.get("default_section") == CFG["default"] and CFG["default"] in bar)
    for sid, want in CFG.get("pages", {}).items():
        need("ui.sections[%s].pages are %s" % (sid, want),
             sid in secs and [p["id"] for p in secs[sid].get("pages") or []] == want)
    for old in CFG.get("gone_ui_keys", []):
        need("the old nav key ui.%s is gone (one declaration, not two)" % old, old not in ui)
    gradle = "\n".join(code(f[g]) for g in CFG["gradles"])
    need("N2 the app compiles project(':libs:bottomnav')", re.search(r"project\(\s*['\"]:libs:bottomnav['\"]", gradle) is not None)
    need("N2 build.json::modules declares libs:bottomnav", "libs:bottomnav" in mods)
    for field in ("UI_BOTTOM_NAV", "UI_SECTIONS_B64", "UI_DEFAULT_SECTION"):
        need("N4 gradle bakes %s" % field, field in gradle)
    need("N4 gradle reads ui.bottom_nav and ui.sections", "bottom_nav" in gradle and "sections" in gradle)
    srcs = {k: code(v) for k, v in f.items() if k.endswith((".kt", ".java", ".xml"))}
    for rel, tokens in CFG["must"].items():
        text = srcs.get(rel, "")
        for t in tokens:
            need("%s uses %s" % (os.path.basename(rel), t), t in text)
    for rel, patterns in CFG.get("mustnot", {}).items():
        text = srcs.get(rel, "")
        for pat in patterns:
            need("%s no longer has /%s/" % (os.path.basename(rel), pat), re.search(pat, text) is None)
    for rel in CFG.get("deleted", []):
        need("%s is deleted (the island replaced it)" % rel, rel not in f)
    for rel, text in srcs.items():
        for pat in FORBIDDEN:
            m = re.search(pat, text)
            if m:
                need("N3 %s draws its own nav (%s)" % (rel, m.group(0).strip()), False)
    declared = set(secs) | {i for s in secs.values() for i in page_ids(s.get("pages"))}
    en = CFG.get("enum")
    if en:
        src = f.get(en["file"], "")
        m = re.search(r"enum class %s\b[^{]*\{(.*?)\n\}" % re.escape(en["name"]), code(src), re.S)
        entries = re.findall(r"^\s*([A-Z][A-Z0-9_]*)\s*\(", m.group(1), re.M) if m else []
        need("%s is found in %s" % (en["name"], en["file"]), bool(entries))
        for e in entries:
            need("%s.%s is declared in ui.sections (as id %r)" % (en["name"], e, e.lower()), e.lower() in declared)
    for rel, text in srcs.items():
        for m in re.finditer(r"\b(?:byId|section|goSection|openSection)\(\s*\"([^\"\n]+)\"", text):
            need("N4 %s names section %r which build.json does not declare" % (rel, m.group(1)), m.group(1) in secs)
        for m in re.finditer(r"\"page:([\w-]+)/([\w-]+)\"", text):
            need("N4 %s names page:%s/%s which build.json does not declare" % (rel, m.group(1), m.group(2)),
                 m.group(1) in secs and m.group(2) in declared)
    return bad


def mutate(f, rel, fn):
    g = dict(f)
    before = g[rel]
    g[rel] = fn(before)
    assert g[rel] != before, "mutation was a no-op on %s" % rel
    return g


real = load()
fails = checks(real)
for x in fails:
    print("FAIL     " + x)
if fails:
    sys.exit(1)
print("PASS     %d source files, nav declaration + island + strips hold" % len(real))

def edit_json(fn):
    def go(t):
        d = json.loads(t); fn(d["ui"], d); return json.dumps(d)
    return go

MUTATIONS = [
    ("bottom_nav gains an undeclared id", "build.json", edit_json(lambda ui, d: ui["bottom_nav"].append("ghost"))),
    ("default_section points outside the bar", "build.json", edit_json(lambda ui, d: ui.__setitem__("default_section", "ghost"))),
    ("bottom_nav grows past five", "build.json", edit_json(lambda ui, d: ui.__setitem__("bottom_nav", ui["bottom_nav"] + ["a", "b", "c", "d", "e", "f"]))),
    ("a section is dropped", "build.json", edit_json(lambda ui, d: ui.__setitem__("sections", ui["sections"][1:]))),
    ("the app stops compiling libs:bottomnav", CFG["gradles"][-1], lambda t: t.replace(":libs:bottomnav", ":libs:gone")),
    ("the gradle stops baking the sections", CFG["gradles"][-1], lambda t: t.replace("UI_SECTIONS_B64", "UI_SECTIONS_X")),
    ("the host grows a BottomNavigationView", CFG["mutate_host"], lambda t: t + "\nval nav: BottomNavigationView? = null\n"),
    ("the host grows a TabLayout", CFG["mutate_host"], lambda t: t + "\nval tabs: TabLayout? = null\n"),
    ("the host grows a Material3 NavigationBar", CFG["mutate_host"], lambda t: t + "\n@Composable fun X() { NavigationBar { } }\n"),
]
for rel, token in CFG["must_token_mutations"]:
    MUTATIONS.append(("%s stops using %s" % (os.path.basename(rel), token), rel, (lambda tok: lambda t: t.replace(tok, "Z" + tok[1:]))(token)))
for sid, want in CFG.get("pages", {}).items():
    MUTATIONS.append(("section %s loses a page" % sid, "build.json",
                      (lambda s: edit_json(lambda ui, d: [x.__setitem__("pages", x["pages"][1:]) for x in ui["sections"] if x["id"] == s]))(sid)))

survivors = []
for name, rel, fn in MUTATIONS:
    if rel not in real:
        print("FAIL     mutation target %s does not exist" % rel); survivors.append(name); continue
    if not checks(mutate(real, rel, fn)):
        survivors.append(name)
        print("FAIL     mutation survived: " + name)
    else:
        print("PASS     mutation caught: " + name)
if survivors:
    sys.exit(1)
PY
