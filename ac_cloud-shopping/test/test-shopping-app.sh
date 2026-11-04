#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ test-shopping-app — Cloud Shopping (#1473), statically           ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# The shell is the fleet's and the rows are the ui-kit's; this proves, from the sources alone, that the app only ASSEMBLES them from its data:
#   S1  the navigation is build.json::ui: ONE bottom item (search), the default section is the shop section, the shop section is a vertical_side_menu with exactly
#       the owner's five groups and ten pages, in order; the ids Decl.kt names are declared (both ways)
#   S2  every page has data: assets/shop/shop.json::pages holds exactly the declared page ids, each with at least two filter tabs (the first unfiltered, ids unique,
#       icons in the ui-icons catalog) and, for a list page, at least five mock items with unique ids; the Feed page is the one `feed` page; the Search tabs exist
#   S3  the shell draws the shared bars (first-row scaffold, bottom nav host, side-menu layout, top strip for the tabs, theme-background) and blanks the one bottom label from the data
#   S4  the rows are ui-kit KitStoreRow in the shared type ramp: no sp literal, no fontSize number, no colour literal in the app's Kotlin
#   S5  no network: no INTERNET permission, no URL in Kotlin, and Configs open the fleet's General | Perms | About
# then plants a mutation per rule in a scratch copy and requires RED for each.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
command -v python3 >/dev/null || { echo "ERROR: python3 is required"; exit 1; }

