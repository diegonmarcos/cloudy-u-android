#!/usr/bin/env bash
# Tester (#1901): Health became Pers, Experiences became Prof, Socials became Proj, and the Lnktree section was spread over them. A MOVE, not a copy.
#   N1 tree     the bar, the subpages, their order, the groups and the defaults are exactly the declared tree
#   N2 parity   field parity against test/fixtures/nav-baseline-1901.json (the tree dumped BEFORE the move by test/nav-tree-dump.py): every page of the
#               old tree resolves, through ui.renamed, to a page that still holds every field, url and tile target it held; every section's own data
#               (the Health metric taxonomy, the Achieves migration) is still declared; labels only changed where the owner renamed them
#   N3 renamed  ui.renamed names only pages and sections of the live tree, lists EVERY page of the old tree, and LegacyRoutes reads and bakes it
#   N4 urls     each tile opens its own site: the URL of every embedded page is declared data (data/ui/<section>/..), none is a literal in Kotlin
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
if ui.get("bottom_nav") != ["pers", "prof", "proj", "inventory", "buro", "llc"] or \
   [secs[i]["label"] for i in ui.get("bottom_nav", []) if i in secs] != ["Pers", "Prof", "Proj", "Inventory", "Buro", "LLC"]:
    bad("tree", f"the bar is {ui.get('bottom_nav')}, wanted Pers | Prof | Proj | Inventory | Buro | LLC")
for gone in ("health", "experiences", "socials", "lnktree"):
    if gone in secs: bad("tree", f"{gone} is a section again: it moved (ui.renamed keeps its ids working)")
def labels(ps): return [(p["id"], p["label"]) for p in ps]
if labels(secs.get("pers", {}).get("pages", [])) != [("pers-profiles", "Profiles"), ("health", "Health"), ("astro", "Astro"), ("dd", "DD"), ("achieves", "Personal Archives")]:
    bad("tree", "Pers is not Profiles, Health, Astro, DD, Personal Archives")
if labels(sub("pers", "pers-profiles").get("pages", [])) != [("personal", "Personal"), ("instagram", "Instagram"), ("social", "Social")]:
    bad("tree", "Pers > Profiles is not Personal, Instagram, Social")
if labels(sub("pers", "health").get("pages", [])) != [("trackers", "Trackers"), ("plan", "Plan"), ("health-profile", "Record")]:
    bad("tree", "Pers > Health is not Trackers, Plan, Record")
if [p["id"] for p in sub("pers", "health", "trackers").get("pages", [])] != ["tracked", "activities", "bodycomp", "hr", "sleep"] or \
   [p["id"] for p in sub("pers", "health", "plan").get("pages", [])] != ["home", "bio", "circadian", "biomarkers", "timeline", "objectives", "matrix", "culinary", "clash", "thermal", "hemo", "shop", "dossier", "workout"]:
    bad("tree", "Pers > Health does not hold the 5 Trackers and 14 Plan pages in their old order")
if [p["id"] for p in sub("pers", "achieves").get("pages", [])] != ["geo", "endurance", "growth", "community", "by30", "by40", "by50"]:
    bad("tree", "Pers > Personal Archives is not Geo, Endurance, Personal Growth, Community, By 30, By 40, By 50")
if labels(secs.get("prof", {}).get("pages", [])) != [("prof-profiles", "Profiles"), ("leafy", "Leaf.y"), ("nexus", "Nexus.z"), ("stark", "Stark.x")] or \
   {g["id"]: g["pages"] for g in secs.get("prof", {}).get("groups", [])} != {"profiles": ["prof-profiles"], "companies": ["leafy", "nexus", "stark"]}:
    bad("tree", "Prof is not Profiles | Leaf.y, Nexus.z, Stark.x")
if [p["id"] for p in sub("prof", "prof-profiles").get("pages", [])] != ["professional", "work", "study"] or \
   [p["id"] for p in sub("prof", "prof-profiles", "study").get("pages", [])] != ["cv", "roadmap"]:
    bad("tree", "Prof > Profiles is not Professional, Work, Study (CV, Roadmap)")
if [p["id"] for p in secs.get("proj", {}).get("pages", [])] != ["lnktree-site", "area-unix", "area-cg", "area-capital", "area-hardtech", "area-lecole42", "area-front"] or \
   secs.get("proj", {}).get("pages", [{}])[0].get("label") != "Lnktree":
    bad("tree", "Proj is not Lnktree | Unix, CG, Capital, Hardtech, 42, Projects")
