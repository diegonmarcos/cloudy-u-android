#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #648 — cloud-c3's chrome is DECLARED, every declared page is REAL, and   ║
# ║ the shell reads the cutout inset instead of consuming it                 ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# WHY THIS EXISTS. This app was EXTRACTED from aa_cloud-superapp, and an extraction
# fails in three ways that all look fine from outside:
#
#   (1) a surface exists with nothing linking to it — invisible, working, dead;
#   (2) a link points at a surface that no longer exists — a tap that does nothing;
#   (3) THE ONE THAT ACTUALLY HAPPENED: the tab exists, the link works, the ship is
#       green, and the page is EMPTY. The first cut of this app shipped a five-tab
#       APK whose two content tabs rendered a "not built yet" placeholder, and the
#       green ship was reported as done. A guard for (1) and (2) cannot see that.
#
# So T6 is the centre of this file: a page the declaration lists must resolve to a
# real fragment, and no placeholder body may exist for one to fall back into.
#
#   T1  ONE tab declaration: exactly the five tabs, in declared order - Infra | Containers |
#       Home | Ops | Observ (owner order, 2026-10-16) - Home in the CENTRE, ui.default_section
#       = home (the app opens on Home), and Configs a declared section that is NOT on the bar.
#   T2  NO ORPHANS, BOTH DIRECTIONS: every declared tab has a branch in the shell's
#       dispatch and every branch answers a declared id.
#   T3  the declaration is the ONLY order: no Kotlin file holds a second list of tab
#       ids, so reordering build.json reorders the nav and nothing else.
#   T4  every declared icon is a real DRAWABLE (ic_*) or a libs:ui-icons catalog name, and every drawable is declared. The
#       shell resolves icons by name with getIdentifier, so a name with
#       no file resolves to 0 and draws a blank square — silently.
#   T5  the Ops tab (was Apps) LAUNCHES the three real sibling APKs: each package equals that
#       sibling's own application_id, each id IS its fleet name, no tile carries a
#       display `label` (#351, the copy #224 reverted), and each package is declared
#       in AndroidManifest <queries> or API 30+ reports an installed app as absent.
#   T6  NO PLACEHOLDER IN A SHIPPED TAB. Every declared page of every content tab
#       resolves to a real fragment in that tab's dispatch AND every dispatched page
#       id is declared; no "not built"/"coming soon" body exists anywhere; and the
#       one stated error fragment is reachable only from an UNDECLARED id, never
#       from a declared one. There is also no `implemented:` style flag in the
#       declaration — an escape hatch is how the empty APK shipped.
#   T7  the shell HOSTS FRAGMENTS and reuses libs:bottomnav's own island
#       (the BottomNavIsland composable), and does NOT consume cloud-mail's bottomNavItems
#       table. The pages are the SuperApp's Fragments, moved, not reauthored.
#   T8  the TOP-OVERFLOW fix is the declared one (#407/#477): the Compose shell reads
#       systemBars UNION displayCutout and pads the content's TOP and SIDES only,
#       leaving the bottom inset to the island; it consumes nothing, and holds no
#       hardcoded top margin or padding. Consuming the bottom would starve the island
#       below of its own inset, which is exactly how #477's bug worked.
#   T10 the FIRST ROW is the fleet's shared AppTopBar (ui.top_bar: drawer + ai + account/configs
#       settings, baked as UI_TOP_BAR_B64, AppTopBarScaffold fed the AI panel, the Account launcher
#       and Settings > Configs); no private top row and no Configs entry on the bottom nav.
#   T11 the drawer is the full tree of the declared nav (C3Nav.drawerRows over every section) and
#       the moved ids keep working: ui.aliases resolves every old section / page id to a declared one.
#   T12 Containers = Topology | Table (the Public and Private pages merged into Table), Infra =
#       the promoted Stack page, and the Table page's data (infra_slice.json routes) carries the
#       seven columns' fields with an auth_type from the edge tiers.
#   M   mutation-proof: every check above is shown able to go RED.
#
# OWN-SOURCE ONLY. python3, jq and grep.
set -uo pipefail

ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
APP="$ROOT/ac_cloud-c3"
BJ="$APP/build.json"
SRC="$APP/app/src/main/java/com/diegonmarcos/cloudc3"
MAIN="$SRC/MainActivity.kt"
NAVKT="$SRC/C3BottomNav.kt"
TABS="$SRC/pages/TabFragments.kt"
DRAWABLE="$APP/app/src/main/res/drawable"
UIICONS="$ROOT/ab_cloud-libs-shared/libs/ui-icons/src/main/kotlin/com/diegonmarcos/superapp/uiicons/UiIcons.kt"
MANIFEST="$APP/app/src/main/AndroidManifest.xml"

FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }
for required in "$BJ" "$MAIN" "$NAVKT" "$TABS" "$MANIFEST"; do
    [ -f "$required" ] || { echo "ERROR missing source: $required — this tester is unrun, not passing"; exit 1; }
done
[ -d "$DRAWABLE" ] || { echo "ERROR missing $DRAWABLE — unrun, not passing"; exit 1; }
command -v jq >/dev/null 2>&1 || { echo "ERROR jq absent — a verdict from a missing tool is not a verdict"; exit 1; }

# ── the checks as functions of their inputs, so the mutation block runs them on copies ──

t1() {
    python3 - "$1" <<'PYTHON'
import json, sys
ui = json.load(open(sys.argv[1], encoding="utf-8"))["ui"]
secs = {s.get("id"): s for s in (ui.get("sections") or [])}
ids = ui.get("bottom_nav") or []
tabs = [secs.get(i) or {"id": i} for i in ids]
labels = [t.get("label") for t in tabs]
want = ["Infra", "Containers", "Home", "Ops", "Observ"]
bad = []
if len(ids) != 5: bad.append("ui.bottom_nav declares %d tabs, not five: %s" % (len(ids), ids))
if labels != want: bad.append("ui.bottom_nav labels are %s, not %s in that order" % (labels, want))
for i in ids:
    if i not in secs: bad.append("ui.bottom_nav names %r, which is no ui.sections id" % i)
if len(ids) == 5 and ids[2] != "home":
    bad.append("the CENTRE tab is %r, not home — every fleet five-tab shell puts Home centre" % ids[2])
if ui.get("default_section") not in ids:
    bad.append("ui.default_section %r is not a declared id %s" % (ui.get("default_section"), ids))
if ui.get("default_section") != "home":
    bad.append("ui.default_section is %r, not home - the app opens on Home" % ui.get("default_section"))
if "configs" in ids:
    bad.append("Configs is on the bottom nav - it belongs behind the top bar's Settings dropdown")
if "configs" not in secs:
    bad.append("no configs section is declared - Settings > Configs would open nothing")
if len(set(ids)) != len(ids): bad.append("two tabs share an id: %s" % ids)
for t in tabs:
    for k in ("id", "label", "icon"):
        if not t.get(k): bad.append("tab %r declares no %s" % (t.get("id"), k))
for b in bad: print("    " + b)
sys.exit(1 if bad else 0)
PYTHON
}

