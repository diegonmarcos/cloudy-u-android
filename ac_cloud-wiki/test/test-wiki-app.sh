#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ test-wiki-app — Cloud Wiki (#1778), statically                    ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# The shell and the rows are the fleet's, the graph is libs:graph-view and the pages are a runtime dataset; this proves, from the sources alone:
#   W1  the navigation is build.json::ui: NO bottom nav (ui.bottom_nav is empty, nav-shape.json::no_bottom names the app), the default section is `wiki`, a
#       vertical_side_menu with the thirteen topics and the graph in four groups, in order; the ids Decl.kt names are declared (both ways)
#   W2  the dataset data/fleet/app/<package>/wiki.json holds exactly the declared topics (the graph is not a topic), each with at least four pages with unique
#       ids prefixed by the topic, tags, Markdown, and links that resolve; Engineering has each major field; the manifest serves the dataset to this package
#   W3  the shell draws the shared bars (first-row scaffold, side-menu layout, theme-background) and NO bottom nav host; the topic page draws the subpage tabs
#       with NavBarTopSecondRow; the graph page draws libs:graph-view's GraphView; the app links libs:graph-view and libs:fleet-data-client
#   W4  the pages are read at runtime (FleetData.observe), no asset holds them, no sp literal, no numeric fontSize, no colour literal in the app's Kotlin
#   W5  no network: no INTERNET permission, no URL in Kotlin, and Configs open the fleet's General | Perms | About
# then plants a mutation per rule in a scratch copy and requires RED for each.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
command -v python3 >/dev/null || { echo "ERROR: python3 is required"; exit 1; }

