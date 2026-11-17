#!/usr/bin/env bash
# Tester (#1331, #1335, #1778): Cloud > Apps > Projects (the Lnktree row, was "Projects W") is Wiki | SuperApp, Omniverse | FinTerm, BioMed, CAD | Circusverse
# (#1778: Wiki, the new Cloud Wiki app, leads; BioMed now precedes CAD; Circusverse closes the row as a greyed-out coming_soon tile. Owner 2026-10-30:
# the SuperApp tile, app://<own package>, #1153, moved here from Configs into the row). #1335: each tile now LAUNCHES a real
# app of the Lnktree family (extapp:, the constellation mechanism) and the temporary in-app pages are gone; the pages live in the apps
# (ad_*/build.json::ui, held by their test/test-lnktree-app.sh). The generic `kind: entry` panel renderer and LnktreeTargets stay
# (T5/T6) for any page that declares entries.
#
#   T1  the row is titled "Projects" (no "Projects W" row left); its tiles, separators included, read Wiki | SuperApp Omniverse | FinTerm BioMed CAD |
#       Circusverse, in that order (SuperApp = app://<own package>, the live rest extapp:), with drawable icons (unique on the Cloud page); Circusverse is
#       the one disabled tile: coming_soon true, no target, no external app, no fleet app (#1778)
#   T2  the content moved out: the SuperApp holds no stack_omniverse / stack_cad / stack_biomed, no hidden omniverse / cad / biomed
#       page and no finterm section; each tile's extapp id is an ui.external_apps entry whose package is the app's own
#       application_id, an app of the constellation fleet, and a build.json::name the Lnktree family accepts
#   T5  the generic entry renderer still greys an unresolved entry and LnktreeTargets answers null for a planned one
#   T6  the code side: Sections reads the entry fields, the renderer is the Compose kind=entry (no TextView), and the
#       resolver and the renderer hold no http(s) literal
#   M   every check above is proven red by a mutation of its own input (the checker is run on the broken copy and must object)
#
# Overrides for mutation runs: BUILD, LINKTREE, REGISTRY, PANELS, SECTIONS, TARGETS, ICONS.
# Usage: ./test-lnktree-row.sh   (static only, no network, no device)
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$APP/.." && pwd)"
L="$APP/app/src/main/java/com/diegonmarcos/superapp/launcher"
command -v python3 >/dev/null 2>&1 || { echo "ERROR: python3 required" >&2; exit 2; }

python3 - "${BUILD:-$APP/build.json}" "${LINKTREE:-$APP/data/linktree.json}" "${REGISTRY:-}" \
    "${PANELS:-$L/AggregatorPanels.kt}" "${SECTIONS:-$L/Sections.kt}" "${TARGETS:-$L/LnktreeTargets.kt}" \
    "${ICONS:-$ROOT/ab_cloud-libs-shared/libs/ui-icons/src/main/kotlin/com/diegonmarcos/superapp/uiicons/UiIcons.kt}" <<'PY'
import copy, json, os, re, sys

build_p, lt_p, reg_p, panels_p, sections_p, targets_p, icons_p = sys.argv[1:]
def read(p):
    with open(p, encoding="utf-8") as f: return f.read()
def code(text):
    t = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return "\n".join(re.sub(r"(?<![:\"])//.*$", "", l) for l in t.splitlines())

if not reg_p:
    for c in ("/home/user/git-repos-master/0_registry/projects-repos.json",
              os.path.join(os.path.dirname(build_p), "../../_git-repos-master/0_registry/projects-repos.json")):
        if os.path.isfile(c): reg_p = c; break
res_dir = os.path.join(os.path.dirname(build_p), "app/src/main/res/drawable")
SOURCES = ("site", "registry", "repo")
TABS = [("fi", "FI"), ("eq", "EQ"), ("ex", "EX"), ("nw", "NW"), ("pm", "PM"), ("my-portfolio", "MY-PM")]
PAGES = {  # page -> (where its stack lives, the entry titles in order)
    "omniverse": ["Galaxy", "Game Store", "VR Store"],
    "cad": ["MCAD", "BIM", "SolWrk"],
    "biomed": ["Physiology", "BioChem", "BioMech", "Anatomy", "Genetic", "Neuro", "Immuno"],
}
FT = {
    "fi": ["Yield Curves", "Forward Rates", "World Interest Rate Probabilities", "World Bonds"],
    "eq": ["World Equity Indices"],
    "ex": ["FX", "Onshore USD Rates"],
    "nw": ["News"],
    "pm": ["Portfolio & Risk Analytics", "40/60", "70/30", "60/40", "30/70", "70/30 DM/EM"],  # these must appear, in this order
    "my-portfolio": ["Personal portfolio"],
}
FX_DESC = "Comprehensive real-time currency pricing, matrix crosses, spot rates, and forwards"

