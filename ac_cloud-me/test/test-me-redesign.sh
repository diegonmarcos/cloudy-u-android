#!/usr/bin/env bash
# Tester (#1144, #1150, #1367, #1901): Cloud Me's six-section bar (Pers, Prof, Proj, Inventory, Buro, LLC), the Samsung Health page tabs, the fleet top bar (hamburger,
# AI, Profile, Configs), the Achieves text, the tap-only side menu (no edge-swipe), the Account launch and the removed
# Wallet link, plus the LLC / Buro / Tasks page shapes, the Tasks redirects and the Inventory rows, seed and summary.
# Structure and data only; the RENDERED strip, bar and top bar are measured by app/src/test/.../MeTabsTest.
# Every check is proven red by its own mutation of a copy of the tree (the tail of this file).
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

# check_tree <app dir> <libs dir> : prints one "ID<TAB>problem" line per failure, nothing when green.
check_tree() {
python3 - "$1" "$2" <<'PY'
import json, os, re, sys

app, libs = sys.argv[1], sys.argv[2]
bj = json.load(open(os.path.join(app, "build.json")))
ui = bj["ui"]
secs = {s["id"]: s for s in ui["sections"]}
MAIN = os.path.join(app, "app/src/main")
out = []
def bad(i, m): out.append(f"{i}\t{m}")

def code(path):
    t = open(path, encoding="utf-8").read()
    t = re.sub(r"/\*.*?\*/", "", t, flags=re.S)
    return re.sub(r"(?m)^\s*//.*$", "", t)

def kt(root):
    for r, _, fs in os.walk(root):
        for f in fs:
            if f.endswith(".kt"):
                yield os.path.join(r, f)

def pages(ps):
    for p in ps or []:
        yield p
        yield from pages(p.get("pages"))

def strings(o):
    if isinstance(o, str): yield o
    elif isinstance(o, dict):
        for v in o.values(): yield from strings(v)
    elif isinstance(o, list):
        for v in o: yield from strings(v)

# ---- N1 nav order
want_ids = ["pers", "prof", "proj", "lnktree", "inventory", "buro", "llc"]
want_labels = ["Pers", "Prof", "Proj", "Lnktree", "Inventory", "Buro", "LLC"]
if ui.get("bottom_nav") != want_ids:
    bad("nav-order", f"ui.bottom_nav is {ui.get('bottom_nav')}, wanted {want_ids}")
if [secs[i]["label"] for i in ui.get("bottom_nav", []) if i in secs] != want_labels:
    bad("nav-order", f"the bar labels are not {want_labels}")

# ---- N2 tab style: icon-only, the selected one also shows its name
# #1154 the strip is libs:ui-nav-bars's (the owner moved the second row out of libs:bottomnav)
strip = os.path.join(libs, "ui-nav-bars/src/main/kotlin/com/diegonmarcos/superapp/navbartop2/NavBarTopSecondRow.kt")
tabs = strip
if not os.path.exists(strip):
    bad("tab-style", "libs:ui-nav-bars has no NavBarTopSecondRow.kt")
else:
    t = code(strip)
    if "SecondRowTags.icon(" not in t:
        bad("tab-style", "the icon pill does not draw an icon for every tab")
    # #1159 (owner, supersedes #1144's selected-only name): at rest EVERY tab shows its name, scrolled down (compact) only the selected one.
    if "named = !compact || page.id == selectedId" not in t:
        bad("tab-style", "the tab name is not gated by `named = !compact || page.id == selectedId` - every tab shows it at rest, only the selected one when compact")
    if not re.search(r"AnimatedVisibility\(\s*named", t):
        bad("tab-style", "the tab name is not drawn inside AnimatedVisibility(named)")
    if len(re.findall(r"page\.label", t)) != len(re.findall(r"AnimatedVisibility\(\s*named,[^{]*\{\s*Text\(\s*page\.label", t)) + len(re.findall(r"contentDescription = page\.label", t)):
        bad("tab-style", "a tab draws its name outside the `named` gate")
if "iconFor" not in code(tabs):
    bad("tab-style", "the second row does not take iconFor (icon-only pills)")
sec = code(os.path.join(MAIN, "java/com/diegonmarcos/cloudme/SectionScreen.kt"))
i = sec.find("NavBarTopSecondRow(")
if i < 0 or not re.search(r"\biconFor\s*=", sec[i:i + 2500]):
    bad("tab-style", "SectionScreen draws its strips without iconFor (no icon-only pills)")
drawables = {n[:-4] for n in os.listdir(os.path.join(MAIN, "res/drawable")) if n.endswith(".xml")}
# A tab icon is an app drawable OR a name of the shared catalog (libs:ui-icons), which the second row draws when no drawable exists.
_cat = os.path.join(app, "../ab_cloud-libs-shared/libs/ui-icons/src/main/kotlin/com/diegonmarcos/superapp/uiicons/UiIcons.kt")
if os.path.isfile(_cat):
    drawables |= set(re.findall(r'"([a-z0-9_]+)"\s+to\s', open(_cat, encoding="utf-8").read()))
for s in secs.values():
    if len(s.get("pages", [])) > 1 or any(len(p.get("pages", [])) > 1 for p in pages(s.get("pages"))):
        for p in pages(s["pages"]):
            if not p.get("icon") or p["icon"] not in drawables:
                bad("tab-style", f"tab {s['id']}/{p['id']} has no drawable icon ({p.get('icon')!r}); an icon-only tab would be blank")

# ---- N3 top bar: the fleet's AppTopBar, inline actions; ui.settings names the sections Profile and Configs open
st = ui.get("settings")
if st != ["profile", "configs"] or [secs[i]["label"] for i in (st or []) if i in secs] != ["Profile", "Configs"]:
    bad("settings", f"ui.settings is {st}; wanted the sections Profile (#1439, libs:account-ui) and Configs, in that order")
for p in kt(MAIN):
    if re.search(r"\bDropdownMenu\b|Icons\.Filled\.Settings|Icons\.[A-Za-z.]*\.Menu\b", code(p)):
        bad("settings", f"{os.path.relpath(p, app)} draws a Settings dropdown or its own hamburger")
bar_src = code(os.path.join(MAIN, "java/com/diegonmarcos/cloudme/MeTopBar.kt"))
if not re.search(r"inlineActions\s*=\s*true", bar_src) or not re.search(r"drawer\s*=\s*true", bar_src):
    bad("settings", "MeTopBar does not declare the hamburger and the inline AI / Profile / Configs icons")
if "NavBarTopFirstRowScaffold(" not in code(os.path.join(MAIN, "java/com/diegonmarcos/cloudme/MeShell.kt")):
    bad("settings", "MeShell does not draw the fleet first row (NavBarTopFirstRowScaffold)")
if "MeShell(" not in code(os.path.join(MAIN, "java/com/diegonmarcos/cloudme/MainActivity.kt")):
    bad("settings", "MainActivity does not draw MeShell")

# ---- N4 achieves: the owner's text, byte for byte, now inside the #1350 tabs (B1 in Personal Growth, B2 in Endurance, B3 in Geo)
ach = {}
ACH_FILE = {"personal": "growth", "pursuits": "endurance", "international": "geo"}
for pid in ("personal", "pursuits", "international"):
    f = os.path.join(app, f"data/ui/pers/achieves/{ACH_FILE[pid]}.json")
    ach[pid] = json.load(open(f, encoding="utf-8")) if os.path.exists(f) else []
    if not ach[pid]:
        bad("achieves", f"data/ui/pers/achieves/{ACH_FILE[pid]}.json is missing or empty")
ACH_TABS = ["geo", "endurance", "growth", "community", "by30", "by40", "by50"]
# #1901 Personal Archives (the Achieves tabs) is a subpage of Pers: Profiles, Health, Astro, DD, Personal Archives; Study and Work stay under Prof > Profiles
_exp = secs.get("pers", {})
_sub = {p["id"]: p for p in _exp.get("pages", [])}
if [p["id"] for p in _exp.get("pages", [])] != ["pers-profiles", "health", "astro", "dd", "achieves"] or [p["label"] for p in _exp.get("pages", [])] != ["Profiles", "Health", "Astro", "DD", "Personal Archives"]:
    bad("achieves", "Pers is not the five subpages Profiles, Health, Astro, DD, Personal Archives")
if "study" in secs or "achieves" in secs or "experiences" in secs:
    bad("achieves", "Study, Achieves or Experiences is still a section of its own")
_ach = _sub.get("achieves", {})
if [p["id"] for p in _ach.get("pages", [])] != ACH_TABS or \
   [p["label"] for p in _ach.get("pages", [])] != ["Geo", "Endurance", "Personal Growth", "Community", "By 30", "By 40", "By 50"]:
    bad("achieves", "Achieves pages are not Geo, Endurance, Personal Growth, Community, By 30, By 40, By 50")
EXPECT = {
 "personal": [
  "B1) Personal",
  "Modern Physics (Quantum and Particle), Global Macro, Mediterranean and Vegetarian Cooking, Backpacking, Jazzy Music.",
  "Hiking, Rock Climbing, Cycling, Skydiving and Bungee Jump.",
  "Tennis (5y), Handball (3y), Rugby (1y) and Waterpolo (1y).",
  "Aikido, Yoga and Meditation.",
  "Oceans Seven Endurance, pre-Tour de France cycling and Seven Summits hike.",
  "INTERESTS", "ENDURANCE", "COMPETING", "MINDFULNESS", "PURSUITS"],
 "pursuits": [
  "B2) Personal Pursuits", "@brief", "@projects",
  "Modern Physics (Quantum and Particle), Global Macro, Mediterranean and Vegetarian Cooking, Backpacking, Jazzy Music.",
  "It is not death that a man should fear, but he should fear never beginning to live.", "Epictetus",
  "🪂 High Adrenaline Sports", "🏂 Adrenaline Sports", "🏔️ Nature Sports and Activities", "🥋 Martial Arts", "⚽️ Collective Sports", "🏃 Individual Sports",
  "My Bucket 50 (things before 50)",
  "🌿 Personal Growth, 🌐 Travel & Adventure, 🌍 Health & Wellness, 💼 Career and Finance, ❤️ Relationship & Community, ✨ Creativity & Fun",
  "🌱 (By 30) Foundational goals, 🌳 (By 40) Mastery & long-term planning, 🍁 (By 50) Legacy goals",
  "🚵🏻‍♂️ Trail Moto | 🪂 Skydiving | 🧗🏻‍♂️ Rock Climbing | 🏂🏻 Snowboarding",
  "🥾 Hiking | 🏄🏻‍♂️ Wakeboard | 🛹 Skating",
  "🏕️ Camping | 🏄🏻‍♂️ Surfing | ⚓ Scuba Diving | ⛵ Sailing",
  "🥋 Aikido",
  "🤾🏻‍♂️ Handball | 🏐 Volleyball | 🏉 Rugby | 🤽🏻‍♂️ Waterpolo",
  "⚽ Futebol",
  "🎾 Tennis | 🏊🏻‍♂️ Swimming | 🚲 Cycling | 🏃🏻‍♂️ Running | 🚴🏻‍♂️🏊🏻‍♂️ Biathlon",
  "⛳ Golf | ⛸️ Ice Lake Skying"],
 "international": [
  "B3) International Experience (Dec 2020 – Present)", "@brief", "@projects",
  "For 5 years, travelled to 180+ cities (110+ NM regions) over 45+ countries across 5 continents; lived in 5 European cities, born and raised in São Paulo.",
  "A ship in harbor is safer, but that is not what ships are built for.", "John A. Shedd",
  "Portuguese, English and Spanish", "Gallego", "Deutsche", "Catalán, French and Italian.",
  "Born and raised in São Paulo, the 5th biggest city in the world",
  "On long stays lived (studying and working remotely) in 5 European cities: Berlin and Barcelona; London, Valencia and Paris",
  "Also took long stays in: Rio de Janeiro, Casablanca and Chiang Mai",
  "On short stays traveled over 180+ cities, 110+ NomadMania Regions, 45+ countries across 5 continents",
  "Visited: World Heritage Sites (60+), New Seven Wonder+Pyramids (3/8) and The Seven Natural WW (1/7)",
  "Places {175 Cities (108 NM) | 43 Countries}",
  "EU", "102 (70)", "⚙️ Germanic", "🪆 Slavic", "🏛️ Romanic-Greek", "🏔️ Non-Euro", "NORTH/SOUTH AMERICA", "51 (22)",
  "🗽 Anglo-Franco", "🪭 Spanish-Aztec/Inca", "🎉 Romance-African", "OCEANIA", "🦘 Anglo-Aborigenal", "🪶 Anglo-Maori", "🗿 Poly-Micronesia",
  "ASIA", "13 (9)", "🕯️ Abrahamic", "🧘🏻‍♂️ Hindu;Buddhist;Tao/Conf", "AFRICA", "9 (4)", "🫖 North-Maghreb", "🥁 Francosphere", "💎 Lusosphere", "🪨 Anglosphere"],
}
for pid, want in EXPECT.items():
    have = set(strings(ach[pid]))
    for w in want:
        if w not in have:
            bad("achieves", f"{pid}: the owner's text is missing or altered: {w!r}")
tbl = [b for b in ach["international"] if isinstance(b, dict) and b.get("kind") == "table"]
if not tbl or (tbl[0].get("link") or {}).get("url") != "https://nomadmania.com/profile/73889":
    bad("achieves", "the NomadMania region table has no link to https://nomadmania.com/profile/73889")
elif len(tbl[0].get("rows", [])) != 21:
    bad("achieves", f"the NomadMania table has {len(tbl[0].get('rows', []))} rows, the owner's text has 21")
stack = code(os.path.join(MAIN, "java/com/diegonmarcos/cloudme/StackScreen.kt"))
kinds = set(re.findall(r'^\s*"([a-z_]+)"(?:\s*,\s*"[a-z_]+")*\s*->', stack, flags=re.M))
for pid, blocks in ach.items():
    for b in blocks:
        if isinstance(b, dict) and b.get("kind") not in kinds:
            bad("achieves", f"{pid}: block kind {b.get('kind')!r} has no renderer in StackScreen")

# ---- N5 the edge-swipe drawer is gone for good
FORBID = r"ModalNavigationDrawer|DismissibleNavigationDrawer|PermanentNavigationDrawer|rememberDrawerState|DrawerValue|ModalDrawerSheet|NavigationDrawerItem|detectHorizontalDragGestures|detectDragGestures|\bswipeable\b|\banchoredDraggable\b|\bdraggable\b|GestureDetector|OnTouchListener|setOnTouchListener|DrawerLayout|ic_hamburger|gesturesEnabled"
for p in kt(MAIN):
    m = re.search(FORBID, code(p))
    if m:
        bad("no-drawer", f"{os.path.relpath(p, app)} carries `{m.group(0)}`: a swipe-to-open drawer handler")
if re.search(r"fun\s+drawer\s*\(", code(os.path.join(MAIN, "java/com/diegonmarcos/cloudme/Sections.kt"))):
    bad("no-drawer", "Sections still declares a drawer()")
html = open(os.path.join(app, "data/web/mysocials/linkedin.html"), encoding="utf-8").read()
m = re.search(r"touchstart|touchmove|touchend|swipe|drawer|hamburger", html, flags=re.I)
if m:
    bad("no-drawer", f"the Profile page (linkedin.html) carries `{m.group(0)}`: a touch or drawer handler")
for r, _, fs in os.walk(os.path.join(MAIN, "res")):
    for f in fs:
        if "drawer" in f.lower() or "hamburger" in f.lower():
            bad("no-drawer", f"resource {f} is a drawer leftover")

# the lib's side menu opens by tap: no drag / swipe API in the top bar that draws it
lib_bar = os.path.join(libs, "bottomnav/src/main/kotlin/com/diegonmarcos/superapp/bottomnav/AppTopBar.kt")
lib_row = os.path.join(libs, "ui-nav-bars/src/main/kotlin/com/diegonmarcos/superapp/navbartop1/NavBarTopFirstRow.kt")
for lib_bar in (lib_bar, lib_row):
  if os.path.exists(lib_bar):
    m = re.search(r"detectHorizontalDragGestures|detectDragGestures|\bswipeable\b|\banchoredDraggable\b|\bdraggable\b|GestureDetector|gesturesEnabled", code(lib_bar))
    if m:
        bad("no-drawer", f"the lib AppTopBar carries `{m.group(0)}`: the side menu must open by tap only")

# ---- N7a House is gone and Work is LLC; Tasks moved from Profile to Duties; Trips is under Achieves
if "home" in secs:
    bad("pages", "the Home section is back")
for gone in ("house", "work"):
    if gone in secs or gone in ui.get("bottom_nav", []):
        bad("pages", f"the retired {gone} section is declared again")
    if os.path.isdir(os.path.join(app, "data/ui", gone)):
        bad("pages", f"data/ui/{gone} is back")
# #1901 the Lnktree section is gone: Prof = Profiles | Leaf.y, Nexus.z, Stark.x; Pers holds Profiles, Health, Astro, DD, Personal Archives; Proj = Lnktree | the areas
_lt = secs.get("lnktree", {})   # #1901 correction: the Lnktree bottom-nav entry is restored with its sixteen pages (lt- prefix, N12)
if "lnktree" not in ui.get("bottom_nav", []) or [p["id"] for p in _lt.get("pages", [])] != ["lt-" + x for x in ["lnktree-site", "professional", "leafy", "nexus", "stark", "personal", "social", "health", "dd", "astro", "area-unix", "area-cg", "area-capital", "area-hardtech", "area-lecole42", "area-front"]]:
    bad("pages", "the Lnktree bottom-nav entry with its sixteen pages is missing")
_pf = secs.get("prof", {})
if [p["id"] for p in _pf.get("pages", [])] != ["prof-web", "prof-profiles", "leafy", "nexus", "stark"] or \
   [p["id"] for p in (_pf.get("pages") or [{}])[0].get("pages", [])] != ["prof-linkedin", "prof-cv"] or \
   [p["id"] for p in (_pf.get("pages") or [{}, {}])[1].get("pages", [])] != ["professional", "work", "study"] or \
   {g["id"]: g["pages"] for g in _pf.get("groups", [])} != {"profiles": ["prof-web", "prof-profiles"], "companies": ["leafy", "nexus", "stark"]}:
    bad("pages", "Prof is not Profiles (Professional, Work, Study) | Leaf.y, Nexus.z, Stark.x")
_pj = secs.get("proj", {})
if [p["id"] for p in _pj.get("pages", [])] != ["pj-home", "pj-unix", "pj-cg", "pj-capital", "pj-hardtech", "pj-wiki", "pj-projects", "pj-lecole42", "pj-repos"]:
    bad("pages", "Proj pages are not Home, Unix, CG, Capital, Hardtech, Wiki, Projects, 42 (#1914)")
if ui.get("default_section") != "lnktree" or (_lt.get("pages") or [{}])[0].get("id") != "lt-lnktree-site" or (_pj.get("pages") or [{}])[0].get("id") != "pj-home":
    bad("pages", "the app does not open on Lnktree, the Lnktree entry on its Lnktree site, or Proj on its Home (#1914)")
_pr = {p["id"]: p for p in _sub.get("pers-profiles", {}).get("pages", [])}
if list(_pr) != ["personal", "ig0", "ig1", "ig2", "face0", "pinterest", "youtube", "tidal", "linkedin", "orkut"]:
    bad("pages", "Pers > Profiles is not Personal, ig0, ig1, ig2, face0, Pinterest, YouTube, Tidal, LinkedIn, Orkut (#1913)")
if [p["id"] for p in _sub.get("health", {}).get("pages", [])] != ["trackers", "plan", "health-profile"]:
    bad("pages", "Pers > Health is not Trackers, Plan, Record")
for _pid, _u, _rel in (("leafy", "https://diegonmarcos.github.io/leafy/", "prof/leafy"), ("nexus", "https://diegonmarcos.github.io/nexus/", "prof/nexus"), ("stark", "https://diegonmarcos.github.io/stark/", "prof/stark"), ("astro", "https://diegonmarcos.github.io/myAstro/", "pers/astro"), ("personal", "https://diegonmarcos.com/", "pers/pers-profiles/personal"), ("lt-lnktree-site", "https://linktree.diegonmarcos.com/", "lnktree/lt-lnktree-site")):
    _f = os.path.join(app, f"data/ui/{_rel}.json")
    _b = json.load(open(_f, encoding="utf-8")) if os.path.exists(_f) else []
    if len(_b) != 1 or _b[0].get("id") != "web" or _b[0].get("url") != _u:
        bad("pages", f"{_rel} is not the embedded page {_u}")
for _t in ACH_TABS:
    _ex = os.path.join(app, f"data/ui/pers/achieves/{_t}.json")
    _eb = [b for b in (json.load(open(_ex, encoding="utf-8")) if os.path.exists(_ex) else []) if b.get("kind") == "ledger"]
    _ls = _eb[0].get("ledgers", []) if len(_eb) == 1 else []
    if len(_eb) != 1 or _eb[0].get("store") != "experiences_user" or [l["collection"] for l in _ls] != [f"{_t}_done", f"{_t}_todo"] \
       or [l.get("move_to") for l in _ls] != [f"{_t}_todo", f"{_t}_done"] or _ls[1].get("move_stamp") != "date":
        bad("pages", f"Achieves > {_t} is not one Done/To do ledger over experiences_user that toggles both ways and stamps the date")
_geo = [b for b in json.load(open(os.path.join(app, "data/ui/pers/achieves/geo.json"), encoding="utf-8")) if b.get("kind") == "ledger"]
if not _geo or (_geo[0].get("geo") or {}).get("visited") != "geo_done" or (_geo[0].get("geo") or {}).get("wishlist") != "geo_todo":
    bad("pages", "Achieves > Geo has no live stats over geo_done and its geo_todo wishlist")
_m = _exp.get("migration") or {}
if _m.get("store") != "experiences_user" or set(_m.get("legacy", {})) != {"done", "todo"} or \
   any(t not in ACH_TABS for t in list(_m.get("by_value", {}).values()) + [_m.get("default")]):
    bad("pages", "ui.sections[pers].migration does not move the old done/todo records to declared tabs")
_fc = json.load(open(os.path.join(libs, "../../fleet-private-data/fleet-config.json"), encoding="utf-8"))
_st = _fc.get("stores", {}).get("experiences_user")
if not _st or "ac_cloud-me" not in _st.get("used_by", []) or _st.get("class") != "config":
    bad("pages", "experiences_user is not a declared config store of ac_cloud-me (the backup would miss it)")
_tb = os.path.join(app, "data/ui/tasks/backlogs.json")
if [p["id"] for p in secs.get("tasks", {}).get("pages", [])] != ["roadmaps", "backlogs"] or \
   (json.load(open(_tb, encoding="utf-8")) if os.path.exists(_tb) else [{}])[0].get("id") != "tasks":
    bad("pages", "Tasks is not RoadMaps then Backlogs, Backlogs drawing the tasks view (the former Duties)")
# #1771/#1901 the Socials tabs moved whole: Professional is Prof > Profiles > Professional, Personal (the Instagram page) is Pers > Profiles > Instagram,
# both drawing the mysocials web pages; Tasks stays declared off the bar with every page, so nothing is dropped.
if "socials" in secs or "socials" in ui.get("bottom_nav", []) or os.path.isdir(os.path.join(app, "data/ui/socials")):
    bad("pages", "the Socials section is declared again (it is Proj; its two tabs live in Prof and Pers)")
for _pg, _url, _rel in (("professional", "https://linkedin.com/in/diegonmarcos", "prof/prof-profiles/professional"), ("ig0", "https://diegonmarcos.github.io/mySocials/instagram-diegocnmarcos_.html", "pers/pers-profiles/ig0")):
    _sf = os.path.join(app, f"data/ui/{_rel}.json")
    _sj = json.load(open(_sf, encoding="utf-8")) if os.path.exists(_sf) else [{}]
    if _sj[0].get("kind") != "fragment" or _sj[0].get("id") != "web" or _sj[0].get("url") != _url:
        bad("pages", f"{_rel} does not embed {_url} (#1911)")
if "tasks" in ui.get("bottom_nav", []) or "tasks" not in secs or [p["id"] for p in secs["tasks"].get("pages", [])] != ["roadmaps", "backlogs"] or \
   [g["id"] for g in secs["tasks"].get("groups", [])] != ["backlogs", "roadmaps"]:
    bad("pages", "Tasks left the bar but must stay a declared section with Tree and Due and its two groups (nothing dropped)")
for _l, _sec in (("professional", "prof"), ("personal", "pers")):
    if _l not in [p["id"] for p in pages(secs.get(_sec, {}).get("pages"))]:
        bad("pages", f"{_sec} lost its {_l} page")
for _gone in ("roadmap", "duties"):
    if _gone in secs or os.path.isdir(os.path.join(app, f"data/ui/{_gone}")):
        bad("pages", f"the retired {_gone} section (or its data/ui folder) is back")
if os.path.isdir(os.path.join(app, "data/ui/home")):
    bad("pages", "data/ui/home is back")
blob_all = "".join(open(os.path.join(r, f), encoding="utf-8").read() for r, _, fs in os.walk(os.path.join(app, "data/ui")) for f in fs if f.endswith(".json"))
for dead in ("page:home/", "page:house/", "page:work/", "page:me/tasks"):
    if dead in blob_all:
        bad("pages", f"a page still targets {dead}...")

# the many_rows contract of Buro and LLC: every flat page in exactly one group, groups have a label and an icon from libs:ui-icons
icons = open(os.path.join(libs, "ui-icons/src/main/kotlin/com/diegonmarcos/superapp/uiicons/UiIcons.kt"), encoding="utf-8").read()
want_groups = {
    "buro": {"iddd": ["ids", "personal-dd", "paycards", "autocomplete", "wallet", "account"], "sec-web": ["logins", "totp", "passkeys"], "sec-cli": ["ssh", "apitokens", "jwt", "certs"], "cfg": ["repos", "android", "desktop", "c3", "backup"], "financial": ["accounts", "cards", "statements", "ir", "cashflow", "budgeting", "services"], "portfolio": ["assets", "liabilities", "nav-value", "performance"], "events": ["events-list", "calendar"]},
    "llc": {"income": ["employed", "side-hustles"], "manage": ["company-assets", "portfolio"], "calendar": ["llc-calendar"]},
}
for sid, want in want_groups.items():
    sec = secs.get(sid, {})
    if sec.get("nav_mode") not in ("horizontal_two_rows", "many_rows"):
        bad("pages", f"{sid} is not on the horizontal_two_rows nav mode (alias many_rows)")
    got = {g["id"]: g["pages"] for g in sec.get("groups", [])}
    if got != want:
        bad("pages", f"{sid} groups are {got}, wanted {want}")
    flat = [p["id"] for p in sec.get("pages", [])]
    allg = [x for g in sec.get("groups", []) for x in g["pages"]]
    if sorted(flat) != sorted(allg):
        bad("pages", f"{sid}: every page must be in exactly one group")
    for g in sec.get("groups", []):
        if not g.get("label") or f'"{g.get("icon")}"' not in icons:
            bad("pages", f"{sid} group {g.get('id')} has no label or an icon that libs:ui-icons does not hold")
    for p in sec.get("pages", []):
        if f'"{p.get("icon")}"' not in icons:
            bad("pages", f"{sid} page {p['id']} icon {p.get('icon')} is not in libs:ui-icons")

# Buro and LLC user data: every ledger block names a declared store, typed fields, and only known report ids
known_reports = {"budget_vs_actual", "accounts_vs_cashflow", "portfolio", "income"}
stores = {"buro": "buro_user", "llc": "llc_user"}
fc = json.load(open(os.path.join(libs, "../../fleet-private-data/fleet-config.json"), encoding="utf-8"))
for sid, store in stores.items():
    st = fc.get("stores", {}).get(store)
    if not st or "ac_cloud-me" not in st.get("used_by", []) or st.get("class") != "config":
        bad("pages", f"{store} is not a declared config store of ac_cloud-me in fleet-config.json (the backup would miss it)")
    for pg in secs.get(sid, {}).get("pages", []):
        f = os.path.join(app, f"data/ui/{sid}/{pg['id']}.json")
        for blk in json.load(open(f, encoding="utf-8")) if os.path.exists(f) else []:
            if blk.get("kind") != "ledger":
                continue
            if blk.get("store") != store:
                bad("pages", f"{f} ledger names store {blk.get('store')}, wanted {store}")
            for r in blk.get("reports", []):
                if r not in known_reports:
                    bad("pages", f"{f} names an unknown report {r}")
            for led in blk.get("ledgers", []):
                for fld in led.get("fields", []):
                    if fld.get("type") not in ("text", "money", "date", "choice") or (fld["type"] == "choice" and not fld.get("options")):
                        bad("pages", f"{f} field {fld.get('id')} has a bad type or no options")
                if any(k in json.dumps(led) for k in ("sample", "mock", "demo")):
                    bad("pages", f"{f} carries sample/mock/demo text: ledgers are empty until typed into")

# ---- N6 Account: renamed from Vault and launching Cloud Account
buro = secs["buro"]
acct = [p for p in buro["pages"] if p["id"] == "account"]
if not acct or acct[0].get("label") != "Account":
    bad("account", "Buro has no page `account` labelled Account")
elif acct[0].get("action") != "extapp:cloud-account":
    bad("account", "Buro > Account is not a launch tab for extapp:cloud-account")
if any(p.get("label") == "Vault" or p["id"] == "vault" for p in pages(buro["pages"])):
    bad("account", "Buro still has a Vault tab")
ea = {a["id"]: a["package"] for a in ui.get("external_apps", [])}
if ea.get("cloud-account") != "com.diegonmarcos.cloudaccount":
    bad("account", "ui.external_apps does not map cloud-account to com.diegonmarcos.cloudaccount")
fleet = os.path.join(app, "../aa_cloud-superapp/data/constellation-fleet.json")
if os.path.exists(fleet):
    pk = {a["id"]: a["package"] for a in json.load(open(fleet))["apps"]}
    if pk.get("account") != ea.get("cloud-account"):
        bad("account", "the fleet manifest's account package differs from ui.external_apps")
mf = open(os.path.join(MAIN, "AndroidManifest.xml")).read()
if "com.diegonmarcos.cloudaccount" not in mf:
    bad("account", "AndroidManifest <queries> does not list com.diegonmarcos.cloudaccount (the launch would be invisible on API 30+)")
af = os.path.join(app, "data/ui/buro/account.json")
if not os.path.exists(af) or "extapp:cloud-account" not in open(af).read():
    bad("account", "data/ui/buro/account.json has no extapp:cloud-account tile")
if "page:buro/vault" in "\n".join(strings([json.load(open(os.path.join(r, f))) for r, _, fs in os.walk(os.path.join(app, "data/ui")) for f in fs if f.endswith(".json")])):
    bad("account", "a page still targets page:buro/vault")
sec_src = code(os.path.join(MAIN, "java/com/diegonmarcos/cloudme/SectionScreen.kt"))
if "tab.action" not in sec_src:
    bad("account", "SectionScreen never dispatches a launch tab's action")

# ---- N7 Wallet link removed
if "wallet" in ui.get("bottom_nav", []) or "wallet" in secs:
    bad("no-wallet", "the Wallet bar section is still declared")
if "cloud-wallet" in ea or "com.diegonmarcos.cloudwallet" in mf:
    bad("no-wallet", "Cloud Wallet is still a declared external app / queried package")
blob = json.dumps(ui["sections"]) + "\n".join(
    open(os.path.join(r, f), encoding="utf-8").read() for r, _, fs in os.walk(os.path.join(app, "data/ui")) for f in fs if f.endswith(".json"))
if "extapp:cloud-wallet" in blob:
    bad("no-wallet", "something still launches extapp:cloud-wallet")

# ---- N8 the new section pages
want_pages = {
    "#health": ["Tracked", "Activities", "Body comp", "HR & HRV", "Sleep", "Index", "Anthropometrics", "Circadian", "Biomarkers", "Timeline", "Objectives", "Matrix", "Culinary", "Absorption clash", "Thermal", "Hemodynamics", "Supermarket", "Dossier", "Workout plan"],
    "llc": ["Employed", "Side hustles", "Assets", "Portfolio", "Calendar"],
    "buro": ["IDs", "Personal DD", "Pay Cards", "Autocomplete", "Wallet", "Account", "Logins", "TOTP", "Passkeys", "SSH", "API Tokens", "JWT", "Certificates", "Repos", "Android", "Desktop", "C3", "Import / Export", "Accounts", "Cards", "Statements", "IR", "Accounts vs Cashflow", "Budgeting", "Services", "Assets", "Liabilities & Taxes", "NaV", "Performance", "List", "Calendar"],
}
for sid, labels in want_pages.items():
    if sid == "#health":   # #1901 the whole former Health section is Pers > Health > Trackers + Plan, every page and label kept
        _hp = [p["label"] for t in _sub.get("health", {}).get("pages", [])[:2] for p in t.get("pages", [])]
        if _hp != labels:
            bad("pages", f"Pers > Health pages are not {labels}")
    elif [p["label"] for p in secs.get(sid, {}).get("pages", [])] != labels:
        bad("pages", f"{sid} pages are not {labels}")
_study = {p["id"]: p for p in secs.get("prof", {}).get("pages", [{}, {}])[1].get("pages", [])}.get("study", {})
if [p["label"] for p in _study.get("pages", [])] != ["CV", "Roadmap"]:
    bad("pages", "Prof > Profiles > Study is not CV, Roadmap")
_w = os.path.join(app, "data/ui/prof/prof-profiles/work.json")
_wb = json.load(open(_w, encoding="utf-8")) if os.path.exists(_w) else []
if not _wb or _wb[0].get("store") != "experiences_user" or [l["collection"] for l in _wb[0].get("ledgers", [])] != ["work_done", "work_todo"] \
   or not _wb[0]["ledgers"][0].get("builtin"):
    bad("pages", "Prof > Profiles > Work is not the Done/To do ledger seeded with the profile roles")
_rf = os.path.join(app, "data/ui/tasks/roadmaps.json")
if "roadmaps" not in [p["id"] for p in secs.get("tasks", {}).get("pages", [])] or \
   (json.load(open(_rf, encoding="utf-8")) if os.path.exists(_rf) else [{}])[0].get("id") != "folder_tree":
    bad("pages", "Tasks > RoadMaps does not draw the folder_tree")
if '"folder_tree" -> FolderTreeScreen(' not in code(os.path.join(MAIN, "java/com/diegonmarcos/cloudme/SectionScreen.kt")):
    bad("pages", "SectionScreen does not draw folder_tree pages")
for sid, pg in (("prof/prof-profiles/study", "cv"), ("prof/prof-profiles/study", "roadmap"), ("prof/prof-profiles", "work"), ("llc", "employed"), ("llc", "side-hustles"), ("tasks", "backlogs"), ("tasks", "roadmaps"), ("pers/achieves", "geo")):
    f = os.path.join(app, f"data/ui/{sid}/{pg}.json")
    if not os.path.exists(f) or not json.load(open(f, encoding="utf-8")):
        bad("pages", f"data/ui/{sid}/{pg}.json is missing or empty")

# ---- N9 #1367 redirects: the old RoadMap and Duties routes land on their Tasks subpages (LegacyRoutes.kt, simulated)
lr = code(os.path.join(MAIN, "java/com/diegonmarcos/cloudme/LegacyRoutes.kt"))
_mv = dict(re.findall(r'"([a-z0-9/-]+)"\s+to\s+"([a-z0-9/-]+)"', lr.split("RETIRED_SECTIONS")[0]))
_m = re.search(r"RETIRED_SECTIONS\s*=\s*setOf\(([^)]*)\)", lr)
_ret = set(re.findall(r'"([a-z0-9-]+)"', _m.group(1))) if _m else set()
_ren = dict(re.findall(r'"([a-z0-9-]+)"\s+to\s+"([a-z0-9-]+)"', lr.split("RENAMED_SECTIONS", 1)[1])) if "RENAMED_SECTIONS" in lr else {}
def resolve(s, p):
    hit = _mv.get(f"{s}/{p or ''}".rstrip("/"))
    fb = _mv.get(s) if hit is None and s in _ret else None
    if hit is None and fb is None and s in _ren: return _ren[s], p
    to = hit or fb
    if to is None: return s, p
    return to.split("/")[0], (to.split("/", 1)[1] if "/" in to else None)
for (s, p), want in {("roadmap", None): ("tasks", "roadmaps"), ("roadmap", "folders"): ("tasks", "roadmaps"), ("roadmap", "anything"): ("tasks", "roadmaps"),
                     ("duties", None): ("tasks", "backlogs"), ("duties", "tasks"): ("tasks", "backlogs"), ("duties", "anything"): ("tasks", "backlogs"),
                     ("me", "tasks"): ("tasks", "backlogs"), ("tasks", "roadmaps"): ("tasks", "roadmaps"), ("inventory", "food"): ("inventory", "food")}.items():
    if resolve(s, p) != want:
        bad("redirects", f"{s}/{p or ''} resolves to {resolve(s, p)}, wanted {want}")
for k, to in _mv.items():
    ts, _, tp = to.partition("/")
    if ts not in secs or (tp and tp not in [x["id"] for x in pages(secs[ts].get("pages"))]):
        bad("redirects", f"LegacyRoutes sends {k} to {to}, which is not a declared page")

# ---- N10 #1367 Inventory: three rows (City, Buro, Lifestyle with separators), ledger pages over inventory_user, mock seed, summary
_inv = secs.get("inventory", {})
_want_rows = {"city": ["housing", "household", "transport", "parking"],
              "buro": ["utilities", "taxes", "insurance", "contracts", "banking", "subscriptions"],
              "lifestyle": ["leisure", "food", "care", "clothing", "electronics", "education"]}
if _inv.get("nav_mode") != "horizontal_two_rows" or {g["id"]: g["pages"] for g in _inv.get("groups", [])} != _want_rows \
   or [g.get("label") for g in _inv.get("groups", [])] != ["City", "B\u00fcro", "Lifestyle"]:
    bad("inventory", "Inventory is not the three rows City, B\u00fcro, Lifestyle with their declared pages")
if [p["id"] for p in _inv.get("pages", [])] != [x for r in _want_rows.values() for x in r]:
    bad("inventory", "Inventory's flat pages are not the rows' pages in row order")
if sorted(p["id"] for p in _inv.get("pages", []) if p.get("separator_before")) != ["clothing", "education", "food"]:
    bad("inventory", "Lifestyle does not read Leisure | Food, Care | Clothing, Electronics | Education")
for g in _inv.get("groups", []):
    if f'"{g.get("icon")}"' not in icons: bad("inventory", f"group {g['id']} icon is not in libs:ui-icons")
for p in _inv.get("pages", []):
    if f'"{p.get("icon")}"' not in icons: bad("inventory", f"page {p['id']} icon is not in libs:ui-icons")
_ist = fc.get("stores", {}).get("inventory_user")
if not _ist or "ac_cloud-me" not in _ist.get("used_by", []) or _ist.get("class") != "config":
    bad("inventory", "inventory_user is not a declared config store of ac_cloud-me (the backup would miss it)")
_ITEM = ["name", "category", "brand", "purchased", "price", "monthly", "location", "status", "note"]
for p in _inv.get("pages", []):
    f = os.path.join(app, f"data/ui/inventory/{p['id']}.json")
    blks = [b for b in (json.load(open(f, encoding="utf-8")) if os.path.exists(f) else []) if b.get("kind") == "ledger"]
    if len(blks) != 1 or blks[0].get("store") != "inventory_user" or blks[0].get("summary") != {"value": "price", "monthly": "monthly"}:
        bad("inventory", f"inventory/{p['id']} is not one ledger over inventory_user with the price/monthly summary"); continue
    leds = blks[0].get("ledgers", [])
    if len(leds) != 1 or leds[0].get("collection") != "inv_" + p["id"] or [x["id"] for x in leds[0].get("fields", [])][:len(_ITEM)] != _ITEM:
        bad("inventory", f"inventory/{p['id']} is not one inv_{p['id']} collection with the item fields {_ITEM}"); continue
    fl = {x["id"]: x for x in leds[0]["fields"]}
    seed = leds[0].get("seed") or []
    if not seed: bad("inventory", f"inventory/{p['id']} has no mock seed")
    for r in seed:
        if not r.get("name") or "id" in r or "mock" in r or set(r) - set(_ITEM) - {"width_cm", "depth_cm", "height_cm", "weight_kg"}:
            bad("inventory", f"inventory/{p['id']} seed row {r.get('name')} has no name or undeclared keys (mock is stamped by LedgerSeed)")
        for k, v in r.items():
            ty = fl.get(k, {}).get("type")
            if (ty == "choice" and v not in fl[k]["options"]) or (ty == "number" and not re.fullmatch(r"\d+(\.\d+)?", v)) or (ty == "money" and not re.fullmatch(r"\d+\.\d{2}", v)) \
               or (ty == "date" and not re.fullmatch(r"20\d\d-[01]\d-[0-3]\d", v)):
                bad("inventory", f"inventory/{p['id']} seed row {r['name']}: {k}={v!r} is not a valid {ty}")
lm, ls = code(os.path.join(MAIN, "java/com/diegonmarcos/cloudme/LedgerModel.kt")), code(os.path.join(MAIN, "java/com/diegonmarcos/cloudme/LedgerScreen.kt"))
lsd = code(os.path.join(MAIN, "java/com/diegonmarcos/cloudme/LedgerSeed.kt"))
if not re.search(r"LedgerSeed\.applied\(d, defs\)\.also \{ if \(it !== d\) LedgerStore\.save\(ctx, store, it\)", ls):
    bad("inventory", "LedgerBlock does not apply and save the declared seed on load")
if 'recs(o.optJSONArray("seed"))' not in lm or "def.collection in done" not in lsd or "out.rows(def.collection).isEmpty()" not in lsd:
    bad("inventory", "LedgerSeed is not seed-once (marker) and never-overwrite (only into an empty collection)")
if "SummaryCard(LedgerReports.summary(" not in ls:
    bad("inventory", "LedgerBlock draws no summary header")
if '"inventory_user"' not in code(os.path.join(MAIN, "java/com/diegonmarcos/cloudme/LedgerStore.kt")):
    bad("inventory", "LedgerStore does not open inventory_user")

for line in out: print(line)
PY
}