# t2 <build.json> <MainActivity.kt> : tab declaration <-> shell dispatch, both ways
t2() {
    python3 - "$1" "$2" <<'PYTHON'
import json, re, sys
# every declared SECTION is dispatched (Configs is a section that is not on the bar)
declared = [x.get("id") for x in (json.load(open(sys.argv[1], encoding="utf-8"))["ui"].get("sections") or [])]
text = open(sys.argv[2], encoding="utf-8").read()
m = re.search(r"val fragment = when \(tabId\) \{(.*?)\n        \}", text, re.S)
if not m:
    print("    no `when (tabId)` in MainActivity — there is no single dispatch to diff"); sys.exit(1)
branches = re.findall(r'^\s*"([^"]+)"\s*->', m.group(1), re.M)
bad = []
for tab in declared:
    if tab not in branches:
        bad.append("tab %r is DECLARED but the shell dispatches no fragment for it — "
                   "an orphaned declaration, a tab that opens nothing" % tab)
for br in branches:
    if br not in declared:
        bad.append("the shell dispatches %r but ui.sections does not declare it — "
                   "an orphaned SCREEN, reachable from nothing" % br)
if len(set(branches)) != len(branches):
    bad.append("the shell has a duplicate branch: %s" % branches)
for b in bad: print("    " + b)
sys.exit(1 if bad else 0)
PYTHON
}

# t3 <build.json> <src dir> : no SECOND list of tab ids
t3() {
    python3 - "$1" "$2" <<'PYTHON'
import json, os, re, sys
_ui = json.load(open(sys.argv[1], encoding="utf-8"))["ui"]
declared = list(_ui.get("bottom_nav") or []) + [x.get("id") for x in (_ui.get("sections") or []) if x.get("id") not in (_ui.get("bottom_nav") or [])]
bad = []
for dirpath, _d, files in os.walk(sys.argv[2]):
    for f in files:
        if not f.endswith(".kt"): continue
        p = os.path.join(dirpath, f)
        text = open(p, encoding="utf-8").read()
        code = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
        code = re.sub(r"//[^\n]*", "", code)
        for lit in re.findall(r"(?:listOf|arrayOf|setOf)\s*\(([^)]*)\)", code, re.S):
            found = [t for t in declared if '"%s"' % t in lit]
            if len(found) >= 2:
                bad.append("%s holds a second list of tab ids %s — reordering ui.bottom_nav would no "
                           "longer reorder the nav" % (os.path.relpath(p, sys.argv[2]), found))
for b in bad: print("    " + b)
sys.exit(1 if bad else 0)
PYTHON
}

# t4 <build.json> <drawable dir> <src dir> : declared icon <-> drawable file, BOTH directions.
# #648 "declared" now also covers the carried c3 section (ui.sections — every `icon` value
# anywhere inside it, the stacks' tiles included) and the drawables Kotlin names directly
# (R.drawable.X, and the quoted "ic_*" names iconResFor-style lookups resolve at runtime) —
# the carried pages resolve icons both ways, and either one missing draws a BLANK square.
t4() {
    python3 - "$1" "$2" "$3" "$4" <<'PYTHON'
import json, os, re, sys
ui = json.load(open(sys.argv[1], encoding="utf-8"))["ui"]
declared = set()
declared.update(a.get("icon") for a in (ui.get("external_apps") or []))
if ui.get("icon_default"): declared.add(ui["icon_default"])
def walk_icons(node):
    if isinstance(node, dict):
        for k, v in node.items():
            if k in ("icon", "icon_apps", "icon_admin") and isinstance(v, str):
                declared.add(v)
            else:
                walk_icons(v)
    elif isinstance(node, list):
        for item in node:
            walk_icons(item)
walk_icons(ui.get("sections") or [])
walk_icons(ui.get("carried_c3") or {})
# Kotlin-side references: compile-time R.drawable ids and runtime getIdentifier names.
for dirpath, _d, files_ in os.walk(sys.argv[3]):
    for f in files_:
        if not f.endswith(".kt"): continue
        text = open(os.path.join(dirpath, f), encoding="utf-8").read()
        code = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
        code = re.sub(r"//[^\n]*", "", code)
        declared.update(re.findall(r"R\.drawable\.([a-z0-9_]+)", code))
        declared.update(re.findall(r'"(ic_[a-z0-9_]+)"', code))
declared = {d for d in declared if d}
# A name that is not an ic_ drawable is a libs:ui-icons catalog name (#1160/#1443): the shared second row draws it
# from the catalog, so it needs no drawable here -- but the catalog must hold it.
catalog = set(re.findall(r'"([a-z0-9_]+)"\s+to\s+Icons\.', open(sys.argv[4], encoding="utf-8").read()))
catalog_names = {d for d in declared if not d.startswith("ic_")}
declared -= catalog_names
files = {f[:-4] for f in os.listdir(sys.argv[2]) if f.endswith(".xml")}
launcher = {"ic_launcher_background", "ic_launcher_foreground", "ic_launcher_monochrome"}
bad = []
for name in sorted(catalog_names - catalog):
    bad.append("declared icon %r is neither an ic_ drawable nor a name in the libs:ui-icons catalog — it draws blank" % name)
for name in sorted(declared - files):
    bad.append("declared icon %r has no res/drawable/%s.xml — the shell resolves icons by "
               "name with getIdentifier, so this resolves to 0 and draws a BLANK square" % (name, name))
for name in sorted(files - declared - launcher):
    bad.append("res/drawable/%s.xml is declared by nothing — a resource no declaration names "
               "is dead weight moved for no reason" % name)
for b in bad: print("    " + b)
sys.exit(1 if bad else 0)
PYTHON
}