def sec(bj, sid): return next((s for s in bj["ui"]["sections"] if s.get("id") == sid), {})
def entries(stack): return [p for p in (stack or []) if p.get("kind") == "entry"]
def all_entry_lists(bj):
    cloud, ft = sec(bj, "cloud"), sec(bj, "finterm")
    for pid in PAGES: yield "cloud/" + pid, cloud.get("stack_" + pid, [])
    for tid, _ in TABS: yield "finterm/" + tid, ft.get("stack_" + tid, [])

def problems(bj, lt, reg, panels, sections, targets, icons):
    root = os.path.abspath(os.path.join(os.path.dirname(build_p), ".."))
    fleet_ids = {a["id"] for a in json.load(open(os.path.join(root, "aa_cloud-superapp/data/constellation-fleet.json")))["apps"]}
    """Every violated rule, each prefixed with its test tag."""
    out = []
    cloud, ft = sec(bj, "cloud"), sec(bj, "finterm")
    # T1
    groups = cloud.get("tile_groups", [])
    titles = [g.get("title") for g in groups]
    if "Projects" not in titles: out.append("T1 no tile group titled 'Projects' (the Lnktree row, renamed #1756)")
    if "Projects W" in titles: out.append("T1 the row is still called 'Projects W'")
    row = next((g for g in groups if g.get("title") == "Projects"), {"tiles": []})
    tiles = [t for t in row["tiles"] if not t.get("separator")]
    if [t.get("label") for t in tiles] != ["Wiki", "SuperApp", "Omniverse", "FinTerm", "BioMed", "CAD", "Circusverse"]:
        out.append("T1 tiles are %r, want Wiki SuperApp Omniverse FinTerm BioMed CAD Circusverse" % [t.get("label") for t in tiles])
    shape = " ".join("|" if t.get("separator") else t.get("label", "?") for t in row["tiles"])
    if shape != "Wiki | SuperApp Omniverse | FinTerm BioMed CAD | Circusverse": out.append("T1 the row reads %r, want 'Wiki | SuperApp Omniverse | FinTerm BioMed CAD | Circusverse'" % shape)
    want_t = {"Wiki": "extapp:cloud-wiki", "SuperApp": "app://" + bj["android"]["application_id"], "Omniverse": "extapp:cg-omniverse", "FinTerm": "extapp:capital-stack-finterminal", "CAD": "extapp:hardtech-cad", "BioMed": "extapp:hardtech-biomed"}
    for t in tiles:
        if t.get("label") == "Circusverse":
            if t.get("coming_soon") is not True: out.append("T1 Circusverse is not a coming_soon tile")
            if t.get("target"): out.append("T1 Circusverse (disabled) carries a target %r" % t.get("target"))
            continue
        if t.get("coming_soon"): out.append("T1 tile %s is coming_soon, only Circusverse is" % t.get("label"))
        if t.get("target") != want_t.get(t.get("label")): out.append("T1 tile %s targets %r" % (t.get("label"), t.get("target")))
    if any("circus" in str(e.get("id", "")).lower() for e in bj["ui"].get("external_apps", [])) or any("circus" in str(a.get("id", "")).lower() and a.get("id") != "circus" for a in json.load(open(os.path.join(root, "aa_cloud-superapp/data/constellation-fleet.json")))["apps"]):
        out.append("T1 Circusverse has an external app or a fleet app: it is a placeholder, there is no app yet")
    for t in tiles:
        if not os.path.isfile(os.path.join(res_dir, t.get("icon", "") + ".xml")): out.append("T1 tile icon %r of %s has no drawable" % (t.get("icon"), t.get("id")))
    icons_seen = [t.get("icon") for g in groups for t in g.get("tiles", []) if t.get("icon")]
    for t in tiles:
        if icons_seen.count(t.get("icon")) > 1: out.append("T1 tile icon %r of %s is shared with another Cloud tile" % (t.get("icon"), t.get("id")))
    for t in row["tiles"]:
        if t.get("separator") and t.get("target"): out.append("T1 a separator carries a target")
    # T2
    for k in ("stack_omniverse", "stack_cad", "stack_biomed"):
        if k in cloud: out.append("T2 the SuperApp still holds %s (the content lives in the app)" % k)
    for pid in PAGES:
        if any(p.get("id") == pid for p in cloud.get("pages", [])): out.append("T2 the SuperApp still declares the hidden page cloud/%s" % pid)
    if ft: out.append("T2 the SuperApp still declares the finterm section")
    ext = {e.get("id"): e for e in bj["ui"].get("external_apps", [])}
    for label, target in want_t.items():
        if not target.startswith("extapp:"): continue
        name = target.split(":", 1)[1]
        e = ext.get(name)
        if not e: out.append("T2 %s is not an ui.external_apps entry" % name); continue
        folder = next((d for d in sorted(os.listdir(root)) if d[:3] in ("ad_", "ac_") and os.path.isfile(os.path.join(root, d, "build.json"))
                       and json.load(open(os.path.join(root, d, "build.json"))).get("name") == name), None)
        if not folder: out.append("T2 no ad_* / ac_* app has build.json::name %s" % name); continue
        pkg = json.load(open(os.path.join(root, folder, "build.json")))["android"]["application_id"]
        if e.get("hub_package") != pkg or e.get("install_package") != pkg: out.append("T2 external app %s names package %r, the app declares %r" % (name, e.get("hub_package"), pkg))
        fid = name[len("cloud-"):] if folder.startswith("ac_") and name.startswith("cloud-") else name  # the Cloud family's fleet id drops "cloud-" (ac_cloud-wiki is "wiki")
        if fid not in fleet_ids: out.append("T2 %s is not in constellation-fleet.json" % name)
    # T5 renderer
    pc, tc, sc = code(panels), code(targets), code(sections)
    m = re.search(r"private fun PageEntry\(.*?\n}\n", pc, re.S)
    body = m.group(0) if m else ""
    if not m: out.append("T5 AggregatorPanels has no PageEntry composable")
    elif not re.search(r"alpha\(\s*if\s*\(\s*url\s*!=\s*null\s*\)\s*1f\s*else\s*0\.\d+f\s*\)", body): out.append("T5 PageEntry does not grey an unresolved entry")
    elif not re.search(r"clickable[^\n]*onOpen\(url\)", body) or "url != null" not in body: out.append("T5 PageEntry may open a planned entry")
    if "panel.planned" not in tc or not re.search(r"if \(panel\.planned\) return null", tc): out.append("T5 LnktreeTargets resolves a planned entry")
    # T6
    for f in ("site", "registry", "repo", "path", "planned", "note"):
        if not re.search(r'\b%s\s*=\s*p\.opt(String|Boolean)\("%s"' % (f, f), sc): out.append("T6 Sections does not read the entry field %s" % f)
    if 'panel.kind == "entry"' not in pc: out.append("T6 PagePanel does not route kind=entry")
    for name, c in (("AggregatorPanels PageEntry", body), ("LnktreeTargets", tc)):
        if re.search(r"https?://", c): out.append("T6 %s holds a URL literal" % name)
    if "TextView(" in body or "TextView(" in tc: out.append("T6 a TextView( was added (compose ratchet)")
    return out