check() {   # check <module dir> <fleet dir> -> prints FAIL lines, exit 1 on any
python3 - "$1" "$2" <<'PY'
import json, os, re, sys
app, fleet = sys.argv[1], sys.argv[2]
bad = []
def read(p):
    with open(p, encoding="utf-8") as f: return f.read()
def code(p):
    t = re.sub(r"/\*.*?\*/", "", read(p), flags=re.S)
    return "\n".join(re.sub(r"(?<![:\"])//.*$", "", l) for l in t.splitlines())
bj = json.loads(read(os.path.join(app, "build.json")))
kt = os.path.join(app, "app/src/main/java/com/diegonmarcos/cloudwiki")
decl, shell, pages_kt, store = (code(os.path.join(kt, f)) for f in ("Decl.kt", "ui/WikiShell.kt", "ui/WikiPages.kt", "WikiStore.kt"))
manifest = read(os.path.join(app, "app/src/main/AndroidManifest.xml"))
pkg = bj["android"]["application_id"]
data = json.loads(read(os.path.join(fleet, "app", pkg, "wiki.json")))
fm = json.loads(read(os.path.join(fleet, "manifest.json")))
ui = bj["ui"]
secs = {s["id"]: s for s in ui["sections"]}

# W1
if ui["bottom_nav"] != []: bad.append("W1 ui.bottom_nav must be empty: the app has no bottom nav")
if ui["default_section"] != "wiki": bad.append("W1 the default section must be wiki")
wiki = secs.get("wiki", {})
if wiki.get("nav_mode") != "vertical_side_menu": bad.append("W1 the wiki section is not vertical_side_menu")
GROUPS = [("sciences", ["math", "cs", "physics", "astronomy", "eng", "medicine"]), ("humanities", ["anthropology", "beliefs", "chronos"]),
          ("arts", ["art", "theater", "music", "movies"]), ("explore", ["graph"])]
if [(g["id"], g["pages"]) for g in wiki.get("groups", [])] != GROUPS: bad.append("W1 the wiki groups are not the four declared, in order")
page_ids = [p["id"] for p in wiki.get("pages", [])]
if page_ids != [p for _, ps in GROUPS for p in ps]: bad.append("W1 the wiki pages are not the thirteen topics and the graph, in order")
named = set(re.findall(r'SECTION_\w+\s*=\s*"([^"]+)"', decl))
for x in sorted(named - set(secs)): bad.append("W1 Decl.kt names section %r that build.json::ui does not declare" % x)
for x in sorted(set(secs) - named): bad.append("W1 section %r is declared but Decl.kt has no id for it" % x)
pg = re.search(r'PAGE_GRAPH\s*=\s*"([^"]+)"', decl)
if not pg or pg.group(1) not in page_ids: bad.append("W1 Decl.kt PAGE_GRAPH is not a declared page")

# W2
topics = {t["id"]: t for t in data.get("topics", [])}
want = [p for p in page_ids if p != "graph"]
for x in sorted(set(want) - set(topics)): bad.append("W2 topic %r has no pages in wiki.json" % x)
for x in sorted(set(topics) - set(want)): bad.append("W2 wiki.json holds topic %r, which build.json::ui does not declare" % x)
ids = [p["id"] for t in topics.values() for p in t.get("pages", [])]
if len(ids) != len(set(ids)): bad.append("W2 a page id is used twice")
for tid, t in topics.items():
    if len(t.get("pages", [])) < 4: bad.append("W2 topic %s has fewer than four pages" % tid)
    for p in t.get("pages", []):
        if not p["id"].startswith(tid + "."): bad.append("W2 page %s is not prefixed by its topic %s" % (p["id"], tid))
        if not p.get("tags"): bad.append("W2 page %s has no tags" % p["id"])
        if not 80 <= len(p.get("md", "")) <= 700: bad.append("W2 page %s is not a short explanation" % p["id"])
for tid, t in topics.items():
    for text in [t.get("intro", "")] + [p.get("md", "") for p in t.get("pages", [])]:
        for l in re.findall(r"\[\[([^\]\s]+)\]\]", text):
            if l not in ids: bad.append("W2 a page of %s links to unknown page %s" % (tid, l))
eng = {p["id"].split(".", 1)[1] for p in topics.get("eng", {}).get("pages", [])}
for f in ("civil", "mechanical", "electrical", "chemical", "software", "aerospace", "materials", "biomedical", "environmental"):
    if f not in eng: bad.append("W2 Engineering lacks the %s subpage" % f)
if "wiki" not in fm.get("apps", {}).get(pkg, []): bad.append("W2 manifest.json::apps does not serve the wiki dataset to %s" % pkg)
for dpth, dn, fn in os.walk(os.path.join(app, "app/src")):
    for f in fn:
        if f.endswith(".json"): bad.append("W4 %s is a JSON asset: the pages are a runtime dataset" % f)

# W3
for sym in ("NavBarTopFirstRowScaffold(", "NavSectionLayout(", "themeBackground("):
    if sym not in shell: bad.append("W3 WikiShell does not call %s" % sym)
if "NavBarBottomHost(" in shell or "BottomNavIsland(" in shell: bad.append("W3 WikiShell draws a bottom nav: the app has none")
if "NavBarTopSecondRow(" not in pages_kt: bad.append("W3 WikiPage does not draw the subpage tabs with NavBarTopSecondRow(")
if "GraphView(" not in pages_kt: bad.append("W3 the graph page does not draw libs:graph-view's GraphView(")
if "Decl.nav" not in shell: bad.append("W3 WikiShell does not read Decl.nav")
mods = read(os.path.join(app, "build.json"))
for m in ("libs:ui-kit", "libs:graph-view", "libs:fleet-data-client"):
    if m not in mods: bad.append("W3 build.json modules lack %s" % m)

# W4
if "FleetData.observe(" not in store or "Wiki.DATASET" not in store: bad.append("W4 WikiStore does not read the wiki dataset through FleetData.observe")
for dpth, dn, fn in os.walk(os.path.join(app, "app/src/main")):
    for f in fn:
        if not f.endswith(".kt"): continue
        t = code(os.path.join(dpth, f))
        if re.search(r"\b\d+(\.\d+)?\.sp\b|\bTextUnit\(", t): bad.append("W4 %s spells an sp literal: the type ramp is KitStoreRow's" % f)
        if re.search(r"fontSize\s*=\s*\d", t): bad.append("W4 %s sets a numeric fontSize" % f)
        if re.search(r"\bColor\(0x", t): bad.append("W4 %s spells a colour literal" % f)
        if re.search(r"https?://", t): bad.append("W5 %s spells a URL (no network)" % f)
        if re.search(r"HttpURLConnection|OkHttp|openConnection", t): bad.append("W5 %s opens a connection" % f)

# W5
if "android.permission.INTERNET" in manifest: bad.append("W5 the manifest asks for INTERNET")
if "FleetConfigsEntries.open(" not in shell or "FleetConfigsPage.General" not in shell: bad.append("W5 the gear does not open the fleet's Configs (General | Perms | About)")

for b in bad: print("FAIL " + b)
sys.exit(1 if bad else 0)
PY
}

FAILS=0
ok()  { echo "  PASS: $1"; }
bad() { echo "  FAIL: $1"; FAILS=$((FAILS + 1)); }

FLEET="$APP/../data/fleet"
PKG="com.diegonmarcos.cloudwiki"
echo "[wiki] assembly rules"
out="$(check "$APP" "$FLEET")" && ok "W1-W5 hold on the real tree" || { bad "the real tree is red"; echo "$out"; }

