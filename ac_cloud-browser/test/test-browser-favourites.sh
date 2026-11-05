#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════╗
# ║ #893 Fav: the island's first item, the bookmarks in two views    ║
# ╚══════════════════════════════════════════════════════════════════╝
#
#   F1 the island is exactly Fav, Tabs, Browser, Search, Configs, each a ui.sections id; MainActivity routes
#      favourites to the host's openFavourites(); the icon exists.
#   F2 Fav IS the bookmarks (one store): openFavourites shows showBookmarks, the page menu's bookmark toggle and
#      "bookmarks" action use it; the screen has list + grid views, tap opens, long-press edits/moves/deletes.
#   F3 the view choice persists (BrowserBookmarks.setViewMode / viewMode over the pure normView).
#   F4 the seed is DECLARED in build.json (sources = the fleet's service data, no typed links), expanded by
#      app/build.gradle, parsed by BrowserConfig and applied once by BrowserBookmarks.applySeed.
#   F5 the old "← Tabs" button at the top left of the bar is gone (Tabs is the island's).
set -eu
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
export ROOT
cd "$(dirname "$0")"
python3 - <<'PYEOF'
import json, os, sys
sys.path.insert(0, ".")
from browser_tester import LIB, APP, kotlin, build_json, main

def check(root, ok):
    src = kotlin(root, LIB, APP)
    frag = src.get(LIB + "/BrowserHostFragment.kt", "")
    sheets = src.get(LIB + "/BrowserSheets.kt", "")
    bm = src.get(LIB + "/BrowserBookmarks.kt", "")
    fav = src.get(LIB + "/BrowserFavourites.kt", "")
    cfg = src.get(LIB + "/BrowserConfig.kt", "")
    main_ = src.get(APP + "/MainActivity.kt", "")
    bj = build_json(root); ui = bj["ui"]
    ok(ui.get("bottom_nav") == ["weby", "cloudy", "browser", "tabs", "domains"], "F1 the island is Weby, Cloudy, Browser, Tabs, Domains (#1493); Fav stays a declared section")
    ids = [s["id"] for s in ui.get("sections", [])]
    ok(all(b in ids for b in ui.get("bottom_nav", [])), "F1 every island item is a section")
    ok('"favourites" -> host.openFavourites()' in main_, "F1 MainActivity routes Fav to the host")
    ok(os.path.exists(os.path.join(root, "ac_cloud-browser/app/src/main/res/drawable/ic_nav_favourites.xml")), "F1 the Fav icon exists")
    ok("fun openFavourites() = showBookmarks()" in frag, "F2 Fav opens the bookmarks")
    ok('"bookmarks" -> { showBookmarks()' in frag and "bookmarks.add(url, wv.title" in frag, "F2 the menu's bookmark toggle and list share the one store")
    ok("BrowserFavScreen(" in frag and "fun BrowserFavScreen(" in sheets, "F2 the Favourites screen is drawn")
    ok("VIEW_GRID" in sheets and "BrowserFavourites.gridRows(g.items).forEach { row ->" in sheets and "BrowserFavourites.letter(" in sheets, "F2 grid view of tiles (favicon or first letter)")
    ok(sheets.count("onLongPress = { acting") >= 2 and "onEdit" in sheets and "onDelete" in sheets and "Folder" in sheets, "F2 long-press edits, moves and deletes")
    ok("onTap = { onOpen(b) }" in sheets, "F2 a tap opens the favourite")
    ok("fun setViewMode(v: String) { sp.edit().putString(VIEW" in bm and "fun viewMode(" in bm and "BrowserFavourites.normView(" in bm, "F3 the view choice persists")
    ok("bookmarks.setViewMode(view)" in frag and "bookmarks.viewMode()" in frag, "F3 the screen reads and writes it")
    ok("fun merge(" in fav and "if (it.url in seen) continue" in fav, "F4 the seed is applied once per entry")
    ok("bookmarks.applySeed(favSeed)" in frag and "fun applySeed(" in bm, "F4 the seed is applied at start")
    ok("favourites = FavSeed.parse(" in cfg, "F4 the config parses the baked seed")
    fd = ui["browser"].get("favourites", {})
    ok(fd.get("catalog") == "app/src/main/assets/favourites_catalog.json" and {"services_public", "services_private", "pages_hub"} <= {x.get("from") for x in fd.get("filters", [])}, "F4 the seed comes from the fleet's service data (and the runtime GitHub Pages hub)")
    ok(not any("password" in json.dumps(fd).lower() for _ in [0]), "F4 no credentials in the seed")
    g = open(os.path.join(root, "ac_cloud-browser/app/build.gradle"), encoding="utf-8").read()
    ok("favDecl.items = favItems.unique" in g and "favDecl.remove('sources')" in g and "favDecl.remove('catalog')" in g, "F4 gradle expands the sources into items")
    ok('" ← Tabs "' not in frag and "text = \" ← Tabs" not in frag, "F5 the top-left Tabs button is gone")

FRAG = LIB + "/BrowserHostFragment.kt"
main("favourites", check, [
    ("Weby leaves the island", "ac_cloud-browser/build.json", '"bottom_nav": [\n      "weby",\n      "cloudy",\n      "browser",', '"bottom_nav": [\n      "cloudy",\n      "browser",', "F1 the island is"),
    ("the island order changes", "ac_cloud-browser/build.json", '"bottom_nav": [\n      "weby",\n      "cloudy",\n      "browser",', '"bottom_nav": [\n      "weby",\n      "browser",\n      "cloudy",', "F1 the island is"),
    ("Fav stops routing", APP + "/MainActivity.kt", '"favourites" -> host.openFavourites()', '"favourites" -> Unit', "routes Fav"),
    ("Fav opens something else", FRAG, "fun openFavourites() = showBookmarks()", "fun openFavourites() = showHistory()", "opens the bookmarks"),
    ("the grid view goes", LIB + "/BrowserSheets.kt", "BrowserFavourites.gridRows(g.items).forEach { row ->", "listOf(g.items).forEach { row ->", "grid view"),
    ("long-press goes", LIB + "/BrowserSheets.kt", "onLongPress = { acting = b; title = b.title; folder = b.folder }) }\n                                .testTag(\"browser:fav:tile\")", "onLongPress = { }) }\n                                .testTag(\"browser:fav:tile\")", "long-press"),
    ("the view is not persisted", LIB + "/BrowserBookmarks.kt", "fun setViewMode(v: String) { sp.edit()", "fun setViewMode(v: String) { sp.edit().clear()", "persists"),
    ("the screen forgets the choice", FRAG, "bookmarks.setViewMode(view)", "Unit", "reads and writes"),
    ("the seed re-adds every launch", LIB + "/BrowserFavourites.kt", "if (it.url in seen) continue", "", "once per entry"),
    ("the seed is not applied", FRAG, "bookmarks.applySeed(favSeed)", "Unit", "applied at start"),
    ("the seed is typed in, not derived", "ac_cloud-browser/build.json", '"from": "services_private"', '"from": "typed"', "fleet's service data"),
    ("gradle stops expanding", "ac_cloud-browser/app/build.gradle", "favDecl.items = favItems.unique", "favDecl.itemz = favItems.unique", "expands the sources"),
    ("the Tabs button returns", FRAG, "        // Address bar: a plain field.", "        bar.addView(TextView(ctx).apply { text = \" ← Tabs \" })\n        // Address bar: a plain field.", "top-left Tabs button"),
])
PYEOF