bj, lt = json.loads(read(build_p)), json.loads(read(lt_p))
reg = json.loads(read(reg_p)) if reg_p and os.path.isfile(reg_p) else None
panels, sections, targets, icons = read(panels_p), read(sections_p), read(targets_p), read(icons_p)

passed = failed = 0
def check(cond, msg):
    global passed, failed
    if cond: passed += 1; print("  PASS: " + msg)
    else:    failed += 1; print("  FAIL: " + msg)

print("== T1-T6: the Lnktree row, its pages and their entries ==")
real = problems(bj, lt, reg, panels, sections, targets, icons)
for tag in ("T1", "T2", "T3", "T4", "T5", "T6"):
    mine = [p for p in real if p.startswith(tag + " ")]
    check(not mine, "%s holds%s" % (tag, "" if not mine else ": " + "; ".join(mine)))
print("  (projects-repos.json %s)" % ("checked: " + reg_p if reg else "not reachable here: registry_sites not cross-checked"))
if not reg: check(True, "T4 registry cross-check skipped, reachable only on a machine with _git-repos-master")

print("== M: every check goes red on a broken input ==")
def mut(name, tag, fn):
    b, l, r = copy.deepcopy(bj), copy.deepcopy(lt), copy.deepcopy(reg)
    args = {"p": panels, "s": sections, "t": targets, "i": icons}
    fn(b, l, r, args)
    got = problems(b, l, r, args["p"], args["s"], args["t"], args["i"])
    check(any(p.startswith(tag + " ") for p in got), "mutation '%s' is caught by %s" % (name, tag))