expect_green() { # name
  local r; r="$(check_tree "$APP" "$APP/../ab_cloud-libs-shared/libs")"
  if [ -z "$r" ]; then ok "$1"; else bad "$1"; echo "$r" | sed 's/^/        /'; fi
}
only() { # <check id>: nothing but that id is red in the real tree
  local r; r="$(check_tree "$APP" "$APP/../ab_cloud-libs-shared/libs" | cut -f1 | sort -u | grep -x "$1" || true)"
  [ -z "$r" ]
}
for t in "nav-order|the bar reads Health, Experiences, Socials, Lnktree, Inventory, Buro, LLC" \
         "tab-style|page tabs show every name at rest and only the selected name when scrolled down (#1159)" \
         "settings|the top bar is the fleet AppTopBar: hamburger, AI, Profile, Configs, no dropdown" \
         "achieves|Experiences is Work, Study, Personal Achieves; Personal Achieves is Geo first then Endurance, Personal Growth, Community, By 30/40/50, keeping the owner's text (B1, B2, B3, region table with its link)" \
         "no-drawer|no swipe-to-open drawer handler anywhere (shell, the lib top bar, Profile page, resources)" \
         "account|Buro > Account launches Cloud Account through extapp, Vault is gone" \
         "no-wallet|the Wallet link is gone" \
         "pages|Health, Experiences (Work, Study, Achieves tabs), Socials (Professional, Personal), Tasks (RoadMaps, Backlogs, off the bar), LLC, Buro pages are declared with their data, House, Work, RoadMap and Duties are gone" \
         "redirects|#1367 roadmap, roadmap/*, duties, duties/* and me/tasks land on Tasks > RoadMaps / Backlogs, every redirect target is declared" \
         "inventory|#1367 Inventory is three rows (City, Buro, Lifestyle with | separators) of ledger pages over inventory_user, each with a valid mock seed applied once and a summary header"; do
  id="${t%%|*}"; name="${t#*|}"
  if only "$id"; then ok "$name"; else bad "$name"; check_tree "$APP" "$APP/../ab_cloud-libs-shared/libs" | grep "^$id" | sed 's/^/        /'; fi
