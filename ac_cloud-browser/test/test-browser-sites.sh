#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════╗
# ║ #1493 Weby | Cloudy | Domains: declarative, derived, scraper-free ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# The rules (Weby derived from the SuperApp phone apps with equal folders, history per day, visited sort, per-site actions)
# are JVM-tested in sites/WebyTest, CloudyTest and DomainsTest. This holds the wiring:
#   W1 app/build.gradle bakes SITES_DECL_B64 from the SuperApp phone_app_groups + data/phone-webapps.json, the declared repos
#      and the Cloud-Webserver's build.json; no copy of any of them lives in the app (no asset, no typed list)
#   W2 the three sections and their pages are declared (build.json ui.sections) with their words and actions (ui.weby / cloudy / domains)
#   W3 the page code is data-driven: dense KitStoreRow rows, no sp literal, no site or app name in Kotlin
#   W4 the Scrapers are gone from the app: no libs:scraper / libs:apify module or dependency, no scraper code or tab in MainActivity
#   W5 the new prefs are declared in the fleet manifest
set -eu
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
export ROOT
cd "$(dirname "$0")"
python3 - <<'PYEOF'
import json, os, re, sys
sys.path.insert(0, ".")
from browser_tester import LIB, APP, MANIFEST, kotlin, build_json, main

SITES = APP + "/sites"
GRADLE = "ac_cloud-browser/app/build.gradle"

def check(root, ok):
    bj = build_json(root)
    ui = bj["ui"]
    gradle = re.sub(r"(?m)^\s*//[^\n]*", "", open(os.path.join(root, GRADLE), encoding="utf-8").read())
    src = kotlin(root, SITES)
    main_kt = kotlin(root, APP)[APP + "/MainActivity.kt"]
    # W1
    ok("aa_cloud-superapp" in gradle and "phone_app_groups" in gradle and "'data/phone-webapps.json'" in gradle, "W1 Weby is derived from the SuperApp phone apps and phone-webapps.json")
    ok("repos_declared.json" in gradle and "ac_cloud-c3-webserver/build.json" in gradle and "SITES_DECL_B64" in gradle, "W1 Cloudy reads the declared repos and the Cloud-Webserver's declaration")
    ok(not os.path.exists(os.path.join(root, "ac_cloud-browser/app/src/main/assets/phone-webapps.json")), "W1 no copy of the SuperApp declaration in the app")
    # W2
    secs = {s["id"]: s for s in ui["sections"]}
    ok(ui["bottom_nav"] == ["weby", "cloudy", "browser", "tabs", "domains"], "W2 the island is Weby | Cloudy | Browser | Tabs | Domains")
    ok([p["id"] for p in secs["cloudy"]["pages"]] == ["gh_pages", "webserver", "private_urls", "public_urls"], "W2 Cloudy has GH Pages | Local Webserver | Cloudy Private URLs | Cloudy Public URLs")
    ok([p["id"] for p in secs["domains"]["pages"]] == ["headless", "files", "site_perms", "history", "visited"], "W2 Domains has Headless Browsers | Files | Perms&Actions | History | Visited")
    ok([a["id"] for a in ui["domains"]["actions"]] == ["cookies", "media", "cache", "storage", "reset_perms"], "W2 the five site actions are declared")
    ok(ui["domains"]["visited_sorts"][0]["id"] == "name" and ui["domains"]["visited_sorts"][1]["id"] == "last", "W2 Visited sorts by name or last visited")
    ok(ui["cloudy"].get("pages_url", "").endswith("/front-html/main/pages.json"), "W2 the public Pages feed is declared")
    # W3
    ok(src, "W3 the sites code exists")
    for p, t in src.items():
        ok(not re.search(r"\d\s*\.?\s*sp\b|\.sp\b", t), "W3 %s has no sp literal" % os.path.basename(p))
    ui_kt = src.get(SITES + "/SitesUi.kt", "")
    ok("KitStoreRowLine(" in ui_kt and "KitStoreRow." in ui_kt and "KitFileTree" in ui_kt and "LazyVerticalGrid(" in ui_kt, "W3 dense Store rows, the shared file tree and a launcher grid")
    names = ("Gmail", "Claude", "WhatsApp", "github.io", "emulated.app", "termux", "Slack")
    for p, t in src.items():
        ok(not any(n.lower() in t.lower() for n in names), "W3 %s names no site or app" % os.path.basename(p))
    # W4
    mods = bj.get("modules", {})
    ok(not any(m in mods for m in ("libs:scraper", "libs:apify", "libs:media-convert-audio")), "W4 build.json no longer declares the scraper modules")
    ok(not re.search(r"libs:(scraper|apify|media-convert-audio)", re.sub(r"//[^\n]*", "", gradle)), "W4 the app does not depend on the scraper libs")
    ok("scraper" not in main_kt.lower() and "scrapers" not in secs, "W4 MainActivity and the sections have no scraper")
    # W5
    st = json.load(open(os.path.join(root, MANIFEST), encoding="utf-8"))["stores"]
    ok(all(k in st and "ac_cloud-browser" in st[k]["used_by"] for k in ("weby_bookmarks", "browser_visit_log", "cloudy_cache")), "W5 the new prefs are declared")
    ok(all(k in "".join(src.values()) for k in ('"weby_bookmarks"', '"browser_visit_log"', '"cloudy_cache"')), "W5 the code opens exactly those prefs")

B = "ac_cloud-browser/build.json"
main("sites", check, [
    ("Weby stops reading the phone apps", GRADLE, "'data/phone-webapps.json'", "'data/phone-webapps.jsonx'", "W1 Weby is derived"),
    ("a copy of the declaration lands in the assets", "ac_cloud-browser/app/src/main/assets/phone-webapps.json", None, "{}", "W1 no copy"),
    ("a scraper module comes back", B, '"libs:bottomnav": {', '"libs:scraper": {"dir": "x"},\n    "libs:bottomnav": {', "W4 build.json no longer"),
    ("the app depends on libs:apify again", GRADLE, "    implementation project(':libs:ui-kit')", "    implementation project(':libs:ui-kit')\n    implementation project(':libs:apify')", "W4 the app does not depend"),
    ("a site action is dropped", B, '{"id": "media", "label": "Clear media", "detail": "cached images, audio, video"},', "", "W2 the five site actions"),
    ("a sp literal appears", APP + "/sites/SitesUi.kt", "private fun rel(", "private val bad = 12.sp\nprivate fun rel(", "W3 SitesUi.kt has no sp literal"),
    ("a site name appears in Kotlin", APP + "/sites/SitesUi.kt", "private fun rel(", "private val bad = \"Gmail\"\nprivate fun rel(", "W3 SitesUi.kt names"),
    ("a pref is undeclared", MANIFEST, '"weby_bookmarks": {', '"weby_bookmarks_x": {', "W5 the new prefs"),
])
PYEOF
