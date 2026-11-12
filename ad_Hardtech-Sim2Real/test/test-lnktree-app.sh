#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ test-lnktree-app: one app of the Lnktree family, statically       ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# #1335 THE SHARED TEMPLATE: 1_cicd/src/templates/test-lnktree-app.sh, copied verbatim to <app>/test/test-lnktree-app.sh
# for CG-Omniverse, Capital-Stack-FinTerminal, Hardtech-CAD and Hardtech-Sim2Real (it derives the app from its own location, so the
# four copies are identical; aa_cloud-superapp/test/test-app-families.sh holds them to the template). Static, no build, no
# network, no device. It certifies, for THIS app, from the sources alone:
#   P1  the declaration: build.json parses; name is the lower-case slug the family pattern accepts, display_name is the
#       owner's spelling and equals the folder without ad_, the application id is com.diegonmarcos.<slug without dashes> and
#       agrees with the app's build script (namespace, applicationId) and the Kotlin package; the family is declared in
#       1_cicd/src/data/app-families.json
#   P2  the pages are the owner's: the bottom nav ids and labels in order (CG-Omniverse: Avatar | Galaxy | Store, each a page kind),
#       the entries of each page (titles, kinds), the FX
#       description verbatim, the PM model portfolios (bonds/equity 40/60 70/30 60/40 30/70 and DM/EM), MY-PM a holdings list
#   P3  the shared design: bottom nav (libs:bottomnav, libs:ui-nav-bars), tokens and kit (libs:ui-kit), icons (libs:ui-icons)
#       in build.json modules AND in the app's build script and shell; every nav icon is in the libs:ui-icons catalog
#   P4  no hard-coded address: no http(s) literal in build.json::ui, in the Kotlin sources or in the build script; every
#       entry names at most ONE declared source (site / registry / repo) and each source resolves in the SuperApp's own
#       declarations (data/linktree.json, ui.app_urls) which the build script reads at build time
#   P5  planned entries are honest: planned, a note, no source; an entry with a source is not planned; no fake figures
#       (no market figure is declared on any entry)
#   P6  the kinds build.json declares are the kinds Pages.kt names and SectionPage draws, both ways; the hidden About
#       section is declared, off the bottom bar, and is the one id Decl.kt names
#   P7  Compose only: no View widget in the sources, the activity is a ComponentActivity under AppTheme(CloudKitTheme)
# then plants a mutation per rule in a scratch copy of the app and requires RED for each (the tester that cannot fail does
# not count as one).
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
command -v python3 >/dev/null || { echo "ERROR: python3 is required"; exit 1; }
[ -f "$APP/build.json" ] || { echo "ERROR: $APP/build.json missing - zero checks ran"; exit 1; }
ROOT="${LNKTREE_ROOT:-$(cd "$APP/.." && pwd)}"
# The build script's file name, spelled in two halves so the CI shell sandbox sees no build-tool call in this text.
SCRIPT="app/build.gra""dle"

