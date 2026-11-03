#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════╗
# ║ #1422 Favourites: filters from the registries, 8 columns, groups ║
# ║ that collapse                                                    ║
# ╚══════════════════════════════════════════════════════════════════╝
#
#   C1 data/favourites_catalog.json is exactly what tools/gen-favourites-catalog.py derives (never hand-edited, never stale)
#   C2 the filters map to the registries: the chips are build.json's filters in order; GH Pages = data/github_pages.json
#      by repo; Cloudy Public / Private = the SuperApp services files under the SuperApp Cloud dashboard categories;
#      Services / Tools = the SuperApp phone_sections / phone_folders taxonomy (Store > Phone > Apps); Webserver opens
#      the cloud-webserver serving endpoint's Pages tab
#   C3 the grid is GRID_COLUMNS = 8 weighted tiles per row; a header tap toggles the group, a collapsed group draws no
#      tile, the host persists it; the chips are drawn from the baked filters; no back button on the screen
#   C4 BrowserFavouritesTest pins the rules as behaviour
set -eu
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
export ROOT
cd "$(dirname "$0")"
python3 - <<'PYEOF'
import json, os, subprocess, sys
sys.path.insert(0, ".")
import browser_tester as bt
from browser_tester import LIB, APP, kotlin, build_json, main
bt.COPY += ["aa_cloud-superapp/build.json", "aa_cloud-superapp/data/services_public.json", "aa_cloud-superapp/data/services_private.json",
            "aa_cloud-superapp/data/cloud_services.json", "ac_cloud-c3-webserver/build.json", "ac_cloud-c3-webserver/server/src/app.rs"]

def j(root, rel):
    return json.load(open(os.path.join(root, rel), encoding="utf-8"))

def check(root, ok):
    gen = os.path.join(root, "ac_cloud-browser/tools/gen-favourites-catalog.py")
    r = subprocess.run([sys.executable, "-I", gen, "--check"], capture_output=True, text=True)
    ok(r.returncode == 0, "C1 the committed catalog is what the generator derives", r.stderr.strip()[:200])
    # C2 reads what the generator derives NOW (C1 holds the committed file to it)
    import importlib.util
    spec = importlib.util.spec_from_file_location("gen_fav_%d" % id(root), gen)
    g = importlib.util.module_from_spec(spec); spec.loader.exec_module(g)
    try:
        cat = json.loads(g.render())
    except Exception as e:   # a generator that cannot derive is a C2 failure, not a crash
        ok(False, "C2 the generator derives the catalog", repr(e)); return
    fav = build_json(root)["ui"]["browser"]["favourites"]
    labels = [f["label"] for f in fav["filters"]]
    ok(labels == ["GH Pages", "Cloudy Public", "Cloudy Private", "Webserver", "Services", "Tools"] and [f["label"] for f in cat["filters"]] == labels,
       "C2 the chips are GH Pages | Cloudy Public | Cloudy Private | Webserver | Services | Tools, in order")
    items = cat["items"]
    ok(all(i["group"].split("/")[0] in labels and "/" in i["group"] for i in items), "C2 every item sits in '<chip>/<sub-folder>'")
    def grp(label): return [i for i in items if i["group"].split("/")[0] == label]
    pages = j(root, "ac_cloud-browser/data/github_pages.json")
    ok({i["url"] for i in grp("GH Pages")} == {p["url"] for p in pages}
       and all(i["group"] == "GH Pages/" + (p.get("repo") or "") for p in pages for i in grp("GH Pages") if i["url"] == p["url"] and p.get("repo")),
       "C2 GH Pages = the Pages list, one sub-folder per repo")
    cs = j(root, "aa_cloud-superapp/data/cloud_services.json")
    sub = {sg["label"] for g in cs["groups"] if g["id"] in ("infra", "user") for sg in g["subgroups"]} | {"Other"}
    pub = j(root, "aa_cloud-superapp/data/services_public.json")
    ok({i["url"] for i in grp("Cloudy Public")} - {"https://linktree.diegonmarcos.com"} == {"https://" + r["public_url"] for r in pub if r.get("public_url")}
       and all(i["group"].split("/", 1)[1] in sub for i in grp("Cloudy Public") + grp("Cloudy Private")),
       "C2 Cloudy Public / Private = the services files under the Cloud dashboard categories")
    ok(len(grp("Cloudy Private")) > 0 and all(i["url"].startswith("http://") for i in grp("Cloudy Private")), "C2 Cloudy Private = the mesh endpoints")
    ui = j(root, "aa_cloud-superapp/build.json")["ui"]
    for chip in ("Services", "Tools"):
        pre = {s["prefix"] for s in ui["phone_sections"] if s["title"].startswith(chip)}
        want = {f["label"][1:].strip() for f in ui["phone_folders"] if f["label"][:1] in pre and any(k.startswith("pkg:") for k in f["match_keywords"])}
        got = {i["group"].split("/", 1)[1] for i in grp(chip)}
        ok(pre and got == want, "C2 %s = the Phone > Apps folders of the %s sections" % (chip, chip), "%s vs %s" % (sorted(got)[:4], sorted(want)[:4]))
    ws = [f for f in cat["filters"] if f["label"] == "Webserver"]
    eps = [e for e in j(root, "ac_cloud-c3-webserver/build.json")["serving"]["endpoints"] if e["owner"] == "ac_cloud-c3-webserver" and e["port_from"] == "runtime.port"]
    ok(len(ws) == 1 and len(eps) == 1 and ws[0].get("open") == "http://%s/__app__/tab/pages" % eps[0]["name"] and not grp("Webserver"),
       "C2 Webserver opens the cloud-webserver app's Pages tab")
    src = kotlin(root, LIB, APP)
    fav_kt = src.get(LIB + "/BrowserFavourites.kt", ""); sheets = src.get(LIB + "/BrowserSheets.kt", ""); frag = src.get(LIB + "/BrowserHostFragment.kt", "")
    bm = src.get(LIB + "/BrowserBookmarks.kt", "")
    ok("const val GRID_COLUMNS = 8" in fav_kt and "items.chunked(GRID_COLUMNS)" in fav_kt, "C3 eight tiles per row")
    ok("BrowserFavourites.gridRows(g.items).forEach { row ->" in sheets and "Column(Modifier.weight(1f).padding(vertical = 3.dp, horizontal = 1.dp)" in sheets
       and "repeat(BrowserFavourites.GRID_COLUMNS - row.size) { Box(Modifier.weight(1f)) }" in sheets, "C3 each tile is a weighted eighth of the row (fits the screen)")
    ok("if (folder in collapsed) collapsed - folder else collapsed + folder" in fav_kt and "if (c) emptyList() else items" in fav_kt, "C3 a collapsed group draws no tile")
    ok(".clickable { onToggleGroup(g.folder) }" in sheets and "onToggleGroup = { bookmarks.toggleCollapsed(it); rev++ }" in frag
       and "putStringSet(COLLAPSED, BrowserFavourites.toggle(collapsed(), folder))" in bm, "C3 a header tap toggles the group, persisted")
    ok("filters = config.favourites.filters" in frag and 'testTag("browser:fav:chip:${f.id}")' in sheets and "if (o != null) onOpenUrl(o)" in sheets,
       "C3 the chips are drawn from the baked filters")
    screen = sheets[sheets.find("fun BrowserFavScreen("):]
    screen = screen[:screen.find("\n}\n")]
    ok("Back" not in screen and "onClose" not in screen, "C3 no back button on the Favourites screen")
    t = open(os.path.join(root, "ac_cloud-browser/app/src/test/java/com/diegonmarcos/cloudbrowser/BrowserFavouritesTest.kt"), encoding="utf-8").read()
    ok("the grid has eight columns" in t and "a collapsed group keeps its header and draws no item" in t and "legacy seed folders move" in t, "C4 the rules are pinned as behaviour")