done

echo "== mutations: each check must go red on its own break =="
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
mkdir -p "$W/base/ac_cloud-me" "$W/base/ab_cloud-libs-shared/libs/bottomnav/src"
for l in ui-nav-bars; do mkdir -p "$W/base/ab_cloud-libs-shared/libs/$l/src"; cp -r "$APP/../ab_cloud-libs-shared/libs/$l/src/main" "$W/base/ab_cloud-libs-shared/libs/$l/src/"; done
mkdir -p "$W/base/ab_cloud-libs-shared/libs/ui-icons/src" "$W/base/fleet-private-data"
cp -r "$APP/../ab_cloud-libs-shared/libs/ui-icons/src/main" "$W/base/ab_cloud-libs-shared/libs/ui-icons/src/"
cp "$APP/../fleet-private-data/fleet-config.json" "$W/base/fleet-private-data/"   # #1566 fleet-private: CI materialises it
cp -r "$APP/build.json" "$APP/app" "$APP/data" "$W/base/ac_cloud-me/" 2>/dev/null
rm -rf "$W/base/ac_cloud-me/app/build"
cp -r "$APP/../ab_cloud-libs-shared/libs/bottomnav/src/commonMain" "$APP/../ab_cloud-libs-shared/libs/bottomnav/src/main" "$W/base/ab_cloud-libs-shared/libs/bottomnav/src/"
mutant() { # id description sh-command-run-in-copy
  local id="$1" desc="$2" cmd="$3" d="$W/m"
  rm -rf "$d"; cp -r "$W/base" "$d"
  ( cd "$d/ac_cloud-me" && eval "$cmd" ) >/dev/null 2>&1
  if check_tree "$d/ac_cloud-me" "$d/ab_cloud-libs-shared/libs" | cut -f1 | grep -qx "$id"; then ok "red: $desc"; else bad "NOT red: $desc"; fi
}
BJ='python3 - "$@"'
mutant nav-order "bar order swapped" "python3 -c \"import json;d=json.load(open('build.json'));b=d['ui']['bottom_nav'];b[0],b[1]=b[1],b[0];json.dump(d,open('build.json','w'))\""
mutant tab-style "name drawn on every tab even when compact" "sed -i 's/named = !compact || page.id == selectedId/named = true/' ../ab_cloud-libs-shared/libs/ui-nav-bars/src/main/kotlin/com/diegonmarcos/superapp/navbartop2/NavBarTopSecondRow.kt"
mutant tab-style "SectionScreen back to text pills" "sed -i 's/iconFor = /xxx = /' app/src/main/java/com/diegonmarcos/cloudme/SectionScreen.kt"
mutant settings "Settings entries reduced to one" "python3 -c \"import json;d=json.load(open('build.json'));d['ui']['settings']=['configs'];json.dump(d,open('build.json','w'))\""
mutant settings "a dropdown comes back" "echo 'val x = DropdownMenu' >> app/src/main/java/com/diegonmarcos/cloudme/MeTopBar.kt"
mutant no-drawer "a drag handler is back in the lib top bar" "echo 'val d = detectHorizontalDragGestures' >> ../ab_cloud-libs-shared/libs/bottomnav/src/main/kotlin/com/diegonmarcos/superapp/bottomnav/AppTopBar.kt"
mutant settings "the top bar loses its inline actions" "sed -i 's/inlineActions = true/inlineActions = false/' app/src/main/java/com/diegonmarcos/cloudme/MeTopBar.kt"
mutant pages "the Home section is back" "python3 -c \"import json;d=json.load(open('build.json'));d['ui']['sections'].append({'id':'home','label':'Home','pages':[]});json.dump(d,open('build.json','w'))\""
mutant pages "Backlogs dropped from Tasks" "python3 -c \"import json;d=json.load(open('build.json'));[s['pages'].pop() for s in d['ui']['sections'] if s['id']=='tasks'];json.dump(d,open('build.json','w'))\""
mutant pages "Instagram dropped from Pers Profiles" "python3 -c \"import json;d=json.load(open('build.json'));[s['pages'][0]['pages'].pop(1) for s in d['ui']['sections'] if s['id']=='pers'];json.dump(d,open('build.json','w'))\""
mutant pages "Prof Professional draws another page" "sed -i 's#https://linkedin.com/in/diegonmarcos\"#mysocials/social.html\"#' data/ui/prof/prof-profiles/professional.json"
mutant pages "Tasks section dropped with the bar slot" "python3 -c \"import json;d=json.load(open('build.json'));d['ui']['sections']=[s for s in d['ui']['sections'] if s['id']!='tasks'];json.dump(d,open('build.json','w'))\""
mutant pages "the Duties section comes back" "python3 -c \"import json;d=json.load(open('build.json'));d['ui']['sections'].append({'id':'duties','label':'Duties','pages':[]});json.dump(d,open('build.json','w'))\""
mutant nav-order "Inventory and Tasks swapped" "python3 -c \"import json;d=json.load(open('build.json'));b=d['ui']['bottom_nav'];b[2],b[4]=b[4],b[2];json.dump(d,open('build.json','w'))\""
mutant nav-order "Proj relabelled RoadMap" "python3 -c \"import json;d=json.load(open('build.json'));[s.update(label='RoadMap') for s in d['ui']['sections'] if s['id']=='proj'];json.dump(d,open('build.json','w'))\""
mutant redirects "duties no longer redirected" "sed -i '/\"duties\" to \"tasks\/backlogs\"/d' app/src/main/java/com/diegonmarcos/cloudme/LegacyRoutes.kt"
mutant redirects "roadmap/* pages fall through (not retired)" "sed -i 's/\"house\", \"roadmap\", \"duties\"/\"house\", \"duties\"/' app/src/main/java/com/diegonmarcos/cloudme/LegacyRoutes.kt"
mutant redirects "roadmap sent to Backlogs" "sed -i 's#\"roadmap\" to \"tasks/roadmaps\"#\"roadmap\" to \"tasks/backlogs\"#' app/src/main/java/com/diegonmarcos/cloudme/LegacyRoutes.kt"
mutant redirects "me/tasks still points at the retired duties" "sed -i 's#\"me/tasks\" to \"tasks/backlogs\"#\"me/tasks\" to \"duties/tasks\"#' app/src/main/java/com/diegonmarcos/cloudme/LegacyRoutes.kt"
mutant redirects "a redirect target page is renamed away" "sed -i 's/\"id\": \"backlogs\"/\"id\": \"backlog\"/' build.json"
mutant inventory "Lifestyle loses its Food separator" "python3 -c \"import json;d=json.load(open('build.json'));[p.pop('separator_before',None) for s in d['ui']['sections'] if s['id']=='inventory' for p in s['pages'] if p['id']=='food'];json.dump(d,open('build.json','w'))\""
mutant inventory "Parking moves to the Buro row" "python3 -c \"import json;d=json.load(open('build.json'));[(s['groups'][0]['pages'].remove('parking'),s['groups'][1]['pages'].append('parking')) for s in d['ui']['sections'] if s['id']=='inventory'];json.dump(d,open('build.json','w'))\""
mutant inventory "an inventory page loses its seed" "python3 -c \"import json;p='data/ui/inventory/taxes.json';d=json.load(open(p));d[1]['ledgers'][0].pop('seed');json.dump(d,open(p,'w'))\""
mutant inventory "a seed price is not money" "sed -i '0,/\"price\": \"[0-9.]*\"/s//\"price\": \"12,50 EUR\"/' data/ui/inventory/electronics.json"
mutant inventory "a seed status is not an option" "sed -i '0,/\"status\": \"Good\"/s//\"status\": \"Shiny\"/' data/ui/inventory/household.json"
mutant inventory "a seed row pre-stamps mock" "python3 -c \"import json;p='data/ui/inventory/food.json';d=json.load(open(p));d[1]['ledgers'][0]['seed'][0]['mock']='1';json.dump(d,open(p,'w'))\""
mutant inventory "an inventory page has no summary" "python3 -c \"import json;p='data/ui/inventory/care.json';d=json.load(open(p));d[1].pop('summary');json.dump(d,open(p,'w'))\""
mutant inventory "an inventory page writes to the buro store" "sed -i 's/\"store\": \"inventory_user\"/\"store\": \"buro_user\"/' data/ui/inventory/banking.json"
mutant inventory "inventory_user undeclared in fleet-config" "sed -i 's/\"inventory_user\"/\"inventory_gone\"/' ../fleet-private-data/fleet-config.json"
mutant inventory "the seed is re-applied every load (no marker)" "sed -i 's/def.collection in done/false/' app/src/main/java/com/diegonmarcos/cloudme/LedgerSeed.kt"
mutant inventory "the seed tops up a collection the owner already filled" "sed -i 's/if (out.rows(def.collection).isEmpty()) for/for/' app/src/main/java/com/diegonmarcos/cloudme/LedgerSeed.kt"
mutant inventory "the seed is applied but not saved" "sed -i 's/if (it !== d) LedgerStore.save(ctx, store, it)/Unit/' app/src/main/java/com/diegonmarcos/cloudme/LedgerScreen.kt"
mutant inventory "the summary header is not drawn" "sed -i 's/SummaryCard(LedgerReports.summary(/Unit(LedgerReports.summary(/' app/src/main/java/com/diegonmarcos/cloudme/LedgerScreen.kt"
mutant pages "House comes back" "python3 -c \"import json;d=json.load(open('build.json'));d['ui']['sections'].append({'id':'house','label':'House','pages':[]});json.dump(d,open('build.json','w'))\""
mutant pages "a Buro page leaves its group" "python3 -c \"import json;d=json.load(open('build.json'));[s['groups'][1]['pages'].pop() for s in d['ui']['sections'] if s['id']=='buro'];json.dump(d,open('build.json','w'))\""
mutant pages "an LLC group icon is not a ui-icons name" "sed -i 's/\"icon\": \"payments\"/\"icon\": \"no_such_icon\"/' build.json"
mutant pages "a Buro ledger names the wrong store" "sed -i 's/\"store\": \"buro_user\"/\"store\": \"llc_user\"/' data/ui/buro/assets.json"
mutant pages "a ledger names an unknown report" "sed -i 's/budget_vs_actual/made_up_report/' data/ui/buro/budgeting.json"
mutant pages "the llc store is undeclared in fleet-config" "sed -i 's/\"llc_user\"/\"llc_gone\"/' ../fleet-private-data/fleet-config.json"
mutant achieves "one owner sentence altered" "sed -i 's/Aikido, Yoga and Meditation\\./Aikido and Yoga./' data/ui/pers/achieves/growth.json"
mutant achieves "NomadMania link dropped" "sed -i 's#https://nomadmania.com/profile/73889#https://example.com#' data/ui/pers/achieves/geo.json"
mutant achieves "Trips tab comes back" "python3 -c \"import json;d=json.load(open('build.json'));[p['pages'].append({'id':'trips','label':'Trips'}) for s in d['ui']['sections'] if s['id']=='pers' for p in s['pages'] if p['id']=='achieves'];json.dump(d,open('build.json','w'))\""
mutant achieves "Geo is not the first tab" "python3 -c \"import json;d=json.load(open('build.json'));[p['pages'].insert(1,p['pages'].pop(0)) for s in d['ui']['sections'] if s['id']=='pers' for p in s['pages'] if p['id']=='achieves'];json.dump(d,open('build.json','w'))\""
mutant pages "a tab loses Mark undone" "sed -i 's/\"move_label\": \"Mark undone\"/\"x\": 1/;0,/\"move_to\": \"by40_todo\"/s//\"move_to\": \"\"/' data/ui/pers/achieves/by40.json"
mutant pages "the done date is no longer stamped" "sed -i 's/\"move_stamp\": \"date\"/\"move_stamp\": \"\"/' data/ui/pers/achieves/community.json"
mutant achieves "Study is a section again" "python3 -c \"import json;d=json.load(open('build.json'));d['ui']['sections'].append({'id':'study','label':'Study','pages':[]});json.dump(d,open('build.json','w'))\""
mutant pages "Work dropped from Prof" "python3 -c \"import json;d=json.load(open('build.json'));[s['pages'][0]['pages'].pop(1) for s in d['ui']['sections'] if s['id']=='prof'];json.dump(d,open('build.json','w'))\""
mutant nav-order "Tasks after Lnktree" "python3 -c \"import json;d=json.load(open('build.json'));b=d['ui']['bottom_nav'];b[2],b[3]=b[3],b[2];json.dump(d,open('build.json','w'))\""
mutant pages "Work loses its seeded roles" "python3 -c \"import json;p='data/ui/prof/prof-profiles/work.json';d=json.load(open(p));d[0]['ledgers'][0].pop('builtin');json.dump(d,open(p,'w'))\""
mutant pages "RoadMaps page draws no tree" "sed -i 's/folder_tree/stack/' data/ui/tasks/roadmaps.json"
mutant pages "Geo loses its stats" "sed -i 's/\"visited\": \"geo_done\"/\"visited\": \"nope\"/' data/ui/pers/achieves/geo.json"
mutant pages "migration sends records to an undeclared tab" "sed -i 's/\"default\": \"endurance\"/\"default\": \"trips\"/' build.json"
mutant no-drawer "the drawer is back in the shell" "echo 'val d = ModalNavigationDrawer' >> app/src/main/java/com/diegonmarcos/cloudme/MainActivity.kt"
mutant no-drawer "a drag handler is back in the shell" "echo 'val d = detectHorizontalDragGestures' >> app/src/main/java/com/diegonmarcos/cloudme/StackScreen.kt"
mutant no-drawer "Profile page gains a swipe handler" "echo '<script>document.addEventListener(\"touchstart\",function(){})</script>' >> data/web/mysocials/linkedin.html"
mutant account "Account tab loses its launch action" "python3 -c \"import json;d=json.load(open('build.json'));[p.pop('action',None) for s in d['ui']['sections'] if s['id']=='buro' for p in s['pages']];json.dump(d,open('build.json','w'))\""
mutant account "Account renamed back to Vault" "sed -i 's/\"label\": \"Account\"/\"label\": \"Vault\"/' build.json"
mutant no-wallet "Wallet link comes back" "sed -i 's#extapp:cloud-account#extapp:cloud-wallet#' data/ui/configs/apps.json"
mutant no-wallet "Wallet section comes back" "python3 -c \"import json;d=json.load(open('build.json'));d['ui']['sections'].append({'id':'wallet','label':'Wallet','pages':[]});json.dump(d,open('build.json','w'))\""
mutant pages "a Health page is dropped" "python3 -c \"import json;d=json.load(open('build.json'));[s['pages'][1]['pages'][0]['pages'].pop(3) for s in d['ui']['sections'] if s['id']=='pers'];json.dump(d,open('build.json','w'))\""

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