# t5 <build.json> <manifest> <repo root> : the Apps tiles are the REAL siblings
t5() {
    python3 - "$1" "$2" "$3" <<'PYTHON'
import json, os, re, sys
ui = json.load(open(sys.argv[1], encoding="utf-8"))["ui"]
manifest = open(sys.argv[2], encoding="utf-8").read()
root = sys.argv[3]
tiles = ui.get("external_apps") or []
# The REQUIRED Apps-tab membership: the three C3 siblings and the cloud-c3-webserver
# sub-app, which launches from here and not from the SuperApp's Configs grid. Each
# app's directory is DERIVED (ac_<id>), never a second table.
want = {i: "ac_" + i for i in ("c3-watchdog", "c3-morpheus", "c3-watchtower",
                               "cloud-c3-webserver")}
ids = [t.get("id") for t in tiles]
bad = []
if sorted(ids) != sorted(want):
    bad.append("the Apps tab declares %s, not the required apps %s" % (ids, sorted(want)))
queried = set(re.findall(r'<package android:name="([^"]+)"', manifest))
for t in tiles:
    tid, pkg = t.get("id"), t.get("package")
    if "label" in t:
        bad.append("Apps tile %r carries a label %r beside a fleet package — an application has "
                   "ONE name (#351) and the id IS it; #224 reverted exactly this" % (tid, t.get("label")))
    sib = want.get(tid)
    if sib is None: continue
    bj = os.path.join(root, sib, "build.json")
    if not os.path.isfile(bj):
        bad.append("%s names sibling %s, which has no build.json" % (tid, sib)); continue
    sj = json.load(open(bj, encoding="utf-8"))
    real = (sj.get("android") or {}).get("application_id")
    if pkg != real:
        bad.append("%s declares package %r but %s's application_id is %r — a wrong package "
                   "compiles, ships, installs and leaves a tab that opens nothing" % (tid, pkg, sib, real))
    if sj.get("name") != tid:
        bad.append("Apps tile id %r is not %s::name %r" % (tid, sib, sj.get("name")))
    if pkg not in queried:
        bad.append("%s's package %r is not in AndroidManifest <queries> — on API 30+ an unqueried "
                   "package is INVISIBLE, so an installed app would read 'Not installed'" % (tid, pkg))
for b in bad: print("    " + b)
sys.exit(1 if bad else 0)
PYTHON
}

# t6 <build.json> <TabFragments.kt> <src dir> : NO PLACEHOLDER IN A SHIPPED TAB
t6() {
    python3 - "$1" "$2" "$3" <<'PYTHON'
import json, os, re, sys
ui = json.load(open(sys.argv[1], encoding="utf-8"))["ui"]
tabsrc = open(sys.argv[2], encoding="utf-8").read()
srcdir = sys.argv[3]
bad = []

# (a) each paged tab's DECLARED pages must each resolve to a real fragment, and every
#     dispatched page id must be declared. Read out of each pageFragment() when-block.
for tab, cls in (("infra", "InfraFragment"), ("containers", "ContainersFragment"), ("observ", "ObservFragment"),
                 ("ops", "OpsFragment"), ("configs", "ConfigsFragment")):
    sec = next((s for s in (ui.get("sections") or []) if s.get("id") == tab), {})
    declared = [p["id"] for p in (sec.get("pages") or [])]
    # #1454 a page that declares pages of its own (Containers: project -> Topology | Table) resolves its NESTED ids through
    # leafFragment(parent, leaf) -> the view is the leaf id after "<parent>_"; the top ids need no fragment of their own.
    nested = [(p["id"], [q["id"] for q in p["pages"]]) for p in (sec.get("pages") or []) if p.get("pages")]
    if nested:
        views = sorted({lid[len(pid) + 1:] for pid, leaves in nested for lid in leaves if lid.startswith(pid + "_")})
        if any(not lid.startswith(pid + "_") for pid, leaves in nested for lid in leaves):
            bad.append("ui.sections[%s] nests a page whose id does not start with its parent's id + '_'" % tab)
        mm = re.search(r"class %s : PagedFragment\(\)(?:(?!\n\}\n).)*?when \(leafId\.removePrefix\(.*?\)\) \{(.*?)\n    \}" % cls, tabsrc, re.S)
        got = re.findall(r'^\s*"([^"]+)"\s*->', mm.group(1), re.M) if mm else []
        if not mm or sorted(got) != views:
            bad.append("%s resolves the nested views %s, the declaration nests %s" % (cls, got, views))
        declared = [d for d in declared if d not in {pid for pid, _ in nested}]
    m = re.search(r"class %s : PagedFragment\(\)(?:(?!\n\}\n).)*?when \(pageId\) \{(.*?)\n    \}" % cls,
                  tabsrc, re.S)
    if nested and not m:
        continue
    if not m:
        bad.append("no pageFragment when-block found for %s — nothing to diff the %s pages against"
                   % (cls, tab))
        continue
    body = m.group(1)
    branches = re.findall(r'^\s*"([^"]+)"\s*->', body, re.M)
    if not declared:
        bad.append("ui.sections[%s] declares NO pages, so the %s tab would open empty — a tab the nav "
                   "shows must have something in it" % (tab, tab))
    for pid in declared:
        if pid not in branches:
            bad.append("ui.sections[%s] declares page %r but %s resolves no fragment for it — the tab "
                       "would draw the stated-bug body, which is a PLACEHOLDER in a shipped tab"
                       % (tab, pid, cls))
    for br in branches:
        if br not in declared:
            bad.append("%s resolves page %r which ui.sections[%s] does not declare — an orphaned page"
                       % (cls, br, tab))

# (b) no placeholder body may EXIST for a declared page to fall back into.
banned = ("NotBuiltYet", "not built yet", "notBuiltYet", "ComingSoon", "coming soon",
          "TODO_PAGE", "Placeholder")
for dirpath, _d, files in os.walk(srcdir):
    for f in files:
        if not f.endswith(".kt"): continue
        p = os.path.join(dirpath, f)
        text = open(p, encoding="utf-8").read()
        code = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
        code = re.sub(r"//[^\n]*", "", code)
        for b in banned:
            if b in code:
                bad.append("%s contains %r — a not-built body is what let an empty APK ship "
                           "green; a page is DECLARED when it is implemented and not before"
                           % (os.path.relpath(p, srcdir), b))

# (c) no escape-hatch flag in the declaration: `implemented`, `stub`, `wip`, `placeholder`
for tab in ("infra", "containers", "observ", "ops", "configs"):
    sec = next((s for s in (ui.get("sections") or []) if s.get("id") == tab), {})
    for p in (sec.get("pages") or []):
        for k in p:
            if k.lower() in ("implemented", "stub", "wip", "placeholder", "not_built", "built"):
                bad.append("ui.sections[%s] page %r declares %r — a flag that excuses an empty page is "
                           "how the empty APK shipped; there is no such flag" % (tab, p.get("id"), k))
for b in bad: print("    " + b)
sys.exit(1 if bad else 0)
PYTHON
}