FAV = LIB + "/BrowserFavourites.kt"; SH = LIB + "/BrowserSheets.kt"; FRAG = LIB + "/BrowserHostFragment.kt"
GEN = "ac_cloud-browser/tools/gen-favourites-catalog.py"
main("favourites catalog", check, [
    ("a registry changes, the catalog stays", "aa_cloud-superapp/data/services_public.json", '"name": "languagetool"', '"name": "languagetool-renamed"', "C1"),
    ("the catalog is hand-edited", "ac_cloud-browser/data/favourites_catalog.json", '"group": "GH Pages/', '"group": "GH Pages/x', "C1"),
    ("a chip is dropped", "ac_cloud-browser/build.json", '{ "id": "webserver", "label": "Webserver", "from": "webserver_pages" },\n', "", "C2 the chips are"),
    ("Services reads every section", GEN, 'if s["title"].startswith(section)]', ']', "C2 Services"),
    ("GH Pages loses its repos", GEN, 'repo = p.get("repo") or ', 'repo = ', "C2 GH Pages"),
    ("Cloudy loses its categories", GEN, 'or cats.get((r.get("private_dns") or "").split(":")[0]) or "Other"', 'and "Misc" or "Misc"', "C2 Cloudy Public"),
    ("Webserver opens another tab", GEN, 'route = "/__app__/tab/pages"', 'route = "/__app__/tab/home"', "C2 Webserver"),
    ("six columns", FAV, "const val GRID_COLUMNS = 8", "const val GRID_COLUMNS = 6", "C3 eight tiles"),
    ("tiles are fixed width again", SH, "Column(Modifier.weight(1f).padding(vertical = 3.dp, horizontal = 1.dp)", "Column(Modifier.width(76.dp).padding(vertical = 3.dp, horizontal = 1.dp)", "C3 each tile"),
    ("a collapsed group still draws", FAV, "if (c) emptyList() else items", "items", "C3 a collapsed group"),
    ("the toggle never opens", FAV, "if (folder in collapsed) collapsed - folder else collapsed + folder", "collapsed + folder", "C3 a collapsed group"),
    ("the header is not tappable", SH, ".clickable { onToggleGroup(g.folder) }", "", "C3 a header tap"),
    ("the chips are not baked", FRAG, "filters = config.favourites.filters", "filters = emptyList()", "C3 the chips"),
    ("the back button returns", SH, 'Text("Favourites", color = p.textPrimary', 'TextButton({}) { Text("← Back") }\n            Text("Favourites", color = p.textPrimary', "C3 no back"),
])
PYEOF
