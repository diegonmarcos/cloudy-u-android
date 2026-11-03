#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════╗
# ║ Fav's "GitHub Pages" section: derived, committed, held           ║
# ╚══════════════════════════════════════════════════════════════════╝
#
#   G1 data/github_pages.json parses, is a list of {name,url}, every url is https on diegonmarcos.github.io,
#      and there are no duplicate urls or names (the seed's merge keys on the url, so a duplicate is a lost row).
#   G2 the list is DERIVED, not typed: tools/gen-github-pages.py turns the front project's published list
#      (front-topology.json) into it; a fixture proves it rejects duplicates and skips unpublished projects.
#   G3 build.json wires it: a "GitHub Pages" source on favourites.sources, the seed version >= 2 (so people who
#      already ran version 1 receive it through the once-per-entry merge), and no link is typed into `extra`.
#   G4 the browser still searches Fav from the address bar (the Favorites section) and filters on the Fav page.
set -eu
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
export ROOT
cd "$(dirname "$0")"
python3 - <<'PYEOF'
import importlib.util, json, os, sys
sys.path.insert(0, ".")
from browser_tester import LIB, APP, kotlin, build_json, main

HOST = "https://diegonmarcos.github.io/"

def gen(root):
    spec = importlib.util.spec_from_file_location("gen_github_pages", os.path.join(root, "ac_cloud-browser/tools/gen-github-pages.py"))
    m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
    return m

def check(root, ok):
    p = os.path.join(root, "ac_cloud-browser/data/github_pages.json")
    try:
        rows = json.load(open(p, encoding="utf-8"))
        parses = True
    except Exception as e:
        rows, parses = [], False
    ok(parses, "G1 data/github_pages.json parses")
    ok(isinstance(rows, list) and len(rows) >= 10, "G1 it is a list of sites", "found %s" % (len(rows) if isinstance(rows, list) else type(rows)))
    rows = rows if isinstance(rows, list) else []
    ok(all(isinstance(r, dict) and {"name", "url"} <= set(r) <= {"name", "url", "repo"} and all(isinstance(v, str) and v.strip() for v in r.values()) for r in rows), "G1 every row is {name,url}, both non-blank")
    urls = [r.get("url", "") for r in rows if isinstance(r, dict)]
    names = [r.get("name", "") for r in rows if isinstance(r, dict)]
    ok(all(u.startswith(HOST) and u.endswith("/") for u in urls), "G1 every url is https on the Pages host, with its trailing slash")
    ok(len({u.lower() for u in urls}) == len(urls), "G1 no duplicate urls")
    ok(len({n.lower() for n in names}) == len(names), "G1 no duplicate names")
    ok(HOST in urls, "G1 the root site is in the list")

    g = gen(root)
    top = {"projects": [{"name": "A", "slug": "a", "deploy_name": "a", "has_dist": True},
                        {"name": "B", "slug": "b", "deploy_name": "b/", "has_dist": True},
                        {"name": "Unbuilt", "slug": "u", "deploy_name": "u", "has_dist": False}]}
    d = g.derive(top)
    ok([r["url"] for r in d] == [HOST, HOST + "a/", HOST + "b/"], "G2 derive: root first, one row per built project, unbuilt skipped", str(d))
    for label, bad in (("url", {"projects": top["projects"][:1] * 2}),
                       ("name", {"projects": [top["projects"][0], dict(top["projects"][1], name="a")]})):
        try:
            g.derive(bad); raised = False
        except ValueError:
            raised = True
        ok(raised, "G2 derive refuses a duplicate %s" % label)
    try:
        g.derive({"projects": [{"name": "X", "slug": "x", "has_dist": True}]}); raised = False
    except ValueError:
        raised = True
    ok(raised, "G2 derive refuses a project with no deploy_name")
    ok("front-topology.json" in open(os.path.join(root, "ac_cloud-browser/tools/gen-github-pages.py"), encoding="utf-8").read(), "G2 the source is the front project's list")

    fav = build_json(root)["ui"]["browser"]["favourites"]
    # #1422 the Pages file reaches the seed through the derived catalog (tools/gen-favourites-catalog.py)
    src = [s for s in fav.get("filters", []) if s.get("from") == "github_pages"]
    catgen = open(os.path.join(root, "ac_cloud-browser/tools/gen-favourites-catalog.py"), encoding="utf-8").read()
    ok(len(src) == 1 and 'read("ac_cloud-browser/data/github_pages.json")' in catgen,
       "G3 favourites.sources carries the GitHub Pages file")
    ok(fav.get("version", 0) >= 2, "G3 the seed version moved, so version-1 users receive the new entries")
    ok(not any("github.io" in e.get("url", "") for e in fav.get("extra", [])), "G3 no Pages link is typed into `extra`")

    s = kotlin(root, LIB, APP)
    sug = s.get(LIB + "/BrowserSuggest.kt", ""); panel = s.get(LIB + "/BrowserSuggestPanel.kt", "")
    sheets = s.get(LIB + "/BrowserSheets.kt", ""); frag = s.get(LIB + "/BrowserHostFragment.kt", "")
    ok("favourites = bookmarks.all()" in frag, "G4 the address bar searches the bookmarks")
    ok('Title("Favorites")' in panel and panel.find('Title("History")') < panel.find('Title("Favorites")'), "G4 Favorites is the third section")
    ok("BrowserFavourites.suggest(" in sug, "G4 the ranking lives in BrowserFavourites")
    ok("BrowserFavourites.filter(BrowserFavourites.byFilter(sections, chip), q)" in sheets and 'testTag("browser:fav:search")' in sheets, "G4 the Fav page filters live")