# t7 <MainActivity.kt> <C3BottomNav.kt> <src dir> : fragment host, shared island reused,
#    and a file that hosts Compose builds no Views
t7() {
    python3 - "$1" "$2" "$3" <<'PYTHON'
import os, re, sys
main = open(sys.argv[1], encoding="utf-8").read()
nav = open(sys.argv[2], encoding="utf-8").read()
def code(t):
    t = re.sub(r"/\*.*?\*/", "", t, flags=re.S)
    return re.sub(r"//[^\n]*", "", t)
mc, nc = code(main), code(nav)
bad = []
if "AppCompatActivity" not in mc:
    bad.append("MainActivity is not an AppCompatActivity, so it cannot host the SuperApp's Fragments")
if "supportFragmentManager" not in mc:
    bad.append("MainActivity never touches supportFragmentManager — it is not a fragment host, "
               "and the pages this app exists to show ARE fragments")
for sym in ("NavBarBottom", "islandEntries"):
    if sym not in nc:
        bad.append("C3BottomNav does not use the shared %s — libs:bottomnav's own island "
                   "is the one every fleet shell draws (items from the catalog via islandEntries, #1160), not a fork" % sym)
if re.search(r"\bbottomNavItems\b", nc) or re.search(r"\bbottomNavItems\b", mc):
    bad.append("the shell references bottomNavItems, which is cloud-MAIL's item table: this "
               "app's nav would be coupled to another shipping app's tabs")
# 2026-10-15 owner decision: all apps must be Compose; replaces the no-@Composable check.
# A file that hosts Compose must not also inflate/build Views: a page is one or the other,
# so a half-migrated screen cannot hide behind a ComposeView.
HOST = re.compile(r"@Composable\b|\bComposeView\b|\bAbstractComposeView\b|\bsetContent\s*[({]")
VIEWS = re.compile(r"\binflate\([^)]*\bR\.layout\.|Binding\.inflate\(|\bfindViewById\b|\bR\.layout\."
                   r"|\b(?:TextView|LinearLayout|FrameLayout|RelativeLayout|ConstraintLayout|ImageView|"
                   r"ImageButton|ScrollView|HorizontalScrollView|NestedScrollView|RecyclerView|EditText|"
                   r"SwitchCompat|CheckBox|GridLayout|ProgressBar|SeekBar|CardView|MaterialCardView|"
                   r"ListView|PopupWindow)\s*\(")
for dirpath, _d, files in os.walk(sys.argv[3]):
    for f in files:
        if not f.endswith(".kt"): continue
        p = os.path.join(dirpath, f)
        c = re.sub(r'"(?:\\.|[^"\\\n])*"', '""', code(open(p, encoding="utf-8").read()))
        if HOST.search(c) and VIEWS.search(c):
            bad.append("%s hosts Compose AND inflates/builds Views (%s) — a Compose page is "
                       "Compose all the way down" % (os.path.relpath(p, sys.argv[3]), VIEWS.search(c).group(0)))
for b in bad: print("    " + b)
sys.exit(1 if bad else 0)
PYTHON
}

# t8 <MainActivity.kt> : read the cutout inset, never consume it
t8() {
    python3 - "$1" <<'PYTHON'
import re, sys
main = open(sys.argv[1], encoding="utf-8").read()
code = re.sub(r"/\*.*?\*/", "", main, flags=re.S)
code = re.sub(r"//[^\n]*", "", code)
bad = []
if "windowInsetsPadding" not in code:
    bad.append("the shell pads its content with no window-inset read, so the top offset cannot be the "
               "real one (#407 replaced a tuned margin with a measured inset)")
if "displayCutout" not in code:
    bad.append("the inset read never includes displayCutout — on this device the cutout is "
               "taller than the status bar, so systemBars alone leaves content clipped (#407)")
if "systemBars" not in code:
    bad.append("the inset read never includes systemBars")
# NON-CONSUMING: the shell pads the top and the sides and LEAVES THE BOTTOM, which the island reads
# for itself. Taking the bottom (Bottom / Vertical / all sides) starves the island of its own inset (#477).
m = re.search(r"windowInsetsPadding\((.*?)\n\s*\)", code, re.S)
if m:
    body = m.group(1)
    if re.search(r"WindowInsetsSides\.(Bottom|Vertical)\b", body) or ".only(" not in body:
        bad.append("the shell's inset padding takes the BOTTOM inset — that starves the island below it "
                   "of its own, which is exactly how #477's bug worked; pad Top + Horizontal only")
else:
    bad.append("no windowInsetsPadding(...) on the shell column")
if re.search(r"\bconsumeWindowInsets\b|\bCONSUMED\b|consumeSystemWindowInsets", code):
    bad.append("the shell CONSUMES window insets — the island reads its own bottom inset")
# and no tuned top offset anywhere in the shell
for m2 in re.finditer(r"\.padding\(\s*top\s*=\s*(\d+)\.dp", code):
    bad.append("the shell hardcodes a top offset of %sdp — the top offset is the measured "
               "inset, never a tuned number (#407)" % m2.group(1))
for b in bad: print("    " + b)
sys.exit(1 if bad else 0)
PYTHON
}

echo "── #648 cloud-c3: declared, implemented, and not drawing under the cutout ──"
# t9 <build.json> <AppsCatalog.kt> <infra_slice.json> : Services/Tools declaration is closed
t9() {
    python3 - "$1" "$2" "$3" <<'PYTHON'
import json, re, sys
ap = json.load(open(sys.argv[1], encoding="utf-8"))["ui"].get("apps_page") or {}
kt = open(sys.argv[2], encoding="utf-8").read()
sl = json.load(open(sys.argv[3], encoding="utf-8"))
tools = [t["id"] for t in ap.get("tools") or []]
svcs = ap.get("services") or []
m = re.search(r"VIEW_IDS = listOf\((.*?)\n    \)", kt, re.S)
views = set(re.findall(r'"([a-z_]+)"', m.group(1))) if m else set()
bad = []
if not tools or not svcs: bad.append("ui.apps_page declares no tools or no services")
used = set()
for s in svcs:
    for t in s.get("tools") or []:
        used.add(t)
        if t not in tools: bad.append("service %r uses tool %r which ui.apps_page.tools does not declare" % (s.get("id"), t))
    for v in s.get("views") or []:
        if v not in views: bad.append("service %r declares view %r with no renderer in AppsCatalog.VIEW_IDS" % (s.get("id"), v))
for t in tools:
    if t not in used: bad.append("tool %r is used by no service - unreachable from Services" % t)
    if t not in (sl.get("tools") or {}): bad.append("tool %r is missing from data/infra_slice.json - rerun gen_infra_slice.py" % t)
declared_views = {v for s in svcs for v in s.get("views") or []}
for v in views - declared_views: bad.append("renderer %r exists but no service declares it" % v)
for b in bad: print("    " + b)
sys.exit(1 if bad else 0)
PYTHON
}