if {g["id"]: g["pages"] for g in secs.get("proj", {}).get("groups", [])} != {"lnktree": ["lnktree-site"], "proj": ["area-unix", "area-cg", "area-capital", "area-hardtech", "area-lecole42", "area-front"]}:
    bad("tree", "Proj is not the rows Lnktree / Proj")
if (secs.get("proj", {}).get("pages") or [{}])[0].get("id") != "lnktree-site" or ui.get("default_section") != "proj":
    bad("tree", "Lnktree is not Proj's first and default page, or Proj is not the section the app opens on")
if "lnktree-site" in [p["id"] for p in secs.get("pers", {}).get("pages", [])] or "lnktree-site" in ids(secs.get("pers", {}).get("pages")):
    bad("tree", "the Lnktree tile is still under Pers (it moved to Proj)")
if ui.get("repo_tree", {}).get("group") not in {g["id"] for g in secs.get("proj", {}).get("groups", [])}:
    bad("tree", "ui.repo_tree.group is not a group of Proj")
if ui.get("vault_rows", {}).get("projects", {}).get("section") != "proj":
    bad("tree", "Tasks > Projects does not take Proj's group")
lc = ui.get("lnktree_cache", {})
if set(lc.get("sections", [])) != {"pers", "prof", "proj"}:
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
    "experiences/achieves": "Personal Archives", "lnktree/health": "Record", "lnktree/personal": "Instagram", "socials/socials-personal": "Instagram",
}
for key, b in sorted(base["pages"].items()):
    sec, pid = key.split("/", 1)
    ns, np = resolve(sec, pid)
    if ns not in secs:
        bad("parity", f"{key}: its section {ns} is not declared"); continue
    t = now["pages"].get(f"{ns}/{np}")
    if t is None:
        bad("parity", f"{key} -> {ns}/{np}: the page is gone"); continue
    if b["container"] != t["container"]:
        bad("parity", f"{key} -> {ns}/{np}: a container became a leaf or the other way round")
    have = {norm(x) for x in t["leaves"]}
    for leaf in b["leaves"]:
        if norm(leaf) not in have:
            bad("parity", f"{key} -> {ns}/{np}: the field {leaf[:110]} is gone")
    for u in b["urls"]:
        if u not in t["urls"]: bad("parity", f"{key} -> {ns}/{np}: the url {u} is gone")
    for tg in b["targets"]:
        n = norm(f'x="{tg}"')[3:-1]
        if n not in t["targets"]: bad("parity", f"{key} -> {ns}/{np}: the tile target {tg} is gone")
    if b["label"] and t["label"] != RELABELED.get(key, b["label"]):
        bad("parity", f"{key} -> {ns}/{np}: the label {b['label']!r} became {t['label']!r}")
# every section's own data (Health's metric taxonomy, the Achieves migration ...) is still declared, on the section that took it over
allextras = {x for s in now["sections"] for x in s["extras"]}
for s in base["sections"]:
    for x in s["extras"]:
        if x not in allextras: bad("parity", f"section {s['id']}: the declaration {x[:110]} is gone")
# the bar: every old bar section is a bar section of the new tree
for i in base["bottom_nav"]:
    if resolve(i, None)[0] not in ui.get("bottom_nav", []): bad("parity", f"the old bar section {i} resolves to {resolve(i, None)[0]}, which is not on the bar")
# a moved page carries its content once: every old page file exists once more (no file is left behind in a retired folder)
for gone in ("health", "experiences", "socials", "lnktree"):
    if os.path.isdir(os.path.join(app, "data/ui", gone)): bad("parity", f"data/ui/{gone} is back (a copy, not a move)")

# ---- N3 the rename map
for old, to in ren.get("pages", {}).items():
    s, _, p = to.partition("/")
    if s not in secs or (p and p not in ids(secs[s].get("pages"))): bad("renamed", f"ui.renamed.pages[{old}] names {to}, which is not a page of the tree")
for old, to in ren.get("sections", {}).items():
    if to not in secs: bad("renamed", f"ui.renamed.sections[{old}] names {to}, which is not a section")
    if old in secs: bad("renamed", f"ui.renamed.sections[{old}] is a live section")
for key in base["pages"]:
    if key.split("/", 1)[0] not in secs and key not in ren.get("pages", {}):   # a page of a section that is gone needs its entry; the live sections' ids are unchanged
        bad("renamed", f"ui.renamed.pages has no entry for the old page {key}")
for s in base["sections"]:
    if s["id"] not in ren.get("sections", {}) and s["id"] not in secs:
        bad("renamed", f"ui.renamed.sections has no entry for the old section {s['id']}")