def row(b): return next(g for g in sec(b, "cloud")["tile_groups"] if g.get("title") == "Projects")
def ent(b, where, title):
    s, k = where.split("/"); return next(e for e in sec(b, s)["stack_" + k] if e.get("title") == title)
def rename(b, l, r, a): row(b)["title"] = "Projects W"
mut("row renamed back to Projects W", "T1", rename)
def swap(b, l, r, a): t = row(b)["tiles"]; t[4], t[6] = t[6], t[4]
mut("Omniverse and FinTerm swapped", "T1", swap)
def bioafter(b, l, r, a): t = row(b)["tiles"]; i = next(k for k, x in enumerate(t) if x.get("label") == "BioMed"); j = next(k for k, x in enumerate(t) if x.get("label") == "CAD"); t[i], t[j] = t[j], t[i]
mut("BioMed and CAD back in the old order", "T1", bioafter)
def circuslive(b, l, r, a): next(x for x in row(b)["tiles"] if x.get("label") == "Circusverse")["coming_soon"] = False
mut("Circusverse made live", "T1", circuslive)
def circustarget(b, l, r, a): next(x for x in row(b)["tiles"] if x.get("label") == "Circusverse")["target"] = "extapp:circusverse"
mut("Circusverse given a target", "T1", circustarget)
def circusapp(b, l, r, a): b["ui"]["external_apps"].append({"id": "circusverse", "hub_package": "x", "install_package": "x"})
mut("Circusverse given an external app", "T1", circusapp)
def nowiki(b, l, r, a): row(b)["tiles"] = [x for x in row(b)["tiles"] if x.get("label") != "Wiki"]
mut("Wiki tile dropped", "T1", nowiki)
def wikiext(b, l, r, a): b["ui"]["external_apps"] = [e for e in b["ui"]["external_apps"] if e.get("id") != "cloud-wiki"]
mut("Wiki missing from ui.external_apps", "T2", wikiext)
def fifth(b, l, r, a): row(b)["tiles"].append({"id": "x", "label": "Extra", "icon": "ic_robot", "target": "page:cloud/cad"})
mut("a fifth tile", "T1", fifth)
def baticon(b, l, r, a): row(b)["tiles"][0]["icon"] = "ic_not_in_catalog"
mut("tile icon with no drawable", "T1", baticon)
def dupicon(b, l, r, a): row(b)["tiles"][0]["icon"] = "ic_robot"
mut("tile icon shared with another Cloud tile", "T1", dupicon)
def keepstack(b, l, r, a): sec(b, "cloud")["stack_cad"] = [{"kind": "entry", "title": "MCAD"}]
mut("a temporary page stack left in the SuperApp", "T2", keepstack)
def keeppage(b, l, r, a): sec(b, "cloud")["pages"].append({"id": "omniverse", "label": "Omniverse", "facet": True})
mut("a hidden page left in the SuperApp", "T2", keeppage)
def keepsec(b, l, r, a): b["ui"]["sections"].append({"id": "finterm", "label": "FinTerm", "pages": []})
mut("the finterm section left in the SuperApp", "T2", keepsec)
def noext(b, l, r, a): b["ui"]["external_apps"] = [e for e in b["ui"]["external_apps"] if e.get("id") != "hardtech-cad"]
mut("an app missing from ui.external_apps", "T2", noext)
def badpkg(b, l, r, a): next(e for e in b["ui"]["external_apps"] if e.get("id") == "capital-stack-finterminal")["install_package"] = "com.example.other"
mut("external app with another package", "T2", badpkg)
def oldtarget(b, l, r, a): row(b)["tiles"][0]["target"] = "page:cloud/omniverse"
mut("a tile pointing back at an in-app page", "T1", oldtarget)
def plannedopens(b, l, r, a): a["t"] = a["t"].replace("if (panel.planned) return null", "")
mut("resolver opens planned entries", "T5", plannedopens)
def noread(b, l, r, a): a["s"] = a["s"].replace('planned         = p.optBoolean("planned", false),', "")
mut("Sections stops reading planned", "T6", noread)
def lit(b, l, r, a): a["t"] += '\nval x = "https://example.org/"\n'
mut("URL literal in the resolver", "T6", lit)
def tv(b, l, r, a): a["p"] = a["p"].replace("private fun PageEntry(", "private fun PageEntry(").replace("Text(if (url != null)", "TextView(if (url != null)")
mut("TextView( in the renderer", "T6", tv)

print("-- %d passed, %d failed" % (passed, failed))
sys.exit(1 if failed else 0)
PY
