#!/usr/bin/env bash
# Tester: the SMS section is in the bottom nav, declared where the fleet declares navigation.
#
#   1. build.json::ui.bottom_nav lists "sms" and a ui.sections entry of that id, with a page
#   2. the bar is within the island's 2..8 items and ui.default_section is on it
#   3. the app compiles libs:bottomnav (gradle + settings + build.json::modules) and bakes the
#      declaration (UI_BOTTOM_NAV / UI_SECTIONS_B64 / UI_DEFAULT_SECTION)
#   4. the main layout holds the pager only: no island and no TabLayout of its own (#1164: the bottom nav is the shared Compose one)
#   5. every section has a pager page (DialerTab) and the SMS page is a Compose host
#   6. the main activity wears the fleet window (FleetChrome.apply), hosts the pages inside the shared first row + bottom nav
#      (NavBarTopFirstRowScaffold, NavBarBottomHost; Profile launches Cloud Account) and selects pages from the nav
#
# The central half (nav-shape.json no longer exempts this app, so N1-N9 hold it) is the
# nav-shape-guard workflow's; this tester is the app-local half. MUTATION: each requirement is
# broken in a throwaway copy and the check must go red on every one.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
command -v python3 >/dev/null 2>&1 || { echo "ERROR: python3 required" >&2; exit 2; }
exec python3 -I - "$APP" <<'PY'
import json, os, re, shutil, sys, tempfile

APP = sys.argv[1]
K = "app/src/main/kotlin/org/fossify/phone/"
FILES = ["build.json", "settings.gradle.kts", "app/build.gradle.kts", "app/src/main/res/layout/activity_main.xml",
         "app/src/main/res/layout/fragment_sms.xml", K + "helpers/Constants.kt", K + "activities/MainActivity.kt",
         K + "fragments/SmsFragment.kt", K + "nav/DialerNav.kt"]

def read(root, rel):
    p = os.path.join(root, rel)
    return open(p, encoding="utf-8").read() if os.path.isfile(p) else ""

def check(root):
    bad = []
    try:
        cfg = json.loads(read(root, "build.json"))
    except ValueError as e:
        return ["build.json unreadable: %s" % e]
    ui = cfg.get("ui") or {}
    bar = ui.get("bottom_nav") or []
    sections = {s.get("id"): s for s in ui.get("sections") or [] if isinstance(s, dict)}
    if "sms" not in bar:
        bad.append("ui.bottom_nav does not list sms")
    if "sms" not in sections or not (sections["sms"].get("pages") or []):
        bad.append("ui.sections has no sms section with a page")
    if not 2 <= len(bar) <= 8:
        bad.append("ui.bottom_nav has %d items, the island holds 2..8" % len(bar))
    for b in bar:
        if b not in sections:
            bad.append("ui.bottom_nav id %r is not a ui.sections id" % b)
    if ui.get("default_section") not in bar:
        bad.append("ui.default_section is not on the bar")
    if "libs:bottomnav" not in (cfg.get("modules") or {}):
        bad.append("build.json::modules does not declare libs:bottomnav")
    if not re.search(r'include\(":libs:bottomnav"\)', read(root, "settings.gradle.kts")):
        bad.append("settings.gradle.kts does not include :libs:bottomnav")
    gradle = read(root, "app/build.gradle.kts")
    if not re.search(r'implementation\(project\(":libs:bottomnav"\)\)', gradle):
        bad.append("app/build.gradle.kts does not compile libs:bottomnav")
    for field in ("UI_BOTTOM_NAV", "UI_SECTIONS_B64", "UI_DEFAULT_SECTION"):
        if field not in gradle:
            bad.append("app/build.gradle.kts does not bake " + field)
    layout = read(root, "app/src/main/res/layout/activity_main.xml")
    if "BottomNavIslandView" in layout:
        bad.append("activity_main.xml draws an island of its own (the bottom nav is the shared Compose one)")
    for lib in ("ui-nav-bars", "ui-nav-bars", "ui-nav-bars", "theme-background"):
        if not re.search(r'implementation\(project\(":libs:%s"\)\)' % lib, gradle):
            bad.append("app/build.gradle.kts does not compile libs:" + lib)
    if re.search(r"TabLayout|BottomNavigationView|NavigationBarView", layout):
        bad.append("activity_main.xml draws a nav widget of its own")
    tabs = read(root, K + "helpers/Constants.kt")
    # #1415 Keypad and SIMs are island items that are not pager pages (DialerNav.NON_PAGER).
    nav = read(root, K + "nav/DialerNav.kt")
    m = re.search(r"NON_PAGER[^=]*=\s*setOf\(([^)]*)\)", nav)
    consts = dict(re.findall(r'const val (\w+) = "([^"]+)"', nav))
    non_pager = {consts.get(x.strip(), x.strip()) for x in m.group(1).split(",")} if m else set()
    for sid in bar:
        if sid in non_pager:
            continue
        if 'sectionId = "%s"' % sid not in tabs:
            bad.append("no DialerTab (pager page) for section %r" % sid)
    if "R.layout.fragment_sms" not in tabs:
        bad.append("the SMS DialerTab does not use fragment_sms")
    if "ComposeView" not in read(root, "app/src/main/res/layout/fragment_sms.xml"):
        bad.append("fragment_sms.xml is not a Compose host")
    main = read(root, K + "activities/MainActivity.kt")
    if "FleetChrome.apply(" not in main:
        bad.append("MainActivity does not call FleetChrome.apply(")
    for tok in ("NavBarTopFirstRowScaffold(", "NavBarBottomHost(", "rememberAccountLauncher()", "DialerNav.shown"):
        if tok not in main:
            bad.append("MainActivity does not use " + tok)
    if re.search(r"\bTabLayout\b", main):
        bad.append("MainActivity names a TabLayout")
    nav = read(root, K + "nav/DialerNav.kt")
    if "NavDecl.fromBuildConfig(" not in nav:
        bad.append("DialerNav does not decode the baked declaration")
    return bad

