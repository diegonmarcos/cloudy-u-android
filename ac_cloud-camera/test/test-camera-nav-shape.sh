#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════╗
# ║ #868 cloud-camera's mode switcher is the fleet's: an island and a page strip
# ╚══════════════════════════════════════════════════════════════════╝
#
# What this certifies, statically (the nav is data, the bar and the strips are libs:bottomnav's):
#   S1 build.json::ui is ONE declaration: bottom_nav = qr_scan|objects|photo|video|vr (QR Code | Objects |
#      Camera | Video | VR, in that order), default_section photo, and
#      the photo family's pages are the flavours (auto|face_retouch|portrait|night|hdr|camera). Every
#      CameraMode is declared, under its lower-cased name.
#   S2 the app compiles libs:bottomnav and bakes UI_BOTTOM_NAV + UI_SECTIONS_B64 + UI_DEFAULT_SECTION.
#   S3 ui/ModeNav.kt draws the modes with BottomNavIslandView + PageTabsView fed by NavDecl;
#      activity_main.xml hosts both inside camera_mode_tabs; ui/BottomTabLayout.kt is gone.
#   S5 the preview never runs under the nav: activity_main.xml lays main_frame (the preview's host) ABOVE
#      camera_mode_tabs, the nav is parent-bottom with no margin of its own (only the navigation-bar inset
#      lifts it, in code), and the old "float the nav up to the preview's foot" math is gone.
#   S4 no hand-rolled nav widget in the app (nav-shape-guard N3): not the old snapping TabLayout and
#      not the scan dialog's never-shown encoding tabs.
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
  "qr_scan",
  "objects",
  "photo",
  "video",
  "vr"
 ],
 "default": "photo",
 "pages": {
  "photo": [
   "auto",
   "face_retouch",
   "portrait",
   "night",
   "hdr",
   "camera"
  ]
 },
 "gradles": [
  "app/build.gradle.kts"
 ],
 "src_roots": [
  "app/src/main"
 ],
 "enum": {
  "file": "app/src/main/java/cld/camera/CamConfig.kt",
  "name": "CameraMode"
 },
 "deleted": [
  "app/src/main/java/cld/camera/ui/BottomTabLayout.kt"
 ],
 "must": {
  "app/src/main/java/cld/camera/ui/activities/MainActivity.kt": [
   "ModeNav(",
   "NavDecl.fromBuildConfig",
   "finalizeMode(mode)"
  ],
  "app/src/main/java/cld/camera/ui/ModeNav.kt": [
   "BottomNavIslandView",
   "PageTabsView",
   "setModes",
   "onPick",
   "CameraMode.entries"
  ],
  "app/src/main/java/cld/camera/CamConfig.kt": [
   "modeNav.setModes"
  ],
  "app/src/main/res/layout/activity_main.xml": [
   "com.diegonmarcos.superapp.bottomnav.BottomNavIslandView",
   "com.diegonmarcos.superapp.bottomnav.PageTabsView",
   "@+id/camera_mode_tabs"
  ]
 },
 "mustnot": {
  "app/src/main/res/layout/scan_result_dialog.xml": [
   "TabLayout"
  ]
 },
 "mutate_host": "app/src/main/java/cld/camera/ui/activities/MainActivity.kt",
 "must_token_mutations": [
  [
   "app/src/main/java/cld/camera/ui/activities/MainActivity.kt",
   "ModeNav("
  ],
  [
   "app/src/main/java/cld/camera/ui/activities/MainActivity.kt",
   "NavDecl.fromBuildConfig"
  ],
  [
   "app/src/main/java/cld/camera/ui/ModeNav.kt",
   "BottomNavIslandView"
  ],
  [
   "app/src/main/java/cld/camera/ui/ModeNav.kt",
   "PageTabsView"
  ],
  [
   "app/src/main/java/cld/camera/CamConfig.kt",
   "modeNav.setModes"
  ],
  [
   "app/src/main/res/layout/activity_main.xml",
   "com.diegonmarcos.superapp.bottomnav.PageTabsView"
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
    layout = f.get("app/src/main/res/layout/activity_main.xml", "")
    try:
        import xml.etree.ElementTree as ET
        A = "{http://schemas.android.com/apk/res/android}"
        views = {e.get(A + "id"): e for e in ET.fromstring(layout).iter() if e.get(A + "id")}
        frame, tabs = views.get("@+id/main_frame"), views.get("@+id/camera_mode_tabs")
        need("S5 main_frame is laid out above camera_mode_tabs", frame is not None and frame.get(A + "layout_above") == "@id/camera_mode_tabs")
        need("S5 camera_mode_tabs is parent-bottom", tabs is not None and tabs.get(A + "layout_alignParentBottom") == "true")
        need("S5 camera_mode_tabs has no margin of its own (the inset is applied in code)",
             tabs is not None and all(tabs.get(A + k) in (None, "0dp") for k in ("layout_marginBottom", "layout_marginTop")))
    except ET.ParseError:
        need("S5 activity_main.xml parses", False)
    main = f.get("app/src/main/java/cld/camera/ui/activities/MainActivity.kt", "")
    need("S5 the nav is lifted by the navigation-bar inset only", "bottomMargin = insets.bottom" in code(main))
    need("S5 the old nav-floats-up-to-the-preview math is gone", "extraHeight169" not in code(main))
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

def layout_edit(a, b):
    return lambda t: t.replace(a, b)

MUTATIONS = [
    ("main_frame stops being above the nav", "app/src/main/res/layout/activity_main.xml", layout_edit('android:layout_above="@id/camera_mode_tabs"\n            android:layout_height="match_parent"', 'android:layout_height="match_parent"')),
    ("the nav gets a bottom margin", "app/src/main/res/layout/activity_main.xml", layout_edit('android:layout_marginBottom="0dp"', 'android:layout_marginBottom="40dp"')),
    ("the nav floats up again", CFG["mutate_host"], lambda t: t + "\nval extraHeight169 = 0\n"),
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
