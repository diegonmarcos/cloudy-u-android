#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════╗
# ║ #1458 Fav's "GH Pages" chip: fed at runtime by front-html        ║
# ╚══════════════════════════════════════════════════════════════════╝
#
#   H1 build.json's GH Pages filter is `from: pages_hub` with a raw.githubusercontent.com `feed` in diegonmarcos/front-html
#      (pages.json); no baked list: no data/github_pages.json, no tools/gen-github-pages.py, no `github_pages` source,
#      and no Pages link typed into `extra`; the seed version moved so the new chip's rows merge in
#   H2 the derived catalog carries the chip and its feed URL and no GH Pages rows
#   H3 libs:browser FavFeed reads the feed with a cache fallback (last good copy kept, a bad download never replaces it),
#      the host starts it after the baked seed, and FavFilter/FavSeed.parse carry `feed`
#   H4 FavFeedTest pins the pages.json parsing as behaviour
set -eu
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
export ROOT
cd "$(dirname "$0")"
python3 - <<'PYEOF'
import json, os, sys
sys.path.insert(0, ".")
from browser_tester import LIB, APP, kotlin, build_json, main

FEED = "https://raw.githubusercontent.com/diegonmarcos/front-html/main/pages.json"

def check(root, ok):
    fav = build_json(root)["ui"]["browser"]["favourites"]
    gh = [f for f in fav["filters"] if f.get("id") == "gh_pages"]
    ok(len(gh) == 1 and gh[0].get("from") == "pages_hub" and gh[0].get("feed") == FEED, "H1 GH Pages is the pages_hub filter with the front-html feed")
    ok(not any(f.get("from") == "github_pages" for f in fav["filters"]), "H1 no build-time github_pages source")
    ok(not any(os.path.exists(os.path.join(root, "ac_cloud-browser", p)) for p in ("data/github_pages.json", "tools/gen-github-pages.py", "test/test-browser-github-pages.sh")),
       "H1 the old baked list, its generator and its test are gone")
    ok(not any("github.io" in e.get("url", "") for e in fav.get("extra", [])), "H1 no Pages link typed into `extra`")
    ok(fav.get("version", 0) >= 4, "H1 the seed version moved")
    cat = json.load(open(os.path.join(root, "ac_cloud-browser/app/src/main/assets/favourites_catalog.json"), encoding="utf-8"))
    f = [x for x in cat["filters"] if x["id"] == "gh_pages"]
    ok(len(f) == 1 and f[0].get("feed") == FEED and not any(i["group"].startswith("GH Pages") for i in cat["items"]), "H2 the catalog carries the chip and the feed, no rows")
    s = kotlin(root, LIB, APP)
    feed = s.get(LIB + "/FavFeed.kt", ""); fav_kt = s.get(LIB + "/BrowserFavourites.kt", ""); frag = s.get(LIB + "/BrowserHostFragment.kt", "")
    ok("sp.edit().putString(cacheKey, text)" in feed and "if (parse(text, f.label).isNotEmpty())" in feed and "apply(sp.getString(cacheKey, null))" in feed,
       "H3 the last good copy is cached, applied at once, and only a valid download replaces it")
    ok("FavFeed.start(ctx, favSeed, bookmarks)" in frag and frag.find("bookmarks.applySeed(favSeed)") < frag.find("FavFeed.start("), "H3 the host starts the feed after the baked seed")
    ok('e.optString("feed")' in fav_kt and "val feed: String?" in fav_kt, "H3 the filter carries its feed")
    t = os.path.join(root, "ac_cloud-browser/app/src/test/java/com/diegonmarcos/cloudbrowser/FavFeedTest.kt")
    ok(os.path.exists(t) and "FavFeed.parse(" in open(t, encoding="utf-8").read(), "H4 FavFeedTest exists")

main("pages hub fav", check, [])
PYEOF