# t10 <build.json> <app/build.gradle> <src dir> : the FIRST ROW is the fleet's shared AppTopBar
t10() {
    python3 - "$1" "$2" "$3" <<'PYTHON'
import json, os, re, sys
ui = json.load(open(sys.argv[1], encoding="utf-8"))["ui"]
gradle = open(sys.argv[2], encoding="utf-8").read()
srcdir = sys.argv[3]
bad = []
tb = ui.get("top_bar") or {}
if tb.get("drawer") is not True: bad.append("ui.top_bar.drawer is not true - no hamburger / drawer")
if tb.get("ai") is not True: bad.append("ui.top_bar.ai is not true - no AI button")
if (tb.get("settings") or []) != ["account", "configs"]:
    bad.append("ui.top_bar.settings is %s, not [account, configs] (Profile/Settings dropdown)" % (tb.get("settings"),))
if "UI_TOP_BAR_B64" not in gradle: bad.append("app/build.gradle does not bake UI_TOP_BAR_B64")
if "project(':libs:ai')" not in gradle: bad.append("app/build.gradle does not link libs:ai - the AI panel has no chat")
if "AI_GATEWAY_URL" not in gradle: bad.append("app/build.gradle does not bake AI_GATEWAY_URL for the shared chat")
code = ""
for dirpath, _d, files in os.walk(srcdir):
    for f in files:
        if f.endswith(".kt"):
            t = open(os.path.join(dirpath, f), encoding="utf-8").read()
            t = re.sub(r"/\*.*?\*/", "", t, flags=re.S)
            code += re.sub(r"//[^\n]*", "", t) + "\n"
for needle, why in (("NavBarTopFirstRowScaffold(", "never draws the shared top bar"),
                    ("AiFleetChatPanel(", "the AI button has no shared chat behind it"),
                    ("rememberAccountLauncher(", "Settings > Account launches nothing"),
                    ("onConfigs =", "Settings > Configs opens nothing"),
                    ("TopBarDrawer", "the hamburger's drawer is empty")):
    if needle not in code: bad.append("no Kotlin calls %s: %s" % (needle, why))
# no private top row, no hand-rolled hamburger / drawer, no Configs entry on the island
for pat, why in ((r"Icons\.\w+(?:\.\w+)?\.Menu\b", "a private hamburger"),
                 (r"\b(?:ModalNavigationDrawer|ModalDrawerSheet|CenterAlignedTopAppBar|TopAppBar)\s*\(", "a private drawer / top app bar")):
    if re.search(pat, code): bad.append("Kotlin draws %s beside the shared top bar" % why)
if "configs" in (ui.get("bottom_nav") or []): bad.append("Configs is still an entry of the bottom nav")
for b in bad: print("    " + b)
sys.exit(1 if bad else 0)
PYTHON
}

# t11 <build.json> <C3Nav.kt> <src dir> : the drawer tree is the declaration; moved ids keep working
t11() {
    python3 - "$1" "$2" "$3" <<'PYTHON'
import json, os, re, sys
ui = json.load(open(sys.argv[1], encoding="utf-8"))["ui"]
nav = open(sys.argv[2], encoding="utf-8").read()
srcdir = sys.argv[3]
bad = []
secs = {x["id"]: x for x in ui.get("sections") or []}
def page_ids(pages):
    out = []
    for p in pages or []:
        out.append(p["id"]); out += page_ids(p.get("pages"))
    return out
if "fun drawerRows(" not in nav or "nav.sections" not in nav:
    bad.append("C3Nav.drawerRows is not built from every declared section (nav.sections)")
al = ui.get("aliases") or {}
for old, new in (al.get("sections") or {}).items():
    if new not in secs: bad.append("ui.aliases.sections %r -> %r: no such section" % (old, new))
    if old in secs: bad.append("ui.aliases.sections %r is still a declared section, so it is no alias" % old)
for old, dest in (al.get("pages") or {}).items():
    sec, _, pg = dest.partition("/")
    if sec not in secs or pg not in page_ids(secs[sec].get("pages")):
        bad.append("ui.aliases.pages %r -> %r: no such section/page" % (old, dest))
# the ids that moved on 2026-10-16 must still resolve
for old in ("topology", "apps", "public", "private", "stack"):
    if old not in (al.get("sections") or {}) and old not in (al.get("pages") or {}) and old not in secs \
            and not any(old in page_ids(x.get("pages")) for x in secs.values()):
        bad.append("old id %r resolves to nothing - a deep link or saved tab naming it would break" % old)
if "aliases" not in open(os.path.join(srcdir, "MainActivity.kt"), encoding="utf-8").read():
    bad.append("MainActivity does not resolve ids through ui.aliases (deep links / restored tab)")
for b in bad: print("    " + b)
sys.exit(1 if bad else 0)
PYTHON
}