check() {   # check <module dir> -> prints FAIL lines, exit 1 on any
python3 - "$1" <<'PY'
import json, os, re, sys
app = sys.argv[1]
bad = []
def read(p):
    with open(p, encoding="utf-8") as f: return f.read()
def code(p):
    t = re.sub(r"/\*.*?\*/", "", read(p), flags=re.S)
    return "\n".join(re.sub(r"(?<![:\"])//.*$", "", l) for l in t.splitlines())
bj = json.loads(read(os.path.join(app, "build.json")))
kt = os.path.join(app, "app/src/main/java/com/diegonmarcos/cloudshopping")
decl, shell, pages_kt, store = (code(os.path.join(kt, f)) for f in ("Decl.kt", "ui/ShopShell.kt", "ui/ShopPages.kt", "ShopStore.kt"))
manifest = read(os.path.join(app, "app/src/main/AndroidManifest.xml"))
data = json.loads(read(os.path.join(app, "app/src/main/assets/shop/shop.json")))
ui = bj["ui"]
secs = {s["id"]: s for s in ui["sections"]}

# S1
if ui["bottom_nav"] != ["search"]: bad.append("S1 the bottom nav must be exactly one item, search")
if ui["default_section"] != "shop": bad.append("S1 the default section must be shop")
shop = secs.get("shop", {})
if shop.get("nav_mode") != "vertical_side_menu": bad.append("S1 the shop section is not vertical_side_menu")
OWNER = [("feed", ["home"]), ("things", ["groceries", "electronics", "clothing"]), ("services", ["transport", "hotels"]),
         ("assets", ["real_estate", "business", "jobs"]), ("entertainment", ["events"])]
if [(g["id"], g["pages"]) for g in shop.get("groups", [])] != OWNER: bad.append("S1 the shop groups are not the owner's five, in order")
page_ids = [p["id"] for p in shop.get("pages", [])]
if page_ids != [p for _, ps in OWNER for p in ps]: bad.append("S1 the shop pages are not the owner's ten, in order")
if secs.get("search", {}).get("pages"): bad.append("S1 the search section must have no pages")
named = set(re.findall(r'SECTION_\w+\s*=\s*"([^"]+)"', decl))
for x in sorted(named - set(secs)): bad.append("S1 Decl.kt names section %r that build.json::ui does not declare" % x)
for x in sorted(set(secs) - named): bad.append("S1 section %r is declared but Decl.kt has no id for it" % x)

# S2
cat = read(os.path.join(app, "..", "ab_cloud-libs-shared/libs/ui-icons/src/main/kotlin/com/diegonmarcos/superapp/uiicons/UiIcons.kt"))
icons = set(re.findall(r'^\s+"([a-z_0-9]+)" to ', cat, re.M))
dp = data.get("pages", {})
for x in sorted(set(page_ids) - set(dp)): bad.append("S2 page %r has no data in shop.json" % x)
for x in sorted(set(dp) - set(page_ids)): bad.append("S2 shop.json holds data for %r, which build.json::ui does not declare" % x)
feeds = [k for k, v in dp.items() if v.get("kind") == "feed"]
if feeds != ["home"]: bad.append("S2 exactly the home page is the feed, found %s" % feeds)
def tabs_ok(name, tabs):
    ids = [t.get("id") for t in tabs]
    if len(tabs) < 2: bad.append("S2 %s has fewer than two filter tabs" % name)
    if len(ids) != len(set(ids)): bad.append("S2 %s repeats a tab id" % name)
    if tabs and any(k in tabs[0] for k in ("tag", "group", "deal", "min_discount")): bad.append("S2 the first tab of %s filters (it must keep everything)" % name)
    for t in tabs:
        if t.get("icon") not in icons: bad.append("S2 %s tab %r icon %r is not in the ui-icons catalog" % (name, t.get("id"), t.get("icon")))
seen = set()
for k, v in dp.items():
    tabs_ok(k, v.get("tabs", []))
    if v.get("kind") == "list":
        if len(v.get("items", [])) < 5: bad.append("S2 %s has fewer than five mock items" % k)
        for i in v.get("items", []):
            if i["id"] in seen: bad.append("S2 item id %r is used twice" % i["id"])
            seen.add(i["id"])
    elif v.get("items"): bad.append("S2 the feed page %s holds items of its own" % k)
tabs_ok("search", data.get("search", {}).get("tabs", []))
if data.get("chrome", {}).get("bottom_labels") is not False: bad.append("S2 chrome.bottom_labels must be false (the search item has no label)")

# S3
for sym in ("NavBarTopFirstRowScaffold(", "NavBarBottomHost(", "NavSectionLayout(", "themeBackground("):
    if sym not in shell: bad.append("S3 ShopShell does not call %s" % sym)
if "NavBarTopSecondRow(" not in pages_kt: bad.append("S3 ShopPage does not draw the filter tabs with NavBarTopSecondRow(")
if "bottomLabels" not in shell or 'label = ""' not in shell: bad.append("S3 ShopShell does not blank the bottom label from shop.json")
if "nav.section(" not in shell and "Decl.nav" not in shell: bad.append("S3 ShopShell does not read Decl.nav")
for p, name in ((os.path.join(app, "build.json"), "build.json"),):
    if "libs:ui-kit" not in read(p): bad.append("S3 build.json modules lack libs:ui-kit")

# S4
for sym in ("KitStoreRowLine(", "KitStoreName(", "KitStoreCaption(", "KitStoreDot("):
    if sym not in pages_kt: bad.append("S4 ShopPages does not draw the row with %s" % sym)
for dpth, dn, fn in os.walk(os.path.join(app, "app/src/main")):
    for f in fn:
        if not f.endswith(".kt"): continue
        t = code(os.path.join(dpth, f))
        if re.search(r"\b\d+(\.\d+)?\.sp\b|\bTextUnit\(", t): bad.append("S4 %s spells an sp literal: the type ramp is KitStoreRow's" % f)
        if re.search(r"fontSize\s*=\s*\d", t): bad.append("S4 %s sets a numeric fontSize" % f)
        if re.search(r"\bColor\(0x", t): bad.append("S4 %s spells a colour literal" % f)
        if re.search(r"https?://", t): bad.append("S4 %s spells a URL (no network)" % f)
        if re.search(r"HttpURLConnection|OkHttp|openConnection", t): bad.append("S5 %s opens a connection" % f)

# S5
if "android.permission.INTERNET" in manifest: bad.append("S5 the manifest asks for INTERNET: the data is mock data")
if "FleetConfigsEntries.open(" not in shell or "FleetConfigsPage.General" not in shell: bad.append("S5 the gear does not open the fleet's Configs (General | Perms | About)")
if "ASSET" not in store or "shop/shop.json" not in store: bad.append("S5 ShopStore does not read assets/shop/shop.json")

for b in bad: print("FAIL " + b)
sys.exit(1 if bad else 0)
PY
}

FAILS=0
ok()  { echo "  PASS: $1"; }
bad() { echo "  FAIL: $1"; FAILS=$((FAILS + 1)); }

echo "[shopping] assembly rules"
out="$(check "$APP")" && ok "S1-S5 hold on the real tree" || { bad "the real tree is red"; echo "$out"; }