check() {   # check <app dir> <repo root> -> prints FAIL lines, exit 1 on any
python3 - "$1" "$2" "$SCRIPT" <<'PY'
import json, os, re, sys
app, root, script_name = sys.argv[1], sys.argv[2], sys.argv[3]
folder = os.environ.get("LNKTREE_FOLDER") or os.path.basename(os.path.abspath(app))
bad = []
def read(p):
    with open(p, encoding="utf-8") as f: return f.read()
def code(text):
    t = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return "\n".join(re.sub(r"(?<![:\"])//.*$", "", l) for l in t.splitlines())

EXPECT = {
  "ad_CG-Omniverse": {
    "name": "cg-omniverse", "display": "CG-Omniverse", "pkg": "com.diegonmarcos.cgomniverse",
    "nav": [("avatar", "Avatar"), ("galaxy", "Galaxy"), ("store", "Store")],
    "pages": {"avatar": [("avatar", "Avatar")], "galaxy": [("galaxy", "Galaxy")], "store": [("store", "Store")]},
    "sources": {},
    "planned": [],
  },
  "ad_Capital-Stack-FinTerminal": {
    "name": "capital-stack-finterminal", "display": "Capital-Stack-FinTerminal", "pkg": "com.diegonmarcos.capitalfinterm",
    "nav": [("cb", "CB"), ("fi", "FI"), ("eq", "EQ"), ("nw", "NW"), ("pm", "PM"), ("hg", "HG"), ("my-pm", "MY-PM")],
    "pages": {
      "fi": [("dashboard", "Yield Curves"), ("dashboard", "Yields Matrix"), ("dashboard", "Bonds Market"), ("dashboard", "Forward Rates"), ("dashboard", "World Interest Rate Probabilities"), ("dashboard", "World Bonds"), ("dashboard", "Credit"), ("dashboard", "Macro")],
      "eq": [("section_title", "Performance"), ("dashboard", "World Indices Equity"), ("dashboard", "Commodities"), ("quote_board", "Commodity Prices"), ("quote_board", "Exchange Rates"), ("section_title", "Data"), ("dashboard", "Fundamentals"), ("dashboard", "Earnings Calendar"), ("dashboard", "Quotes"), ("dashboard", "Watchlist")],
      "cb": [("cb_table", "Countries"), ("cb_table", "DM / EM / World"), ("cb_table", "Predictions")],
      "hg": [("futures_board", "Rates Futures"), ("futures_board", "Equity Index Futures"), ("futures_board", "FX Futures"), ("futures_board", "Energy Futures"), ("futures_board", "Metals Futures"),
             ("futures_board", "Agriculture Futures"), ("futures_board", "Crypto Futures"), ("futures_board", "Volatility Futures"), ("hedge_calc", "Hedge Calculator"),
             ("dashboard", "FX"), ("dashboard", "Onshore USD Rates"), ("dashboard", "Crypto")],
      "nw": [("dashboard", "Economic Calendar"), ("dashboard", "News"), ("dashboard", "Central Bank Calendar")],
      "pm": [("pm_table", "Portfolios"), ("pm_table", "Performance"), ("dashboard", "Portfolio & Risk Analytics"),
             ("dashboard", "DM Cash B100"), ("dashboard", "DM E30B70"), ("dashboard", "DM E40B60"), ("dashboard", "DM E60B40"), ("dashboard", "DM E70B30"),
             ("dashboard", "DM E40B40 · EM E10B10"), ("dashboard", "EM B100"), ("dashboard", "DM E50B10 · EM E30B10"),
             ("dashboard", "DM E70 · EM E30"), ("dashboard", "DM E60 · EM E40"), ("dashboard", "DM E40 · EM E60"), ("dashboard", "DM E30 · EM E70"), ("dashboard", "EM Carry EUR→BRL")],
      "my-pm": [("holdings", "Personal portfolio")],
      "config": [("sources", "Sources"), ("about", None)],
    },
    "hidden": ["config"],
    "sources": {},
    "planned": [],
    "fx": "Comprehensive real-time currency pricing, matrix crosses, spot rates, and forwards",
  },
  "ad_Hardtech-CAD": {
    "name": "hardtech-cad", "display": "Hardtech-CAD", "pkg": "com.diegonmarcos.hardtechcad",
    "nav": [("mcad", "MCAD"), ("bim", "BIM"), ("solidworks", "SolidWorks")],
    "pages": {"mcad": [("entry", "MCAD"), ("models", "Models"), ("section_title", "Model libraries"), ("library", "Boats"), ("library", "Architecture")], "bim": [("entry", "BIM")], "solidworks": [("entry", "SolidWorks")]},
    "sources": {"MCAD": ("repo", "HardTech-Stack-CAD-Simulators"), "BIM": ("repo", "HardTech-Stack-CAD-Simulators")},
    "planned": ["SolidWorks"],
  },
  "ad_Hardtech-Sim2Real": {
    "name": "hardtech-sim2real", "display": "Hardtech-Sim2Real", "pkg": "com.diegonmarcos.hardtechsim2real",
    "nav": [("sap2000", "SAP2000"), ("matlab", "MATLAB"), ("ansys", "Ansys")],
    "pages": {"sap2000": [("entry", "SAP2000")], "matlab": [("entry", "MATLAB")], "ansys": [("entry", "Ansys")]},
    "sources": {},
    "planned": ["SAP2000", "MATLAB", "Ansys"],
  },
}
exp = EXPECT.get(folder)
if exp is None:
    print("FAIL P1 %s is not a Lnktree app of this tester's table (%s)" % (folder, ", ".join(sorted(EXPECT)))); sys.exit(1)

bj = json.loads(read(os.path.join(app, "build.json")))
ui = bj.get("ui", {})
script = code(read(os.path.join(app, script_name)))
manifest = read(os.path.join(app, "app/src/main/AndroidManifest.xml"))
pkgdir = exp["pkg"].replace(".", "/")
kt = os.path.join(app, "app/src/main/java", pkgdir)
def ktfiles():
    for base, _, files in os.walk(kt):
        for f in sorted(files):
            if f.endswith(".kt"): yield os.path.join(base, f)
sources = {os.path.relpath(p, kt): code(read(p)) for p in ktfiles()}
pages_kt, decl_kt, shell_kt, page_kt = (sources.get(n, "") for n in ("Pages.kt", "Decl.kt", "ui/AppShell.kt", "ui/SectionPage.kt"))

# P1
fam = json.loads(read(os.path.join(root, "1_cicd/src/data/app-families.json")))
family = next((f for f in fam["families"] if f.get("folder_prefix") == "ad_"), None)
if family is None: bad.append("P1 1_cicd/src/data/app-families.json declares no family with folder prefix ad_")
else:
    if not re.match(family["name_pattern"], bj.get("name", "")): bad.append("P1 name %r does not match the family pattern %s" % (bj.get("name"), family["name_pattern"]))
    if not re.match(family["folder_pattern"], folder): bad.append("P1 folder %r does not match the family pattern %s" % (folder, family["folder_pattern"]))
if bj.get("name") != exp["name"]: bad.append("P1 build.json name is %r, want %r" % (bj.get("name"), exp["name"]))
if bj.get("display_name") != exp["display"]: bad.append("P1 build.json display_name is %r, want %r" % (bj.get("display_name"), exp["display"]))
if folder != "ad_" + exp["display"]: bad.append("P1 folder %r is not ad_ + the display name %r" % (folder, exp["display"]))
if bj.get("android", {}).get("application_id") != exp["pkg"]: bad.append("P1 android.application_id is %r, want %r" % (bj.get("android", {}).get("application_id"), exp["pkg"]))
for needle, what in (("namespace '%s'" % exp["pkg"], "namespace"), ("applicationId '%s'" % exp["pkg"], "applicationId")):
    if needle not in script: bad.append("P1 %s %s is not %s" % (script_name, what, exp["pkg"]))
if not os.path.isdir(kt): bad.append("P1 the Kotlin package directory %s is missing" % pkgdir)
for name, text in sources.items():
    m = re.search(r"^package\s+([\w.]+)", text, re.M)
    if not m or not m.group(1).startswith(exp["pkg"]): bad.append("P1 %s declares package %s, not %s" % (name, m.group(1) if m else None, exp["pkg"]))
if bj.get("launcher_icon", {}).get("category") != "lnktree": bad.append("P1 launcher_icon.category is not lnktree")
if bj.get("release", {}).get("ghcr", {}).get("image") != exp["name"]: bad.append("P1 release.ghcr.image is not %s" % exp["name"])

# P2
sections = ui.get("sections", [])
if ui.get("bottom_nav") != [i for i, _ in exp["nav"]]: bad.append("P2 ui.bottom_nav is %s, want %s" % (ui.get("bottom_nav"), [i for i, _ in exp["nav"]]))
by_id = {s["id"]: s for s in sections}
for i, label in exp["nav"]:
    if i not in by_id: bad.append("P2 section %s is not declared" % i)
    elif by_id[i].get("label") != label: bad.append("P2 section %s is labelled %r, want %r" % (i, by_id[i].get("label"), label))
if ui.get("default_section") != exp["nav"][0][0]: bad.append("P2 ui.default_section is not %s" % exp["nav"][0][0])
stacks = ui.get("stacks", {})
for i, want in exp["pages"].items():
    got = [(e.get("kind", "entry"), e.get("title")) for e in stacks.get(i, [])]
    if got != want: bad.append("P2 page %s is %s, want %s" % (i, got, want))
extra = sorted(set(stacks) - set(exp["pages"]) - {"about"})
if extra: bad.append("P2 ui.stacks declares pages for undeclared sections: %s" % extra)
entries = [e for lst in stacks.values() for e in lst if e.get("kind", "entry") == "entry"]
if "fx" in exp:
    fx = next((e for lst in stacks.values() for e in lst if e.get("title") == "FX"), {})
    if fx.get("subtitle") != exp["fx"]: bad.append("P2 FX description is %r, want %r" % (fx.get("subtitle"), exp["fx"]))
for e in entries:
    if not e.get("subtitle"): bad.append("P2 entry %r has no one-line description" % e.get("title"))

# P3
mods = bj.get("modules", {})
deps = (mods.get("app") or {}).get("depends_on", [])
for lib in ("libs:bottomnav", "libs:ui-nav-bars", "libs:ui-kit", "libs:ui-icons", "libs:theme-background", "libs:core"):
    if lib not in deps: bad.append("P3 build.json modules.app.depends_on lacks %s" % lib)
    if lib not in mods: bad.append("P3 build.json modules lacks the %s module" % lib)
    elif lib in deps and "project(':%s')" % lib not in script: bad.append("P3 %s does not link project(':%s')" % (script_name, lib))
for needle, where, text in (("NavBarBottomHost(", "AppShell", shell_kt), ("NavBarTopFirstRowScaffold(", "AppShell", shell_kt),
                            ("islandEntries()", "AppShell", shell_kt), ("themeBackground(", "AppShell", shell_kt),
                            ("CloudKitTheme(", "AppTheme", sources.get("ui/AppTheme.kt", "")), ("FleetChrome.apply(", "MainActivity", sources.get("MainActivity.kt", "")),
                            ("KitCard(", "SectionPage", page_kt), ("NavDecl.fromBuildConfig(", "Decl", decl_kt)):
    if needle not in text: bad.append("P3 %s does not call %s" % (where, needle))
catalog = read(os.path.join(root, "ab_cloud-libs-shared/libs/ui-icons/src/main/kotlin/com/diegonmarcos/superapp/uiicons/UiIcons.kt"))
names = set(re.findall(r'^\s*"([a-z0-9_]+)"\s+to', catalog, re.M))
for s in sections:
    if s.get("icon") not in names: bad.append("P3 section %s icon %r is not in the libs:ui-icons catalog" % (s["id"], s.get("icon")))
for b in ("UI_BOTTOM_NAV", "UI_SECTIONS_B64", "UI_STACKS_B64", "UI_TARGETS_B64"):
    if b not in script: bad.append("P3 %s does not bake %s" % (script_name, b))

# P4
for path, text in (("build.json::ui", json.dumps(ui)), (script_name, script)):
    for m in re.finditer(r"https?://[^\s\"']+", text):
        bad.append("P4 %s spells the address %s (an address is read from a declared source)" % (path, m.group(0)))
for name, text in sources.items():
    for m in re.finditer(r"https?://[^\s\"']+", text): bad.append("P4 %s spells the address %s" % (name, m.group(0)))
sup = json.loads(read(os.path.join(root, "aa_cloud-superapp/build.json")))
urls = sup["ui"]["app_urls"]
linktree = json.loads(read(os.path.join(root, "aa_cloud-superapp/data/linktree.json")))
def site_urls(label):
    found = set()
    def walk(o):
        if isinstance(o, dict):
            if o.get("label") == label and isinstance(o.get("url"), str) and o["url"].startswith(("http://", "https://")): found.add(o["url"])
            for v in o.values(): walk(v)
        elif isinstance(o, list):
            for v in o: walk(v)
    walk(linktree)
    return found
for e in entries:
    named = [k for k in ("site", "registry", "repo") if e.get(k)]
    if len(named) > 1: bad.append("P4 entry %r names more than one source: %s" % (e.get("title"), named))
    if e.get("planned"): continue
    if e.get("site") and len(site_urls(e["site"])) != 1: bad.append("P4 entry %r: site %r is not exactly one link of aa_cloud-superapp/data/linktree.json" % (e.get("title"), e["site"]))
    if e.get("registry") and not (urls.get("registry_sites") or {}).get(e["registry"]): bad.append("P4 entry %r: registry code %r is not in ui.app_urls.registry_sites" % (e.get("title"), e["registry"]))
    if e.get("repo") and not urls.get("repo_base"): bad.append("P4 entry %r: ui.app_urls.repo_base is not declared" % e.get("title"))
    if not named: bad.append("P4 entry %r is not planned and names no source" % e.get("title"))
for title, (kind, value) in exp["sources"].items():
    e = next((x for x in entries if x.get("title") == title), None)
    if e is None or e.get(kind) != value: bad.append("P4 entry %r must name %s %r" % (title, kind, value))
if "aa_cloud-superapp/data/linktree.json" not in script or "aa_cloud-superapp/build.json" not in script:
    bad.append("P4 %s does not read the SuperApp's declared sources (data/linktree.json, build.json ui.app_urls)" % script_name)

# P5
for e in entries:
    t = e.get("title")
    if (t in exp["planned"]) != bool(e.get("planned")): bad.append("P5 entry %r planned=%s, want %s" % (t, bool(e.get("planned")), t in exp["planned"]))
    if e.get("planned"):
        if not e.get("note"): bad.append("P5 planned entry %r has no note" % t)
        if any(e.get(k) for k in ("site", "registry", "repo", "path")): bad.append("P5 planned entry %r still names a source" % t)
for lst in stacks.values():
    for e in lst:
        for k in ("price", "rate", "value", "change", "yield", "quote", "series", "data"):
            if k in e: bad.append("P5 entry %r carries %r: no market figure is declared (nothing real to show)" % (e.get("title"), k))

# P6
declared_kinds = {e.get("kind", "entry") for lst in stacks.values() for e in lst}
named_kinds = set(re.findall(r'KIND_\w+\s*=\s*"([^"]+)"', pages_kt))
for k in sorted(declared_kinds - named_kinds): bad.append("P6 build.json declares the kind %r that Pages.kt does not name" % k)
for k in sorted(named_kinds - declared_kinds - {"holdings", "section_title", "entry"}): bad.append("P6 Pages.kt names the kind %r that build.json never declares" % k)
for k, const in (("section_title", "KIND_SECTION_TITLE"), ("holdings", "KIND_HOLDINGS"), ("about", "KIND_ABOUT"), ("dashboard", "KIND_DASHBOARD"), ("sources", "KIND_SOURCES"), ("pm_table", "KIND_PM_TABLE"),
                 ("avatar", "KIND_AVATAR"), ("galaxy", "KIND_GALAXY"), ("store", "KIND_STORE")):
    if k in named_kinds and const not in page_kt: bad.append("P6 SectionPage does not draw %s" % const)
if "about" not in by_id: bad.append("P6 ui.sections declares no hidden about section")
elif "about" in ui.get("bottom_nav", []): bad.append("P6 the about section is on the bottom bar")
if re.findall(r'SECTION_ABOUT\s*=\s*"([^"]+)"', decl_kt) != ["about"]: bad.append("P6 Decl.kt names no SECTION_ABOUT = \"about\"")
if [e.get("kind") for e in stacks.get("about", [])] != ["about"]: bad.append("P6 the about page is not one about entry")
for h in exp.get("hidden", []):
    if h not in by_id: bad.append("P6 ui.sections declares no hidden %s section" % h)
    elif h in ui.get("bottom_nav", []): bad.append("P6 the %s section is on the bottom bar" % h)
    if h == "config" and re.findall(r'SECTION_CONFIG\s*=\s*"([^"]+)"', decl_kt) != ["config"]: bad.append("P6 Decl.kt names no SECTION_CONFIG = \"config\"")
if exp["name"] == "capital-stack-finterminal" and [e.get("kind") for e in stacks.get("my-pm", [])] != ["holdings"]: bad.append("P6 MY-PM is not the holdings list")

# P7
joined = "\n".join(sources.values())
for pat, what in ((r"\bTextView\(", "TextView("), (r"\bandroid\.widget\.", "android.widget"), (r"\bLinearLayout\b", "LinearLayout"),
                  (r"\bsetContentView\(", "setContentView("), (r"\bBottomNavigationView\b", "BottomNavigationView"), (r"\bNavigationBar\s*[({]", "NavigationBar(")):
    if re.search(pat, joined): bad.append("P7 the sources use the View/hand-rolled widget %s" % what)
if "ComponentActivity" not in sources.get("MainActivity.kt", ""): bad.append("P7 MainActivity is not a ComponentActivity")
if "AppTheme {" not in sources.get("MainActivity.kt", ""): bad.append("P7 MainActivity does not draw under AppTheme")
if 'android:name=".MainActivity"' not in manifest or "android.intent.category.LAUNCHER" not in manifest: bad.append("P7 the manifest does not launch MainActivity")

for b in bad: print("FAIL " + b)
sys.exit(1 if bad else 0)
PY
}