# t12 <build.json> <infra_slice.json> <TabFragments.kt> : Containers = Topology | Table, Infra = five fleet pages, Table's data
t12() {
    python3 - "$1" "$2" "$3" <<'PYTHON'
import json, re, sys
b = json.load(open(sys.argv[1], encoding="utf-8"))
ui = b["ui"]
sl = {"routes": json.load(open(sys.argv[2].replace("infra_slice.json", "routes.json"), encoding="utf-8"))}
_decl = json.load(open(sys.argv[2].replace("infra_slice.json", "declarations.json"), encoding="utf-8"))
tabs = open(sys.argv[3], encoding="utf-8").read()
bad = []
secs = {x["id"]: x for x in ui.get("sections") or []}
def pids(i): return [p["id"] for p in (secs.get(i) or {}).get("pages") or []]
if pids("containers") != ["all", "cloudy", "cg", "capital", "hardtech", "cloud_me"]: bad.append("Containers pages are %s, not the project pages [all, cloudy, cg, capital, hardtech, cloud_me]" % pids("containers"))
for _p in (secs.get("containers") or {}).get("pages") or []:
    if [q["id"] for q in _p.get("pages") or []] != [_p["id"] + "_topology", _p["id"] + "_table"]:
        bad.append("Containers project %r lost its Topology | Table switch (nested pages %s)" % (_p["id"], [q["id"] for q in _p.get("pages") or []]))
if pids("ops") != ["service", "infrastructure", "orchestration", "network", "release", "agents"]: bad.append("Ops pages are %s, not the five topology views then Agents (Services and Tools are gone)" % pids("ops"))
if pids("infra") != ["providers", "machines", "serverless", "node_python", "storage", "desktop", "mobile", "gadgets", "domains", "domains_public"]: bad.append("Infra pages are %s, not the three declared rows (Bare, Personal, Domains)" % pids("infra"))
c3 = ui["carried_c3"]
topo, infra = c3.get("stack_topology") or [], []
for k in ("c3_public", "c3_private"):
    if any(p.get("kind") == k for p in topo): bad.append("Containers > Topology still embeds the %s address table" % k)
if any(p.get("kind") == "tile_row" and p.get("title") == "More" for p in topo + infra):
    bad.append("the More table is back on the Topology / Infra page (owner item 10: removed)")
if "stack_infra" in c3: bad.append("stack_infra is back: the Infra section is five fleet pages now (data/fleet_slice.json), not the Stack card")
if any(p.get("anchor") == "stack" for p in topo): bad.append("the Stack card is back on Containers > Topology (it is gone: Infra reads the fleet declarations)")
if '"table" -> RoutesTableFragment.newInstance(parentId)' not in tabs: bad.append("ContainersFragment resolves no RoutesTableFragment for the table page")
_rt = open(sys.argv[3].replace("TabFragments.kt", "RoutesTable.kt"), encoding="utf-8").read()
_rm = open(sys.argv[3].replace("TabFragments.kt", "RoutesTableModel.kt"), encoding="utf-8").read()
if "SearchBox(" not in _rt or "fun matches(" not in _rm or "state.query" not in _rm:
    bad.append("the Table page lost its search box / predicate (owner item 11)")
if "ProbeModel.light(" not in _rt or "LightSheet(" not in _rt or "DeclarationPage(" not in _rt:
    bad.append("the Table page lost its status light / breakdown sheet / More declaration page (owner item 13)")
if "onLongClick" not in _rt or "Copy & Open" not in _rt or "fun openTarget(" not in _rm:
    bad.append("the Table page lost its long-press Copy / Open / Copy & Open menu (owner item 12)")
if "InfraSectionFragment.newInstance(" not in tabs: bad.append("InfraFragment does not render the Infra pages through InfraSectionFragment")
routes = sl.get("routes") or []
if set(_decl) != {r["container"] for r in routes}: bad.append("data/declarations.json does not cover exactly the Table rows - run data/gen_infra_slice.py")
if len(routes) < 50: bad.append("data/routes.json carries %d routes - run data/gen_infra_slice.py" % len(routes))
want = {"category", "container", "public_url", "auth_type", "private_url", "vm_name", "vm_addr"}
for r in routes:
    if set(r) != want: bad.append("route %r has fields %s, not the seven columns" % (r.get("container"), sorted(r))); break
    if r["auth_type"] not in ("bearer", "woauth", "mesh", "open"): bad.append("route %r auth_type %r" % (r["container"], r["auth_type"])); break
    if not r["public_url"] and r["auth_type"] != "mesh": bad.append("route %r has no public URL but is not mesh-only" % r["container"]); break
if not {"bearer", "woauth", "mesh"} <= {r["auth_type"] for r in routes}:
    bad.append("the shipped routes lack one of the three auth types Bearer / WOAuth / Mesh-only")
for b_ in bad: print("    " + b_)
sys.exit(1 if bad else 0)
PYTHON
}

t1 "$BJ"                              && pass "T1 ui.bottom_nav is the five declared tabs, in order, Home centre" || fail "T1 the tab declaration is wrong"
t2 "$BJ" "$MAIN"                      && pass "T2 declaration <-> shell dispatch agree BOTH ways: no orphan either side" || fail "T2 declaration and dispatch disagree"
t3 "$BJ" "$SRC"                       && pass "T3 no parallel list of tab ids" || fail "T3 a second list of tab ids exists"
t4 "$BJ" "$DRAWABLE" "$SRC" "$UIICONS"         && pass "T4 every declared icon is a real drawable, and every drawable is declared" || fail "T4 an icon would draw blank, or a drawable is dead"
t5 "$BJ" "$MANIFEST" "$ROOT"          && pass "T5 the Apps tab launches the REAL siblings + cloud-c3-webserver, each queried, none renamed" || fail "T5 an Apps tile cannot open what it names"
t6 "$BJ" "$TABS" "$SRC"               && pass "T6 NO PLACEHOLDER: every declared page resolves to a real fragment, and no not-built body exists" || fail "T6 a shipped tab can render a placeholder"
t7 "$MAIN" "$NAVKT" "$SRC"            && pass "T7 the shell HOSTS FRAGMENTS and reuses libs:bottomnav's island" || fail "T7 the shell is not a fragment host, or forks the nav"
t9 "$BJ" "$SRC/AppsCatalog.kt" "$APP/data/infra_slice.json" && pass "T9 Services/Tools declaration is closed: tools, views and the derived slice agree" || fail "T9 apps_page and its renderers or slice disagree"
t8 "$MAIN"                           && pass "T8 the top inset is READ (systemBars u displayCutout) and never consumed" || fail "T8 the top-overflow fix is not the declared one"
t10 "$BJ" "$APP/app/build.gradle" "$SRC" && pass "T10 the first row is the shared AppTopBar (hamburger, AI, Account/Configs); no private top row, no Configs on the bottom nav" || fail "T10 the top bar is not the declared shared one"
t11 "$BJ" "$SRC/C3Nav.kt" "$SRC"     && pass "T11 the drawer is the full declared tree and every moved id still resolves (ui.aliases)" || fail "T11 drawer tree or aliases broken"
t12 "$BJ" "$APP/data/infra_slice.json" "$TABS" && pass "T12 Containers = Topology | Table, Infra = Providers | VMs | Storage | Electronics | Domains, no More table, Table rows carry the seven columns" || fail "T12 the Containers/Infra pages or the Table data are wrong"

# ── MUTATION PROOF ─────────────────────────────────────────────────────────
echo
echo "── mutation proof: each check must be able to go RED ──"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
MUT_FAIL=0
mutate() { # <name> <check-fn> <setup-fn>
    local name="$1" check="$2" setup="$3"
    rm -rf "$WORK/t"; mkdir -p "$WORK/t"
    cp -r "$APP" "$WORK/t/ac_cloud-c3"
    for sib in $(jq -r '.ui.external_apps[].id | "ac_" + .' "$APP/build.json"); do
        mkdir -p "$WORK/t/$sib"
        [ -f "$ROOT/$sib/build.json" ] && cp "$ROOT/$sib/build.json" "$WORK/t/$sib/build.json"
    done
    if ! "$check" "$WORK/t" >/dev/null 2>&1; then
        echo "  VOID  $name — the unmutated COPY is already red, so this mutation proves nothing"
        MUT_FAIL=$((MUT_FAIL + 1)); return
    fi
    if ! "$setup" "$WORK/t" >/dev/null 2>&1; then
        echo "  VOID  $name — the mutation itself failed to apply, so it proves nothing"
        MUT_FAIL=$((MUT_FAIL + 1)); return
    fi
    if "$check" "$WORK/t" >/dev/null 2>&1; then
        echo "  HOLLOW  $name — the check stayed GREEN under the mutation"
        MUT_FAIL=$((MUT_FAIL + 1))
    else
        echo "  RED     $name"
    fi
}