src = lambda f: open(os.path.join(app, f), encoding="utf-8").read() if os.path.exists(os.path.join(app, f)) else ""
lr = src("app/src/main/java/com/diegonmarcos/cloudme/LegacyRoutes.kt")
if "UI_RENAMED_B64" not in lr or "fun renamed(" not in lr or "table.pages[key]" not in lr or "table.sections[section]" not in lr:
    bad("renamed", "LegacyRoutes does not apply ui.renamed (pages first, then the section alias)")
if "UI_RENAMED_B64" not in src("app/build.gradle") or "ui.renamed.pages[" not in src("app/build.gradle"):
    bad("renamed", "app/build.gradle does not bake and validate ui.renamed")
mo = src("app/src/main/java/com/diegonmarcos/cloudme/MainActivity.kt")
if "LegacyRoutes.resolve(sectionId, pageId)" not in mo:
    bad("renamed", "MainActivity.open does not resolve the id through LegacyRoutes (deep links and the SuperApp Me row would miss)")

# ---- N4 every tile opens its own site; the URL is declared data
SITES = {  # tile -> (page file, the url it embeds)
    "Prof > Profiles > Professional (LinkedIn)": ("data/ui/prof/prof-profiles/professional.json", "mysocials/linkedin.html"),
    "Prof > Leaf.y": ("data/ui/prof/leafy.json", "https://diegonmarcos.github.io/leafy/"),
    "Prof > Nexus.z": ("data/ui/prof/nexus.json", "https://diegonmarcos.github.io/nexus/"),
    "Prof > Stark.x": ("data/ui/prof/stark.json", "https://diegonmarcos.github.io/stark/"),
    "Pers > Profiles > Personal": ("data/ui/pers/pers-profiles/personal.json", "https://diegonmarcos.com/"),
    "Pers > Profiles > Instagram": ("data/ui/pers/pers-profiles/instagram.json", "mysocials/instagram-diegocnmarcos_.html"),
    "Pers > Astro": ("data/ui/pers/astro.json", "https://diegonmarcos.github.io/myAstro/"),
    "Proj > Lnktree": ("data/ui/proj/lnktree-site.json", "https://linktree.diegonmarcos.com/"),
}
for tile, (f, url) in SITES.items():
    try: blocks = json.load(open(os.path.join(app, f), encoding="utf-8"))
    except (OSError, ValueError): bad("urls", f"{tile}: {f} is missing"); continue
    if len(blocks) != 1 or blocks[0].get("kind") != "fragment" or blocks[0].get("id") != "web" or blocks[0].get("url") != url:
        bad("urls", f"{tile} does not open {url} embedded")
    if not url.startswith("https://") and not os.path.exists(os.path.join(app, "data/web", url)):
        bad("urls", f"{tile}: the bundled page {url} does not exist")
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
         "renamed|ui.renamed lists every old page and section, names only live pages, and LegacyRoutes bakes and applies it" \
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
mutant parity "a seeded Work role is dropped" "$J \"import json;f='data/ui/prof/prof-profiles/work.json';d=json.load(open(f));d[0]['ledgers'][0]['builtin'].pop(0);json.dump(d,open(f,'w'))\""
mutant parity "a Health plan page's content is changed" "sed -i 's/\"page\": \"circadian\"/\"page\": \"circadian2\"/' data/ui/pers/health/plan/circadian.json"
mutant parity "a Health tracker page file is lost" "rm data/ui/pers/health/trackers/sleep.json; $J \"$BJ;h=[p for p in S['pers']['pages'] if p['id']=='health'][0];h['pages'][0]['pages']=[p for p in h['pages'][0]['pages'] if p['id']!='sleep'];$W_\""
mutant parity "a page is dropped from Pers (Astro)" "rm data/ui/pers/astro.json; $J \"$BJ;S['pers']['pages']=[p for p in S['pers']['pages'] if p['id']!='astro'];$W_\""
mutant parity "a tile target inside a page changes" "sed -i 's#\"page:pers/home\"#\"page:pers/bio\"#' data/ui/pers/achieves/geo.json"
mutant parity "the Health metric taxonomy loses a metric" "$J \"$BJ;S['pers']['metrics'].pop(0);$W_\""
mutant parity "the Achieves migration is dropped" "$J \"$BJ;S['pers'].pop('migration');$W_\""
mutant parity "the Instagram page is dropped (Personal's old content)" "rm data/ui/pers/pers-profiles/instagram.json; $J \"$BJ;[p['pages'].pop(1) for p in S['pers']['pages'] if p['id']=='pers-profiles'];$W_\""
mutant parity "a moved page is left behind as a copy in the old folder" "mkdir -p data/ui/health && cp data/ui/pers/health/trackers/tracked.json data/ui/health/tracked.json"
mutant parity "a page is silently relabelled" "$J \"$BJ;[q.update(label='Moods') for p in S['pers']['pages'] if p['id']=='health' for t in p['pages'] if t['id']=='trackers' for q in t['pages'] if q['id']=='hr'];$W_\""
mutant parity "Work leaves Prof for nowhere" "rm data/ui/prof/prof-profiles/work.json; $J \"$BJ;[p['pages'].pop(1) for p in S['prof']['pages'] if p['id']=='prof-profiles'];$W_\""
# renamed
mutant renamed "an old page has no rename entry" "$J \"$BJ;U['renamed']['pages'].pop('lnktree/dd');$W_\""
mutant renamed "a rename names a page that does not exist" "$J \"$BJ;U['renamed']['pages']['health/home']='pers/nope';$W_\""
mutant renamed "the section alias is gone" "$J \"$BJ;U['renamed']['sections'].pop('health');$W_\""
mutant renamed "a rename sends a page to a section that is not declared" "$J \"$BJ;U['renamed']['sections']['socials']='social';$W_\""
mutant renamed "LegacyRoutes stops applying ui.renamed" "sed -i 's/table.pages\\[key\\]/table.pagesX[key]/' app/src/main/java/com/diegonmarcos/cloudme/LegacyRoutes.kt"
mutant renamed "app/build.gradle stops baking ui.renamed" "sed -i 's/UI_RENAMED_B64/UI_RENAMED_X/' app/build.gradle"
mutant renamed "MainActivity stops resolving old ids" "sed -i 's/LegacyRoutes.resolve(sectionId, pageId)/Pair(sectionId, pageId)/' app/src/main/java/com/diegonmarcos/cloudme/MainActivity.kt"
# tree
mutant tree "the bar order is swapped" "$J \"$BJ;b=U['bottom_nav'];b[0],b[1]=b[1],b[0];$W_\""
mutant tree "Proj opens on Unix, not Lnktree" "$J \"$BJ;p=S['proj']['pages'];p.append(p.pop(0));$W_\""
mutant tree "the app opens on Pers" "$J \"$BJ;U['default_section']='pers';$W_\""
mutant tree "the Lnktree tile is back under Pers" "$J \"$BJ;S['pers']['pages'].append({'id':'lnktree-site','label':'Lnktree','icon':'ic_link_tile'});$W_\""
mutant tree "Prof loses its companies group" "$J \"$BJ;S['prof']['groups']=S['prof']['groups'][:1];$W_\""
mutant tree "Personal Archives is called Achieves again" "$J \"$BJ;[p.update(label='Personal Achieves') for p in S['pers']['pages'] if p['id']=='achieves'];$W_\""
mutant tree "Health holds Plan before Trackers" "$J \"$BJ;[p['pages'].reverse() for p in S['pers']['pages'] if p['id']=='health'];$W_\""
mutant tree "the Lnktree section is declared again" "$J \"$BJ;U['sections'].append({'id':'lnktree','label':'Lnktree','pages':[]});$W_\""
mutant tree "the page cache covers only Proj" "$J \"$BJ;U['lnktree_cache']['sections']=['proj'];$W_\""
# urls
mutant urls "Pers > Personal opens another site" "sed -i 's#https://diegonmarcos.com/#https://example.com/#' data/ui/pers/pers-profiles/personal.json"
mutant urls "Proj > Lnktree opens another site" "sed -i 's#https://linktree.diegonmarcos.com/#https://example.com/#' data/ui/proj/lnktree-site.json"
mutant urls "Prof > Nexus.z opens Stark.x" "sed -i 's#diegonmarcos.github.io/nexus/#diegonmarcos.github.io/stark/#' data/ui/prof/nexus.json"
mutant urls "Prof > Professional is not the LinkedIn page" "sed -i 's#mysocials/linkedin.html#mysocials/social.html#' data/ui/prof/prof-profiles/professional.json"
mutant urls "a tile URL becomes a Kotlin literal" "sed -i '0,/^object Sections {/s//object Sections {\n    const val PERSONAL = \"https:\\/\\/diegonmarcos.com\\/\"/' app/src/main/java/com/diegonmarcos/cloudme/Sections.kt"
# walk
mutant walk "the walk no longer visits Prof" "sed -i 's#section:prof#section:inventory#' test/emulator-me-socials.sh"
mutant walk "the walk takes no Proj screenshot" "sed -i 's/me-proj/me-xxx/g' test/emulator-me-socials.sh"

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
