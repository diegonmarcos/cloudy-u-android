#!/usr/bin/env bash
# Tester (#1901): Health became Pers, Experiences became Prof, Socials became Proj, and the Lnktree section was spread over them. A MOVE, not a copy.
#   N1 tree     the bar, the subpages, their order, the groups and the defaults are exactly the declared tree
#   N2 parity   field parity against test/fixtures/nav-baseline-1901.json (the tree dumped BEFORE the move by test/nav-tree-dump.py): every page of the
#               old tree resolves, through ui.renamed, to a page that still holds every field, url and tile target it held; every section's own data
#               (the Health metric taxonomy, the Achieves migration) is still declared; labels only changed where the owner renamed them
#   N3 renamed  ui.renamed names only pages and sections of the live tree, lists EVERY page of the old tree, and LegacyRoutes reads it from its asset
#   N4 urls     each tile opens its own site: the URL of every embedded page is declared data (data/ui/<section>/..), none is a literal in Kotlin
#   N6 profile  page:profile/profile (the Profile tile of the SuperApp Me row) stays reachable: settings section + page file, not renamed away
#   N5 walk     the emulator walk visits Pers, Prof and Proj and screenshots each default page
# Every check is proven red by its own mutation of a copy of the tree (the tail of this file).
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

check_tree() { # <app dir>: one "ID<TAB>problem" line per failure, nothing when green
python3 - "$1" "$APP/test/nav-tree-dump.py" "$APP/test/fixtures/nav-baseline-1901.json" <<'PY'
import json, os, re, subprocess, sys

app, dump, basef = sys.argv[1:4]
out = []
def bad(i, m): out.append(f"{i}\t{m}")

ui = json.load(open(os.path.join(app, "build.json"), encoding="utf-8"))["ui"]
secs = {s["id"]: s for s in ui["sections"]}
now = json.loads(subprocess.run([sys.executable, dump, app], capture_output=True, text=True, check=True).stdout)
base = json.load(open(basef, encoding="utf-8"))
ren = ui.get("renamed", {})

def ids(pages):
    for p in pages or []:
        yield p["id"]
        yield from ids(p.get("pages"))

def sub(section, *chain):
    """The page at chain below a section (ids), or {}."""
    ps = secs.get(section, {}).get("pages", [])
    cur = {}
    for c in chain:
        cur = next((p for p in ps if p["id"] == c), {})
        ps = cur.get("pages", [])
    return cur

# ---- N1 the declared tree
if ui.get("bottom_nav") != ["pers", "prof", "proj", "lnktree", "inventory", "buro", "llc"] or \
   [secs[i]["label"] for i in ui.get("bottom_nav", []) if i in secs] != ["Pers", "Prof", "Proj", "Lnktree", "Inventory", "Buro", "LLC"]:
    bad("tree", f"the bar is {ui.get('bottom_nav')}, wanted Pers | Prof | Proj | Lnktree | Inventory | Buro | LLC (the Lnktree entry is NOT removed)")
LT = ["lnktree-site", "professional", "leafy", "nexus", "stark", "personal", "social", "health", "dd", "astro", "area-unix", "area-cg", "area-capital", "area-hardtech", "area-lecole42", "area-front"]
if [p["id"] for p in secs.get("lnktree", {}).get("pages", [])] != ["lt-" + x for x in LT] or secs.get("lnktree", {}).get("nav_mode") != "horizontal_two_rows" or \
   [g["id"] for g in secs.get("lnktree", {}).get("groups", [])] != ["lnktree", "prof", "pers", "proj"] or (secs.get("lnktree", {}).get("pages") or [{}])[0].get("id") != "lt-lnktree-site":
    bad("tree", "the Lnktree bottom-nav section does not hold its sixteen pages in the rows Lnktree / Prof / Pers / Proj, opening on lt-lnktree-site (#1914)")
if ui.get("default_section") != "lnktree": bad("tree", "the app does not open on the Lnktree entry")
for gone in ("health", "experiences", "socials"):
    if gone in secs: bad("tree", f"{gone} is a section again: it moved (ui.renamed keeps its ids working)")
def labels(ps): return [(p["id"], p["label"]) for p in ps]
if labels(secs.get("pers", {}).get("pages", [])) != [("pers-profiles", "Profiles"), ("health", "Health"), ("astro", "Astro"), ("dd", "DD"), ("achieves", "Personal Archives")]:
    bad("tree", "Pers is not Profiles, Health, Astro, DD, Personal Archives")
if labels(sub("pers", "pers-profiles").get("pages", [])) != [("personal", "Personal"), ("ig0", "ig0"), ("ig1", "ig1"), ("ig2", "ig2"), ("face0", "face0"), ("pinterest", "Pinterest"), ("youtube", "YouTube"), ("tidal", "Tidal"), ("linkedin", "LinkedIn"), ("orkut", "Orkut")]:
    bad("tree", "Pers > Profiles is not Personal | ig0, ig1, ig2, face0, Pinterest, YouTube, Tidal, LinkedIn, Orkut (#1913)")
if labels(sub("pers", "health").get("pages", [])) != [("trackers", "Trackers"), ("plan", "Plan"), ("health-profile", "Record")]:
    bad("tree", "Pers > Health is not Trackers, Plan, Record")
if [p["id"] for p in sub("pers", "health", "trackers").get("pages", [])] != ["tracked", "activities", "bodycomp", "hr", "sleep"] or \
   [p["id"] for p in sub("pers", "health", "plan").get("pages", [])] != ["home", "bio", "circadian", "biomarkers", "timeline", "objectives", "matrix", "culinary", "clash", "thermal", "hemo", "shop", "dossier", "workout"]:
    bad("tree", "Pers > Health does not hold the 5 Trackers and 14 Plan pages in their old order")
if [p["id"] for p in sub("pers", "achieves").get("pages", [])] != ["geo", "endurance", "growth", "community", "by30", "by40", "by50"]:
    bad("tree", "Pers > Personal Archives is not Geo, Endurance, Personal Growth, Community, By 30, By 40, By 50")
if labels(secs.get("prof", {}).get("pages", [])) != [("prof-web", "Profiles"), ("leafy", "Leaf.y"), ("nexus", "Nexus.z"), ("stark", "Stark.x")] or \
   {g["id"]: g["pages"] for g in secs.get("prof", {}).get("groups", [])} != {"profiles": ["prof-web"], "companies": ["leafy", "nexus", "stark"]}:
    bad("tree", "Prof is not Profiles (Professional, LinkedIn, Portfolio, Developer, CV) | Leaf.y, Nexus.z, Stark.x (#1937: More is removed by owner order)")
if [p["id"] for p in sub("prof", "prof-web").get("pages", [])] != ["prof-professional", "prof-linkedin", "prof-portfolio", "prof-developer", "prof-cv"]:
    bad("tree", "Prof > Profiles is not Professional | LinkedIn | Portfolio | Developer | CV")
for _gone in ("prof-profiles", "professional", "work", "study", "cv", "roadmap"):
    if _gone in set(ids(secs.get("prof", {}).get("pages"))): bad("tree", f"#1937 the removed Prof page {_gone} is declared again")
PROJ = {"unix": ["superapp", "cloudme", "cloudy"], "cg": ["omniverse", "zirkusverse"], "capital": ["cb", "pm", "finterm"], "hardtech": ["biomed", "cad", "deepxtech"],
        "wiki": ["modernsc", "arcanum", "projects"], "projects": ["endeavours", "duckpeng"], "lecole42": ["algo", "graphic", "system", "mylibs", "curriculum", "agentic", "datascience", "machinelearning", "cyberwarfare", "dev"]}
if [p["id"] for p in secs.get("proj", {}).get("pages", [])] != ["pj-home"] + ["pj-" + g for g in PROJ] + ["pj-repos"] or \
   [re.sub(r"^[^\w]+ ", "", p["label"]) for p in secs.get("proj", {}).get("pages", [])] != ["Home", "Unix", "CG", "Capital", "Hardtech", "Wiki", "Projects", "42", "Repos"] or \
   any([c["id"] for c in next(p for p in secs["proj"]["pages"] if p["id"] == "pj-" + g).get("pages", [])] != [f"pj-{g}-{l}" for l in ls] for g, ls in PROJ.items()):
    bad("tree", "Proj is not Home | Unix, CG, Capital, Hardtech, Wiki, Projects, 42 with every project leaf (#1914)")
for _o, _n in {"area-unix": "pj-unix", "area-cg": "pj-cg", "area-capital": "pj-capital", "area-hardtech": "pj-hardtech", "area-lecole42": "pj-lecole42", "area-front": "pj-projects"}.items():
    if ren.get("pages", {}).get("proj/" + _o) != "proj/" + _n: bad("renamed", f"ui.renamed.pages has no alias proj/{_o} -> proj/{_n} (#1914)")
if ren.get("pages", {}).get("proj/lnktree-site") != "lnktree/lt-lnktree-site": bad("renamed", "proj/lnktree-site does not alias lnktree/lt-lnktree-site (#1914)")
if "lnktree-site" in [p["id"] for p in secs.get("pers", {}).get("pages", [])] or "lnktree-site" in ids(secs.get("pers", {}).get("pages")):
    bad("tree", "the Lnktree tile is still under Pers (it moved to Proj)")
if ui.get("repo_tree", {}).get("group") not in {g["id"] for g in secs.get("lnktree", {}).get("groups", [])}:
    bad("tree", "ui.repo_tree.group is not a group of the Lnktree bottom-nav section (its Proj row)")
if ui.get("vault_rows", {}).get("projects", {}).get("section") != "lnktree":
    bad("tree", "Tasks > Projects does not take the Lnktree bottom-nav section's Proj group")
lc = ui.get("lnktree_cache", {})
if not {"pers", "prof", "proj"} <= set(lc.get("sections", [])):
    bad("tree", "the page cache does not cover Pers, Prof and Proj")

# ---- N2 parity against the baseline
def resolve(section, page):
    key = f"{section}/{page}" if page else section
    to = ren.get("pages", {}).get(key)
    if to: return to.split("/", 1)[0], to.split("/", 1)[1] if "/" in to else None
    return ren.get("sections", {}).get(section, section), page

def norm(leaf):
    """A tile target inside a page ("page:<old section>/<old page>") is compared through the rename map."""
    m = re.match(r'^(.*)="(page|section):([^"/]*)(?:/([^"]*))?"$', leaf)
    if not m: return leaf
    s, p = resolve(m.group(3), m.group(4))
    return f'{m.group(1)}="{m.group(2)}:{s}' + (f"/{p}" if p else "") + '"'

RELABELED = {  # the owner's renames of #1901, and the two pages whose name the move gave to another page
    "experiences/achieves": "Personal Archives", "socials/socials-personal": "ig0",
}
# #1911 owner order: these three tabs embed their real site (the declared me-links.json URL) instead of the bundled mysocials mirror; the
# baseline value is compared through this table, ONLY on these pages (the Lnktree bottom-nav copies keep the mirrors)
EMBED1911 = {"pers/ig0": {"mysocials/instagram-diegocnmarcos_.html": "https://diegonmarcos.github.io/mySocials/instagram-diegocnmarcos_.html"}}
def real(x, to):
    for old, new in EMBED1911.get(to, {}).items(): x = x.replace(old, new)
    return x
# #1937 OWNER-ORDERED REMOVAL: Prof > More (prof-profiles) and its subpages Professional, Work, Study (CV, Roadmap) were removed. These baseline pages are the only
# ones allowed to lose their content; each must still resolve (deep links, saved state) to Prof > Profiles (prof/prof-web). Nothing else may be lost.
OWNER_REMOVED_1937 = {"experiences/work", "experiences/study", "experiences/cv", "experiences/roadmap", "socials/socials-professional"}
for key, b in sorted(base["pages"].items()):
    sec, pid = key.split("/", 1)
    ns, np = resolve(sec, pid)
    if key in OWNER_REMOVED_1937:
        if (ns, np) != ("prof", "prof-web"): bad("parity", f"{key} (owner-removed, #1937) resolves to {ns}/{np}, wanted prof/prof-web")
        continue
    if ns not in secs:
        bad("parity", f"{key}: its section {ns} is not declared"); continue
    t = now["pages"].get(f"{ns}/{np}")
    if t is None:
        bad("parity", f"{key} -> {ns}/{np}: the page is gone"); continue
    if b["container"] != t["container"]:
        bad("parity", f"{key} -> {ns}/{np}: a container became a leaf or the other way round")
    have = {norm(x) for x in t["leaves"]}
    for leaf in b["leaves"]:
        if norm(real(leaf, f"{ns}/{np}")) not in have:
            bad("parity", f"{key} -> {ns}/{np}: the field {leaf[:110]} is gone")
    for u in b["urls"]:
        if real(u, f"{ns}/{np}") not in t["urls"]: bad("parity", f"{key} -> {ns}/{np}: the url {u} is gone")
    for tg in b["targets"]:
        n = norm(f'x="{tg}"')[3:-1]
        if n not in t["targets"]: bad("parity", f"{key} -> {ns}/{np}: the tile target {tg} is gone")
    if b["label"] and t["label"] != RELABELED.get(key, b["label"]):
        bad("parity", f"{key} -> {ns}/{np}: the label {b['label']!r} became {t['label']!r}")
# the Lnktree bottom-nav pages (restored, lt- ids) also live on as the reorganised pages of Pers, Prof and Proj: those hold every baseline field too
ALSO = {"leafy": "prof/leafy", "nexus": "prof/nexus", "stark": "prof/stark", "personal": "pers/ig0",
        "health": "pers/health-profile", "dd": "pers/dd", "astro": "pers/astro", }
for pid, to in ALSO.items():
    b = base["pages"].get(f"lnktree/{pid}")
    t = now["pages"].get(to)
    if b is None or t is None:
        bad("parity", f"lnktree/{pid} is also expected at {to}: one of them is gone"); continue
    have = {norm(x) for x in t["leaves"]}
    for leaf in b["leaves"]:
        if norm(real(leaf, to)) not in have: bad("parity", f"lnktree/{pid} -> {to}: the field {leaf[:110]} is gone")
# every section's own data (Health's metric taxonomy, the Achieves migration ...) is still declared, on the section that took it over
allextras = {x for s in now["sections"] for x in s["extras"]}
for s in base["sections"]:
    for x in s["extras"]:
        if x not in allextras: bad("parity", f"section {s['id']}: the declaration {x[:110]} is gone")
# #1937 every id of the removed pages (the 1901 layout ones included) resolves to Prof > Profiles
for _old in ("prof/prof-profiles", "prof/professional", "prof/work", "prof/study", "prof/cv", "prof/roadmap"):
    if ren.get("pages", {}).get(_old) != "prof/prof-web": bad("renamed", f"ui.renamed.pages has no alias {_old} -> prof/prof-web (#1937)")
if os.path.isdir(os.path.join(app, "data/ui/prof/prof-profiles")): bad("parity", "data/ui/prof/prof-profiles is back (#1937 removed it)")
# the bar: every old bar section is a bar section of the new tree
for i in base["bottom_nav"]:
    if resolve(i, None)[0] not in ui.get("bottom_nav", []): bad("parity", f"the old bar section {i} resolves to {resolve(i, None)[0]}, which is not on the bar")
# a moved page carries its content once: every old page file exists once more (no file is left behind in a retired folder)
for gone in ("health", "experiences", "socials"):
    if os.path.isdir(os.path.join(app, "data/ui", gone)): bad("parity", f"data/ui/{gone} is back (a copy, not a move)")

# ---- N3 the rename map
for old, to in ren.get("pages", {}).items():
    s, _, p = to.partition("/")
    if s not in secs or (p and p not in ids(secs[s].get("pages"))): bad("renamed", f"ui.renamed.pages[{old}] names {to}, which is not a page of the tree")
for old, to in ren.get("sections", {}).items():
    if to not in secs: bad("renamed", f"ui.renamed.sections[{old}] names {to}, which is not a section")
    if old in secs: bad("renamed", f"ui.renamed.sections[{old}] is a live section")
if "lnktree" in ren.get("sections", {}): bad("renamed", "ui.renamed.sections still sends lnktree away (it is a bar section again)")
for key in base["pages"]:
    if key.split("/", 1)[0] not in secs and key not in ren.get("pages", {}):   # a page of a section that is gone needs its entry; the live sections' ids are unchanged
        bad("renamed", f"ui.renamed.pages has no entry for the old page {key}")
# #1913 the Instagram and Social tabs were replaced by the mySocials page tabs: their ids still resolve, to a declared tab
for old in ("pers/instagram", "pers/social"):
    if ren.get("pages", {}).get(old) != "pers/ig0": bad("renamed", f"ui.renamed.pages has no alias {old} -> pers/ig0 (the replaced Pers > Profiles tab)")
for s in base["sections"]:
    if s["id"] not in ren.get("sections", {}) and s["id"] not in secs:
        bad("renamed", f"ui.renamed.sections has no entry for the old section {s['id']}")
src = lambda f: open(os.path.join(app, f), encoding="utf-8").read() if os.path.exists(os.path.join(app, f)) else ""
lr = src("app/src/main/java/com/diegonmarcos/cloudme/LegacyRoutes.kt")
if "fun renamed(" not in lr or "table.pages[key]" not in lr or "table.sections[section]" not in lr or "fun load(" not in lr or "assets.open(ASSET)" not in lr:
    bad("renamed", "LegacyRoutes does not apply ui.renamed (pages first, then the section alias)")
if "'renamed.json'" not in src("app/build.gradle") or "ui.renamed.pages[" not in src("app/build.gradle") or "UI_RENAMED" in src("app/build.gradle"):
    bad("renamed", "app/build.gradle does not write ui.renamed to the renamed.json asset (not a BuildConfig field) and validate it")
mo = src("app/src/main/java/com/diegonmarcos/cloudme/MainActivity.kt")
if "LegacyRoutes.resolve(sectionId, pageId)" not in mo or "LegacyRoutes.load(this)" not in mo:
    bad("renamed", "MainActivity.open does not resolve the id through LegacyRoutes (deep links and the SuperApp Me row would miss)")

# ---- N4 every tile opens its own site; the URL is declared data
SITES = {  # tile -> (page file, the url it embeds)
    "Prof > Leaf.y": ("data/ui/prof/leafy.json", "https://diegonmarcos.github.io/leafy/"),
    "Prof > Nexus.z": ("data/ui/prof/nexus.json", "https://diegonmarcos.github.io/nexus/"),
    "Prof > Stark.x": ("data/ui/prof/stark.json", "https://diegonmarcos.github.io/stark/"),
    "Pers > Profiles > Personal": ("data/ui/pers/pers-profiles/personal.json", "https://diegonmarcos.github.io/mySocials/"),
    "Prof > Profiles > Professional": ("data/ui/prof/prof-web/prof-professional.json", "https://diegonmarcos.com/"),
    "Prof > Profiles > Portfolio": ("data/ui/prof/prof-web/prof-portfolio.json", "https://diegonmarcos.github.io/cv_web/"),
    "Prof > Profiles > Developer": ("data/ui/prof/prof-web/prof-developer.json", "https://github.com/diegonmarcos"),
    "Prof > Profiles > CV": ("data/ui/prof/prof-web/prof-cv.json", "https://diegonmarcos.github.io/cv_pdf/"),
    "Pers > Profiles > ig0": ("data/ui/pers/pers-profiles/ig0.json", "https://diegonmarcos.github.io/mySocials/instagram-diegocnmarcos_.html"),
    "Pers > Profiles > ig1": ("data/ui/pers/pers-profiles/ig1.json", "https://diegonmarcos.github.io/mySocials/instagram-diegocmarcos_.html"),
    "Pers > Profiles > ig2": ("data/ui/pers/pers-profiles/ig2.json", "https://diegonmarcos.github.io/mySocials/instagram-diegonmarcos.html"),
    "Pers > Profiles > face0": ("data/ui/pers/pers-profiles/face0.json", "https://diegonmarcos.github.io/mySocials/facebook-diegonmarcos.html"),
    "Pers > Profiles > Pinterest": ("data/ui/pers/pers-profiles/pinterest.json", "https://diegonmarcos.github.io/mySocials/pinterest.html"),
    "Pers > Profiles > YouTube": ("data/ui/pers/pers-profiles/youtube.json", "https://diegonmarcos.github.io/mySocials/youtube.html"),
    "Pers > Profiles > Tidal": ("data/ui/pers/pers-profiles/tidal.json", "https://diegonmarcos.github.io/mySocials/tidal.html"),
    "Pers > Profiles > LinkedIn": ("data/ui/pers/pers-profiles/linkedin.json", "https://diegonmarcos.github.io/mySocials/linkedin.html"),
    "Pers > Profiles > Orkut": ("data/ui/pers/pers-profiles/orkut.json", "https://diegonmarcos.github.io/mySocials/orkut.html"),
    "Pers > Astro": ("data/ui/pers/astro.json", "https://diegonmarcos.github.io/myAstro/"),
        "Lnktree (bottom nav) > Lnktree": ("data/ui/lnktree/lt-lnktree-site.json", "https://linktree.diegonmarcos.com/"),
    "Lnktree (bottom nav) > Leaf.y": ("data/ui/lnktree/lt-leafy.json", "https://diegonmarcos.github.io/leafy/"),
}
for tile, (f, url) in SITES.items():
    try: blocks = json.load(open(os.path.join(app, f), encoding="utf-8"))
    except (OSError, ValueError): bad("urls", f"{tile}: {f} is missing"); continue
    if len(blocks) != 1 or blocks[0].get("kind") != "fragment" or blocks[0].get("id") != "web" or blocks[0].get("url") != url:
        bad("urls", f"{tile} does not open {url} embedded")
    if not url.startswith("https://") and not os.path.exists(os.path.join(app, "data/web", url)):
        bad("urls", f"{tile}: the bundled page {url} does not exist")
# #1914 every Proj leaf embeds the URL its me-links.json entry declares (derived from the SoT), no two the same
_seen = {}
for e in json.load(open(os.path.join(app, "data/web/me-links.json")))["entries"]:
    if e["section"] != "proj": continue
    f = os.path.join(app, "data/ui/proj", *e["path"]) + ".json"
    try: b = json.load(open(f, encoding="utf-8"))[0]
    except (OSError, ValueError): bad("urls", f"Proj leaf {e['id']}: {f} is missing"); continue
    if b.get("url") != e["url"] or b.get("kind") != "fragment" or b.get("id") != "web": bad("urls", f"Proj leaf {e['id']} does not embed {e['url']}")
    if e["url"] in _seen: bad("urls", f"Proj leaves {_seen[e['url']]} and {e['id']} embed the same page")
    _seen[e["url"]] = e["id"]
if len(_seen) != 26: bad("urls", f"Proj holds {len(_seen)} leaves, wanted the 26 of Unix, CG, Capital, Hardtech, Wiki, Projects, 42")
main = os.path.join(app, "app/src/main/java")
for r, _, fs in os.walk(main):
    for f in fs:
        if not f.endswith(".kt"): continue
        t = open(os.path.join(r, f), encoding="utf-8").read()
        t = re.sub(r"/\*.*?\*/", "", t, flags=re.S); t = re.sub(r"(?m)^\s*//.*$", "", t)
        for host in ("diegonmarcos.com", "diegonmarcos.github.io", "linktree.", "linkedin.com"):
            for m in re.finditer(r'"[^"\n]*' + re.escape(host) + r'[^"\n]*"', t):
                if "BuildConfig" in m.group(0): continue
                bad("urls", f"{os.path.relpath(os.path.join(r, f), app)} carries the literal {m.group(0)[:80]}: tile URLs are declared data")

# ---- N6 the Cloud Me profile page (page:profile/profile, the SuperApp Me row's former Profile tile, #1906) stays reachable: it is its own settings section
# (the avatar at the top right), the very page that carries libs:account-ui's profile, so no copy under Pers is added (a copy would be a second page for one content).
_ps = secs.get("profile", {})
if "profile" not in ui.get("settings", []): bad("profile", "profile is not a settings section (the avatar no longer opens it)")
if [p.get("id") for p in _ps.get("pages", [])] != ["profile"]: bad("profile", "page profile/profile is not declared")
try: _pb = json.load(open(os.path.join(app, "data/ui/profile/profile.json"), encoding="utf-8"))
except (OSError, ValueError): _pb = None
if _pb != [{"kind": "fragment", "id": "account_profile"}]: bad("profile", "data/ui/profile/profile.json no longer draws the account profile")
if "profile/profile" in ren.get("pages", {}) or "profile" in ren.get("sections", {}): bad("profile", "profile/profile is renamed away although it is a live page")

# ---- N5 the emulator walk
walk = src("test/emulator-me-socials.sh")
for need in ("section:pers", "section:prof", "section:proj", "me-pers", "me-prof", "me-proj", "page:health/home", "page:lnktree/leafy"):
    if need not in walk: bad("walk", f"emulator-me-socials.sh does not visit/screenshot {need}")
for line in out: print(line)
PY
}