A="/ac_cloud-c3"
c_t1() { t1 "$1$A/build.json"; }
c_t2() { t2 "$1$A/build.json" "$1$A/app/src/main/java/com/diegonmarcos/cloudc3/MainActivity.kt"; }
c_t3() { t3 "$1$A/build.json" "$1$A/app/src/main/java/com/diegonmarcos/cloudc3"; }
c_t4() { t4 "$1$A/build.json" "$1$A/app/src/main/res/drawable" "$1$A/app/src/main/java/com/diegonmarcos/cloudc3" "$UIICONS"; }
c_t5() { t5 "$1$A/build.json" "$1$A/app/src/main/AndroidManifest.xml" "$1"; }
c_t6() { t6 "$1$A/build.json" "$1$A/app/src/main/java/com/diegonmarcos/cloudc3/pages/TabFragments.kt" "$1$A/app/src/main/java/com/diegonmarcos/cloudc3"; }
c_t7() { t7 "$1$A/app/src/main/java/com/diegonmarcos/cloudc3/MainActivity.kt" "$1$A/app/src/main/java/com/diegonmarcos/cloudc3/C3BottomNav.kt" "$1$A/app/src/main/java/com/diegonmarcos/cloudc3"; }
c_t8() { t8 "$1$A/app/src/main/java/com/diegonmarcos/cloudc3/MainActivity.kt"; }
c_t10() { t10 "$1$A/build.json" "$1$A/app/build.gradle" "$1$A/app/src/main/java/com/diegonmarcos/cloudc3"; }
c_t11() { t11 "$1$A/build.json" "$1$A/app/src/main/java/com/diegonmarcos/cloudc3/C3Nav.kt" "$1$A/app/src/main/java/com/diegonmarcos/cloudc3"; }
c_t12() { t12 "$1$A/build.json" "$1$A/data/infra_slice.json" "$1$A/app/src/main/java/com/diegonmarcos/cloudc3/pages/TabFragments.kt"; }

jqset() { python3 - "$1" "$2" <<'PY'
import json,sys,collections
p,expr=sys.argv[1],sys.argv[2]
d=json.load(open(p),object_pairs_hook=collections.OrderedDict)
exec(expr,{"d":d,"collections":collections,"sec":lambda i:next(s for s in d["ui"]["sections"] if s["id"]==i)})
json.dump(d,open(p,"w"),indent=2)
PY
}

m_sixth_tab()   { jqset "$1$A/build.json" 'd["ui"]["sections"].append(collections.OrderedDict([("id","extra"),("label","Extra"),("icon","ic_home")])); d["ui"]["bottom_nav"].append("extra")'; }
m_home_offset() { jqset "$1$A/build.json" 't=d["ui"]["bottom_nav"]; t[2],t[4]=t[4],t[2]'; }
m_drop_branch() { python3 - "$1$A/app/src/main/java/com/diegonmarcos/cloudc3/MainActivity.kt" <<'PY'
import re,sys
p=sys.argv[1]; s=open(p).read(); b=s
s=re.sub(r'\n\s*"configs" -> ConfigsFragment\(\)','',s)
assert s!=b, "branch not found"
open(p,"w").write(s)
PY
}
m_orphan_branch() { python3 - "$1$A/app/src/main/java/com/diegonmarcos/cloudc3/MainActivity.kt" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read(); b=s
s=s.replace('"configs" -> ConfigsFragment()','"configs" -> ConfigsFragment()\n            "ghost" -> HomeFragment()')
assert s!=b
open(p,"w").write(s)
PY
}
m_parallel()    { python3 - "$1$A/app/src/main/java/com/diegonmarcos/cloudc3/C3BottomNav.kt" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read(); b=s
s=s.replace("object C3BottomNav {",'object C3BottomNav {\n    val order = listOf("infra", "containers", "home", "ops", "observ")\n')
assert s!=b
open(p,"w").write(s)
PY
}
m_bad_icon()    { jqset "$1$A/build.json" 'd["ui"]["sections"][0]["icon"]="ic_does_not_exist"'; }
m_bad_catalog_icon() { jqset "$1$A/build.json" 'sec("configs")["pages"][1]["icon"]="not_in_the_catalog"'; }
m_dead_drawable() { cp "$1$A/app/src/main/res/drawable/ic_home.xml" "$1$A/app/src/main/res/drawable/ic_unused_ghost.xml"; }
m_wrong_pkg()   { jqset "$1$A/build.json" 'd["ui"]["external_apps"][0]["package"]="com.diegonmarcos.c3watchdog"'; }
m_add_label()   { jqset "$1$A/build.json" 'd["ui"]["external_apps"][0]["label"]="Watchdog"'; }
m_drop_query()  { python3 - "$1$A/app/src/main/AndroidManifest.xml" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read(); b=s
s=s.replace('        <package android:name="com.diegonmarcos.watchdog" />\n','')
assert s!=b
open(p,"w").write(s)
PY
}
m_drop_websrv() { jqset "$1$A/build.json" 'd["ui"]["external_apps"]=[a for a in d["ui"]["external_apps"] if a["id"]!="cloud-c3-webserver"]'; }
m_websrv_pkg()  { jqset "$1$A/build.json" 'next(a for a in d["ui"]["external_apps"] if a["id"]=="cloud-c3-webserver")["package"]="com.diegonmarcos.webserver"'; }
m_websrv_noq()  { python3 - "$1$A/app/src/main/AndroidManifest.xml" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read(); b=s
s=s.replace('        <package android:name="com.diegonmarcos.cloudwebserver" />\n','')
assert s!=b
open(p,"w").write(s)
PY
}
# T6: the three shapes of "a shipped tab is empty"
m_declare_unbuilt() { jqset "$1$A/build.json" 'sec("observ")["pages"].append(collections.OrderedDict([("id","workflows"),("label","Workflows"),("icon","ic_p_c3_workflows")]))'; }
m_placeholder_body() { python3 - "$1$A/app/src/main/java/com/diegonmarcos/cloudc3/pages/TabFragments.kt" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read(); b=s
s=s.replace('        "health" -> C3HealthFragment.newInstance(C3HealthFragment.SCOPE_ALL)',
            '        "health" -> NotBuiltYet()')
assert s!=b
open(p,"w").write(s)
PY
}
m_escape_flag() { jqset "$1$A/build.json" 'sec("observ")["pages"][0]["implemented"]=False'; }
m_empty_tab()   { jqset "$1$A/build.json" 'sec("observ")["pages"]=[]'; }
# T7 / T8
m_compose_screen() { printf 'package com.diegonmarcos.cloudc3.pages\nimport androidx.compose.runtime.Composable\n@Composable\nfun Reauthored(c: android.content.Context) { android.widget.TextView(c) }\n' > "$1$A/app/src/main/java/com/diegonmarcos/cloudc3/pages/Reauthored.kt"; }
m_consume_insets() { python3 - "$1$A/app/src/main/java/com/diegonmarcos/cloudc3/MainActivity.kt" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read(); b=s
s=s.replace("WindowInsetsSides.Top + WindowInsetsSides.Horizontal","WindowInsetsSides.Vertical + WindowInsetsSides.Horizontal")
assert s!=b
open(p,"w").write(s)
PY
}
m_drop_cutout() { python3 - "$1$A/app/src/main/java/com/diegonmarcos/cloudc3/MainActivity.kt" <<'PY'
import re,sys
p=sys.argv[1]; s=open(p).read(); b=s
s=s.replace(".union(WindowInsets.displayCutout)","")
s=re.sub(r"import [^\n]*displayCutout\n","",s)
s=re.sub(r"import [^\n]*\.union\n","",s)
assert s!=b
open(p,"w").write(s)
PY
}
m_hardcode_top() { python3 - "$1$A/app/src/main/java/com/diegonmarcos/cloudc3/MainActivity.kt" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read(); b=s
s=s.replace("Modifier.fillMaxSize().themeBackground(dark = true)","Modifier.fillMaxSize().themeBackground(dark = true).padding(top = 36.dp)")
assert s!=b
open(p,"w").write(s)
PY
}