# mutations on a scratch copy: each rule must go red (the copy sits next to the lib dir the icon check reads)
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
mkdir -p "$W/ab_cloud-libs-shared/libs/ui-icons"; cp -r "$APP/../ab_cloud-libs-shared/libs/ui-icons/src" "$W/ab_cloud-libs-shared/libs/ui-icons/"
mutant() {   # mutant <name> <shell edit run in the copy> <needle expected in the output>
  rm -rf "$W/app"; cp -r "$APP" "$W/app"
  ( cd "$W/app" && eval "$2" )
  if out="$(check "$W/app" 2>&1)"; then bad "mutant '$1' stayed green"; elif grep -q -- "$3" <<<"$out"; then ok "mutant '$1' is red ($3)"; else bad "mutant '$1' is red for the wrong reason: $out"; fi
}
KT=app/src/main/java/com/diegonmarcos/cloudshopping
JS=app/src/main/assets/shop/shop.json
mutant "a second bottom item"       "python3 -c \"import json;p='build.json';d=json.load(open(p));d['ui']['bottom_nav'].append('shop');json.dump(d,open(p,'w'))\"" "S1 the bottom nav must be exactly one item"
mutant "side menu becomes a row"    "sed -i 's/vertical_side_menu/horizontal_one_row/' build.json"            "S1 the shop section is not vertical_side_menu"
mutant "a group is renamed"         "sed -i 's/\"id\": \"assets\"/\"id\": \"stuff\"/' build.json"            "S1 the shop groups are not the owner's five"
mutant "a page loses its data"      "python3 -c \"import json;p='$JS';d=json.load(open(p));del d['pages']['jobs'];json.dump(d,open(p,'w'))\"" "S2 page 'jobs' has no data"
mutant "data for an unknown page"   "python3 -c \"import json;p='$JS';d=json.load(open(p));d['pages']['toys']=d['pages']['jobs'];json.dump(d,open(p,'w'))\"" "S2 shop.json holds data for 'toys'"
mutant "a page keeps one tab"       "python3 -c \"import json;p='$JS';d=json.load(open(p));d['pages']['events']['tabs']=d['pages']['events']['tabs'][:1];json.dump(d,open(p,'w'))\"" "S2 events has fewer than two filter tabs"
mutant "a tab icon is unknown"      "sed -i '0,/\"icon\": \"bolt\"/s//\"icon\": \"nope\"/' $JS"             "is not in the ui-icons catalog"
mutant "the first tab filters"      "python3 -c \"import json;p='$JS';d=json.load(open(p));d['pages']['hotels']['tabs'][0]['tag']='city';json.dump(d,open(p,'w'))\"" "S2 the first tab of hotels filters"
mutant "bottom label comes back"    "sed -i 's/\"bottom_labels\": false/\"bottom_labels\": true/' $JS"       "S2 chrome.bottom_labels must be false"
mutant "shell loses the side menu"  "sed -i 's/NavSectionLayout(/Foo(/' $KT/ui/ShopShell.kt"                 "S3 ShopShell does not call NavSectionLayout("
mutant "tabs drawn by hand"         "sed -i 's/NavBarTopSecondRow(/Foo(/' $KT/ui/ShopPages.kt"               "S3 ShopPage does not draw the filter tabs"
mutant "label no longer blanked"    "sed -i 's/label = \"\"/label = it.label/' $KT/ui/ShopShell.kt"          "S3 ShopShell does not blank the bottom label"
mutant "row drawn by hand"          "sed -i 's/KitStoreRowLine(/Row(/' $KT/ui/ShopPages.kt"                  "S4 ShopPages does not draw the row with KitStoreRowLine("
mutant "an sp literal creeps in"    "echo 'val x = 12.sp' >> $KT/ui/ShopPages.kt"                            "S4 ShopPages.kt spells an sp literal"
mutant "a font size number"         "echo 'val y = Foo(fontSize = 13)' >> $KT/ui/ShopPages.kt"               "S4 ShopPages.kt sets a numeric fontSize"
mutant "a colour literal"           "echo 'val c = Color(0xFF112233)' >> $KT/ui/ShopPages.kt"                "S4 ShopPages.kt spells a colour literal"
mutant "a URL in Kotlin"            "echo 'val u = \"https://shop.example/\"' >> $KT/Shop.kt"                "S4 Shop.kt spells a URL"
mutant "network permission added"   "sed -i 's|<queries>|<uses-permission android:name=\"android.permission.INTERNET\" /><queries>|' app/src/main/AndroidManifest.xml" "S5 the manifest asks for INTERNET"
mutant "gear no longer opens Configs" "sed -i 's/FleetConfigsEntries.open(/Foo(/' $KT/ui/ShopShell.kt"       "S5 the gear does not open the fleet's Configs"

[ "$FAILS" -eq 0 ] && echo "ALL GREEN" || { echo "$FAILS FAILED"; exit 1; }