expect_green() { local r; r="$(check_tree "$APP")"; if [ -z "$r" ]; then ok "the tree is green"; else bad "the tree is not green"; echo "$r" | sed 's/^/        /'; fi; }
only() { check_tree "$APP" | cut -f1 | grep -qx "$1" && return 1 || return 0; }
echo "== checks on the real tree =="
expect_green
for t in "tree|the bar, subpages, groups and defaults are Pers | Prof | Proj as declared (Lnktree first and default in Proj)" \
         "parity|every page, field, url and tile target of the baseline tree is still there (field parity)" \
         "renamed|ui.renamed lists every old page and section, names only live pages, and LegacyRoutes loads and applies it" \
         "urls|every tile opens its own site, the URL is declared data and no Kotlin file carries one" \
         "walk|the emulator walk visits Pers, Prof and Proj and screenshots each default page"; do
  id="${t%%|*}"; name="${t#*|}"
  if only "$id"; then ok "$name"; else bad "$name"; check_tree "$APP" | grep "^$id" | sed 's/^/        /'; fi
done

echo "== mutations: each check must go red on its own break =="
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
mkdir -p "$W/base"; cp -r "$APP/app" "$APP/data" "$APP/test" "$APP/build.json" "$W/base/" 2>/dev/null; rm -rf "$W/base/app/build"
mutant() { # id description command-run-in-copy
  local id="$1" desc="$2" cmd="$3" d="$W/m"
  rm -rf "$d"; cp -r "$W/base" "$d"
  ( cd "$d" && eval "$cmd" ) >/dev/null 2>&1
  if check_tree "$d" | cut -f1 | grep -qx "$id"; then ok "red: $desc"; else bad "NOT red: $desc"; fi
}
J='python3 -c'
BJ="import json;d=json.load(open('build.json'));U=d['ui'];S={s['id']:s for s in U['sections']}"
W_="json.dump(d,open('build.json','w'),indent=1)"
# parity: a field, a page, a block, a value, a tile target, section data
mutant parity "a field is dropped from a moved Leaf.y page" "$J \"import json;f='data/ui/prof/leafy.json';d=json.load(open(f));d[0].pop('url');json.dump(d,open(f,'w'))\""
mutant parity "a page's content is emptied (DD)" "echo '[]' > data/ui/pers/dd.json"
mutant parity "a ledger field is dropped from a moved Personal Archives tab" "$J \"import json;f='data/ui/pers/achieves/by40.json';d=json.load(open(f));l=d[0]['ledgers'][0];l['fields']=l['fields'][1:];json.dump(d,open(f,'w'))\""
mutant renamed "a removed Prof page id (work) no longer resolves to Profiles (#1937)" "$J \"$BJ;U['renamed']['pages'].pop('prof/work');$W_\""
mutant parity "an old experiences id (cv) resolves elsewhere than Profiles (#1937)" "$J \"$BJ;U['renamed']['pages']['experiences/cv']='pers/geo';$W_\""
mutant tree "Prof > More is declared again (#1937)" "$J \"$BJ;S['prof']['pages'].insert(1,{'id':'prof-profiles','label':'More','icon':'ic_nav_me'});$W_\""
mutant parity "a Health plan page's content is changed" "sed -i 's/\"page\": \"circadian\"/\"page\": \"circadian2\"/' data/ui/pers/health/plan/circadian.json"
mutant parity "a Health tracker page file is lost" "rm data/ui/pers/health/trackers/sleep.json; $J \"$BJ;h=[p for p in S['pers']['pages'] if p['id']=='health'][0];h['pages'][0]['pages']=[p for p in h['pages'][0]['pages'] if p['id']!='sleep'];$W_\""
mutant parity "a page is dropped from Pers (Astro)" "rm data/ui/pers/astro.json; $J \"$BJ;S['pers']['pages']=[p for p in S['pers']['pages'] if p['id']!='astro'];$W_\""
mutant parity "a tile target inside a page changes" "sed -i 's#\"page:pers/home\"#\"page:pers/bio\"#' data/ui/pers/achieves/geo.json"
mutant parity "the Health metric taxonomy loses a metric" "$J \"$BJ;S['pers']['metrics'].pop(0);$W_\""
mutant parity "the Achieves migration is dropped" "$J \"$BJ;S['pers'].pop('migration');$W_\""
mutant parity "the ig0 page (the old Instagram content) is dropped" "rm data/ui/pers/pers-profiles/ig0.json; $J \"$BJ;[p['pages'].pop(1) for p in S['pers']['pages'] if p['id']=='pers-profiles'];$W_\""
mutant parity "a moved page is left behind as a copy in the old folder" "mkdir -p data/ui/health && cp data/ui/pers/health/trackers/tracked.json data/ui/health/tracked.json"
mutant parity "a page is silently relabelled" "$J \"$BJ;[q.update(label='Moods') for p in S['pers']['pages'] if p['id']=='health' for t in p['pages'] if t['id']=='trackers' for q in t['pages'] if q['id']=='hr'];$W_\""
mutant parity "the removed Prof > More folder is back (#1937)" "mkdir -p data/ui/prof/prof-profiles && echo '[]' > data/ui/prof/prof-profiles/work.json"
# renamed
mutant renamed "an old page has no rename entry" "$J \"$BJ;U['renamed']['pages'].pop('health/home');$W_\""
mutant renamed "a rename names a page that does not exist" "$J \"$BJ;U['renamed']['pages']['health/home']='pers/nope';$W_\""
mutant renamed "the old Instagram tab id no longer resolves" "$J \"$BJ;U['renamed']['pages'].pop('pers/instagram');$W_\""
mutant renamed "the old Social tab id no longer resolves" "$J \"$BJ;U['renamed']['pages'].pop('pers/social');$W_\""
mutant tree "a Pers Profiles tab (Orkut) is dropped" "rm data/ui/pers/pers-profiles/orkut.json; $J \"$BJ;[p['pages'].pop() for p in S['pers']['pages'] if p['id']=='pers-profiles'];$W_\""
mutant renamed "the section alias is gone" "$J \"$BJ;U['renamed']['sections'].pop('health');$W_\""
mutant renamed "a rename sends a page to a section that is not declared" "$J \"$BJ;U['renamed']['sections']['socials']='social';$W_\""
mutant renamed "LegacyRoutes stops applying ui.renamed" "sed -i 's/table.pages\\[key\\]/table.pagesX[key]/' app/src/main/java/com/diegonmarcos/cloudme/LegacyRoutes.kt"
mutant renamed "app/build.gradle stops writing ui.renamed to its asset" "sed -i \"s/'renamed.json'/'renamed-x.json'/\" app/build.gradle"
mutant renamed "MainActivity stops loading the table" "sed -i 's/LegacyRoutes.load(this)/Unit/' app/src/main/java/com/diegonmarcos/cloudme/MainActivity.kt"
mutant renamed "MainActivity stops resolving old ids" "sed -i 's/LegacyRoutes.resolve(sectionId, pageId)/Pair(sectionId, pageId)/' app/src/main/java/com/diegonmarcos/cloudme/MainActivity.kt"
# tree
mutant tree "the bar order is swapped" "$J \"$BJ;b=U['bottom_nav'];b[0],b[1]=b[1],b[0];$W_\""
mutant tree "Proj opens on Unix, not Lnktree" "$J \"$BJ;p=S['proj']['pages'];p.append(p.pop(0));$W_\""
mutant tree "the app opens on Pers" "$J \"$BJ;U['default_section']='pers';$W_\""
mutant tree "the Lnktree tile is back under Pers" "$J \"$BJ;S['pers']['pages'].append({'id':'lnktree-site','label':'Lnktree','icon':'ic_link_tile'});$W_\""
mutant tree "Prof loses its companies group" "$J \"$BJ;S['prof']['groups']=S['prof']['groups'][:1];$W_\""
mutant tree "Personal Archives is called Achieves again" "$J \"$BJ;[p.update(label='Personal Achieves') for p in S['pers']['pages'] if p['id']=='achieves'];$W_\""
mutant tree "Health holds Plan before Trackers" "$J \"$BJ;[p['pages'].reverse() for p in S['pers']['pages'] if p['id']=='health'];$W_\""
mutant tree "the Lnktree bottom-nav entry is removed from the bar" "$J \"$BJ;U['bottom_nav'].remove('lnktree');$W_\""
mutant tree "the Lnktree section loses a page" "$J \"$BJ;S['lnktree']['pages'].pop();$W_\""
mutant tree "the Lnktree section is removed" "$J \"$BJ;U['bottom_nav'].remove('lnktree');U['sections']=[s for s in U['sections'] if s['id']!='lnktree'];U['default_section']='proj';$W_\""
mutant tree "the app stops opening on Lnktree" "$J \"$BJ;U['default_section']='proj';$W_\""
mutant urls "the Lnktree bottom-nav page loses its default URL" "sed -i 's#https://linktree.diegonmarcos.com/#https://example.com/#' data/ui/lnktree/lt-lnktree-site.json"
mutant parity "a Lnktree bottom-nav page file is emptied" "echo '[]' > data/ui/lnktree/lt-dd.json"
mutant tree "the page cache covers only Proj" "$J \"$BJ;U['lnktree_cache']['sections']=['proj'];$W_\""
# urls
mutant urls "Pers > Personal opens another site" "sed -i 's#https://diegonmarcos.github.io/mySocials/#https://example.com/#' data/ui/pers/pers-profiles/personal.json"
mutant urls "a Proj leaf embeds another site" "sed -i 's#https://diegonmarcos.github.io/galaxy/#https://example.com/#' data/ui/proj/pj-cg/pj-cg-omniverse.json"
mutant tree "a Proj leaf is dropped (42 Dev)" "rm data/ui/proj/pj-lecole42/pj-lecole42-dev.json; $J \"$BJ;[g['pages'].pop() for p in [x for x in S['proj']['pages'] if x['id']=='pj-lecole42'] for g in [p]];$W_\""
mutant tree "Lnktree opens on Professional again" "$J \"$BJ;p=S['lnktree']['pages'];p.append(p.pop(0));$W_\""
mutant renamed "an old Proj area id has no alias" "$J \"$BJ;U['renamed']['pages'].pop('proj/area-cg');$W_\""
mutant renamed "the old Proj Lnktree id has no alias" "$J \"$BJ;U['renamed']['pages'].pop('proj/lnktree-site');$W_\""
mutant urls "Prof > Nexus.z opens Stark.x" "sed -i 's#diegonmarcos.github.io/nexus/#diegonmarcos.github.io/stark/#' data/ui/prof/nexus.json"
mutant urls "a tile URL becomes a Kotlin literal" "sed -i '0,/^object Sections {/s//object Sections {\n    const val PERSONAL = \"https:\\/\\/diegonmarcos.com\\/\"/' app/src/main/java/com/diegonmarcos/cloudme/Sections.kt"
# profile
mutant profile "the profile section leaves the settings (unreachable)" "$J \"$BJ;U['settings'].remove('profile');$W_\""
mutant profile "the profile page file is emptied" "echo '[]' > data/ui/profile/profile.json"
mutant profile "the profile page is dropped from its section" "$J \"$BJ;S['profile']['pages']=[];$W_\""
# walk
mutant walk "the walk no longer visits Prof" "sed -i 's#section:prof#section:inventory#' test/emulator-me-socials.sh"
mutant walk "the walk takes no Proj screenshot" "sed -i 's/me-proj/me-xxx/g' test/emulator-me-socials.sh"

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