# T10 / T11 / T12
m_no_topbar()    { jqset "$1$A/build.json" 'del d["ui"]["top_bar"]'; }
m_configs_nav()  { jqset "$1$A/build.json" 'd["ui"]["bottom_nav"][4]="configs"'; }
m_own_hamburger() { printf 'package com.diegonmarcos.cloudc3\nimport androidx.compose.material.icons.Icons\nval privateMenu = Icons.Filled.Menu\n' > "$1$A/app/src/main/java/com/diegonmarcos/cloudc3/OwnTopRow.kt"; }
m_no_ai_panel()  { python3 - "$1$A/app/src/main/java/com/diegonmarcos/cloudc3/MainActivity.kt" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read(); b=s
s=s.replace("AiFleetChatPanel(","AiOther(")
assert s!=b
open(p,"w").write(s)
PY
}
m_default_topology() { jqset "$1$A/build.json" 'd["ui"]["default_section"]="containers"'; }
m_alias_dead()   { jqset "$1$A/build.json" 'd["ui"]["aliases"]["sections"]["apps"]="nowhere"'; }
m_alias_gone()   { jqset "$1$A/build.json" 'del d["ui"]["aliases"]["pages"]["public"]'; }
m_more_back()    { jqset "$1$A/build.json" 'd["ui"]["carried_c3"]["stack_topology"].append(collections.OrderedDict([("kind","tile_row"),("title","More"),("tiles",[])]))'; }
m_addr_back()    { jqset "$1$A/build.json" 'd["ui"]["carried_c3"]["stack_topology"].append(collections.OrderedDict([("kind","c3_public"),("title","Public")]))'; }
m_table_dropped() { jqset "$1$A/build.json" 'for p in sec("containers")["pages"]: p["pages"]=[q for q in p["pages"] if not q["id"].endswith("_table")]'; }
m_route_field()  { jqset "$1$A/data/routes.json" 'del d[0]["vm_addr"]'; }
m_route_authless() { jqset "$1$A/data/routes.json" 'd[0]["auth_type"]="anything"'; }
m_decl_gone()    { jqset "$1$A/data/declarations.json" 'd.pop(next(iter(d)))'; }

mutate "a SIXTH tab appears in the declaration"           c_t1 m_sixth_tab
mutate "Home is moved off the centre position"            c_t1 m_home_offset
mutate "a declared tab loses its dispatch branch"         c_t2 m_drop_branch
mutate "a screen is dispatched that nothing declares"     c_t2 m_orphan_branch
mutate "a parallel list of tab ids is added"              c_t3 m_parallel
mutate "a tab declares an icon with no drawable"          c_t4 m_bad_icon
mutate "a catalog icon name the catalog lacks"             c_t4 m_bad_catalog_icon
mutate "a drawable is added that nothing declares"        c_t4 m_dead_drawable
mutate "an Apps tile names a package no sibling has"      c_t5 m_wrong_pkg
mutate "an Apps tile regains a display label (#224)"      c_t5 m_add_label
mutate "a sibling package is dropped from <queries>"      c_t5 m_drop_query
mutate "cloud-c3-webserver is dropped from the Apps tab"  c_t5 m_drop_websrv
mutate "the webserver tile names the wrong package"       c_t5 m_websrv_pkg
mutate "the webserver package is dropped from <queries>"  c_t5 m_websrv_noq
mutate "an UNBUILT page is declared (the empty tab)"      c_t6 m_declare_unbuilt
mutate "a declared page resolves to a not-built body"     c_t6 m_placeholder_body
mutate "an 'implemented:false' escape hatch is added"     c_t6 m_escape_flag
mutate "a content tab is declared with zero pages"        c_t6 m_empty_tab
mutate "a Compose file also builds a View (2026-10-15)"   c_t7 m_compose_screen
mutate "the shell takes the BOTTOM inset (#477)"          c_t8 m_consume_insets
mutate "the cutout is dropped from the inset read (#407)" c_t8 m_drop_cutout
mutate "a tuned top padding is hardcoded in the shell"    c_t8 m_hardcode_top
mutate "the shared top bar declaration is deleted"        c_t10 m_no_topbar
mutate "Configs becomes an entry of the bottom nav"       c_t10 m_configs_nav
mutate "a private hamburger is drawn beside the bar"      c_t10 m_own_hamburger
mutate "the AI button loses the shared chat"              c_t10 m_no_ai_panel
mutate "the app opens on Containers instead of Home"      c_t1 m_default_topology
mutate "an old section id aliases to a section that is gone" c_t11 m_alias_dead
mutate "an old page id (public) loses its alias"          c_t11 m_alias_gone
mutate "the More table comes back on Topology"            c_t12 m_more_back
mutate "an address table comes back on Topology"          c_t12 m_addr_back
mutate "the Table page is dropped from Containers"        c_t12 m_table_dropped
mutate "a route loses a Table column"                     c_t12 m_route_field
mutate "a route carries an unknown auth type"             c_t12 m_route_authless
mutate "a row loses its declaration page data"            c_t12 m_decl_gone

echo
if [ "$FAILURES" -ne 0 ] || [ "$MUT_FAIL" -ne 0 ]; then
    echo "FAIL  $FAILURES assertion(s) red, $MUT_FAIL mutation(s) void or hollow"
    exit 1
fi
echo "PASS  12 properties asserted, 34 mutations each proved able to go red"