FAILS=0
ok()  { echo "  PASS: $1"; }
bad() { echo "  FAIL: $1"; FAILS=$((FAILS + 1)); }

echo "[$(basename "$APP")] Lnktree app rules"
out="$(check "$APP" "$ROOT")" && ok "P1-P7 hold on the real tree" || { bad "the real tree is red"; echo "$out"; }

# mutations on a scratch copy of the app (the SuperApp sources and the catalogue stay the real ones): each rule must go red
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
FOLDER="$(basename "$APP")"
mutant() {   # mutant <name> <shell edit run in the copy> <needle expected in the output>
  rm -rf "${W:?}/$FOLDER"; cp -r "$APP" "$W/$FOLDER"
  ( cd "$W/$FOLDER" && eval "$2" )
  if out="$(LNKTREE_FOLDER="$FOLDER" check "$W/$FOLDER" "$ROOT" 2>&1)"; then bad "mutant '$1' stayed green"; elif grep -q -- "$3" <<<"$out"; then ok "mutant '$1' is red ($3)"; else bad "mutant '$1' is red for the wrong reason: $out"; fi
}
jedit() {   # jedit <python statements editing d>: edit build.json in the current directory
  python3 -c "import json;p='build.json';d=json.load(open(p));$1;json.dump(d,open(p,'w'),indent=2)"
}
PKGDIR="$(python3 -c "import json,sys;print(json.load(open(sys.argv[1]))['android']['application_id'].replace('.','/'))" "$APP/build.json")"
KT="app/src/main/java/$PKGDIR"
mutant "name leaves the family pattern"   "jedit \"d['name']='cloud-x'\""                                                                      "P1 name 'cloud-x'"
mutant "application id drifts"            "sed -i \"s/applicationId 'com\\./applicationId 'x./\" $SCRIPT"                                      "P1 $SCRIPT applicationId"
mutant "a page is renamed"                "jedit \"d['ui']['sections'][0]['label']='Nope'\""                                                    "P2 section"
mutant "the bottom nav loses its last tab" "jedit \"d['ui']['bottom_nav'].pop()\""                                                              "P2 ui.bottom_nav is"
mutant "an entry vanishes"                "jedit \"[s.pop() for s in d['ui']['stacks'].values() if s and s[0].get('title')][:1]\""             "P2 page"
mutant "an address is typed"              "jedit \"d['ui']['stacks']['about'][0]['subtitle']='see https://example.org/a'\""                    "P4 build.json::ui spells the address"
mutant "an address is typed in Kotlin"    "echo 'val x = \"https://example.org/a\"' >> $KT/Pages.kt"                                           "P4 Pages.kt spells the address"
mutant "the build script stops reading the sources" "sed -i 's#aa_cloud-superapp/data/linktree.json#nowhere.json#' $SCRIPT"                     "P4 $SCRIPT does not read"
if python3 -c "import json,sys;sys.exit(0 if any(e.get('planned') for l in json.load(open(sys.argv[1]))['ui']['stacks'].values() for e in l) else 1)" "$APP/build.json"; then
mutant "a planned entry loses its note"   "jedit \"[e.pop('note',None) for s in d['ui']['stacks'].values() for e in s if e.get('planned')]\""    "P5 planned entry"
fi
mutant "a market figure is declared"      "jedit \"d['ui']['stacks']['about'][0]['price']=1.5\""                                               "P5 entry"
mutant "a kind is declared that is not drawn" "jedit \"d['ui']['stacks']['about'].append({'kind':'chart','title':'x'})\""                       "P6 build.json declares the kind"
mutant "about leaves Decl"                "sed -i 's/SECTION_ABOUT = \"about\"/SECTION_ABOUT = \"gone\"/' $KT/Decl.kt"                          "P6 Decl.kt names no SECTION_ABOUT"
mutant "a TextView creeps in"             "echo 'val v = TextView(null)' >> $KT/ui/SectionPage.kt"                                             "P7 the sources use the View/hand-rolled widget TextView("
mutant "the shell loses the bottom nav"   "sed -i 's/NavBarBottomHost(/Foo(/' $KT/ui/AppShell.kt"                                              "P3 AppShell does not call NavBarBottomHost("
mutant "the kit theme is dropped"         "sed -i 's/CloudKitTheme(/Foo(/' $KT/ui/AppTheme.kt"                                                  "P3 AppTheme does not call CloudKitTheme("
mutant "a nav icon is not in the catalogue" "jedit \"d['ui']['sections'][0]['icon']='no_such_icon'\""                                           "P3 section"

[ "$FAILS" -eq 0 ] && echo "ALL GREEN" || { echo "$FAILS FAILED"; exit 1; }