PASS = FAIL = 0
def ok(m):
    global PASS; PASS += 1; print("  PASS: " + m)
def no(m):
    global FAIL; FAIL += 1; print("  FAIL: " + m)

real = check(APP)
if real:
    for b in real: no(b)
else:
    ok("SMS is in the bottom nav, declared in build.json and drawn by libs:bottomnav's island")

def clone():
    tmp = tempfile.mkdtemp(prefix="dialer-smsnav-")
    for f in FILES:
        src = os.path.join(APP, f)
        if os.path.isfile(src):
            dst = os.path.join(tmp, f)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copy(src, dst)
    return tmp

def edit(tmp, rel, fn):
    p = os.path.join(tmp, rel)
    old = open(p, encoding="utf-8").read()
    new = fn(old)
    open(p, "w", encoding="utf-8").write(new)
    return old != new

def ui_edit(fn):
    def go(s):
        d = json.loads(s); fn(d); return json.dumps(d, indent=2)
    return go

MUT = [
    ("sms leaves ui.bottom_nav", "build.json", ui_edit(lambda d: d["ui"]["bottom_nav"].remove("sms"))),
    ("the sms section is deleted", "build.json", ui_edit(lambda d: d["ui"].__setitem__("sections", [s for s in d["ui"]["sections"] if s["id"] != "sms"]))),
    ("the bar is cut to one item", "build.json", ui_edit(lambda d: d["ui"].__setitem__("bottom_nav", ["home"]))),
    ("the default section leaves the bar", "build.json", ui_edit(lambda d: d["ui"].__setitem__("default_section", "nowhere"))),
    ("libs:bottomnav is no longer a module", "build.json", ui_edit(lambda d: d["modules"].pop("libs:bottomnav"))),
    ("settings no longer includes libs:bottomnav", "settings.gradle.kts", lambda s: s.replace('include(":libs:bottomnav")', "")),
    ("the app stops compiling libs:bottomnav", "app/build.gradle.kts", lambda s: s.replace('implementation(project(":libs:bottomnav"))', "")),
    ("the app stops baking UI_SECTIONS_B64", "app/build.gradle.kts", lambda s: s.replace("UI_SECTIONS_B64", "UI_SOMETHING")),
    ("the layout draws its own island again", "app/src/main/res/layout/activity_main.xml", lambda s: s.replace("</androidx.coordinatorlayout.widget.CoordinatorLayout>", '<com.diegonmarcos.superapp.bottomnav.BottomNavIslandView android:layout_width="match_parent" android:layout_height="wrap_content" /></androidx.coordinatorlayout.widget.CoordinatorLayout>')),
    ("the app stops compiling libs:ui-nav-bars", "app/build.gradle.kts", lambda s: s.replace('implementation(project(":libs:ui-nav-bars"))', "")),
    ("MainActivity stops using the shared first row", K + "activities/MainActivity.kt", lambda s: s.replace("NavBarTopFirstRowScaffold(", "OtherScaffold(")),
    ("MainActivity stops using the shared bottom nav", K + "activities/MainActivity.kt", lambda s: s.replace("NavBarBottomHost(", "OtherHost(")),
    ("Profile stops launching Cloud Account", K + "activities/MainActivity.kt", lambda s: s.replace("rememberAccountLauncher()", "{}")),
    ("the layout grows a TabLayout", "app/src/main/res/layout/activity_main.xml", lambda s: s.replace("</androidx.coordinatorlayout.widget.CoordinatorLayout>", '<com.google.android.material.tabs.TabLayout android:layout_width="match_parent" android:layout_height="wrap_content" /></androidx.coordinatorlayout.widget.CoordinatorLayout>')),
    ("the SMS pager page is dropped", K + "helpers/Constants.kt", lambda s: s.replace('sectionId = "sms"', 'sectionId = "other"')),
    ("the SMS page stops being a Compose host", "app/src/main/res/layout/fragment_sms.xml", lambda s: s.replace("ComposeView", "FrameLayout")),
    ("MainActivity drops the fleet window", K + "activities/MainActivity.kt", lambda s: s.replace("FleetChrome.apply(", "noChrome(")),
    ("MainActivity stops driving the nav from the declaration", K + "activities/MainActivity.kt", lambda s: s.replace("DialerNav.shown", "emptyList<Any>")),
    ("MainActivity names a TabLayout again", K + "activities/MainActivity.kt", lambda s: s + "\nprivate val legacy = com.google.android.material.tabs.TabLayout::class\n"),
]
for label, rel, fn in MUT:
    tmp = clone()
    try:
        if not edit(tmp, rel, fn):
            no("mutation `%s` changed nothing (the tester's anchor is stale)" % label)
        elif check(tmp):
            ok("mutation caught: " + label)
        else:
            no("mutation NOT caught: " + label)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

print("== RESULT: %d passed, %d failed ==" % (PASS, FAIL))
sys.exit(1 if FAIL else 0)
PY