main("github pages fav", check, [
    ("the file stops parsing", "ac_cloud-browser/data/github_pages.json", "[\n  {", "[\n  {,", "parses"),
    ("a duplicate url slips in", "ac_cloud-browser/data/github_pages.json", '"url": "https://diegonmarcos.github.io/leafy/"', '"url": "https://diegonmarcos.github.io/"', "no duplicate urls"),
    ("a duplicate name slips in", "ac_cloud-browser/data/github_pages.json", '"name": "Leafy"', '"name": "Diego Marcos"', "no duplicate names"),
    ("a foreign host slips in", "ac_cloud-browser/data/github_pages.json", '"url": "https://diegonmarcos.github.io/leafy/"', '"url": "https://evil.example/leafy/"', "Pages host"),
    ("a site loses its trailing slash", "ac_cloud-browser/data/github_pages.json", '"url": "https://diegonmarcos.github.io/leafy/"', '"url": "https://diegonmarcos.github.io/leafy"', "trailing slash"),
    ("the generator accepts duplicates", "ac_cloud-browser/tools/gen-github-pages.py", "raise ValueError(\"duplicate %s: %s\"", "print(\"duplicate %s: %s\"", "refuses a duplicate"),
    ("the generator lists unbuilt projects", "ac_cloud-browser/tools/gen-github-pages.py", "if not p.get(\"has_dist\"):", "if False:", "unbuilt skipped"),
    ("the source is not wired", "ac_cloud-browser/build.json", '"from": "github_pages"', '"from": "other"', "carries the GitHub Pages file"),
    ("the seed version stays at 1", "ac_cloud-browser/build.json", '"version": 3,\n        "catalog"', '"version": 1,\n        "catalog"', "seed version moved"),
    ("a link is typed into extra", "ac_cloud-browser/build.json", '"title": "Linktree",\n            "url": "https://linktree.diegonmarcos.com",', '"title": "Leafy",\n            "url": "https://diegonmarcos.github.io/leafy/",', "typed into"),
    ("the bookmarks leave the address bar", LIB + "/BrowserHostFragment.kt", "favourites = bookmarks.all()", "favourites = emptyList()", "searches the bookmarks"),
    ("Favorites moves before History", LIB + "/BrowserSuggestPanel.kt", 'if (sections.favourites.isNotEmpty()) { add(Title("Favorites"))', 'if (sections.favourites.isNotEmpty()) { add(Title("History"))', "third section"),
    ("the Fav page stops filtering", LIB + "/BrowserSheets.kt", "BrowserFavourites.filter(BrowserFavourites.byFilter(sections, chip), q)", "BrowserFavourites.byFilter(sections, chip)", "filters live"),
])
PYEOF