# mutations on a scratch copy: each rule must go red
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
mutant() {   # mutant <name> <shell edit run in the copy (cwd = copy of the app; ../fleet is the copy of the fleet data)> <needle expected in the output>
  rm -rf "$W/app" "$W/fleet"; cp -r "$APP" "$W/app"; mkdir -p "$W/fleet/app"; cp -r "$FLEET/app/$PKG" "$W/fleet/app/"; cp "$FLEET/manifest.json" "$W/fleet/"
  ( cd "$W/app" && eval "$2" )
  if out="$(check "$W/app" "$W/fleet" 2>&1)"; then bad "mutant '$1' stayed green"; elif grep -q -- "$3" <<<"$out"; then ok "mutant '$1' is red ($3)"; else bad "mutant '$1' is red for the wrong reason: $out"; fi
}
KT=app/src/main/java/com/diegonmarcos/cloudwiki
JS="../fleet/app/$PKG/wiki.json"
mutant "a bottom item appears"       "python3 -c \"import json;p='build.json';d=json.load(open(p));d['ui']['bottom_nav'].append('wiki');json.dump(d,open(p,'w'))\"" "W1 ui.bottom_nav must be empty"
mutant "side menu becomes a row"     "sed -i 's/vertical_side_menu/horizontal_one_row/' build.json"               "W1 the wiki section is not vertical_side_menu"
mutant "a group is renamed"          "sed -i 's/\"id\": \"arts\"/\"id\": \"crafts\"/' build.json"                  "W1 the wiki groups are not the four declared"
mutant "a topic loses its pages"     "python3 -c \"import json;p='$JS';d=json.load(open(p));d['topics']=[t for t in d['topics'] if t['id']!='music'];json.dump(d,open(p,'w'))\"" "W2 topic 'music' has no pages"
mutant "data for an unknown topic"   "python3 -c \"import json;p='$JS';d=json.load(open(p));t=dict(d['topics'][0]);t['id']='toys';d['topics'].append(t);json.dump(d,open(p,'w'))\"" "W2 wiki.json holds topic 'toys'"
mutant "a topic keeps three pages"   "python3 -c \"import json;p='$JS';d=json.load(open(p));d['topics'][1]['pages']=d['topics'][1]['pages'][:3];json.dump(d,open(p,'w'))\"" "has fewer than four pages"
mutant "a page loses its tags"       "python3 -c \"import json;p='$JS';d=json.load(open(p));d['topics'][0]['pages'][0]['tags']=[];json.dump(d,open(p,'w'))\"" "has no tags"
mutant "a link dangles"              "sed -i '0,/\\[\\[math.algebra\\]\\]/s//[[math.nothing]]/' $JS"             "links to unknown page math.nothing"
mutant "engineering loses a field"   "python3 -c \"import json;p='$JS';d=json.load(open(p));e=[t for t in d['topics'] if t['id']=='eng'][0];e['pages']=[x for x in e['pages'] if x['id']!='eng.civil'];json.dump(d,open(p,'w'))\"" "Engineering lacks the civil subpage"
mutant "the manifest stops serving"  "python3 -c \"import json;p='../fleet/manifest.json';d=json.load(open(p));d['apps'].pop('$PKG');json.dump(d,open(p,'w'))\"" "manifest.json::apps does not serve the wiki dataset"
mutant "the pages are baked"         "mkdir -p app/src/main/assets && echo '{}' > app/src/main/assets/wiki.json" "W4 wiki.json is a JSON asset"
mutant "shell loses the side menu"   "sed -i 's/NavSectionLayout(/Foo(/' $KT/ui/WikiShell.kt"                      "W3 WikiShell does not call NavSectionLayout("
mutant "a bottom nav host returns"   "echo 'val b = NavBarBottomHost(1)' >> $KT/ui/WikiShell.kt"                   "W3 WikiShell draws a bottom nav"
mutant "tabs drawn by hand"          "sed -i 's/NavBarTopSecondRow(/Foo(/' $KT/ui/WikiPages.kt"                    "W3 WikiPage does not draw the subpage tabs"
mutant "graph drawn by hand"         "sed -i 's/GraphView(/Foo(/' $KT/ui/WikiPages.kt"                             "W3 the graph page does not draw libs:graph-view"
mutant "the graph lib is unlinked"   "sed -i 's/libs:graph-view/libs:other/g' build.json"                          "W3 build.json modules lack libs:graph-view"
mutant "pages no longer observed"    "sed -i 's/FleetData.observe(/Foo(/' $KT/WikiStore.kt"                        "W4 WikiStore does not read the wiki dataset"
mutant "an sp literal creeps in"     "echo 'val x = 12.sp' >> $KT/ui/WikiPages.kt"                                 "W4 WikiPages.kt spells an sp literal"
mutant "a font size number"          "echo 'val y = Foo(fontSize = 13)' >> $KT/ui/WikiPages.kt"                    "W4 WikiPages.kt sets a numeric fontSize"
mutant "a colour literal"            "echo 'val c = Color(0xFF112233)' >> $KT/ui/WikiPages.kt"                     "W4 WikiPages.kt spells a colour literal"
mutant "a URL in Kotlin"             "echo 'val u = \"https://wiki.example/\"' >> $KT/Wiki.kt"                     "W5 Wiki.kt spells a URL"
mutant "network permission added"    "sed -i 's|<queries>|<uses-permission android:name=\"android.permission.INTERNET\" /><queries>|' app/src/main/AndroidManifest.xml" "W5 the manifest asks for INTERNET"
mutant "gear no longer opens Configs" "sed -i 's/FleetConfigsEntries.open(/Foo(/' $KT/ui/WikiShell.kt"             "W5 the gear does not open the fleet's Configs"

[ "$FAILS" -eq 0 ] && echo "ALL GREEN" || { echo "$FAILS FAILED"; exit 1; }
